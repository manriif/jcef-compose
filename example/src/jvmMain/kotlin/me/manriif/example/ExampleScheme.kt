package me.manriif.example

import me.manriif.jcef.Cef
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefLifeSpanHandler
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.FileNotFoundException
import java.io.IOException
import java.util.logging.Logger

private const val EXAMPLE_SCHEME_NAME = "example"
private const val FILE_URL_FORMAT = "$EXAMPLE_SCHEME_NAME:///local/%s"

private val SchemeLogger: Logger = Logger.getLogger(EXAMPLE_SCHEME_NAME)

private val FileRequestMap by lazy {
    mutableMapOf<CefBrowser, MutableMap<String, FileResource>>()
}

class FileResource(
    val classLoader: ClassLoader,
    val filePath: String,
    val mimeType: String
)

class LocalSite(
    val mainResource: FileResource,
    val childResources: List<FileResource>
)

/**
 * Create a [LocalSite] with vararg [childResource].
 */
fun localSite(
    mainResource: FileResource,
    vararg childResource: FileResource
) = LocalSite(mainResource, childResource.toList())

/**
 * Register the custom scheme [EXAMPLE_SCHEME_NAME] to [Cef]
 * This method must be called before [Cef] initialization.
 */
fun registerExampleScheme() {
    Cef.registerCustomScheme(
        schemeName = EXAMPLE_SCHEME_NAME,
        isLocal = true
    )

    Cef.registerSchemeHandlerFactory(
        schemeName = EXAMPLE_SCHEME_NAME,
        factory = ::ExampleSchemeHandlerFactory
    )
}

/**
 * @return an url for the [FileResource].
 */
private fun FileResource.exampleSchemeUrl() = FILE_URL_FORMAT.format(filePath)

/**
 * Create and return an url for the [LocalSite].
 * The returned url must be used as [CefBrowser] initial url.
 */
fun LocalSite.exampleSchemeUrl(): String = mainResource.exampleSchemeUrl()

/**
 * Associate a [LocalSite] to a [CefBrowser].
 *
 * This function should be called just after the [CefBrowser] creation, for example
 * in [CefLifeSpanHandler.onAfterCreated].
 */
fun LocalSite.associateWith(browser: CefBrowser) = synchronized(FileRequestMap) {
    FileRequestMap.getOrPut(browser) { mutableMapOf() }.apply {
        put(mainResource.exampleSchemeUrl(), mainResource)

        childResources.forEach { resource ->
            put(resource.exampleSchemeUrl(), resource)
        }
    }
}

private class ExampleSchemeHandlerFactory : CefSchemeHandlerFactory {

    override fun create(
        browser: CefBrowser,
        frame: CefFrame,
        schemeName: String,
        request: CefRequest
    ): CefResourceHandler? {
        if (EXAMPLE_SCHEME_NAME != schemeName) {
            return null
        }

        val url = request.url ?: return null

        val fileResource = FileRequestMap[browser]?.remove(url)
            ?: return null

        return ExampleSchemeResourceHandler(fileResource)
    }
}

private class ExampleSchemeResourceHandler(private val fileResource: FileResource) :
    CefResourceHandlerAdapter() {

    private val inputStream = with(fileResource) {
        classLoader.getResourceAsStream(filePath)
            ?: throw FileNotFoundException("Resources at $filePath not found.")
    }

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(
        response: CefResponse,
        responseLength: IntRef,
        redirectUrl: StringRef
    ) = with(response) {
        mimeType = fileResource.mimeType
        status = 200
    }

    override fun readResponse(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefCallback
    ): Boolean {
        try {
            val availableSize = inputStream.available()

            if (availableSize > 0) {
                var maxBytesToRead = availableSize.coerceAtMost(bytesToRead)
                maxBytesToRead = inputStream.read(dataOut, 0, maxBytesToRead)
                bytesRead.set(maxBytesToRead)
                return true
            }
        } catch (exception: IOException) {
            SchemeLogger.warning(exception.message)
        }

        bytesRead.set(0)

        try {
            inputStream.close()
        } catch (exception: IOException) {
            SchemeLogger.warning(exception.message)
        }

        return false
    }
}