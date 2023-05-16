import org.jetbrains.gradle.ext.Gradle
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings

plugins {
    `common-jvm`
    org.jetbrains.gradle.plugin.`idea-ext`
}

dependencies {
    jvmMainImplementation(project(":jcef"))
}

listOf(
    "advanced-browser",
    "local-browser",
    "simple-browser",
).forEach { exampleName ->
    val task = tasks.create<JavaExec>(exampleName) {
        mainClass.set("me.manriif.example.MainKt")

        val kotlinJvm = kotlin.jvm()
        val compilation = kotlinJvm.compilations.getByName("main")
        val jar = tasks.getByName(kotlinJvm.artifactsTaskName)

        dependsOn(jar)
        classpath((jar as Jar).archiveFile.get().asFile)
        classpath(compilation.runtimeDependencyFiles)
        args(exampleName)
    }

    rootProject.idea.project?.settings?.runConfigurations?.create<Gradle>(exampleName) {
        projectPath = project.projectDir.absolutePath
        taskNames = listOf(task.name)
    }
}