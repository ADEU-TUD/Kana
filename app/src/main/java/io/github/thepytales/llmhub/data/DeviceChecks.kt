package io.github.thepytales.llmhub.data

import io.github.thepytales.llmhub.logFailure
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Geführte Prüfung am Gerät (AP-133): the open checks of all packages come from one list in the repository
 * (`app/src/main/resources/pruefungen/pruefungen.txt`, shared by both apps). *Einstellungen › Prüfungen am Gerät* leads
 * through them step by step; each step is *Passt* or *Passt nicht*, with a note and a screenshot, and at the end a report
 * goes out through *Problem melden*. A step that passed on this device disappears; a package whose steps all passed is
 * done. A new package brings its steps by adding lines to the list.
 */
object DeviceChecks {
    /** Devices a step can be for, with the name shown. */
    val DEVICES = linkedMapOf("windows" to "Windows", "macos" to "macOS", "linux" to "Linux", "android" to "Android")

    const val RESOURCE = "/pruefungen/pruefungen.txt"

    data class Step(
        val pkg: String,
        val title: String,
        /** What to do. */
        val todo: String,
        /** What should happen. */
        val expected: String,
        /** Place of the app map ([AppMap.byId]) the step starts at; null = none. */
        val place: String?,
        val devices: Set<String>,
    ) {
        /** Stable as long as package and title stay; a renamed step is checked again. */
        val key: String = "$pkg/" + AppMap.norm(title).replace(' ', '-')
        fun on(device: String) = device in devices
    }

    data class Package(val id: String, val title: String, val devices: Set<String>, val before: String, val steps: List<Step>) {
        fun stepsOn(device: String) = steps.filter { it.on(device) }
    }

    data class Catalog(val packages: List<Package>, val errors: List<String>) {
        val steps: List<Step> get() = packages.flatMap { it.steps }
        fun step(key: String): Step? = steps.firstOrNull { it.key == key }
    }

    /** The list built into the app. */
    val catalog: Catalog by lazy {
        val text = runCatching { DeviceChecks::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }
            .logFailure("Prüfliste lesen").getOrNull().orEmpty()
        parse(text)
    }

