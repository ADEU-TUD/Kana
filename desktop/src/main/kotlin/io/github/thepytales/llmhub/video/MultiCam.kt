package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.transcribe.Transcript
import io.github.thepytales.llmhub.ui.tr
import java.io.File
import kotlin.math.abs

/**
 * Mehrkamera-Schnitt nach Sprecher (AP-129, für Interviews und Gespräche wie die Multicam-Schnitte in DaVinci): two
 * cameras record the same conversation; the app synchronises them over the sound (AP-53) and cuts to the camera of the
 * person who speaks – from the speakers of the transcript, or without one from which microphone is louder. The sound
 * of the main camera runs through without a break: shots of the other camera are muted and get the main camera's
 * sound linked to them, so it moves and is trimmed with each shot.
 */
object MultiCam {
    /** A shot: [from]..[to] in source seconds of the main camera, shown by [camera] (0 = main, 1 = second). */
    data class Shot(val from: Double, val to: Double, val camera: Int) {
        val length: Double get() = to - from
    }

    /**
     * Merges shots of the same camera, then lets short ones go into the shot before (no flicker between cameras) –
     * in this order, so a speaker made of many short windows (loudness) still gets one long shot.
     */
    internal fun tidy(shots: List<Shot>, minShot: Double): List<Shot> {
        fun merge(list: List<Shot>): MutableList<Shot> {
            val out = mutableListOf<Shot>()
            for (s in list) { val l = out.lastOrNull(); if (l != null && l.camera == s.camera) out[out.lastIndex] = l.copy(to = s.to) else out += s }
            return out
        }
        val merged = merge(shots.sortedBy { it.from }.filter { it.length > 1e-6 })
        val out = mutableListOf<Shot>()
        for (s in merged) {
            val last = out.lastOrNull()
            if (last != null && s.length < minShot) out[out.lastIndex] = last.copy(to = s.to) else out += s
        }
        // A short first shot goes into the second.
        if (out.size > 1 && out[0].length < minShot) { out[1] = out[1].copy(from = out[0].from); out.removeAt(0) }
        return merge(out)
    }

    /**
     * Shots from the speakers of [t] within [from]..[to] (source time of the main camera): each speaker is shown by
     * [cameraOf] (speaker index → camera; unknown speakers by the main camera). A cut lies [lead] seconds before the
     * new speaker begins; silence keeps the last camera.
     */
    fun fromSpeakers(t: Transcript, from: Double, to: Double, cameraOf: Map<Int, Int>, minShot: Double = 2.0, lead: Double = 0.15): List<Shot> {
        val segs = t.segments.filter { it.end > from && it.start < to && it.text.isNotBlank() }.sortedBy { it.start }
        if (segs.isEmpty()) return listOf(Shot(from, to, 0))
        val raw = mutableListOf<Shot>()
        var cam = cameraOf[segs.first().speaker] ?: 0
        var start = from
        for (s in segs) {
            val c = cameraOf[s.speaker] ?: 0
            if (c != cam) {
                val cut = (s.start - lead).coerceIn(start, to)
                raw += Shot(start, cut, cam)
                start = cut; cam = c
            }
        }
        raw += Shot(start, to, cam)
        return tidy(raw, minShot)
    }

