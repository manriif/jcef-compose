@file:Suppress("FunctionName")

package me.manriif.example

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import me.manriif.jcef.CefBrowser
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter

private val TopRoundCornerShape = RoundedCornerShape(
    topStart = 8.dp,
    topEnd = 8.dp
)

@Stable
private class BrowserState {
    var browser: CefBrowser? by mutableStateOf(null)
    var url: String by mutableStateOf(PROJECT_GITHUB_PAGE)
    var isLoading: Boolean by mutableStateOf(false)
    var canGoBack: Boolean by mutableStateOf(false)
    var canGoForward: Boolean by mutableStateOf(false)
    var errorText: String? by mutableStateOf(null)
}

/**
 * Window that will display [CefBrowser] and a [BottomBar] to interact with the [CefBrowser].
 */
fun advancedBrowser() {
    val title = mutableStateOf("Advanced Browser")

    cefApplication(title) {
        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                val state = remember { BrowserState() }

                CefBrowser(
                    url = state.url,
                    window = window,
                    onBrowserAvailable = state::browser::set,
                    onClientAvailable = { it.configure(state, title) }
                )

                AnimatedVisibility(
                    visible = state.browser != null,
                    modifier = Modifier
                        .height(48.0.dp)
                        .align(Alignment.BottomCenter)
                        .clip(TopRoundCornerShape),
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    BottomBar(state)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(state: BrowserState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(.5f)
            .background(Color.White)
            .border(
                width = 1.0.dp,
                color = Color.Black,
                shape = TopRoundCornerShape
            )
    ) {
        NavigationButtons(state)
        UrlBar(state)
    }
}

@Composable
private fun NavigationButtons(state: BrowserState) {
    IconButton(
        enabled = state.canGoBack,
        onClick = { state.browser?.goBack() }
    ) {
        Icon(Icons.Default.ArrowBack, "Go back")
    }

    IconButton(
        enabled = state.canGoForward,
        onClick = { state.browser?.goForward() }
    ) {
        Icon(Icons.Default.ArrowForward, "Go forward")
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RowScope.UrlBar(state: BrowserState) {
    var text by remember { mutableStateOf(state.url) }

    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        modifier = Modifier
            .fillMaxHeight()
            .weight(1.0f)
            .background(Color.White)
            .padding(horizontal = 8.0.dp)
            .onKeyEvent { keyEvent ->
                when (keyEvent.key) {
                    Key.Enter -> {
                        state.url = text
                        true
                    }

                    else -> false
                }
            },
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.0.dp)
                        .weight(1f)
                ) {
                    innerTextField()
                }

                if (state.isLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 3.0.dp,
                        modifier = Modifier
                            .padding(horizontal = 8.0.dp)
                            .size(20.0.dp)
                    )
                }
            }
        }
    )
}

private fun CefClient.configure(
    state: BrowserState,
    mutableTitle: MutableState<String>
) {
    addLoadHandler(object : CefLoadHandlerAdapter() {
        override fun onLoadingStateChange(
            browser: CefBrowser,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean
        ) {
            super.onLoadingStateChange(browser, isLoading, canGoBack, canGoForward)

            if (isLoading) {
                state.errorText = null
            }

            state.isLoading = isLoading
            state.canGoBack = canGoBack
            state.canGoForward = canGoForward
        }

        override fun onLoadError(
            browser: CefBrowser,
            frame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String
        ) {
            super.onLoadError(browser, frame, errorCode, errorText, failedUrl)
            state.errorText = errorText
        }
    })

    addDisplayHandler(object : CefDisplayHandlerAdapter() {
        override fun onTitleChange(browser: CefBrowser, title: String) {
            super.onTitleChange(browser, title)
            mutableTitle.value = title
        }
    })
}