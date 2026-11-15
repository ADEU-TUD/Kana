/*
 * AP-132 (a): Der PR-Check lässt nur die Tests laufen, die eine Änderung betreffen. Die Regeln und Bereiche stehen in
 * testbereiche.txt; hier steht, wie sie angewandt werden.
 *
 *   ./gradlew -p desktop testAuswahl [-Pseit=<Stand>] [-Pbereiche=a,b] [-Pzeigen]
 *   ./gradlew -p desktop testAuswahl -Pseit=<Stand> -Pbis=<Stand> -Pzeigen   # Regeln an einem früheren PR ausprobieren
 *   ./gradlew -p desktop -q testBedarf [-Pseit=<Stand>]   # nur „keine“, „android“ oder „tests“ (ohne zu kompilieren)
 *
 * Ohne -Pseit gilt der Stand, an dem der Zweig von origin/main abging, und auch was noch nicht eingecheckt ist.
 */
import java.io.DataInputStream

val repoDir: File = projectDir.parentFile
val paketPfad = "io/github/thepytales/llmhub/"
val quellBaeume = listOf("app/src/main/java/", "desktop/src/main/kotlin/", "speech/src/main/java/")
val testBaum = "desktop/src/test/kotlin/"
/** A source file that this many test classes use directly concerns nearly everything. */
val direktGrenze = 15

class Bereich(val name: String, val text: String) {
    val quellen = mutableListOf<Regex>()
    val tests = mutableListOf<Regex>()
}

fun globRegex(muster: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < muster.length) {
        when {
            muster.startsWith("**", i) -> { sb.append(".*"); i++ }
            muster[i] == '*' -> sb.append("[^/]*")
            muster[i] == '?' -> sb.append("[^/]")
            else -> sb.append(Regex.escape(muster[i].toString()))
        }
        i++
    }
    return sb.toString()
}

fun glob(muster: String): Regex =
    if (muster.startsWith("/")) Regex(globRegex(muster.substring(1)))
    else Regex("(?:" + quellBaeume.joinToString("|") { Regex.escape(it) } + ")" + Regex.escape(paketPfad) + globRegex(muster))

fun testGlob(muster: String) = Regex(muster.split('*').joinToString("[^.]*") { Regex.escape(it) })

fun leseBereiche(): List<Bereich> {
    val liste = mutableListOf<Bereich>()
    file("testbereiche.txt").readLines().forEach { zeile ->
        val z = zeile.trim()
        when {
            z.isEmpty() || z.startsWith("#") -> {}
            z.startsWith("[") -> liste += Bereich(z.substring(1, z.indexOf(']')), z.substringAfter(']').trim())
            z.startsWith("quellen:") -> liste.last().quellen += z.removePrefix("quellen:").trim().split(Regex("\\s+")).map(::glob)
            z.startsWith("tests:") -> liste.last().tests += z.removePrefix("tests:").trim().split(Regex("\\s+")).map(::testGlob)
        }
    }
    return liste
}

fun git(vararg args: String): List<String> {
    val p = ProcessBuilder(listOf("git") + args).directory(repoDir).redirectErrorStream(false).start()
    val out = p.inputStream.bufferedReader().readLines()
    check(p.waitFor() == 0) { "git ${args.joinToString(" ")} fehlgeschlagen" }
    return out.filter { it.isNotBlank() }
}

fun geaenderteDateien(): Pair<String, List<String>> {
    val seit = (findProperty("seit") as String?)?.takeIf { it.isNotBlank() }
        ?: runCatching { git("merge-base", "origin/main", "HEAD").first() }.getOrElse { "origin/main" }
    // -Pbis=<Stand>: only the commits between the two (to try the rules on earlier pull requests).
    val bis = (findProperty("bis") as String?)?.takeIf { it.isNotBlank() }
    val dateien = if (bis != null) git("diff", "--name-only", "--no-renames", seit, bis)
        else (git("diff", "--name-only", "--no-renames", seit) + git("ls-files", "--others", "--exclude-standard")).distinct().sorted()
    return seit to dateien
}

