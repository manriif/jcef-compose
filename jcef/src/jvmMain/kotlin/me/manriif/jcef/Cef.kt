package me.manriif.jcef

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.friwi.jcefmaven.*
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler
import me.friwi.jcefmaven.impl.step.check.CefInstallationChecker
import org.cef.CefApp
import org.cef.CefClient
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.callback.CefSchemeRegistrar
import org.cef.handler.CefAppHandler
import java.io.File
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The application needs to be restarted in order to avoid random crash on some platform.
 * This exception is only thrown after an asynchronous installation.
 */
class ApplicationRestartRequiredException(message: String) : Exception(message)

/**
 * Implement this interface to configure [CefAppBuilder].
 *
 * Some methods of [CefAppBuilder] will be called internally and may erase user defined values,
 * prefer below replacements:
 *
 * [CefAppBuilder.setProgressHandler] => [Cef.progressHandler]
 * [CefAppBuilder.setAppHandler] => [Cef.appHandlerAdapter]
 * [CefAppBuilder.setInstallDir] => [Cef.installDir]
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
    Error, // Used in case of asynchronous init
    Disposed
}

/**
 * Wrapper around [CefApp] and [CefAppBuilder].
 *
 * Note that call to [Cef.initSync] or [Cef.initAsync] will trigger [CefAppBuilder.build]
 * and [CefApp] initialization, thus, all properties such as [Cef.appHandlerAdapter] and methods
 * such as [Cef.registerCustomScheme] must be set/called before that call.
 *
 * @author Maanrifa Bacar Ali
 */
object Cef {

    private val state = MutableStateFlow(State.New)
    private var cefInitError: Throwable? = null
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
    var installDir: File = File("jcef-bundle")

    private val cefApp: CefApp
        get() = checkNotNull(cefAppInstance) { "CefApp must not be null." }

