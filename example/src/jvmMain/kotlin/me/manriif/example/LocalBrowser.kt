@file:Suppress("FunctionName")

package me.manriif.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.manriif.jcef.CefBrowser
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter

private const val HELLO_WORLD = "hello-world"
private const val URL_TAG = "URL"

/**
 * Window that will render local HTML file and permits JS interaction through [CefBrowser].
 */
fun localBrowser() {
    registerExampleScheme()

    val classLoader = FileResource::class.java.classLoader!!

    val helloWorldSite = localSite(
        FileResource(classLoader, "$HELLO_WORLD.html", "text/html"),
        FileResource(classLoader, "$HELLO_WORLD.css", "text/css"),
        FileResource(classLoader, "$HELLO_WORLD.js", "text/javascript")
    )

    cefApplication(mutableStateOf("Local Browser")) {
        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                val browserRef = remember { Ref<CefBrowser>() }
                val isBrowserReady = remember { mutableStateOf(false) }

                CefBrowser(
                    url = remember { helloWorldSite.exampleSchemeUrl() },
                    window = window,
                    onClientAvailable = { it.configure(helloWorldSite, isBrowserReady) },
                    onBrowserAvailable = { browserRef.value = it }
                )

                if (isBrowserReady.value) {
                    Row(
                        modifier = Modifier
                            .padding(16.0.dp)
                            .align(Alignment.BottomStart)
                    ) {
                        BrowserJsInteractionButtons(browserRef, isBrowserReady)
                    }

                    Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                        CodePenLink()
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserJsInteractionButtons(
    browserRef: Ref<CefBrowser>,
    isBrowserReady: State<Boolean>
) {
    OutlinedIconButton(
        enabled = isBrowserReady.value,
        onClick = { browserRef.value?.executeJavaScript("resetTransitions()", "", 0) }
    ) {
        Icon(Icons.Default.Refresh, "Reset")
    }

    FilledIconButton(
        enabled = isBrowserReady.value,
        onClick = { browserRef.value?.executeJavaScript("startTransitions()", "", 0) }
    ) {
        Icon(Icons.Default.PlayArrow, "Start")
    }
}

@Composable
private fun CodePenLink() {
    val uriHandler = LocalUriHandler.current
    val text = remember { codepenText() }

    ClickableText(
        text = text,
        modifier = Modifier.padding(8.0.dp),
        onClick = { offset ->
            val annotation = text.getStringAnnotations(offset, offset).first()
            uriHandler.openUri(annotation.item)
        }
    )
}

private fun CefClient.configure(
    site: LocalSite,
    isBrowserReady: MutableState<Boolean>
) {
    addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
        override fun onAfterCreated(browser: CefBrowser) {
            super.onAfterCreated(browser)
            site.associateWith(browser)
        }
    })

    addLoadHandler(object : CefLoadHandlerAdapter() {
        override fun onLoadingStateChange(
            browser: CefBrowser,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean
        ) {
            super.onLoadingStateChange(browser, isLoading, canGoBack, canGoForward)
            isBrowserReady.value = !isLoading
        }
    })
}

private fun codepenText() = buildAnnotatedString {
    pushStringAnnotation(URL_TAG, "https://codepen.io/vickimurley/pen/LYXRwo")

    val span = SpanStyle(
        color = Color(0xff0000EE),
        textDecoration = TextDecoration.Underline
    )

    withStyle(span) {
        append("Codepen")
    }

    pop()
}