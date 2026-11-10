var ICON_STAR = '<svg viewBox="0 0 24 24"><path d="M12 2.6l2.9 6 6.6.9-4.8 4.6 1.2 6.5L12 17.5l-5.9 3.1 1.2-6.5-4.8-4.6 6.6-.9z"/></svg>';
var ICON_LOCK = '<svg viewBox="0 0 24 24"><path d="M7 10V7.5a5 5 0 0110 0V10h1.5v11h-13V10H7zm2.2 0h5.6V7.5a2.8 2.8 0 00-5.6 0V10z"/></svg>';

function $(id) { return document.getElementById(id); }

// ---------- Короткое сообщение внизу экрана ----------
var Toast = {
  timer: null,
  show: function (msg, ms) {
    var el = $('toast');
    el.textContent = msg;
    el.classList.add('on');
    clearTimeout(this.timer);
    this.timer = setTimeout(function () { el.classList.remove('on'); }, ms || 3000);
  }
};

// ---------- Полноэкранное ожидание ----------
var Busy = {
  show: function (msg) { $('busyText').textContent = msg || ''; $('busy').classList.add('on'); },
  hide: function () { $('busy').classList.remove('on'); }
};

// ---------- Окно с кнопками ----------
var Confirm = {
  isOpen: false,
  idx: 0,
  btns: [],
  onCancel: null,

  // msg — необязательный текст под заголовком
  open: function (title, buttons, onCancel, msg) {
    var self = this;
    this.isOpen = true;
    this.btns = buttons;
    this.onCancel = onCancel || null;
    this.idx = 0;
    $('cfTitle').textContent = title;
    $('cfMsg').textContent = msg || '';
    $('cfMsg').style.display = msg ? '' : 'none';
    var box = $('cfBtns');
    box.innerHTML = '';
    buttons.forEach(function (b, i) {
      var el = document.createElement('button');
      el.className = 'btn';
      el.textContent = b.label;
      el.addEventListener('mousemove', function () { self.idx = i; self.draw(); });
      el.addEventListener('click', function () { self.idx = i; self.choose(); });
      box.appendChild(el);
    });
    $('confirm').classList.add('on');
    this.draw();
  },

  draw: function () {
    var els = $('cfBtns').children;
    for (var i = 0; i < els.length; i++) els[i].classList.toggle('focus', i === this.idx);
  },

  close: function () {
    this.isOpen = false;
    $('confirm').classList.remove('on');
  },

  choose: function () {
    var b = this.btns[this.idx];
    this.close();
    if (b && b.action) b.action();
  },

  onKey: function (code) {
    if (code === K.LEFT) { this.idx = Math.max(0, this.idx - 1); this.draw(); }
    else if (code === K.RIGHT) { this.idx = Math.min(this.btns.length - 1, this.idx + 1); this.draw(); }
    else if (code === K.OK) this.choose();
    else if (code === K.BACK) { this.close(); if (this.onCancel) this.onCancel(); }
    return true; // окно модальное: никакие клавиши не уходят дальше
  }
};

