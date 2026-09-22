package com.yattubhaa.app.service

/**
 * Remote tap and swipe is never applied inside a banking or payment app. Nobody needs the helper
 * to be able to tap around a bank app for them, and if a stranger ever did get control, that is
 * the place they would go.
 *
 * This is a best-effort guard, not a guarantee: it recognises apps by package name (a list of the
 * ones he is likely to have, plus obvious words like "bank" and "upi"), and an app it does not
 * recognise is treated as ordinary. Add to [exact] when he installs another such app.
 * Screens that block screen capture (FLAG_SECURE) already show as black to the helper.
 */
object SecureAppPolicy {
    private val exact = setOf(
        "com.snapwork.hdfc",                 // HDFC Bank
        "com.enstage.wibmo.hdfc",            // HDFC PayZapp
        "net.one97.paytm",                   // Paytm
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "com.phonepe.app",                   // PhonePe
        "in.org.npci.upiapp",                // BHIM
        "com.msf.kbank.mobile",              // Kotak
        "com.kotak811mobilebankingapp.instantsavingsbankaccount", // Kotak 811
        "com.sbi.lotusintouch",              // SBI YONO
        "com.sbi.sbifreedomplus",            // SBI
        "com.csam.icici.bank.imobile",       // ICICI iMobile
        "com.axis.mobile",                   // Axis
        "com.samsung.android.spay",          // Samsung Wallet / Pay
    )

    private val keywords = listOf(
        "bank", "upi", "paytm", "paisa", "phonepe", "payzapp", "bhim", "wallet", "netbanking",
    )

    fun isBlocked(packageName: String?): Boolean {
        val p = packageName?.lowercase() ?: return false
        return p in exact || keywords.any { it in p }
    }

    /**
     * Whether a window from [packageName] counts as "the app he is in". The notification shade,
     * the keyboard and our own overlay appear on top of whatever app is really in front, and must
     * not make us forget that a bank app is still behind them.
     */
    fun countsAsForeground(packageName: String?, ownPackage: String): Boolean {
        val p = packageName?.lowercase() ?: return false
        if (p == ownPackage.lowercase() || p == "com.android.systemui" || p == "android") return false
        return listOf("inputmethod", "keyboard", "honeyboard").none { it in p }
    }
}
