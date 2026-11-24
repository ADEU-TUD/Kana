package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.data.CommandRecognizer
import io.github.thepytales.llmhub.docs.PptxReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AP-155: Lora heißt jetzt Kana – der alte Name wird weiter verstanden, alte Dateien bleiben „eigene“. */
class RenameKanaTest {
    @Test
    fun appIsCalledKanaAndKnowsItsOldName() {
        assertEquals("Kana", Brand.NAME)
        assertEquals(listOf("Kana", "Lora"), Brand.NAMES)
    }

    @Test
    fun commandModelHearsKanaAsTheNameItWasTrainedWith() {
        assertEquals("Lora, öffne die Dateien", CommandRecognizer.trainedName("Kana, öffne die Dateien"))
        assertEquals("hey Lora blende dich aus", CommandRecognizer.trainedName("hey kana blende dich aus"))
        assertEquals("Lora, mach das", CommandRecognizer.trainedName("Lora, mach das"), "der alte Name bleibt, wie er ist")
        assertEquals("Kanalisation und Kanaan", CommandRecognizer.trainedName("Kanalisation und Kanaan"), "nur das ganze Wort")
    }

    @Test
    fun decksOfBothNamesCountAsOwn() {
        fun core(creator: String) = mapOf("docProps/core.xml" to "<cp:coreProperties><dc:creator>$creator</dc:creator></cp:coreProperties>".toByteArray())
        assertTrue(PptxReader.isOwn(core("Kana")))
        assertTrue(PptxReader.isOwn(core("Lora")), "vor 1.0.78 geschrieben")
        assertFalse(PptxReader.isOwn(core("Word")))
    }
}