    ///////////////////////////////////////////////////////////////////////////
    // Lifespan
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Dispose the [CefApp] if it was created.
     * If [initAsync] was called, this method will wait for initialization to finish.
     */
    fun dispose() {
        when (state.value) {
            State.New, State.Disposed, State.Error -> return
            State.Initializing -> {
                runBlocking {
                    state.first { it != State.Initializing }
                }

                return dispose()
            }

            State.Initialized -> {
                state.value = State.Disposed
                cefApp.dispose()
                cefAppInstance = null
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Init
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Initialize the [cefAppInstance], downloading the native bundle if missing.
     * The calling thread may be blocked for a long duration during the initialization process
     * depending on network speed.
     *
     * This method is thread-safe and can be called multiple times.
     *
     * @throws IOException if an artifact could not be fetched or IO-actions on disk failed
     * @throws UnsupportedPlatformException if the platform is not supported
     * @throws InterruptedException if the installation process got interrupted
     * @throws CefInitializationException if the initialization of JCef failed
     * @throws IllegalStateException if [dispose] was called
     */
    @Throws(
        IOException::class,
        UnsupportedPlatformException::class,
        InterruptedException::class,
        CefInitializationException::class,
        IllegalStateException::class
    )
    fun initSync() {
        val builder = getInitBuilder(installDir) ?: return
        val result = runCatching { builder.build() }

        setInitResult(result)
        result.exceptionOrNull()?.let { throw it }
    }

    /**
     * Initialize the [cefAppInstance] in background, downloading native bundle if missing.
     *
     * The main purpose of this method is to prevent the main thread from being blocked
     * when [CefAppBuilder.install] will start the native bundle download process.
     * If an error occurs, it will be forwarded to [onError] if provided and thrown otherwise.
     *
     * This method is thread-safe and can be called multiple times.
     *
     * FIXME: [CefApp.createClient] crashes randomly after the native bundle have been downloaded
     *  and [CefApp.getInstance] is not called within a certain time.
     *  The cause of this issue (observed on macOS) remains unknown but it seems to occurs
     *  in [CefApp.N_Initialize].
     *  As a result, an [ApplicationRestartRequiredException] will be thrown on each call
     *  to [newClient].
     *  Application restart can be achieved in [onRestartRequired].
     *
     * @throws IllegalStateException if [dispose] was called
     */
    @Throws(IllegalStateException::class)
    fun initAsync(
        onError: ((Throwable) -> Unit)? = null,
        onRestartRequired: (() -> Unit)? = null
    ) {
        val installDir = this.installDir
        val builder = getInitBuilder(installDir) ?: return
        val isInstallOk = CefInstallationChecker.checkInstallation(installDir)

        if (isInstallOk) {
            Dispatchers.Default.dispatch(EmptyCoroutineContext) {
                val result = runCatching { builder.build() }
                setInitResult(result)

                result.exceptionOrNull()?.let { error ->
                    onError?.invoke(error) ?: throw error
                }
            }
        } else {
            Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                try {
                    builder.install()
                } catch (throwable: Throwable) {
                    setInitResult(Result.failure(throwable))
                    onError?.invoke(throwable) ?: throw throwable
                }

                val exception = ApplicationRestartRequiredException("Application needs to restart.")

                setInitResult(Result.failure(exception))
                onRestartRequired?.invoke()
            }
        }
    }

    /**
     * Create, configure and return a [CefAppBuilder] instance or return null if [state] is
     * [State.Initializing] or [State.Initialized].
     *
     * [State.Error] behave the same as [State.New] and thus allow to retry initialization if it has
     * previously failed.
     *
     * @throws IllegalStateException if [state] is [State.Disposed].
     */
    @Throws(IllegalStateException::class)
    private fun getInitBuilder(installDir: File): CefAppBuilder? {
        val currentState = state.value

        when (currentState) {
            State.Disposed -> illegalState("Cef is disposed.")
            State.Initializing, State.Initialized -> return null
            State.New, State.Error -> state.value = State.Initializing
        }

        if (currentState == State.Error) {
            cefInitError = null
        }

        val builder = CefAppBuilder().apply {
            cefSettings.windowless_rendering_enabled = true
        }

        builderConfigurator?.run {
            builder.configure()
        }

        return builder.apply {
            setProgressHandler(::dispatchProgress)
            setAppHandler(AppHandler)
            setInstallDir(installDir)
        }
    }

    /**
     * Update [state] according to [result].
     * [Result.isSuccess] implies that [CefApp] successfully initialized and thus [state] will be
     * [State.Initialized].
     * Conversely, [Result.isFailure] will cause [state] to be [State.Error].
     */
    private fun setInitResult(result: Result<CefApp>) {
        val nextState = if (result.isSuccess) {
            cefAppInstance = result.getOrThrow()
            builderConfigurator = null
            progressHandler = null
            State.Initialized
        } else {
            cefInitError = result.exceptionOrNull()
            State.Error
        }

        check(state.compareAndSet(State.Initializing, nextState)) {
            "State.Initializing was expected."
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Client
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Obtain a new [CefClient] ang get notified about initialization progress if passing a
     * non-null [onProgress] instance.
     * If an instance of [CefApp] can be obtained immediately, [onProgress] will be ignored.
     *
     * @throws IOException if an artifact could not be fetched or IO-actions on disk failed
     * @throws UnsupportedPlatformException if the platform is not supported
     * @throws InterruptedException if the installation process got interrupted
     * @throws CefInitializationException if the initialization of JCef failed
     * @throws IllegalStateException if [Cef] was not initialized or [dispose] was called.
     * @throws ApplicationRestartRequiredException if [initAsync] was called and the native bundle
     * was downloaded
     */
    @Throws(
        IOException::class,
        UnsupportedPlatformException::class,
        InterruptedException::class,
        CefInitializationException::class,
        IllegalStateException::class,
        ApplicationRestartRequiredException::class
    )
    suspend fun newClient(onProgress: IProgressHandler? = null): CefClient {
        return when (state.value) {
            State.New -> illegalState("Cef was not initialized.")
            State.Disposed -> illegalState("Could not create client after dispose() was called")
            State.Error -> throw checkNotNull(cefInitError) { "Error must not be null" }
            State.Initialized -> cefApp.createClient()

            State.Initializing -> {
                val added = onProgress?.let { handler ->
                    synchronized(progressLock) {
                        handler.handleProgress(progressState, progressValue)
                        progressHandlers.add(handler)
                    }
                }

                state.first { it != State.Initializing }

                if (added == true) {
                    synchronized(progressLock) {
                        progressHandlers.remove(onProgress)
                    }
                }

                return newClient(onProgress)
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Progress
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Dispatch progress [state] and [value] to [progressHandler] and [progressHandlers].
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
     * This method must be called before [initSync] or [initAsync] after which it will no longer be
     * possible to register a custom scheme.
     *
     * @see [CefSchemeRegistrar.addCustomScheme]
     *
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
     * The [factory] will be instantiated in [CefAppHandler.onContextInitialized].
     *
     * This method must be called before [initSync] or [initAsync].
     *
     * @see CefSchemeHandlerFactory
     *
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
        when (state.value) {
            State.New, State.Error -> return

            State.Initializing, State.Initialized ->
                illegalState("Could not ${lazyAction()} after CefApp started initializing.")

            State.Disposed ->
                illegalState("Could not ${lazyAction()} after CefApp is disposed.")
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

            schemeHandlers.onEach { handler ->
                cefApp.registerSchemeHandlerFactory(
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