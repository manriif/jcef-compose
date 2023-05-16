package org.cef.browser

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.awt.Rectangle
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock

/**
 * This renderer is too slow, especially on HiDPI screens.
 * It should be replaced.
 */
internal class BrowserRenderer {

    private var bitmap: Bitmap? = null
    private var bitmapPixels: ByteArray? = null
    private var bitmapWidth = 1
    private var bitmapHeight = 1

    private var popupRect = Rectangle(0, 0, 0, 0)
    private var popupOriginRect = Rectangle(0, 0, 0, 0)
    private var popup = false

    private val lock = ReentrantLock()

    ///////////////////////////////////////////////////////////////////////////
    // Bitmap
    ///////////////////////////////////////////////////////////////////////////

    fun getBitmap(): Bitmap {
        lock.lock()

        return try {
            if (bitmap == null) {
                init()
            }

            bitmap!!
        } finally {
            lock.unlock()
        }
    }

    private fun init() {
        bitmap = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(bitmapWidth, bitmapHeight, ColorAlphaType.PREMUL))
        }
    }

    fun clean() {
        bitmapWidth = 1
        bitmapHeight = 1
    }

    private fun getBytes(buffer: ByteBuffer, width: Int, height: Int): ByteArray {
        val pixels = ByteArray(width * height * 4)
        var index = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = buffer.int
                pixels[index++] = (pixel shr 24 and 0xFF).toByte() // alpha
                pixels[index++] = (pixel shr 16 and 0xFF).toByte() // red
                pixels[index++] = (pixel shr 8 and 0xFF).toByte()  // green
                pixels[index++] = (pixel and 0xFF).toByte()        // blue
            }
        }

        return pixels
    }

    fun setBitmapData(popup: Boolean, buffer: ByteBuffer, width: Int, height: Int) {
        lock.lock()

        try {
            this.popup = popup

            if (bitmapWidth != width || bitmapHeight != height) {
                bitmapHeight = height
                bitmapWidth = width
                init()
            }

            bitmapPixels = getBytes(buffer, width, height)
            bitmap!!.installPixels(bitmap!!.imageInfo, bitmapPixels, width * 4)
        } finally {
            lock.unlock()
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Popup
    ///////////////////////////////////////////////////////////////////////////

    fun onPopupSize(rect: Rectangle) {
        if (rect.width > 0 && rect.height > 0) {
            popupOriginRect = rect
            popupRect = getPopupRectInWebView(popupOriginRect)
        }
    }

    private fun getPopupRectInWebView(originalRect: Rectangle): Rectangle {
        if (originalRect.x < 0) {
            originalRect.x = 0
        }
        if (originalRect.y < 0) {
            originalRect.y = 0
        }
        if (originalRect.x + originalRect.width > bitmapWidth) {
            originalRect.x = bitmapWidth - originalRect.width
        }
        if (originalRect.y + originalRect.height > bitmapHeight) {
            originalRect.y = bitmapHeight - originalRect.height
        }
        if (originalRect.x < 0) {
            originalRect.x = 0
        }
        if (originalRect.y < 0) {
            originalRect.y = 0
        }
        return originalRect
    }

    fun clearPopupRects() {
        popupRect.setBounds(0, 0, 0, 0)
        popupOriginRect.setBounds(0, 0, 0, 0)
    }
}