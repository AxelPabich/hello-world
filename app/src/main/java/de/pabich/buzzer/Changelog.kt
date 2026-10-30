package de.pabich.buzzer

import android.content.Context
import org.json.JSONObject

/** Änderungen je Build aus `aenderungen/aenderungen.json` (als Asset eingebaut) – für „Was ist neu?“. */
object Changelog {
    data class Entry(val build: Int, val date: String, val points: List<String>)

    fun load(context: Context): List<Entry> = runCatching {
        val text = context.assets.open("aenderungen.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val list = JSONObject(text).getJSONArray("eintraege")
        (0 until list.length()).map { i ->
            val e = list.getJSONObject(i)
            val points = e.getJSONArray("punkte")
            Entry(e.optInt("build"), e.optString("datum"), (0 until points.length()).map { points.getString(it) })
        }.filter { it.build > 0 }
    }.onFailure { Diagnostics.log("Änderungen nicht lesbar: ${it.message}") }.getOrDefault(emptyList())

    /** Was seit [lastBuild] neu ist (bis zum installierten Build). */
    fun since(context: Context, lastBuild: Int, ownBuild: Int): List<Entry> =
        load(context).filter { it.build in (lastBuild + 1)..ownBuild }
}
