package app.cinema.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArchivePublicCatalogTest {
    @Test fun publicDomainMarkAndCc0AreAllowedButOtherLicensesAreNot() {
        assertTrue(ArchivePublicCatalog.isDeclaredFree(
            "http://creativecommons.org/publicdomain/mark/1.0/"))
        assertTrue(ArchivePublicCatalog.isDeclaredFree(
            "https://creativecommons.org/publicdomain/zero/1.0/"))
        assertFalse(ArchivePublicCatalog.isDeclaredFree(
            "https://creativecommons.org/licenses/by-nc/4.0/"))
        assertFalse(ArchivePublicCatalog.isDeclaredFree(
            "http://creativecommons.org/licenses/publicdomain/"))
    }

    @Test fun discoverParsesOnlyRealLicenseLabeledArchiveItems() {
        val json = JSONObject("""
            {"response":{"docs":[
              {"identifier":"AsYouLikeIt1936","title":"As You Like It",
               "year":"1936","licenseurl":"http://creativecommons.org/publicdomain/mark/1.0/"},
              {"identifier":"Sita_Sings_the_Blues","title":"Sita Sings the Blues",
               "licenseurl":"http://creativecommons.org/publicdomain/zero/1.0/"},
              {"identifier":"OtherCommercialVideo","title":"A paid movie",
               "licenseurl":"http://creativecommons.org/licenses/by-nc/4.0/"},
              {"identifier":"bad/id","title":"Broken entry",
               "licenseurl":"http://creativecommons.org/publicdomain/mark/1.0/"}
            ]}}
        """.trimIndent())
        val found = ArchivePublicCatalog.parseSearch(json)
        assertEquals(2, found.size)
        assertEquals("As You Like It", found.first().title)
        assertEquals("https://archive.org/details/AsYouLikeIt1936", found.first().url)
        assertTrue(found.all { it.poster.startsWith("https://archive.org/services/img/") })
    }

    @Test fun archiveIdentityDoesNotAcceptForeignHostsOrPathTraversal() {
        assertEquals("AsYouLikeIt1936", ArchivePublicCatalog.identifier(
            "https://archive.org/details/AsYouLikeIt1936"))
        assertNull(ArchivePublicCatalog.identifier(
            "https://fakearchive.org/details/AsYouLikeIt1936"))
        assertNull(ArchivePublicCatalog.identifier(
            "https://archive.org/details/../metadata"))
        assertNull(ArchivePublicCatalog.identifier(
            "https://archive.org/details/AsYouLikeIt1936?auth=secret"))
    }
}
