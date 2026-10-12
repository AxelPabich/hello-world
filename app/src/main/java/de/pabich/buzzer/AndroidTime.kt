package de.pabich.buzzer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.pabich.buzzer.core.Cancellable
import de.pabich.buzzer.core.Clock
import de.pabich.buzzer.core.Scheduler

/** Gleiche Zeitbasis wie `MotionEvent.getEventTime()` und `PointerInputChange.uptimeMillis`. */
val UptimeClock = Clock { SystemClock.uptimeMillis() }

/** Führt alles auf dem Main-Thread aus – dort laufen auch die Nearby-Callbacks. */
class MainThreadScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0))
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