    /**
     * Reads the list: `== AP-xx Titel` starts a package (`geräte:`, `vorher:` below it hold for its steps), `- Titel`
     * a step with `tun:`, `erwartet:`, optional `ort:` (place of the app map) and `geräte:`; longer texts go on in
     * indented lines. `#` starts a comment. Mistakes land in [Catalog.errors] (a test checks there are none).
     */
    fun parse(text: String): Catalog {
        val errors = mutableListOf<String>()
        val packages = mutableListOf<Package>()
        var pkg: Package? = null
        var step: MutableMap<String, String>? = null
        var stepTitle = ""
        var lastKey: String? = null
        var lineNo = 0

        fun devicesOf(value: String, where: String): Set<String> {
            val list = value.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val all = if (list == listOf("alle")) DEVICES.keys else list.toSet()
            all.filter { it !in DEVICES }.forEach { errors += "Zeile $lineNo ($where): unbekanntes Gerät „$it“" }
            return all.filter { it in DEVICES }.toSet()
        }

        fun closeStep() {
            val s = step ?: return
            val p = pkg ?: return
            val todo = s["tun"].orEmpty()
            val expected = s["erwartet"].orEmpty()
            if (todo.isBlank()) errors += "${p.id} „$stepTitle“: tun: fehlt"
            if (expected.isBlank()) errors += "${p.id} „$stepTitle“: erwartet: fehlt"
            val devices = s["geräte"]?.let { devicesOf(it, "${p.id} „$stepTitle“") } ?: p.devices
            val added = Step(p.id, stepTitle, todo, expected, s["ort"]?.trim()?.ifBlank { null }, devices)
            if (p.steps.any { it.key == added.key }) errors += "${p.id}: Schritt „$stepTitle“ doppelt"
            pkg = p.copy(steps = p.steps + added)
            step = null
        }

        fun closePackage() {
            closeStep()
            pkg?.let { p ->
                if (p.steps.isEmpty()) errors += "${p.id}: keine Schritte"
                packages += p
            }
            pkg = null
        }

        for (raw in text.lines()) {
            lineNo++
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            when {
                line.startsWith("== ") -> {
                    closePackage()
                    val head = line.removePrefix("== ").trim()
                    val id = head.substringBefore(' ')
                    if (!Regex("APD?-\\d+").matches(id)) errors += "Zeile $lineNo: Paket „$id“ ist keine AP-/APD-Nummer"
                    if (packages.any { it.id == id }) errors += "Zeile $lineNo: Paket $id doppelt"
                    pkg = Package(id, head.substringAfter(' ', "").trim().removePrefix("·").trim(), DEVICES.keys, "", emptyList())
                    lastKey = null
                }
                line.startsWith("- ") -> {
                    if (pkg == null) { errors += "Zeile $lineNo: Schritt ohne Paket"; continue }
                    closeStep()
                    stepTitle = line.removePrefix("- ").trim()
                    step = mutableMapOf()
                    lastKey = null
                }
                else -> {
                    val trimmed = line.trim()
                    val key = trimmed.substringBefore(':', "").lowercase()
                    val known = key in setOf("tun", "erwartet", "ort", "geräte", "vorher")
                    val target = step
                    when {
                        known && target != null -> { target[key] = trimmed.substringAfter(':').trim(); lastKey = key }
                        known && pkg != null && key == "geräte" -> pkg = pkg!!.copy(devices = devicesOf(trimmed.substringAfter(':'), pkg!!.id))
                        known && pkg != null && key == "vorher" -> { pkg = pkg!!.copy(before = trimmed.substringAfter(':').trim()); lastKey = "vorher" }
                        // A longer text goes on in the next indented line.
                        raw.startsWith(" ") && lastKey != null && target != null -> target[lastKey!!] = target[lastKey!!].orEmpty() + " " + trimmed
                        raw.startsWith(" ") && lastKey == "vorher" && pkg != null -> pkg = pkg!!.copy(before = pkg!!.before + " " + trimmed)
                        else -> errors += "Zeile $lineNo: nicht verstanden: „$trimmed“"
                    }
                }
            }
        }
        closePackage()
        return Catalog(packages, errors)
    }
}

/** What came out of one step on this device. */
@Serializable
data class CheckResult(
    val key: String,
    val ok: Boolean,
    val note: String = "",
    /** File name of the screenshot in [DeviceCheckStore.imageDir]; empty = none. */
    val image: String = "",
    val version: String = "",
    val device: String = "",
    val at: Long = 0,
)

/** Results of the checks on this device (`pruefungen.json`, screenshots in `pruefungen/`). */
class DeviceCheckStore(dir: File, private val catalog: () -> DeviceChecks.Catalog = { DeviceChecks.catalog }) {
    enum class State { OPEN, PASSED, FAILED }

    val results = ListStore(FileBackend(File(dir, "pruefungen.json")), CheckResult.serializer(), { it.key })
    val imageDir = File(dir, "pruefungen")

    fun result(step: DeviceChecks.Step): CheckResult? = results.get(step.key)

    fun state(step: DeviceChecks.Step, list: List<CheckResult> = results.current): State =
        when (list.firstOrNull { it.key == step.key }?.ok) { true -> State.PASSED; false -> State.FAILED; null -> State.OPEN }

    /** Steps still to do on [device]: not yet checked or did not pass – in the order of the list. */
    fun open(device: String, list: List<CheckResult> = results.current): List<DeviceChecks.Step> =
        catalog().steps.filter { it.on(device) && state(it, list) != State.PASSED }

    /** Packages with steps on [device] that are not all passed (done ones disappear from the list). */
    fun openPackages(device: String, list: List<CheckResult> = results.current): List<DeviceChecks.Package> =
        catalog().packages.filter { p -> p.stepsOn(device).any { state(it, list) != State.PASSED } }

    fun donePackages(device: String, list: List<CheckResult> = results.current): List<DeviceChecks.Package> =
        catalog().packages.filter { p -> p.stepsOn(device).let { s -> s.isNotEmpty() && s.all { state(it, list) == State.PASSED } } }

