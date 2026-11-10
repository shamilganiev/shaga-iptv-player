// Перенесено из Roku-версии (source/textutil.brs, storage.brs, groups.brs) один к одному,
// чтобы обе версии находили одни и те же каналы и называли плейлисты одинаково.
var Text = {

  // Нижний регистр + ё→е + схлопывание пробелов
  norm: function (s) {
    if (s == null) return '';
    return String(s).toLowerCase().replace(/ё/g, 'е').replace(/ {2,}/g, ' ').trim();
  },

  MAP: {
    'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'е': 'e', 'ж': 'zh', 'з': 'z',
    'и': 'i', 'й': 'y', 'к': 'k', 'л': 'l', 'м': 'm', 'н': 'n', 'о': 'o', 'п': 'p',
    'р': 'r', 'с': 's', 'т': 't', 'у': 'u', 'ф': 'f', 'х': 'h', 'ц': 'c', 'ч': 'ch',
    'ш': 'sh', 'щ': 'sch', 'ъ': '', 'ы': 'y', 'ь': '', 'э': 'e', 'ю': 'yu', 'я': 'ya',
    'і': 'i', 'ї': 'i', 'є': 'e', 'ґ': 'g', 'ў': 'u',
    'ә': 'a', 'ө': 'o', 'ү': 'u', 'ғ': 'g', 'ҡ': 'k', 'ң': 'n', 'ҙ': 'z', 'ҫ': 's', 'һ': 'h', 'җ': 'zh',
    'á': 'a', 'à': 'a', 'â': 'a', 'ä': 'a', 'ã': 'a', 'å': 'a',
    'é': 'e', 'è': 'e', 'ê': 'e', 'ë': 'e',
    'í': 'i', 'ì': 'i', 'î': 'i', 'ï': 'i',
    'ó': 'o', 'ò': 'o', 'ô': 'o', 'ö': 'o', 'õ': 'o',
    'ú': 'u', 'ù': 'u', 'û': 'u', 'ü': 'u',
    'ñ': 'n', 'ç': 'c'
  },

  // Сводим варианты латиницы: kh→h, ts→c, j→y, w→v, x→ks, q→k, ie→e, ye→e
  fuzzLatin: function (s) {
    return s.split('kh').join('h').split('ts').join('c').split('j').join('y').split('w').join('v')
      .split('x').join('ks').split('q').join('k').split('ie').join('e').split('ye').join('e');
  },

  // norm(s) → латиница → fuzzLatin
  fuzz: function (norm) {
    var out = '', map = this.MAP;
    for (var i = 0; i < norm.length; i++) {
      var ch = norm.charAt(i);
      if (ch.charCodeAt(0) < 128) out += ch;
      else out += map[ch] != null ? map[ch] : ch;
    }
    return this.fuzzLatin(out);
  },

  // Ключ поиска канала: «нормализованный|латиница»
  searchKey: function (title) {
    var n = this.norm(title);
    return n + '|' + this.fuzz(n);
  },

  matches: function (key, qNorm, qFuzz) {
    if (!qNorm) return false;
    if (key.indexOf(qNorm) >= 0) return true;
    return !!qFuzz && key.indexOf(qFuzz) >= 0;
  },

  // ---------- ссылки ----------
  MAX_URL: 2048,

  // Пробелы убираются, без схемы добавляется https://, схема — в нижний регистр
  normalizeUrl: function (raw) {
    var s = String(raw == null ? '' : raw).trim().replace(/ /g, '');
    if (!s) return '';
    var low = s.toLowerCase();
    if (low.indexOf('://') < 0) s = 'https://' + s;
    else if (low.slice(0, 7) === 'http://') s = 'http://' + s.slice(7);
    else if (low.slice(0, 8) === 'https://') s = 'https://' + s.slice(8);
    return s;
  },

  isValidUrl: function (s) {
    var low = s.toLowerCase();
    if (low.slice(0, 7) !== 'http://' && low.slice(0, 8) !== 'https://') return false;
    var rest = s.slice(s.indexOf('://') + 3);
    var sl = rest.indexOf('/');
    var host = sl >= 0 ? rest.slice(0, sl) : rest;
    if (host.length < 3) return false;
    return host.indexOf('.') >= 0 || host.indexOf(':') >= 0;
  },

  // «сайт · имя файла», если имя файла не длиннее 24 символов; без логина и пароля
  playlistName: function (url) {
    var s = url, p = s.indexOf('://');
    if (p >= 0) s = s.slice(p + 3);
    var at = s.indexOf('@'), sl = s.indexOf('/');
    if (at >= 0 && (sl < 0 || at < sl)) s = s.slice(at + 1);
    var q = s.indexOf('?');
    if (q >= 0) s = s.slice(0, q);
    var host = s, file = '';
    sl = s.indexOf('/');
    if (sl >= 0) {
      host = s.slice(0, sl);
      var parts = s.slice(sl + 1).split('/');
      file = parts[parts.length - 1];
    }
    return file && file.length <= 24 ? host + ' · ' + file : host;
  },

  // ---------- группы для взрослых ----------
  // Проверка по norm(названия), как в Roku. «ххх» — кириллицей.
  ADULT: ['18+', '+18', '(18)', 'xxx', 'ххх', 'adult', 'porn', 'erotic', 'sexy', 'взросл', 'эрот', 'порн', 'для взрослых'],

  isAdult: function (name) {
    var n = this.norm(name);
    if (!n) return false;
    for (var i = 0; i < this.ADULT.length; i++) if (n.indexOf(this.ADULT[i]) >= 0) return true;
    return false;
  }
};