    /**
     * Shots from the loudness of the two cameras' microphones ([a], [b]: envelopes of [AudioSync], [rate] values per
     * second; [offset]: the second camera's time = main time + offset): the camera whose microphone is clearly louder
     * over [window] seconds shows its person – each camera standing by the person it films. Within 3 dB both stay.
     */
    fun fromLoudness(a: FloatArray, b: FloatArray, rate: Int, offset: Double, from: Double, to: Double, window: Double = 1.0, minShot: Double = 2.0): List<Shot> {
        fun level(e: FloatArray, t0: Double, t1: Double): Double {
            val i0 = (t0 * rate).toInt().coerceIn(0, e.size); val i1 = (t1 * rate).toInt().coerceIn(i0, e.size)
            if (i1 <= i0) return 0.0
            var s = 0.0
            for (i in i0 until i1) s += e[i] * e[i]
            return Math.sqrt(s / (i1 - i0))
        }
        val raw = mutableListOf<Shot>()
        var cam = 0
        var t = from
        while (t < to - 1e-6) {
            val e = minOf(to, t + window)
            val la = level(a, t, e)
            val lb = level(b, t + offset, e + offset)
            if (la > 1e-4 || lb > 1e-4) {
                val ratio = (lb + 1e-6) / (la + 1e-6)
                if (ratio > 1.41) cam = 1 else if (ratio < 0.71) cam = 0
            }
            raw += Shot(t, e, cam)
            t = e
        }
        return tidy(raw, minShot)
    }

    /** Why V1 clip [c] cannot be cut with a second camera, or null. */
    fun problem(c: VideoClip): String? = when {
        c.fx.freeze || c.fx.reverse || abs(c.speed - 1.0) > 1e-6 -> tr("Der Mehrkamera-Schnitt geht nur mit Clips in normalem Tempo (ohne Standbild und rückwärts).")
        Media.isStill(File(c.path)) -> tr("Der Mehrkamera-Schnitt braucht ein Video, kein Bild.")
        else -> null
    }

    /**
     * Replaces V1 clip [clipId] (the main camera) by the [shots]: shots of camera 0 are pieces of the main file with
     * their own sound, shots of camera 1 pieces of [second] (its time = main time + [offset]), muted, with the main
     * camera's sound linked on audio track [soundTrack]. Effects and picture position of the main clip go to its shots;
     * the length of the video stays the same.
     */
    fun apply(p: VideoProject, clipId: String, second: String, offset: Double, shots: List<Shot>, soundTrack: Int = freeTrack(p)): VideoProject {
        val i = p.clips.indexOfFirst { it.id == clipId }.takeIf { it >= 0 } ?: return p
        val c = p.clips[i]
        if (problem(c) != null || p.track("V1").locked) return p
        val parts = shots.mapNotNull { s ->
            val a = maxOf(s.from, c.inSec); val b = minOf(s.to, c.outSec)
            if (b - a < 0.04) null else s.copy(from = a, to = b)
        }
        if (parts.isEmpty()) return p
        val newClips = mutableListOf<VideoClip>()
        val newSound = mutableListOf<AudioClip>()
        parts.forEachIndexed { k, s ->
            val transition = if (k == 0) c.transition else "none"
            if (s.camera == 0) newClips += c.copy(id = newVideoId(), inSec = s.from, outSec = s.to, transition = transition)
            else {
                // The second camera's picture with its own regions and colour left out (they belong to the main file).
                val clip = VideoClip(path = second, inSec = s.from + offset, outSec = s.to + offset, muted = true, transition = transition, frame = c.frame, fx = ClipFx())
                newClips += clip
                if (!c.muted) newSound += AudioClip(path = c.path, startSec = 0.0, inSec = s.from, outSec = s.to, volume = c.volume, fadeSec = 0.0, track = soundTrack, linkedTo = clip.id, linkOffset = -offset)
            }
        }
        val clips = p.clips.toMutableList().apply { removeAt(i); addAll(i, newClips) }
        // Sound already linked to the main clip (a lapel microphone) stays as one piece where it lies – the shots fill
        // exactly the time of the clip, so it stays in sync; it is only no longer linked.
        val audio = p.audio.map { if (it.linkedTo == c.id) it.copy(linkedTo = null) else it } + newSound
        val q = p.copy(clips = clips, audio = audio, audioTracks = maxOf(p.audioTrackCount, soundTrack))
        return VideoEdit.addToPool(q, listOf(second)).linked()
    }

    /** The first audio track without anything on it (the main camera's sound goes there). */
    fun freeTrack(p: VideoProject): Int {
        val used = p.audio.map { it.track }.toSet()
        return (1..p.audioTrackCount + 1).first { it !in used }
    }
}