/** Files of the app that only the Android app compiles: the desktop has its own version, or leaves them out. */
fun nurAndroid(pfad: String): Boolean {
    if (!pfad.startsWith("app/src/main/java/")) return false
    val rel = pfad.removePrefix("app/src/main/java/")
    return file("src/main/kotlin/$rel").exists() || rel in setOf(
        "android/print/PdfPrintBridge.kt", "io/github/thepytales/llmhub/MainActivity.kt",
        "io/github/thepytales/llmhub/transcribe/TranscriptionService.kt", "io/github/thepytales/llmhub/ui/PhoneBrowser.kt",
    )
}

fun Bereich.passt(pfad: String) = quellen.any { it.matches(pfad) }

/** "keine", "android" or "tests" – without compiling. */
fun bedarf(bereiche: List<Bereich>, dateien: List<String>): String {
    val doku = bereiche.first { it.name == "doku" }
    val android = bereiche.first { it.name == "android" }
    val rest = dateien.filterNot { doku.passt(it) }
    return when {
        rest.isEmpty() -> "keine"
        rest.all { android.passt(it) || nurAndroid(it) } -> "android"
        else -> "tests"
    }
}

// --- What the compiled classes refer to (constant pool) and which source file they come from.
class Klasse(val name: String, val quelle: String, val bezuege: Set<String>)

fun leseKlasse(f: File, name: String): Klasse {
    DataInputStream(f.inputStream().buffered()).use { d ->
        d.readInt(); d.readUnsignedShort(); d.readUnsignedShort()
        val n = d.readUnsignedShort()
        val utf = arrayOfNulls<String>(n)
        val klassen = mutableListOf<Int>()
        var k = 1
        while (k < n) {
            when (val t = d.readUnsignedByte()) {
                1 -> utf[k] = d.readUTF()
                7 -> klassen += d.readUnsignedShort()
                8, 16, 19, 20 -> d.readUnsignedShort()
                3, 4, 9, 10, 11, 12, 17, 18 -> d.readInt()
                5, 6 -> { d.readLong(); k++ }
                15 -> { d.readUnsignedByte(); d.readUnsignedShort() }
                else -> error("Unbekannter Eintrag $t in $f")
            }
            k++
        }
        d.readUnsignedShort(); d.readUnsignedShort(); d.readUnsignedShort()
        repeat(d.readUnsignedShort()) { d.readUnsignedShort() }
        fun ueberspringen() = repeat(d.readUnsignedShort()) {
            d.readUnsignedShort(); d.readUnsignedShort(); d.readUnsignedShort()
            repeat(d.readUnsignedShort()) { d.readUnsignedShort(); d.skipNBytes(d.readInt().toLong()) }
        }
        ueberspringen(); ueberspringen()
        var quelle = "?"
        repeat(d.readUnsignedShort()) {
            val a = utf[d.readUnsignedShort()]; val len = d.readInt()
            if (a == "SourceFile") quelle = utf[d.readUnsignedShort()] ?: "?" else d.skipNBytes(len.toLong())
        }
        val bezuege = HashSet<String>()
        klassen.forEach { i -> utf[i]?.trimStart('[')?.let { bezuege += it.removePrefix("L").removeSuffix(";") } }
        val typ = Regex("L([\\w/$]+);")
        utf.forEach { s -> if (s != null && 'L' in s && ';' in s) typ.findAll(s).forEach { bezuege += it.groupValues[1] } }
        val paket = name.substringBeforeLast('/', "")
        return Klasse(name, if (paket.isEmpty()) quelle else "$paket/$quelle", bezuege)
    }
}

fun leseKlassen(dirs: Iterable<File>): Map<String, Klasse> = dirs.filter { it.isDirectory }.flatMap { dir ->
    dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
        .map { leseKlasse(it, it.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".class")) }.toList()
}.associateBy { it.name }

/** Path below a source tree → path below the package root of the class files ("io/…/ui/Foo.kt"). */
fun klassenPfad(pfad: String): String? =
    (quellBaeume + testBaum).firstOrNull { pfad.startsWith(it) }?.let { pfad.removePrefix(it) }

