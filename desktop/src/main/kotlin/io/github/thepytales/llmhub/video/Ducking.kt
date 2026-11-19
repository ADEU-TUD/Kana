package io.github.thepytales.llmhub.video

import java.util.Locale
import kotlin.math.pow

/**
 * Musik automatisch leiser, wenn gesprochen wird (AP-129, „Ducking“ wie in Clipchamp, CapCut und DaVinci): the speech
 * (the sound of V1, of the pictures above and of sound linked to a clip) steers a compressor on the music. A compressor
 * alone pulls the music down by as much as the speech is loud; mixed with a copy of the music at the wanted level it
 * goes down by exactly [VideoProject.sound].duckDb and no further, and stays as it was where nobody speaks.
 */
object Ducking {
    /** How much quieter the music gets in the editor's choice. */
    val LEVELS = listOf(8f to io.github.thepytales.llmhub.ui.trLater("Leicht (−8 dB)"), 12f to io.github.thepytales.llmhub.ui.trLater("Mittel (−12 dB)"), 18f to io.github.thepytales.llmhub.ui.trLater("Stark (−18 dB)"))

    private fun f(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    /** Level (linear) above which the steering sound counts as speech: −40 dBFS. */
    private const val GATE = "0.01"

    /** Share of the music that is left while someone speaks: −[db] dB. */
    fun floor(db: Float): Double = 10.0.pow(-db.coerceIn(3f, 40f) / 20.0)

    /**
     * Lines of the graph: the speech [speech] (the label of V1's sound, without brackets) and the other speech sources
     * [extra] are mixed into one stream, which goes out and steers the compressor of the [music] (mixed first when there
     * are several). Returns the lines and the label of the mixed sound.
     */
    fun lines(speech: String, extra: List<String>, music: List<String>, db: Float): Pair<List<String>, String> {
        val out = mutableListOf<String>()
        var sp = speech
        if (extra.isNotEmpty()) {
            out += "[$speech]" + extra.joinToString("") { "[$it]" } + "amix=inputs=${extra.size + 1}:duration=first:dropout_transition=0:normalize=0[dsp]"
            sp = "dsp"
        }
        var mu = music.first()
        if (music.size > 1) {
            out += music.joinToString("") { "[$it]" } + "amix=inputs=${music.size}:duration=longest:dropout_transition=0:normalize=0[dmu]"
            mu = "dmu"
        }
        val keep = floor(db)
        out += "[$sp]asplit=2[dso][dsc]"
        out += "[$mu]asplit=2[dm1][dm2]"
        // The steering signal: the speech band through a gate (room noise below −40 dBFS counts as silence), then 40 dB
        // louder – so quiet speech pulls the compressed copy to nothing as well as loud speech does (a compressor alone
        // ducked a quiet speaker by only half). Attack 20 ms, release 400 ms like the auto-ducking of the big editors
        // (no pumping between words).
        out += "[dsc]highpass=f=120,lowpass=f=4000,agate=threshold=$GATE:ratio=9000:range=0.001:attack=10:release=300,volume=40dB[dsg]"
        out += "[dm1][dsg]sidechaincompress=threshold=0.01:ratio=20:attack=20:release=400:makeup=1:knee=2,volume=${f(1 - keep)}[dmc]"
        out += "[dm2]volume=${f(keep)}[dmf]"
        out += "[dmc][dmf]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0[dmd]"
        out += "[dso][dmd]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[amx]"
        return out to "amx"
    }
}