// ---------- Ввод PIN-кода: цифрами пульта или экранной клавиатурой ----------
// Экранная клавиатура нужна для Magic Remote, у которого может не быть цифр.
var PinPad = {
  isOpen: false,
  digits: '',
  idx: 0,
  keys: ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'del', '0', 'ok'],
  onDone: null,
  onCancel: null,

  init: function () {
    var self = this, box = $('ppKeys');
    this.keys.forEach(function (k, i) {
      var el = document.createElement('button');
      el.className = 'btn';
      el.innerHTML = k === 'del' ? '&#9003;' : k === 'ok' ? 'OK' : k;
      el.addEventListener('mousemove', function () { self.idx = i; self.draw(); });
      el.addEventListener('click', function () { self.idx = i; self.press(k); });
      box.appendChild(el);
    });
  },

  // opts.title, opts.hint, opts.onDone(pin, pad), opts.onCancel()
  open: function (opts) {
    this.isOpen = true;
    $('ppHint').textContent = opts.hint || '';
    $('ppHint').style.display = opts.hint ? '' : 'none';
    this.onDone = opts.onDone;
    this.onCancel = opts.onCancel || null;
    this.idx = 0;
    this.reset(opts.title, '');
    $('pinpad').classList.add('on');
  },

  reset: function (title, msg) {
    this.digits = '';
    if (title) $('ppTitle').textContent = title;
    $('ppMsg').textContent = msg || '';
    this.draw();
  },

  close: function () {
    this.isOpen = false;
    $('pinpad').classList.remove('on');
  },

  draw: function () {
    var dots = $('ppDots').children, keys = $('ppKeys').children;
    for (var i = 0; i < dots.length; i++) dots[i].classList.toggle('full', i < this.digits.length);
    for (var j = 0; j < keys.length; j++) keys[j].classList.toggle('focus', j === this.idx);
  },

  press: function (k) {
    if (k === 'del') { this.digits = this.digits.slice(0, -1); this.draw(); return; }
    if (k === 'ok') { if (this.digits.length === 4) this.submit(); return; }
    if (this.digits.length >= 4) return;
    this.digits += k;
    this.draw();
    if (this.digits.length === 4) {
      var self = this;
      setTimeout(function () { self.submit(); }, 150);
    }
  },

  submit: function () {
    var pin = this.digits;
    if (pin.length !== 4 || !this.isOpen) return;
    this.onDone(pin, this);
  },

  onKey: function (code) {
    var d = digitOf(code);
    if (d >= 0) { this.press(String(d)); return true; }
    var i = this.idx;
    if (code === K.LEFT && i % 3 > 0) i--;
    else if (code === K.RIGHT && i % 3 < 2) i++;
    else if (code === K.UP && i >= 3) i -= 3;
    else if (code === K.DOWN && i < 9) i += 3;
    else if (code === K.OK) { this.press(this.keys[this.idx]); return true; }
    else if (code === K.BACK) { this.close(); if (this.onCancel) this.onCancel(); return true; }
    this.idx = i;
    this.draw();
    return true;
  }
};

// ---------- Набор номера канала цифрами пульта ----------
var Num = {
  buf: '',
  timer: null,

  press: function (d) {
    if (this.buf.length >= 4) this.buf = '';
    this.buf += String(d);
    var el = $('numosd');
    el.textContent = this.buf + (this.buf.length < 4 ? '_' : '');
    el.classList.add('on');
    clearTimeout(this.timer);
    var self = this;
    this.timer = setTimeout(function () { self.commit(); }, 1600);
  },

  commit: function () {
    clearTimeout(this.timer);
    $('numosd').classList.remove('on');
    var n = parseInt(this.buf, 10);
    this.buf = '';
    if (n) App.playNumber(n);
  }
};

// ---------- Вертикальное меню (плейлисты, язык, режим группы) ----------
// items: [{ label, mark (текущий — «* »), action }]. Список зациклен.
var Menu = {
  isOpen: false,
  idx: 0,
  items: [],
  onCancel: null,

  open: function (title, msg, items, opts) {
    opts = opts || {};
    var self = this;
    this.isOpen = true;
    this.items = items;
    this.onCancel = opts.onCancel || null;
    this.idx = opts.focus != null ? opts.focus : 0;
    $('mnTitle').textContent = title;
    $('mnMsg').textContent = msg || '';
    $('mnMsg').style.display = msg ? '' : 'none';
    var box = $('mnList');
    box.innerHTML = '';
    items.forEach(function (it, i) {
      var row = document.createElement('div');
      row.className = 'm-row';
      row.textContent = (it.mark ? '* ' : '') + it.label;
      row.addEventListener('mousemove', function () { if (self.idx !== i) { self.idx = i; self.draw(); } });
      row.addEventListener('click', function () { self.idx = i; self.choose(); });
      box.appendChild(row);
    });
    $('menu').classList.add('on');
    this.draw();
  },

  draw: function () {
    var rows = $('mnList').children;
    for (var i = 0; i < rows.length; i++) rows[i].classList.toggle('focus', i === this.idx);
  },

  close: function () {
    this.isOpen = false;
    $('menu').classList.remove('on');
  },

  choose: function () {
    var it = this.items[this.idx];
    this.close();
    if (it && it.action) it.action();
  },

  onKey: function (code) {
    var n = this.items.length;
    if (code === K.UP) { this.idx = (this.idx - 1 + n) % n; this.draw(); }
    else if (code === K.DOWN) { this.idx = (this.idx + 1) % n; this.draw(); }
    else if (code === K.OK) this.choose();
    else if (code === K.BACK) { this.close(); if (this.onCancel) this.onCancel(); }
    return true;
  }
};

