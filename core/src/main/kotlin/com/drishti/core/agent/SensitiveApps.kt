package com.drishti.core.agent

/**
 * Apps Aura never reads: money, health, passwords.
 *
 * This is a product promise, so it is enforced in code on every step rather than left to a
 * setting. Keyword matching alone misses most Indian banking apps (HDFC ships as
 * com.snapwork.hdfc, CRED as com.dreamplug.androidapp), hence the explicit list first.
 */
object SensitiveApps {
    private val PACKAGES = setOf(
        // Banks
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.sbi.upi",
        "com.snapwork.hdfc", "com.hdfcbank.payzapp", "com.csam.icici.bank.imobile",
        "com.axis.mobile", "com.msf.kbank.mobile", "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge",
        "com.bankofbaroda.mconnect", "com.infrasofttech.indianbank", "com.canarabank.mobility",
        "com.pnb.pnbone", "com.unionbank.ecommerce.mobile.android", "com.idbibank.abhay_card",
        "com.yesbank", "com.indusind.indie", "com.fss.idfcpay", "com.idfcfirstbank.optimus",
        "com.rblbank.mobank", "com.federalbank.fedmobile", "com.aubank.aumobile",
        // Payments and UPI
        "net.one97.paytm", "com.phonepe.app", "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp", "com.dreamplug.androidapp", "in.amazon.mShop.android.shopping.pay",
        "com.mobikwik_new", "com.freecharge.android", "com.whatsapp.payments",
        "com.paypal.android.p2pmobile",
        // Investing and crypto
        "com.zerodha.kite3", "com.nextbillion.groww", "com.upstox.pro", "com.angelbroking.angelbroking",
        "com.coinbase.android", "com.wazirx",
        // Health
        "com.practo.fabric", "com.apollo.patientapp", "in.gov.ayushmanbharat", "com.tatadigital.tata1mg",
        // Passwords and authenticators
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.x8bit.bitwarden",
        "com.agilebits.onepassword", "com.lastpass.lpandroid", "keepass2android.keepass2android",
    )

    private val KEYWORDS = listOf(
        "bank", "banking", "upi", "wallet", "paytm", "phonepe", "paisa", "pay.", ".pay",
        "loan", "credit", "invest", "trading", "crypto", "insur",
        "health", "medical", "patient", "pharma",
        "password", "authenticator", "keepass", "vault",
    )

    fun isSensitive(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg in PACKAGES) return true
        val p = pkg.lowercase()
        // Google Wallet / Pay; never the wider Google app family.
        if (p.startsWith("com.google.android.apps.walletnfcrel")) return true
        return KEYWORDS.any { p.contains(it) }
    }
}
