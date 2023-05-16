package me.manriif.jcef

import me.friwi.jcefmaven.*
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.callback.CefSchemeRegistrar
import org.cef.handler.CefAppHandler
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/**
 * Implement this interface to configure [CefAppBuilder].
 *
 * [CefAppBuilder.setAppHandler] and [CefAppBuilder.setProgressHandler] will be replaced so use
 * [Cef.appHandlerAdapter] and [Cef.progressHandler] respectively.
 */
fun interface CefAppBuilderConfigurator {
    fun CefAppBuilder.configure()
}

private class SchemeHandler(
    val schemeName: String,
    val domainName: String?,
    val factory: () -> CefSchemeHandlerFactory
)

private class CustomScheme(
    val schemeName: String,
    val isStandard: Boolean,
    val isLocal: Boolean,
    val isDisplayIsolated: Boolean,
    val isSecure: Boolean,
    val isCorsEnabled: Boolean,
    val isCspBypassing: Boolean,
    val isFetchEnabled: Boolean
)

/**
 * Wrapper around [CefApp] and [CefAppBuilder].
 *
 * Note that first call to [Cef.newClient] will trigger [CefAppBuilder.build] and [CefApp]
 * initialization, thus, all properties such as [Cef.appHandlerAdapter] and methods
 * such as [Cef.registerCustomScheme] must be set/called before that call.
 */
object Cef {

    private val cefAppLock = ReentrantLock()
    private var cefAppInstance: CefApp? = null

    private val schemeHandlers = mutableSetOf<SchemeHandler>()
    private val customSchemes = mutableSetOf<CustomScheme>()

    private val progressHandlers = mutableSetOf<IProgressHandler>(ConsoleProgressHandler())
    private var progressState = EnumProgress.LOCATING
    private var progressValue = EnumProgress.NO_ESTIMATION
    private val progressLock = Any()

    var appHandlerAdapter: MavenCefAppHandlerAdapter? = null
    var builderConfigurator: CefAppBuilderConfigurator? = null
    var progressHandler: IProgressHandler? = null

    ///////////////////////////////////////////////////////////////////////////
    // Instance
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Initialize the [CefApp] instance if necessary and return it.
     * The calling thread will be blocked during the initialization process.
     *
     * @throws IOException                  if an artifact could not be fetched or IO-actions
     * on disk failed
     * @throws UnsupportedPlatformException if the platform is not supported
     * @throws InterruptedException         if the installation process got interrupted
     * @throws CefInitializationException   if the initialization of JCef failed
     */
    @get:Throws(
        IOException::class,
        UnsupportedPlatformException::class,
        InterruptedException::class,
        CefInitializationException::class
    )
    val app: CefApp
        get() {
            cefAppLock.lock()

            var instance = cefAppInstance

            if (instance != null) {
                cefAppLock.unlock()
                return instance
            }

            return try {
                val builder = CefAppBuilder().apply {
                    cefSettings.windowless_rendering_enabled = true
                }

                builderConfigurator?.run {
                    builder.configure()
                }

                with(builder) {
                    setProgressHandler(this@Cef::dispatchProgress)
                    setAppHandler(AppHandler)
                    install()
                    cefAppInstance = build()
                }

                builderConfigurator = null
                progressHandler = null
                cefAppInstance!!
            } finally {
                cefAppLock.unlock()
            }
        }

    ///////////////////////////////////////////////////////////////////////////
    // Lifespan
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Dispose the [CefApp] if it was created.
     */
    fun dispose() {
        cefAppLock.lock()

        val instance = cefAppInstance

        if (instance != null) {
            instance.dispose()
            cefAppInstance = null
        }

        cefAppLock.unlock()
    }

