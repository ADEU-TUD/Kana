package io.github.thepytales.llmhub.video

import io.github.thepytales.llmhub.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10

/**
 * Mehrkamera-Schnitt nach Sprecher (AP-129 d): an interview filmed with two or three cameras – one on each person, on
 * request a wide shot – is cut by who speaks. The cameras are synchronised over their sound ([AudioSync], as a lapel
 * microphone in AP-53); each camera's sound says how loud it is near its person (in dB above its own quiet level, so
 * different gains do not matter); a calm cut follows: a new shot only when the other person has spoken for a second and
 * the shot has stood for two, the cut placed where the new speaker began; when both speak or nobody does for long, the
 * wide shot. The sound stays the main camera's (and its lapel microphones), linked to every piece – later cuts stay in
 * sync. Everything as ordinary clips of V1: each piece can be trimmed, moved or swapped by hand afterwards.
 */
object MultiCam {
    /** A camera: its file, how far its sound is behind the main camera's ([offset] s: main t ↔ this t + offset), wide shot or not. */
    data class Angle(val path: String, val offset: Double = 0.0, val wide: Boolean = false, val length: Double = Double.MAX_VALUE) {
        fun covers(t: Double) = t + offset >= 0.0 && t + offset <= length - 0.05
    }

    /** A shot: [from]..[to] in the source time of the main camera, from camera [angle] (index into the angles). */
    data class Shot(val from: Double, val to: Double, val angle: Int)

    /** Seconds per decision. */
    const val STEP = 0.25

    /** Above its own quiet level a camera's sound counts as speech (dB), and one is clearly louder than the others by (dB). */
    const val SPEAKS_DB = 8.0
    const val CLEAR_DB = 3.0

    /** Level in dB per [STEP] of an envelope ([AudioSync.RATE] values per second, peaks 0..1). */
    internal fun levels(env: FloatArray): DoubleArray {
        val per = (STEP * AudioSync.RATE).toInt()
        val n = env.size / per
        return DoubleArray(n) { w ->
            var sum = 0.0
            for (k in w * per until (w + 1) * per) sum += env[k]
            20 * log10(sum / per + 1e-5)
        }
    }

    /** Levels above the quiet level of the same camera (its 20th percentile): comparable between cameras. */
    internal fun activity(env: FloatArray): DoubleArray {
        val l = levels(env)
        if (l.isEmpty()) return l
        val floor = l.sorted()[(l.size * 0.2).toInt().coerceIn(0, l.size - 1)]
        return DoubleArray(l.size) { l[it] - floor }
    }

