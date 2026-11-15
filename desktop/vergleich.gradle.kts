/*
 * AP-132 (b): Vorher/Nachher-Bilder auf Knopfdruck.
 *
 *   ./gradlew -p desktop vergleich                           # alle *LookTest und *ReviewTest
 *   ./gradlew -p desktop vergleich -Ptests=DocEditorLookTest,ControlsLookTest -Pbasis=origin/main
 *
 * 1. Die Bild-Tests laufen hier im Zweig (Aufgabe bilderNachher, mit den Änderungen, die noch nicht eingecheckt sind).
 * 2. Der Stand von `basis` (Standard origin/main) wird in ../build/vergleich-vorher als eigenes Arbeitsverzeichnis
 *    ausgecheckt (git worktree) und läuft dort mit denselben Tests – zuerst mit den Testdateien des Zweigs (damit neue
 *    Szenen auch „vorher“ entstehen), und wenn die mit dem alten Code nicht kompilieren, mit denen von `basis`.
 * 3. Jedes PNG, das die Tests unter desktop/build geschrieben haben, wird mit seinem Gegenstück verglichen: Abweichung in
 *    Prozent der Bildpunkte, geänderte Stellen rot umrandet. Ergebnis: desktop/build/vergleich/index.html (eine Datei mit
 *    eingebetteten Bildern, direkt als Vergleichsseite für den Hauptnutzer verwendbar) und die Bilder in voller Größe.
 *
 * Kleine Unterschiede einzelner Bildpunkte (Kantenglättung) unter der Schwelle -Pschwelle (Standard 24 von 255 je
 * Farbkanal) zählen nicht.
 */
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO

val vergleichTests: List<String> = (findProperty("tests") as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("*LookTest", "*ReviewTest")
val vergleichBasis = (findProperty("basis") as String?)?.takeIf { it.isNotBlank() } ?: "origin/main"
val vergleichSchwelle = (findProperty("schwelle") as String?)?.toIntOrNull() ?: 24

/** Folders under desktop/build that hold build results, not pictures of the tests. */
val keineBilder = setOf(
    "classes", "kotlin", "tmp", "reports", "test-results", "resources", "libs", "generated", "shared-src", "speech",
    "compose", "portable", "vergleich", "kotlinToolingMetadata", "distributions", "scripts",
)

fun bilderSeit(buildDir: File, seit: Long): Map<String, File> =
    buildDir.listFiles().orEmpty().filter { it.isDirectory && it.name !in keineBilder }
        .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension.lowercase() == "png" && it.lastModified() >= seit }.toList() }
        .associateBy { it.relativeTo(buildDir).invariantSeparatorsPath }

val testSourceSet = the<SourceSetContainer>()["test"]

val bilderNachher by tasks.registering(Test::class) {
    group = "verification"
    description = "Bild-Tests für den Vergleich (Teil von „vergleich“)."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    jvmArgs(tasks.named<Test>("test").get().jvmArgs.orEmpty())
    systemProperties(tasks.named<Test>("test").get().systemProperties)
    filter {
        vergleichTests.forEach { includeTestsMatching(if ('.' in it) it else "*.$it") }
        isFailOnNoMatchingTests = false
    }
    ignoreFailures = true
    outputs.upToDateWhen { false }
    val marke = layout.buildDirectory.file("vergleich-start.txt")
    doFirst { marke.get().asFile.apply { parentFile.mkdirs(); writeText(System.currentTimeMillis().toString()) } }
}

