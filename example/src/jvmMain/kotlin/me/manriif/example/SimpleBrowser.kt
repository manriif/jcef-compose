package me.manriif.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import me.manriif.jcef.CefBrowser

/**
 * Simple browser window.
 */
fun simpleBrowser() = cefApplication(mutableStateOf("Simple Browser")) {
    MaterialTheme {
        CefBrowser(COMPOSE_MULTIPLATFORM_GITHUB, window)
    }
}