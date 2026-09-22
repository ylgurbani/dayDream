package com.yattubhaa.app.service

import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.Protocol.Message
import com.yattubhaa.app.service.RemoteInput.Result
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val SWIPE_PATH = listOf(Protocol.Point(0.5f, 0.8f), Protocol.Point(0.5f, 0.2f))

private class FakeTarget(var visible: List<String>? = listOf("com.android.chrome")) : RemoteInputTarget {
    val log = mutableListOf<String>()
    override fun visiblePackages(): List<String>? = visible
    var succeed = true
    override fun tap(x: Float, y: Float, longPress: Boolean): Boolean { log += if (longPress) "long" else "tap"; return succeed }
    override fun gesturePath(points: List<Protocol.Point>, durationMs: Int): Boolean { log += "swipe"; return succeed }
    override fun navigate(action: NavAction): Boolean { log += "nav:$action"; return succeed }
}

class RemoteInputTest {
    private val target = FakeTarget()

    @After fun cleanUp() = RemoteInput.detach(target)

    @Test
    fun nothingHappensUntilTheAccessibilityServiceIsConnected() {
        assertFalse(RemoteInput.isAvailable)
        assertEquals(Result.Unavailable, RemoteInput.apply(Message.Tap(0.5f, 0.5f)))
        assertEquals(Result.Unavailable, RemoteInput.apply(Message.Nav(NavAction.Back)))
    }

    @Test
    fun anOrdinaryAppGetsTapsSwipesAndLongPresses() {
        RemoteInput.attach(target)
        target.visible = listOf("com.android.chrome")
        assertEquals(Result.Done, RemoteInput.apply(Message.Tap(0.1f, 0.2f)))
        assertEquals(Result.Done, RemoteInput.apply(Message.LongPress(0.1f, 0.2f)))
        assertEquals(Result.Done, RemoteInput.apply(Message.GesturePath(SWIPE_PATH, 300)))
        assertEquals(listOf("tap", "long", "swipe"), target.log)
    }

    @Test
    fun aBankingAppBlocksTapsAndSwipesButNotGettingOut() {
        RemoteInput.attach(target)
        target.visible = listOf("net.one97.paytm")
        assertEquals(Result.Blocked, RemoteInput.apply(Message.Tap(0.5f, 0.5f)))
        assertEquals(Result.Blocked, RemoteInput.apply(Message.LongPress(0.5f, 0.5f)))
        assertEquals(Result.Blocked, RemoteInput.apply(Message.GesturePath(SWIPE_PATH, 200)))
        assertTrue("nothing must reach the phone", target.log.isEmpty())
        assertEquals(Result.Done, RemoteInput.apply(Message.Nav(NavAction.Home)))
        assertEquals(listOf("nav:Home"), target.log)
    }

    @Test
    fun aBankAppHidingBehindAnotherWindowIsStillCaught() {
        RemoteInput.attach(target)
        // A pop-up from some other app is on top, but the bank app is still on screen underneath.
        target.visible = listOf("com.google.android.gms", "com.msf.kbank.mobile")
        assertEquals(Result.Blocked, RemoteInput.apply(Message.Tap(0.5f, 0.5f)))
        assertTrue(target.log.isEmpty())
    }

    @Test
    fun ifItCannotTellWhichAppsAreOpenItRefusesToTapButStillAllowsGoingHome() {
        RemoteInput.attach(target)
        target.visible = null
        assertEquals(Result.Blocked, RemoteInput.apply(Message.Tap(0.5f, 0.5f)))
        assertEquals(Result.Blocked, RemoteInput.apply(Message.GesturePath(SWIPE_PATH, 200)))
        assertEquals(Result.Done, RemoteInput.apply(Message.Nav(NavAction.Home)))
        assertEquals(listOf("nav:Home"), target.log)
    }

    @Test
    fun aFailedGestureIsReportedAsFailed() {
        RemoteInput.attach(target)
        target.succeed = false
        assertEquals(Result.Failed, RemoteInput.apply(Message.Tap(0.5f, 0.5f)))
    }

    @Test
    fun switchingTheServiceOffMakesItUnavailableAgain() {
        RemoteInput.attach(target)
        assertTrue(RemoteInput.connected.value)
        RemoteInput.detach(target)
        assertFalse(RemoteInput.connected.value)
        assertEquals(Result.Unavailable, RemoteInput.apply(Message.Tap(0.5f, 0.5f)))
    }

    @Test
    fun anOldServiceCannotDetachANewerOne() {
        val newer = FakeTarget()
        RemoteInput.attach(target)
        RemoteInput.attach(newer)
        RemoteInput.detach(target)
        assertTrue(RemoteInput.isAvailable)
        RemoteInput.detach(newer)
    }
}
