import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

/**
 * Vosk's small Indian-English model is ~36 MB, so it is downloaded once at
 * build time and verified against a pinned SHA-256 before it is unpacked into
 * main assets. This keeps the model out of git while making builds reproducible.
 */
val voskModelName = "vosk-model-small-en-in-0.4"
val voskSha256 = "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7"
val voskAssetsDir = layout.buildDirectory.dir("generated/vosk-assets")

val fetchVoskModel by tasks.registering {
    outputs.dir(voskAssetsDir)
    doLast {
        val destination = voskAssetsDir.get().asFile
        if (File(destination, "vosk/am/final.mdl").exists()) return@doLast
        destination.mkdirs()
        val archive = temporaryDir.resolve("$voskModelName.zip")
        uri("https://alphacephei.com/vosk/models/$voskModelName.zip").toURL()
            .openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == voskSha256) {
            "Vosk model checksum mismatch. Expected $voskSha256, got $digest"
        }
        copy {
            from(zipTree(archive))
            into(destination)
            eachFile { path = path.replaceFirst(voskModelName, "vosk") }
        }
    }
}

android {
    namespace = "com.orin.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.orin.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v2 covers API 24+, v3 covers API 28+ with key rotation support.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "UnusedResources",
        )
    }

    sourceSets {
        getByName("main").assets.srcDir(voskAssetsDir.get().asFile)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchVoskModel) }

/**
 * Refuse to produce a release artifact that is not signed. Shipping an unsigned
 * APK is never intentional, so make it a hard build failure.
 */
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(keystorePropertiesFile.exists()) {
            "Release signing is not configured. Create keystore.properties and signing/orin-release.jks."
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.vosk.android)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
