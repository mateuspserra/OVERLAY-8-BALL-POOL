import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use(localProperties::load)
}

fun readConfig(name: String): String {
    return localProperties.getProperty(name) ?: System.getenv(name) ?: ""
}

fun escapedStringConfig(name: String): String {
    return readConfig(name)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

fun floatConfig(name: String, defaultValue: String): String {
    return readConfig(name).toFloatOrNull()?.toString() ?: defaultValue
}

fun longConfig(name: String, defaultValue: String): String {
    return readConfig(name).toLongOrNull()?.toString() ?: defaultValue
}

val releaseStoreFilePath = readConfig("RELEASE_STORE_FILE").trim()
val releaseStorePassword = readConfig("RELEASE_STORE_PASSWORD").trim()
val releaseKeyAlias = readConfig("RELEASE_KEY_ALIAS").trim()
val releaseKeyPassword = readConfig("RELEASE_KEY_PASSWORD").trim()
val releaseStoreFile = releaseStoreFilePath
    .takeIf { it.isNotEmpty() }
    ?.let { rootProject.file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true &&
    releaseStorePassword.isNotEmpty() &&
    releaseKeyAlias.isNotEmpty() &&
    releaseKeyPassword.isNotEmpty()

android {
    namespace = "com.overlaypool"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.overlaypool"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "AI_ENDPOINT", "\"${escapedStringConfig("AI_ENDPOINT")}\"")
        buildConfigField("String", "AI_API_KEY", "\"${escapedStringConfig("AI_API_KEY")}\"")
        buildConfigField("String", "AI_PROVIDER", "\"${escapedStringConfig("AI_PROVIDER").ifEmpty { "local_heuristic" }}\"")
        buildConfigField("float", "MIN_DETECTION_CONFIDENCE", "${floatConfig("MIN_DETECTION_CONFIDENCE", "0.55")}f")
        buildConfigField("long", "CAPTURE_INTERVAL_MS", "${longConfig("CAPTURE_INTERVAL_MS", "250")}L")
        buildConfigField("int", "MAX_UPLOAD_IMAGE_SIZE", readConfig("MAX_UPLOAD_IMAGE_SIZE").toIntOrNull()?.toString() ?: "960")
    }

    signingConfigs {
        getByName("debug") {
            val localDebugKeystore = rootProject.file(".tools/debug.keystore")
            if (localDebugKeystore.exists()) {
                storeFile = localDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        create("release") {
            if (hasReleaseSigning && releaseStoreFile != null) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
