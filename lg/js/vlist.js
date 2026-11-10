// Виртуальный список: в DOM живут только видимые строки (10–15 штук),
// поэтому список на 2800 каналов листается так же быстро, как на 20.
//
// opts.rowH       — высота строки, px
// opts.render     — function(rowEl, item, index) — заполняет строку
// opts.onMove     — function(index) — выделение сместилось
// opts.onActivate — function(index) — OK или клик
// opts.onPointer  — function() — пользователь водит курсором Magic Remote
function VList(el, opts) {
  this.el = el;
  this.rowH = opts.rowH;
  this.renderRow = opts.render;
  this.onMove = opts.onMove || function () {};
  this.onActivate = opts.onActivate || function () {};
  this.onPointer = opts.onPointer || function () {};
  this.items = [];
  this.index = 0;
  this.top = 0;
  this.active = false;
  this.pool = [];
  el.classList.add('vlist');

  var self = this;
  el.addEventListener('wheel', function (e) {
    e.preventDefault();
    self.onPointer();
    self.move(e.deltaY > 0 ? 1 : -1, false);
  }, { passive: false });
}

VList.prototype.capacity = function () {
  var h = this.el.clientHeight || this.rowH * 8;
  return Math.max(1, Math.floor(h / this.rowH));
};

VList.prototype.ensurePool = function () {
  var need = this.capacity();
  while (this.pool.length < need) this.addRow(this.pool.length);
};

VList.prototype.addRow = function (slot) {
  var self = this;
  var row = document.createElement('div');
  row.className = 'vrow';
  row.style.height = (this.rowH - 6) + 'px';
  row.addEventListener('mousemove', function () {
    var idx = self.top + slot;
    self.onPointer();
    if (idx < self.items.length && idx !== self.index) {
      self.index = idx;
      self.render();
      self.onMove(idx);
    }
  });
  row.addEventListener('click', function () {
    var idx = self.top + slot;
    if (idx >= self.items.length) return;
    self.onPointer();
    self.index = idx;
    self.render();
    self.onActivate(idx);
  });
  this.el.appendChild(row);
  this.pool.push(row);
};

VList.prototype.clamp = function (i) {
  if (!this.items.length) return 0;
  return Math.max(0, Math.min(this.items.length - 1, i));
};

VList.prototype.setItems = function (items, index) {
  this.items = items || [];
  this.index = this.clamp(index || 0);
  this.top = Math.max(0, this.index - Math.floor(this.capacity() / 2));
  this.render();
};

VList.prototype.setIndex = function (i) {
  this.index = this.clamp(i);
  this.render();
};

// Шаг на одну строку зацикливается (с последней — на первую и обратно).
// wrap = false — у краёв останавливаемся (листание страницами).
VList.prototype.move = function (d, wrap) {
  var n = this.items.length;
  if (!n) return false;
  var ni = this.index + d;
  if (wrap !== false && Math.abs(d) === 1) ni = (ni + n) % n;
  else ni = this.clamp(ni);
  if (ni === this.index) return false;
  this.index = ni;
  this.render();
  this.onMove(ni);
  return true;
};

VList.prototype.page = function (dir) {
  return this.move(dir * (this.capacity() - 1), false);
};

VList.prototype.setActive = function (a) {
  if (this.active === a) return;
  this.active = a;
  this.render();
};

VList.prototype.current = function () { return this.items[this.index]; };

VList.prototype.render = function () {
  this.ensurePool();
  var cap = this.capacity(), len = this.items.length;
  if (this.index < this.top) this.top = this.index;
  if (this.index >= this.top + cap) this.top = this.index - cap + 1;
  this.top = Math.max(0, Math.min(this.top, len - cap));

  for (var i = 0; i < this.pool.length; i++) {
    var row = this.pool[i], idx = this.top + i;
    if (i < cap && idx < len) {
      row.style.display = '';
      row.style.transform = 'translateY(' + (i * this.rowH) + 'px)';
      row.classList.toggle('focus', idx === this.index && this.active);
      row.classList.toggle('sel', idx === this.index && !this.active);
      this.renderRow(row, this.items[idx], idx);
    } else {
      row.style.display = 'none';
    }
  }
};
