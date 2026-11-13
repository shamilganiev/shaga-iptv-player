// ShaGa IPTV Player for Android — AppLog.kt
// Event log: Android logcat (tag ShaGa) + the last records in memory. Access keys are masked.
// Журнал событий: logcat Android (тег ShaGa) + последние записи в памяти. Ключи доступа маскируются.
//
// How to get the log / Как получить журнал:
//   adb logcat -d -s ShaGa:V > shaga-log.txt
// Tags as in Roku / Теги как в Roku: APP, KEY, PLAY, ERR, LOAD, UI.
package com.shaga.player

import android.util.Log

object AppLog {
    const val TAG = "ShaGa"
    private val buf = ArrayDeque<String>()

    // The first line of the log: name and version / Первая строка журнала: название и версия.
    fun start(version: String) = add('I', "=====", "${Config.APP_NAME} v$version =====")

    fun i(tag: String, msg: String) = add('I', tag, msg)
    fun w(tag: String, msg: String) = add('W', tag, msg)
    fun e(tag: String, msg: String) = add('E', tag, msg)

    private fun add(level: Char, tag: String, msg: String) {
        val line = tag + " " + Mask.mask(msg)
        when (level) {
            'E' -> Log.e(TAG, line)
            'W' -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
        synchronized(buf) {
            buf.addLast("$level $line")
            while (buf.size > Config.LOG_MAX) buf.removeFirst()
        }
    }

    // For the future "Settings > Log" screen / Для будущего экрана «Настройки > Журнал».
    fun text(): String = synchronized(buf) { buf.joinToString("\n") }
}
