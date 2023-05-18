package me.manriif.jcef

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.friwi.jcefmaven.*
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.callback.CefSchemeRegistrar
import org.cef.handler.CefAppHandler
import java.io.IOException
import kotlin.concurrent.thread

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

private enum class State {
    New,
    Initializing,
    Initialized,
    Disposed
}

/**
 * Wrapper around [CefApp] and [CefAppBuilder].
 *
 * Note that first call to [Cef.newClient] will trigger [CefAppBuilder.build] and [CefApp]
 * initialization, thus, all properties such as [Cef.appHandlerAdapter] and methods
 * such as [Cef.registerCustomScheme] must be set/called before that call.
 *
 * @author Maanrifa Bacar Ali
 */
object Cef {

    private var state = State.New
    private val stateLock = Any()

    private var cefAppInstanceFlow = MutableStateFlow<CefApp?>(null)
    private val cefAppLock = Any()

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
    // Lifespan
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Initialize the [CefApp] instance, downloading native bundle if required.
     * If non-null, [onBuildStarted] will be invoked before [CefAppBuilder.build].
     * The calling thread may be blocked during the initialization process.
     * This method is thread-safe and can be called multiple time.
     *
     * @throws IOException                  if an artifact could not be fetched or IO-actions
     * on disk failed
     * @throws UnsupportedPlatformException if the platform is not supported
     * @throws InterruptedException         if the installation process got interrupted
     * @throws CefInitializationException   if the initialization of JCef failed
     * @throws IllegalStateException        if [dispose] was called
     */
    @Throws(
        IOException::class,
        UnsupportedPlatformException::class,
        InterruptedException::class,
        CefInitializationException::class,
        IllegalStateException::class
    )
    fun init(onBuildStarted: (() -> Unit)? = null) {
        synchronized(stateLock) sync@{
            when (state) {
                State.Disposed -> illegalState("CefApp is disposed.")
                State.Initializing, State.Initialized -> return@sync onBuildStarted?.invoke()
                State.New -> state = State.Initializing
            }
        }

        synchronized(cefAppLock) {
            val builder = CefAppBuilder().apply {
                cefSettings.windowless_rendering_enabled = true
            }

            builderConfigurator?.run {
                builder.configure()
            }

            with(builder) {
                setProgressHandler(this@Cef::dispatchProgress)
                setAppHandler(AppHandler)
                onBuildStarted?.invoke()
                cefAppInstanceFlow.value = build()
            }

            builderConfigurator = null
            progressHandler = null

            synchronized(stateLock) {
                check(state == State.Initializing) { "Unexpected state." }
                state = State.Initialized
            }
        }
    }

    /**
     * Dispose the [CefApp] if it was created.
     *
     * This method is thread-safe and has no effect if [init] was not called or
     * if [CefApp] is already disposed.
     */
    fun dispose() {
        synchronized(stateLock) {
            if (state == State.New || state == State.Disposed) {
                return@synchronized
            }

            synchronized(cefAppLock) {
                val instance = cefAppInstanceFlow.value ?: return
                instance.dispose()
                cefAppInstanceFlow.value = null
                state = State.Disposed
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Client
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Obtain a new [CefClient] ang get notified about initialization progress if passing a
     * non-null [onProgress] instance.
     * If an instance of [CefApp] could be obtained immediately, [onProgress] will be ignored.
     *
     * @throws IllegalStateException if [init] was not called or [dispose] was called.
     */
    @Throws(IllegalStateException::class)
    suspend fun newClient(onProgress: IProgressHandler? = null): CefClient {
        synchronized(stateLock) {
            if (state == State.New) {
                illegalState("init() must be called before newClient().")
            } else if (state == State.Disposed) {
                illegalState("Could not create client after dispose() was called")
            }
        }

        var cefApp = cefAppInstanceFlow.value

        if (cefApp != null) {
            return cefApp.createClient()
        }

        val added = onProgress?.let { handler ->
            synchronized(progressLock) {
                handler.handleProgress(progressState, progressValue)
                progressHandlers.add(handler)
            }
        }

        cefApp = cefAppInstanceFlow.filterNotNull().first()

        if (added == true) {
            synchronized(progressLock) {
                progressHandlers.remove(onProgress)
            }
        }

        return cefApp.createClient()
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
     * This method must be called before [init] after which it will no longer be possible to
     * register a custom scheme.
     *
     * @see [CefSchemeRegistrar.addCustomScheme]
     * @throws IllegalStateException if [CefApp] is initialized or disposed.
     */
    @Throws(IllegalStateException::class)
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
        ensureIsNew { "register custom scheme" }

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

    /**
     * Register a [CefSchemeHandlerFactory] for [schemeName].
     * The [factory] will be instantiated on [CefAppHandler.onContextInitialized]
     *
     * This method must be called before [init].
     *
     * @see CefSchemeHandlerFactory
     * @throws IllegalStateException if [CefApp] is initialized or disposed.
     */
    @Throws(IllegalStateException::class)
    fun registerSchemeHandlerFactory(
        schemeName: String,
        domainName: String? = null,
        factory: () -> CefSchemeHandlerFactory,
    ) {
        ensureIsNew { "register scheme handler" }
        schemeHandlers.add(SchemeHandler(schemeName, domainName, factory))
    }

    private inline fun ensureIsNew(lazyAction: () -> String) {
        synchronized(stateLock) {
            when (state) {
                State.New -> return

                State.Initializing, State.Initialized ->
                    illegalState("Could not ${lazyAction()} after CefApp started initializing.")

                State.Disposed ->
                    illegalState("Could not ${lazyAction()} after CefApp is disposed.")
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Exception
    ///////////////////////////////////////////////////////////////////////////

    private fun illegalState(message: String): Nothing {
        throw IllegalStateException(message)
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

            val instance = checkNotNull(cefAppInstanceFlow.value) {
                "CefApp instance must not be null."
            }

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

/**
 * Call [Cef.init] in a background thread.
 *
 * The main purpose of this method is to prevent the main thread from being blocked
 * when [CefAppBuilder.install] will start the native bundle download process. If an error occurs,
 * it will be forwarded to [onError] if provided and thrown otherwise.
 *
 * This method will return after the background thread successfully calls [Cef.init].
 *
 * FIXME: [CefApp.createClient] crashes randomly if the native bundle needs to be downloaded and
 * [CefApp.getInstance] is not called within a certain time.
 * The cause of this issue remains unknown but it's seems to occur in [CefApp.N_Initialize].
 *
 * @author Maanrifa Bacar Ali
 */
fun Cef.initOnBackgroundThread(
    threadName: String = "CefBackgroundInit",
    onError: ((Throwable) -> Unit)? = null
) {
    val buildStarted = MutableStateFlow(false)

    thread(name = threadName) {
        try {
            init { buildStarted.value = true }
        } catch (error: Throwable) {
            onError?.invoke(error) ?: throw error
        }
    }

    runBlocking {
        buildStarted.first { it }
    }
}