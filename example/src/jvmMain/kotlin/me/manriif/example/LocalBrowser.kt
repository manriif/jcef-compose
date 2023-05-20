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
import androidx.compose.ui.window.FrameWindowScope
import me.manriif.jcef.CefBrowserAwt
import me.manriif.jcef.CefBrowserCompose
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter

private const val HELLO_WORLD_AWT = "awt/hello-world"
private const val HELLO_WORLD_COMPOSE = "compose/hello-world"
private const val URL_TAG = "URL"

///////////////////////////////////////////////////////////////////////////
// Awt
///////////////////////////////////////////////////////////////////////////

/**
 * Window that will render local HTML file and permits JS interaction through [CefBrowserCompose].
 */
fun localAwtBrowser() = helloWorldApplication(HELLO_WORLD_AWT) { helloWorldSite ->
    Box(modifier = Modifier.fillMaxSize()) {
        CefBrowserAwt(
            url = remember { helloWorldSite.exampleSchemeUrl() },
            onClientAvailable = { it.configure(helloWorldSite) },
            errorContent = { CefInitErrorContent(it) },
            initContent = { CefInitProgressContent(it) }
        )
    }
}

///////////////////////////////////////////////////////////////////////////
// Compose
///////////////////////////////////////////////////////////////////////////

/**
 * Window that will render local HTML file through [CefBrowserAwt].
 */
fun localComposeBrowser() = helloWorldApplication(HELLO_WORLD_COMPOSE) { helloWorldSite ->
    Box(modifier = Modifier.fillMaxSize()) {
        val browserRef = remember { Ref<CefBrowser>() }
        val isBrowserReady = remember { mutableStateOf(false) }

        CefBrowserCompose(
            url = remember { helloWorldSite.exampleSchemeUrl() },
            window = window,
            onClientAvailable = { it.configure(helloWorldSite, isBrowserReady) },
            onBrowserAvailable = { browserRef.value = it },
            errorContent = { CefInitErrorContent(it) },
            initContent = { CefInitProgressContent(it) }
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
            text.getStringAnnotations(offset, offset).firstOrNull()?.let { annotation ->
                uriHandler.openUri(annotation.item)
            }
        }
    )
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

///////////////////////////////////////////////////////////////////////////
// Common
///////////////////////////////////////////////////////////////////////////

private fun helloWorldApplication(
    path: String,
    content: @Composable FrameWindowScope.(helloWorldSite: LocalSite) -> Unit
) {
    registerExampleScheme()

    val classLoader = FileResource::class.java.classLoader!!

    val helloWorldSite = localSite(
        FileResource(classLoader, "$path.html", "text/html"),
        FileResource(classLoader, "$path.css", "text/css"),
        FileResource(classLoader, "$path.js", "text/javascript")
    )

    cefApplication(mutableStateOf("Local Browser")) {
        MaterialTheme {
            content(helloWorldSite)
        }
    }
}

private fun CefClient.configure(
    site: LocalSite,
    isBrowserReady: MutableState<Boolean>? = null
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
            isBrowserReady?.value = !isLoading
        }
    })
}