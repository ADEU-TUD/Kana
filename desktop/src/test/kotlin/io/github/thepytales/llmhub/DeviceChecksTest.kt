package io.github.thepytales.llmhub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thepytales.llmhub.data.AppMap
import io.github.thepytales.llmhub.data.AppSettings
import io.github.thepytales.llmhub.data.DeviceCheckStore
import io.github.thepytales.llmhub.data.DeviceChecks
import io.github.thepytales.llmhub.ui.DeviceChecksSection
import io.github.thepytales.llmhub.ui.LlmHubTheme
import io.github.thepytales.llmhub.ui.ProblemDraft
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Geführte Prüfung am Gerät (AP-133): the list in the repository is free of mistakes and leads to places that exist, the
 * results disappear and come back as they should, the report puts what did not pass first, and the page leads through
 * the steps (pictures in build/screenshots/pruefungen-*.png).
 */
@OptIn(ExperimentalTestApi::class)
class DeviceChecksTest {
    private val base = java.nio.file.Files.createTempDirectory("adeu-pruefungen").toFile().also {
        if (System.getProperty("adeu.data").isNullOrBlank()) System.setProperty("adeu.data", File(it, "daten").absolutePath)
    }

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    private val sample = """
        # Kommentar
        == AP-901 Erstes Paket
        geräte: windows, android
        vorher: Ein Testgerät.
        - Schritt eins
          tun: Etwas tun, das länger
            beschrieben wird.
          erwartet: Es klappt.
          ort: area.files
        - Schritt zwei
          geräte: android
          tun: Am Handy tun.
          erwartet: Es klappt am Handy.
        == AP-902 Zweites Paket
        - Nur ein Schritt
          tun: Tun.
          erwartet: Passt.
    """.trimIndent()

    @Test
    fun theListInTheRepositoryIsFreeOfMistakes() {
        val cat = DeviceChecks.catalog
        assertEquals(emptyList(), cat.errors, "Fehler in pruefungen.txt")
        assertTrue(cat.steps.size >= 30, "die offenen Prüfungen der Pakete stehen in der Liste: ${cat.steps.size}")
        // Fertig, wenn: the open checks of AP-120 to AP-125 can be gone through in one session at the Windows PC.
        listOf("AP-120", "AP-121", "AP-122", "AP-123", "AP-125").forEach { id ->
            val p = cat.packages.firstOrNull { it.id == id }
            assertNotNull(p, "$id fehlt")
            assertTrue(p.stepsOn("windows").isNotEmpty(), "$id hat Schritte für Windows")
        }
        // Every place exists on every device the step is for.
        cat.steps.filter { it.place != null }.forEach { s ->
            if ("windows" in s.devices || "macos" in s.devices || "linux" in s.devices) assertNotNull(AppMap.byId(s.place!!, AppMap.build()), "Ort ${s.place} (${s.key})")
            if ("android" in s.devices && s.devices.size == 1) assertNotNull(AppMap.byId(s.place!!, AppMap.build(android = true)), "Ort ${s.place} auf Android (${s.key})")
        }
        // Each package is one of the plans.
        val plans = File("../docs/ARBEITSPLAN.md").readText() + File("../docs/DESIGN-ARBEITSPLAN.md").readText()
        cat.packages.forEach { p -> assertTrue("### ${p.id} " in plans, "${p.id} steht in keinem Arbeitsplan") }
        assertEquals(cat.steps.size, cat.steps.map { it.key }.toSet().size, "Schlüssel eindeutig")
    }

    @Test
    fun readsPackagesStepsDevicesAndLongerTexts() {
        val cat = DeviceChecks.parse(sample)
        assertEquals(emptyList(), cat.errors)
        assertEquals(listOf("AP-901", "AP-902"), cat.packages.map { it.id })
        val first = cat.packages[0]
        assertEquals("Erstes Paket", first.title)
        assertEquals("Ein Testgerät.", first.before)
        assertEquals("Etwas tun, das länger beschrieben wird.", first.steps[0].todo)
        assertEquals("area.files", first.steps[0].place)
        assertEquals(setOf("windows", "android"), first.steps[0].devices)
        assertEquals(setOf("android"), first.steps[1].devices)
        assertEquals(DeviceChecks.DEVICES.keys, cat.packages[1].steps[0].devices, "ohne Angabe: alle Geräte")
        assertEquals(listOf("Schritt eins"), first.stepsOn("windows").map { it.title })
    }

