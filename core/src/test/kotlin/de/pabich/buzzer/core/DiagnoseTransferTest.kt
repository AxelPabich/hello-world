package de.pabich.buzzer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnoseTransferTest {
    private val report = buildString {
        repeat(600) { appendLine("13.10. 22:58:37.320  Zeile $it – Umlaute äöü ß | & = % +") }
    }

    @Test
    fun `report survives splitting, the codec and reassembly in any order`() {
        val chunks = DiagnoseTransfer.split("r1", "Wally", 12, report)
        assertTrue(chunks.size > 5)
        for (c in chunks) {
            val bytes = MessageCodec.encode(c)
            assertTrue(bytes.size < 32_768, "Nearby-Grenze: ${bytes.size}")
            assertEquals(c, MessageCodec.decode(bytes))
        }
        val collector = DiagnoseTransfer.Collector()
        val shuffled = chunks.reversed()
        shuffled.dropLast(1).forEach { assertNull(collector.add(it)) }
        val done = collector.add(shuffled.last())!!
        assertEquals(report, done.text)
        assertEquals("Wally", done.from)
        assertEquals(12, done.build)
    }

    @Test
    fun `worst case chunk stays below the nearby limit`() {
        val chunk = DiagnoseTransfer.split("r", "X", 1, "€".repeat(DiagnoseTransfer.CHUNK_CHARS)).single()
        assertTrue(MessageCodec.encode(chunk).size < 32_768)
    }

    @Test
    fun `github path is sorted by date and build`() {
        assertEquals("diagnose/2026-10-13/Build-12/Wally-22-58-31.txt", DiagnoseTransfer.path("2026-10-13", "22-58-31", 12, "Wally"))
        assertEquals("diagnose/2026-10-13/Build-12/Axel-Master-01-02-03.txt", DiagnoseTransfer.path("2026-10-13", "01-02-03", 12, "Axel (Master)"))
        assertEquals("diagnose/2026-10-13/Build-7/Jürgen-K-01-02-03.txt", DiagnoseTransfer.path("2026-10-13", "01-02-03", 7, " Jürgen K. /../ "))
        assertEquals("diagnose/d/Build-1/Unbekannt-t.txt", DiagnoseTransfer.path("d", "t", 1, "../"))
    }

    @Test
    fun `diagnose messages survive the codec`() {
        val m = Message.DiagnoseReceived("abc-1")
        assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }
}