tasks.register("vergleich") {
    group = "verification"
    description = "Vorher/Nachher-Bilder: Bild-Tests hier und auf dem Stand von -Pbasis, Vergleichsseite in build/vergleich."
    dependsOn(bilderNachher)
    val desktopDir = projectDir
    val repo = projectDir.parentFile
    val buildDir = layout.buildDirectory.get().asFile
    doLast {
        System.setProperty("java.awt.headless", "true")
        val seitNachher = File(buildDir, "vergleich-start.txt").readText().trim().toLong()
        val nachher = bilderSeit(buildDir, seitNachher)

        fun run(dir: File, vararg cmd: String, log: File? = null): Int {
            val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
            if (log != null) pb.redirectOutput(log) else pb.inheritIO()
            return pb.start().waitFor()
        }

        // "vorher": worktree with the state of the basis.
        val wt = File(repo, "build/vergleich-vorher")
        run(repo, "git", "fetch", "--quiet", "origin")
        if (!File(wt, ".git").exists()) {
            run(repo, "git", "worktree", "prune")
            check(run(repo, "git", "worktree", "add", "--force", "--detach", wt.path, vergleichBasis) == 0) { "git worktree add $vergleichBasis fehlgeschlagen" }
        } else {
            run(wt, "git", "reset", "--quiet", "--hard")
            run(wt, "git", "clean", "-fdq", "desktop/src/test")
            check(run(wt, "git", "checkout", "--quiet", "--detach", vergleichBasis) == 0) { "git checkout $vergleichBasis fehlgeschlagen" }
        }
        val basisName = ProcessBuilder("git", "log", "-1", "--format=%h %s", vergleichBasis).directory(repo).start()
            .inputStream.bufferedReader().readText().trim()
        // Speech libraries are downloads; take them from here instead of fetching them again.
        File(repo, "build/speech-downloads").takeIf { it.isDirectory }?.copyRecursively(File(wt, "build/speech-downloads"), overwrite = true)

        val gradlew = File(wt, if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew").path
        val testArgs = vergleichTests.flatMap { listOf("--tests", if ('.' in it) it else "*.$it") }
        val wtBuild = File(wt, "desktop/build")
        val log = File(buildDir, "vergleich/vorher.log").apply { parentFile.mkdirs() }

        fun vorherLauf(): Long {
            val seit = System.currentTimeMillis()
            run(wt, gradlew, "-p", "desktop", "--no-daemon", "--continue", "test", "--rerun", *testArgs.toTypedArray(), log = log)
            return seit
        }
        // First with the tests of the branch, so new scenes exist "vorher" as well.
        val wtTests = File(wt, "desktop/src/test")
        wtTests.deleteRecursively()
        File(desktopDir, "src/test").copyRecursively(wtTests, overwrite = true)
        var seitVorher = vorherLauf()
        var testsVon = "Tests des Zweigs"
        fun kompiliertNicht() = log.readText().contains("Task :compileTestKotlin FAILED")
        if (kompiliertNicht()) {
            // Second try: the tests of the basis, only the chosen picture tests from the branch.
            run(wt, "git", "checkout", "--quiet", "--", "desktop/src/test")
            run(wt, "git", "clean", "-fdq", "desktop/src/test")
            val muster = vergleichTests.map { t -> Regex(t.substringAfterLast('.').split('*').joinToString(".*") { Regex.escape(it) }) }
            val eigene = File(desktopDir, "src/test/kotlin").walkTopDown().filter { f -> f.isFile && muster.any { it.matches(f.nameWithoutExtension) } }.toList()
            eigene.forEach { it.copyTo(File(wtTests, "kotlin/" + it.relativeTo(File(desktopDir, "src/test/kotlin")).path), overwrite = true) }
            logger.lifecycle("Die Tests des Zweigs kompilieren mit dem alten Code nicht – „vorher“ mit den Tests von $vergleichBasis und den ${eigene.size} Bild-Tests des Zweigs.")
            seitVorher = vorherLauf()
            testsVon = "Tests von $vergleichBasis mit den Bild-Tests des Zweigs"
            if (kompiliertNicht()) {
                logger.lifecycle("Auch das kompiliert nicht – „vorher“ ganz mit den Tests von $vergleichBasis.")
                run(wt, "git", "checkout", "--quiet", "--", "desktop/src/test")
                run(wt, "git", "clean", "-fdq", "desktop/src/test")
                seitVorher = vorherLauf()
                testsVon = "Tests von $vergleichBasis (die des Zweigs kompilieren mit dem alten Code nicht)"
            }
        }
        val vorher = bilderSeit(wtBuild, seitVorher)

        // Compare.
        val out = File(buildDir, "vergleich").apply { File(this, "bilder").deleteRecursively() }
        data class Paar(val name: String, val alt: File?, val neu: File?, val anteil: Double, val markiert: BufferedImage?, val hinweis: String)

        fun vergleiche(name: String, a: File, b: File): Paar {
            val x = ImageIO.read(a); val y = ImageIO.read(b)
            if (x.width != y.width || x.height != y.height) {
                return Paar(name, a, b, 100.0, null, "Größe ${x.width}×${x.height} → ${y.width}×${y.height}")
            }
            val zelle = 24
            val cols = (x.width + zelle - 1) / zelle; val rows = (x.height + zelle - 1) / zelle
            val zellen = BooleanArray(cols * rows)
            var anders = 0L
            for (j in 0 until x.height) for (i in 0 until x.width) {
                val p = x.getRGB(i, j); val q = y.getRGB(i, j)
                if (p == q) continue
                var d = 0
                for (s in intArrayOf(0, 8, 16, 24)) d = maxOf(d, kotlin.math.abs(((p ushr s) and 255) - ((q ushr s) and 255)))
                if (d > vergleichSchwelle) { anders++; zellen[(j / zelle) * cols + i / zelle] = true }
            }
            if (anders == 0L) return Paar(name, a, b, 0.0, null, "")
            // The new picture, faded, with the changed cells outlined in red (neighbouring cells as one box).
            val m = BufferedImage(y.width, y.height, BufferedImage.TYPE_INT_RGB)
            val g = m.createGraphics()
            g.drawImage(y, 0, 0, null)
            g.color = Color(255, 255, 255, 150); g.fillRect(0, 0, y.width, y.height)
            val besucht = BooleanArray(zellen.size)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (start in zellen.indices) {
                if (!zellen[start] || besucht[start]) continue
                var x0 = cols; var y0 = rows; var x1 = 0; var y1 = 0
                val stapel = ArrayDeque(listOf(start)); besucht[start] = true
                while (stapel.isNotEmpty()) {
                    val k = stapel.removeLast(); val cx = k % cols; val cy = k / cols
                    x0 = minOf(x0, cx); y0 = minOf(y0, cy); x1 = maxOf(x1, cx); y1 = maxOf(y1, cy)
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = cx + dx; val ny = cy + dy
                        if (nx in 0 until cols && ny in 0 until rows) {
                            val n = ny * cols + nx
                            if (zellen[n] && !besucht[n]) { besucht[n] = true; stapel.addLast(n) }
                        }
                    }
                }
                val rx = x0 * zelle; val ry = y0 * zelle
                val rw = minOf(y.width, (x1 + 1) * zelle) - rx; val rh = minOf(y.height, (y1 + 1) * zelle) - ry
                g.drawImage(y.getSubimage(rx, ry, rw, rh), rx, ry, null)
                g.color = Color(220, 30, 30); g.stroke = BasicStroke(maxOf(2f, y.width / 500f))
                g.drawRect(rx, ry, rw - 1, rh - 1)
            }
            g.dispose()
            return Paar(name, a, b, 100.0 * anders / (x.width.toLong() * x.height), m, "")
        }

        val paare = (vorher.keys + nachher.keys).sorted().map { name ->
            val a = vorher[name]; val b = nachher[name]
            when {
                a == null -> Paar(name, null, b, 100.0, null, "neu")
                b == null -> Paar(name, a, null, 100.0, null, "weggefallen")
                else -> vergleiche(name, a, b)
            }
        }

        fun kopie(f: File?, seite: String, name: String): String? = f?.let {
            val ziel = File(out, "bilder/$seite/$name"); ziel.parentFile.mkdirs(); it.copyTo(ziel, true)
            "bilder/$seite/$name"
        }
        /** Picture for the page: at most 1400 px wide, embedded, so the page is one file. */
        fun eingebettet(img: BufferedImage): String {
            val w = minOf(img.width, 1400)
            val s = if (w == img.width) img else BufferedImage(w, img.height * w / img.width, BufferedImage.TYPE_INT_ARGB).also {
                val g = it.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, 0, 0, it.width, it.height, null); g.dispose()
            }
            val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(s, "png", it) }.toByteArray()
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun figur(titel: String, img: BufferedImage?, voll: String?) =
            if (img == null) "<figure class=\"leer\"><figcaption>$titel</figcaption><div>—</div></figure>"
            else "<figure><figcaption>$titel${voll?.let { " · <a href=\"$it\">volle Größe</a>" } ?: ""}</figcaption><img loading=\"lazy\" src=\"${eingebettet(img)}\" alt=\"${esc(titel)}\"></figure>"

        val geaendert = paare.filter { it.anteil > 0 }.sortedByDescending { if (it.hinweis.isEmpty()) it.anteil else 101.0 }
        val gleich = paare.filter { it.anteil == 0.0 }
        val html = StringBuilder()
        html.append("""<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Vorher und Nachher</title><style>
:root{--bg:#f6f6f4;--fg:#1d1d1b;--mute:#6b6b66;--card:#fff;--line:#deded8;--rot:#c62828}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#17181a;--fg:#ececea;--mute:#a3a39e;--card:#212226;--line:#34353a;--rot:#ef5350}}
:root[data-theme=dark]{--bg:#17181a;--fg:#ececea;--mute:#a3a39e;--card:#212226;--line:#34353a;--rot:#ef5350}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif}
main{max-width:1500px;margin:0 auto;padding:24px 16px 64px}h1{font-size:24px;margin:0 0 4px}h2{font-size:17px;margin:0}
.mute{color:var(--mute)}section{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin:18px 0}
.kopf{display:flex;gap:12px;align-items:baseline;flex-wrap:wrap;margin-bottom:10px}.wert{font-variant-numeric:tabular-nums;color:var(--rot);font-weight:600}
.reihe{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px}figure{margin:0}figcaption{font-size:13px;color:var(--mute);margin-bottom:4px}
figure img{width:100%;height:auto;border:1px solid var(--line);border-radius:6px;background:#fff}figure.leer div{padding:40px;text-align:center;color:var(--mute);border:1px dashed var(--line);border-radius:6px}
a{color:inherit}details{margin-top:18px}ul{columns:2;font-size:13px}@media (max-width:700px){ul{columns:1}}
</style></head><body><main>""")
        html.append("<h1>Vorher und Nachher</h1><p class=\"mute\">Vorher: ${esc(basisName)} · Nachher: dieser Zweig mit allen Änderungen · ")
        html.append("${paare.size} Bilder aus ${esc(vergleichTests.joinToString())} · ${geaendert.size} geändert, ${gleich.size} gleich · vorher mit den ${esc(testsVon)}.</p>")
        for (p in geaendert) {
            val alt = p.alt?.let { ImageIO.read(it) }; val neu = p.neu?.let { ImageIO.read(it) }
            val wert = when {
                p.hinweis.isNotEmpty() -> esc(p.hinweis)
                else -> String.format(java.util.Locale.GERMANY, "%.2f %% der Bildpunkte geändert", p.anteil)
            }
            html.append("<section><div class=\"kopf\"><h2>${esc(p.name)}</h2><span class=\"wert\">$wert</span></div><div class=\"reihe\">")
            html.append(figur("Vorher", alt, kopie(p.alt, "vorher", p.name)))
            html.append(figur("Nachher", neu, kopie(p.neu, "nachher", p.name)))
            if (p.markiert != null) {
                val f = File(out, "bilder/markiert/${p.name}").apply { parentFile.mkdirs() }
                ImageIO.write(p.markiert, "png", f)
                html.append(figur("Geänderte Stellen", p.markiert, "bilder/markiert/${p.name}"))
            }
            html.append("</div></section>")
        }
        if (gleich.isNotEmpty()) {
            html.append("<details><summary>${gleich.size} Bilder unverändert</summary><ul>")
            gleich.forEach { html.append("<li>${esc(it.name)}</li>") }
            html.append("</ul></details>")
        }
        if (paare.isEmpty()) html.append("<p>Keine Bilder gefunden – schreiben die gewählten Tests Bilder nach desktop/build?</p>")
        html.append("</main></body></html>")
        File(out, "index.html").writeText(html.toString())
        logger.lifecycle("Vergleich: ${paare.size} Bilder, ${geaendert.size} geändert, ${gleich.size} gleich → ${File(out, "index.html")}")
    }
}