    @Test
    fun namesMistakesInTheList() {
        val cat = DeviceChecks.parse(
            """
            - Schritt ohne Paket
            == XY-1 Falsche Nummer
            - Ohne Erwartung
              tun: Etwas.
              geräte: toaster
            == AP-5 Leer
            Unsinn hier
            """.trimIndent()
        )
        val text = cat.errors.joinToString("\n")
        assertTrue("Schritt ohne Paket" in text, text)
        assertTrue("keine AP-/APD-Nummer" in text, text)
        assertTrue("erwartet: fehlt" in text, text)
        assertTrue("unbekanntes Gerät „toaster“" in text, text)
        assertTrue("AP-5: keine Schritte" in text, text)
        assertTrue("nicht verstanden" in text, text)
    }

    @Test
    fun passedStepsDisappearDonePackagesTooAndTheReportPutsFailuresFirst() {
        val cat = DeviceChecks.parse(sample)
        val store = DeviceCheckStore(base) { cat }
        val (one, two) = cat.packages[0].steps
        val three = cat.packages[1].steps[0]
        assertEquals(listOf(one, three), store.open("windows"))
        assertEquals(listOf(one, two, three), store.open("android"))

        store.record(one, ok = true, note = "", version = "1.0.75", device = "windows")
        store.attach(three, png())
        store.record(three, ok = false, note = "Knopf fehlt", version = "1.0.75", device = "windows")
        assertEquals(listOf(three), store.open("windows"), "Passt verschwindet, Passt nicht bleibt")
        assertEquals(listOf("AP-901"), store.donePackages("windows").map { it.id })
        assertEquals(listOf("AP-902"), store.openPackages("windows").map { it.id })
        assertEquals(listOf("AP-901", "AP-902"), store.openPackages("android").map { it.id }, "am Handy ist Schritt zwei noch offen")
        assertEquals(DeviceCheckStore.State.FAILED, store.state(three))

        val report = store.report("windows", "1.0.76", "Windows 11")
        assertTrue(report.startsWith("Prüfung am Gerät · Kana 1.0.76 · Windows · Windows 11"), report)
        assertTrue("1 passt · 1 passt nicht · 0 offen" in report, report)
        assertTrue(report.indexOf("PASST NICHT") < report.indexOf("\nPASST\n"), report)
        assertTrue("Notiz: Knopf fehlt" in report && "Bild: AP-902_nur-ein-schritt.png" in report, report)
        assertTrue("(geprüft mit 1.0.75)" in report, "ältere Version wird genannt")

        // Survives a restart; the ZIP carries report and picture.
        val again = DeviceCheckStore(base) { cat }
        assertEquals(listOf(three), again.open("windows"))
        assertNotNull(again.imageOf(three))
        val zip = ByteArrayOutputStream().also { again.writeZip(it, report) }.toByteArray()
        val names = ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("bericht.txt", "bilder/AP-902_nur-ein-schritt.png"), names)

