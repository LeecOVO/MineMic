plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

// Xcode and Gradle share the same version source.
val mineMicVersionConfig = providers.fileContents(layout.projectDirectory.file("version.xcconfig"))
    .asText.get().lineSequence().filter { '=' in it && !it.trimStart().startsWith("//") }
    .associate { line -> line.split('=', limit = 2).let { it[0].trim() to it[1].trim() } }
extra["mineMicVersion"] = mineMicVersionConfig.getValue("MINEMIC_VERSION")
extra["mineMicBuild"] = mineMicVersionConfig.getValue("CURRENT_PROJECT_VERSION").toInt()
