package com.yattubhaa.app.net

import java.nio.ByteBuffer

/** Where a request to control the phone currently stands, as reported by the phone itself. */
enum class ControlState(val wire: Byte) {
    /** No control: never asked, refused, or ended. */
    Off(0),
    On(1),
    /** Asked; waiting for the person to say yes. */
    Asked(2),
    /** On, but a banking or payment app is in front, so taps are being held back. */
    Blocked(3),
    /** They said yes, but the phone's Accessibility setting is not switched on. */
    Unavailable(4),
}

enum class NavAction(val wire: Byte) { Back(1), Home(2), Recents(3), Notifications(4) }

/** How well the connection is keeping up with the picture right now, as judged by the needy
 *  phone (it is the one that can see its own outgoing queue). Shown to the helper only — he is
 *  the technical user here, and a raw quality readout would just be one more confusing thing on
 *  a screen that is deliberately kept simple. */
enum class ConnectionQuality(val wire: Byte) { Good(0), Fair(1), Poor(2) }

/**
 * What the two phones say to each other once the secure channel is up. Each message is one
 * type byte followed by its payload; the whole thing is encrypted by [SecureChannel].
 * Positions are fractions of the screen sent as 0..10000.
 *
 *   VIDEO_CHUNK    needy -> helper   flags (1, bit0 = keyframe) | width (2) | height (2) | H.264
 *   SHARING_STARTED needy -> helper  sharing has begun; the picture is on its way but has not
 *                                    necessarily arrived yet, so the helper has something to show
 *                                    other than silence while it does
 *   CONNECTION_QUALITY needy -> helper  a [ConnectionQuality], sent only when it changes
 *   POINTER        helper -> needy   x (2) | y (2); 0xFFFF, 0xFFFF clears the ring
 *   STOP           either            ends the session
 *   CONTROL_REQUEST  helper -> needy  ask to tap and swipe for them (they must say yes)
 *   CONTROL_RELEASE  helper -> needy  hand control back
 *   CONTROL_STATUS   needy -> helper  a [ControlState]
 *   TAP / LONG_PRESS helper -> needy  x (2) | y (2)
 *   GESTURE_PATH     helper -> needy  point count (1) | that many x,y pairs (2 each) | duration
 *                                     in ms (2) — the whole path the finger actually took, not
 *                                     just where it started and ended
 *   NAV              helper -> needy  back, home or recents
 *
 * The helper can only ever ask. Whether control is on is decided on the other phone, which
 * ignores every control message unless it has said yes for this session.
 */
object Protocol {
    private const val VIDEO_CHUNK: Byte = 1
    private const val POINTER: Byte = 2
    private const val STOP: Byte = 3
    private const val CONTROL_REQUEST: Byte = 4
    private const val CONTROL_RELEASE: Byte = 5
    private const val CONTROL_STATUS: Byte = 6
    private const val TAP: Byte = 7
    private const val LONG_PRESS: Byte = 8
    private const val GESTURE_PATH: Byte = 9
    private const val NAV: Byte = 10
    private const val SHARING_STARTED: Byte = 11
    private const val CONNECTION_QUALITY: Byte = 12
    private const val SCALE = 10000
    private const val CLEAR = 0xFFFF
    const val MIN_SWIPE_MS = 50
    const val MAX_SWIPE_MS = 2000
    /** A path longer than this is thinned out, keeping its last (dropped-at) point exactly —
     *  comfortably more than a throttled real gesture produces; see HelperFrameView. */
    const val MAX_PATH_POINTS = 80

    /** One point along a dragged path, as a fraction of the screen, 0..1. */
    data class Point(val x: Float, val y: Float)

    sealed interface Message {
        /** One chunk of the H.264 stream. A decoder needs a keyframe before anything else makes
         *  sense; everything before the first one it sees should be dropped. */
        class VideoChunk(val keyframe: Boolean, val width: Int, val height: Int, val data: ByteArray) : Message
        /** Sharing has begun; the picture itself may still be a moment away. */
        data object SharingStarted : Message
        data class Connection(val quality: ConnectionQuality) : Message
        /** Fractions of the screen, 0..1. Null means "remove the pointer". */
        data class Pointer(val x: Float?, val y: Float?) : Message
        data object Stop : Message
        data object ControlRequest : Message
        data object ControlRelease : Message
        data class ControlStatus(val state: ControlState) : Message
        data class Tap(val x: Float, val y: Float) : Message
        data class LongPress(val x: Float, val y: Float) : Message
        /** At least two points; the first is where the finger touched down, the last is where
         *  it lifted off. [durationMs] is how long the whole path took. */
        data class GesturePath(val points: List<Point>, val durationMs: Int) : Message
        data class Nav(val action: NavAction) : Message
    }

    private fun scaled(v: Float) = (v.coerceIn(0f, 1f) * SCALE).toInt().toShort()

