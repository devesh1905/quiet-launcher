package app.quiet.launcher.feature.focus.engine

/** Launch-delay lengths. */
object Friction {
    const val FLAT_SECONDS = 15
    const val EXTENSION_SECONDS = 15
    const val COOLDOWN_SECONDS = 60
    const val LATE_NIGHT_SECONDS = 20

    /** 5 s for the first open today, 10 s for the second, 20 s from the third on. */
    fun progressive(opensBefore: Int): Int = when {
        opensBefore <= 0 -> 5
        opensBefore == 1 -> 10
        else -> 20
    }

    /** Between 23:00 and 05:00 the pause is never shorter than [LATE_NIGHT_SECONDS]. */
    fun isLateNight(hour: Int): Boolean = hour >= 23 || hour < 5
}

/** Apps watched by default (social, short video, streaming, dating, games). */
object DefaultRules {
    val PACKAGES: Set<String> = setOf(
        "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona", "com.instander.android", "com.instapro.android",
        "com.facebook.katana", "com.facebook.lite",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.twitter.android", "com.reddit.frontpage", "com.snapchat.android", "com.pinterest", "com.linkedin.android",
        "com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube",
        "com.netflix.mediaclient", "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient", "in.startv.hotstar",
        "com.mxtech.videoplayer.ad", "tv.twitch.android.app",
        "com.tinder", "com.bumble.app", "co.hinge.app", "com.grindrapp.android",
        "com.chess", "com.naver.linewebtoon", "wp.wattpad", "com.sgiggle.production",
        "com.alibaba.aliexpresshd", "com.application.zomato", "sport1.mobile.android.apps",
    )

    /** ApplicationInfo.CATEGORY_GAME, CATEGORY_VIDEO, CATEGORY_SOCIAL. */
    private val WATCHED_CATEGORIES = setOf(0, 2, 4)

    fun isWatchedByDefault(pkg: String, category: Int?): Boolean =
        pkg in PACKAGES || (category != null && category in WATCHED_CATEGORIES)
}
