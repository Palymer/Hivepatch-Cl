import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.hivepatch.client"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.hivepatch.client"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    val releaseStore = System.getenv("HIVEPATCH_RELEASE_STORE_FILE")
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("HIVEPATCH_RELEASE_STORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("HIVEPATCH_RELEASE_KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("HIVEPATCH_RELEASE_KEY_PASSWORD").orEmpty()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

val libDir = layout.projectDirectory.dir("libs")
val libAar = libDir.file("libv2ray.aar")
val libv2rayUrl =
    "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.7.28/libv2ray.aar"

tasks.register("downloadLibv2ray") {
    outputs.file(libAar)
    doLast {
        val dest = libAar.asFile
        dest.parentFile.mkdirs()
        if (dest.exists() && dest.length() > 1_000_000) return@doLast
        URI(libv2rayUrl).toURL().openStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        println("downloaded libv2ray.aar ${dest.length()} bytes")
    }
}
tasks.named("preBuild").configure { dependsOn("downloadLibv2ray") }

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
