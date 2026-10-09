package com.overlingo.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.overlingo.android.R
import com.overlingo.android.export.OverlayStyle
import java.util.Locale

@Composable
fun OverlingoApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val speakingText by viewModel.speakingText.collectAsStateWithLifecycle()
    val downloadingModel by viewModel.offlineDownloading.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = state.screen != Screen.CAMERA) { viewModel.back() }

    when (state.screen) {
        Screen.CAMERA -> CameraScreen(
            target = settings.target,
            onPhoto = viewModel::onCameraPhoto,
            onImageUri = viewModel::onImageUri,
            onTargetSelected = viewModel::setTargetLanguage,
            onOpenText = { viewModel.openTextScreen() },
            onOpenSettings = viewModel::openSettings,
            onError = viewModel::showToast,
        )
        Screen.RESULT -> state.photo?.let { photo ->
            ResultScreen(
                photo = photo,
                target = settings.target,
                style = OverlayStyle.from(settings),
                speakingText = speakingText,
                downloadingModel = downloadingModel,
                exporting = state.exporting,
                onSave = { viewModel.exportPhoto(share = false) },
                onShare = { viewModel.exportPhoto(share = true) },
                onPermissionDenied = { viewModel.showToast(UiMessage(R.string.storage_permission_denied)) },
                onBack = viewModel::backToCamera,
                onRetry = viewModel::retryPhoto,
                onToggleOriginal = viewModel::toggleOriginal,
                onToggleHidden = viewModel::toggleHidden,
                onShowAllHidden = viewModel::showAllHidden,
                onTargetSelected = viewModel::setTargetLanguage,
                onOpenSettings = viewModel::openSettings,
                onSpeak = viewModel::toggleSpeak,
                onCopied = { viewModel.showToast(UiMessage(R.string.copied)) },
            )
        }
        Screen.TEXT -> TextScreen(
            state = state.text,
            target = settings.target,
            speakingText = speakingText,
            downloadingModel = downloadingModel,
            onInput = viewModel::setTextInput,
            onTranslate = viewModel::translateText,
            onTargetSelected = viewModel::setTargetLanguage,
            onSpeak = viewModel::toggleSpeak,
            onCopied = { viewModel.showToast(UiMessage(R.string.copied)) },
            onBack = viewModel::back,
            onOpenSettings = viewModel::openSettings,
        )
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            offlineModels = state.offlineModels,
            onUpdate = viewModel::updateSettings,
            onRefreshOfflineModels = viewModel::refreshOfflineModels,
            onDeleteOfflineModel = viewModel::deleteOfflineModel,
            onBack = viewModel::back,
        )
    }

    // Short messages (copied, fallback service, download errors...)
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it.render(context), Toast.LENGTH_LONG).show()
            viewModel.consumeToast()
        }
    }

    // The image with translations is ready to share - open Android's "Share" sheet.
    LaunchedEffect(state.shareUri) {
        state.shareUri?.let { uri ->
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // ClipData - for the preview in the "Share" sheet and for granting read access through it
            send.clipData = ClipData.newRawUri(null, uri)
            openActivity(context, Intent.createChooser(send, null))
            viewModel.consumeShareUri()
        }
    }

    state.ttsProblem?.let { problem ->
        TtsProblemDialog(problem, onDismiss = viewModel::dismissTtsProblem)
    }
}

/** The message text in the UI language + the detail after it. */
fun UiMessage.render(context: Context): String {
    val main = context.getString(res)
    return if (detail.isBlank()) main else "$main ($detail)"
}

@Composable
fun UiMessage.text(): String {
    val main = stringResource(res)
    return if (detail.isBlank()) main else "$main ($detail)"
}

/** The UI locale - for language names (Bulgarian "Nemski" / English "German"). */
@Composable
fun uiLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
private fun TtsProblemDialog(problem: TtsProblem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tts_problem_title)) },
        text = {
            Text(
                stringResource(
                    if (problem == TtsProblem.MISSING_DATA) R.string.tts_missing_data else R.string.tts_not_supported
                )
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                val intent = if (problem == TtsProblem.MISSING_DATA) {
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                } else {
                    Intent("com.android.settings.TTS_SETTINGS")
                }
                openActivity(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                Text(
                    stringResource(
                        if (problem == TtsProblem.MISSING_DATA) R.string.tts_install_voice else R.string.tts_open_settings
                    )
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

fun openActivity(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.cannot_open_settings), Toast.LENGTH_LONG).show()
    }
}
