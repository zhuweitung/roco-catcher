import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val signingPropsFile = rootProject.file("keystore.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) {
        signingPropsFile.inputStream().use { load(it) }
    }
}

fun prop(name: String, default: String = ""): String {
    return (signingProps.getProperty(name) ?: default).trim()
}

android {
    namespace = "com.roco.catcher"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.roco.catcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.9"
    }

    signingConfigs {
        // Fixed project keystore so debug/release installs can upgrade each other.
        // Passwords live in root keystore.properties (gitignored).
        create("shared") {
            check(signingPropsFile.exists()) {
                "Missing keystore.properties. Copy keystore.properties.example and fill secrets."
            }
            val storePath = prop("storeFile")
            val storePwd = prop("storePassword")
            val alias = prop("keyAlias")
            val keyPwd = prop("keyPassword")
            check(storePath.isNotEmpty() && storePwd.isNotEmpty() && alias.isNotEmpty() && keyPwd.isNotEmpty()) {
                "keystore.properties is incomplete. Required: storeFile, storePassword, keyAlias, keyPassword."
            }
            storeFile = rootProject.file(storePath)
            storePassword = storePwd
            keyAlias = alias
            keyPassword = keyPwd
            check(storeFile?.exists() == true) {
                "Keystore file not found: ${storeFile?.absolutePath}"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    applicationVariants.all {
        outputs.all {
            val output = this as BaseVariantOutputImpl
            output.outputFileName = "$applicationId-v$versionName.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
