plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dad.tablet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dad.tablet"
        minSdk = 24
        targetSdk = 34
        // CI passes -PverCode=<run number> so each build is seen as newer.
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0.${(project.findProperty("verCode") as String?) ?: "0"}"
    }

    signingConfigs {
        create("app") {
            storeFile = rootProject.file("dad.keystore")
            storePassword = "dadpass"
            keyAlias = "dad"
            keyPassword = "dadpass"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    // Lightweight embedded HTTP server for same-WiFi control.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
