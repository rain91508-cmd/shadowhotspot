plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shadowhotspot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shadowhotspot"
        minSdk = 26
        targetSdk = 35
        // Derive version from the repo-root VERSION file so each release is a
        // distinct, upgradeable build (avoids "reinstall same version" on adb install).
        val versionNameValue = file(rootDir, "../VERSION").readText().trim()
        val versionCodeValue = versionNameValue.split('.').let { p ->
            val m = p.getOrNull(0)?.toIntOrNull() ?: 0
            val n = p.getOrNull(1)?.toIntOrNull() ?: 0
            val x = p.getOrNull(2)?.toIntOrNull() ?: 0
            m * 10000 + n * 100 + x
        }
        versionCode = versionCodeValue
        versionName = versionNameValue

        // We only bundle the arm64 ssserver binary.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // --- Release signing ----------------------------------------------------
    // The release APK is signed only when a keystore is supplied (CI or local).
    // Credentials are read from environment variables first, then from gradle
    // properties / local.properties. Without these, `assembleRelease` produces
    // an *unsigned* APK, which Android refuses to install.
    val keystoreFile = run {
        val path = System.getenv("KEYSTORE_FILE")
            ?: project.findProperty("KEYSTORE_FILE")?.toString()
        path?.let { file(it) }?.takeIf { it.exists() }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: project.findProperty("KEYSTORE_PASSWORD")?.toString()
                    ?: error("KEYSTORE_PASSWORD is required when KEYSTORE_FILE is set")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: project.findProperty("KEY_ALIAS")?.toString()
                    ?: error("KEY_ALIAS is required when KEYSTORE_FILE is set")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: project.findProperty("KEY_PASSWORD")?.toString()
                    ?: error("KEY_PASSWORD is required when KEYSTORE_FILE is set")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
    }
    packaging {
        // Keep our ssserver "lib" uncompressed & extracted with exec permission.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // QR code generation for client import (Shadowsocks ss:// URI).
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
