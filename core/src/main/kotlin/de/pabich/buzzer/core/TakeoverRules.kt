package de.pabich.buzzer.core

/**
 * Sync-Verfahren für die Übernahme der Spielleitung (Punkt 118, ab Build 46).
 *
 * - Jede Gruppe kündigt Spiel-ID und Leitungsnummer (Epoche) an ([PeerInfo.game], [PeerInfo.epoch]).
 * - Fällt der Spielleiter weg, übernimmt Nachfolger 1 der Liste [Message.Succession.order] nach
 *   [waitMs] (5 s), jeder weitere 5 s später – aber nur, wenn bis dahin niemand aus diesem Spiel zu finden ist.
 * - Eine höhere Epoche hat immer Vorrang. Bei gleicher Epoche (zwei haben zugleich übernommen) gewinnt der
 *   vordere Rang. Der alte Spielleiter (niedrigere Epoche) tritt dem Nachfolger bei, nie umgekehrt.
 */
object TakeoverRules {
    const val FIRST_WAIT_MS = 5_000L
    const val STEP_MS = 5_000L

    /** Rang von [name] in der Nachfolger-Liste; wer fehlt, kommt ans Ende. */
    fun rank(s: Message.Succession, name: String): Int =
        s.order.indexOf(name).let { if (it < 0) s.order.size else it }

    /** Wartezeit, bis Rang [rank] selbst übernimmt. */
    fun waitMs(rank: Int): Long = FIRST_WAIT_MS + STEP_MS * rank

    /**
     * Spieler wartet auf einen Nachfolger (Stand [s]): Soll er der gefundenen Gruppe [peer] beitreten?
     * Ja beim Nachfolger (gleiches Spiel, höhere Epoche) und beim zurückgekehrten Spielleiter (gleiche Epoche,
     * oder eine Gruppe ohne Kennung unter dem Namen des Spielleiters – er hat dann noch nie übergeben).
     */
    fun joinWhileWaiting(s: Message.Succession, peer: PeerInfo): Boolean = when (peer.game) {
        s.game -> peer.epoch > s.epoch || (peer.epoch == s.epoch && peer.name == s.master)
        null -> peer.name == s.master
        else -> false
    }

    /**
     * Neuer Spielleiter mit Epoche [myEpoch] (übernommen aus [s]) findet die Gruppe [peer]: abgeben und beitreten?
     * Nur bei höherer Epoche oder bei gleicher Epoche mit vorderem Rang – nie an den Vorgänger.
     */
    fun yieldTo(s: Message.Succession, myEpoch: Int, myName: String, peer: PeerInfo): Boolean {
        if (peer.game != s.game || peer.name == myName) return false
        if (peer.epoch != myEpoch) return peer.epoch > myEpoch
        return rank(s, peer.name) < rank(s, myName)
    }

    /** Spielleiter (Spiel [game], Epoche [epoch]) ohne Spieler findet einen Nachfolger seines Spiels: beitreten. */
    fun joinAsFormerMaster(game: String, epoch: Int, peer: PeerInfo): Boolean = peer.game == game && peer.epoch > epoch
}