        // Wieder offen, renamed steps and Zurücksetzen.
        again.forget(three)
        assertNull(again.imageOf(three))
        val renamed = DeviceChecks.parse(sample.replace("Schritt eins", "Schritt eins, neu"))
        assertEquals(2, DeviceCheckStore(base) { renamed }.open("windows").size, "ein umbenannter Schritt wird neu geprüft")
        again.reset()
        assertEquals(2, again.open("windows").size)
        assertFalse(again.imageDir.exists())
    }

    @Test
    fun aScreenshotBeforeTheDecisionStaysWithTheStep() {
        val cat = DeviceChecks.parse(sample)
        val store = DeviceCheckStore(base) { cat }
        val one = cat.steps[0]
        store.attach(one, png())
        assertNotNull(store.imageOf(one), "vor der Entscheidung schon sichtbar")
        store.record(one, ok = false, note = "", version = "1", device = "windows")
        assertEquals("AP-901_schritt-eins.png", store.result(one)!!.image)
        store.record(one, ok = true, note = "doch", version = "1", device = "windows")
        assertEquals("AP-901_schritt-eins.png", store.result(one)!!.image, "bleibt bei neuer Entscheidung")
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    // ── The page ────────────────────────────────────────────────────────────────────────────────────────────────

    private val c by lazy { AppContainer(android.content.Context(File(base, "files").apply { mkdirs() }, File(base, "cache").apply { mkdirs() })) }

    private val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    /** Tall enough for the whole list (it grows with every package's steps – AP-129 added eight, AP-137 nine). */
    private fun ui(width: Dp, block: SkikoComposeUiTest.() -> Unit) =
        runSkikoComposeUiTest(Size(width.value * 2f + 80, 6600 * 2f), Density(2f)) {
            mainClock.autoAdvance = false
            block()
        }

    private fun SkikoComposeUiTest.settle() {
        repeat(4) {
            Thread.sleep(100)
            mainClock.advanceTimeBy(300)
        }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.page(width: Dp, navigated: MutableList<String>) {
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LlmHubTheme(AppSettings()) {
                    Box(Modifier.background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopStart) {
                        Column(
                            Modifier.width(width).wrapContentHeight().background(MaterialTheme.colorScheme.background).padding(16.dp).testTag("shot"),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) { DeviceChecksSection(c, onNavigate = { navigated += it }, here = "windows") }
                    }
                }
            }
        }
        settle()
    }

    private fun SkikoComposeUiTest.shot(name: String) {
        settle()
        val img = onNodeWithTag("shot").captureToImage().toAwtImage()
        File("build/screenshots").mkdirs()
        ImageIO.write(img, "png", File("build/screenshots/$name"))
    }

    private fun SkikoComposeUiTest.has(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun thePageLeadsStepByStepAndHandsTheReportToProblemMelden() = ui(760.dp) {
        val navigated = mutableListOf<String>()
        c.deviceChecks.reset()
        page(760.dp, navigated)
        val windows = DeviceChecks.catalog.steps.filter { it.on("windows") }
        assertTrue(has("0 von ${windows.size} Schritten geprüft"))
        assertTrue(has("AP-121 · Videoeditor für Einsteiger"))
        shot("pruefungen-uebersicht.png")

        onNodeWithText("Prüfung beginnen").performClick()
        settle()
        val first = windows.first()
        assertTrue(has(first.title) && has("Schritt 1 von"))
        assertTrue(has("So geht's") && has("Das soll passieren"))
        shot("pruefungen-schritt.png")

        // Hingehen leads to the place of the step.
        if (first.place != null) {
            onNodeWithText("Hingehen").performClick()
            settle()
            assertEquals(AppMap.byId(first.place!!)!!.route, navigated.last())
        }
        onNodeWithText("Passt").performClick()
        settle()
        assertEquals(DeviceCheckStore.State.PASSED, c.deviceChecks.state(first))
        val second = windows[1]
        assertTrue(has(second.title) && has("Schritt 2 von"), "danach der nächste Schritt")

        onNodeWithText("Notiz (was war anders?)").performTextInput("Text ist abgeschnitten")
        onNodeWithText("Passt nicht").performClick()
        settle()
        assertEquals("Text ist abgeschnitten", c.deviceChecks.result(second)!!.note)
        assertTrue(has("2 von ${windows.size} Schritten geprüft"))
        shot("pruefungen-bericht.png")

        onNodeWithText("Bericht über Problem melden").performClick()
        settle()
        assertEquals("settings/help", navigated.last())
        val draft = ProblemDraft.take()
        assertTrue(draft.startsWith("Prüfung am Gerät") && "Notiz: Text ist abgeschnitten" in draft, draft)
    }

    @Test
    fun allPassedShowsDoneAndNothingOpen() = ui(400.dp) {
        val store = c.deviceChecks
        store.reset()
        DeviceChecks.catalog.packages.first { it.id == "AP-123" }.stepsOn("windows").forEach { store.record(it, true, "", "1", "windows") }
        page(400.dp, mutableListOf())
        assertTrue(has("Erledigt") && has("AP-123 · Bedienelemente aus einem Guss"))
        assertFalse(onAllNodesWithText("Tooltips an Symbol-Knöpfen").fetchSemanticsNodes().isNotEmpty(), "erledigte Schritte verschwinden")
        shot("pruefungen-handy-erledigt.png")
    }
}
