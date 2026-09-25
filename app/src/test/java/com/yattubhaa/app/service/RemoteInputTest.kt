package com.yattubhaa.app.service

import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.TouchPhase
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
    var checks = 0
    override fun visiblePackages(): List<String>? { checks++; return visible }
    var succeed = true
    override fun tap(x: Float, y: Float, longPress: Boolean): Boolean { log += if (longPress) "long" else "tap"; return succeed }
    override fun gesturePath(points: List<Protocol.Point>, durationMs: Int): Boolean { log += "swipe"; return succeed }
    override fun touch(phase: TouchPhase, x: Float, y: Float): Boolean { log += "touch:$phase"; return succeed }
    override fun cancelTouch() { log += "cancel" }
    override fun navigate(action: NavAction): Boolean { log += "nav:$action"; return succeed }
    override fun switchOff() { log += "off" }
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

    @Test
    fun aHeldDragIsPassedThroughStepByStep() {
        RemoteInput.attach(target)
        for (phase in TouchPhase.entries) assertEquals(Result.Done, RemoteInput.apply(Message.Touch(phase, 0.5f, 0.5f)))
        assertEquals(listOf("touch:Down", "touch:Move", "touch:Up"), target.log)
    }

    @Test
    fun aHeldDragIsLetGoTheMomentABankAppComesToTheFrontButLiftingIsAlwaysAllowed() {
        RemoteInput.attach(target)
        RemoteInput.apply(Message.Touch(TouchPhase.Down, 0.5f, 0.5f), nowMs = 0)
        target.visible = listOf("com.snapwork.hdfc")
        RemoteInput.onWindowsChanged() // what Android reports as the bank app comes to the front
        assertEquals(Result.Blocked, RemoteInput.apply(Message.Touch(TouchPhase.Move, 0.5f, 0.6f), nowMs = 40))
        assertEquals(Result.Done, RemoteInput.apply(Message.Touch(TouchPhase.Up, 0.5f, 0.6f), nowMs = 80))
        assertEquals(listOf("touch:Down", "cancel", "touch:Up"), target.log)
    }

    @Test
    fun aHeldDragIsCheckedWhenPressedThenOnlyEveryFewHundredMillisecondsNotEveryStep() {
        RemoteInput.attach(target)
        RemoteInput.apply(Message.Touch(TouchPhase.Down, 0.5f, 0.5f), nowMs = 0)
        assertEquals(1, target.checks)
        for (t in 40L..700L step 40) RemoteInput.apply(Message.Touch(TouchPhase.Move, 0.5f, 0.5f), nowMs = t)
        assertEquals("seventeen steps, no extra checks", 1, target.checks)
        RemoteInput.apply(Message.Touch(TouchPhase.Move, 0.5f, 0.5f), nowMs = RemoteInput.MOVE_RECHECK_MS)
        assertEquals(2, target.checks)
    }

    @Test
    fun evenWithoutAWindowChangeABankAppIsCaughtWithinTheRecheckInterval() {
        RemoteInput.attach(target)
        RemoteInput.apply(Message.Touch(TouchPhase.Down, 0.5f, 0.5f), nowMs = 0)
        target.visible = listOf("net.one97.paytm")
        assertEquals(Result.Done, RemoteInput.apply(Message.Touch(TouchPhase.Move, 0.5f, 0.6f), nowMs = 100))
        assertEquals(Result.Blocked, RemoteInput.apply(Message.Touch(TouchPhase.Move, 0.5f, 0.7f), nowMs = 800))
        assertTrue("cut short, not left pressed", "cancel" in target.log)
    }

    @Test
    fun tapsAreStillCheckedEveryTime() {
        RemoteInput.attach(target)
        repeat(3) { RemoteInput.apply(Message.Tap(0.5f, 0.5f), nowMs = it.toLong()) }
        assertEquals(3, target.checks)
    }
}