    /**
     * The shots for main-camera time [from]..[to] from the activity of each camera ([act], in the order of [angles];
     * index 0 is the main camera). [minShot]: no shot shorter; [hold]: how long someone must speak before the cut;
     * [wideAfter]: silence or both talking this long → wide shot (if there is one).
     */
    fun shots(angles: List<Angle>, act: List<DoubleArray>, from: Double, to: Double, minShot: Double = 2.0, hold: Double = 1.0, wideAfter: Double = 3.0): List<Shot> {
        require(angles.isNotEmpty() && angles.size == act.size)
        val wide = angles.indexOfFirst { it.wide }.takeIf { it >= 0 }
        val people = angles.indices.filter { !angles[it].wide }
        fun actAt(i: Int, t: Double): Double {
            val w = ((t + angles[i].offset) / STEP).toInt()
            return act[i].getOrElse(w) { Double.NEGATIVE_INFINITY }
        }
        // What each step wants: the person speaking clearly, WIDE for both/none, null for no change.
        val steps = generateSequence(from) { it + STEP }.takeWhile { it < to - 1e-9 }.toList()
        val want = steps.map { t ->
            val cand = people.filter { angles[it].covers(t) }.map { it to actAt(it, t) }.sortedByDescending { it.second }
            val best = cand.getOrNull(0)
            val second = cand.getOrNull(1)?.second ?: Double.NEGATIVE_INFINITY
            when {
                best == null -> -1
                best.second >= SPEAKS_DB && best.second - second >= CLEAR_DB -> best.first
                best.second >= SPEAKS_DB -> -2 // both
                else -> -3 // nobody
            }
        }
        val first = wide?.takeIf { angles[it].covers(from) } ?: want.firstOrNull { it >= 0 } ?: 0
        val out = mutableListOf<Shot>()
        var cur = first
        var start = from
        // The candidate for the next shot, since when it is wanted, and how many steps it was heard clearly – pauses
        // between words do not reset it, a short „mhm“ followed by the current speaker does.
        var cand = cur
        var candSince = from
        var votes = 0
        var quiet = 0
        steps.forEachIndexed { k, t ->
            val w = want[k]
            val voice = when {
                w >= 0 -> w
                w == -2 && wide != null && angles[wide].covers(t) -> wide
                else -> null
            }
            quiet = if (w == -3 || w == -1) quiet + 1 else 0
            if (voice != null) {
                when (voice) {
                    cur -> { cand = cur; votes = 0 }
                    cand -> votes++
                    else -> { cand = voice; candSince = t; votes = 1 }
                }
            } else if (wide != null && cur != wide && quiet * STEP >= wideAfter - 1e-9 && angles[wide].covers(t) && cand != wide) {
                // Nobody speaks for a while: the wide shot, from where the silence began.
                cand = wide; candSince = t - (quiet - 1) * STEP; votes = Int.MAX_VALUE / 2
            }
            val ready = cand != cur && votes * STEP >= hold / 2 - 1e-9 && t - candSince >= hold - 1e-9 && t - start >= minShot - 1e-9 && angles[cand].covers(t)
            if (ready) {
                // The cut where the new speaker began (back-dated), but never into the last shot's minimum.
                val cut = maxOf(candSince, start + minShot)
                out += Shot(start, cut, cur)
                cur = cand; start = cut; votes = 0
            }
        }
        out += Shot(start, to, cur)
        // A camera must cover all of its shot; where it does not, the main camera takes over.
        return out.flatMap { s -> if (s.angle == 0 || (angles[s.angle].covers(s.from) && angles[s.angle].covers(s.to - 0.05))) listOf(s) else listOf(s.copy(angle = 0)) }
            .fold(mutableListOf()) { acc, s -> if (acc.isNotEmpty() && acc.last().angle == s.angle) acc[acc.lastIndex] = acc.last().copy(to = s.to) else acc += s; acc }
    }

    /**
     * V1 clip [clipId] (the main camera) replaced by [shots]: each a clip of its camera at the synchronised time, silent;
     * the main camera's sound and every sound linked to the clip (lapel microphones) linked to each piece at the right
     * offset. Effects and position of the clip go with every piece (a pixelated face of another camera needs its own
     * search). The other cameras go into the media pool.
     */
    fun apply(p: VideoProject, clipId: String, angles: List<Angle>, shots: List<Shot>): VideoProject {
        val i = p.clips.indexOfFirst { it.id == clipId }.takeIf { it >= 0 } ?: return p
        val c = p.clips[i]
        if (shots.isEmpty()) return p
        val linkedBefore = p.audio.filter { it.linkedTo == c.id }
        // The main camera's own sound: laid out unless it is silent or already on a track.
        val ownSound = if (!c.muted && linkedBefore.none { it.path == c.path }) listOf(AudioClip(path = c.path, startSec = 0.0, outSec = 0.0, volume = c.volume, fadeSec = 0.0, track = VideoEdit.freeAudioTrack(p, p.starts()[i], p.starts()[i] + c.length), linkedTo = c.id, channel = c.channel)) else emptyList()
        val sounds = linkedBefore + ownSound
        val pieces = mutableListOf<VideoClip>()
        val newSounds = mutableListOf<AudioClip>()
        shots.forEachIndexed { k, s ->
            val a = angles[s.angle]
            val piece = c.copy(
                id = newVideoId(), path = a.path, inSec = s.from + a.offset, outSec = s.to + a.offset, muted = true, channel = "",
                transition = if (k == 0) c.transition else "none", fx = if (s.angle == 0) c.fx else c.fx.copy(regions = c.fx.regions.filter { !it.face }, zoom = emptyList(), fill = c.fx.fill),
            )
            pieces += piece
            // Source time of the sound = clip's In + offset; the main camera's time is the piece's minus the camera's offset.
            sounds.forEach { snd -> newSounds += snd.copy(id = newVideoId(), linkedTo = piece.id, linkOffset = snd.linkOffset - a.offset) }
        }
        val audioTracks = maxOf(p.audioTracks, newSounds.maxOfOrNull { it.track } ?: 1)
        val q = p.copy(
            clips = p.clips.toMutableList().apply { removeAt(i); addAll(i, pieces) },
            audio = p.audio.filter { it.linkedTo != c.id } + newSounds,
            audioTracks = audioTracks,
        )
        return VideoEdit.addToPool(q, angles.map { it.path }).linked()
    }

