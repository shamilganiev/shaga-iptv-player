// ShaGa IPTV Player for Android — Groups.kt
// Group protection (SPEC.md, section 7): modes Shown / Locked with PIN / Hidden, the "18+ group"
// mark, the PIN code (sha256), the master code, the "PIN entered" flag of one group with its
// 10-minute timer. No Android code here; MainActivity saves the data through Storage.
// Защита групп (SPEC.md, раздел 7): режимы Показана / По PIN-коду / Скрыта, отметка
// «Группа 18+», PIN-код (sha256), мастер-код, флажок «PIN введён» одной группы с таймером
// 10 минут. Кода Android здесь нет; данные сохраняет MainActivity через Storage.
package com.shaga.player

import java.security.MessageDigest
import java.util.Calendar

enum class GroupMode(val code: String) {
    SHOWN("s"), PIN("p"), HIDDEN("h");

    companion object {
        fun of(code: String?): GroupMode? = entries.firstOrNull { it.code == code }
    }
}

object Protection {
    // Signs of an adult group in its name (checked on norm(name)); "ххх" is Cyrillic.
    // Признаки группы для взрослых в названии (по norm(названия)); «ххх» — кириллицей.
    val ADULT = listOf("18+", "+18", "(18)", "xxx", "ххх", "adult", "porn", "erotic", "sexy", "взросл", "эрот", "порн", "для взрослых")

    // The flag is dropped after this time with nothing of its group playing.
    // Флажок сбрасывается через это время, если ничего из его группы не играет.
    const val IDLE_MS = 10 * 60 * 1000L

    // Saved data (keys are group names; "" = channels without a group).
    // Сохраняемые данные (ключи — названия групп; "" = каналы без группы).
    val modes = HashMap<String, GroupMode>()
    val adult = HashMap<String, Boolean>()       // the user's mark; absent = by the name / отметка пользователя; нет = по названию
    val seen = HashSet<String>()                 // groups already checked once / группы, уже проверенные один раз
    var pinHash = ""                             // "" = no PIN / PIN не задан

    // The "PIN entered" flag: one group at most; not saved.
    // Флажок «PIN введён»: не больше одной группы; не сохраняется.
    var openGroup: String? = null
        private set
    private var touched = 0L

    fun clear() {
        modes.clear(); adult.clear(); seen.clear(); pinHash = ""
        openGroup = null; touched = 0L
    }

    fun isAdultName(name: String): Boolean {
        val n = TextUtil.norm(name)
        return n.isNotEmpty() && ADULT.any { n.contains(it) }
    }

    fun hasPin(): Boolean = pinHash.isNotEmpty()
    fun mode(g: String): GroupMode = modes[g] ?: GroupMode.SHOWN
    fun isAdult(g: String): Boolean = adult[g] ?: isAdultName(g)
    fun isHidden(g: String): Boolean = mode(g) == GroupMode.HIDDEN

    // Without a PIN code the lock does not work (deleting the PIN hides such groups anyway).
    // Без PIN-кода замок не действует (при удалении PIN такие группы всё равно скрываются).
    fun isLocked(g: String): Boolean = hasPin() && mode(g) == GroupMode.PIN
    fun isOpen(g: String): Boolean = !isLocked(g) || openGroup == g

    // Channels of the group may be shown in its own list (the group is not hidden, the PIN is
    // entered if needed). / Каналы группы можно показать в её собственном списке.
    fun viewable(g: String): Boolean = !isHidden(g) && isOpen(g)

    // "All channels", favourites, search, autostart, channel number: only groups that are
    // Shown and have no 18+ mark (SPEC, section 7, "Channels of 18+ groups").
    // «Все каналы», избранное, поиск, автозапуск, номер канала: только группы «Показана»
    // без отметки «Группа 18+» (SPEC, раздел 7, «Каналы групп 18+»).
    fun general(g: String): Boolean = !isHidden(g) && !isLocked(g) && !isAdult(g)

    // A group seen for the first time with adult signs in its name is hidden.
    // Returns true when something changed and must be saved.
    // Группа, впервые встреченная с признаками 18+ в названии, скрывается.
    // Возвращает true, если что-то изменилось и надо сохранить.
    fun noteGroups(groups: Collection<String>): Boolean {
        var changed = false
        for (g in groups) {
            if (!seen.add(g)) continue
            changed = true
            if (isAdultName(g) && !modes.containsKey(g)) modes[g] = GroupMode.HIDDEN
        }
        return changed
    }

    // ---------- PIN code / PIN-код ----------

    fun hash(pin: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("shaga:" + pin).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    fun checkPin(pin: String): Boolean = hasPin() && hash(pin) == pinHash

    fun setPin(pin: String) {
        pinHash = hash(pin)
    }

    // Deleting or resetting the PIN: every group "Locked with PIN" becomes "Hidden".
    // Удаление или сброс PIN: все группы «По PIN-коду» становятся «Скрыта».
    fun removePin() {
        pinHash = ""
        for ((g, m) in modes.entries.toList()) if (m == GroupMode.PIN) modes[g] = GroupMode.HIDDEN
        openGroup = null
    }

    // Master code (only for resetting the PIN): local time HHMM (24 h) backwards; the previous
    // minute also fits. 13:45 -> 5431.
    // Мастер-код (только для сброса PIN): местное время ЧЧММ (24 ч) задом наперёд; подходит и
    // предыдущая минута. 13:45 -> 5431.
    fun isMaster(pin: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        for (back in 0..1) {
            val c = Calendar.getInstance()
            c.timeInMillis = nowMs - back * 60_000L
            val hhmm = String.format("%02d%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            if (pin == hhmm.reversed()) return true
        }
        return false
    }

    // ---------- the "PIN entered" flag / флажок «PIN введён» ----------

    fun open(g: String, nowMs: Long) {
        openGroup = g
        touched = nowMs
    }

    fun close() {
        openGroup = null
    }

    // Called every 30 s and when playback stops. playingGroup = the group of the channel that is
    // playing (null = nothing plays). Returns true when the flag has just been dropped.
    // Вызывается раз в 30 с и при остановке видео. playingGroup = группа играющего канала
    // (null = ничего не играет). Возвращает true, если флажок только что сброшен.
    fun tick(nowMs: Long, playingGroup: String?): Boolean {
        val g = openGroup ?: return false
        if (playingGroup == g) {
            touched = nowMs                       // watching: the timer stands / смотрят: таймер стоит
            return false
        }
        if (nowMs - touched < IDLE_MS) return false
        openGroup = null
        return true
    }

    // Playback stopped: the 10 minutes are counted from now.
    // Видео остановили: 10 минут считаются с этого момента.
    fun touch(nowMs: Long) {
        if (openGroup != null) touched = nowMs
    }
}
