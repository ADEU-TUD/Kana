package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Ton im Alltag (AP-129 b): a recording whose sound is on one side only (a lapel microphone into the left input of a
 * camera, a recorder with one microphone) is found by comparing the two channels; [channelFilter] then puts that side
 * on both. Ducking and „Stimmen angleichen“ live in the graph ([VideoRender.graph]).
 */
object SoundFix {
    private val sides = ConcurrentHashMap<String, String>()

    /** A side this much louder than the other (dB) holds the whole sound; the other is empty or only noise. */
    const val ONE_SIDED_DB = 20.0

    /**
     * "links" or "rechts" when the sound of [file] is on that side only (from its first [seconds] s), "" when both carry
     * it, when it is mono or silent, or without ffmpeg. Blocking, cached per file.
     */
    fun oneSided(file: File, seconds: Double = 60.0): String {
        val key = file.absolutePath + "|" + file.length() + "|" + file.lastModified()
        sides[key]?.let { return it }
        val exe = Media.ffmpeg() ?: return ""
        val result = runCatching {
            val p = ProcessBuilder(
                exe, "-nostdin", "-hide_banner", "-t", String.format(java.util.Locale.ROOT, "%.1f", seconds), "-i", file.absolutePath, "-vn",
                "-af", "astats=measure_perchannel=RMS_level:measure_overall=none", "-f", "null", "-",
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS)
            side(levels(out))
        }.onFailure { AppLog.e("Tonkanäle ${file.name}", it) }.getOrDefault("")
        sides[key] = result
        return result
    }

    /** The RMS level in dB per channel from the output of ffmpeg's astats (−inf for silence). */
    internal fun levels(astats: String): List<Double> {
        val out = mutableListOf<Double>()
        var inChannel = false
        for (line in astats.lines()) {
            if (Regex("Channel: \\d+").containsMatchIn(line)) { inChannel = true; continue }
            if (Regex("Overall").containsMatchIn(line)) inChannel = false
            if (!inChannel) continue
            val m = Regex("RMS level dB: (-?inf|-?[0-9.]+)").find(line) ?: continue
            out += m.groupValues[1].let { if (it.endsWith("inf")) Double.NEGATIVE_INFINITY else it.toDouble() }
            inChannel = false
        }
        return out
    }

    /** "links"/"rechts" for two channel levels of which one carries everything, else "". */
    internal fun side(levels: List<Double>): String {
        if (levels.size != 2) return ""
        val (l, r) = levels
        val loud = maxOf(l, r)
        if (loud < -60.0) return ""
        val quiet = minOf(l, r).let { if (it.isInfinite()) -120.0 else it }
        if (loud - quiet < ONE_SIDED_DB) return ""
        return if (l > r) "links" else "rechts"
    }

    /** Puts side [channel] of V1 clip [id] (and of its linked sound) on both sides; "" back to as recorded. */
    fun setClip(p: VideoProject, id: String, channel: String): VideoProject = p.copy(
        clips = p.clips.map { if (it.id == id) it.copy(channel = channel) else it },
        audio = p.audio.map { if (it.linkedTo == id && it.path == p.clips.firstOrNull { c -> c.id == id }?.path) it.copy(channel = channel) else it },
    )

    /** Ducking on or off for the sounds [ids] of A1 … (all unlinked ones when null – music is never linked). */
    fun duck(p: VideoProject, on: Boolean, ids: Set<String>? = null): VideoProject =
        p.copy(audio = p.audio.map { if ((ids == null && it.linkedTo == null) || (ids != null && it.id in ids)) it.copy(duck = on) else it })
}
