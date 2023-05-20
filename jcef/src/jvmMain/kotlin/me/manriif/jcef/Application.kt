package me.manriif.jcef

import androidx.compose.runtime.*
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import kotlin.system.exitProcess

private val LocalApplicationDisposer = staticCompositionLocalOf<ApplicationDisposer> {
    error("CompositionLocal LocalApplicationDisposer not found.")
}

/**
 * Handler for exiting or restarting the application.
 */
interface ApplicationDisposer {

    /**
     * Exit the application.
     * The resulting behavior will be the same as [ApplicationScope.exitApplication].
     */
    fun exit()

    /**
     * Restart the application.
     * The resulting behavior will be the same as [ApplicationScope.exitApplication].
     */
    fun restart()

    companion object {
        val current: ApplicationDisposer
            @Composable
            @ReadOnlyComposable
            get() = LocalApplicationDisposer.current
    }
}

/**
 * Application that inject [ApplicationDisposer] into composition, making it able to be restarted
 * or exited from anywhere. [onExitProcess] will be invoked once on application exit only.
 *
 * @see application
 */
fun disposableApplication(
    exitProcessOnExit: Boolean = true,
    onExitProcess: (() -> Unit)? = null,
    content: @Composable ApplicationScope.() -> Unit
) {
    var restartApplication = true

    while (restartApplication) {
        restartApplication = newApplication(content)
    }

    if (exitProcessOnExit) {
        onExitProcess?.invoke()
        exitProcess(0)
    }
}

/**
 * This method returns true if the application should be restarted after a call to
 * [ApplicationDisposer.restart].
 *
 * @see application
 */
private fun newApplication(content: @Composable ApplicationScope.() -> Unit): Boolean {
    var shouldRestart = false

    application(exitProcessOnExit = false) {
        val applicationDisposer = remember(this) {
            ApplicationDisposerImpl { restart ->
                shouldRestart = restart
                exitApplication()
            }
        }

        CompositionLocalProvider(LocalApplicationDisposer provides applicationDisposer) {
            val disposer = LocalApplicationDisposer.current

            val disposerApplicationScope = remember(disposer) {
                object : ApplicationScope {
                    override fun exitApplication() = disposer.exit()
                }
            }

            disposerApplicationScope.content()
        }
    }

    return shouldRestart
}

private class ApplicationDisposerImpl(private val onExit: (restart: Boolean) -> Unit) :
    ApplicationDisposer {

    @Volatile
    private var isAlive = true

    override fun exit() = exitApplication(false)

    override fun restart() = exitApplication(true)

    private fun exitApplication(restart: Boolean) {
        check(isAlive) { "Application is no longer alive." }
        isAlive = false
        onExit(restart)
    }
}