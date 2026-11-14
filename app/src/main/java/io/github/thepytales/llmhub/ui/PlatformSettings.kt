package io.github.thepytales.llmhub.ui

import androidx.compose.runtime.Composable
import io.github.thepytales.llmhub.AppContainer

/** Android has no settings pages of its own (Windows: "windows"); false = not handled here. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PlatformSettingsSection(c: AppContainer, section: String?): Boolean = false

fun platformSettingsItem(): SettingsItem? = null

/** Einstellungen › Lora › Server-Terminal (AP-131 e) is desktop-only – Android has no Server-Terminal. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PlatformLoraSettings(c: AppContainer) = Unit

/** Reloadable extensions (AP-25) exist on Windows only. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ExtensionsSettings(c: AppContainer) = Unit

/** Einstellungen › Synchronisation (AP-12) is Windows-only for now (Android: AP-28). */
fun syncSettingsItem(): SettingsItem? = null

val KEY_STORAGE_HINT: String get() = tr("Er wird verschlüsselt auf dem Gerät gespeichert.")
val DEVICE_VOICE: String get() = tr("Gerätestimme")
val DEVICE_VOICE_TITLE: String get() = tr("Gerätestimme")
val DEVICE_VOICE_DESCRIPTION: String get() = tr("Android-Sprachausgabe, nur Offline-Stimmen – nichts verlässt das Handy.")
val NO_DEVICE_VOICES: String get() = tr("Keine Offline-Stimmen gefunden. Installiere in den Android-Einstellungen (Sprachausgabe) eine deutsche Stimme.")
