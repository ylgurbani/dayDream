package com.yattubhaa.app.pairing

import org.junit.Assert.assertNotNull
import org.junit.Test

/** The exact link the helper phone produced during the two-emulator test. */
class PairingLinkRealLinkTest {
    @Test
    fun theLinkTheHelperPhoneMade() {
        val query = "k=x6xqmAer3Hsya6NkTwReIFsi8z5FQfMBY5fQrI4WmvA&n=Yashwant&r=ws%3A%2F%2F10.0.2.2%3A8787"
        assertNotNull(PairingLink.parse(query, allowCleartext = true))
    }
}
