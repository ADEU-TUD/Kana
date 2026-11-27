package io.github.thepytales.llmhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.thepytales.llmhub.AppContainer
import io.github.thepytales.llmhub.data.RecentWork
import java.io.File

/*
 * Weiterarbeiten (AP-135 b, Analyse Q-02): the last files of all editors on the start page and in *Erstellen*; a click
 * opens one where one stopped (Seite, Folie, Zelle, Zeitmarke).
 */

/** What an entry is, for the line under its title. */
internal fun workKind(kind: String): String = tr(
    when (kind) {
        RecentWork.SHEET -> "Tabelle"
        RecentWork.SLIDES -> "Präsentation"
        RecentWork.DESIGN -> "Design"
        RecentWork.VIDEO -> "Video"
        RecentWork.PDF -> "PDF"
        RecentWork.TRANSCRIPT -> "Transkript"
        else -> "Dokument"
    },
)

private fun workIcon(kind: String): ImageVector = when (kind) {
    RecentWork.SHEET -> Icons.Outlined.TableChart
    RecentWork.SLIDES -> Icons.Outlined.Slideshow
    RecentWork.DESIGN -> Icons.Outlined.Brush
    RecentWork.VIDEO -> Icons.Outlined.Movie
    RecentWork.PDF -> Icons.Outlined.PictureAsPdf
    RecentWork.TRANSCRIPT -> Icons.Outlined.GraphicEq
    else -> Icons.Outlined.Description
}

/** The app's container where the screen has none of its own (the start page); null before the app has started (tests). */
internal fun appContainer(): AppContainer? = runCatching { io.github.thepytales.llmhub.App.container }.getOrNull()

/** The entries that still exist (a deleted document drops out of the list). */
internal fun existingWork(c: AppContainer, max: Int): List<RecentWork.Entry> = RecentWork.recent(c).filter { e -> exists(c, e) }.take(max)

private fun exists(c: AppContainer, e: RecentWork.Entry): Boolean = when (e.kind) {
    RecentWork.DOC, RecentWork.SHEET, RecentWork.SLIDES -> c.docs.get(e.ref) != null
    RecentWork.DESIGN -> c.designs.get(e.ref) != null
    RecentWork.PDF -> File(e.ref).isFile
    RecentWork.TRANSCRIPT -> c.transcription.transcripts.value.any { it.id == e.ref }
    else -> true
}

/**
 * „Weiterarbeiten“: the last [max] files of all editors with where one stopped. Nothing is shown while there is none.
 * [title] heads the list (on the start page beside „Zuletzt“ and „Favoriten“).
 */
@Composable
fun ContinueWorkList(c: AppContainer, max: Int = 5, title: Boolean = true, modifier: Modifier = Modifier) {
    val version by RecentWork.version.collectAsState()
    val entries = remember(version) { existingWork(c, max) }
    if (entries.isEmpty()) return
    Column(modifier.widthIn(min = 220.dp, max = 360.dp).testTag("weiterarbeiten")) {
        if (title) GroupLabel(tr("Weiterarbeiten"))
        entries.forEach { e ->
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { RecentWork.openRequest.value = e }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(workIcon(e.kind), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Column {
                    Text(e.title.ifBlank { workKind(e.kind) }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(workKind(e.kind), e.place).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Opens [e] where one stopped (the window's root calls it for [RecentWork.openRequest]): the place waits for the
 * editor ([RecentWork.resume]); [navigate] goes to a route, [openVideo] opens a video project (PC). A file that is gone
 * leaves the list with a note.
 */
fun openWork(c: AppContainer, e: RecentWork.Entry, navigate: (String) -> Unit, openVideo: ((String) -> Unit)? = null) {
    if (!exists(c, e)) {
        RecentWork.forget(c, e.key)
        Notices.info(tr("„{0}“ gibt es nicht mehr.", e.title))
        return
    }
    RecentWork.resume(e)
    when (e.kind) {
        RecentWork.DOC, RecentWork.SHEET, RecentWork.SLIDES -> navigate("doc/${e.ref}")
        RecentWork.DESIGN -> navigate("design/${e.ref}")
        RecentWork.TRANSCRIPT -> navigate("transcript/${e.ref}")
        RecentWork.PDF -> PdfHandoff.open(e.ref, (RecentWork.number(RecentWork.take(RecentWork.PDF, e.ref), "page") ?: 0) + 1, e.title)
        RecentWork.VIDEO -> openVideo?.invoke(e.ref) ?: Notices.info(tr("Videos gibt es nur am PC."))
    }
}
