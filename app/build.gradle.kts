plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val apiBaseUrl = (providers.gradleProperty("BAZR_API_BASE_URL").orNull ?: "https://example.invalid/api/")
require(apiBaseUrl.startsWith("https://") && apiBaseUrl.endsWith("/")) {
    "BAZR_API_BASE_URL must be an HTTPS URL ending in /"
}
val releaseStorePath = providers.environmentVariable("BAZR_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("BAZR_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("BAZR_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("BAZR_KEY_PASSWORD").orNull
val hasReleaseSigning = !releaseStorePath.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()
android {
    namespace = "ir.bazr.inspection"
    compileSdk = 35
    defaultConfig {
        applicationId = "ir.bazr.inspection"
        minSdk = 30
        targetSdk = 35
        versionCode = 29
        versionName = "4.15.0"
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
