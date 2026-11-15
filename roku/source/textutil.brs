' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Text helpers for search: Unicode lowercase, normalization, transliteration.
' The Roku on-screen keyboard is Latin-only, so "pervyy" must find "Первый"
' and "espana" must find "España".
' Помощники для поиска: нижний регистр Unicode, нормализация, транслит.
' Экранная клавиатура Roku — только латиница, поэтому "pervyy" должно находить
' "Первый", а "espana" — "España".

' Lowercase incl. Cyrillic and Latin-1 (LCase reliably handles only ASCII)
' Нижний регистр, включая кириллицу и Latin-1 (LCase надёжен только для ASCII)
function lowerU(s as string) as string
    out = ""
    n = Len(s)
    for i = 1 to n
        ch = Mid(s, i, 1)
        c = Asc(ch)
        if c >= 65 and c <= 90 then
            out = out + Chr(c + 32)
        else if c < 128 then
            out = out + ch
        else if c >= &h0410 and c <= &h042F then
            out = out + Chr(c + 32)                 ' А-Я
        else if c >= &h0400 and c <= &h040F then
            out = out + Chr(c + 80)                 ' Ѐ-Џ (Ё, Є, І, Ї, Ў ...)
        else if c >= &h0460 and c <= &h052F and (c mod 2) = 0 then
            out = out + Chr(c + 1)                  ' extended Cyrillic pairs / расширенная кириллица
        else if c >= &h00C0 and c <= &h00DE and c <> &h00D7 then
            out = out + Chr(c + 32)                 ' Latin-1 capitals / заглавные Latin-1
        else
            out = out + ch
        end if
    end for
    return out
end function

' Lowercase + ё→е + collapse spaces / Нижний регистр + ё→е + схлопывание пробелов
function normText(s as dynamic) as string
    if s = invalid then return ""
    t = lowerU(s.ToStr()).Replace("ё", "е")
    while t.Instr("  ") >= 0
        t = t.Replace("  ", " ")
    end while
    return t.Trim()
end function

function translitMap() as object
    if m.tlMap = invalid then
        m.tlMap = {
            "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ж": "zh", "з": "z",
            "и": "i", "й": "y", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p",
            "р": "r", "с": "s", "т": "t", "у": "u", "ф": "f", "х": "h", "ц": "c", "ч": "ch",
            "ш": "sh", "щ": "sch", "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
            "і": "i", "ї": "i", "є": "e", "ґ": "g", "ў": "u",
            "ә": "a", "ө": "o", "ү": "u", "ғ": "g", "ҡ": "k", "ң": "n", "ҙ": "z", "ҫ": "s", "һ": "h", "җ": "zh",
            "á": "a", "à": "a", "â": "a", "ä": "a", "ã": "a", "å": "a",
            "é": "e", "è": "e", "ê": "e", "ë": "e",
            "í": "i", "ì": "i", "î": "i", "ï": "i",
            "ó": "o", "ò": "o", "ô": "o", "ö": "o", "õ": "o",
            "ú": "u", "ù": "u", "û": "u", "ü": "u",
            "ñ": "n", "ç": "c"
        }
    end if
    return m.tlMap
end function

' Latin spelling variants folded together: kh→h, ts→c, j→y, w→v, x→ks, q→k, ie→e, ye→e
' Сводим варианты латиницы: kh→h, ts→c, j→y, w→v, x→ks, q→k, ie→e, ye→e
function fuzzLatin(s as string) as string
    t = s.Replace("kh", "h").Replace("ts", "c").Replace("j", "y").Replace("w", "v")
    t = t.Replace("x", "ks").Replace("q", "k").Replace("ie", "e").Replace("ye", "e")
    return t
end function

' normText(s) → Latin → fuzzLatin / normText → латиница → fuzzLatin
function fuzzTranslit(norm as string) as string
    mp = translitMap()
    out = ""
    n = Len(norm)
    for i = 1 to n
        ch = Mid(norm, i, 1)
        if Asc(ch) < 128 then
            out = out + ch
        else
            v = mp[ch]
            if v <> invalid then out = out + v else out = out + ch
        end if
    end for
    return fuzzLatin(out)
end function

' Search key stored with each channel: "normalized|fuzzy-latin"
' Ключ поиска для каждого канала: "нормализованный|латиница"
function searchKey(title as string) as string
    n = normText(title)
    return n + "|" + fuzzTranslit(n)
end function

' Does the channel key match the query? / Подходит ли канал под запрос?
function keyMatches(key as string, qNorm as string, qFuzz as string) as boolean
    if qNorm = "" then return false
    if key.Instr(qNorm) >= 0 then return true
    if qFuzz <> "" and key.Instr(qFuzz) >= 0 then return true
    return false
end function

' TV guide name key: normText without spaces and punctuation ("Первый канал HD" → "первыйканалhd")
' Ключ названия для телепрограммы: normText без пробелов и знаков
function epgNorm(s as dynamic) as string
    n = normText(s)
    for each c in [" ", ".", ",", "-", "_", ":", ";", "!", "?", "'", Chr(34), "(", ")", "[", "]", "/", "|", "«", "»", "&"]
        n = n.Replace(c, "")
    end for
    return n
