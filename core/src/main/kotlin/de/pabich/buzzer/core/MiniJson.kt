package de.pabich.buzzer.core

/**
 * Kleiner JSON-Leser und -Schreiber für Fragenpakete und die Spielstatistik – ohne Bibliothek,
 * damit der Kern auch ohne Android läuft. Objekte werden zu [Map] (Reihenfolge bleibt), Arrays zu
 * [List], Zahlen zu [Long] bzw. [Double], `null` zu `null`.
 */
object MiniJson {
    class ParseException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.value()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("Unerwartetes Zeichen an Position ${p.pos}")
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value, 0) }.toString()

    private fun writeTo(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean, is Int, is Long -> sb.append(value.toString())
            is Double -> sb.append(if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString())
            is Float -> writeTo(sb, value.toDouble(), indent)
            is Map<*, *> -> {
                if (value.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    pad(sb, indent + 1)
                    quote(sb, k.toString())
                    sb.append(": ")
                    writeTo(sb, v, indent + 1)
                    if (i < value.size - 1) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, indent)
                sb.append('}')
            }
            is Collection<*> -> {
                if (value.isEmpty()) { sb.append("[]"); return }
                // Kurze Listen aus einfachen Werten bleiben in einer Zeile.
                if (value.all { it == null || it is String || it is Number || it is Boolean } && value.size <= 8) {
                    sb.append('[')
                    value.forEachIndexed { i, v -> if (i > 0) sb.append(", "); writeTo(sb, v, indent) }
                    sb.append(']')
                    return
                }
                sb.append("[\n")
                value.forEachIndexed { i, v ->
                    pad(sb, indent + 1)
                    writeTo(sb, v, indent + 1)
                    if (i < value.size - 1) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, indent)
                sb.append(']')
            }
            else -> quote(sb, value.toString())
        }
    }

    private fun pad(sb: StringBuilder, indent: Int) {
        repeat(indent) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun fail(what: String): Nothing = throw ParseException("$what an Position $pos")

        fun value(): Any? {
            if (atEnd()) fail("Wert erwartet")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Unerwartetes Zeichen '$c'")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("'$word' erwartet")
            pos += word.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (s.getOrNull(pos) == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (s.getOrNull(pos) != '"') fail("Schlüssel erwartet")
                val key = str()
                skipWs()
                if (s.getOrNull(pos) != ':') fail("':' erwartet")
                pos++
                skipWs()
                map[key] = value()
                skipWs()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> fail("',' oder '}' erwartet")
                }
            }
        }

        private fun arr(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skipWs()
            if (s.getOrNull(pos) == ']') { pos++; return list }
            while (true) {
                skipWs()
                list += value()
                skipWs()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> fail("',' oder ']' erwartet")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            pos++
            while (true) {
                if (atEnd()) fail("Text nicht abgeschlossen")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("Escape unvollständig")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("\\u unvollständig")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("Unbekanntes Escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: fail("Zahl ungültig: $text")
        }
    }
}
