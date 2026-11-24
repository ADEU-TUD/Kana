package io.github.thepytales.llmhub

/**
 * Name and slogan of the app (AP-33). The app is called after Kana, its assistant (until 1.0.x „Lora“, AP-155). Technical names stay as they were
 * ("ADEU LLM.exe", the data folders %APPDATA%\ADEU LLM and %LOCALAPPDATA%\ADEU LLM, the names of the release files), so
 * installed versions keep finding their data and updates keep working.
 */
object Brand {
    const val NAME = "Kana"

    /** Earlier name of the app and figure (AP-155): still understood when spoken to, and in files written before. */
    const val OLD_NAME = "Lora"

    /** Both names, for recognizing own files and comments written before the renaming. */
    val NAMES = listOf(NAME, OLD_NAME)
    const val SLOGAN = "Der freundliche Helfer für alle Wünsche in Forschung, Lehre und Verwaltung an der TU Dresden"
}
