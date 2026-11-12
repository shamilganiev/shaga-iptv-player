// ShaGa IPTV Player for LG webOS — browser.js
// Main screen: groups, channels, info column with the programme list.
// Главный экран: группы, каналы, третья колонка с программой канала.
var Browser = {
  el: null,
  groups: null,      // VList групп
  chans: null,       // VList каналов
  progs: null,       // VList программы канала (третья колонка)
  panel: 'groups',   // groups | channels | prog
  groupItems: [],
  gid: '__all',
  memory: {},        // gid -> на каком канале стояли в этой группе
  progCh: null,      // канал, чья программа открыта в третьей колонке
  acceptsDigits: true,

  init: function () {
    var self = this;
    this.el = $('browser');

    this.groups = new VList($('groupList'), {
      rowH: 70,
      render: this.renderGroup,
      onMove: function (i) { self.openGroup(i); },
      onActivate: function (i) { self.openGroup(i); self.enterGroup(); },
      onPointer: function () { self.focusPanel('groups'); }
    });

    this.chans = new VList($('chanList'), {
      rowH: 98,
      render: this.renderChannel,
      onMove: function (i) { self.memory[self.gid] = i; self.scheduleInfo(); },
      onActivate: function (i) { App.playFrom(self.chans.items, i, self.gid); },
      onPointer: function () { if (self.panel === 'prog') self.closeGuide(); self.focusPanel('channels'); }
    });

    this.progs = new VList($('progList'), {
      rowH: 62,
      render: this.renderProg,
      onMove: function () { self.drawProgDesc(); },
      onActivate: function (i) { self.actOnProgramme(self.progs.items[i]); },
      onPointer: function () { self.focusPanel('prog'); }
    });
  },

  // ---------- Отрисовка строк ----------
  renderGroup: function (row, g) {
    if (!row._g) {
      row.classList.add('g-row');
      row.innerHTML = '<span class="g-name"></span><span class="ico lock">' + ICON_LOCK +
        '</span><span class="g-count"></span>';
      row._g = { name: row.children[0], lock: row.children[1], count: row.children[2] };
    }
    row._g.name.textContent = g.name;
    row._g.lock.style.display = g.locked ? '' : 'none';
    // Группа «По PIN»: без счётчика (как в Roku — «[PIN]» вместо числа)
    row._g.count.textContent = g.svc ? '' : g.locked ? '[PIN]' : g.count;
    row.classList.toggle('svc', !!g.svc);
  },

  renderChannel: function (row, ch) {
    if (!row._c) {
      row.classList.add('c-row');
      row.innerHTML = '<span class="c-num"></span><span class="c-logo"><img alt=""></span>' +
        '<span class="c-text"><span class="c-name"></span><span class="c-prog"></span>' +
        '<span class="c-bar"><i></i></span></span>' +
        '<span class="ico lock">' + ICON_LOCK + '</span>' +
        '<span class="ico star">' + ICON_STAR + '</span>';
      var logo = row.children[1], img = logo.firstChild;
      img.onerror = function () { logo.classList.add('noimg'); };
      var text = row.children[2];
      row._c = { num: row.children[0], logo: logo, img: img, name: text.children[0], prog: text.children[1],
                 bar: text.children[2], fill: text.children[2].firstChild,
                 lock: row.children[3], star: row.children[4], src: null };
    }
    var c = row._c;
    c.num.textContent = ch.n;
    c.name.textContent = ch.name;
    var p = EPG.now(ch);
    c.prog.textContent = p ? p.t : '';
    c.bar.classList.toggle('off', !p);
    if (p) c.fill.style.width = Math.round(EPG.progress(p) * 100) + '%';
    if (c.src !== ch.logo) {
      c.src = ch.logo;
      c.logo.setAttribute('data-l', (ch.name.charAt(0) || '?').toUpperCase());
      if (ch.logo) { c.logo.classList.remove('noimg'); c.img.src = ch.logo; }
      else { c.logo.classList.add('noimg'); c.img.removeAttribute('src'); }
    }
    c.lock.style.display = App.isLocked(ch.group) ? '' : 'none';
    c.star.style.display = App.isFav(ch) ? '' : 'none';
    row.classList.toggle('playing', Player.isCurrent(ch));
  },

  // Строка программы: «> » у текущей, «вчера »/«завтра » у другого дня
  renderProg: function (row, p) {
    if (!row._p) {
      row.classList.add('p-row');
      row.innerHTML = '<span class="p-time"></span><span class="p-title"></span>';
      row._p = { time: row.children[0], title: row.children[1] };
    }
    var now = Date.now(), cur = p.s <= now && now < p.e;
    row._p.time.textContent = (cur ? '> ' : '') + Browser.dayPrefix(p.s) + EPG.hm(p.s);
    row._p.title.textContent = p.t;
    row.classList.toggle('past', p.e <= now);
    row.classList.toggle('cur', cur);
  },

  dayPrefix: function (ms) {
    var a = new Date(ms), b = new Date();
    var da = a.getFullYear() * 400 + a.getMonth() * 32 + a.getDate();
    var db = b.getFullYear() * 400 + b.getMonth() * 32 + b.getDate();
    if (da === db) return '';
    return (da < db ? t('yesterday') : t('tomorrow')) + ' ';
  },

  // ---------- Заголовки ----------
  // Верхняя строка — «Вы смотрите: канал» (эфир или архив).
  // Над каналами — канал под курсором. Над третьей колонкой — передача играющего канала
  // («Архив: …» для архива), иначе — канала под курсором.
  drawHeads: function () {
    var g = this.groupItems[this.groups.index];
    $('gHead').textContent = g ? g.name : '';

    var cur = this.chans.items.length ? this.chans.current() : null;
    var playing = Player.ch && Player.state !== 'idle' ? Player.ch : null;
    var top = $('topNow');
    top.innerHTML = '';
    if (playing) {
      var pre = document.createElement('span');
      pre.className = 'h-pre';
      pre.textContent = t('watching');
      top.appendChild(pre);
      top.appendChild(document.createTextNode(playing.name));
    }
    $('cHead').textContent = cur ? cur.name : '';

    if (this.panel === 'prog' && this.progCh) { $('iHead').textContent = this.progCh.name; return; }
    if (playing && Player.arch) { $('iHead').textContent = t('archLabel') + ': ' + Player.arch.t; return; }
    var src = playing || cur, p = src ? EPG.now(src) : null;
    $('iHead').textContent = p ? p.t : (src ? src.name : '');
  },

  // ---------- Третья колонка: программа канала под курсором ----------
  infoTimer: null,

  // При быстром листании не перерисовываем на каждом шаге
  scheduleInfo: function () {
    var self = this;
    clearTimeout(this.infoTimer);
    this.infoTimer = setTimeout(function () { self.drawInfo(); }, 120);
  },

  drawInfo: function () {
    if (this.panel === 'prog') { this.drawHeads(); return; }   // открыт список передач
    var ch = this.chans.items.length ? this.chans.current() : null;
    var panel = $('infoPanel');
    panel.style.visibility = ch ? '' : 'hidden';
    if (!ch) { this.drawHeads(); return; }
    $('iCh').textContent = ch.n + '  ' + ch.name;
    this.drawHeads();

    var p = EPG.now(ch), msg = '';
    if (!p) {
      EPG.diagnose(ch);
      msg = EPG.state === 'nourl' ? t('epgNoSource')
        : EPG.state === 'error' ? t('epgError')
        : EPG.state === 'ready' ? t('epgNone')
        : t('epgLoading');
    }
    $('iMsg').textContent = msg;
    $('iMsg').style.display = msg ? '' : 'none';
    $('iNowBox').style.display = p ? '' : 'none';
    if (!p) return;

    $('iTitle').textContent = p.t;
    $('iTime').textContent = EPG.hm(p.s) + ' – ' + EPG.hm(p.e);
    $('iLeft').textContent = t('epgLeft', { m: Math.max(1, Math.round((p.e - Date.now()) / 60000)) });
    $('iBar').style.width = Math.round(EPG.progress(p) * 100) + '%';
    $('iDesc').textContent = p.d || '';
    $('iDesc').style.display = p.d ? '' : 'none';

    var next = EPG.next(ch, p.d ? 4 : 8), box = $('iNext');
    box.innerHTML = '';
    next.forEach(function (n) {
      var row = document.createElement('div');
      row.className = 'i-next-row';
      var b = document.createElement('b'); b.textContent = EPG.hm(n.s);
      var s = document.createElement('span'); s.textContent = n.t;
      row.appendChild(b); row.appendChild(s);
      box.appendChild(row);
    });
    $('iNextLabel').style.display = next.length ? '' : 'none';
  },

  // Программа загрузилась или прошла минута — обновить строки и колонку
  refreshEpg: function () {
    if (App.screen !== this) return;
    this.chans.render();
    if (this.panel === 'prog') this.progs.render();
    this.drawInfo();
  },

  // ---------- Программа канала (вправо из списка каналов) ----------
  openGuide: function (ch) {
    var list = EPG.all(ch);
    if (!list.length) {
      Log.info('Программа канала пуста: EPG ' + EPG.state);
      var why = EPG.state === 'ready' ? t('epgNone') : EPG.state === 'nourl' ? t('epgNoSource')
        : EPG.state === 'error' ? t('epgError') : t('epgLoading');
      Confirm.open(ch.name, [{ label: t('ok') }], null, why);
      return;
    }
    this.progCh = ch;
    var now = Date.now(), at = 0;
    for (var i = 0; i < list.length; i++) { if (list[i].s <= now) at = i; else break; }
    this.showGuideNodes(true);
    this.progs.setItems(list, at);
    this.focusPanel('prog');
    this.drawProgDesc();
    this.drawHeads();
  },

  closeGuide: function () {
    if (this.panel !== 'prog' && !this.progCh) return;
    this.progCh = null;
    this.showGuideNodes(false);
    this.focusPanel('channels');
    this.drawInfo();
  },

  showGuideNodes: function (on) {
    $('progBox').style.display = on ? '' : 'none';
    $('iCh').style.display = on ? 'none' : '';
    $('iNowBox').style.display = on ? 'none' : '';
    $('iMsg').style.display = on ? 'none' : $('iMsg').style.display;
    $('infoPanel').style.visibility = '';
  },

  drawProgDesc: function () {
    var p = this.progs.current();
    $('progDesc').textContent = p ? (EPG.hm(p.s) + ' – ' + EPG.hm(p.e) + (p.d ? '   ' + p.d : '')) : '';
  },

  // OK на передаче: идёт сейчас — эфир; прошла — архив (если есть); будущая — описание.
  // Из списка передач видео включается позади списка; повторный OK на том, что уже
  // играет, — полный экран (как в Roku, 1.3.2).
  actOnProgramme: function (p) {
    var ch = this.progCh;
    if (!p || !ch) return;
    var now = Date.now(), cur = p.s <= now && now < p.e;
    if (Player.isCurrent(ch) && Player.state !== 'error' && Player.state !== 'idle') {
      var same = Player.arch ? Player.arch.s === Math.floor(p.s / 1000) : cur;
      if (same) { Log.info('Передача уже играет — полный экран'); App.show('player'); return; }
    }
    if (cur) { this.playLive(ch); return; }
    if (p.e <= now) { this.playFromArchive(ch, p); return; }
    this.showDescription(p);
  },

  // Канал в текущем списке (для CH+/CH−); если его там нет — во «Всех каналах»
  listWith: function (ch) {
    var items = this.chans.items, i = items.indexOf(ch);
    if (i >= 0) return { list: items, idx: i, gid: this.gid };
    var all = App.listFor('__all');
    return { list: all, idx: all.indexOf(ch), gid: '__all' };
  },

  // Эфир из списка передач: видео позади списка, список остаётся
  playLive: function (ch) {
    var self = this, w = this.listWith(ch);
    if (w.idx < 0) return;
    App.guardPlay(ch, function () { Player.play(w.list, w.idx, w.gid); self.afterPlay(); });
  },

  afterPlay: function () {
    this.chans.render();
    this.progs.render();
    this.drawHeads();
  },

  playFromArchive: function (ch, p) {
    var self = this, pl = Playlists.byId(App.plId), info = Catchup.info(ch, pl && pl.arch);
    var now = Math.floor(Date.now() / 1000);
    if (!info) {
      // В плейлисте нет атрибутов архива, а в настройке «Из плейлиста»
      Log.info('Архив: для плейлиста не настроен');
      Confirm.open(p.t, [{ label: t('ok') }], null, t('archNotSet'));
      return;
    }
    if (!Catchup.covers(info, Math.floor(p.s / 1000), now)) {
      Log.info('Архив: передача старше глубины ' + info.d + ' дн.');
      Confirm.open(p.t, [{ label: t('ok') }], null, t('archUnavailable'));
      return;
    }
    var w = this.listWith(ch);
    if (w.idx < 0) return;
    App.guardPlay(ch, function () {
      if (Player.playArchive(w.list, w.idx, w.gid, p, 0)) self.afterPlay();
    });
  },

  // 1.3.4: широкое окно; сразу — короткий текст, полный приходит из файла и заменяет его
  showDescription: function (p) {
    var time = this.dayPrefix(p.s) + EPG.hm(p.s) + ' – ' + EPG.hm(p.e);
    Confirm.open(p.t, [{ label: t('ok') }], null, time + '\n\n' + (p.d ? p.d + '\n\n' : '') + t('descLoading'));
    this.descFit();
    var self = this;
    EPG.fullDesc(p).then(function (r) {
      var d = r.d || '';
      Log.info('UI описание «' + p.t + '» ' + EPG.hm(p.s) + ': ' + (r.src === 'network' ? 'из файла' : 'из памяти') + ', ' +
        d.length + ' симв.' + (r.why ? ' (' + r.why + ')' : '') + ': ' + (d ? d.slice(0, 60) : '(нет описания)'));
      if (!Confirm.isOpen || $('cfTitle').textContent !== p.t) return;
      $('cfMsg').textContent = time + '\n\n' + (d || t('noDesc'));
      self.descFit();
    });
  },

  // Окно описания пошире, шрифт меньше для длинного текста
  descFit: function () {
    var box = $('confirm').querySelector('.box'), n = $('cfMsg').textContent.length;
    box.classList.add('desc-box');
    $('cfMsg').style.fontSize = n > 1400 ? '20px' : n > 1000 ? '22px' : n > 700 ? '24px' : '26px';
  },

  // ---------- Контекстное меню (красная кнопка; на Roku — «*») ----------
  openContextMenu: function () {
    var self = this, items = [], now = Date.now();
    if (this.panel === 'prog') {
      var p = this.progs.current();
      if (!p) return;
      items.push({ label: t('ctxDesc'), action: function () { self.showDescription(p); } });
      if (p.s <= now) items.push({ label: t('ctxWatch'), action: function () { self.actOnProgramme(p); } });
      items.push({ label: t('cancel') });
      Menu.open(p.t, '', items);
      return;
    }
    if (this.panel === 'channels') {
      if (!this.chans.items.length) return;
      var ch = this.chans.current(), cur = EPG.now(ch);
      items.push({ label: t(App.isFav(ch) ? 'ctxFavRemove' : 'ctxFavAdd'),
                   action: function () { App.toggleFav(ch); self.refreshCounts(); } });
      items.push({ label: t('ctxDesc'), action: function () {
        if (cur) self.showDescription(cur);
        else Confirm.open(ch.name, [{ label: t('ok') }], null, t('noDesc'));
      } });
      items.push({ label: t('ctxGuide'), action: function () { self.openGuide(ch); } });
      var pl = Playlists.byId(App.plId);
      if (cur && Catchup.covers(Catchup.info(ch, pl && pl.arch), Math.floor(cur.s / 1000), Math.floor(now / 1000))) {
        items.push({ label: t('ctxFromStart'), action: function () { self.playFromArchive(ch, cur); } });
      }
      if (!App.groupMap[this.gid]) {
        items.push({ label: t('ctxGoGroup'), action: function () { self.goToChannelGroup(ch); } });
      }
      items.push({ label: t('cancel') });
      Menu.open(ch.name, '', items);
      return;
    }
    // Группа: «Закрыть PIN-кодом» (если показана) и «Скрыть» — усиление защиты, PIN не нужен
    var g = this.groupItems[this.groups.index];
    if (!g || g.svc || !App.groupMap[g.id]) return;
    if (Settings.groupState(g.id) === 'visible') {
      items.push({ label: t('ctxLock'), action: function () { Settings.setGroupState(g.id, 'locked'); } });
    }
    items.push({ label: t('ctxHide'), action: function () { Settings.setGroupState(g.id, 'hidden'); } });
    items.push({ label: t('cancel') });
    Menu.open(g.name, '', items);
  },

  goToChannelGroup: function (ch) {
    var gi = this.indexOfGroup(ch.group);
    if (gi < 0) return;
    var self = this;
    var go = function () {
      self.groups.setIndex(gi);
      self.openGroup(gi);
      var i = self.chans.items.indexOf(ch);
      if (i >= 0) { self.chans.setIndex(i); self.memory[self.gid] = i; }
      self.focusPanel('channels');
      self.scheduleInfo();
    };
    if (App.isOpen(ch.group)) go();
    else App.askPin(function () { App.unlockGroup(ch.group); go(); });
  },

  // ---------- Данные ----------
  // Вверху — служебные строки, как в Roku: «> Поиск», «> Плейлисты», «> Язык», «> Настройки».
  // Затем «Поиск: …» (если был поиск), «Избранное», «Все каналы», группы.
  // Название плейлиста — в Настройках (строка «Плейлист»).
  buildGroups: function () {
    var items = [
      { id: '__svc_search', svc: true, name: '> ' + t('svcSearch'), action: function () { App.openSearch(); } },
      { id: '__svc_playlists', svc: true, name: '> ' + t('svcPlaylists'), action: function () { App.openPlaylists(); } },
      { id: '__svc_lang', svc: true, name: '> ' + t('svcLanguage'), action: function () { App.openLanguage(); } },
      { id: '__svc_settings', svc: true, name: '> ' + t('svcSettings'), action: function () { App.show('settings'); } }
    ];
    if (App.search) {
      items.push({ id: '__search', name: t('searchPrefix') + ' ' + App.search.q, count: App.listFor('__search').length });
    }
    if (!App.channels.length) { this.groupItems = items; return; }
    items.push(
      { id: '__fav', name: t('favorites'), count: App.listFor('__fav').length },
      { id: '__all', name: t('allChannels'), count: App.listFor('__all').length }
    );
    App.groupOrder.forEach(function (g) {
      if (App.isHidden(g)) return;
      var locked = App.isLocked(g);
      items.push({ id: g, name: g || t('noGroup'), count: App.groupMap[g].length,
                   locked: locked, closed: locked && !App.isOpen(g) });
    });
    this.groupItems = items;
  },

  indexOfGroup: function (gid) {
    for (var i = 0; i < this.groupItems.length; i++) if (this.groupItems[i].id === gid) return i;
    return -1;
  },

  // Курсор на группе. Уход курсора из открытой PIN-кодом группы — ещё не «уход»:
  // флаг сбрасывается только при старте канала из другой группы (Player.start).
  openGroup: function (i) {
    var g = this.groupItems[i];
    if (!g) return;
    if (g.svc) { this.drawHeads(); return; }   // служебная строка — список каналов не меняем
    if (this.panel === 'prog') { this.progCh = null; this.showGuideNodes(false); }
    g.closed = g.locked && !App.isOpen(g.id);
    this.groups.render();
    this.gid = g.id;
    var items = App.listFor(g.id);    // для закрытой группы — пусто
    this.chans.setItems(items, this.memory[g.id] || 0);
    this.drawHeads();
    var empty = $('chanEmpty');
    // Закрытая группа — колонка каналов пустая, без текста (как в Roku)
    empty.textContent = g.closed ? '' : g.id === '__fav' ? t('emptyFav') : t('emptyGroup');
    empty.classList.toggle('on', items.length === 0 && !g.closed);
    this.scheduleInfo();
  },

  // OK / вправо на группе: закрытая — спросить PIN, потом перейти к каналам
  enterGroup: function () {
    var self = this, g = this.groupItems[this.groups.index];
    if (g && g.svc) { g.action(); return; }
    if (g && g.closed) {
      App.askPin(function () {
        App.unlockGroup(g.id);
        self.openGroup(self.groups.index);
        self.focusPanel('channels');
      });
      return;
    }
    this.focusPanel('channels');
  },

  focusPanel: function (p) {
    if (p === 'channels' && !this.chans.items.length) p = 'groups';
    if (p === 'prog' && !this.progCh) p = 'channels';
    this.panel = p;
    this.groups.setActive(p === 'groups');
    this.chans.setActive(p === 'channels');
    this.progs.setActive(p === 'prog');
    this.drawHints();
  },

  drawHints: function () {
    $('hintPanels').textContent = t(this.panel === 'prog' ? 'hintBackList' : 'hintPanels');
  },

  // opts.fromPlayer — встать на играющий канал; opts.keep — сохранить текущее место
  enter: function (opts) {
    opts = opts || {};
    this.buildGroups();
    if (this.progCh) { this.progCh = null; this.showGuideNodes(false); }

    var gid = this.gid, chIdx = null, panel = this.panel === 'prog' ? 'channels' : this.panel;
    if (opts.fromPlayer && Player.ch) {
      gid = Player.gid;
      panel = 'channels';
    }
    var gi = this.indexOfGroup(gid);
    if (gi < 0) {
      // Группа исчезла или скрыта; по умолчанию — Избранное, если оно не пустое
      gi = this.indexOfGroup(App.listFor('__fav').length ? '__fav' : '__all');
      panel = 'groups';
    }
    var noChannels = gi < 0;
    if (noChannels) {
      // Каналов нет: при запуске нет ни кэша, ни сети — текст ошибки; иначе подсказка
      gi = this.indexOfGroup('__svc_playlists');
      this.gid = null;
    }
    this.groups.setItems(this.groupItems, gi);
    this.openGroup(gi);
    if (noChannels) {
      this.chans.setItems([], 0);
      $('chanEmpty').textContent = App.loadErr || t('noPlaylistMsg');
      $('chanEmpty').classList.add('on');
    }

    if (opts.fromPlayer && Player.ch) {
      var items = this.chans.items;
      for (var i = 0; i < items.length; i++) {
        if (Player.isCurrent(items[i])) { chIdx = i; break; }
      }
      if (chIdx !== null) { this.chans.setIndex(chIdx); this.memory[this.gid] = chIdx; }
    }
    this.panel = null;
    this.focusPanel(panel);
    this.drawHeads();
  },

  onKey: function (code) {
    if (this.panel === 'prog') return this.onProgKey(code);
    var list = this.panel === 'groups' ? this.groups : this.chans;
    switch (code) {
      case K.UP: list.move(-1); return true;
      case K.DOWN: list.move(1); return true;
      case K.CHUP: list.page(-1); return true;
      case K.CHDN: list.page(1); return true;
      case K.LEFT: this.focusPanel('groups'); return true;
      case K.RIGHT:
        if (this.panel === 'groups') this.enterGroup();
        else if (this.chans.items.length) this.openGuide(this.chans.current());
        return true;
      case K.OK:
        if (this.panel === 'groups') this.enterGroup();
        else if (this.chans.items.length) {
          var ch = this.chans.current();
          if (Player.isCurrent(ch) && Player.state !== 'error') App.show('player');
          else App.playFrom(this.chans.items, this.chans.index, this.gid);
        }
        return true;
      case K.RED: this.openContextMenu(); return true;
      case K.YELLOW:
        if (this.panel === 'channels' && this.chans.items.length) {
          App.toggleFav(this.chans.current());
          this.refreshCounts();
        }
        return true;
      case K.BLUE: App.show('settings'); return true;
      // Back отматывает по шагу: каналы → группы → остановить видео → предложить закрыть
      case K.BACK:
        if (this.panel === 'channels') this.focusPanel('groups');
        else if (Player.ch) { Player.stop(); this.chans.render(); this.drawHeads(); }
        else App.confirmExit();
        return true;
    }
    return false;
  },

  onProgKey: function (code) {
    switch (code) {
      case K.UP: this.progs.move(-1); return true;
      case K.DOWN: this.progs.move(1); return true;
      case K.CHUP: this.progs.page(-1); this.drawProgDesc(); return true;
      case K.CHDN: this.progs.page(1); this.drawProgDesc(); return true;
      case K.OK: this.actOnProgramme(this.progs.current()); return true;
      case K.RED: this.openContextMenu(); return true;
      case K.LEFT: case K.BACK: this.closeGuide(); return true;
      case K.BLUE: App.show('settings'); return true;
      case K.RIGHT: return true;
    }
    return false;
  },

  // Результаты поиска: категория «Поиск: …», фокус на каналах
  showSearch: function () {
    this.memory.__search = 0;
    this.gid = '__search';
    this.enter({});
    var gi = this.indexOfGroup('__search');
    this.groups.setIndex(gi);
    this.openGroup(gi);
    this.focusPanel('channels');
  },

  // После изменения избранного: обновить счётчик и, если мы в Избранном, сам список
  refreshCounts: function () {
    var favIdx = this.indexOfGroup('__fav');
    if (favIdx < 0) return;
    this.groupItems[favIdx].count = App.listFor('__fav').length;
    this.groups.render();
    if (this.gid === '__fav') {
      var keep = this.chans.index;
      this.memory.__fav = keep;
      this.openGroup(favIdx);
      this.focusPanel(this.chans.items.length ? 'channels' : 'groups');
    } else {
      this.chans.render();
    }
  }
};