class Auswahl(val alle: Boolean, val tests: Set<String>, val gruende: List<String>)

/** Test classes: the classes named like their file (FooTest in FooTest.kt) that have tests (not CommandCorpus & Co.). */
fun testKlassen(test: Map<String, Klasse>) = test.values.filter {
    '$' !in it.name && it.quelle.substringAfterLast('/') == it.name.substringAfterLast('/') + ".kt" && "org/junit/jupiter/api/Test" in it.bezuege
}

fun Bereich.testsVon(klassen: List<Klasse>) = klassen.filter { k -> tests.any { it.matches(k.name.substringAfterLast('/')) } }.map { it.name }

/** The tests of [kern] and every test class that is in no area: they always run. */
fun kernTests(bereiche: List<Bereich>, klassen: List<Klasse>): Set<String> {
    val fachlich = bereiche.filter { it.tests.isNotEmpty() && it.name != "kern" }
    val ohneBereich = klassen.filter { k -> fachlich.none { b -> b.tests.any { it.matches(k.name.substringAfterLast('/')) } } }.map { it.name }
    return (bereiche.first { it.name == "kern" }.testsVon(klassen) + ohneBereich).toSet()
}

fun auswahl(bereiche: List<Bereich>, dateien: List<String>, haupt: Map<String, Klasse>, test: Map<String, Klasse>): Auswahl {
    val gruende = mutableListOf<String>()
    val testDateien = test.values.groupBy { it.quelle }
    val testKlassen = testKlassen(test)
    fun bereichTests(b: Bereich) = b.testsVon(testKlassen)
    val kern = kernTests(bereiche, testKlassen)
    // Which test files use a source file directly.
    val nutzer = HashMap<String, MutableSet<String>>()
    testDateien.forEach { (datei, klassen) ->
        val bez = klassen.flatMapTo(HashSet()) { it.bezuege }
        bez.mapNotNull { haupt[it]?.quelle ?: test[it]?.quelle?.takeIf { q -> q != datei } }.toSet().forEach { q ->
            nutzer.getOrPut(q) { HashSet() } += datei
        }
    }
    fun testsDerDateien(d: Collection<String>) = testKlassen.filter { it.quelle in d }.map { it.name }

    val doku = bereiche.first { it.name == "doku" }
    val android = bereiche.first { it.name == "android" }
    val alles = bereiche.first { it.name == "alles" }
    val gewaehlt = HashSet<String>(kern)
    for (pfad in dateien) {
        if (doku.passt(pfad)) continue
        if (android.passt(pfad) || nurAndroid(pfad)) { gruende += "$pfad: nur Android"; continue }
        if (alles.passt(pfad)) return Auswahl(true, emptySet(), gruende + "$pfad: betrifft alles (testbereiche.txt › [alles])")
        val kp = klassenPfad(pfad)
        if (kp != null && pfad.startsWith(testBaum)) {
            val betroffen = testsDerDateien(setOf(kp) + nutzer[kp].orEmpty())
            gewaehlt += betroffen
            gruende += "$pfad: Testdatei – ${betroffen.size} Testklassen"
            continue
        }
        val direkt = kp?.let { nutzer[it] }.orEmpty()
        if (direkt.size >= direktGrenze) return Auswahl(true, emptySet(), gruende + "$pfad: ${direkt.size} Testdateien benutzen sie direkt – betrifft fast alles")
        val passend = bereiche.filter { it.name !in setOf("doku", "android", "alles") && it.passt(pfad) }
        if (passend.isEmpty()) {
            val geloescht = !File(repoDir, pfad).exists()
            if (!geloescht || direkt.isNotEmpty() || kp == null) {
                return Auswahl(true, emptySet(), gruende + "$pfad: in keinem Bereich (testbereiche.txt) – volle Sammlung")
            }
        }
        passend.forEach { gewaehlt += bereichTests(it) }
        gewaehlt += testsDerDateien(direkt)
        gruende += "$pfad: " + (passend.map { it.name } + direkt.takeIf { it.isNotEmpty() }?.let { "${it.size} direkt" }).filterNotNull().joinToString(", ")
    }
    return Auswahl(false, gewaehlt, gruende)
}

