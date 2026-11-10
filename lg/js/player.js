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
  bannerTimer: null,
  acceptsDigits: true,
  STALL_MS: 45000,
  waitT: 0,        // когда началась буферизация
  stages: {},      // этапы старта, которые уже записаны в журнал
  startedCbs: [],  // кто ждёт первого кадра (фоновое обновление плейлиста)

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
      self.setState('playing');
    });

    // Значок загрузки — только если пауза дольше 0,7 с: короткие подвисания не мелькают
    v.addEventListener('waiting', function () {
      if (self.state === 'playing' && !self.waitT) {
        self.waitT = Date.now();
        clearTimeout(self.spinTimer);
        self.spinTimer = setTimeout(function () {
          if (self.waitT) $('vspin').classList.add('on');
        }, 700);
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

  start: function (ch) {
    var self = this, v = this.video;
    this.teardown();
    if (App.openLocked !== null && ch.group !== App.openLocked) {
      App.lockTouch[App.openLocked] = Date.now();
      App.openLocked = null;
    }
    this.ch = ch;
    this.suspended = false;
    this.waitT = 0;
    this.stages = {};
    this.hlsRetries = 0;
    Config.d.last = { name: ch.name, gid: this.gid };
    Config.save();
    document.body.classList.add('has-video');

    this.engine = this.pickEngine(ch.url);
    this.t0 = Date.now();
    Log.info('Канал ' + ch.n + ' «' + ch.name + '» [' + (ch.group || '-') + '] ' + Log.url(ch.url) +
      ' — движок ' + this.engine);
    this.setState('loading');

    if (this.engine === 'hlsjs') this.startHls(ch.url);
    else {
      v.src = ch.url;
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

  // Полностью отпустить текущий поток (оба движка)
  teardown: function () {
    clearTimeout(this.stallTimer);
    clearTimeout(this.spinTimer);
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
    this.setState('error');
    this.fireStarted();
  },

  stop: function () {
    this.teardown();
    this.ch = null;
    this.setState('idle');
    document.body.classList.remove('has-video');
    App.relockIfLeft(App.screen === Browser ? Browser.gid : null);
  },

  step: function (d) {
    var n = this.list.length;
    if (n < 2) return;
    var i = (this.idx + d + n) % n;
    var ch = this.list[i], self = this;
    App.guardPlay(ch, function () {
      self.idx = i;
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
    var pr = EPG.now(ch), bp = $('bProg');
    bp.innerHTML = '';
    if (pr) {
      bp.appendChild(document.createTextNode(pr.t));
      var tm = document.createElement('span');
      tm.className = 't';
      tm.textContent = EPG.hm(pr.s) + ' – ' + EPG.hm(pr.e);
      bp.appendChild(tm);
    }
    var st = $('bStatus');
    st.classList.toggle('err', this.state === 'error');
    st.textContent = this.state === 'loading' ? t('stConnecting')
      : this.state === 'error' ? t('stError') : '';
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
      if (App.isHidden(this.ch.group)) { this.suspended = false; return; }   // группу скрыли при сворачивании
      this.start(this.ch);
    }
  },

  onKey: function (code) {
    switch (code) {
      // «Как по списку» (по умолчанию): вверх — к меньшему номеру. «Классика»: вверх — к большему.
      case K.CHUP: case K.UP: this.step(Config.d.zapInvert ? -1 : 1); return true;
      case K.CHDN: case K.DOWN: this.step(Config.d.zapInvert ? 1 : -1); return true;
      case K.OK:
        if (this.state === 'error') this.start(this.ch);
        else App.show('browser', { fromPlayer: true });
        return true;
      case K.BACK: App.show('browser', { fromPlayer: true }); return true;
      case K.LEFT: case K.RIGHT: this.drawBanner(); this.showBanner(); return true;
      case K.YELLOW: if (this.ch) { App.toggleFav(this.ch); this.drawBanner(); this.showBanner(); } return true;
      case K.BLUE: App.show('settings'); return true;
      case K.STOP: this.stop(); App.show('browser'); return true;
    }
    return false;
  }
};
