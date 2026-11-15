package app.quiet.launcher.feature.art

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** One shared client, polite User-Agent, blocking calls (callers use Dispatchers.IO). */
class ArtHttp(
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("User-Agent", "QuietLauncher/0.1 (personal use)")
        .header("Accept", "*/*")
        .build()

    fun getString(url: HttpUrl): String? = try {
        client.newCall(request(url)).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${url.host} ${e.javaClass.simpleName}"); null
    }

    fun getBytes(url: String, maxBytes: Long = 40L * 1024 * 1024): ByteArray? {
        val http = url.toHttpUrlOrNull() ?: return null
        return try {
            client.newCall(request(http)).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) return null
                if (body.contentLength() > maxBytes) return null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                body.byteStream().use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) return null
                    }
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${http.host} ${e.javaClass.simpleName}"); null
        }
    }

    companion object { const val TAG = "QuietArt" }
}

interface ArtSource {
    val name: String
    /** Public-domain candidates for [artist]; empty on any failure. */
    suspend fun candidates(artist: String): List<ArtCandidate>
}

class AicSource(private val http: ArtHttp) : ArtSource {
    override val name = "aic"
    override suspend fun candidates(artist: String): List<ArtCandidate> = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder().scheme("https").host("api.artic.edu").addPathSegments("api/v1/artworks/search")
            .addQueryParameter("q", artist)
            .addQueryParameter("query[term][is_public_domain]", "true")
            .addQueryParameter("fields", "id,title,artist_title,date_display,image_id,artwork_type_title,is_public_domain,thumbnail")
            .addQueryParameter("limit", "40")
            .build()
        http.getString(url)?.let { ArtParsers.parseAic(it) }.orEmpty()
    }
}

class MetSource(private val http: ArtHttp, private val rnd: Random = Random.Default) : ArtSource {
    override val name = "met"
    override suspend fun candidates(artist: String): List<ArtCandidate> = withContext(Dispatchers.IO) {
        val search = HttpUrl.Builder().scheme("https").host("collectionapi.metmuseum.org")
            .addPathSegments("public/collection/v1/search")
            .addQueryParameter("hasImages", "true")
            .addQueryParameter("artistOrCulture", "true")
            .addQueryParameter("q", artist)
            .build()
        val ids = http.getString(search)?.let { ArtParsers.parseMetSearchIds(it) }.orEmpty().shuffled(rnd)
        val out = ArrayList<ArtCandidate>()
        // Budget: 1 search + at most MAX_DETAILS object lookups (<= 10 requests per run).
        for (id in ids.take(MAX_DETAILS)) {
            delay(300)
            val u = HttpUrl.Builder().scheme("https").host("collectionapi.metmuseum.org")
                .addPathSegments("public/collection/v1/objects/$id").build()
            http.getString(u)?.let { ArtParsers.parseMetObject(it) }?.let(out::add)
        }
        out
    }

    companion object { const val MAX_DETAILS = 7 }
}

class ClevelandSource(private val http: ArtHttp) : ArtSource {
    override val name = "cma"
    override suspend fun candidates(artist: String): List<ArtCandidate> = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder().scheme("https").host("openaccess-api.clevelandart.org")
            .addPathSegments("api/artworks/")
            .addQueryParameter("q", artist)
            .addQueryParameter("cc0", "1")
            .addQueryParameter("has_image", "1")
            .addQueryParameter("limit", "40")
            .build()
        http.getString(url)?.let { ArtParsers.parseCleveland(it) }.orEmpty()
    }
}
