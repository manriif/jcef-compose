@file:Suppress("FunctionName")

package me.manriif.example

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import me.friwi.jcefmaven.EnumProgress
import me.manriif.jcef.ApplicationRestartRequiredException
import me.manriif.jcef.BrowserInitState
import me.manriif.jcef.Cef

const val PROJECT_GITHUB_PAGE = "https://github.com/Manriif/jcef-compose"

private fun interface ApplicationRestarter {
    fun restart()
}

private val LocalApplicationRestarter = staticCompositionLocalOf<ApplicationRestarter> {
    error("CompositionLocal LocalApplicationRestarter not found.")
}

fun cefApplication(
    title: State<String>,
    content: @Composable FrameWindowScope.() -> Unit
) {
    var restart = true

    while (restart) {
        restart = newApplication(title, content)
    }

    Cef.dispose()
}

private fun newApplication(
    title: State<String>,
    content: @Composable FrameWindowScope.() -> Unit
): Boolean {
    var shouldRestart = false

    application(exitProcessOnExit = false) {
        val restarter = remember(this) {
            ApplicationRestarter {
                shouldRestart = true
                exitApplication()
            }
        }

        CompositionLocalProvider(LocalApplicationRestarter provides restarter) {
            Window(
                onCloseRequest = this::exitApplication,
                title = title.value,
            ) {
                Cef.initAsync() // https://github.com/JetBrains/compose-multiplatform/issues/2939
                content()
            }
        }
    }

    return shouldRestart
}

/**
 * Init content example.
 */
@Composable
fun CefInitProgressContent(state: BrowserInitState) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                modifier = Modifier.padding(bottom = 8.dp),
                text = when (state.step) {
                    EnumProgress.LOCATING -> "Locating JCEF native bundle"
                    EnumProgress.EXTRACTING -> "Extracting JCEF native bundle"

                    EnumProgress.DOWNLOADING -> when (val progress = state.progress) {
                        EnumProgress.NO_ESTIMATION -> "Downloading JCEF native bundle"
                        in 0f..100f -> "Downloading JCEF native bundle: ${progress.toInt()}%"
                        else -> throw IllegalStateException("Unexpected progress value")
                    }

                    EnumProgress.INSTALL -> "Installing JCEF native bundle"
                    EnumProgress.INITIALIZING -> "Initializing JCEF"
                    EnumProgress.INITIALIZED -> "JCEF successfully initialized"
                }
            )

            if (state.step == EnumProgress.DOWNLOADING && state.progress >= 0f) {
                LinearProgressIndicator(state.progress / 100f, Modifier.fillMaxWidth(.5f))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(.5f))
            }
        }
    }
}

/**
 * Error content example.
 */
@Composable
fun CefInitErrorContent(throwable: Throwable) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = throwable.message ?: "Failed to init cef.")

            if (throwable is ApplicationRestartRequiredException) {
                val applicationRestarter = LocalApplicationRestarter.current

                Button(
                    modifier = Modifier.padding(top = 16.0.dp),
                    onClick = { applicationRestarter.restart() }
                ) {
                    Text("Restart")
                }
            }
        }
    }
}