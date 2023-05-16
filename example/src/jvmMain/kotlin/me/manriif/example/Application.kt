package me.manriif.example

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import me.manriif.jcef.Cef

const val COMPOSE_MULTIPLATFORM_GITHUB = "https://github.com/JetBrains/compose-multiplatform"

fun cefApplication(
    title: State<String>,
    content: @Composable FrameWindowScope.() -> Unit
) = application {
    Window(
        onCloseRequest = {
            Cef.dispose()
            exitApplication()
        },
        title = title.value,
        content = content
    )
}