end function

' ---------- 1.4.0: search pattern with masks / шаблон поиска с масками ----------
' The query becomes a regular expression applied to RAW titles (160 000 programme titles are
' not normalized one by one — too slow on Roku). Rules:
'   * — any number of any characters (also none), ? — exactly one character;
'   a leading and a trailing * are always implied (substring search);
'   letter case does not matter, е = ё; Latin letters also find Cyrillic (translit):
'   "garri*potter" finds «Гарри Поттер и философский камень».
' Non-ASCII letters are written as alternatives (а|А), never as [classes]: this works
' whether the regex engine counts bytes or characters.
' Запрос превращается в регулярное выражение по ИСХОДНЫМ названиям (нормализовать 160 000
' названий по одному на Roku слишком долго). Правила: * — любое число любых символов (и ноль),
' ? — ровно один символ; * в начале и в конце подразумевается всегда; регистр не важен,
' е = ё; латиница находит и кириллицу (транслит).
function findOneChar() as string
    return "(?:[^\x80-\xBF][\x80-\xBF]*)"
end function

function upperCyr(ch as string) as string
    c = Asc(ch)
    if c >= &h0430 and c <= &h044F then return Chr(c - 32)
    if c >= &h0450 and c <= &h045F then return Chr(c - 80)
    if c >= &h00E0 and c <= &h00FE and c <> &h00F7 then return Chr(c - 32)
    return ch
end function

' (?:x|X) for one lowercase non-ASCII letter; е also matches ё / для одной буквы; е = ё
function findCyrAlt(ch as string) as string
    u = upperCyr(ch)
    if ch = "е" then return "(?:е|Е|ё|Ё)"
    if u = ch then return ch
    return "(?:" + ch + "|" + u + ")"
end function

function findLatinTable() as object
    if m.findLat = invalid then
        m.findLat = {
            multi: [["shch", "щ"], ["sch", "щ"], ["sh", "ш"], ["ch", "ч"], ["zh", "ж"], ["kh", "х"], ["ts", "ц"], ["yu", "ю"], ["ya", "я"], ["yo", "е"], ["ye", "е"], ["ju", "ю"], ["ja", "я"]]
            one: {
                a: ["а"], b: ["б"], v: ["в"], w: ["в"], g: ["г"], d: ["д"], e: ["е", "э"], z: ["з"],
                i: ["и", "й", "ы", "і"], y: ["й", "ы", "и"], j: ["й", "ж"], k: ["к"], l: ["л"], m: ["м"],
                n: ["н"], o: ["о"], p: ["п"], r: ["р"], s: ["с"], t: ["т"], u: ["у"], f: ["ф"],
                h: ["х", "г"], c: ["ц", "к", "с"], x: ["кс", "х"], q: ["к"]
            }
        }
    end if
    return m.findLat
end function

' One Latin letter: itself in both cases or its Cyrillic readings / Одна латинская буква
function findLatinOne(ch as string) as string
    alts = findLatinTable().one[ch]
    out = "(?:[" + ch + UCase(ch) + "]"
    if alts <> invalid then
        for each a in alts
            part = ""
            for i = 1 to Len(a)
                part = part + findCyrAlt(Mid(a, i, 1))
            end for
            out = out + "|" + part
        end for
    end if
    return out + ")"
end function

' Returns a roRegex or invalid (empty query) / Возвращает roRegex или invalid (пустой запрос)
function buildFindRegex(query as dynamic) as dynamic
    q = normText(query)
    while q.Left(1) = "*"
        q = q.Mid(1)
    end while
    while q.Right(1) = "*"
        q = q.Left(Len(q) - 1)
    end while
    q = q.Trim()
    if q = "" then return invalid
    tbl = findLatinTable()
    soft = "(?:ь|Ь|ъ|Ъ)?"
    special = "\.^$|()[]{}+-/"
    out = ""
    n = Len(q)
    i = 1
    while i <= n
        ch = Mid(q, i, 1)
        c = Asc(ch)
        if ch = "*" then
            out = out + ".*"
            i = i + 1
        else if ch = "?" then
            out = out + findOneChar()
            i = i + 1
        else if ch = " " then
            out = out + "\s+"
            i = i + 1
        else if c >= 97 and c <= 122 then
            done = false
            for each mt in tbl.multi
                l = Len(mt[0])
                if Mid(q, i, l) = mt[0] then
                    seq = ""
                    for j = 1 to l
                        seq = seq + findLatinOne(Mid(mt[0], j, 1))
                    end for
                    out = out + "(?:" + findCyrAlt(mt[1]) + "|" + seq + ")" + soft
                    i = i + l
                    done = true
                    exit for
                end if
            end for
            if not done then
                out = out + findLatinOne(ch) + soft
                i = i + 1
            end if
        else if c < 128 then
            if special.Instr(ch) >= 0 then out = out + "\" + ch else out = out + ch
            i = i + 1
        else
            out = out + findCyrAlt(ch)
            i = i + 1
        end if
    end while
    return CreateObject("roRegex", out, "")
end function
