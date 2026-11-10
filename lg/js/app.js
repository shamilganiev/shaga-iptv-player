// Коды кнопок пульта LG. Для проверки в браузере на компьютере:
// Esc = Back, PageUp/PageDown = CH+/CH−, Y = жёлтая, B = синяя.
var K = {
  LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 461,
  CHUP: 33, CHDN: 34,
  RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406,
  PLAY: 415, PAUSE: 19, STOP: 413
};

function digitOf(code) {
  if (code >= 48 && code <= 57) return code - 48;
  if (code >= 96 && code <= 105) return code - 96;
  return -1;
}

function isInput(el) {
  return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA');
}

var App = {
  version: '0.4.0',
  channels: [],
  groupOrder: [],    // группы в порядке появления в плейлисте
  groupMap: {},      // группа -> [каналы]
  byNum: {},         // номер -> канал
  meta: {},
  favSet: {},
  plId: null,        // плейлист, который сейчас показан
  plStale: false,    // показана сохранённая копия, обновить не удалось
  search: null,      // { q, list } — результаты поиска
  screens: {},
  screen: null,
  prev: null,
  openLocked: null,  // группа «Под PIN-кодом», для которой сейчас введён PIN (одна)
  lockTouch: {},     // группа -> когда её последний раз смотрели (для автоскрытия через 10 мин)
  LOCK_IDLE_MS: 10 * 60 * 1000,

  init: function () {
    Config.load();
    // «Под PIN-кодом» не переживает перезапуск
    this.hideAllLocked('запуск');
    Lang.set(Lang.resolve(Config.d.lang));
    this.applyI18n();

    PinPad.init();
    Prompt.init();
    Player.init();
    Browser.init();
    Settings.init();
    Setup.init();
    this.screens = { setup: Setup, browser: Browser, player: Player, settings: Settings };
    this.rebuildFavSet();

    document.addEventListener('keydown', function (e) { App.onKey(e); }, true);
    document.addEventListener('visibilitychange', function () {
      var visible = !document.hidden;
      if (!visible) { App.hideAllLocked('сворачивание'); App.openLocked = null; }
      Player.onVisibility(visible);
      if (visible) App.refreshScreen();
    });
    setInterval(function () { App.checkLocks(); }, 30000);
    // Программа передач: перерисовка после загрузки и раз в минуту (сменилась передача, сдвинулся прогресс)
    EPG.onUpdate = function () { Browser.refreshEpg(); if (Settings.mode === 'main' && App.screen === Settings) Settings.draw(); };
    setInterval(function () {
      Browser.refreshEpg();
      if (App.screen === Player && $('banner').classList.contains('on')) Player.drawBanner();
    }, 60000);

    this.start();
  },

  start: function () {
    var self = this, d = Config.d;
    Log.info('Версия ' + this.version + ', язык ' + Lang.cur + (d.lang ? '' : ' (авто)') + ', движок ' + d.engine +
      ', hls.js ' + (window.Hls ? Hls.version + (Hls.isSupported() ? '' : ' (не поддерж.)') : 'нет') +
      ', плейлистов ' + d.playlists.length);
    var pl = Playlists.byId(d.lastOkId) || d.playlists[0];
    if (!pl) { this.show('setup'); return; }

    // Переход с 0.3.x: старый кэш из localStorage переносим в IndexedDB
    var old = d.migratedFrom03 ? Store.get('playlist', null) : null;
    var ready = old && old.url === pl.url ? Cache.set(pl.id, old) : Promise.resolve();
    if (d.migratedFrom03) { delete d.migratedFrom03; Store.del('playlist'); Config.save(); }
    ready.then(function () { self.openPlaylist(pl, { startup: true }); });
  },

  // ---------- Плейлисты ----------
  // Сначала кэш (сразу на экран), потом сеть. opts.startup — включить последний канал.
  openPlaylist: function (pl, opts) {
    var self = this;
    opts = opts || {};
    Log.info('Плейлист «' + pl.name + '» (id ' + pl.id + ')' + (opts.startup ? ' при запуске' : ''));
    Cache.get(pl.id).then(function (cache) {
      if (cache && cache.url === pl.url && cache.channels && cache.channels.length) {
        Log.info('Список из кэша: ' + cache.channels.length + ' каналов');
        self.applyPlaylist(pl, cache, false);
        if (opts.startup) self.autoplayLast();
        // Свежий плейлист — в фоне, когда канал уже пошёл (разбор на ТВ занимает около секунды)
        Player.onceStarted(function () { self.refreshPlaylist(pl, true).catch(function () {}); });
        return;
      }
      Busy.show(t('loading'));
      Source.load({ type: 'url', url: pl.url }).then(function (res) {
        Busy.hide();
        self.applyPlaylist(pl, res, true);
        if (opts.startup) self.autoplayLast();
      }).catch(function (err) {
        Busy.hide();
        Log.error('Плейлист «' + pl.name + '» не загрузился, кэша нет');
        if (self.plId === null) self.show('browser');   // при запуске показываем пустой список
        self.showLoadError(err);
      });
    });
  },

  // Обновить текущий плейлист из сети. background — тихо, при ошибке пометить «копия».
  refreshPlaylist: function (pl, background) {
    var self = this;
    return Source.load({ type: 'url', url: pl.url }).then(function (res) {
      if (self.plId !== pl.id) return res;
      self.setChannels(res);
      self.saveCache(pl, res);
      self.plStale = false;
      Config.d.lastOkId = pl.id;
      Config.save();
      if (self.screen === Browser) Browser.enter({});
      return res;
    }).catch(function (err) {
      if (self.plId !== pl.id) throw err;
      if (background) {
        self.plStale = true;
        Toast.show(pl.name + ' — ' + t('staleNote'), 5000);
        if (self.screen === Browser) Browser.enter({});
      }
      throw err;
    });
  },

  applyPlaylist: function (pl, res, fresh) {
    if (this.plId !== pl.id && Player.ch) Player.stop();
    this.plId = pl.id;
    this.plStale = false;
    this.search = null;
    this.openLocked = null;
    this.hideAdultGroups(res.channels);
    this.setChannels(res);
    if (fresh) {
      this.saveCache(pl, res);
      Config.d.lastOkId = pl.id;
      Config.save();
    }
    this.show('browser');
  },

  saveCache: function (pl, res) {
    Cache.set(pl.id, { url: pl.url, at: Date.now(), meta: res.meta || {}, channels: res.channels });
  },

  showLoadError: function (err) {
    Confirm.open(t('errTitle'), [{ label: t('ok') }], null, this.errText(err) + ' ' + t('errHint'));
  },

  // Группы для взрослых, которые встретились впервые, скрываются (как в Roku)
  hideAdultGroups: function (channels) {
    var d = Config.d, seen = d.seenGroups || (d.seenGroups = {}), changed = false;
    channels.forEach(function (ch) {
      var g = ch.group;
      if (seen[g]) return;
      seen[g] = 1;
      changed = true;
      if (Text.isAdult(g) && d.hidden.indexOf(g) < 0 && d.locked.indexOf(g) < 0) {
        d.hidden.push(g);
        Log.info('Группа «' + g + '» скрыта: для взрослых');
      }
    });
    if (changed) Config.save();
  },

  // Окно «Плейлисты»: сохранённые (текущий «*»), «+ Добавить плейлист», «Закрыть»
  openPlaylists: function () {
    var self = this, items = [];
    Playlists.all().forEach(function (pl) {
      var cur = pl.id === self.plId;
      items.push({ label: pl.name + (cur && self.plStale ? ' — ' + t('staleNote') : ''), mark: cur,
                   action: function () { self.playlistActions(pl); } });
    });
    items.push({ label: t('addPlaylist'), action: function () { self.addPlaylist(); } });
    items.push({ label: t('close') });
    var focus = 0;
    Playlists.all().forEach(function (pl, i) { if (pl.id === self.plId) focus = i; });
    Menu.open(t('playlistsTitle'), t('playlistsMsg'), items, { focus: focus });
  },

  playlistActions: function (pl) {
    var self = this;
    Menu.open(pl.name, '', [
      { label: t('open'), action: function () { self.openPlaylist(pl); } },
      { label: t('editLink'), action: function () { self.editPlaylist(pl); } },
      { label: t('del'), action: function () { self.deletePlaylist(pl); } },
      { label: t('cancel'), action: function () { self.openPlaylists(); } }
    ], { onCancel: function () { self.openPlaylists(); } });
  },

  // Проверить ссылку из окна ввода. Возвращает нормализованную ссылку или null (ошибка показана).
  checkUrl: function (raw, prompt) {
    var url = Text.normalizeUrl(raw);
    if (url.length > Text.MAX_URL) { prompt.error(t('tooLong', { n: Text.MAX_URL })); return null; }
    if (!Text.isValidUrl(url)) { prompt.error(t('invalidUrl')); return null; }
    return url;
  },

  addPlaylist: function () {
    var self = this;
    if (Playlists.all().length >= Playlists.MAX) {
      Confirm.open(t('playlistsTitle'), [{ label: t('ok') }], null, t('limitReached', { n: Playlists.MAX }));
      return;
    }
    Prompt.open({
      title: t('urlTitle'), msg: t('urlMsg'), placeholder: t('urlPlaceholder'),
      onSubmit: function (raw, prompt) {
        var url = self.checkUrl(raw, prompt);
        if (!url) return;
        var dup = Playlists.byUrl(url);
        if (dup) { prompt.close(); Toast.show(t('dupOpened')); self.openPlaylist(dup); return; }
        // Сохраняем только после успешной загрузки
        Busy.show(t('loading'));
        Source.load({ type: 'url', url: url }).then(function (res) {
          Busy.hide();
          prompt.close();
          var pl = Playlists.add(url);
          Log.info('Добавлен плейлист «' + pl.name + '»');
          self.applyPlaylist(pl, res, true);
        }).catch(function (err) {
          Busy.hide();
          prompt.error(self.errText(err));
        });
      }
    });
  },

  editPlaylist: function (pl) {
    var self = this;
    Prompt.open({
      title: t('editTitle'), msg: t('urlMsg'), value: pl.url, placeholder: t('urlPlaceholder'),
      onCancel: function () { self.playlistActions(pl); },
      onSubmit: function (raw, prompt) {
        var url = self.checkUrl(raw, prompt);
        if (!url) return;
        var dup = Playlists.byUrl(url);
        if (dup && dup.id !== pl.id) { prompt.close(); Toast.show(t('dupOpened')); self.openPlaylist(dup); return; }
        Busy.show(t('loading'));
        Source.load({ type: 'url', url: url }).then(function (res) {
          Busy.hide();
          prompt.close();
          Playlists.setUrl(pl, url);
          Log.info('Ссылка плейлиста id ' + pl.id + ' изменена: ' + pl.name);
          self.plId = null;            // чтобы applyPlaylist пересобрал всё заново
          self.applyPlaylist(pl, res, true);
        }).catch(function (err) {
          Busy.hide();
          prompt.error(self.errText(err));
        });
      }
    });
  },

  deletePlaylist: function (pl) {
    var self = this;
    Confirm.open(t('deleteTitle'), [
      { label: t('del'), action: function () {
          Playlists.remove(pl.id);
          Cache.del(pl.id);
          Log.info('Удалён плейлист «' + pl.name + '»');
          if (self.plId !== pl.id) { self.openPlaylists(); return; }
          // Удалили текущий: открываем другой или возвращаемся к приветствию
          Player.stop();
          self.plId = null;
          self.setChannels({ channels: [], meta: {} });
          var next = Playlists.byId(Config.d.lastOkId) || Playlists.all()[0];
          if (next) self.openPlaylist(next); else self.show('setup');
        } },
      { label: t('cancel'), action: function () { self.playlistActions(pl); } }
    ], function () { self.playlistActions(pl); }, pl.name + '. ' + t('deleteMsg'));
  },

  // ---------- Поиск (как в Roku: латиница находит кириллицу, акценты не важны) ----------
  openSearch: function () {
    var self = this;
    Prompt.open({
      title: t('searchTitle'), msg: t('searchMsg'), value: this.search ? this.search.q : '',
      onSubmit: function (q, prompt) {
        q = String(q).trim();
        if (!q) { prompt.close(); return; }
        prompt.close();
        self.runSearch(q);
      }
    });
  },

  runSearch: function (q) {
    var self = this, qn = Text.norm(q), qf = Text.fuzz(qn);
    var res = this.channels.filter(function (ch) {
      if (self.isRestricted(ch.group)) return false;          // скрытые и закрытые не ищутся
      if (ch.k == null) ch.k = Text.searchKey(ch.name);
      return Text.matches(ch.k, qn, qf);
    });
    Log.info('Поиск «' + qn + '» / «' + qf + '» → ' + res.length);
    if (!res.length) {
      Confirm.open(t('searchTitle'), [{ label: t('ok') }], null, t('noResults', { q: q }));
      return;
    }
    this.search = { q: q, list: res };
    if (this.screen !== Browser) this.show('browser');
    Browser.showSearch();
  },

  // ---------- Язык ----------
  openLanguage: function () {
    var self = this, pref = Config.d.lang;
    var items = [{ code: null, label: t('langAuto') }].concat(Lang.list.map(function (c) {
      return { code: c, label: I18N[c]._name };
    }));
    var focus = 0;
    Menu.open(t('langTitle'), '', items.map(function (it, i) {
      if (it.code === pref) focus = i;
      return { label: it.label, mark: it.code === pref, action: function () {
        Config.d.lang = it.code;
        Config.save();
        Lang.set(Lang.resolve(it.code));
        Log.info('Язык: ' + (it.code || 'авто') + ' → ' + Lang.cur);
        self.applyI18n();
        self.refreshScreen();
      } };
    }), { focus: focus });
  },

  // ---------- Данные ----------
  setChannels: function (res) {
    this.channels = res.channels;
    this.meta = res.meta || {};
    var order = [], map = {}, byNum = {};
    this.channels.forEach(function (ch) {
      if (!map[ch.group]) { map[ch.group] = []; order.push(ch.group); }
      map[ch.group].push(ch);
      byNum[ch.n] = ch;
    });
    this.groupOrder = order;
    this.groupMap = map;
    this.byNum = byNum;
    if (this.search) {
      // результаты поиска пересчитываются на новом списке
      var q = this.search.q;
      this.search = null;
      if (this.channels.length) {
        var self = this, qn = Text.norm(q), qf = Text.fuzz(qn);
        var list = this.channels.filter(function (ch) {
          if (ch.k == null) ch.k = Text.searchKey(ch.name);
          return Text.matches(ch.k, qn, qf);
        });
        if (list.length) this.search = { q: q, list: list };
      }
    }
    if (this.channels.length) this.scheduleEpg();
  },

  // EPG качается после старта видео (как и обновление плейлиста), чтобы не тормозить канал
  scheduleEpg: function (force) {
    var self = this;
    // setTimeout 0: при запуске сначала стартует последний канал, и только потом мы ждём его картинку
    setTimeout(function () {
      if (Player.ch && Player.state === 'loading') Log.info('EPG: загрузка начнётся после старта видео');
      Player.onceStarted(function () {
        setTimeout(function () { EPG.load(self.meta, self.channels, force); }, 1500);
      });
    }, 0);
  },

  isHidden: function (g) { return Config.d.hidden.indexOf(g) >= 0; },
  // Без PIN-кода замок не действует
  isLocked: function (g) { return !!Config.d.pin && Config.d.locked.indexOf(g) >= 0; },
  // Канал недоступен без PIN: группа скрыта или закрыта
  isRestricted: function (g) { return this.isHidden(g) || this.isLocked(g); },
  isOpen: function (g) { return !this.isLocked(g) || this.openLocked === g; },

  listFor: function (gid) {
    var self = this;
    if (gid === '__fav') {
      return this.channels.filter(function (c) { return self.favSet[c.name] && !self.isRestricted(c.group); });
    }
    if (gid === '__search') {
      return this.search ? this.search.list.filter(function (c) { return !self.isRestricted(c.group); }) : [];
    }
    if (gid === '__all') {
      return this.channels.filter(function (c) { return !self.isRestricted(c.group); });
    }
    // Закрытая группа отдаёт каналы только после PIN-кода
    if (this.isHidden(gid) || !this.isOpen(gid) || !this.groupMap[gid]) return [];
    return this.groupMap[gid];
  },

  rebuildFavSet: function () {
    var s = {};
    Config.d.favorites.forEach(function (n) { s[n] = true; });
    this.favSet = s;
  },

  isFav: function (ch) { return !!this.favSet[ch.name]; },

  toggleFav: function (ch) {
    var f = Config.d.favorites, i = f.indexOf(ch.name);
    if (i >= 0) { f.splice(i, 1); Toast.show(t('favRemoved', { name: ch.name })); }
    else { f.push(ch.name); Toast.show(t('favAdded', { name: ch.name })); }
    Config.save();
    this.rebuildFavSet();
  },

  // ---------- Воспроизведение ----------
  guardPlay: function (ch, cb) {
    var self = this, g = ch.group;
    if (this.isHidden(g)) return;
    if (this.isOpen(g)) { cb(); return; }
    this.askPin(function () { self.unlockGroup(g); cb(); });
  },

  playFrom: function (list, idx, gid) {
    var self = this;
    this.guardPlay(list[idx], function () {
      Player.play(list, idx, gid);
      self.show('player');
    });
  },

  playNumber: function (n) {
    var ch = this.byNum[n];
    if (!ch || this.isRestricted(ch.group)) { Toast.show(t('noChannel', { n: n })); return; }
    var list = this.listFor('__all');
    this.playFrom(list, list.indexOf(ch), '__all');
  },

  autoplayLast: function () {
    var last = Config.d.last;
    if (!last) return;
    var gid = last.gid || '__all';
    var list = this.listFor(gid);
    var idx = -1;
    for (var i = 0; i < list.length; i++) if (list[i].name === last.name) { idx = i; break; }
    if (idx < 0) {
      gid = '__all';
      list = this.listFor(gid);
      for (var j = 0; j < list.length; j++) if (list[j].name === last.name) { idx = j; break; }
    }
    // Каналы под PIN-кодом автоматически не включаем
    if (idx < 0 || this.isRestricted(list[idx].group)) return;
    Player.play(list, idx, gid);
    this.show('player');
  },

  // ---------- PIN-код ----------
  // Спрашивает PIN каждый раз (запоминание — забота вызывающего). Без PIN — сразу cb.
  // Настоящий PIN важнее мастер-кода; мастер-код только предлагает сбросить PIN.
  askPin: function (cb) {
    var self = this;
    if (!Config.d.pin) { cb(); return; }
    PinPad.open({
      title: t('pinEnter'),
      hint: t('pinEnterHint'),
      onDone: function (p, pad) {
        if (hashPin(p) === Config.d.pin) {
          pad.close();
          cb();
        } else if (isMasterPin(p)) {
          pad.close();
          Log.info('PIN: введён мастер-код');
          self.confirmResetPin();
        } else {
          pad.reset(t('pinEnter'), t('pinWrong'));
        }
      }
    });
  },

  confirmResetPin: function () {
    var self = this;
    Confirm.open(t('pinResetTitle'), [
      { label: t('pinReset'), action: function () { self.removePin('сброс мастер-кодом'); } },
      { label: t('cancel') }
    ], null, t('pinResetMsg'));
  },

  // Удаление и сброс PIN: группы «По PIN» становятся скрытыми
  removePin: function (why) {
    this.hideAllLocked(why);
    Config.d.pin = null;
    Config.save();
    Log.info('PIN удалён: ' + why);
    Toast.show(t('pinRemoved'), 4000);
    this.refreshScreen();
  },

  // hint — пояснение в окне (например, «чтобы ослабить защиту, сначала придумайте PIN»)
  setNewPin: function (cb, hint) {
    var self = this, first = null;
    PinPad.open({
      title: t('pinNew'),
      hint: hint || '',
      onDone: function (p, pad) {
        if (first === null) { first = p; pad.reset(t('pinRepeat')); return; }
        if (p === first) {
          Config.d.pin = hashPin(p);
          Config.save();
          pad.close();
          Toast.show(t('pinSaved'));
          if (cb) cb();
        } else {
          first = null;
          pad.reset(t('pinNew'), t('pinMismatch'));
        }
      }
    });
  },

  // ---------- Замки групп ----------
  unlockGroup: function (g) {
    this.openLocked = g;
    this.lockTouch[g] = Date.now();
    Log.info('PIN: открыта группа «' + g + '»');
  },

  // Закрыть открытую группу, если из неё ушли (и её канал сейчас не играет)
  relockIfLeft: function (currentGid) {
    var g = this.openLocked;
    if (g === null || currentGid === g) return;
    if (Player.ch && Player.ch.group === g && Player.state !== 'idle') return;
    this.lockTouch[g] = Date.now();
    this.openLocked = null;
  },

  // Группу «смотрят»: открыта PIN-кодом и (играет её канал или она выбрана в списке)
  isLockActive: function (g) {
    if (this.openLocked !== g) return false;
    if (Player.ch && Player.ch.group === g && Player.state !== 'idle') return true;
    return this.screen === Browser && Browser.gid === g;
  },

  hideGroup: function (g, why) {
    var d = Config.d;
    d.locked = d.locked.filter(function (x) { return x !== g; });
    if (d.hidden.indexOf(g) < 0) d.hidden.push(g);
    if (this.openLocked === g) this.openLocked = null;
    delete this.lockTouch[g];
    Config.save();
    Log.info('Группа «' + g + '» скрыта: ' + why);
  },

  hideAllLocked: function (why) {
    var self = this;
    Config.d.locked.slice().forEach(function (g) { self.hideGroup(g, why); });
  },

  // Раз в 30 с: группы «Под PIN-кодом», которые не смотрят 10 минут, становятся скрытыми
  checkLocks: function () {
    var self = this, now = Date.now(), changed = false;
    Config.d.locked.slice().forEach(function (g) {
      if (self.isLockActive(g)) { self.lockTouch[g] = now; return; }
      if (!self.lockTouch[g]) self.lockTouch[g] = now;
      if (now - self.lockTouch[g] >= self.LOCK_IDLE_MS) { self.hideGroup(g, '10 минут без просмотра'); changed = true; }
    });
    if (changed) this.refreshScreen();
  },

  // Перерисовать текущий экран после смены замков
  refreshScreen: function () {
    if (Player.ch && this.isHidden(Player.ch.group)) {
      Player.stop();
      if (this.screen === Player) this.show('browser');
    }
    if (this.screen === Browser) Browser.enter({});
    else if (this.screen === Settings) Settings.draw();
  },

  // ---------- Экраны ----------
  show: function (name, opts) {
    var s = this.screens[name];
    if (this.screen && this.screen !== s) {
      this.screen.el.classList.remove('active');
      if (this.screen.leave) this.screen.leave();
    }
    if (this.screen !== s) this.prev = this.screen;
    this.screen = s;
    s.el.classList.add('active');
    if (s.enter) s.enter(opts || {});
  },

  confirmExit: function () {
    Confirm.open(t('exitTitle'), [
      { label: t('exit'), action: function () { window.close(); } },
      { label: t('cancel') }
    ]);
  },

  errText: function (err) {
    err = err || {};
    if (err.code === 'http') return t('errHttp', { code: err.status });
    if (err.code === 'timeout') return t('errTimeout');
    if (err.code === 'empty') return t('errEmpty');
    return t('errNetwork');
  },

  applyI18n: function () {
    var els = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < els.length; i++) els[i].textContent = t(els[i].getAttribute('data-i18n'));
    document.title = t('appName');
  },

  // ---------- Пульт ----------
  onKey: function (e) {
    var code = e.keyCode;
    var inInput = isInput(e.target);
    if (code === 27 || (code === 8 && !inInput)) code = K.BACK;
    if (!inInput) {
      if (code === 89) code = K.YELLOW;
      else if (code === 66) code = K.BLUE;
    }

    if (PinPad.isOpen) { if (PinPad.onKey(code)) e.preventDefault(); return; }
    if (Confirm.isOpen) { if (Confirm.onKey(code)) e.preventDefault(); return; }
    if (Menu.isOpen) { if (Menu.onKey(code)) e.preventDefault(); return; }
    if (Prompt.isOpen) { if (Prompt.onKey(code)) e.preventDefault(); return; }

    if (!inInput && this.screen && this.screen.acceptsDigits) {
      var d = digitOf(code);
      if (d >= 0) { Num.press(d); e.preventDefault(); return; }
      if (Num.buf && code === K.OK) { Num.commit(); e.preventDefault(); return; }
    }

    if (this.screen && this.screen.onKey(code, e)) e.preventDefault();
  }
};

window.addEventListener('load', function () { App.init(); });

// Мастер-код только для сброса PIN: текущее местное время ЧЧММ (24 ч) задом наперёд.
// 13:45 → «1345» → «5431». Подходит и предыдущая минута. Как в Roku-версии.
function isMasterPin(pin) {
  var now = Date.now();
  for (var off = 0; off <= 60000; off += 60000) {
    var d = new Date(now - off);
    var hhmm = (d.getHours() < 10 ? '0' : '') + d.getHours() + (d.getMinutes() < 10 ? '0' : '') + d.getMinutes();
    if (pin === hhmm.split('').reverse().join('')) return true;
  }
  return false;
}
