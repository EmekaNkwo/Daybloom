import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        // commonTest is deliberately not part of the instrumented tree: its backtick test names contain spaces,
        // which D8 rejects below DEX 040 (minSdk 26). Shared tests run on the JVM and iOS instead.
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.addAll(
            "kotlin.time.ExperimentalTime",
            "kotlin.uuid.ExperimentalUuidApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }

    sourceSets {
        matching { it.name.startsWith("ios") }.configureEach {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.datastore.preferences)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.androidx.work.runtime)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
        }
        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.room.testing)
            implementation(libs.sqlite.framework)
            implementation(libs.androidx.work.testing)
            implementation(libs.koin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.work.testing)
            implementation(libs.sqlite.framework)
            implementation(libs.room.testing)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.daybloom.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    sourceSets["test"].assets.srcDir("$projectDir/schemas")
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    listOf(
        "kspAndroid",
        "kspIosX64",
        "kspIosArm64",
        "kspIosSimulatorArm64",
    ).forEach { add(it, libs.room.compiler) }
    debugImplementation(libs.compose.ui.tooling)
}
