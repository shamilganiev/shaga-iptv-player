// Программа передач (EPG) в формате XMLTV.
//
// Файл у провайдеров огромный (десятки–сотни МБ), поэтому читаем его ПОТОКОМ кусками
// и храним только нужное:
//  - передачи каналов из плейлиста;
//  - в окне «2 часа назад — сутки вперёд»;
//  - описания — только у передач ближайших 4 часов.
// Каналы сопоставляются по tvg-id, а если он не совпал — по названию.
var EPG = {
  state: 'none',      // none | nourl | loading | ready | error
  url: '',
  byKey: {},          // ключ канала -> [{ s, e, t, d }] по возрастанию времени
  loadedAt: 0,
  stats: null,
  aborter: null,
  refreshTimer: null,
  BACK_MS: 2 * 3600 * 1000,
  FWD_MS: 24 * 3600 * 1000,
  DESC_MS: 4 * 3600 * 1000,
  REFRESH_MS: 6 * 3600 * 1000,
  TIMEOUT_MS: 180000,
  onUpdate: null,     // вызывается, когда программа загрузилась

  // Ключ канала: tvg-id, а без него — нормализованное название
  key: function (ch) { return ch.tvgId || ('n:' + this.norm(ch.name)); },

  norm: function (s) {
    return String(s || '').toLowerCase()
      .replace(/ё/g, 'е')
      .replace(/[^a-zа-я0-9+]/g, '');
  },

  // Ссылка на EPG из плейлиста. «only4;iptvx.one» и прочие не-ссылки не подходят.
  pickUrl: function (meta) {
    var list = String((meta && meta.epg) || '').split(/[,\s]+/);
    for (var i = 0; i < list.length; i++) if (/^https?:\/\//i.test(list[i])) return list[i];
    return '';
  },

  // force — загрузить, даже если свежая программа уже есть
  load: function (meta, channels, force) {
    var self = this, url = this.pickUrl(meta);
    if (!url) {
      this.state = 'nourl';
      Log.info('EPG: в плейлисте нет ссылки на программу передач (url-tvg = «' +
        ((meta && meta.epg) || '') + '»)');
      this.notify();
      return;
    }
    if (!force && url === this.url) {
      if (this.loading) return;
      if (this.state === 'ready' && Date.now() - this.loadedAt < this.REFRESH_MS) return;
    }
    if (this.aborter) { try { this.aborter.abort(); } catch (e) {} }

    var seq = this.seq = (this.seq || 0) + 1;   // ответ устаревшей загрузки игнорируется
    if (url !== this.url) { this.byKey = {}; this.state = 'none'; }
    this.url = url;
    if (this.state !== 'ready') this.state = 'loading';   // старая программа остаётся на экране
    this.loading = true;
    this.notify();
    this.fetchParse(url, channels).then(function (res) {
      if (seq !== self.seq) return;
      self.byKey = res.byKey;
      self.stats = res.stats;
      self.state = 'ready';
      self.loading = false;
      self.loadedAt = Date.now();
      self.notify();
      self.scheduleRefresh(meta, channels);
    }).catch(function (err) {
      if (seq !== self.seq) return;
      self.loading = false;
      var msg = err && err.name === 'AbortError' ? 'нет ответа за ' + self.TIMEOUT_MS / 1000 + ' с'
        : (err && err.message ? err.message : String(err));
      Log.error('EPG: ' + msg);
      if (self.state !== 'ready') self.state = 'error';
      self.notify();
    });
  },

  scheduleRefresh: function (meta, channels) {
    var self = this;
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(function () { self.load(meta, channels, true); }, this.REFRESH_MS);
  },

  notify: function () { if (this.onUpdate) this.onUpdate(); },

  // opts.gz — распаковывать gzip, opts.enc — кодировка. Если по первым байтам видно,
  // что угадали неверно, загрузка перезапускается с правильными настройками.
  fetchParse: function (url, channels, opts) {
    opts = opts || {};
    var self = this, t0 = Date.now();
    var tz = -new Date().getTimezoneOffset() / 60;
    if (!opts.retry) {
      Log.info('EPG: время ТВ ' + new Date().toString().slice(0, 24) + ', пояс UTC' + (tz >= 0 ? '+' : '') + tz +
        '; каналов в плейлисте ' + channels.length + ', из них с tvg-id ' +
        channels.filter(function (c) { return c.tvgId; }).length);
    }
    var now = Date.now(), from = now - this.BACK_MS, to = now + this.FWD_MS, descTo = now + this.DESC_MS;

    // Какие каналы нам нужны
    var wantId = {}, nameKey = {};
    channels.forEach(function (ch) {
      var k = self.key(ch);
      if (ch.tvgId) wantId[ch.tvgId] = k;
      nameKey[self.norm(ch.name)] = k;
    });

    var alias = {};          // id из EPG -> ключ канала
    var byKey = {};
    var st = { bytes: 0, epgChannels: 0, byId: 0, byName: 0, progs: 0, kept: 0,
               noChan: 0, badTime: 0, outWindow: 0, minS: 0, maxS: 0, nextLog: 20971520 };
    var sampleEpgIds = [], sampleNoChan = [];

    function handleChannel(attrs, body) {
      var id = attr(attrs, 'id');
      if (!id) return;
      st.epgChannels++;
      if (sampleEpgIds.length < 6) sampleEpgIds.push(id);
      if (wantId[id]) { alias[id] = wantId[id]; st.byId++; return; }
      var re = /<display-name[^>]*>([\s\S]*?)<\/display-name>/g, m;
      while ((m = re.exec(body))) {
        var k = nameKey[self.norm(decode(m[1]))];
        if (k) { alias[id] = k; st.byName++; return; }
      }
    }

    function handleProg(attrs, body) {
      st.progs++;
      var cid = attr(attrs, 'channel');
      var k = alias[cid] || wantId[cid];
      if (!k) {
        st.noChan++;
        if (sampleNoChan.length < 4 && sampleNoChan.indexOf(cid) < 0) sampleNoChan.push(cid);
        return;
      }
      var s = parseTime(attr(attrs, 'start')), e = parseTime(attr(attrs, 'stop'));
      if (!s || !e) {
        if (st.badTime++ === 0) Log.warn('EPG: не разобрано время: start="' + attr(attrs, 'start') + '"');
        return;
      }
      if (!st.minS || s < st.minS) st.minS = s;
      if (s > st.maxS) st.maxS = s;
      if (e <= from || s >= to) { st.outWindow++; return; }
      var tm = /<title[^>]*>([\s\S]*?)<\/title>/.exec(body);
      var p = { s: s, e: e, t: tm ? decode(tm[1]) : '' };
      if (s < descTo && e > now - 3600 * 1000) {
        var dm = /<desc[^>]*>([\s\S]*?)<\/desc>/.exec(body);
        if (dm) p.d = decode(dm[1]).slice(0, 600);
      }
      (byKey[k] || (byKey[k] = [])).push(p);
      st.kept++;
    }

    // Разбираем только законченные элементы; хвост ждёт следующего куска
    var buf = '';
    function processBuf(final) {
      var cut = final ? buf.length
        : Math.max(buf.lastIndexOf('</programme>') + 12, buf.lastIndexOf('</channel>') + 10);
      if (cut < 12) return;
      var seg = buf.slice(0, cut);
      buf = buf.slice(cut);
      var re = /<(programme|channel)\b([^>]*)>([\s\S]*?)<\/\1>/g, m;
      while ((m = re.exec(seg))) {
        if (m[1] === 'programme') handleProg(m[2], m[3]);
        else handleChannel(m[2], m[3]);
      }
    }

    var aborter = window.AbortController ? new AbortController() : null;
    this.aborter = aborter;
    var timer = setTimeout(function () { if (aborter) aborter.abort(); }, this.TIMEOUT_MS);
    Log.info('EPG: загрузка ' + Log.url(url) + (opts.retry ? ' (повтор: ' + opts.retry + ')' : ''));
    var restart = null;   // { gz, enc } — если нужно начать заново

    return fetch(url, aborter ? { signal: aborter.signal } : {}).then(function (res) {
      var h = function (n) { return res.headers.get(n) || '—'; };
      Log.info('EPG: ответ HTTP ' + res.status + ' за ' + (Date.now() - t0) + ' мс; тип ' + h('content-type') +
        ', размер ' + h('content-length') + ', сжатие ' + h('content-encoding'));
      if (!res.ok) throw new Error('HTTP ' + res.status);
      var stream = res.body;
      var gz = opts.gz != null ? opts.gz : /\.gz(\?|#|$)/i.test(url);
      var sniffed = false;
      if (!stream) {
        if (gz) throw new Error('нет потокового чтения для .gz');
        return res.text().then(function (txt) { st.bytes = txt.length; buf = txt; processBuf(true); });
      }
      if (gz) {
        if (!window.DecompressionStream) throw new Error('нет поддержки .gz');
        stream = stream.pipeThrough(new DecompressionStream('gzip'));
      }
      var reader = stream.getReader(), dec = new TextDecoder(opts.enc || 'utf-8');
      return new Promise(function (resolve, reject) {
        function pump() {
          reader.read().then(function (r) {
            if (r.done) { buf += dec.decode(); processBuf(true); resolve(); return; }
            var v = r.value;
            if (!sniffed) {
              sniffed = true;
              // gzip без .gz в ссылке: первые байты 1F 8B
              if (!gz && v[0] === 0x1f && v[1] === 0x8b) {
                Log.warn('EPG: файл сжат gzip, перезапуск с распаковкой');
                restart = { gz: true }; reader.cancel(); resolve(); return;
              }
              var head = new TextDecoder(opts.enc || 'utf-8').decode(v.slice(0, 400));
              Log.info('EPG: начало файла: ' + head.replace(/\s+/g, ' ').slice(0, 200));
              var em = /encoding\s*=\s*["']([^"']+)["']/i.exec(head);
              var enc = em ? em[1].toLowerCase() : 'utf-8';
              if (!opts.enc && enc !== 'utf-8' && enc !== 'utf8') {
                Log.warn('EPG: кодировка ' + enc + ', перезапуск');
                restart = { gz: gz, enc: enc }; reader.cancel(); resolve(); return;
              }
            }
            st.bytes += v.length;
            buf += dec.decode(v, { stream: true });
            if (buf.length > 262144) processBuf(false);
            if (st.bytes >= st.nextLog) {
              st.nextLog += 20971520;
              Log.info('EPG: … ' + Math.round(st.bytes / 1048576) + ' МБ за ' + (Date.now() - t0) + ' мс; каналов ' +
                st.epgChannels + ', передач ' + st.progs + ', сохранено ' + st.kept);
            }
            pump();
          }).catch(reject);
        }
        pump();
      });
    }).then(function () {
      clearTimeout(timer);
      if (restart) {
        restart.retry = restart.gz ? 'gzip' : restart.enc;
        return self.fetchParse(url, channels, restart);
      }
      var keys = Object.keys(byKey);
      keys.forEach(function (k) { byKey[k].sort(function (a, b) { return a.s - b.s; }); });
      var withEpg = channels.filter(function (ch) { return byKey[self.key(ch)]; }).length;
      st.ms = Date.now() - t0;
      Log.info('EPG: ' + Math.round(st.bytes / 1048576 * 10) / 10 + ' МБ за ' + st.ms + ' мс; в файле ' +
        st.epgChannels + ' каналов, совпало по id ' + st.byId + ', по названию ' + st.byName +
        '; передач ' + st.progs + ', сохранено ' + st.kept + '; с программой ' + withEpg + ' из ' + channels.length);
      // Почему передачи не попали в программу
      Log.info('EPG: отброшено — канала нет в плейлисте ' + st.noChan + ', вне окна по времени ' + st.outWindow +
        ', неразобранное время ' + st.badTime);
      if (st.minS) {
        Log.info('EPG: в файле передачи с ' + new Date(st.minS).toString().slice(0, 21) + ' по ' +
          new Date(st.maxS).toString().slice(0, 21) + ' (окно: ' + new Date(from).toString().slice(0, 21) +
          ' – ' + new Date(to).toString().slice(0, 21) + ')');
      }
      if (!st.epgChannels && !st.progs) Log.warn('EPG: в файле не найдено ни <channel>, ни <programme> — это не XMLTV?');
      if (st.byId + st.byName === 0 || withEpg < channels.length / 2) {
        var miss = channels.filter(function (c) { return c.tvgId && !byKey[self.key(c)]; })
          .slice(0, 6).map(function (c) { return c.tvgId; });
        Log.info('EPG: примеры id в файле: ' + sampleEpgIds.join(', ') + '; в плейлисте без программы: ' + miss.join(', ') +
          (sampleNoChan.length ? '; передачи для неизвестных id: ' + sampleNoChan.join(', ') : ''));
      }
      return { byKey: byKey, stats: { channels: withEpg } };
    }, function (err) {
      clearTimeout(timer);
      throw err;
    });

    function attr(s, name) {
      var m = new RegExp('\\b' + name + '\\s*=\\s*(?:"([^"]*)"|\'([^\']*)\')').exec(s);
      return m ? (m[1] != null ? m[1] : m[2]) : '';
    }
  },

  // Почему у канала нет текущей передачи — пишется в журнал один раз на канал
  diagLogged: {},
  diagnose: function (ch) {
    if (this.state !== 'ready' || this.diagLogged[ch.n]) return;
    this.diagLogged[ch.n] = 1;
    var l = this.list(ch), k = this.key(ch);
    var why = !l ? 'нет передач с ключом «' + k + '»' + (ch.tvgId ? '' : ' (у канала нет tvg-id, искали по названию)')
      : 'передач ' + l.length + ', ближайшие ' + new Date(l[0].s).toString().slice(4, 21) + ' – ' +
        new Date(l[l.length - 1].e).toString().slice(4, 21) + ', сейчас ни одна не идёт';
    Log.info('EPG: канал ' + ch.n + ' «' + ch.name + '» без программы: ' + why);
  },

  // ---------- Запросы ----------
  list: function (ch) { return this.byKey[this.key(ch)] || null; },

  // Текущая передача и индекс в списке
  nowIdx: function (ch) {
    var l = this.list(ch), now = Date.now();
    if (!l) return -1;
    for (var i = 0; i < l.length; i++) if (l[i].s <= now && now < l[i].e) return i;
    return -1;
  },

  now: function (ch) {
    var i = this.nowIdx(ch);
    return i < 0 ? null : this.list(ch)[i];
  },

  next: function (ch, n) {
    var l = this.list(ch), now = Date.now(), out = [];
    if (!l) return out;
    for (var i = 0; i < l.length && out.length < n; i++) if (l[i].s >= now) out.push(l[i]);
    return out;
  },

  progress: function (p) {
    if (!p) return 0;
    return Math.max(0, Math.min(1, (Date.now() - p.s) / (p.e - p.s)));
  },

  hm: function (ms) {
    var d = new Date(ms), h = d.getHours(), m = d.getMinutes();
    return (h < 10 ? '0' : '') + h + ':' + (m < 10 ? '0' : '') + m;
  }
};

// «20261107030000 +0300» -> миллисекунды
function parseTime(s) {
  var m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-])?(\d{2})?(\d{2})?/.exec(s || '');
  if (!m) return 0;
  var ms = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  if (m[7]) ms -= (m[7] === '-' ? -1 : 1) * ((+m[8]) * 60 + (+(m[9] || 0))) * 60000;
  return ms;
}

// Важно: результат — НОВАЯ строка. Иначе V8 хранит «срез» исходного куска файла,
// и 50 тысяч коротких названий удерживают в памяти весь файл программы (сотни МБ).
function decode(s) {
  return own(String(s)
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&#x([0-9a-f]+);/gi, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(+d); })
    .replace(/&amp;/g, '&')
    .trim());
}

function own(s) { return (' ' + s).slice(1); }
