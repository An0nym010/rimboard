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

    lint {
        // An unused string is how a removed preference leaves a trace: the
        // title, summary and option list stay behind, still translated into
        // every language, while the accessor that fed them sits in Prefs
        // looking live. Twenty-one of these had accumulated as a warning
        // nobody read. Failing the build keeps that from happening again.
        error += "UnusedResources"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

/**
 * The unit tests read res/ and assets/ straight off disk rather than through
 * generated R fields — that is what lets them run on a plain JVM with no
 * device. Gradle cannot see those reads, so it had no reason to believe the
 * test task was out of date when only a resource changed: editing arrays.xml
 * and running the tests reported UP-TO-DATE and told you nothing, in green.
 *
 * Declaring the directories as inputs costs one hash of each tree, which
 * Gradle then caches; assets is 40 MB of dictionaries but only changes when
 * tools/fetch_dictionaries.py is re-run, which is exactly when AssetsTest
 * ought to run again.
 */
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/assets").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    // Already arrives transitively via material, but the toolbar picker depends
    // on it directly (ItemTouchHelper), so pin it rather than inherit it.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
