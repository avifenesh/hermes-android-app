import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProperties = providers.gradleProperty("hermesSigningProperties").orElse("keystore.properties")
val ks = Properties().apply { rootProject.file(signingProperties.get()).takeIf { it.exists() }?.inputStream()?.use { load(it) } }
val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !ks.getProperty(it).isNullOrBlank() }
val verifyReleaseSigning by tasks.registering {
    doLast {
        check(hasReleaseKey) { "Release signing is required. Configure keystore.properties; use assembleDebug for local unsigned-by-publisher testing." }
        check(file(ks.getProperty("storeFile")).isFile) { "Configured release keystore is unavailable." }
    }
}
tasks.configureEach {
    if (name.matches(Regex("pre.*ReleaseBuild"))) dependsOn(verifyReleaseSigning)
}

android {
    namespace = "dev.avifenesh.hermes"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "dev.avifenesh.hermes"
        minSdk = 31
        targetSdk = 36
        versionCode = 39
        versionName = "0.16.7"
        testInstrumentationRunner = if (providers.gradleProperty("hermesTestBuildType").orNull == "release")
            "dev.avifenesh.hermes.ReleaseLinkageProbe" else "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DANDROID_STL=c++_shared",
                    "-DHERMES_WAKE_REFERENCE_TESTS=${if (providers.gradleProperty("hermesWakeReferenceTests").orNull == "true") "ON" else "OFF"}")
            }
        }
    }
    // Source sets, not server flags: Play never compiles autonomous phone control.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // Preserve the installed sideload application's package and signing identity.
        }
        create("play") {
            dimension = "distribution"
            // Development coexistence identity; owner must approve before first Console upload.
            applicationIdSuffix = ".play"
        }
    }
    signingConfigs {
        if (hasReleaseKey) create("rel") {
            storeFile = file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    // Instrument the actual minified artifact when qualifying a release.
    testBuildType = providers.gradleProperty("hermesTestBuildType").orElse("debug").get()
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release tasks fail closed without publisher signing. Fresh clones use assembleDebug.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("rel") else null
            // Universal sideload build: ARM phones/tablets and x86 Android devices.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    androidResources { noCompress += "tflite" }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/microwakeword/CMakeLists.txt"); version = "3.22.1" }
    }
    lint { checkReleaseBuilds = true; abortOnError = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.0")
    implementation("com.google.firebase:firebase-messaging:25.1.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
