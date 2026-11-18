// ShaGa IPTV Player for LG webOS — player.js
// Playback: live channels and archive (catchup) / Просмотр: эфир и архив.
// Просмотр. Два движка:
//  native — встроенный плеер webOS через <video src>; HLS он понимает сам, но перед стартом
//           копит несколько сегментов, поэтому может запускаться долго;
//  hlsjs  — библиотека hls.js через Media Source Extensions: начинает с одного сегмента
//           у края эфира и даёт подробную диагностику.
// Движок выбирается в настройках; время старта пишется в журнал для сравнения.
var Player = {
  el: null,
  video: null,
  hls: null,
  list: [],        // список, из которого запустили канал (группа) — по нему ходят CH+/CH−
  idx: 0,
  gid: '__all',
  ch: null,
  engine: 'native',
  state: 'idle',   // idle | loading | playing | error
  suspended: false,
  stallTimer: null,
  bufTimer: null,
  bannerTimer: null,
  acceptsDigits: true,
  STALL_MS: 45000,
  waitT: 0,        // когда началась буферизация
  stages: {},      // этапы старта, которые уже записаны в журнал
  startedCbs: [],  // кто ждёт первого кадра (фоновое обновление плейлиста)
  // Archive: own clock, because the stream position of shift/catchup streams follows the wall clock.
  // Архив: свои часы — позиция у shift/catchup-потоков идёт по часам, а не по кадрам.
  // { s, e (секунды UTC), t (название), base (сек от начала передачи), acc, run, since }
  arch: null,
  // 1.5.0: присмотр за архивом: перезапуск при ошибке и буферизации > 25 с (до 3 раз подряд),
  // следующая передача, когда закончилась текущая
  archRetry: 0,     // перезапусков подряд
  archEver: false,  // архив хоть раз пошёл (иначе его просто нет)
  archAuto: false,  // следующий playArchive — перезапуск/перемотка, а не новый выбор
  archOkAt: 0,      // когда картинка пошла в последний раз
  errText: '',      // своя строка ошибки на плашке
  BUF_MS: 25000,

  HLS_CONFIG: {
    enableWorker: true,
    lowLatencyMode: false,
    liveSyncDurationCount: 2,      // стартуем в 2 сегментах от края эфира
    maxBufferLength: 20,           // не копим больше 20 с впереди — памяти у ТВ мало
    maxMaxBufferLength: 30,
    backBufferLength: 15,
    manifestLoadingTimeOut: 15000,
    levelLoadingTimeOut: 15000,
    fragLoadingTimeOut: 20000
  },

  init: function () {
    var self = this;
    this.el = $('player');
    this.video = $('video');
    var v = this.video;

    // Этапы старта (каждый пишется один раз за запуск канала)
    ['loadstart', 'loadedmetadata', 'loadeddata', 'canplay'].forEach(function (ev) {
      v.addEventListener(ev, function () {
        if (!self.ch || self.state !== 'loading') return;
        self.stage('video:' + ev, ev === 'loadedmetadata' ? v.videoWidth + 'x' + v.videoHeight : '');
      });
    });

    v.addEventListener('playing', function () {
      clearTimeout(self.stallTimer);
      if (self.state === 'loading') {
        Log.info('Видео: ПОШЛО через ' + (Date.now() - self.t0) + ' мс [' + self.engine + '] ' +
          v.videoWidth + 'x' + v.videoHeight);
        self.fireStarted();
      } else if (self.waitT) {
        Log.warn('Видео: буферизация длилась ' + (Date.now() - self.waitT) + ' мс');
      }
      self.waitT = 0;
      clearTimeout(self.spinTimer);
      clearTimeout(self.bufTimer);
      if (self.arch) { self.archEver = true; self.archOkAt = Date.now(); }
      self.clockRun();
      self.setState('playing');
    });
    // 1.5.0: поток архива закончился: в конце передачи — следующая, иначе перезапуск
    v.addEventListener('ended', function () {
      var a = self.arch;
      if (!a || !self.ch) return;
      if (self.archPos() >= a.e - a.s - 120) self.nextProgramme(true);
      else self.archRestart('поток закончился');
    });
    // Каждые 10 с: архивная передача кончилась — следующая (подписи; сам поток идёт дальше)
    setInterval(function () {
      var a = self.arch;
      if (a && self.state === 'playing' && !self.waitT && self.archPos() >= a.e - a.s) self.nextProgramme(false);
    }, 10000);
    v.addEventListener('pause', function () { self.clockStop(); });

    // Значок загрузки — только если пауза дольше 0,7 с: короткие подвисания не мелькают
    v.addEventListener('waiting', function () {
      self.clockStop();
      if (self.state === 'playing' && !self.waitT) {
        self.waitT = Date.now();
        clearTimeout(self.spinTimer);
        self.spinTimer = setTimeout(function () {
          if (self.waitT) $('vspin').classList.add('on');
        }, 700);
        // 1.5.0: архив стоит 25 с — перезапуск с того же места
        clearTimeout(self.bufTimer);
        if (self.arch) self.bufTimer = setTimeout(function () {
          if (self.waitT && self.arch) self.archRestart('буферизация 25 с');
        }, self.BUF_MS);
      }
    });

    v.addEventListener('error', function () {
      if (!self.ch || self.hls) return;   // ошибки hls.js обрабатываются в onHlsError
      var e = v.error, names = { 1: 'ABORTED', 2: 'NETWORK', 3: 'DECODE', 4: 'SRC_NOT_SUPPORTED' };
      Log.error('Видео: ошибка ' + (e ? (names[e.code] || e.code) + (e.message ? ' — ' + e.message : '') : '?') +
        ' на канале ' + self.ch.n + ' ' + Log.url(self.ch.url));
      self.fail();
    });

    $('banner').addEventListener('click', function () { App.show('browser', { fromPlayer: true }); });
  },

  // key — этап (пишется один раз за запуск), detail — подробности
  stage: function (key, detail) {
    if (this.stages[key]) return;
    this.stages[key] = 1;
    Log.info('  ' + key + (detail ? ' ' + detail : '') + ' +' + (Date.now() - this.t0) + ' мс');
  },

  // Вызвать cb, когда пойдёт картинка (или канал упадёт) — один раз
  onceStarted: function (cb) {
    if (!this.ch || this.state === 'playing' || this.state === 'error') { cb(); return; }
    this.startedCbs.push(cb);
  },

  fireStarted: function () {
    var cbs = this.startedCbs;
    this.startedCbs = [];
    cbs.forEach(function (cb) { setTimeout(cb, 0); });
  },

  isCurrent: function (ch) {
    return !!(this.ch && ch && this.ch.url === ch.url);
  },

  play: function (list, idx, gid) {
    this.list = list;
    this.idx = idx;
    this.gid = gid;
    this.start(list[idx]);
  },

  // hls.js — только для HLS-ссылок; .ts, .mp4 и прочее всегда идут во встроенный плеер
  pickEngine: function (url) {
    var want = Config.d.engine === 'hlsjs';
    var isHls = /\.m3u8?(\?|#|$)/i.test(url) || /m3u8/i.test(url);
    var ok = !!(window.Hls && Hls.isSupported());
    if (want && isHls && !ok && !this.warnedNoMse) {
      this.warnedNoMse = true;
      Log.warn('hls.js не поддерживается на этом устройстве (нет MSE), используется встроенный плеер');
    }
    return want && isHls && ok ? 'hlsjs' : 'native';
  },

  // url — своя ссылка (архив), arch — данные архива; без них — прямой эфир
  start: function (ch, url, arch) {
    var self = this, v = this.video;
    this.teardown();
    this.arch = arch || null;
    url = url || ch.url;
    if (App.openLocked !== null && ch.group !== App.openLocked) {
      App.lockTouch[App.openLocked] = Date.now();
      App.openLocked = null;
    }
    this.ch = ch;
    this.suspended = false;
    this.waitT = 0;
    this.errText = '';
    this.stages = {};
    this.hlsRetries = 0;
    if (!this.arch) {                     // последний канал запоминается только для эфира
      Config.d.last = { name: ch.name, gid: this.gid };
      Config.save();
    }
    document.body.classList.add('has-video');

    this.engine = this.pickEngine(url);
    this.t0 = Date.now();
    Log.info((this.arch ? 'Архив' : 'Канал') + ' ' + ch.n + ' «' + ch.name + '» [' + (ch.group || '-') + '] ' +
      Log.url(url) + ' — движок ' + this.engine);
    this.setState('loading');

    if (this.engine === 'hlsjs') this.startHls(url);
    else {
      v.src = url;
      v.load();
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
    }

    clearTimeout(this.stallTimer);
    this.stallTimer = setTimeout(function () {
      if (self.state === 'loading') {
        Log.error('Видео: нет картинки за ' + self.STALL_MS / 1000 + ' с [' + self.engine + ']');
        self.fail();
      }
    }, this.STALL_MS);
  },

  startHls: function (url) {
    var self = this, v = this.video, E = Hls.Events;
    var hls = new Hls(this.HLS_CONFIG);
    this.hls = hls;

    hls.on(E.MANIFEST_PARSED, function (_, d) {
      self.stage('hls:manifest', d.levels.length + ' качеств');
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
    });
    hls.on(E.LEVEL_LOADED, function (_, d) {
      var det = d.details;
      self.stage('hls:level', 'сегменты по ' + det.targetduration + ' с, в списке ' + det.fragments.length +
        (det.live ? ', эфир' : ''));
    });
    hls.on(E.FRAG_LOADED, function (_, d) {
      var st = d.frag.stats || d.stats || {};
      var bytes = st.total || st.loaded || 0;
      self.stage('hls:первый сегмент', Math.round(bytes / 1024) + ' КБ');
    });
    hls.on(E.FRAG_BUFFERED, function () { self.stage('hls:первый сегмент в буфере'); });
    hls.on(E.ERROR, function (_, d) { self.onHlsError(d); });

    hls.loadSource(url);
    hls.attachMedia(v);
  },

  onHlsError: function (d) {
    if (!this.hls) return;
    var info = d.type + '/' + d.details + (d.response && d.response.code ? ' HTTP ' + d.response.code : '');
    if (!d.fatal) {
      // Нефатальные ошибки hls.js исправляет сам; пишем только заметные
      if (d.details !== 'bufferStalledError') Log.warn('hls.js: ' + info);
      return;
    }
    Log.error('hls.js: фатальная ' + info);
    // Не загрузился сам плейлист канала (404, таймаут, мусор вместо m3u8) — повтор не поможет
    var code = d.response && d.response.code;
    if (/^manifest/.test(d.details) || (code >= 400 && code < 500)) { this.fail(); return; }
    if (this.hlsRetries < 2 && d.type === Hls.ErrorTypes.NETWORK_ERROR) {
      this.hlsRetries++;
      Log.info('hls.js: повтор загрузки (' + this.hlsRetries + ')');
      this.hls.startLoad();
    } else if (this.hlsRetries < 2 && d.type === Hls.ErrorTypes.MEDIA_ERROR) {
      this.hlsRetries++;
      Log.info('hls.js: восстановление декодера (' + this.hlsRetries + ')');
      this.hls.recoverMediaError();
    } else {
      this.fail();
    }
  },

  // ---------- Архив ----------
  // Передача из архива: s, e — миллисекунды (как в EPG), offset — секунды от начала передачи
  playArchive: function (list, idx, gid, prog, offset) {
    var ch = list[idx], pl = Playlists.byId(App.plId);
    var info = Catchup.info(ch, pl && pl.arch);
    if (!info) return false;
    var s = Math.floor(prog.s / 1000), e = Math.floor(prog.e / 1000), now = Math.floor(Date.now() / 1000);
    offset = offset || 0;
    var url = Catchup.url(ch.url, info, s + offset, e, now);
    // Новая передача (выбрана или следующая): перезапуски считаются заново
    if (!this.archAuto) { this.archRetry = 0; this.archEver = false; }
    this.archAuto = false;
    this.list = list;
    this.idx = idx;
    this.gid = gid;
    Log.info('Архив ' + info.t + ': ' + EPG.hm(prog.s) + '–' + EPG.hm(prog.e) + ' +' + offset + ' с');
    this.start(ch, url, { s: s, e: e, t: prog.t, base: offset, acc: 0, run: false, since: 0 });
    return true;
  },

  clockRun: function () {
    var a = this.arch;
    if (a && !a.run) { a.run = true; a.since = Date.now(); }
  },

  clockStop: function () {
    var a = this.arch;
    if (a && a.run) { a.acc += (Date.now() - a.since) / 1000; a.run = false; }
  },

  // Секунды от начала передачи
  archPos: function () {
    var a = this.arch;
    if (!a) return 0;
    return Math.floor(a.base + a.acc + (a.run ? (Date.now() - a.since) / 1000 : 0));
  },

  // Перемотка в архиве — перезапуском потока с нового места (как в Roku)
  seekArchive: function (delta) {
    var a = this.arch;
    if (!a || !this.ch) return;
    var now = Math.floor(Date.now() / 1000), p = this.archPos() + delta;
    var maxP = Math.min(a.e - a.s - 30, now - 30 - a.s);
    p = Math.max(0, Math.min(p, maxP));
    this.archAuto = true;    // та же передача — не новый выбор
    this.playArchive(this.list, this.idx, this.gid, { s: a.s * 1000, e: a.e * 1000, t: a.t }, p);
  },

  // 1.5.0: перезапуск архива с того же места, до 3 раз подряд (счёт сбрасывается после 2 минут
  // нормального показа). Передача, которая так и не пошла, не перезапускается — её архива нет.
  archRestart: function (why) {
    if (!this.arch || !this.archEver) return false;
    if (Date.now() - this.archOkAt > 120000) this.archRetry = 0;
    if (this.archRetry >= 3) { Log.warn('Архив: ' + why + ' — 3 перезапуска не помогли'); return false; }
    this.archRetry++;
    Log.info('Архив: ' + why + ' — перезапуск ' + this.archRetry + '/3 с +' + this.archPos() + ' с');
    this.seekArchive(0);
    return true;
  },

  // 1.5.0: следующая передача после архивной. play — поток закончился, включить её;
  // иначе поток идёт дальше сам (shift), меняются только подписи, часы продолжают идти.
  nextProgramme: function (play) {
    var a = this.arch, ch = this.ch;
    if (!a || !ch) return;
    var list = EPG.all(ch), nx = null;
    for (var i = 0; i < list.length; i++) if (list[i].s >= a.e * 1000 - 60000) { nx = list[i]; break; }
    if (!nx) { if (play) Log.info('Архив: следующей передачи в программе нет'); return; }
    if (play) {
      Log.info('Архив: следующая передача «' + nx.t + '»');
      if (nx.s >= Date.now() - 60000) this.start(ch);
      else this.playArchive(this.list, this.idx, this.gid, nx, 0);
      return;
    }
    var ns = Math.floor(nx.s / 1000);
    a.base -= ns - a.s;
    a.s = ns;
    a.e = Math.floor(nx.e / 1000);
    a.t = nx.t;
    Log.info('Архив: теперь «' + nx.t + '» ' + EPG.hm(nx.s) + '–' + EPG.hm(nx.e));
    this.drawBanner();
    if (App.screen === Browser) Browser.afterPlay();
  },

  // 1.5.0: кнопка Stop — перезапуск потока (эфир) или архива с того же места
  restart: function () {
    if (!this.ch) return;
    if (this.arch) { Log.info('Архив: перезапуск по кнопке'); this.archRetry = 0; this.archAuto = true; this.seekArchive(0); }
    else { Log.info('Перезапуск потока по кнопке'); this.start(this.ch); }
  },

  mmss: function (sec) {
    sec = Math.max(0, Math.floor(sec));
    var h = Math.floor(sec / 3600), m = Math.floor(sec / 60) % 60, x = sec % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (x < 10 ? '0' : '') + x;
  },

  // Полностью отпустить текущий поток (оба движка)
  teardown: function () {
    this.clockStop();
    clearTimeout(this.stallTimer);
    clearTimeout(this.spinTimer);
    clearTimeout(this.bufTimer);
    this.waitT = 0;
    if (this.hls) {
      try { this.hls.destroy(); } catch (e) {}
      this.hls = null;
    }
    var v = this.video;
    v.pause();
    if (v.getAttribute('src') || v.src) {
      v.removeAttribute('src');
      v.load();
    }
  },

  fail: function () {
    clearTimeout(this.stallTimer);
    if (this.arch && this.archRestart('ошибка')) return;
    // Архив: так и не пошёл — его нет; шёл и оборвался — прервался
    this.errText = this.arch ? t(this.archEver ? 'archBroken' : 'archNoStart') : '';
    this.setState('error');
    this.fireStarted();
  },

  stop: function () {
    this.teardown();
    this.ch = null;
    this.arch = null;
    this.setState('idle');
    document.body.classList.remove('has-video');
    App.touchLock();     // 10 минут без просмотра считаются от остановки
  },

  step: function (d) {
    var n = this.list.length;
    if (n < 2) return;
    var i = (this.idx + d + n) % n;
    var ch = this.list[i], self = this;
    App.guardPlay(ch, function () {
      self.idx = i;
      Browser.fullFrom = 'channels';   // 1.4.1: после переключения возвращаемся в список каналов
      self.start(ch);
    });
  },

  setState: function (s) {
    this.state = s;
    $('vspin').classList.toggle('on', s === 'loading');
    this.drawBanner();
    if (s === 'playing') this.showBanner();
    else if (s !== 'idle') this.showBanner(true);
  },

  drawBanner: function () {
    var ch = this.ch;
    if (!ch) return;
    $('bNum').textContent = ch.n;
    $('bName').textContent = ch.name;
    $('bSub').innerHTML = '';
    $('bSub').appendChild(document.createTextNode(ch.group || t('noGroup')));
    if (App.isFav(ch)) $('bSub').appendChild(document.createTextNode('  ★'));
    var pr = EPG.now(ch), bp = $('bProg'), a = this.arch;
    bp.innerHTML = '';
    if (a) {
      // Архив: «Архив: передача  12:00 – 13:00  5:10 / 60:00»
      bp.appendChild(document.createTextNode(t('archLabel') + ': ' + a.t));
      var at = document.createElement('span');
      at.className = 't';
      at.textContent = EPG.hm(a.s * 1000) + ' – ' + EPG.hm(a.e * 1000) + '   ' +
        this.mmss(this.archPos()) + ' / ' + this.mmss(a.e - a.s);
      bp.appendChild(at);
    } else if (pr) {
      bp.appendChild(document.createTextNode(pr.t));
      var tm = document.createElement('span');
      tm.className = 't';
      tm.textContent = EPG.hm(pr.s) + ' – ' + EPG.hm(pr.e);
      bp.appendChild(tm);
    }
    var st = $('bStatus');
    st.classList.toggle('err', this.state === 'error');
    st.textContent = this.state === 'loading' ? t('stConnecting')
      : this.state === 'error' ? (this.errText || t('stError')) : '';
  },

  // sticky = не прятать (подключение, ошибка)
  showBanner: function (sticky) {
    if (!this.ch || App.screen !== this) return;
    var b = $('banner');
    b.classList.add('on');
    clearTimeout(this.bannerTimer);
    if (!sticky) this.bannerTimer = setTimeout(function () { b.classList.remove('on'); }, 4000);
  },

  enter: function () {
    this.drawBanner();
    this.showBanner(this.state !== 'playing');
  },

  leave: function () {
    clearTimeout(this.bannerTimer);
    $('banner').classList.remove('on');
  },

  // Приложение свернули — останавливаем поток; вернули — подключаемся заново
  // (у прямого эфира нет смысла «продолжать с паузы»).
  onVisibility: function (visible) {
    Log.info(visible ? 'Приложение снова на экране' : 'Приложение свёрнуто');
    if (!visible && this.ch) {
      this.teardown();
      this.suspended = true;
    } else if (visible && this.suspended && this.ch) {
      this.suspended = false;
      // Флаг PIN сброшен при сворачивании — канал из группы «По PIN» сам не включаем
      if (App.isHidden(this.ch.group) || !App.isOpen(this.ch.group)) {
        this.stop();
        if (App.screen === this) App.show('browser');
        return;
      }
      if (this.arch) this.seekArchive(0);
      else this.start(this.ch);
    }
  },

  onKey: function (code) {
    switch (code) {
      // «Как по списку» (по умолчанию): вверх — к меньшему номеру. «Классика»: вверх — к большему.
      case K.CHUP: case K.UP: this.step(Config.d.zapInvert ? -1 : 1); return true;
      case K.CHDN: case K.DOWN: this.step(Config.d.zapInvert ? 1 : -1); return true;
      case K.OK:
        if (this.state === 'error') { if (this.arch) this.seekArchive(0); else this.start(this.ch); }
        else App.show('browser', { fromPlayer: true });
        return true;
      case K.BACK: App.show('browser', { fromPlayer: true }); return true;
      // В архиве: влево/вправо — минута, перемотка — 5 минут
      case K.LEFT: case K.RIGHT:
        if (this.arch) this.seekArchive(code === K.LEFT ? -60 : 60);
        else { this.drawBanner(); this.showBanner(); }
        return true;
      case K.REW: case K.FF:
        if (this.arch) this.seekArchive(code === K.REW ? -300 : 300);
        return true;
      case K.PAUSE: this.clockStop(); this.video.pause(); this.drawBanner(); this.showBanner(true); return true;
      case K.PLAY: if (this.video.paused) { var pp = this.video.play(); if (pp && pp.catch) pp.catch(function () {}); } this.showBanner(); return true;
      case K.YELLOW: if (this.ch) { App.toggleFav(this.ch); this.drawBanner(); this.showBanner(); } return true;
      case K.BLUE: App.show('settings'); return true;
      case K.STOP: this.restart(); return true;     // 1.5.0: Stop — перезапуск потока
    }
    return false;
  }
};
