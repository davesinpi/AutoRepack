plugins {
    id("com.android.application")
}

android {
    namespace = "com.autorepack"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.autorepack"
        minSdk = 29
        targetSdk = 34
        versionCode = 8
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload build: signed with the local debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Google's APK signing library (v1/v2/v3 signatures + zip alignment), pure Java.
    implementation("com.android.tools.build:apksig:9.4.1")
}
