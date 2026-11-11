// ShaGa IPTV Player for LG webOS — settings.js
// Settings pages: main, playlist (archive), groups, log.
// Настройки. Страницы: главная, «Плейлист» (архив), «Группы», журнал.
var Settings = {
  el: null,
  mode: 'main',
  idx: 0,
  items: [],
  glist: null,
  returnTo: 'browser',

  init: function () {
    var self = this;
    this.el = $('settings');
    this.glist = new VList($('setGroups'), {
      rowH: 92,
      render: this.renderGroup,
      onActivate: function (i) { self.cycleGroup(i); }
    });
  },

  enter: function () {
    this.returnTo = (App.prev === Player) ? 'player' : 'browser';
    this.mode = 'main';
    this.idx = 0;
    this.draw();
  },

  // ---------- Главный список (порядок как в Roku) ----------
  buildMain: function () {
    var pl = Playlists.byId(App.plId);
    var plValue = pl ? pl.name + ' · ' + Lang.count(App.channels.length) + (App.plStale ? ' ' + t('staleShort') : '') : '';
    this.items = [
      { key: 'playlist', label: t('playlistRow') + ' >', value: plValue, action: function () { Settings.openPlaylistPage(); } },
      { label: t('sPin'), value: Config.d.pin ? t('sPinOn') : t('sPinOff'),
        action: function () { Settings.pinMenu(); } },
      { key: 'zap', label: t('sZap'), value: t(Config.d.zapInvert ? 'zapList' : 'zapClassic'),
        action: function () { Config.d.zapInvert = !Config.d.zapInvert; Config.save(); Settings.draw(); } },
      { key: 'epg', label: t('sEpg'), value: Settings.epgStatus(), action: function () {
          EPG.load(App.meta, App.channels, true);
          Toast.show(t('epgLoading'));
        } },
      { key: 'groups', label: t('sGroups') + ' >', value: t('groupsValue', { n: App.groupOrder.length }),
        action: function () { Settings.openGroups(); } },
      { key: 'engine', label: t('sEngine'), value: t(Config.d.engine === 'hlsjs' ? 'engHls' : 'engNative'),
        action: function () { Settings.toggleEngine(); } },
      { key: 'log', label: t('sLog'), value: t('sLogValue', { n: Log.buf.length }), action: function () { Settings.openLog(); } },
      { key: 'about', label: t('aboutRow'), value: App.version, action: function () { Settings.showAbout(); } },
      { label: t('sClose'), value: '', action: function () { App.confirmExit(); } }
    ];
  },

  // ---------- Страница «Плейлист»: плейлисты, обновление, архив ----------
  buildPlaylistPage: function () {
    var a = this.currentArchive();
    this.items = [
      { key: 'pls', label: t('svcPlaylists'), value: t('playlistsHint'), action: function () { App.openPlaylists(); } },
      { key: 'refresh', label: t('sRefresh'), value: '', action: function () { Settings.refresh(); } },
      { key: 'archT', label: t('archRow'), value: this.archTypeText(a.t), action: function () { Settings.openArchType(); } },
      { key: 'archD', label: t('archDaysRow'), value: t('archDaysValue', { n: a.d }), action: function () { Settings.openArchDays(); } }
    ];
  },

  openPlaylistPage: function () {
    if (!Playlists.byId(App.plId)) { App.openPlaylists(); return; }
    this.mode = 'playlist';
    this.idx = 0;
    this.draw();
  },

  // Настройка архива текущего плейлиста: { t: auto|shift|flussonic|xc|off, d: 1..7 }
  currentArchive: function () {
    var pl = Playlists.byId(App.plId), a = (pl && pl.arch) || {};
    return { t: a.t || 'auto', d: a.d || 3 };
  },

  archTypeText: function (tp) {
    return tp === 'auto' ? t('archAuto') : tp === 'off' ? t('archOff') : tp;
  },

  setArchive: function (tp, d) {
    var pl = Playlists.byId(App.plId);
    if (!pl) return;
    pl.arch = { t: tp, d: d };
    Config.save();
    Log.info('Архив плейлиста id ' + pl.id + ': ' + tp + ', ' + d + ' дн.');
    this.draw();
  },

  openArchType: function () {
    var self = this, a = this.currentArchive(), types = ['auto', 'shift', 'flussonic', 'xc', 'off'];
    Menu.open(t('archRow'), t('archMsg'), types.map(function (tp) {
      return { label: self.archTypeText(tp), mark: tp === a.t, action: function () { self.setArchive(tp, a.d); } };
    }).concat([{ label: t('cancel') }]), { focus: Math.max(0, types.indexOf(a.t)) });
  },

  openArchDays: function () {
    var self = this, a = this.currentArchive(), items = [];
    for (var d = 1; d <= 7; d++) {
      (function (n) {
        items.push({ label: t('archDaysValue', { n: n }), mark: n === a.d, action: function () { self.setArchive(a.t, n); } });
      })(d);
    }
    items.push({ label: t('cancel') });
    Menu.open(t('archDaysRow'), t('archDaysMsg'), items, { focus: a.d - 1 });
  },

  showAbout: function () {
    Confirm.open('ShaGa IPTV Player', [{ label: t('ok') }], null,
      t('aboutText', { v: t('sVersion') + ' ' + App.version }) + '\n\n' +
      t('privacyLabel') + ' ' + App.PRIVACY_URL + '\n' + t('supportLabel') + ' ' + App.SUPPORT_URL);
  },

  draw: function () {
    var main = this.mode === 'main' || this.mode === 'playlist';
    $('setList').style.display = main ? '' : 'none';
    $('setGroups').style.display = this.mode === 'groups' ? '' : 'none';
    $('setLog').style.display = this.mode === 'log' ? 'block' : 'none';
    if (this.mode === 'log') {
      $('setTitle').textContent = t('sLog');
      $('setSub').textContent = t('sLogSub');
      $('setSub').style.display = '';
      this.drawLog();
      return;
    }
    if (main) {
      var pl = Playlists.byId(App.plId);
      $('setTitle').textContent = this.mode === 'playlist' ? t('playlistRow') + (pl ? ': ' + pl.name : '') : t('settings');
      $('setSub').textContent = '';
      $('setSub').style.display = 'none';
      if (this.mode === 'playlist') this.buildPlaylistPage(); else this.buildMain();
      if (this.idx >= this.items.length) this.idx = 0;
      this.drawMain();
      this.glist.setActive(false);
    } else {
      $('setTitle').textContent = t('sGroups');
      $('setSub').textContent = t('sGroupsSub');
      $('setSub').style.display = '';
      this.glist.setActive(true);
    }
  },

  drawMain: function () {
    var self = this, box = $('setList');
    box.innerHTML = '';
    this.items.forEach(function (it, i) {
      var row = document.createElement('div');
      row.className = 's-row' + (i === self.idx ? ' focus' : '');
      var l = document.createElement('span'); l.className = 's-label'; l.textContent = it.label;
      var v = document.createElement('span'); v.className = 's-value'; v.textContent = it.value;
      row.appendChild(l); row.appendChild(v);
      row.addEventListener('mousemove', function () {
        if (self.idx !== i) { self.idx = i; self.drawMain(); }
      });
      row.addEventListener('click', function () { self.idx = i; self.activate(); });
      box.appendChild(row);
    });
  },

  focusKey: function (key) {
    for (var i = 0; i < this.items.length; i++) if (this.items[i].key === key) { this.idx = i; break; }
    this.drawMain();
  },

  epgStatus: function () {
    if (EPG.loading && EPG.state !== 'ready') return t('epgLoadingShort');
    if (EPG.state === 'ready') return t('epgStatus', { n: Lang.count(EPG.stats ? EPG.stats.channels : 0), time: EPG.hm(EPG.loadedAt) });
    if (EPG.state === 'nourl') return t('epgNoSourceShort');
    if (EPG.state === 'error') return t('epgErrorShort');
    return '';
  },

  activate: function () {
    var it = this.items[this.idx];
    if (it && it.action) it.action();
  },

  refresh: function () {
    var pl = Playlists.byId(App.plId);
    if (!pl) return;
    Busy.show(t('loading'));
    App.refreshPlaylist(pl, false).then(function (res) {
      Busy.hide();
      Toast.show(t('sRefreshed', { count: Lang.count(res.channels.length) }));
      Settings.draw();
    }).catch(function (err) {
      Busy.hide();
      Toast.show(App.errText(err), 5000);
    });
  },

  pinMenu: function () {
    var self = this;
    if (!Config.d.pin) {
      App.setNewPin(function () { self.draw(); });
      return;
    }
    App.askPin(function () {
      Confirm.open(t('sPin'), [
        { label: t('sPinChange'), action: function () { App.setNewPin(function () { self.draw(); }); } },
        { label: t('sPinRemove'), action: function () { App.removePin('удалён в настройках'); } },
        { label: t('cancel') }
      ]);
    });
  },

  toggleEngine: function () {
    Config.d.engine = Config.d.engine === 'hlsjs' ? 'native' : 'hlsjs';
    Config.save();
    Log.info('Движок переключён на ' + Config.d.engine);
    this.draw();
    // Если канал играет — перезапускаем его на новом движке
    if (Player.ch) Player.start(Player.ch);
  },

  // ---------- Журнал ----------
  logOffset: 0,   // сколько строк от конца пропущено (листание вверх)
  LOG_LINES: 28,

  openLog: function () {
    this.mode = 'log';
    this.logOffset = 0;
    this.draw();
  },

  drawLog: function () {
    var b = Log.buf, end = b.length - this.logOffset;
    $('setLog').textContent = b.slice(Math.max(0, end - this.LOG_LINES), end).join('\n');
  },

  // ---------- Группы ----------
  pinOk: false,   // PIN уже вводили в этом заходе в «Группы» — повторно не спрашиваем

  openGroups: function () {
    this.mode = 'groups';
    this.pinOk = false;
    this.glist.setItems(App.groupOrder.slice(), 0);
    this.draw();
  },

  groupState: function (g) {
    if (App.isHidden(g)) return 'hidden';
    if (Config.d.locked.indexOf(g) >= 0) return 'locked';
    return 'visible';
  },

  renderGroup: function (row, g) {
    if (!row._s) {
      row.classList.add('s-row');
      row.innerHTML = '<span class="s-label"></span><span class="s-value"></span>';
      row._s = { l: row.children[0], v: row.children[1] };
    }
    var st = Settings.groupState(g);
    row._s.l.textContent = g || t('noGroup');
    row._s.v.textContent = t(st === 'hidden' ? 'gsHidden' : st === 'locked' ? 'gsLocked' : 'gsVisible');
    row._s.v.className = 's-value st-' + st;
  },

  // OK на группе — окно из трёх режимов + «Отмена», текущий отмечен «* » (как в Roku)
  cycleGroup: function (i) {
    var self = this, g = this.glist.items[i], st = this.groupState(g);
    var opts = [['visible', 'gsVisible'], ['locked', 'gsLocked'], ['hidden', 'gsHidden']];
    var items = opts.map(function (o) {
      return { label: t(o[1]), mark: o[0] === st, action: function () { self.setGroupState(g, o[0]); } };
    });
    items.push({ label: t('cancel') });
    Menu.open(g || t('noGroup'), t('groupModeMsg'), items, { focus: ['visible', 'locked', 'hidden'].indexOf(st) });
  },

  // Ослабление защиты (из «Скрыта» во что угодно, из «По PIN» в «Показана»):
  //  - PIN задан — спросить его (один раз за заход в «Группы»);
  //  - PIN не задан — сначала придумать PIN (как в Roku).
  // «По PIN» без заданного PIN тоже требует сначала его создать.
  setGroupState: function (g, next) {
    var self = this, st = this.groupState(g);
    if (next === st) return;
    var weaker = st === 'hidden' || (st === 'locked' && next === 'visible');
    var apply = function () {
      var d = Config.d;
      d.hidden = d.hidden.filter(function (x) { return x !== g; });
      d.locked = d.locked.filter(function (x) { return x !== g; });
      if (next === 'hidden') d.hidden.push(g);
      if (next === 'locked') d.locked.push(g);
      // PIN, введённый в настройках, ничего не открывает для просмотра
      if (App.openLocked === g) App.openLocked = null;
      Config.save();
      Log.info('Группа «' + g + '»: ' + st + ' → ' + next);
      if (App.screen === Settings) { self.draw(); self.glist.render(); }
      else App.refreshScreen();     // из контекстного меню главного экрана
    };
    if (!Config.d.pin && (weaker || next === 'locked')) {
      App.setNewPin(function () { self.pinOk = true; apply(); }, t('pinCreateFirst'));
    } else if (weaker && !this.pinOk) {
      App.askPin(function () { self.pinOk = true; apply(); });
    } else {
      apply();
    }
  },

  onKey: function (code) {
    if (this.mode === 'log') {
      var maxOff = Math.max(0, Log.buf.length - this.LOG_LINES);
      if (code === K.UP || code === K.CHUP) this.logOffset = Math.min(maxOff, this.logOffset + (code === K.UP ? 3 : this.LOG_LINES));
      else if (code === K.DOWN || code === K.CHDN) this.logOffset = Math.max(0, this.logOffset - (code === K.DOWN ? 3 : this.LOG_LINES));
      else if (code === K.OK) {
        var self = this;
        Confirm.open(t('logClear') + '?', [
          { label: t('logClear'), action: function () { Log.clear(); self.logOffset = 0; self.drawLog(); Toast.show(t('logCleared')); } },
          { label: t('cancel') }
        ]);
        return true;
      }
      else if (code === K.BACK) { this.mode = 'main'; this.draw(); this.focusKey('log'); return true; }
      else return false;
      this.drawLog();
      return true;
    }
    if (this.mode === 'groups') {
      if (code === K.UP) this.glist.move(-1);
      else if (code === K.DOWN) this.glist.move(1);
      else if (code === K.CHUP) this.glist.page(-1);
      else if (code === K.CHDN) this.glist.page(1);
      else if (code === K.OK) this.cycleGroup(this.glist.index);
      else if (code === K.BACK) { this.mode = 'main'; this.draw(); this.focusKey('groups'); }
      else return false;
      return true;
    }
    var n = this.items.length;
    if (code === K.UP) { this.idx = (this.idx - 1 + n) % n; this.drawMain(); }
    else if (code === K.DOWN) { this.idx = (this.idx + 1) % n; this.drawMain(); }
    else if (code === K.OK) this.activate();
    else if (code === K.RIGHT && this.items[this.idx].label.slice(-2) === ' >') this.activate();
    else if ((code === K.LEFT || code === K.RIGHT) && /^(engine|zap)$/.test(this.items[this.idx].key || '')) this.activate();
    else if (code === K.BACK && this.mode === 'playlist') { this.mode = 'main'; this.draw(); this.focusKey('playlist'); }
    else if (code === K.BACK || code === K.BLUE) App.show(this.returnTo, { fromPlayer: true });
    else return false;
    return true;
  }
};
