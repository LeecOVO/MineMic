import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val generateAppVersion by tasks.registering(Sync::class) {
    val appVersion = rootProject.extra["mineMicVersion"] as String
    inputs.property("appVersion", appVersion)
    from("src/versionTemplate") { expand("appVersion" to appVersion) }
    into(layout.buildDirectory.dir("generated/minemicVersion"))
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        val buildApm = tasks.register<Exec>("buildApm${iosTarget.name.replaceFirstChar { it.uppercase() }}") {
            commandLine("python3", rootProject.file("scripts/build-apm.py"), "--target", iosTarget.name)
        }
        val apmInterop = iosTarget.compilations.getByName("main").cinterops.create("minemic_apm") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/minemic_apm.def"))
            includeDirs(rootProject.file("native/include"))
            extraOpts("-staticLibrary", "libminemic_apm.a", "-libraryPath", rootProject.file("native/build/${iosTarget.name}").absolutePath)
        }
        tasks.named(apmInterop.interopProcessingTaskName).configure { dependsOn(buildApm) }
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            linkerOpts("-lc++")
        }
    }

    jvm()

    android {
        namespace = "com.aiaixyz.minemic.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain { kotlin.srcDir(generateAppVersion) }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
