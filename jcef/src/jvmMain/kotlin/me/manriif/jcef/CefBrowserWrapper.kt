package me.manriif.jcef

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import org.cef.CefClient
import org.cef.browser.BrowserOsr
import org.cef.browser.CefBrowser
import org.jetbrains.skia.Image
import java.awt.KeyboardFocusManager
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/**
 * Wrapper around [CefBrowser] that will notify it about UI-related stuffs.
 * The constructor is public, but the use remains internal.
 *
 * @see [me.manriif.jcef.CefBrowser].
 */
class CefBrowserWrapper(private val window: ComposeWindow, client: CefClient, url: String) {

    private val osrBrowser = BrowserOsr(window, client, url)

    internal val image: Image
        get() = osrBrowser.image

    val browser: CefBrowser
        get() = osrBrowser

    ///////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ///////////////////////////////////////////////////////////////////////////

    internal fun start() {
        osrBrowser.start()
    }

    internal fun stop() {
        osrBrowser.stop()
    }

    ///////////////////////////////////////////////////////////////////////////
    // Size & Position
    ///////////////////////////////////////////////////////////////////////////

    internal fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val location = coordinates.positionInWindow()

        osrBrowser.onResized(
            location.x.toInt(),
            location.y.toInt(),
            coordinates.size.width,
            coordinates.size.height
        )
    }

    ///////////////////////////////////////////////////////////////////////////
    // Event
    ///////////////////////////////////////////////////////////////////////////

    internal fun onPointerEvent(pointerEvent: PointerEvent) {
        when (val nativeEvent = pointerEvent.nativeEvent) {
            is MouseWheelEvent -> osrBrowser.onMouseScrollEvent(nativeEvent)
            is MouseEvent -> osrBrowser.onMouseEvent(nativeEvent)
        }
    }

    internal fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        osrBrowser.onKeyEvent(keyEvent.nativeKeyEvent as java.awt.event.KeyEvent)
        return true
    }

    internal fun onFocusEvent(state: FocusState) {
        if (state.isFocused) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
        }

        osrBrowser.onFocusChanged(state.isFocused)
    }
}
