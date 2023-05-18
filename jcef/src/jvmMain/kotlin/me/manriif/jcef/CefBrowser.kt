@file:Suppress("FunctionName")

package me.manriif.jcef

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.IProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser

/**
 * Implement this interface to get notified about [CefApp] initialization progress.
 */
@Stable
interface BrowserInitState {
    val step: EnumProgress
    val progress: Float
}

/**
 * Create a [CefClient] in order to obtain a [CefBrowser] and then display it's content.
 *
 * The callbacks [onClientAvailable] and [onBrowserAvailable] will be called respectively after
 * [CefClient] and [CefBrowser] are instantiated.
 *
 * Before the [CefBrowser] content is rendered, [initContent] will be displayed and remains visible
 * until the [CefApp] is successfully initialized. If [CefApp] initialization fails, [errorContent]
 * will replace [initContent].
 *
 * @see [CefAppBuilder.build] for possible [errorContent] parameter value.
 */
@Composable
fun CefBrowser(
    url: String,
    window: ComposeWindow,
    onClientAvailable: (suspend (CefClient) -> Unit)? = null,
    onBrowserAvailable: (suspend (CefBrowser) -> Unit)? = null,
    modifier: Modifier = Modifier,
    errorContent: @Composable (Throwable) -> Unit = remember {
        { CefInitErrorContent(it) }
    },
    initContent: @Composable (BrowserInitState) -> Unit = remember {
        { CefInitProgressContent(it) }
    },
) {
    val browserCallback by rememberUpdatedState(onBrowserAvailable)
    val clientCallback by rememberUpdatedState(onClientAvailable)
    val targetUrl = rememberUpdatedState(url)
    val targetWindow = rememberUpdatedState(window)
    val client = remember { mutableStateOf<CefClient?>(null) }
    val error = remember { mutableStateOf<Throwable?>(null) }
    val wrapper = remember { mutableStateOf<CefBrowserWrapper?>(null) }
    val initState = remember { BrowserInitStateImpl() }

    wrapper.value?.let { CefBrowser(it, modifier) }
        ?: error.value?.let { errorContent(it) }
        ?: initContent(initState)

    client.value?.let { instance ->
        DisposableEffect(Unit) {
            wrapper.value = CefBrowserWrapper(targetWindow.value, instance, targetUrl.value)
            onDispose { instance.dispose() }
        }

        LaunchedEffect(clientCallback) {
            clientCallback?.invoke(instance)
        }
    }

    wrapper.value?.let { instance ->
        LaunchedEffect(browserCallback) {
            browserCallback?.invoke(instance.browser)
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { targetUrl.value }.drop(1).onEach { url ->
            wrapper.value?.browser?.loadURL(url)
        }.launchIn(this)

        snapshotFlow { targetWindow.value }.drop(1).onEach { window ->
            client.value?.let { client ->
                wrapper.value = CefBrowserWrapper(window, client, targetUrl.value)
            }
        }.launchIn(this)

        runCatching { client.value = Cef.newClient(initState) }.exceptionOrNull()
            ?.let(error::value::set)
    }
}

/**
 * Render the [wrapper] content into [Canvas] and notify it about ui events.
 */
@Composable
fun CefBrowser(wrapper: CefBrowserWrapper, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }

    Canvas(Modifier
        .fillMaxSize()
        .onGloballyPositioned(wrapper::onGloballyPositioned)
        .focusRequester(focusRequester)
        .onFocusChanged(wrapper::onFocusEvent)
        .focusable()
        .onKeyEvent(wrapper::onKeyEvent)
        .pointerInput(Unit) {
            val context = currentCoroutineContext()

            awaitEachGesture {
                while (context.isActive) {
                    val event = awaitPointerEvent()

                    if (event.type == PointerEventType.Press) {
                        focusRequester.requestFocus()
                    }

                    wrapper.onPointerEvent(event)
                }
            }
        }
        .then(modifier)
    ) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImage(wrapper.image, 0f, 0f)
        }
    }

    DisposableEffect(wrapper) {
        val wrapperInstance = wrapper.apply { start() }
        onDispose { wrapperInstance.stop() }
    }
}

/**
 * Init content example.
 */
@Composable
private fun CefInitProgressContent(state: BrowserInitState) {
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
private fun CefInitErrorContent(throwable: Throwable) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Failed to initialize cef: ${throwable.message}")
        }
    }
}

@Stable
private class BrowserInitStateImpl : BrowserInitState, IProgressHandler {

    override var progress: Float by mutableStateOf(EnumProgress.NO_ESTIMATION)
        private set

    override var step: EnumProgress by mutableStateOf(EnumProgress.LOCATING)
        private set

    override fun handleProgress(state: EnumProgress, percent: Float) {
        progress = percent
        step = state
    }
}