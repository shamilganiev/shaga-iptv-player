// Всё хранится в localStorage телевизора. Ошибки (переполнение, запрет) не роняют приложение.
var Store = {
  get: function (key, def) {
    try {
      var v = localStorage.getItem(key);
      return v == null ? def : JSON.parse(v);
    } catch (e) { return def; }
  },
  set: function (key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); return true; }
    catch (e) { return false; }
  },
  del: function (key) {
    try { localStorage.removeItem(key); } catch (e) {}
  }
};

var Config = {
  d: null,
  defaults: {
    playlists: [],    // [{ id, name, url }] — до 5
    lastOkId: null,   // последний успешно загруженный — открывается при запуске
    nextId: 1,
    lang: null,       // null = автоматически (язык системы)
    favorites: [],    // названия каналов (не ссылки: в ссылках токен, он может смениться)
    hidden: [],       // скрытые группы
    locked: [],       // группы под PIN-кодом
    pin: null,        // хеш PIN-кода
    last: null,       // { name, gid } — последний канал
    engine: 'native', // 'native' — встроенный плеер webOS, 'hlsjs' — hls.js
    zapInvert: true   // вверх/CH+ на весь экран — к меньшему номеру, как по списку
  },
  load: function () {
    var s = Store.get('config', {}) || {};
    for (var k in this.defaults) {
      if (!(k in s)) s[k] = JSON.parse(JSON.stringify(this.defaults[k]));
    }
    // 0.3.x → 0.4.0: одна ссылка source → первый плейлист
    if (s.source && s.source.url && !s.playlists.length) {
      s.playlists.push({ id: 1, name: Text.playlistName(s.source.url), url: s.source.url });
      s.lastOkId = 1;
      s.nextId = 2;
      s.migratedFrom03 = true;
    }
    delete s.source;
    this.d = s;
    return s;
  },
  save: function () { Store.set('config', this.d); }
};

// Это родительский замок, а не защита банковского уровня: хеш нужен лишь,
// чтобы PIN не лежал открытым текстом.
function hashPin(p) {
  var h = 5381;
  for (var i = 0; i < p.length; i++) h = ((h << 5) + h + p.charCodeAt(i)) | 0;
  return 'h' + (h >>> 0).toString(36);
}

// ---------- Плейлисты (до 5), как в Roku-версии ----------
var Playlists = {
  MAX: 5,
  all: function () { return Config.d.playlists; },
  byId: function (id) {
    var l = Config.d.playlists;
    for (var i = 0; i < l.length; i++) if (l[i].id === id) return l[i];
    return null;
  },
  byUrl: function (url) {
    var l = Config.d.playlists;
    for (var i = 0; i < l.length; i++) if (l[i].url === url) return l[i];
    return null;
  },
  add: function (url) {
    var d = Config.d;
    var pl = { id: d.nextId++, name: Text.playlistName(url), url: url };
    d.playlists.push(pl);
    Config.save();
    return pl;
  },
  setUrl: function (pl, url) {
    pl.url = url;
    pl.name = Text.playlistName(url);
    Config.save();
  },
  remove: function (id) {
    var d = Config.d;
    d.playlists = d.playlists.filter(function (p) { return p.id !== id; });
    if (d.lastOkId === id) d.lastOkId = d.playlists.length ? d.playlists[0].id : null;
    Config.save();
  }
};

// ---------- Кэш разобранных плейлистов в IndexedDB ----------
// В localStorage около 5 МБ, а один большой плейлист — около 1 МБ; пять туда не влезут.
var Cache = {
  db: null,
  opening: null,

  open: function () {
    var self = this;
    if (this.db) return Promise.resolve(this.db);
    if (this.opening) return this.opening;
    this.opening = new Promise(function (resolve) {
      try {
        var req = indexedDB.open('shaga', 1);
        req.onupgradeneeded = function () { req.result.createObjectStore('playlists'); };
        req.onsuccess = function () { self.db = req.result; resolve(self.db); };
        req.onerror = function () { Log.warn('Кэш: IndexedDB недоступна'); resolve(null); };
      } catch (e) { Log.warn('Кэш: ' + e.message); resolve(null); }
    });
    return this.opening;
  },

  run: function (mode, fn) {
    return this.open().then(function (db) {
      if (!db) return null;
      return new Promise(function (resolve) {
        try {
          var tx = db.transaction('playlists', mode), st = tx.objectStore('playlists'), out = null;
          var req = fn(st);
          if (req) req.onsuccess = function () { out = req.result; };
          tx.oncomplete = function () { resolve(out == null ? null : out); };
          tx.onerror = tx.onabort = function () { Log.warn('Кэш: ошибка ' + (tx.error && tx.error.name)); resolve(null); };
        } catch (e) { Log.warn('Кэш: ' + e.message); resolve(null); }
      });
    });
  },

  get: function (id) { return this.run('readonly', function (st) { return st.get('pl:' + id); }); },
  set: function (id, data) { return this.run('readwrite', function (st) { st.put(data, 'pl:' + id); }); },
  del: function (id) { return this.run('readwrite', function (st) { st.delete('pl:' + id); }); }
};
