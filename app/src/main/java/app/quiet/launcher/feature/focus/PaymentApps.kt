package app.quiet.launcher.feature.focus

import android.content.Context
import app.quiet.launcher.core.platform.AppCategories
import app.quiet.launcher.core.platform.AppCategory

/** Payment, bank and UPI apps. Focus rules never block or pause these. */
object PaymentApps {
    private val extra = setOf(
        "com.dreamplug.androidapp", "com.mobikwik_new", "com.freecharge.android", "com.kotak811mobilebankingapp",
        "com.msf.kbank.mobile", "com.bankofbaroda.mconnect", "com.infrasofttech.indianBank", "com.canarabank.mobility",
        "com.unionbank.ecommerce.mobile.android", "com.pnb.mbanking", "com.idfcfirstbank.optimus", "com.yesbank",
        "com.amazon.mShop.android.shopping.pay", "money.super.payments", "com.whatsapp.payments",
    )

    fun isPayment(ctx: Context, pkg: String): Boolean {
        if (pkg in extra) return true
        if (AppCategories.of(ctx, pkg) == AppCategory.MONEY) return true
        val p = pkg.lowercase()
        return "bank" in p || ".upi" in p || "upiapp" in p
    }
}
