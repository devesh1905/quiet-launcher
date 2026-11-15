package app.quiet.launcher.feature.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtParsersTest {
    @Test fun aic_parses_and_skips_missing_image() {
        val json = """{"pagination":{"total":2},"data":[
            {"id":28560,"title":"The Bedroom","artist_title":"Vincent van Gogh","date_display":"1889",
             "image_id":"25c31d8d-21a4-9ea1-1d73-6a2eca4dda7e","artwork_type_title":"Painting","thumbnail":{"width":100},"extra":1},
            {"id":1,"title":"No image","artist_title":"X","image_id":null,"artwork_type_title":"Painting"}
        ],"info":{}}"""
        val r = ArtParsers.parseAic(json)
        assertEquals(1, r.size)
        val c = r[0]
        assertEquals("aic_28560", c.id)
        assertEquals("Vincent van Gogh", c.artist)
        assertEquals("1889", c.year)
        assertEquals("https://www.artic.edu/iiif/2/25c31d8d-21a4-9ea1-1d73-6a2eca4dda7e/full/1686,/0/default.jpg", c.imageUrl)
        assertEquals("Painting", c.type)
    }

    @Test fun aic_bad_json_is_empty() {
        assertTrue(ArtParsers.parseAic("not json").isEmpty())
    }

    @Test fun met_search_ids_and_null() {
        assertEquals(listOf(436535L, 1L), ArtParsers.parseMetSearchIds("""{"total":2,"objectIDs":[436535,1]}"""))
        assertTrue(ArtParsers.parseMetSearchIds("""{"total":0,"objectIDs":null}""").isEmpty())
    }

    @Test fun met_object_requires_public_domain_and_image() {
        val ok = """{"objectID":436535,"isPublicDomain":true,"primaryImage":"https://images.metmuseum.org/a.jpg",
            "title":"Wheat Field with Cypresses","artistDisplayName":"Vincent van Gogh","objectDate":"1889","classification":"Paintings"}"""
        val c = ArtParsers.parseMetObject(ok)!!
        assertEquals("met_436535", c.id)
        assertEquals("1889", c.year)
        assertEquals("Paintings", c.type)
        assertNull(ArtParsers.parseMetObject(ok.replace("true", "false")))
        assertNull(ArtParsers.parseMetObject(ok.replace("https://images.metmuseum.org/a.jpg", "")))
    }

    @Test fun cleveland_cc0_print_with_fallback() {
        val json = """{"info":{"total":3},"data":[
            {"id":1,"title":"Water Lilies","creation_date":"1915","share_license_status":"CC0",
             "creators":[{"description":"Claude Monet (French, 1840-1926)"}],
             "images":{"web":{"url":"https://w/1.jpg"},"print":{"url":"https://p/1.jpg"}}},
            {"id":2,"title":"Web only","creation_date":"1900","share_license_status":"CC0",
             "creators":[],"images":{"web":{"url":"https://w/2.jpg"}}},
            {"id":3,"title":"Copyrighted","share_license_status":"Copyrighted","images":{"print":{"url":"https://p/3.jpg"}}}
        ]}"""
        val r = ArtParsers.parseCleveland(json)
        assertEquals(2, r.size)
        assertEquals("https://p/1.jpg", r[0].imageUrl)
        assertEquals("Claude Monet", r[0].artist)
        assertEquals("https://w/2.jpg", r[1].imageUrl)
        assertEquals("Unknown", r[1].artist)
    }
}
