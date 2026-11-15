package app.quiet.launcher.feature.art

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val ArtJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable private data class AicResponse(val data: List<AicItem> = emptyList())

@Serializable
private data class AicItem(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("artist_title") val artistTitle: String? = null,
    @SerialName("date_display") val dateDisplay: String? = null,
    @SerialName("image_id") val imageId: String? = null,
    @SerialName("artwork_type_title") val type: String? = null,
    @SerialName("is_public_domain") val isPublicDomain: Boolean? = null,
)

@Serializable private data class MetSearch(val objectIDs: List<Long>? = null)

@Serializable
private data class MetObject(
    val objectID: Long? = null,
    val isPublicDomain: Boolean? = null,
    val primaryImage: String? = null,
    val title: String? = null,
    val artistDisplayName: String? = null,
    val objectDate: String? = null,
    val classification: String? = null,
)

@Serializable private data class CmaResponse(val data: List<CmaItem> = emptyList())

@Serializable
private data class CmaItem(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("creation_date") val creationDate: String? = null,
    val creators: List<CmaCreator>? = null,
    val images: CmaImages? = null,
    @SerialName("share_license_status") val license: String? = null,
    val type: String? = null,
)

@Serializable private data class CmaCreator(val description: String? = null)
@Serializable private data class CmaImages(val print: CmaImage? = null, val web: CmaImage? = null)
@Serializable private data class CmaImage(val url: String? = null)

/** Pure JSON -> candidate parsers (public-domain filtering included). */
object ArtParsers {
    const val AIC = "Art Institute of Chicago"
    const val MET = "The Metropolitan Museum of Art"
    const val CMA = "Cleveland Museum of Art"

    fun aicImageUrl(imageId: String) = "https://www.artic.edu/iiif/2/$imageId/full/1686,/0/default.jpg"

    fun parseAic(json: String): List<ArtCandidate> {
        val r = runCatching { ArtJson.decodeFromString<AicResponse>(json) }.getOrNull() ?: return emptyList()
        return r.data.mapNotNull { a ->
            if (a.isPublicDomain == false) return@mapNotNull null
            val id = a.id ?: return@mapNotNull null
            val img = a.imageId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = a.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ArtCandidate("aic", id.toString(), title, a.artistTitle.orEmpty().ifBlank { "Unknown" },
                a.dateDisplay?.takeIf { it.isNotBlank() }, AIC, aicImageUrl(img), a.type)
        }
    }

    fun parseMetSearchIds(json: String): List<Long> =
        runCatching { ArtJson.decodeFromString<MetSearch>(json).objectIDs.orEmpty() }.getOrDefault(emptyList())

    /** Null unless the object is public domain and has a primary image. */
    fun parseMetObject(json: String): ArtCandidate? {
        val o = runCatching { ArtJson.decodeFromString<MetObject>(json) }.getOrNull() ?: return null
        if (o.isPublicDomain != true) return null
        val img = o.primaryImage?.takeIf { it.isNotBlank() } ?: return null
        val id = o.objectID ?: return null
        val title = o.title?.takeIf { it.isNotBlank() } ?: return null
        return ArtCandidate("met", id.toString(), title, o.artistDisplayName.orEmpty().ifBlank { "Unknown" },
            o.objectDate?.takeIf { it.isNotBlank() }, MET, img, o.classification)
    }

    fun parseCleveland(json: String): List<ArtCandidate> {
        val r = runCatching { ArtJson.decodeFromString<CmaResponse>(json) }.getOrNull() ?: return emptyList()
        return r.data.mapNotNull { a ->
            if (!a.license.equals("CC0", ignoreCase = true)) return@mapNotNull null
            val id = a.id ?: return@mapNotNull null
            val url = (a.images?.print?.url?.takeIf { it.isNotBlank() } ?: a.images?.web?.url?.takeIf { it.isNotBlank() })
                ?: return@mapNotNull null
            val title = a.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = a.creators?.firstOrNull()?.description?.substringBefore(" (")?.trim()
                .orEmpty().ifBlank { "Unknown" }
            ArtCandidate("cma", id.toString(), title, artist, a.creationDate?.takeIf { it.isNotBlank() }, CMA, url, a.type)
        }
    }
}
