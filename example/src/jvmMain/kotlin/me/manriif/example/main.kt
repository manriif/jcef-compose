package me.manriif.example

private enum class Example(runnable: Runnable) : Runnable by runnable {
    AwtBrowser(::awtBrowser),
    ComposeBrowser(::composeBrowser),
    LocalAwtBrowser(::localAwtBrowser),
    LocalComposeBrowser(::localComposeBrowser)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        return Example.AwtBrowser.run()
    }

    val arg = args[0]
    val pascalCaseArg = arg.fromDashCaseToPascalCase()

    val example = try {
        Example.valueOf(pascalCaseArg)
    } catch (exception: IllegalArgumentException) {
        val validExamples = Example.values().map { it.name.fromPascalCaseToDashCase() }
        error("$arg is not a valid example value. Valid values are: $validExamples")
    }

    example.run()
}

///////////////////////////////////////////////////////////////////////////
// Case
///////////////////////////////////////////////////////////////////////////

private fun String.fromDashCaseToPascalCase(): String {
    if (isEmpty()) {
        return this
    }

    val base = if (get(0).isUpperCase()) this else {
        replaceFirstChar { it.uppercaseChar() }
    }

    return base.replace("""-[a-z]""".toRegex()) { it.value.last().uppercase() }
}

private fun String.fromPascalCaseToDashCase(): String = buildString {
    this@fromPascalCaseToDashCase.forEachIndexed { index, char ->
        if (index > 0 && char.isUpperCase()) {
            append("-")
        }

        append(char.lowercaseChar())
    }
}