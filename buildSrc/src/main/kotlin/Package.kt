import org.gradle.api.Project

val Project.packageName: String
    get() = requireProperty("package.name")

val Project.packageGroup: String
    get() = requireProperty("package.group")

val Project.packageVersion: String
    get() = requireProperty("package.version")

private fun Project.requireProperty(name: String): String {
    return checkNotNull(findProperty(name) as? String) {
        "Property $name is missing from gradle.properties file."
    }
}