package org.cef.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import org.cef.CefClient
import org.cef.OS
import org.cef.callback.CefDragData
import org.cef.callback.CefDragData.DragOperations
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefScreenInfo
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.dnd.*
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import kotlin.math.max

private const val WHEEL_ROTATION_FACTOR = 10

/**
 * [CefBrowser] OSR implementation.
 */
internal class BrowserOsr(
    private val composeWindow: ComposeWindow,
    client: CefClient,
    url: String,
    context: CefRequestContext? = null,
    parent: BrowserOsr? = null,
    inspectAt: Point? = null
) : CefBrowser_N(client, url, context, parent, inspectAt),
    CefRenderHandler {

    private val uiComponent: Component = findCanvas(composeWindow) ?: composeWindow
    private val renderer = BrowserRenderer()
    private val contentRect = Rectangle(0, 0, 1, 1)
    private var screenPoint = Point(0, 0)
    private var windowHandle = 0L
    private var justCreated = false

    private val scaleFactor: Double
    private val depthPerComponent: Int
    private val depth: Int

    var image: Image by mutableStateOf(Image.makeFromBitmap(renderer.getBitmap()))
        private set

    ///////////////////////////////////////////////////////////////////////////
    // Init
    ///////////////////////////////////////////////////////////////////////////

    init {
        with(composeWindow.graphics as Graphics2D) {
            scaleFactor = getScaleFactor(this)

            with(deviceConfiguration.colorModel) {
                depthPerComponent = componentSize[0]
                depth = pixelSize
            }
        }

        // Connect the Canvas with a drag and drop listener.
        //DropTarget(uiComponent, CefDropTargetListener(this))
    }

    private fun findCanvas(container: Container): Canvas? {
        for (component in container.components) {
            when (component) {
                is SkiaLayer -> return component.canvas
                is Container -> findCanvas(component)?.let { canvas ->
                    return canvas
                }
            }
        }

        return null
    }

    private fun getScaleFactor(graphics2D: Graphics2D): Double {
        val javaRuntimeVersion = System.getProperty("java.runtime.version")

        if (!OS.isMacintosh() || !javaRuntimeVersion.startsWith("1.8")) {
            return graphics2D.transform.scaleX
        }

        return try {
            val scaleFactorAccessor = javaClass.classLoader!!
                .loadClass("sun.awt.CGraphicsDevice")
                .getDeclaredMethod("getScaleFactor")

            val factor = scaleFactorAccessor.invoke(graphics2D.deviceConfiguration.device)
            (factor as? Int)?.toDouble() ?: 1.0
        } catch (throwable: Throwable) {
            return 1.0
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Lifespan
    ///////////////////////////////////////////////////////////////////////////

    fun start() {
        SwingUtilities.invokeLater { createBrowserIfRequired(true) }
    }

    fun stop() {
        renderer.clean()
    }

    ///////////////////////////////////////////////////////////////////////////
    // Impl
    ///////////////////////////////////////////////////////////////////////////

    override fun createImmediately() {
        justCreated = true
        createBrowserIfRequired(false)
    }

    private fun createBrowserIfRequired(hasParent: Boolean) {
        var windowHandle = 0L

        if (hasParent) {
            windowHandle = getWindowHandle()
        }

        if (getNativeRef("CefBrowser") == 0L) {
            if (parentBrowser != null) {
                createDevTools(parentBrowser, client, windowHandle, true, true, null, inspectAt)
            } else {
                createBrowser(client, windowHandle, getUrl(), true, true, null, requestContext)
            }
        } else if (hasParent && justCreated) {
            client.onAfterParentChanged(this)
            setFocus(true)
            justCreated = false
        }
    }

    @Synchronized
    private fun getWindowHandle(): Long {
        if (this.windowHandle == 0L) {
            this.windowHandle = getWindowHandle(composeWindow.windowHandle)
            assert(windowHandle != 0L) { "windowHandle is equal to 0!" }
        }

        return this.windowHandle
    }

    override fun getUIComponent(): Component = uiComponent

    override fun getRenderHandler(): CefRenderHandler = this

    override fun createDevToolsBrowser(
        client: CefClient,
        url: String,
        context: CefRequestContext,
        parent: CefBrowser_N?,
        inspectAt: Point
    ): CefBrowser_N = BrowserOsr(composeWindow, client, url, context, this, inspectAt)

    override fun getViewRect(browser: CefBrowser): Rectangle = contentRect

    override fun getScreenPoint(browser: CefBrowser, viewPoint: Point) = Point(screenPoint).apply {
        translate(viewPoint.x, viewPoint.y)
    }

    override fun onPopupShow(browser: CefBrowser, show: Boolean) {
        if (!show) {
            this.renderer.clearPopupRects()
            invalidate()
        }
    }

    override fun onPopupSize(browser: CefBrowser, size: Rectangle) {
        renderer.onPopupSize(size)
    }

    override fun onPaint(
        browser: CefBrowser,
        popup: Boolean,
        dirtyRects: Array<out Rectangle>,
        buffer: ByteBuffer,
        width: Int,
        height: Int
    ) {
        renderer.setBitmapData(popup, buffer, width, height)
        image = Image.makeFromBitmap(renderer.getBitmap())
    }

    override fun onCursorChange(browser: CefBrowser, cursorType: Int): Boolean {
        SwingUtilities.invokeLater {
            composeWindow.focusOwner?.cursor = Cursor.getPredefinedCursor(cursorType)
        }

        return true
    }

    override fun startDragging(
        browser: CefBrowser,
        dragData: CefDragData,
        mask: Int,
        x: Int,
        y: Int
    ): Boolean {
        val action = getDndAction(mask)
        val triggerEvent = MouseEvent(composeWindow, MouseEvent.MOUSE_DRAGGED, 0, 0, x, y, 0, false)

        val event = DragGestureEvent(
            SyntheticDragGestureRecognizer(composeWindow, action, triggerEvent),
            action,
            Point(x, y),
            listOf(triggerEvent)
        )

        DragSource.getDefaultDragSource().startDrag(
            event,
            /*dragCursor=*/null,
            StringSelection(dragData.fragmentText),
            object : DragSourceAdapter() {
                override fun dragDropEnd(dsde: DragSourceDropEvent) {
                    dragSourceEndedAt(dsde.location, action)
                    dragSourceSystemDragEnded()
                }
            }
        )

        return true
    }

    private fun getDndAction(mask: Int): Int = when {
        mask and DragOperations.DRAG_OPERATION_COPY == DragOperations.DRAG_OPERATION_COPY ->
            DnDConstants.ACTION_COPY

        mask and DragOperations.DRAG_OPERATION_MOVE == DragOperations.DRAG_OPERATION_MOVE ->
            DnDConstants.ACTION_MOVE

        mask and DragOperations.DRAG_OPERATION_LINK == DragOperations.DRAG_OPERATION_LINK ->
            DnDConstants.ACTION_LINK

        else -> DnDConstants.ACTION_NONE
    }

    override fun updateDragCursor(browser: CefBrowser, operation: Int) = Unit

    override fun createScreenshot(nativeResolution: Boolean): CompletableFuture<BufferedImage> {
        TODO("Not yet implemented")
    }

    override fun getScreenInfo(browser: CefBrowser, screenInfo: CefScreenInfo): Boolean {
        screenInfo.Set(
            /* device_scale_factor = */ scaleFactor,
            /* depth = */ depth,
            /* depth_per_component = */ depthPerComponent,
            /* is_monochrome = */ false,
            /* rect = */ contentRect.bounds,
            /* availableRect = */ contentRect.bounds
        )

        return true
    }


    ///////////////////////////////////////////////////////////////////////////
    // Interact
    ///////////////////////////////////////////////////////////////////////////

    fun onMouseEvent(event: MouseEvent) {
        event.translatePoint(-contentRect.x, -contentRect.y)
        sendMouseEvent(event)
    }

    fun onMouseScrollEvent(event: MouseWheelEvent) {
        val wheelRotation = if (OS.isWindows()) event.wheelRotation else event.wheelRotation * -1
        val scrollAmount = event.scrollAmount * WHEEL_ROTATION_FACTOR

        sendMouseWheelEvent(
            MouseWheelEvent(
                /* source = */ event.component,
                /* id = */ event.id,
                /* when = */ event.getWhen(),
                /* modifiers = */ event.modifiersEx,
                /* x = */ event.x + contentRect.x,
                /* y = */ event.y + contentRect.y,
                /* clickCount = */ event.clickCount,
                /* popupTrigger = */ event.isPopupTrigger,
                /* scrollType = */ event.scrollType,
                /* scrollAmount = */ scrollAmount,
                /* wheelRotation = */ wheelRotation
            )
        )
    }

    fun onKeyEvent(event: KeyEvent) {
        sendKeyEvent(event)
    }

    fun onResized(x: Int, y: Int, width: Int, height: Int) {
        var newWidth = width
        var newHeight = height

        if (OS.isMacintosh()) {
            newWidth = max((width / scaleFactor).toInt(), 1)
            newHeight = max((height / scaleFactor).toInt(), 1)
        }

        contentRect.setBounds(x, y, newWidth, newHeight)
        screenPoint = composeWindow.locationOnScreen
        wasResized(newWidth, newHeight)
    }

    fun onFocusChanged(focused: Boolean) {
        if (windowHandle != 0L) {
            if (focused) {
                // Dismiss any Java menus that are currently displayed.
                MenuSelectionManager.defaultManager().clearSelectedPath()
            }

            setFocus(focused)
        }
    }
}

private class SyntheticDragGestureRecognizer(component: Component, action: Int, event: MouseEvent) :
    DragGestureRecognizer(DragSource(), component, action) {

    init {
        appendEvent(event)
    }

    override fun registerListeners() = Unit

    override fun unregisterListeners() = Unit
}