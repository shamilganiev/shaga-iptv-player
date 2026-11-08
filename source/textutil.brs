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