    /** Records *Passt* or *Passt nicht*; note and screenshot stay unless given anew. */
    fun record(step: DeviceChecks.Step, ok: Boolean, note: String, version: String, device: String, now: Long = System.currentTimeMillis()) {
        val old = results.get(step.key)
        val image = pendingImages.remove(step.key) ?: old?.image.orEmpty()
        results.upsert(CheckResult(step.key, ok, note.trim(), image, version, device, now))
    }

    /** Screenshots taken before *Passt* or *Passt nicht* was chosen; [record] takes them over. */
    private val pendingImages = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Keeps [png] as the screenshot of [step] (one per step; a new one replaces it). */
    fun attach(step: DeviceChecks.Step, png: ByteArray): String {
        imageDir.mkdirs()
        val name = step.key.replace('/', '_').replace(Regex("[^A-Za-z0-9_-]"), "") + ".png"
        AtomicFiles.writeBytes(File(imageDir, name), png)
        val old = results.get(step.key)
        if (old != null) results.upsert(old.copy(image = name)) else pendingImages[step.key] = name
        return name
    }

    fun imageOf(step: DeviceChecks.Step): File? =
        (results.get(step.key)?.image?.ifBlank { null } ?: pendingImages[step.key])?.let { File(imageDir, it) }?.takeIf { it.isFile }

    fun forget(step: DeviceChecks.Step) {
        results.get(step.key)?.image?.takeIf { it.isNotBlank() }?.let { File(imageDir, it).delete() }
        pendingImages.remove(step.key)
        results.delete(step.key)
    }

    fun reset() {
        results.replaceAll(emptyList())
        pendingImages.clear()
        imageDir.deleteRecursively()
    }

    /** Text for *Problem melden*: what did not pass first, then what passed, then what is still open. */
    fun report(device: String, version: String, system: String): String {
        val cat = catalog()
        val list = results.current
        val mine = cat.steps.filter { it.on(device) }
        val failed = mine.filter { state(it, list) == State.FAILED }
        val passed = mine.filter { state(it, list) == State.PASSED }
        val open = mine.filter { state(it, list) == State.OPEN }
        fun pkgTitle(id: String) = cat.packages.firstOrNull { it.id == id }?.let { "${it.id} ${it.title}" } ?: id
        return buildString {
            append("Prüfung am Gerät · Kana $version · ${DeviceChecks.DEVICES[device] ?: device} · $system\n")
            append("${passed.size} passt · ${failed.size} passt nicht · ${open.size} offen\n")
            fun section(title: String, steps: List<DeviceChecks.Step>, withNote: Boolean) {
                if (steps.isEmpty()) return
                append("\n").append(title).append("\n")
                steps.groupBy { it.pkg }.forEach { (id, s) ->
                    append("\n").append(pkgTitle(id)).append("\n")
                    s.forEach { step ->
                        val r = list.firstOrNull { it.key == step.key }
                        append("- ").append(step.title)
                        if (r != null && r.version.isNotBlank() && r.version != version) append(" (geprüft mit ${r.version})")
                        append("\n")
                        if (withNote) {
                            append("  Erwartet: ").append(step.expected).append("\n")
                            r?.note?.takeIf { it.isNotBlank() }?.let { append("  Notiz: ").append(it.replace("\n", "\n  ")).append("\n") }
                            r?.image?.takeIf { it.isNotBlank() }?.let { append("  Bild: ").append(it).append("\n") }
                        } else {
                            r?.note?.takeIf { it.isNotBlank() }?.let { append("  Notiz: ").append(it.replace("\n", "\n  ")).append("\n") }
                        }
                    }
                }
            }
            section("PASST NICHT", failed, withNote = true)
            section("PASST", passed, withNote = false)
            if (open.isNotEmpty()) {
                append("\nNOCH OFFEN\n")
                open.groupBy { it.pkg }.forEach { (id, s) -> append(pkgTitle(id)).append(": ").append(s.joinToString(" · ") { it.title }).append("\n") }
            }
        }
    }

    /** The report with all screenshots as a ZIP (for the mail, which cannot carry pictures by itself). */
    fun writeZip(out: java.io.OutputStream, report: String) {
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("bericht.txt"))
            zip.write(report.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            results.current.mapNotNull { r -> r.image.ifBlank { null }?.let { File(imageDir, it) } }.filter { it.isFile }.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry("bilder/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