    /** How the cut went: shots and share of each camera (for the message after the cut). */
    fun summary(angles: List<Angle>, shots: List<Shot>): String {
        val total = shots.sumOf { it.to - it.from }.coerceAtLeast(1e-6)
        return tr("{0} Einstellungen", shots.size) + " · " + angles.indices.joinToString(" · ") { i ->
            val share = shots.filter { it.angle == i }.sumOf { it.to - it.from } / total
            "${File(angles[i].path).nameWithoutExtension}: ${Math.round(share * 100)} %"
        }
    }

    /**
     * Synchronises [others] (camera files; [wide]: the wide shot among them) with V1 clip [clipId] over the sound and
     * cuts by speaker. Blocking work on IO; throws with a clear message when a camera does not match.
     */
    suspend fun run(p: VideoProject, clipId: String, others: List<File>, wide: Set<String> = emptySet()): Pair<VideoProject, String> = withContext(Dispatchers.IO) {
        val c = p.clips.firstOrNull { it.id == clipId } ?: throw IllegalStateException(tr("Bitte den Clip der Hauptkamera wählen."))
        if (c.fx.freeze || c.fx.reverse || kotlin.math.abs(c.speed - 1.0) > 1e-6) throw IllegalStateException(tr("Der Clip läuft schneller, langsamer, rückwärts oder steht – Mehrkamera geht nur mit normalem Tempo."))
        val main = File(c.path)
        val mainEnv = AudioSync.envelope(main) ?: throw IllegalStateException(tr("Die Hauptkamera hat keinen Ton – ohne Ton lässt sich nicht erkennen, wer spricht."))
        val angles = mutableListOf(Angle(c.path, 0.0, wide = c.path in wide))
        val envs = mutableListOf(mainEnv)
        for (f in others) {
            val env = AudioSync.envelope(f) ?: throw IllegalStateException(tr("„{0}“ hat keinen Ton – so lässt sich die Kamera nicht synchronisieren.", f.name))
            val off = AudioSync.offset(mainEnv, env) ?: throw IllegalStateException(tr("„{0}“ passt nicht zur Hauptkamera – ist es dasselbe Gespräch?", f.name))
            angles += Angle(f.absolutePath, off, wide = f.absolutePath in wide, length = Media.probe(f)?.duration ?: Double.MAX_VALUE)
            envs += env
        }
        if (angles.none { !it.wide }) throw IllegalStateException(tr("Mindestens eine Kamera muss auf eine Person gerichtet sein."))
        val act = envs.map { activity(it) }
        val shots = shots(angles, act, c.inSec, c.outSec)
        apply(p, clipId, angles, shots) to summary(angles, shots)
    }
}
