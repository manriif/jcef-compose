package me.manriif.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import me.manriif.jcef.CefBrowserAwt
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.handler.CefDisplayHandlerAdapter

/**
 * Awt browser.
 */
fun awtBrowser() {
    val title = mutableStateOf("Awt Browser")

    cefApplication(title) {
        MaterialTheme {
            CefBrowserAwt(
                url = PROJECT_GITHUB_PAGE,
                onClientAvailable = { it.configure(title) },
                errorContent = { CefInitErrorContent(it) },
                initContent = { CefInitProgressContent(it) }
            )
        }
    }
}

private fun CefClient.configure(mutableTitle: MutableState<String>) {
    addDisplayHandler(object : CefDisplayHandlerAdapter() {
        override fun onTitleChange(browser: CefBrowser, title: String) {
            super.onTitleChange(browser, title)
            mutableTitle.value = title
        }
    })
}