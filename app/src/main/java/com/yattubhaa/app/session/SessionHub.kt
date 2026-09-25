package com.yattubhaa.app.session

import android.content.Context
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.service.ControlCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the one live session per role so it survives screen changes and can be reached by the
 * screen-sharing service. There is never more than one: starting a new session ends the old.
 * Every session also runs [SessionService] for as long as it lasts, so its connection survives
 * this app leaving the screen.
 */
object SessionHub {
    private lateinit var app: Context

    @Volatile var needy: NeedySession? = null
        private set
    @Volatile var helper: HelperSession? = null
        private set

    private val _changes = MutableStateFlow(0)

    /** Changes whenever a session starts or ends, however it ended. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun startNeedy(pairing: PairingStore.Record): NeedySession {
        needy?.end("Replaced by a new session.")
        return NeedySession(pairing).also {
            needy = it
            it.start()
            changed()
            SessionService.start(app)
        }
    }

    fun startHelper(pairing: PairingStore.Record, code: String): HelperSession {
        helper?.end("Replaced by a new session.")
        return HelperSession(pairing, code).also {
            helper = it
            it.start()
            changed()
            SessionService.start(app)
        }
    }

    fun endNeedy(reason: String = "Stopped.") {
        needy?.end(reason)
        needy = null
        changed()
    }

    fun endHelper(reason: String = "Stopped.") {
        helper?.end(reason)
        helper = null
        changed()
    }

    /** Called by every session as it ends, including when the other phone ended it. */
    internal fun changed() {
        _changes.update { it + 1 }
    }

    /** Called by the needy session as it ends, however it ended: remote tap and swipe never
     *  stays switched on after a session (see [ControlCapability]). */
    internal fun needyEnded() = ControlCapability.switchOff(app)
}
