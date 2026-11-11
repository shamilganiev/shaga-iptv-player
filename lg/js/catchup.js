// ShaGa IPTV Player for LG webOS — catchup.js
// Archive (catchup) links / Архив (catchup): ссылки на прошлые передачи. Один к одному с Roku (source/catchup.brs).
// Типы:
//   shift      ссылка + ?utc=НАЧАЛО&lutc=СЕЙЧАС
//   append     ссылка + catchup-source (с подстановками)
//   default    catchup-source — вся ссылка
//   flussonic  .../name.m3u8 -> .../name-НАЧАЛО-ДЛИТЕЛЬНОСТЬ.m3u8 ; /mpegts -> /timeshift_abs-НАЧАЛО.ts
//   xc         Xtream: /live/user/pass/ID.ext -> /timeshift/user/pass/МИНУТЫ/YYYY-MM-DD:HH-MM/ID.ts
// Подстановки: {utc} {start} ${start} {utcend} {end} ${end} {duration} ${duration}
//   {lutc} {now} ${now} {timestamp} {offset} ${offset} {Y} {m} {d} {H} {M} {S}
// Время — секунды UTC.
var Catchup = {

  // Настройки архива канала или null. arch — настройка плейлиста { t, d }:
  // t = auto (из атрибутов плейлиста) | off | shift | flussonic | xc; d — дни.
  info: function (ch, arch) {
    arch = arch || { t: 'auto', d: 3 };
    if (arch.t === 'off') return null;
    if (arch.t === 'auto') {
      if (!ch.catchup) return null;
      return { t: this.normType(ch.catchup, ch.catchupSource || ''), d: ch.catchupDays || 3, src: ch.catchupSource || '' };
    }
    return { t: arch.t, d: arch.d || 3, src: '' };
  },

  normType: function (t, src) {
    t = String(t).toLowerCase();
    if (t === 'fs' || t.indexOf('flussonic') === 0) return 'flussonic';
    if (t === 'xc' || t === 'xtream') return 'xc';
    if (t === 'append' || t === 'default' || t === 'shift') return t;
    return src ? 'default' : 'shift';
  },

  // Можно ли сейчас смотреть из архива передачу, начавшуюся в s (секунды)?
  covers: function (info, s, now) {
    return !!info && s < now && s >= now - info.d * 86400;
  },

  url: function (url, info, start, finish, now) {
    var dur = Math.max(60, finish - start);
    if (info.t === 'append') return url + this.fill(info.src, start, finish, now);
    if (info.t === 'default' && info.src) return this.fill(info.src, start, finish, now);
    if (info.t === 'flussonic') return this.flussonic(url, start, dur);
    if (info.t === 'xc') { var x = this.xtream(url, start, dur); if (x) return x; }
    return url + (url.indexOf('?') >= 0 ? '&' : '?') + 'utc=' + start + '&lutc=' + now;
  },

  fill: function (tpl, start, finish, now) {
    var d = new Date(start * 1000), p2 = this.p2;
    var map = {
      '${start}': start, '{utc}': start, '{start}': start,
      '${end}': finish, '{utcend}': finish, '{end}': finish,
      '${duration}': finish - start, '{duration}': finish - start,
      '${now}': now, '{lutc}': now, '{now}': now, '{timestamp}': now,
      '${offset}': now - start, '{offset}': now - start,
      '{Y}': d.getUTCFullYear(), '{m}': p2(d.getUTCMonth() + 1), '{d}': p2(d.getUTCDate()),
      '{H}': p2(d.getUTCHours()), '{M}': p2(d.getUTCMinutes()), '{S}': p2(d.getUTCSeconds())
    };
    var s = tpl;
    Object.keys(map).forEach(function (k) { s = s.split(k).join(String(map[k])); });
    return s;
  },

  flussonic: function (url, start, dur) {
    var qi = url.indexOf('?'), base = qi >= 0 ? url.slice(0, qi) : url, q = qi >= 0 ? url.slice(qi) : '';
    if (/\/mpegts$/.test(base)) return base.slice(0, -7) + '/timeshift_abs-' + start + '.ts' + q;
    if (/\.m3u8$/i.test(base)) return base.slice(0, -5) + '-' + start + '-' + dur + '.m3u8' + q;
    return base + '/index-' + start + '-' + dur + '.m3u8' + q;
  },

  // Xtream timeshift (начало пишется в UTC)
  xtream: function (url, start, dur) {
    var m = /^(https?:\/\/[^\/]+)\/(.*)$/i.exec(url);
    if (!m) return '';
    var segs = m[2].split('/');
    if (segs.length && segs[0].toLowerCase() === 'live') segs.shift();
    if (segs.length < 3) return '';
    var id = segs[2].split('.')[0], d = new Date(start * 1000), p2 = this.p2;
    var stamp = d.getUTCFullYear() + '-' + p2(d.getUTCMonth() + 1) + '-' + p2(d.getUTCDate()) + ':' +
      p2(d.getUTCHours()) + '-' + p2(d.getUTCMinutes());
    return m[1] + '/timeshift/' + segs[0] + '/' + segs[1] + '/' + Math.ceil(dur / 60) + '/' + stamp + '/' + id + '.ts';
  },

  p2: function (n) { return (n < 10 ? '0' : '') + n; }
};
