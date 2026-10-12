package de.pabich.buzzer.core

import java.util.PriorityQueue
import kotlin.random.Random

/** Virtuelle Zeit: ein gemeinsamer Ereignis-Takt für alle simulierten Geräte. */
class VirtualTime : Scheduler {
    private class Task(val at: Long, val seq: Long, val block: () -> Unit) {
        var cancelled = false
    }

    var now = 0L
        private set
    private var seq = 0L
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))

    override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
        val task = Task(now + delayMs.coerceAtLeast(0), seq++, block)
        queue += task
        return Cancellable { task.cancelled = true }
    }

    fun advanceTo(time: Long) {
        while (true) {
            val next = queue.peek() ?: break
            if (next.at > time) break
            queue.poll()
            now = next.at
            if (!next.cancelled) next.block()
        }
        now = maxOf(now, time)
    }

    fun advanceBy(ms: Long) = advanceTo(now + ms)
}

/** Ein simuliertes Spieler-Gerät mit eigener Uhr (Abweichung) und Funk-Laufzeit. */
class SimPlayer(
    val id: PlayerId,
    val clockOffsetMs: Long,
    val latencyMs: Long,
    val engine: PlayerEngine,
    val clock: Clock,
)

class SimGame(
    settings: Settings = Settings(),
    seed: Int = 1,
) {
    val time = VirtualTime()
    val masterClock = Clock { time.now }
    private val simPlayers = mutableMapOf<PlayerId, SimPlayer>()
    val received = mutableMapOf<PlayerId, MutableList<Message>>()
    val masterLog = mutableListOf<String>()
    val playerLogs = mutableMapOf<PlayerId, MutableList<String>>()

    val master = MasterEngine(masterClock, time, Random(seed), log = { masterLog += it }) { to, msg ->
        val p = simPlayers.getValue(to)
        received.getOrPut(to) { mutableListOf() } += msg
        // Über die Funkstrecke: kodieren, verzögern, dekodieren.
        val bytes = MessageCodec.encode(msg)
        time.schedule(p.latencyMs) { p.engine.onMessage(MessageCodec.decode(bytes)!!) }
    }

    init {
        master.updateSettings(settings)
    }

    fun addPlayer(id: PlayerId, clockOffsetMs: Long, latencyMs: Long): SimPlayer {
        val clock = Clock { time.now + clockOffsetMs }
        lateinit var sim: SimPlayer
        val logLines = playerLogs.getOrPut(id) { mutableListOf() }
        val engine = PlayerEngine(clock, time, log = { logLines += it }) { msg ->
            val bytes = MessageCodec.encode(msg)
            time.schedule(sim.latencyMs) { master.onMessage(id, MessageCodec.decode(bytes)!!) }
        }
        sim = SimPlayer(id, clockOffsetMs, latencyMs, engine, clock)
        simPlayers[id] = sim
        master.addPlayer(id, id)
        return sim
    }

    fun player(id: PlayerId) = simPlayers.getValue(id)

    /** Startet den Uhrenabgleich und lässt ihn einschwingen. */
    fun syncClocks() {
        master.start()
        time.advanceBy(5_000)
    }

    /** Zeitpunkt (virtuell), zu dem der Spieler gelb sieht – also seine lokale Freigabezeit. */
    fun localGoAt(id: PlayerId): Long =
        received.getValue(id).filterIsInstance<Message.GoAt>().last().goAtLocal

    /** Spieler tippt [reactionMs] nach seiner lokalen Freigabe (in virtueller Zeit). */
    fun scheduleTap(id: PlayerId, reactionMs: Long) {
        val p = player(id)
        val atVirtual = localGoAt(id) - p.clockOffsetMs + reactionMs
        time.schedule(atVirtual - time.now) { p.engine.onTouchDown(p.clock.now()) }
    }

    fun scheduleRelease(id: PlayerId, reactionMs: Long) {
        val p = player(id)
        val atVirtual = localGoAt(id) - p.clockOffsetMs + reactionMs
        time.schedule(atVirtual - time.now) { p.engine.onTouchUp(p.clock.now()) }
    }

    fun touchDownNow(id: PlayerId) = player(id).let { it.engine.onTouchDown(it.clock.now()) }
    fun touchUpNow(id: PlayerId) = player(id).let { it.engine.onTouchUp(it.clock.now()) }

    fun view(id: PlayerId) = player(id).engine.view
    fun row(id: PlayerId) = master.snapshot().players.first { it.id == id }
}
