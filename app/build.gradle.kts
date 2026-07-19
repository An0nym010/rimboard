plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rimboard.keyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rimboard.keyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "2.8.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Real release signing, configured out-of-band so no key or password is
        // ever committed. Set these in ~/.gradle/gradle.properties or as env
        // vars (RIMBOARD_KEYSTORE / _PASSWORD / _KEY_ALIAS / _KEY_PASSWORD).
        // Without them the release build simply falls back to the debug key.
        create("release") {
            val store = (project.findProperty("rimboard.keystore") as String?)
                ?: System.getenv("RIMBOARD_KEYSTORE")
            if (store != null && file(store).exists()) {
                storeFile = file(store)
                storePassword = (project.findProperty("rimboard.storePassword") as String?)
                    ?: System.getenv("RIMBOARD_PASSWORD")
                keyAlias = (project.findProperty("rimboard.keyAlias") as String?)
                    ?: System.getenv("RIMBOARD_KEY_ALIAS")
                keyPassword = (project.findProperty("rimboard.keyPassword") as String?)
                    ?: System.getenv("RIMBOARD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig =
                if (signingConfigs.getByName("release").storeFile != null)
                    signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
}
