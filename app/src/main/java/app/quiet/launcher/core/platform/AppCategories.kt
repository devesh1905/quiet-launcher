package app.quiet.launcher.core.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** What kind of app something is, for the preset chips above app lists. */
enum class AppCategory(val label: String, val distracting: Boolean) {
    GAMES("Games", true),
    SOCIAL("Social", true),
    VIDEO("Video", true),
    SHOPPING("Shopping and food", true),
    NEWS("News and reading", true),
    MESSAGING("Messaging", false),
    MUSIC("Music", false),
    PRODUCTIVITY("Work and tools", false),
    OTHER("Other", false),
}

/**
 * Works out an app's category on the phone, without the network:
 *  1. well-known apps by package (Android's own category is often missing or wrong for these);
 *  2. the app's declared category / game flag (`ApplicationInfo.category`, `FLAG_IS_GAME`);
 *  3. game publishers and "game" in the package name.
 */
object AppCategories {
    private val cache = ConcurrentHashMap<String, AppCategory>()

    private val known: Map<String, AppCategory> = buildMap {
        listOf(
            "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona", "com.facebook.katana", "com.facebook.lite",
            "com.snapchat.android", "com.twitter.android", "com.reddit.frontpage", "com.linkedin.android", "com.pinterest",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "in.mohalla.sharechat", "in.mohalla.video", "com.eterno.shortvideos",
            "com.tumblr", "com.bereal.ft", "com.discord", "com.chatroulette.android", "com.tinder", "com.bumble.app", "co.hinge.app",
            "com.instander.android", "com.instapro.android", "com.vkontakte.android", "com.quora.android", "com.threads.android",
        ).forEach { put(it, AppCategory.SOCIAL) }
        listOf(
            "com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube", "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient", "in.startv.hotstar", "com.disney.disneyplus", "com.mxtech.videoplayer.ad",
            "tv.twitch.android.app", "com.jio.media.jiobeats", "com.jio.jioplay.tv", "com.sonyliv", "com.graymatrix.did",
            "com.apple.atve.androidtv.appletv", "com.google.android.videos", "org.videolan.vlc", "com.vimeo.android.videoapp",
        ).forEach { put(it, AppCategory.VIDEO) }
        listOf(
            "in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping", "com.flipkart.android", "com.meesho.supply",
            "com.myntra.android", "com.zeptoconsumerapp", "com.grofers.customerapp", "in.swiggy.android", "com.application.zomato",
            "com.application.zomato.district", "com.magicpin.local", "com.alibaba.aliexpresshd", "com.ebay.mobile", "com.nykaa.app",
            "com.ril.ajio", "com.bigbasket.mobileapp", "com.dunzo.user", "com.savana.in",
        ).forEach { put(it, AppCategory.SHOPPING) }
        listOf(
            "com.google.android.apps.magazines", "com.nis.app", "com.naver.linewebtoon", "wp.wattpad", "com.medium.reader",
            "flipboard.app", "com.dailyhunt.app", "com.google.android.apps.youtube.kids",
        ).forEach { put(it, AppCategory.NEWS) }
        listOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web", "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging", "com.google.android.gm", "com.google.android.apps.dynamite", "com.facebook.orca",
            "com.microsoft.office.outlook", "com.truecaller", "com.google.android.dialer", "com.android.contacts",
        ).forEach { put(it, AppCategory.MESSAGING) }
        listOf(
            "com.spotify.music", "com.apple.android.music", "com.google.android.apps.youtube.music", "com.jio.media.jiobeats",
            "com.gaana", "com.wynk.music", "com.soundcloud.android", "com.shazam.android", "ai.moises",
        ).forEach { put(it, AppCategory.MUSIC) }
        listOf(
            "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets", "com.google.android.apps.docs.editors.slides",
            "com.google.android.keep", "com.google.android.apps.tasks", "com.google.android.calendar", "com.xiaomi.calendar", "com.miui.notes",
            "com.github.android", "com.anthropic.claude", "com.openai.chatgpt", "ai.perplexity.app.android", "com.google.android.apps.classroom",
            "com.microsoft.teams", "com.slack", "com.notion.id", "com.google.android.apps.translate", "com.google.android.apps.maps",
        ).forEach { put(it, AppCategory.PRODUCTIVITY) }
    }

    private val gamePrefixes = listOf(
        "com.supercell.", "com.king.", "com.activision.", "com.garena.", "com.pubg.", "com.tencent.ig", "com.ubisoft.",
        "com.roblox.", "com.mojang.", "com.miniclip.", "com.rovio.", "com.ea.", "com.gameloft.", "com.netmarble.",
        "com.innersloth.", "com.chess", "air.com.", "net.froemling.", "fi.twomenandadog.", "de.lotum.", "com.kiloo.",
        "com.outfit7.", "com.playrix.", "com.zynga.", "com.nianticlabs.", "com.mobile.legends", "com.dts.freefire",
        "com.valvesoftware.", "com.epicgames.", "com.riotgames.", "com.hoyoverse.", "com.miHoYo.", "com.square_enix.",
    )

    fun of(ctx: Context, pkg: String): AppCategory = cache.getOrPut(pkg) { classify(ctx.packageManager, pkg) }

    private fun classify(pm: PackageManager, pkg: String): AppCategory {
        known[pkg]?.let { return it }
        if (gamePrefixes.any { pkg.startsWith(it) }) return AppCategory.GAMES
        val info: ApplicationInfo? = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
        if (info != null) {
            @Suppress("DEPRECATION")
            if (info.flags and ApplicationInfo.FLAG_IS_GAME != 0) return AppCategory.GAMES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (info.category) {
                    ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAMES
                    ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.SOCIAL
                    ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.VIDEO
                    ApplicationInfo.CATEGORY_AUDIO -> return AppCategory.MUSIC
                    ApplicationInfo.CATEGORY_NEWS -> return AppCategory.NEWS
                    ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_MAPS -> return AppCategory.PRODUCTIVITY
                }
            }
        }
        val p = pkg.lowercase()
        if (".game" in p || "games" in p || p.endsWith("game")) return AppCategory.GAMES
        return AppCategory.OTHER
    }
}

/** Categories for [pkgs], worked out off the main thread (first frame shows no chips, then they appear). */
@Composable
fun rememberAppCategories(pkgs: Collection<String>): Map<String, AppCategory> {
    val ctx = LocalContext.current
    val key = pkgs.size to pkgs.hashCode()
    val state by produceState(emptyMap<String, AppCategory>(), key) {
        value = withContext(Dispatchers.Default) { pkgs.associateWith { AppCategories.of(ctx, it) } }
    }
    return state
}
