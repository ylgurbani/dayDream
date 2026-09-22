package com.yattubhaa.app.session

import com.yattubhaa.app.pairing.PairingStore

/**
 * Holds the one live session per role so it survives screen changes and can be reached by the
 * screen-sharing service. There is never more than one: starting a new session ends the old.
 */
object SessionHub {
    @Volatile var needy: NeedySession? = null
        private set
    @Volatile var helper: HelperSession? = null
        private set

    fun startNeedy(pairing: PairingStore.Record): NeedySession {
        needy?.end("Replaced by a new session.")
        return NeedySession(pairing).also { needy = it; it.start() }
    }

    fun startHelper(pairing: PairingStore.Record, code: String): HelperSession {
        helper?.end("Replaced by a new session.")
        return HelperSession(pairing, code).also { helper = it; it.start() }
    }

    fun endNeedy(reason: String = "Stopped.") {
        needy?.end(reason)
        needy = null
    }

    fun endHelper(reason: String = "Stopped.") {
        helper?.end(reason)
        helper = null
    }
}
