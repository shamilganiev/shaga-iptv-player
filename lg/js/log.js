// Журнал событий. Хранит последние 500 записей в localStorage (переживает перезапуск),
// токены и пароли в ссылках заменяются на ***.
//
// Как получить журнал:
//   ares-inspect com.shaga.player --open
//   в консоли DevTools:  copy(Log.text())   — журнал окажется в буфере обмена компьютера
var Log = {
  MAX: 500,
  KEY: 'log',
  buf: [],
  saveTimer: null,
  t0: Date.now(),

  init: function () {
    try { this.buf = JSON.parse(localStorage.getItem(this.KEY) || '[]') || []; } catch (e) { this.buf = []; }
    // Записи из 0.1.1 могли содержать ключ в пути — прогоняем через новый фильтр
    var self0 = this;
    this.buf = this.buf.map(function (line) { return self0.mask(line); });
    this.add('info', '===== запуск приложения =====');
    var self = this;

    window.addEventListener('error', function (e) {
      self.add('error', 'JS: ' + e.message + ' @ ' + (e.filename || '').split('/').pop() + ':' + e.lineno);
    });
    window.addEventListener('unhandledrejection', function (e) {
      var r = e.reason;
      self.add('error', 'Promise: ' + (r && r.message ? r.message : JSON.stringify(r)));
    });
    ['error', 'warn'].forEach(function (lvl) {
      var orig = console[lvl];
      console[lvl] = function () {
        try { self.add(lvl, Array.prototype.map.call(arguments, String).join(' ')); } catch (e) {}
        return orig.apply(console, arguments);
      };
    });
  },

  // Убираем из текста всё, что похоже на ключи доступа
  mask: function (s) {
    var self = this;
    return String(s)
      .replace(/(https?:\/\/[^\/\s?#]+)(\/[^\s?#"']*)/gi, function (_, host, path) { return host + self.maskPath(path); })
      .replace(/([?&;](?:token|key|password|pass|pwd|auth|sid|session|signature|sig|username|user|login|mac)=)[^&\s"']+/gi, '$1***')
      .replace(/(\/\/)[^\/\s:@]+:[^\/\s@]+@/g, '$1***:***@');
  },

  // Короткая ссылка для журнала: сервер + путь без ключей и без параметров
  url: function (u) {
    var m = /^(https?:\/\/[^\/?#]+)([^?#]*)/i.exec(u || '');
    return m ? m[1] + this.maskPath(m[2]) + (/[?]/.test(u) ? '?…' : '') : this.mask(u);
  },

  // Ключ бывает прямо в пути: /pl/AbC123xYz/list.m3u8. Сегмент, похожий на случайный
  // ключ (8+ символов, есть и буквы, и цифры, нет точки), заменяется на ***.
  // Номера каналов (12101) и имена файлов (index.m3u8) остаются.
  maskPath: function (path) {
    // Xtream Codes: /live/логин/пароль/123.ts (так же movie, series, timeshift)
    path = String(path).replace(/^\/(live|movie|series|timeshift)\/[^\/]+\/[^\/]+\//i, '/$1/***/***/');
    return path.replace(/[^\/]+/g, function (seg) {
      var key = seg.length >= 8 && seg.indexOf('.') < 0 && /[a-z]/i.test(seg) && /\d/.test(seg);
      var long = seg.length >= 24 && seg.indexOf('.') < 0;   // длинные хеши без цифр тоже
      return key || long ? '***' : seg;
    });
  },

  add: function (level, msg) {
    var d = new Date();
    var p = function (n, w) { n = String(n); while (n.length < (w || 2)) n = '0' + n; return n; };
    var ts = p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' +
      p(d.getMinutes()) + ':' + p(d.getSeconds()) + '.' + p(d.getMilliseconds(), 3);
    this.buf.push(ts + ' ' + level.toUpperCase().slice(0, 4) + ' ' + this.mask(msg));
    if (this.buf.length > this.MAX) this.buf.splice(0, this.buf.length - this.MAX);
    this.scheduleSave();
  },

  info: function (m) { this.add('info', m); },
  warn: function (m) { this.add('warn', m); },
  error: function (m) { this.add('error', m); },

  scheduleSave: function () {
    var self = this;
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(function () {
      self.saveTimer = null;
      try { localStorage.setItem(self.KEY, JSON.stringify(self.buf)); } catch (e) {}
    }, 1000);
  },

  clear: function () {
    this.buf = [];
    try { localStorage.removeItem(this.KEY); } catch (e) {}
    this.add('info', 'журнал очищен');
  },

  device: function () {
    var info = '';
    try { if (window.PalmSystem && PalmSystem.deviceInfo) info = PalmSystem.deviceInfo; } catch (e) {}
    return info || navigator.userAgent;
  },

  text: function () {
    var head = [
      'ShaGa Player ' + (window.App ? App.version : '?'),
      'Устройство: ' + this.device(),
      'Экран: ' + window.innerWidth + 'x' + window.innerHeight,
      'Журнал выгружен: ' + new Date().toString(),
      '----------------------------------------'
    ];
    return head.concat(this.buf).join('\n');
  }
};

Log.init();
