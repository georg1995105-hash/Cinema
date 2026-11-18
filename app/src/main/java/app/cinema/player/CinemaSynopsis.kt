package app.cinema.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Show source text immediately, then translate English synopses locally
 * once ML Kit's English/Russian model is available. First model download
 * requires Wi-Fi (~30 MB). No account, API key, proxy or new server.
 *
 * Translation is an assistive machine translation, not verified film metadata.
 */
@Composable
internal fun CinemaSynopsis(source: String) {
    val original = source.trim().take(4000)
    var translated by remember(original) { mutableStateOf<String?>(null) }
    var failed by remember(original) { mutableStateOf(false) }
    val latin = original.count { it in 'A'..'Z' || it in 'a'..'z' }
    val cyrillic = original.count { it in '\u0400'..'\u04FF' }
    val needsTranslation = latin >= 30 && cyrillic < latin / 3

    DisposableEffect(original, needsTranslation) {
        var active = true
        val translator = if (needsTranslation) {
            Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build())
        } else null
        if (translator != null) {
            translator.downloadModelIfNeeded(
                DownloadConditions.Builder().requireWifi().build()
            ).addOnSuccessListener {
                if (active) {
                    translator.translate(original)
                        .addOnSuccessListener { result ->
                            if (active && result.isNotBlank()) translated = result
                        }
                        .addOnFailureListener {
                            if (active) failed = true
                        }
                }
            }.addOnFailureListener {
                if (active) failed = true
            }
        }
        onDispose {
            active = false
            translator?.close()
        }
    }

    Column {
        Text(translated ?: original)
        if (!translated.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Машинный перевод · Google Translate",
                style = MaterialTheme.typography.labelSmall,
                color = appMuted
            )
        } else if (failed && needsTranslation) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Русский перевод будет доступен после загрузки модели по Wi-Fi",
                style = MaterialTheme.typography.labelSmall,
                color = appMuted
            )
        }
    }
}
