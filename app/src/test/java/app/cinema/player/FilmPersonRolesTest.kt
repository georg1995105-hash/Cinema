package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class FilmPersonRolesTest {
    @Test fun directorIsNotAccidentallyAlsoCast() {
        val director=FilmPerson("Гай Ричи","","https://example.com/person/guy")
        val actor=FilmPerson("Актёр","","https://example.com/person/actor")
        val candidates=listOf(director to "unknown", actor to "actor")
        assertEquals(listOf(actor),FilmPersonRoles.actorCredits(candidates,listOf(director)))
    }
    @Test fun explicitActingCreditDoesNotDisappearForDirectorActor() {
        val person=FilmPerson("Актёр-режиссёр","","")
        val result=FilmPersonRoles.actorCredits(listOf(person to "actor"),listOf(person))
        assertEquals(listOf(person),result)
    }
    @Test fun dedicatedDirectorEntryIsNeverShownAsActor() {
        val director=FilmPerson("Режиссёр","","")
        val result=FilmPersonRoles.actorCredits(listOf(director to "director"),emptyList())
        assertTrue(result.isEmpty())
    }
}
