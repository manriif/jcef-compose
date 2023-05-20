@file:Suppress("FunctionName")

package me.manriif.jcef

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.IProgressHandler
import org.cef.CefClient
import org.cef.browser.CefBrowser

/**
 * Implement this interface to get notified about [Cef] initialization progress.
 */
@Stable
interface BrowserInitState {
    val step: EnumProgress
    val progress: Float
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

///////////////////////////////////////////////////////////////////////////
// Awt
///////////////////////////////////////////////////////////////////////////

/**
 * Create a [CefClient] in order to obtain a [CefBrowser] and then display it's content.
 *
 * The callbacks [onClientAvailable] and [onBrowserAvailable] will be called respectively after
 * [CefClient] and [CefBrowser] are instantiated.
 *
 * Before the [CefBrowser] content is rendered, [initContent] will be displayed and remains
 * visible until the [Cef] is successfully initialized.
 * If [Cef] initialization fails, [errorContent] will replace [initContent].
 *
 * @see [Cef.newClient] for possible [errorContent] parameter value types.
 */
@Composable
fun CefBrowserAwt(
    url: String,
    osr: Boolean = false,
    transparent: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize(),
    onClientAvailable: (suspend (CefClient) -> Unit)? = null,
    onBrowserAvailable: (suspend (CefBrowser) -> Unit)? = null,
    errorContent: @Composable (Throwable) -> Unit,
    initContent: @Composable (BrowserInitState) -> Unit,
) {
    val useOsr = rememberUpdatedState(osr)
    val useTransparent = rememberUpdatedState(transparent)

    val holder = rememberBrowserHolder(url, onClientAvailable, onBrowserAvailable) { client, url ->
        CefBrowserAwtWrapper(client, url, useOsr.value, useTransparent.value)
    }

    holder.wrapper?.let { CefBrowserAwt(it, Color.Transparent, modifier) }
        ?: holder.error?.let { errorContent(it) }
        ?: initContent(holder.initState)

    LaunchedEffect(Unit) {
        snapshotFlow { useTransparent.value }.updateWrapperOnChange(holder, this)
        snapshotFlow { useOsr.value }.updateWrapperOnChange(holder, this)
    }
}

/**
 * Display the [wrapper] content into [SwingPanel].
 */
@Composable
fun CefBrowserAwt(
    wrapper: CefBrowserAwtWrapper,
    background: Color = Color.Transparent,
    modifier: Modifier = Modifier
) {
    SwingPanel(
        background = background,
        modifier = modifier,
        factory = { wrapper.browser.uiComponent },
    )
}

///////////////////////////////////////////////////////////////////////////
// Compose
///////////////////////////////////////////////////////////////////////////

/**
 * Create a [CefClient] in order to obtain a [CefBrowserCompose] and then display it's content.
 *
 * The callbacks [onClientAvailable] and [onBrowserAvailable] will be called respectively after
 * [CefClient] and [CefBrowser] are instantiated.
 *
 * Before the [CefBrowserCompose] content is rendered, [initContent] will be displayed and remains
 * visible until the [Cef] is successfully initialized.
 * If [Cef] initialization fails, [errorContent] will replace [initContent].
 *
 * @see [Cef.newClient] for possible [errorContent] parameter value types.
 */
@Composable
fun CefBrowserCompose(
    url: String,
    window: ComposeWindow,
    modifier: Modifier = Modifier,
    onClientAvailable: (suspend (CefClient) -> Unit)? = null,
    onBrowserAvailable: (suspend (CefBrowser) -> Unit)? = null,
    errorContent: @Composable (Throwable) -> Unit,
    initContent: @Composable (BrowserInitState) -> Unit,
) {
    val targetWindow = rememberUpdatedState(window)

    val holder = rememberBrowserHolder(url, onClientAvailable, onBrowserAvailable) { client, url ->
        CefBrowserComposeWrapper(targetWindow.value, client, url)
    }

    holder.wrapper?.let { CefBrowserCompose(it, modifier) }
        ?: holder.error?.let { errorContent(it) }
        ?: initContent(holder.initState)

    LaunchedEffect(Unit) {
        snapshotFlow { targetWindow.value }.updateWrapperOnChange(holder, this)
    }
}

/**
 * Display the [wrapper] content into [Canvas] and notify it about ui events.
 */
@Composable
fun CefBrowserCompose(
    wrapper: CefBrowserComposeWrapper,
    modifier: Modifier = Modifier
) {
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

///////////////////////////////////////////////////////////////////////////
// Common
///////////////////////////////////////////////////////////////////////////

@Composable
private fun <W : CefBrowserWrapper> rememberBrowserHolder(
    url: String,
    onClientAvailable: (suspend (CefClient) -> Unit)?,
    onBrowserAvailable: (suspend (CefBrowser) -> Unit)?,
    onCreateWrapper: (CefClient, String) -> W
): CefClientHolder<W> {
    val targetUrl = rememberUpdatedState(url)
    val holder = remember { CefClientHolder(targetUrl, onCreateWrapper) }
    val browserCallback by rememberUpdatedState(onBrowserAvailable)
    val clientCallback by rememberUpdatedState(onClientAvailable)

    holder.client?.let { instance ->
        DisposableEffect(Unit) {
            holder.wrapper = onCreateWrapper(instance, targetUrl.value)
            onDispose { instance.dispose() }
        }

        LaunchedEffect(clientCallback) {
            clientCallback?.invoke(instance)
        }
    }

    holder.wrapper?.let { instance ->
        LaunchedEffect(browserCallback) {
            browserCallback?.invoke(instance.browser)
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { targetUrl.value }.drop(1).onEach { url ->
            holder.wrapper?.browser?.loadURL(url)
        }.launchIn(this)

        try {
            holder.client = Cef.newClient(holder.initState)
        } catch (throwable: Throwable) {
            holder.error = throwable
        }
    }

    return holder
}

private fun <W : CefBrowserWrapper, T> Flow<T>.updateWrapperOnChange(
    holder: CefClientHolder<W>,
    scope: CoroutineScope,
    drop: Int = 1
) {
    val flow = if (drop > 0) this.drop(drop) else this

    flow.onEach {
        holder.client?.let { client ->
            holder.wrapper = holder.onCreateWrapper(client, holder.url.value)
        }
    }.launchIn(scope)
}

@Stable
private class CefClientHolder<W>(
    val url: State<String>,
    val onCreateWrapper: (CefClient, String) -> W
) {
    val initState = BrowserInitStateImpl()
    var client: CefClient? by mutableStateOf(null)
    var error: Throwable? by mutableStateOf(null)
    var wrapper: W? by mutableStateOf(null)
}