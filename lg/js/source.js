// Слой «откуда берётся плейлист». Сейчас только { type: 'url' }.
// QR-передача добавится сюда же отдельным type, остальной код не изменится.
var Source = {
  TIMEOUT: 20000,

  // fetch с cache: 'no-store' — всегда свежий плейлист, мимо HTTP-кэша
  // (иначе «обновить» могло молча вернуть старую копию). Заголовков не добавляем,
  // чтобы не было лишних CORS-проверок на серверах провайдеров.
  fetchText: function (url) {
    var self = this, t0 = Date.now();
    Log.info('Плейлист: загрузка ' + Log.url(url));
    var ctrl = window.AbortController ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, this.TIMEOUT);
    return fetch(url, { cache: 'no-store', signal: ctrl ? ctrl.signal : undefined }).then(function (res) {
      if (!res.ok) {
        Log.error('Плейлист: HTTP ' + res.status);
        throw { code: 'http', status: res.status };
      }
      return res.text();
    }).then(function (text) {
      clearTimeout(timer);
      Log.info('Плейлист: получено ' + Math.round(text.length / 1024) + ' КБ за ' + (Date.now() - t0) + ' мс');
      return text;
    }, function (err) {
      clearTimeout(timer);
      if (err && err.code) throw err;
      if (err && err.name === 'AbortError') {
        Log.error('Плейлист: таймаут ' + self.TIMEOUT + ' мс');
        throw { code: 'timeout' };
      }
      Log.error('Плейлист: сетевая ошибка (нет связи или CORS) через ' + (Date.now() - t0) + ' мс');
      throw { code: 'network' };
    });
  },

  load: function (src) {
    if (!src || src.type !== 'url') return Promise.reject({ code: 'network' });
    return this.fetchText(src.url).then(function (text) {
      var t1 = Date.now();
      var res = M3U.parse(text);
      var groups = {};
      res.channels.forEach(function (c) { groups[c.group] = 1; });
      Log.info('Плейлист: разобрано ' + res.channels.length + ' каналов, ' + Object.keys(groups).length +
        ' групп за ' + (Date.now() - t1) + ' мс; EPG: ' + (res.meta.epg || 'нет'));
      if (!res.channels.length) { Log.warn('Плейлист: каналов нет, начало файла: ' + String(text).slice(0, 120)); throw { code: 'empty' }; }
      return res;
    });
  }
};