    fun videoChunk(keyframe: Boolean, width: Int, height: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(6 + data.size)
            .put(VIDEO_CHUNK).put(if (keyframe) 1 else 0)
            .putShort(width.toShort()).putShort(height.toShort())
            .put(data).array()

    fun sharingStarted(): ByteArray = byteArrayOf(SHARING_STARTED)
    fun connectionQuality(quality: ConnectionQuality): ByteArray = byteArrayOf(CONNECTION_QUALITY, quality.wire)

    fun pointer(x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(5).put(POINTER).putShort(scaled(x)).putShort(scaled(y)).array()

    fun clearPointer(): ByteArray = ByteBuffer.allocate(5)
        .put(POINTER).putShort(CLEAR.toShort()).putShort(CLEAR.toShort()).array()

    fun stop(): ByteArray = byteArrayOf(STOP)
    fun controlRequest(): ByteArray = byteArrayOf(CONTROL_REQUEST)
    fun controlRelease(): ByteArray = byteArrayOf(CONTROL_RELEASE)
    fun controlStatus(state: ControlState): ByteArray = byteArrayOf(CONTROL_STATUS, state.wire)
    fun nav(action: NavAction): ByteArray = byteArrayOf(NAV, action.wire)

    fun tap(x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(5).put(TAP).putShort(scaled(x)).putShort(scaled(y)).array()

    fun longPress(x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(5).put(LONG_PRESS).putShort(scaled(x)).putShort(scaled(y)).array()

    /** [points] is thinned to [MAX_PATH_POINTS] if longer, always keeping the last (dropped-at)
     *  point exactly rather than losing it to truncation. Fewer than two points is meaningless
     *  (there is no path), so it is padded by repeating the single point — a zero-length "path",
     *  parsed back the same as any other, rather than a special case callers must avoid. */
    fun gesturePath(points: List<Point>, durationMs: Int): ByteArray {
        val safe = when {
            points.size >= 2 -> thin(points)
            points.size == 1 -> listOf(points[0], points[0])
            else -> listOf(Point(0f, 0f), Point(0f, 0f))
        }
        val buf = ByteBuffer.allocate(2 + safe.size * 4 + 2)
        buf.put(GESTURE_PATH).put(safe.size.toByte())
        for (p in safe) buf.putShort(scaled(p.x)).putShort(scaled(p.y))
        buf.putShort(durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS).toShort())
        return buf.array()
    }

    private fun thin(points: List<Point>): List<Point> {
        if (points.size <= MAX_PATH_POINTS) return points
        val step = (points.size - 1).toFloat() / (MAX_PATH_POINTS - 1)
        val out = ArrayList<Point>(MAX_PATH_POINTS)
        for (i in 0 until MAX_PATH_POINTS - 1) out += points[(i * step).toInt()]
        out += points.last() // always keep the exact point the finger lifted at
        return out
    }

    /** Returns null for anything malformed or unknown, so a bad peer cannot crash us. */
    fun parse(bytes: ByteArray): Message? {
        if (bytes.isEmpty()) return null
        return when (bytes[0]) {
            VIDEO_CHUNK -> {
                if (bytes.size <= 6) return null
                val buf = ByteBuffer.wrap(bytes, 2, 4)
                val w = buf.short.toInt() and 0xFFFF
                val h = buf.short.toInt() and 0xFFFF
                if (w == 0 || h == 0) return null
                Message.VideoChunk(bytes[1] != 0.toByte(), w, h, bytes.copyOfRange(6, bytes.size))
            }
            SHARING_STARTED -> if (bytes.size == 1) Message.SharingStarted else null
            CONNECTION_QUALITY -> {
                if (bytes.size != 2) return null
                ConnectionQuality.entries.firstOrNull { it.wire == bytes[1] }?.let { Message.Connection(it) }
            }
            POINTER -> {
                if (bytes.size != 5) return null
                val buf = ByteBuffer.wrap(bytes, 1, 4)
                val x = buf.short.toInt() and 0xFFFF
                val y = buf.short.toInt() and 0xFFFF
                when {
                    x == CLEAR && y == CLEAR -> Message.Pointer(null, null)
                    x > SCALE || y > SCALE -> null
                    else -> Message.Pointer(x / SCALE.toFloat(), y / SCALE.toFloat())
                }
            }
            STOP -> Message.Stop
            CONTROL_REQUEST -> if (bytes.size == 1) Message.ControlRequest else null
            CONTROL_RELEASE -> if (bytes.size == 1) Message.ControlRelease else null
            CONTROL_STATUS -> {
                if (bytes.size != 2) return null
                ControlState.entries.firstOrNull { it.wire == bytes[1] }?.let { Message.ControlStatus(it) }
            }
            TAP, LONG_PRESS -> {
                if (bytes.size != 5) return null
                val p = fractions(ByteBuffer.wrap(bytes, 1, 4), 2) ?: return null
                if (bytes[0] == TAP) Message.Tap(p[0], p[1]) else Message.LongPress(p[0], p[1])
            }
            GESTURE_PATH -> {
                if (bytes.size < 2) return null
                val count = bytes[1].toInt() and 0xFF
                if (count < 2 || count > MAX_PATH_POINTS) return null
                val expected = 2 + count * 4 + 2
                if (bytes.size != expected) return null
                val buf = ByteBuffer.wrap(bytes, 2, count * 4 + 2)
                val points = ArrayList<Point>(count)
                for (i in 0 until count) {
                    val f = fractions(buf, 2) ?: return null
                    points += Point(f[0], f[1])
                }
                val ms = buf.short.toInt() and 0xFFFF
                if (ms !in MIN_SWIPE_MS..MAX_SWIPE_MS) return null
                Message.GesturePath(points, ms)
            }
            NAV -> {
                if (bytes.size != 2) return null
                NavAction.entries.firstOrNull { it.wire == bytes[1] }?.let { Message.Nav(it) }
            }
            else -> null
        }
    }

    /** Reads [count] positions; null if any is outside 0..10000. */
    private fun fractions(buf: ByteBuffer, count: Int): FloatArray? {
        val out = FloatArray(count)
        for (i in 0 until count) {
            val v = buf.short.toInt() and 0xFFFF
            if (v > SCALE) return null
            out[i] = v / SCALE.toFloat()
        }
        return out
    }
}
