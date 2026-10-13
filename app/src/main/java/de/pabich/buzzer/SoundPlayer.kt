package de.pabich.buzzer

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import de.pabich.buzzer.core.SoundEvent
import de.pabich.buzzer.core.SoundSynth
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Spielt die synthetisch erzeugten Klänge. Beim ersten Start werden sie als WAV in den Cache
 * geschrieben (je Build neu) und in einen SoundPool geladen – das kostet einmalig ein paar Sekunden
 * im Hintergrund; bis dahin bleibt es still.
 */
class SoundPlayer(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = ConcurrentHashMap<SoundEvent, Int>()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded += sampleId }
        val dir = context.cacheDir
        thread(name = "buzzer-sounds", isDaemon = true) {
            for (event in SoundEvent.entries) {
                runCatching {
                    val file = File(dir, "sound_${BuildConfig.VERSION_CODE}_${event.name}.wav")
                    if (!file.exists()) writeWav(file, SoundSynth.render(event))
                    ids[event] = pool.load(file.path, 1)
                }.onFailure { Diagnostics.log("Klang ${event.name} nicht geladen: ${it.message}") }
            }
        }
    }

    /** Spielt einen Klang; liefert die Stream-ID (0 = nicht gespielt). */
    fun play(event: SoundEvent, volume: Float = 1f): Int {
        val id = ids[event] ?: return 0
        if (id !in loaded) return 0
        return pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun stop(streamId: Int) {
        if (streamId != 0) pool.stop(streamId)
    }

    fun release() = pool.release()

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataBytes = pcm.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SoundSynth.SAMPLE_RATE).putInt(SoundSynth.SAMPLE_RATE * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        pcm.forEach { buffer.putShort(it) }
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(buffer.array())
        tmp.renameTo(file)
    }
}
