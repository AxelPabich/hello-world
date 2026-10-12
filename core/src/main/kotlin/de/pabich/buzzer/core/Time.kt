package de.pabich.buzzer.core

/** Monotone Uhr in Millisekunden. Auf Android: `SystemClock.uptimeMillis()`, dieselbe Basis wie `MotionEvent.getEventTime()`. */
fun interface Clock {
    fun now(): Long
}

fun interface Cancellable {
    fun cancel()
}

/** Führt Aufgaben verzögert aus. Alle Engine-Aufrufe laufen auf einem Thread (Android: Main-Looper). */
fun interface Scheduler {
    fun schedule(delayMs: Long, block: () -> Unit): Cancellable
}
