// Первый запуск: приветствие (как в Roku). Каналов в приложении нет — нужен свой плейлист.
// OK — окно ввода ссылки. Демо-плейлиста нет: тестовый плейлист для магазина — в заметках к отправке.
var Setup = {
  el: null,

  init: function () {
    this.el = $('setup');
    $('btnAdd').addEventListener('click', function () { App.addPlaylist(); });
  },

  enter: function () {
    $('btnAdd').classList.add('focus');
  },

  onKey: function (code) {
    if (code === K.OK) { App.addPlaylist(); return true; }
    if (code === K.BACK) { App.confirmExit(); return true; }
    return code === K.UP || code === K.DOWN || code === K.LEFT || code === K.RIGHT;
  }
};
