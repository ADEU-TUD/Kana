package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.ssh.ServerModelChoice
import io.github.thepytales.llmhub.ssh.ServerModelChoice.Choice
import io.github.thepytales.llmhub.ssh.ServerModelChoice.Facts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where Lora thinks in the Server-Terminal (AP-131 e): the recommendation follows this PC – a strong, fast local model
 * stays local, a small or slow one gives way to the TU-Server – and both ways name their risks. External providers are
 * never an option.
 */
class ServerModelChoiceTest {
    private val tu = Facts(tuModel = "zai-org/GLM-5.3-Flash", tuName = "GLM-5.3 Flash")

    @Test
    fun strongAndFastPcStaysLocal() {
        val a = ServerModelChoice.advise(tu.copy(localModel = "local:qwen-14b", localName = "Qwen 14B", localQuality = 14.0, wordsPerSec = 20.0))
        assertEquals(Choice.LOCAL, a.recommended)
        assertTrue("stark genug" in a.why && "rund 10 Sekunden" in a.why, a.why)
        assertTrue(a.local.possible && a.tu.possible)
    }

    @Test
    fun slowOrSmallLocalModelGivesWayToTheTuServer() {
        val slow = ServerModelChoice.advise(tu.copy(localModel = "local:qwen-14b", localName = "Qwen 14B", localQuality = 14.0, wordsPerSec = 2.5))
        assertEquals(Choice.TU_SERVER, slow.recommended)
        assertTrue("zu langsam" in slow.why && "66 Sekunden" in slow.why, slow.why)
        assertTrue(slow.local.risks.any { "66 Sekunden" in it }, "die Wartezeit steht auch bei den Risiken")
        val small = ServerModelChoice.advise(tu.copy(localModel = "local:qwen-2b", localName = "Qwen 2B", localQuality = 2.0, wordsPerSec = 30.0))
        assertEquals(Choice.TU_SERVER, small.recommended)
        assertTrue("klein" in small.why)
        assertTrue(small.local.risks.any { "falsche oder unvollständige Befehle" in it })
    }

    @Test
    fun withoutLocalModelTheTuServerAndAWeakPcIsNamed() {
        val a = ServerModelChoice.advise(tu.copy(ramGb = 8.0))
        assertEquals(Choice.TU_SERVER, a.recommended)
        assertTrue("8 GB" in a.why, a.why)
        assertFalse(a.local.possible)
        assertTrue(a.local.risks.any { "Lokale Modelle" in it })
    }

    @Test
    fun withoutUniKeyOnlyLocalAndCloudStaysBlocked() {
        val a = ServerModelChoice.advise(Facts(localModel = "local:qwen-4b", localName = "Qwen 4B", localQuality = 4.0, cloudKey = true))
        assertEquals(Choice.LOCAL, a.recommended)
        assertFalse(a.tu.possible)
        assertTrue("externer Anbieter ist fürs Terminal gesperrt" in a.why, a.why)
        val none = ServerModelChoice.advise(Facts())
        assertNull(none.recommended)
        assertFalse(none.anyPossible, "ohne Modell und ohne Uni-Schlüssel: kein Pop-up")
    }

    @Test
    fun theRisksOfTheTuServerAreSaidPlainly() {
        val a = ServerModelChoice.advise(tu.copy(localModel = "local:qwen-14b", localName = "Qwen 14B", localQuality = 14.0, wordsPerSec = 20.0))
        val risks = a.tu.risks.joinToString(" ")
        assertTrue("Namen von Personen in Logs" in risks && "Uni-Server" in risks && "Störung" in risks, risks)
        assertTrue("ohne deinen Klick" in a.both && "Cloud-Anbieter" in a.both)
        assertEquals(10, ServerModelChoice.secondsPerProposal(20.0))
    }
}
