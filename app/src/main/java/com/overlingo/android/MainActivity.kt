package com.overlingo.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.overlingo.android.ui.MainViewModel
import com.overlingo.android.ui.OverlingoApp
import com.overlingo.android.ui.OverlingoTheme

/**
 * Единствената Activity. AppCompatActivity (а не ComponentActivity) е нужна
 * само заради смяната на езика на интерфейса от настройките.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Интерфейсът винаги е тъмен - светли икони в лентите на системата.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // При завъртане/смяна на езика Activity-то се пресъздава - тогава не обработваме "Сподели" пак.
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            OverlingoTheme {
                OverlingoApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Сподели" -> Overlingo от друго приложение: снимка или текст. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        when {
            type.startsWith("image/") ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(viewModel::onImageUri)
            type.startsWith("text/") ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(viewModel::onSharedText)
        }
    }
}
