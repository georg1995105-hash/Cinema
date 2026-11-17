package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class AutoVoicePolicyTest {
    private val list = listOf(
        MirrorVoice("1","HDrezka Studio"),
        MirrorVoice("2","HDrezka Studio (18+)"),
        MirrorVoice("3","Novamedia"),
        MirrorVoice("4","Premium подписка")
    )
    @Test fun movesToAnotherSourceProvidedVoiceWithoutRepeating() {
        assertEquals("2",AutoVoicePolicy.next(list,"1",emptyList())?.id)
        assertEquals("3",AutoVoicePolicy.next(list,"2",listOf("1"))?.id)
    }
    @Test fun excludesPremiumOnlyLabel() {
        assertNull(AutoVoicePolicy.next(list,"3",listOf("1","2")))
    }
    @Test fun boundsRequests() {
        val many = (1..15).map { MirrorVoice(it.toString(),"Озвучка $it") }
        assertNull(AutoVoicePolicy.next(many,"8",(1..7).map(Int::toString)))
    }
}
