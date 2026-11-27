package io.github.thepytales.llmhub

import io.github.thepytales.llmhub.research.CslStyle
import io.github.thepytales.llmhub.research.Person
import io.github.thepytales.llmhub.research.RefType
import io.github.thepytales.llmhub.research.Reference
import kotlin.test.Test

class CslTest {
    private fun style(name: String) = CslStyle.parse(javaClass.getResource("/csl/$name.csl")!!.readText(), name)

    val article = Reference(
        id = "a", type = RefType.ARTICLE,
        authors = listOf(Person("Praetorius", "Anna-Katharina"), Person("Klieme", "Eckhard"), Person("Herbert", "Benjamin"), Person("Pinger", "Petra")),
        year = "2018", title = "Generic dimensions of teaching quality: The German framework of Three Basic Dimensions",
        container = "ZDM", volume = "50", issue = "3", pages = "407-426", doi = "10.1007/s11858-018-0918-4",
    )
    val book = Reference(id = "b", type = RefType.BOOK, authors = listOf(Person("Helmke", "Andreas")), year = "2009",
        title = "Unterrichtsqualität und Lehrerprofessionalität", edition = "2", publisher = "Klett-Kallmeyer", place = "Seelze")
    val chapter = Reference(id = "c", type = RefType.CHAPTER, authors = listOf(Person("Terhart", "Ewald")), year = "2011", title = "Lehrerberuf und Professionalität",
        editors = listOf(Person("Helsper", "Werner"), Person("Tippelt", "Rudolf")), container = "Pädagogische Professionalität",
        pages = "202–224", publisher = "Beltz", place = "Weinheim")
    val web = Reference(id = "w", type = RefType.WEB, authors = listOf(Person("Kultusministerkonferenz", literal = true)), year = "2022", title = "Standards für die Lehrerbildung", url = "https://www.kmk.org/x.pdf", accessed = "2026-03-03")
    val edited = Reference(id = "e", type = RefType.EDITED_BOOK, editors = listOf(Person("Helsper", "Werner"), Person("Tippelt", "Rudolf")), year = "2011", title = "Pädagogische Professionalität", publisher = "Beltz", place = "Weinheim")

    @Test
    fun explore() {
        for (n in listOf("ieee", "harvard-cite-them-right", "din-1505-2", "apa", "springer-basic-author-date", "nature", "deutsche-gesellschaft-fur-psychologie", "elife")) {
            val s = style(n)
            println("== $n (${s.format}, ${s.defaultLocale})")
            s.bibliography(listOf(article, book, chapter, web, edited)).forEach { println("  " + it.second) }
            println("  cite: " + s.cite(listOf(article to 1)) + "  " + s.cite(listOf(chapter to 3)) + " " + s.cite(listOf(edited to 2)))
        }
    }
}