// ---------- Окно ввода текста (ссылка на плейлист, поиск) ----------
// opts: title, msg, value, placeholder, onSubmit(value, prompt), onCancel()
// onSubmit сам решает: prompt.close() или prompt.error('текст').
var Prompt = {
  isOpen: false,
  idx: 0,          // 0 — поле, 1 — OK, 2 — Отмена
  opts: null,

  init: function () {
    var self = this;
    this.input = $('prInput');
    this.els = [this.input, $('prOk'), $('prCancel')];
    this.els.forEach(function (el, i) {
      el.addEventListener('mousemove', function () { if (self.idx !== i) { self.idx = i; self.draw(); } });
    });
    $('prOk').addEventListener('click', function () { self.submit(); });
    $('prCancel').addEventListener('click', function () { self.cancel(); });
  },

  open: function (opts) {
    this.isOpen = true;
    this.opts = opts;
    $('prTitle').textContent = opts.title;
    $('prMsg').textContent = opts.msg || '';
    $('prOk').textContent = t('ok');
    $('prCancel').textContent = t('cancel');
    this.input.value = opts.value || '';
    this.input.placeholder = opts.placeholder || '';
    this.error('');
    $('prompt').classList.add('on');
    this.idx = 0;
    this.draw();
    // сразу открываем клавиатуру — пользователь пришёл сюда, чтобы печатать
    var inp = this.input;
    setTimeout(function () { inp.focus(); }, 50);
  },

  draw: function () {
    for (var i = 0; i < this.els.length; i++) this.els[i].classList.toggle('focus', i === this.idx);
  },

  error: function (msg) { $('prError').textContent = msg || ''; },

  close: function () {
    this.isOpen = false;
    this.input.blur();
    $('prompt').classList.remove('on');
  },

  submit: function () {
    this.input.blur();
    this.opts.onSubmit(this.input.value, this);
  },

  cancel: function () {
    this.close();
    if (this.opts.onCancel) this.opts.onCancel();
  },

  onKey: function (code) {
    if (document.activeElement === this.input) {
      // Пока открыта клавиатура, перехватываем только навигацию — буквы идут в поле
      if (code === K.OK) { this.submit(); return true; }
      if (code === K.DOWN) { this.input.blur(); this.idx = 1; this.draw(); return true; }
      if (code === K.BACK) { this.input.blur(); return true; }
      return false;
    }
    if (code === K.UP) { this.idx = 0; this.draw(); }
    else if (code === K.DOWN) { if (this.idx === 0) { this.idx = 1; this.draw(); } }
    else if (code === K.LEFT) { if (this.idx === 2) { this.idx = 1; this.draw(); } }
    else if (code === K.RIGHT) { if (this.idx === 1) { this.idx = 2; this.draw(); } }
    else if (code === K.OK) {
      if (this.idx === 0) this.input.focus();
      else if (this.idx === 1) this.submit();
      else this.cancel();
    }
    else if (code === K.BACK) this.cancel();
    return true;
  }
};
