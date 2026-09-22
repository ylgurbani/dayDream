package com.yattubhaa.app.pairing

import com.yattubhaa.app.net.Crypto
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a pairing link carries: who is offering to help, which relay to meet at, and the shared
 * secret. The helper builds it and sends it over WhatsApp; the person being helped taps it.
 *
 * This is the one place text from outside enters the app, and a hostile page or app can fire
 * the same link at the phone, so parsing is strict and the caller must always show a
 * confirmation before storing anything.
 */
class PairingLink(val helperName: String, val relayUrl: String, val secret: ByteArray) {

    /** The https link to send. The secret sits in the fragment, which is never sent to a server. */
    fun toShareUrl(): String =
        "${httpBase(relayUrl)}/join#k=${Crypto.b64url(secret)}&n=${enc(helperName)}&r=${enc(relayUrl)}"

    companion object {
        const val SECRET_BYTES = 32
        const val MAX_NAME = 40

        /** Parses the query part of `yattubhaa://pair?k=..&n=..&r=..`. Null if anything is off. */
        fun parse(query: String?, allowCleartext: Boolean): PairingLink? {
            if (query.isNullOrBlank() || query.length > 1024) return null
            val fields = HashMap<String, String>()
            for (pair in query.split('&')) {
                val i = pair.indexOf('=')
                if (i <= 0) return null
                val key = pair.substring(0, i)
                val value = try {
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    return null
                }
                if (fields.put(key, value) != null) return null // a repeated field is suspicious
            }
            val secret = try {
                Crypto.fromB64url(fields["k"] ?: return null)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (secret.size != SECRET_BYTES) return null
            val name = cleanName(fields["n"] ?: return null)
            if (name.isEmpty()) return null
            val relay = normalizeRelayUrl(fields["r"] ?: return null, allowCleartext) ?: return null
            return PairingLink(name, relay, secret)
        }

        /** Accepts wss:// (and ws:// only when [allowCleartext]); returns it without a trailing slash. */
        fun normalizeRelayUrl(raw: String, allowCleartext: Boolean): String? {
            val uri = try { URI(raw.trim()) } catch (e: Exception) { return null }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "wss" && !(allowCleartext && scheme == "ws")) return null
            if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
            if (uri.query != null || uri.fragment != null) return null
            if (!uri.path.isNullOrEmpty() && uri.path != "/") return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "$scheme://${uri.host}$port"
        }

        /**
         * Strips control and invisible formatting characters (including the right-to-left
         * override tricks used to fake names) and limits the length. The name is shown on the
         * screen of someone who may struggle to spot a fake, so it is kept plain.
         */
        fun cleanName(raw: String): String {
            val sb = StringBuilder()
            raw.codePoints().forEach { cp ->
                val type = Character.getType(cp)
                val bad = Character.isISOControl(cp) ||
                    type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                    type == Character.PRIVATE_USE.toInt() ||
                    type == Character.UNASSIGNED.toInt()
                if (!bad) sb.appendCodePoint(cp)
            }
            return sb.toString().trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        }

        fun httpBase(relayUrl: String): String =
            relayUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").trimEnd('/')

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