val testSet = the<SourceSetContainer>()["test"]
val mainSet = the<SourceSetContainer>()["main"]

tasks.register("testBedarf") {
    group = "verification"
    description = "Gibt aus, ob die Änderungen Tests brauchen: keine, android oder tests (ohne zu kompilieren)."
    doLast {
        val (_, dateien) = geaenderteDateien()
        println(bedarf(leseBereiche(), dateien))
    }
}

tasks.register<Test>("testAuswahl") {
    group = "verification"
    description = "Nur die Tests, die die Änderungen betreffen (testbereiche.txt); -Pbereiche=a,b für bestimmte Bereiche."
    testClassesDirs = testSet.output.classesDirs
    classpath = testSet.runtimeClasspath
    useJUnitPlatform()
    jvmArgs(tasks.named<Test>("test").get().jvmArgs.orEmpty())
    systemProperties(tasks.named<Test>("test").get().systemProperties)
    maxParallelForks = tasks.named<Test>("test").get().maxParallelForks
    outputs.upToDateWhen { false }
    val zeigen = project.hasProperty("zeigen")
    val bereicheWahl = (findProperty("bereiche") as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    val bericht = layout.buildDirectory.file("testauswahl.txt")
    doFirst {
        val bereiche = leseBereiche()
        val haupt = leseKlassen(mainSet.output.classesDirs)
        val tests = leseKlassen(testSet.output.classesDirs)
        val ergebnis: Auswahl
        val kopf: String
        if (bereicheWahl != null) {
            val unbekannt = bereicheWahl.filter { w -> bereiche.none { it.name == w } }
            check(unbekannt.isEmpty()) { "Unbekannte Bereiche: $unbekannt – vorhanden: ${bereiche.filter { it.tests.isNotEmpty() }.joinToString { it.name }}" }
            val k = testKlassen(tests)
            ergebnis = Auswahl(false, bereiche.filter { it.name in bereicheWahl }.flatMap { it.testsVon(k) }.toSet() + kernTests(bereiche, k),
                listOf("Bereiche: ${(bereicheWahl + "kern").distinct().joinToString()}"))
            kopf = "Gewählte Bereiche"
        } else {
            val (seit, dateien) = geaenderteDateien()
            kopf = "Änderungen seit ${seit.take(12)}: ${dateien.size} Dateien"
            ergebnis = when (bedarf(bereiche, dateien)) {
                "tests" -> auswahl(bereiche, dateien, haupt, tests)
                else -> Auswahl(false, emptySet(), listOf("Nur Texte oder Android – keine Tests der Windows-Version nötig"))
            }
        }
        val alleTests = testKlassen(tests).size
        val zeilen = buildList {
            add(kopf)
            addAll(ergebnis.gruende.map { "  $it" })
            add(if (ergebnis.alle) "→ volle Sammlung ($alleTests Testklassen)" else "→ ${ergebnis.tests.size} von $alleTests Testklassen")
            if (!ergebnis.alle) ergebnis.tests.sorted().forEach { add("    ${it.substringAfter(paketPfad).replace('/', '.')}") }
        }
        bericht.get().asFile.writeText(zeilen.joinToString("\n") + "\n")
        zeilen.take(if (zeigen) zeilen.size else 60).forEach { logger.lifecycle(it) }
        System.getenv("GITHUB_STEP_SUMMARY")?.let { s ->
            File(s).appendText("### Testauswahl\n\n```\n" + zeilen.joinToString("\n") + "\n```\n")
        }
        if (zeigen || (!ergebnis.alle && ergebnis.tests.isEmpty())) throw StopExecutionException()
        if (!ergebnis.alle) filter { ergebnis.tests.forEach { includeTestsMatching(it.replace('/', '.')) } }
    }
}
