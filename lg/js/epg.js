// ShaGa IPTV Player for LG webOS — epg.js
// Программа передач (EPG) в формате XMLTV.
//
// Файл у провайдеров огромный (десятки–сотни МБ), поэтому читаем его ПОТОКОМ кусками
// и храним только нужное:
//  - передачи каналов из плейлиста;
//  - от «сутки назад» (1.5.0: у каналов с архивом — от глубины архива) до «сутки вперёд»;
//  - 1.5.0: краткое описание (200 символов) — только у передачи в эфире и следующей;
//    любое другое описание скачивается из файла по запросу по его байтовому месту (1.3.4).
// Каналы сопоставляются по tvg-id, а если он не совпал — по названию.
//
// 1.3.1: saved copy in IndexedDB. A copy younger than 24 h is shown at once; if it is older
// than 12 h a fresh file is downloaded in the background and the old programme stays on screen.
// The first load without a copy publishes the programme every 20 MB while parsing.
// 1.3.1: сохранённая копия в IndexedDB. Копия моложе 24 ч показывается сразу; если она старше
// 12 ч — свежий файл качается в фоне, старая программа остаётся на экране.
// Первая загрузка без копии показывает программу каждые 20 МБ по ходу разбора.
var EPG = {
  state: 'none',      // none | nourl | loading | ready | error
  url: '',
  byKey: {},          // ключ канала -> [{ s, e, t, d }] по возрастанию времени
  loadedAt: 0,
  stats: null,
  aborter: null,
  refreshTimer: null,
  DAY: 24 * 3600 * 1000,
  FWD_MS: 24 * 3600 * 1000,
  back: 0,            // 1.5.0: сколько мс назад сохранено в программе / how far back the guide keeps
  prog: '',           // 1.5.0: «60/190» МБ по ходу загрузки — для строки Настроек
  onProgress: null,
  REFRESH_MS: 12 * 3600 * 1000,
  MAX_AGE_MS: 24 * 3600 * 1000,
  TIMEOUT_MS: 30 * 60000,   // 1.3.2: было 180 с — файл 250+ МБ на загруженном ТВ не успевал
  onUpdate: null,     // вызывается, когда программа загрузилась

  // Ключ канала: tvg-id, а без него — нормализованное название
  key: function (ch) { return ch.tvgId || ('n:' + this.norm(ch.name)); },

  norm: function (s) {
    return String(s || '').toLowerCase()
      .replace(/ё/g, 'е')
      .replace(/[^a-zа-я0-9+]/g, '');
  },

  // 1.5.0: глубина архива каналов (ключ канала -> дни) — столько дней программы хранится назад
  depthMap: function (channels) {
    var self = this, pl = Playlists.byId(App.plId), arch = pl && pl.arch, map = {};
    (channels || []).forEach(function (ch) {
      var info = Catchup.info(ch, arch);
      if (!info) return;
      var k = self.key(ch);
      if (!map[k] || map[k] < info.d) map[k] = info.d;
    });
    return map;
  },

  // Сколько мс назад нужно самому глубокому каналу: сутки или архив (не больше 14 дней)
  maxBack: function (depth) {
    var d = 1;
    Object.keys(depth).forEach(function (k) { if (depth[k] > d) d = depth[k]; });
    return Math.min(d, 14) * this.DAY;
  },

  // Ссылка на EPG из плейлиста. «only4;iptvx.one» и прочие не-ссылки не подходят.
  // 1.5.0: «;» тоже разделяет ссылки
  pickUrl: function (meta) {
    var list = String((meta && meta.epg) || '').split(/[,;\s]+/);
    for (var i = 0; i < list.length; i++) if (/^https?:\/\//i.test(list[i])) return list[i];
    return '';
  },

  // Saved copy: shown as soon as the playlist is on screen (before the video starts)
  // Сохранённая копия: показываем сразу, как только плейлист на экране (до старта видео)
  loadCached: function (meta) {
    var self = this, url = this.pickUrl(meta);
    if (!url || (url === this.url && this.state === 'ready')) return Promise.resolve(false);
    if (url !== this.url) { this.byKey = {}; this.state = 'none'; this.url = url; this.loadedAt = 0; }
    var t0 = Date.now();
    var p = this.cacheP = Cache.getKey('epg').then(function (c) {
      if (self.url !== url || self.state === 'ready') return false;
      // v5 (1.5.0): старые дни у каналов с архивом, описания — только у текущей и следующей
      if (!c || c.url !== url || !c.byKey || c.v !== 5) return false;
      var age = Date.now() - c.loadedAt;
      if (age >= self.MAX_AGE_MS) { Log.info('EPG: сохранённая копия старше 24 ч, не используется'); return false; }
      self.byKey = c.byKey;
      self.stats = c.stats;
      self.ranges = c.ranges !== false;
      self.chanPos = c.chanPos || {};
      self.fileSize = c.size || 0;
      self.back = c.back || self.DAY;
      self.loadedAt = c.loadedAt;
      self.state = 'ready';
      Log.info('EPG: сохранённая копия за ' + (Date.now() - t0) + ' мс, ей ' + Math.round(age / 60000) + ' мин; каналов с программой ' +
        (c.stats ? c.stats.channels : '?'));
      self.notify();
      return true;
    }).then(function (ok) { self.cacheP = null; return ok; }, function () { self.cacheP = null; return false; });
    return p;
  },

  // force — загрузить, даже если свежая программа уже есть
  load: function (meta, channels, force) {
    var self = this, url = this.pickUrl(meta);
    // Сначала дождаться чтения сохранённой копии — может, качать и не нужно
    if (this.cacheP) { this.cacheP.then(function () { self.load(meta, channels, force); }); return; }
    if (url && !force && url === this.url && this.state === 'ready' && !this.loading) {
      var age = Date.now() - this.loadedAt;
      // 1.5.0: архив стал глубже, чем хранится программа (поменяли настройку) — скачать заново
      var needBack = this.maxBack(this.depthMap(channels));
      if (needBack > this.back) Log.info('EPG: архив ' + needBack / this.DAY + ' дн., программа хранит ' + Math.round(this.back / this.DAY) + ' — скачать заново');
      else if (age < this.REFRESH_MS) {
        // Свежая копия уже на экране — следующее обновление по расписанию
        this.scheduleRefresh(meta, channels, this.REFRESH_MS - age);
        return;
      }
      Log.info('EPG: копии ' + Math.round(age / 60000) + ' мин — обновление в фоне');
    }
    if (!url) {
      this.state = 'nourl';
      Log.info('EPG: в плейлисте нет ссылки на программу передач (url-tvg = «' +
        ((meta && meta.epg) || '') + '»)');
      this.notify();
      return;
    }
    if (!force && url === this.url && this.loading) return;
    if (this.aborter) { try { this.aborter.abort(); } catch (e) {} }

    var seq = this.seq = (this.seq || 0) + 1;   // ответ устаревшей загрузки игнорируется
    if (url !== this.url) { this.byKey = {}; this.state = 'none'; }
    this.url = url;
    if (this.state !== 'ready') this.state = 'loading';   // старая программа остаётся на экране
    this.loading = true;
    this.notify();
    var hadOld = this.state === 'ready';
    // Первая загрузка без старой программы — показываем разобранное по ходу (каждые 20 МБ)
    var partial = hadOld ? null : function (byKey) {
      if (seq !== self.seq || self.state === 'ready') return;
      self.byKey = byKey;
      self.notify();
    };
    this.fetchParse(url, channels, null, partial).then(function (res) {
      if (seq !== self.seq) return;
      self.byKey = res.byKey;
      self.stats = res.stats;
      self.chanPos = res.chanPos;
      self.fileSize = res.size;
      self.back = res.back;
      self.prog = '';
      self.state = 'ready';
      self.loading = false;
      self.loadedAt = Date.now();
      self.notify();
      self.saveCopy();
      self.scheduleRefresh(meta, channels, self.REFRESH_MS);
    }).catch(function (err) {
      if (seq !== self.seq) return;
      self.loading = false;
      self.prog = '';
      var msg = err && err.name === 'AbortError' ? 'нет ответа за ' + self.TIMEOUT_MS / 1000 + ' с'
        : (err && err.message ? err.message : String(err));
      if (self.state === 'ready') {
        Log.error('EPG: обновление не удалось (' + msg + '), старая программа остаётся, повтор через 30 мин');
        self.scheduleRefresh(meta, channels, 30 * 60000);
      } else {
        Log.error('EPG: ' + msg);
        self.byKey = {};
        self.state = 'error';
      }
      self.notify();
    });
  },

  saveCopy: function () {
    var t0 = Date.now(), url = this.url;
    Cache.setKey('epg', { v: 5, back: this.back, url: url, loadedAt: this.loadedAt, byKey: this.byKey, stats: this.stats, ranges: this.ranges, chanPos: this.chanPos, size: this.fileSize }).then(function () {
      Log.info('EPG: копия сохранена за ' + (Date.now() - t0) + ' мс');
    });
  },

  scheduleRefresh: function (meta, channels, ms) {
    var self = this;
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(function () { self.load(meta, channels, true); }, ms);
  },

  notify: function () { if (this.onUpdate) this.onUpdate(); },

  // opts.gz — распаковывать gzip, opts.enc — кодировка. Если по первым байтам видно,
  // что угадали неверно, загрузка перезапускается с правильными настройками.
  // partial(byKey) — показать разобранное по ходу загрузки (первая загрузка)
  fetchParse: function (url, channels, opts, partial) {
    opts = opts || {};
    var self = this, t0 = Date.now();
    var tz = -new Date().getTimezoneOffset() / 60;
    if (!opts.retry) {
      Log.info('EPG: время ТВ ' + new Date().toString().slice(0, 24) + ', пояс UTC' + (tz >= 0 ? '+' : '') + tz +
        '; каналов в плейлисте ' + channels.length + ', из них с tvg-id ' +
        channels.filter(function (c) { return c.tvgId; }).length);
    }
    // 1.5.0: окно назад — по глубине архива каждого канала (сутки у каналов без архива)
    var depth = this.depthMap(channels), back = this.maxBack(depth), DAY = this.DAY;
    var now = Date.now(), from = now - back, to = now + this.FWD_MS, descN = {};

    // Какие каналы нам нужны. 1.5.0: tvg-name (если есть) — имя для поиска канала в программе
    var wantId = {}, nameKey = {};
    channels.forEach(function (ch) {
      var k = self.key(ch);
      if (ch.tvgId) wantId[ch.tvgId] = k;
      nameKey[self.norm(ch.tvgName || ch.name)] = k;
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

    // 1.3.4: байтовое место каждой передачи в файле — для полного описания по запросу
    var chanPos = {}, cidStr = {};
    var enc8 = window.TextEncoder ? new TextEncoder() : null;
    var byteOk = !!enc8, bufByte = 0;
    function blen(x) { return enc8.encode(x).length; }

    function handleProg(attrs, body, at) {
      st.progs++;
      // 1.3.7: порядок каналов в файле (место первой передачи канала) — для поиска сдвинувшейся передачи
      var cid0 = attr(attrs, 'channel');
      if (at >= 0 && chanPos[cid0] == null) { cidStr[cid0] = own(cid0); chanPos[cidStr[cid0]] = at; }
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
      // 1.5.0: старше глубины архива этого канала (сутки без архива)
      if (e <= now - Math.min(depth[k] || 1, 14) * DAY) { st.outWindow++; return; }
      var tm = /<title[^>]*>([\s\S]*?)<\/title>/.exec(body);
      var p = { s: s, e: e, t: tm ? decode(tm[1]) : '' };
      // 1.5.0: краткое описание (200 символов) — только у передачи в эфире и следующей;
      // остальные — из файла по запросу (1.3.4)
      if (e > now && (descN[k] || 0) < 2) {
        descN[k] = (descN[k] || 0) + 1;
        var dm = /<desc[^>]*>([\s\S]*?)<\/desc>/.exec(body);
        if (dm) { var dd = decode(dm[1]); p.d = dd.length > 200 ? own(dd.slice(0, 197)) + '...' : dd; }
      }
      if (at >= 0) { p.o = at; p.c = cidStr[cid] || (cidStr[cid] = own(cid)); }
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
      var cur = bufByte, last = 0;
      while ((m = re.exec(seg))) {
        if (m[1] === 'programme') {
          var at = -1;
          if (byteOk) { cur += blen(seg.slice(last, m.index)); last = m.index; at = cur; }
          handleProg(m[2], m[3], at);
        } else handleChannel(m[2], m[3]);
      }
      if (byteOk) bufByte = cur + blen(seg.slice(last));
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
      var total = +(res.headers.get('content-length') || 0);
      var stream = res.body;
      var gz = opts.gz != null ? opts.gz : /\.gz(\?|#|$)/i.test(url);
      var sniffed = false;
      if (!stream) {
        if (gz) throw new Error('нет потокового чтения для .gz');
        return res.text().then(function (txt) { st.bytes = txt.length; buf = txt; processBuf(true); });
      }
      // Заголовок Accept-Ranges с другого сервера может быть не виден (CORS) — просто пробуем Range,
      // а если сервер ответит 200 вместо 206, больше не пробуем
      self.ranges = true;
      if (gz || (opts.enc && opts.enc !== 'utf-8')) byteOk = false;   // места в сжатом/перекодированном файле не годятся
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
            if (total && Math.floor(st.bytes / 10485760) !== Math.floor((st.bytes - v.length) / 10485760)) {
              self.prog = Math.round(st.bytes / 1048576) + '/' + Math.round(total / 1048576);
              if (self.onProgress) self.onProgress();
            }
            buf += dec.decode(v, { stream: true });
            if (buf.length > 262144) processBuf(false);
            if (st.bytes >= st.nextLog) {
              st.nextLog += 20971520;
              Log.info('EPG: … ' + Math.round(st.bytes / 1048576) + ' МБ за ' + (Date.now() - t0) + ' мс; каналов ' +
                st.epgChannels + ', передач ' + st.progs + ', сохранено ' + st.kept);
              if (partial) partial(byKey);
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
        return self.fetchParse(url, channels, restart, partial);
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
      var size = st.bytes;
      return { byKey: byKey, stats: { channels: withEpg }, chanPos: byteOk ? chanPos : {}, size: byteOk ? size : 0, back: back };
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

  // Все передачи канала (от глубины архива до завтра)
  all: function (ch) { return this.list(ch) || []; },

  // 1.5.0: передачи, закончившиеся после from (0 — последние сутки); more — есть и более старые
  range: function (ch, from) {
    var l = this.list(ch) || [], out = [], more = false;
    from = from || (Date.now() - this.DAY);
    for (var i = 0; i < l.length; i++) { if (l[i].e <= from) more = true; else out.push(l[i]); }
    return { list: out, more: more, from: from };
  },

  // 1.5.0: канал файла -> ключ канала плейлиста (по местам передач в файле)
  cidKeys: function () {
    var out = {}, by = this.byKey;
    Object.keys(by).forEach(function (key) {
      var l = by[key];
      for (var i = 0; i < l.length; i++) if (l[i].c != null) { if (!out[l[i].c]) out[l[i].c] = key; break; }
    });
    return out;
  },

  // 1.5.0: поиск в описаниях. Описаний в памяти нет, поэтому весь файл читается заново через сеть.
  // Кусок без совпадений пропускается сразу. lo — ключ канала -> самое раннее окончание передачи (мс).
  // onProg(мб, всего) — ход; ctrl.stop() — остановить. Ответ: { found: [{ key, p }], total, stopped, err }
  scanDesc: function (rx, lo, onProg, ctrl) {
    var self = this, url = this.url, cid = this.cidKeys(), hi = Date.now() + this.FWD_MS;
    var out = { found: [], total: 0, stopped: false, err: '' };
    var ab = window.AbortController ? new AbortController() : null, stopped = false;
    ctrl.stop = function () { stopped = true; out.stopped = true; if (ab) ab.abort(); };
    var buf = '', bytes = 0, total = 0, lastMb = -1;
    function scan(seg) {
      if (!rx.test(seg)) return;
      var re = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g, m;
      while ((m = re.exec(seg))) {
        if (!rx.test(m[2])) continue;
        var cm = /\bchannel\s*=\s*["']([^"']*)/.exec(m[1]), key = cm && cid[cm[1]];
        if (!key || lo[key] == null) continue;
        var dm = /<desc[^>]*>([\s\S]*?)<\/desc>/.exec(m[2]);
        if (!dm) continue;
        var d = decode(dm[1]);
        if (!rx.test(d)) continue;
        var sm = /\bstart\s*=\s*["']([^"']*)/.exec(m[1]), em = /\bstop\s*=\s*["']([^"']*)/.exec(m[1]);
        var s = sm ? parseTime(sm[1]) : 0, e = em ? parseTime(em[1]) : s + 1800000;
        if (!s || e <= lo[key] || s >= hi) continue;
        out.total++;
        if (out.found.length >= 5000) continue;
        var tm = /<title[^>]*>([\s\S]*?)<\/title>/.exec(m[2]);
        out.found.push({ key: key, p: { s: s, e: e, t: tm ? decode(tm[1]) : '', d: d.length > 200 ? d.slice(0, 197) + '...' : d } });
      }
    }
    return fetch(url, ab ? { signal: ab.signal } : {}).then(function (res) {
      if (!res.ok || !res.body) throw new Error('HTTP ' + res.status);
      total = +(res.headers.get('content-length') || 0);
      var reader = res.body.getReader(), dec = new TextDecoder('utf-8');
      return new Promise(function (resolve, reject) {
        function pump() {
          reader.read().then(function (r) {
            if (r.done) { scan(buf + dec.decode()); buf = ''; resolve(); return; }
            bytes += r.value.length;
            buf += dec.decode(r.value, { stream: true });
            if (buf.length > 4194304) {
              var cut = buf.lastIndexOf('</programme>') + 12;
              if (cut > 12) { scan(buf.slice(0, cut)); buf = buf.slice(cut); }
            }
            var mb = Math.floor(bytes / 1048576);
            if (mb !== lastMb) { lastMb = mb; onProg(mb, Math.round(total / 1048576)); }
            pump();
          }).catch(reject);
        }
        pump();
      });
    }).then(function () { return out; }, function (err) {
      if (stopped) return out;
      out.err = (err && err.message) || 'сеть';
      return out;
    });
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

  // ---------- Полное описание по запросу (1.3.4) ----------
  // Просим у сервера ~16 КБ вокруг места передачи в файле (HTTP Range), проверяем, что это
  // та же передача (начало и название), берём весь <desc>. Иначе — короткий текст из памяти.
  // Ответ: { d, src: 'network'|'memory', why }
  // 1.3.7: провайдер пересобирает файл каждые несколько часов, передачи сдвигаются. Если передачи
  // нет на месте — ищем: в каждом куске видны передачи, чьи старые места известны (по ним берём
  // сдвиг), и понятно, раньше цель или позже (порядок каналов в файле сохраняется, передачи идут
  // по времени). До 10 запросов / 6 с.
  fullDesc: function (p) {
    var self = this, short = { d: p.d || '', src: 'memory', why: '' };
    if (!this.ranges) { short.why = 'сервер не отдаёт куски файла'; return Promise.resolve(short); }
    if (p.o == null) { short.why = 'нет места в файле'; return Promise.resolve(short); }
    var t0 = Date.now(), tries = 0, lo = 0, guess = p.o;
    var hi = this.fileSize ? Math.round(this.fileSize * 1.33) + 1048576 : -1;
    var chanPos = this.chanPos || {}, rankT = p.c != null ? chanPos[p.c] : null;
    var enc = window.TextEncoder ? new TextEncoder() : null;
    var known = this.knownByCid();

    function fail(why) { short.why = why; return short; }

    function step() {
      if (tries >= 10 || Date.now() - t0 > 6000) return fail('не найдено за ' + tries + ' запросов');
      tries++;
      var a = Math.max(0, lo, guess - 2048), b = a + 18431;
      var ctrl = window.AbortController ? new AbortController() : null;
      var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, 4000);
      return fetch(self.url, { headers: { Range: 'bytes=' + a + '-' + b }, signal: ctrl ? ctrl.signal : undefined })
        .then(function (res) {
          if (res.status === 416) { clearTimeout(timer); return null; }
          if (res.status !== 206) {
            if (ctrl) ctrl.abort();
            if (res.status === 200) self.ranges = false;   // отдаёт весь файл — кусками не умеет
            throw new Error('HTTP ' + res.status);
          }
          return res.text();
        }).then(function (txt) {
          clearTimeout(timer);
          if (txt === null) {                               // за концом файла (он стал короче)
            if (hi < 0 || hi > a) hi = a;
            guess = lo + Math.floor((hi - lo) / 2);
            return step();
          }
          var re = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g, m, n = 0;
          var before = false, after = false, delta = null;
          var sMin = 0, sMax = 0, sN = 0, nearPs = 0, nearAt = 0;   // передачи того же канала в куске
          while ((m = re.exec(txt))) {
            n++;
            var st = /\bstart\s*=\s*["']([^"']*)/.exec(m[1]);
            var cm = /\bchannel\s*=\s*["']([^"']*)/.exec(m[1]);
            var ps = st ? parseTime(st[1]) : 0, cid = cm ? cm[1] : '';
            if (ps === p.s && (p.c == null || cid === p.c)) {
              var tm = /<title[^>]*>([\s\S]*?)<\/title>/.exec(m[2]);
              if (tm && decode(tm[1]) === p.t) {
                var dm = /<desc[^>]*>([\s\S]*?)<\/desc>/.exec(m[2]);
                Log.info('EPG: полное описание из файла за ' + (Date.now() - t0) + ' мс, запросов ' + tries);
                var full = dm ? decode(dm[1]) : '';
                // 1.5.0: краткий текст остаётся в памяти — колонка и окно получат его сразу
                if (full && !p.d) p.d = full.length > 200 ? full.slice(0, 197) + '...' : full;
                return { d: full, src: 'network', why: '' };
              }
            }
            if (p.c != null && cid === p.c) {
              if (ps < p.s) before = true; else after = true;
              sN++;
              if (!sMin || ps < sMin) sMin = ps;
              if (ps > sMax) sMax = ps;
              if (!nearPs || Math.abs(ps - p.s) < Math.abs(nearPs - p.s)) { nearPs = ps; nearAt = a + Math.round(m.index / txt.length * 18432); }
            }
            else if (rankT != null && chanPos[cid] != null) { if (chanPos[cid] < rankT) before = true; else after = true; }
            if (delta === null && enc && known[cid] && known[cid][ps] != null) {
              delta = a + enc.encode(txt.slice(0, m.index)).length - known[cid][ps];
            }
          }
          if (before && after) return fail('передачи уже нет в файле');
          if (n === 0 && a < 2097152) before = true;        // ещё раздел каналов
          else if (n === 0) after = true;                   // пустой кусок — за концом данных
          if (before) lo = b - 4096;
          if (after && (hi < 0 || hi > a + 4096)) hi = a + 4096;
          var g = delta !== null ? p.o + delta : -1;
          // Тот же канал, но соседей с известным местом нет: прикидываем по времени
          // (сколько передач до цели) × (средний размер передачи в куске)
          if (g < 0 && sN >= 2 && sMax > sMin) {
            g = Math.round(nearAt + (p.s - nearPs) / ((sMax - sMin) / (sN - 1)) * (18432 / n));
          }
          if (g >= 0 && (g < a || g > b) && g >= lo && (hi < 0 || g < hi)) guess = g;
          else if (hi >= 0) {
            if (hi - lo < 4096) return fail('передача не найдена в файле');
            guess = lo + Math.floor((hi - lo) / 2);
          } else return fail('файл изменился (искать не по чему)');
          return step();
        });
    }

    return step().catch(function (err) {
      return fail(err && err.name === 'AbortError' ? 'нет ответа за 4 с' : (err && err.message) || 'сеть');
    });
  },

  // Старые места известных передач: { cid: { начало: байт } }. Строится один раз на программу.
  knownByCid: function () {
    if (this._knownSrc === this.byKey && this._known) return this._known;
    var k = {}, by = this.byKey;
    Object.keys(by).forEach(function (key) {
      by[key].forEach(function (p) {
        if (p.c != null && p.o != null) (k[p.c] || (k[p.c] = {}))[p.s] = p.o;
      });
    });
    this._known = k;
    this._knownSrc = by;
    return k;
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
