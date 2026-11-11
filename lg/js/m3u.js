// ShaGa IPTV Player for LG webOS — m3u.js
// M3U/M3U8 playlist parser / Разбор M3U/M3U8 (формат IPTV-плейлистов).
//
// #EXTM3U url-tvg="..."
// #EXTINF:-1 tvg-id="..." tvg-logo="..." group-title="Кино" catchup="shift" catchup-days="7",Название
// http://server/123/index.m3u8?<ключ>=...
var M3U = (function () {

  function parseAttrs(s) {
    var o = {}, re = /([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"/g, m;
    while ((m = re.exec(s))) o[m[1].toLowerCase()] = m[2];
    return o;
  }

  // Название идёт после первой запятой ВНЕ кавычек: в атрибутах тоже бывают запятые.
  function splitInfo(s) {
    var inQ = false;
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (c === '"') inQ = !inQ;
      else if (c === ',' && !inQ) return [s.slice(0, i), s.slice(i + 1)];
    }
    return [s, ''];
  }

  // Атрибуты архива (как в Roku): catchup / catchup-type, catchup-source / catchup-template,
  // catchup-days / tvg-rec / timeshift. Дни без типа означают shift.
  function catchupOf(a) {
    var t = (a['catchup'] || a['catchup-type'] || '').toLowerCase();
    var src = a['catchup-source'] || a['catchup-template'] || '';
    var d = parseInt(a['catchup-days'] || a['tvg-rec'] || a['timeshift'], 10) || 0;
    if (!t && d > 0) t = 'shift';
    if (!t) return null;
    return { t: t, d: d > 0 ? d : 3, src: src };
  }

  function parse(text) {
    text = String(text || '').replace(/^\uFEFF/, '');
    var lines = text.split(/\r?\n/);
    var meta = { epg: '' };
    var channels = [];
    var cur = null, extgrp = '', hdrCatch = null;

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (!line) continue;

      if (/^#EXTM3U/i.test(line)) {
        var h = parseAttrs(line);
        meta.epg = h['url-tvg'] || h['x-tvg-url'] || '';
        hdrCatch = catchupOf(h);
        continue;
      }
      if (/^#EXTINF:/i.test(line)) {
        var parts = splitInfo(line.slice(8));
        var a = parseAttrs(parts[0]);
        cur = {
          name: parts[1].trim(),
          group: (a['group-title'] || '').trim(),
          logo: a['tvg-logo'] || '',
          tvgId: a['tvg-id'] || '',
          catchup: '', catchupDays: 0, catchupSource: ''
        };
        var cu = catchupOf(a) || hdrCatch;
        if (cu) { cur.catchup = cu.t; cur.catchupDays = cu.d; cur.catchupSource = cu.src; }
        extgrp = '';
        continue;
      }
      if (/^#EXTGRP:/i.test(line)) { extgrp = line.slice(8).trim(); continue; }
      if (line.charAt(0) === '#') continue;            // прочие теги (#EXTVLCOPT и т.п.)

      // Строка со ссылкой на поток
      if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(line)) { cur = null; continue; }
      var ch = cur || { name: '', group: '', logo: '', tvgId: '', catchup: '', catchupDays: 0, catchupSource: '' };
      if (!ch.group && extgrp) ch.group = extgrp;
      ch.url = line;
      ch.n = channels.length + 1;
      if (!ch.name) ch.name = 'Channel ' + ch.n;
      channels.push(ch);
      cur = null;
      extgrp = '';
    }
    return { meta: meta, channels: channels };
  }

  return { parse: parse };
})();
