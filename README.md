
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
# jcef-compose

Jcef-compose is a small API targeting jvm that offers the ability to embed a [CEF Browser](https://github.com/chromiumembedded/java-cef) as a [Composable](https://github.com/JetBrains/compose-multiplatform/blob/master/README.md?plain=1).
The project was born because of [Swing interoperability](https://github.com/JetBrains/compose-multiplatform/tree/master/tutorials/Swing_Integration) limitations; indeed, it is currently not possible to compose on top of a swing component ([#1521](https://github.com/JetBrains/compose-multiplatform/issues/1521), [#2926](https://github.com/JetBrains/compose-multiplatform/issues/2926)).

Initially, the goal was to take advantage of the CEF OSR feature to render in Canvas
like in [this experimental project](https://github.com/JetBrains/compose-multiplatform/tree/d44114d8b92669d1a15c1e979b91d221fa5253f3/experimental/cef/src/main/kotlin/org/jetbrains/compose/desktop/browser),
but the performance was not up to par.\
Ultimately, the project focuses on making it easy to integrate JCEF into a new or existing application using [jcefmaven](https://github.com/jcefmaven/jcefmaven#readme)
while providing a lightweight and robust API.

Waiting for the JetBrains team and other compose contributors to provide an answer to Swing interoperability issue(s)
(they will, sure).

## Requirements

* Java 11 or later

## Supported platforms

* Windows, OSR is not supported on arm64
* macOS
* Linux

## Installation

Coming soon

## How to use

Synchronous [`Cef`](jcef/src/jvmMain/kotlin/me/manriif/jcef/Cef.kt) initialization example:

```kotlin
fun main() {
    // https://github.com/JetBrains/compose-multiplatform/issues/2939
    // The issue above will affect the window resizing and closing
    Cef.initSync()

    singleWindowApplication(
        title = "CEF Compose Browser",
        exitProcessOnExit = false
    ) {
        CefBrowserCompose(
            url = remember { "https://github.com/Manriif/jcef-compose" },
            window = window,
            initContent = { CefInitProgressContent(it) },
            errorContent = { CefInitErrorContent(it) }
        )
    }

    Cef.dispose()
    exitProcess(0)
}
```

Asynchronous [`Cef`](jcef/src/jvmMain/kotlin/me/manriif/jcef/Cef.kt) initialization example:

```kotlin
fun main() = disposableSingleWindowApplication(
    title = "CEF Awt Browser",
    onExitProcess = Cef::dispose
) {
    val applicationDisposer = ApplicationDisposer.current

    // https://github.com/JetBrains/compose-multiplatform/issues/2939
    // Initializing asynchronously here fix the issue above without blocking the main thread
    Cef.initAsync(onRestartRequired = applicationDisposer::restart)

    CefBrowserAwt(
        url = remember { "https://github.com/Manriif/jcef-compose" },
        initContent = { CefInitProgressContent(it) },
        errorContent = { CefInitErrorContent(it) }
    )
}
```

Ready to run examples are available [here](example/src/jvmMain/kotlin/me/manriif/example).\
These examples can be run on IntelliJ through run configurations.

<img src="readme/run_configurations.png" alt="run / debug configurations" height="200px">

Or as gradle tasks in the terminal:

* `./gradlew awt-browser`, example usage of [`CefBrowserAwt`](jcef/src/jvmMain/kotlin/me/manriif/jcef/CefBrowser.kt).
* `./gradlew compose-browser`, example usage of [`CefBrowserCompose`](jcef/src/jvmMain/kotlin/me/manriif/jcef/CefBrowser.kt).
* `./gradlew local-awt-browser`, example usage of [`CefBrowserAwt`](jcef/src/jvmMain/kotlin/me/manriif/jcef/CefBrowser.kt) with local HTML, CSS and JS files.
* `./gradlew local-compose-browser`, example usage of [`CefBrowserCompose`](jcef/src/jvmMain/kotlin/me/manriif/jcef/CefBrowser.kt) with local HTML, CSS and JS files.

## Limitations

Jcef limitations apply to this project, see:

* [java-cef](https://github.com/chromiumembedded/java-cef/#readme)
* [jcefmaven](https://github.com/jcefmaven/jcefmaven#readme) 

## Contributing

Feel free to contribute!!





