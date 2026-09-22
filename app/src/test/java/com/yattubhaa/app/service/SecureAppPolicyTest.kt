package com.yattubhaa.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureAppPolicyTest {
    @Test
    fun knownBankingAndPaymentAppsAreBlocked() {
        for (pkg in listOf(
            "com.snapwork.hdfc", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user",
            "com.msf.kbank.mobile", "com.phonepe.app", "in.org.npci.upiapp",
        )) assertTrue("$pkg should be blocked", SecureAppPolicy.isBlocked(pkg))
    }

    @Test
    fun anAppWeDoNotKnowIsCaughtByItsName() {
        assertTrue(SecureAppPolicy.isBlocked("com.examplebank.mobile"))
        assertTrue(SecureAppPolicy.isBlocked("in.co.someupi.pay"))
        assertTrue(SecureAppPolicy.isBlocked("com.google.android.apps.walletnfcrel"))
    }

    @Test
    fun ordinaryAppsAreNotBlocked() {
        for (pkg in listOf(
            "com.android.chrome", "com.whatsapp", "com.android.settings", "com.google.android.youtube",
            "com.motorola.launcher3", "com.google.android.apps.messaging",
        )) assertFalse("$pkg should be allowed", SecureAppPolicy.isBlocked(pkg))
    }

    @Test
    fun matchingIsCaseInsensitiveAndNullIsNotBlocked() {
        assertTrue(SecureAppPolicy.isBlocked("NET.ONE97.PAYTM"))
        assertFalse(SecureAppPolicy.isBlocked(null))
    }

    @Test
    fun theShadeKeyboardAndOurOwnOverlayDoNotHideABankAppBehindThem() {
        val own = "com.yattubhaa.app"
        assertFalse(SecureAppPolicy.countsAsForeground("com.android.systemui", own))
        assertFalse(SecureAppPolicy.countsAsForeground(own, own))
        assertFalse(SecureAppPolicy.countsAsForeground("com.google.android.inputmethod.latin", own))
        assertFalse(SecureAppPolicy.countsAsForeground("com.samsung.android.honeyboard", own))
        assertFalse(SecureAppPolicy.countsAsForeground(null, own))
        assertTrue(SecureAppPolicy.countsAsForeground("net.one97.paytm", own))
        assertTrue(SecureAppPolicy.countsAsForeground("com.android.chrome", own))
    }
}
