package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class FranchisePartsTest {
    private val source = "https://mirror.example/"
    private val input = listOf(
        FranchisePartInput("https://mirror.example/films/a.html", "Часть 2", year = "2010", rating = "7.8", number = 2),
        FranchisePartInput("https://mirror.example/films/b.html", "Часть 1", year = "2009", rating = "8.3", number = 1),
        FranchisePartInput("https://mirror.example/films/b.html", "Повтор", year = "2009", number = 3),
        FranchisePartInput("https://wrong.example/films/c.html", "Чужой фильм", number = 3),
        FranchisePartInput("http://mirror.example/films/d.html", "Незащищённая ссылка", number = 4)
    )

    @Test fun keepsOnlyExplicitSameMirrorEntriesInNumericalOrder() {
        assertEquals(listOf("Часть 1", "Часть 2"), FranchiseParts.validate(input, source).map { it.title })
    }

    @Test fun preservesRealRatingAndYear() {
        assertEquals("2009 · ★ 8.3", FranchiseParts.validate(input, source).first().details)
    }

    @Test fun neverInventsRatingForInvalidMetadata() {
        val parts = FranchiseParts.validate(listOf(
            FranchisePartInput("https://mirror.example/f.html", "Название", rating = "42", year = "2025")
        ), source)
        assertEquals("2025", parts.single().details)
    }

    @Test fun rejectsMisleadingSamePrefixHost() {
        assertTrue(FranchiseParts.validate(listOf(
            FranchisePartInput("https://mirror.example.evil.test/a", "Чужой фильм")
        ), source).isEmpty())
    }

    @Test fun emptyListHasNoFakeFranchise() {
        assertTrue(FranchiseParts.validate(emptyList(), source).isEmpty())
    }
}