    ///////////////////////////////////////////////////////////////////////////
    // Client
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Obtain a new [CefClient] ang get notified about initialization progress if passing a
     * non-null [onProgress] instance.
     *
     * This method should not be called from the main thread as it will block the calling thread
     * and potentially freeze the user interface.
     *
     * @throws IOException                  if an artifact could not be fetched or IO-actions
     * on disk failed
     * @throws UnsupportedPlatformException if the platform is not supported
     * @throws InterruptedException         if the installation process got interrupted
     * @throws CefInitializationException   if the initialization of JCef failed
     */
    @Throws(
        IOException::class,
        UnsupportedPlatformException::class,
        InterruptedException::class,
        CefInitializationException::class
    )
    fun newClient(onProgress: IProgressHandler? = null): CefClient {
        val added = onProgress?.let { handler ->
            synchronized(progressLock) {
                handler.handleProgress(progressState, progressValue)
                progressHandlers.add(handler)
            }
        }

        // FIXME: CefApp.createClient() crashes randomly the first time (if native bundle
        //  was installed before). Looks like this happen when CefApp is initialized in a thread
        //  other than AWT event dispatcher. Initializing CefApp in the AWT thread will lock the UI.
        //  Initialize CefApp before window is shown ??
        return try {
            app.createClient()
        } finally {
            if (added == true) {
                synchronized(progressLock) {
                    progressHandlers.remove(onProgress)
                }
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Progress
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Dispatch progress [state] and [value] to [progressHandler] and [progressHandlers]
     */
    private fun dispatchProgress(state: EnumProgress, value: Float) = synchronized(progressLock) {
        progressState = state
        progressValue = value

        progressHandler?.handleProgress(state, value)

        progressHandlers.forEach { handler ->
            handler.handleProgress(state, value)
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Scheme
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Register a custom scheme.
     *
     * This method must be called before [CefApp] initialization, after which it will no longer
     * be possible to register a custom scheme.
     *
     * @see [CefSchemeRegistrar.addCustomScheme]
     */
    fun registerCustomScheme(
        schemeName: String,
        isStandard: Boolean = false,
        isLocal: Boolean = false,
        isDisplayIsolated: Boolean = false,
        isSecure: Boolean = false,
        isCorsEnabled: Boolean = false,
        isCspBypassing: Boolean = false,
        isFetchEnabled: Boolean = false
    ) {
        ensureNotInitialized({ "register custom scheme" }) {
            require(customSchemes.none { it.schemeName == schemeName }) {
                "A scheme is already registered with the name `$schemeName`."
            }

            val customScheme = CustomScheme(
                schemeName = schemeName,
                isStandard = isStandard,
                isLocal = isLocal,
                isDisplayIsolated = isDisplayIsolated,
                isSecure = isSecure,
                isCorsEnabled = isCorsEnabled,
                isCspBypassing = isCspBypassing,
                isFetchEnabled = isFetchEnabled
            )

            customSchemes.add(customScheme)
        }
    }

    /**
     * Register a [CefSchemeHandlerFactory] for [schemeName].
     * The [factory] will be instantiated on [CefAppHandler.onContextInitialized]
     *
     * This method must be called before [CefApp] initialization.
     *
     * @see CefSchemeHandlerFactory
     */
    fun registerSchemeHandlerFactory(
        schemeName: String,
        domainName: String? = null,
        factory: () -> CefSchemeHandlerFactory,
    ) {
        ensureNotInitialized({ "register scheme handler" }) {
            schemeHandlers.add(SchemeHandler(schemeName, domainName, factory))
        }
    }

    private inline fun <T> ensureNotInitialized(lazyAction: () -> String, block: () -> T): T {
        cefAppLock.lock()

        check(cefAppInstance == null) {
            "Could not ${lazyAction.invoke()} after CefApp initialized."
        }

        return try {
            block()
        } finally {
            cefAppLock.unlock()
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Handler
    ///////////////////////////////////////////////////////////////////////////

    private object AppHandler : MavenCefAppHandlerAdapter() {

        override fun onRegisterCustomSchemes(registrar: CefSchemeRegistrar) {
            super.onRegisterCustomSchemes(registrar)
            appHandlerAdapter?.onRegisterCustomSchemes(registrar)

            customSchemes.onEach { customScheme ->
                with(customScheme) {
                    registrar.addCustomScheme(
                        /* schemeName = */ schemeName,
                        /* isStandard = */ isStandard,
                        /* isLocal = */ isLocal,
                        /* isDisplayIsolated = */ isDisplayIsolated,
                        /* isSecure = */ isSecure,
                        /* isCorsEnabled = */ isCorsEnabled,
                        /* isCspBypassing = */ isCspBypassing,
                        /* isFetchEnabled = */ isFetchEnabled
                    )
                }
            }.clear()
        }

        override fun onContextInitialized() {
            super.onContextInitialized()
            appHandlerAdapter?.onContextInitialized()

            val instance = checkNotNull(cefAppInstance) { "CefApp instance must not be null." }

            schemeHandlers.onEach { handler ->
                instance.registerSchemeHandlerFactory(
                    /* schemeName = */ handler.schemeName,
                    /* domainName = */ handler.domainName,
                    /* factory = */ handler.factory()
                )
            }.clear()
        }

        override fun onBeforeTerminate(): Boolean {
            return appHandlerAdapter?.onBeforeTerminate()
                ?: super.onBeforeTerminate()
        }

        override fun onScheduleMessagePumpWork(delayMs: Long) {
            appHandlerAdapter?.onScheduleMessagePumpWork(delayMs)
                ?: super.onScheduleMessagePumpWork(delayMs)
        }

        override fun stateHasChanged(state: CefApp.CefAppState) {
            super.stateHasChanged(state)
            appHandlerAdapter?.stateHasChanged(state)
        }
    }
}