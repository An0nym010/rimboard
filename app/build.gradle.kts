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
        versionCode = 24
        versionName = "2.9.0"
    }

    /**
     * Two builds from one source tree, split on whether the APK asks for
     * INTERNET at all.
     *
     * This is a dimension rather than a setting because `INTERNET` is a normal
     * install-time permission: once it is in the manifest it is granted at
     * install and neither the user nor the app can ever take it back. An
     * in-app "offline mode" on a build that holds the permission is a promise
     * the app makes about itself. Leaving the permission out is a fact about
     * the APK that anyone can check with `aapt dump permissions`, and that the
     * kernel enforces against us whether or not our code is honest.
     *
     * So: `offline` ships no permission and no network backend, and is the
     * build the README points at when it says the keyboard cannot phone home.
     * `online` adds the permission plus the features that need it, and its
     * offline switch is enforced in code — a weaker guarantee, labelled as one
     * wherever it is shown to the user.
     */
    flavorDimensions += "net"

    productFlavors {
        create("offline") {
            dimension = "net"
            // Surfaced in Settings → About → Version, so a screenshot of the
            // version is enough to tell the two builds apart.
            versionNameSuffix = "-offline"
        }
        create("online") {
            dimension = "net"
            // Its own applicationId, so the two builds are two apps: they can
            // be installed side by side, and each can be listed separately.
            // Sharing one meant installing either replaced the other and only
            // one could ever reach a store.
            //
            // The suffix goes on `online` rather than on `offline` because
            // offline is the build the README sends people to first, and it
            // therefore keeps the plain name. Anyone already carrying an online
            // build will have to install this one fresh; that is the price of
            // doing this at all, and it is smallest now, while every release so
            // far has been a debug-signed pre-release.
            applicationIdSuffix = ".online"
            versionNameSuffix = "-online"
        }
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

    testOptions {
        // Unmocked android.* stubs return 0/null in unit tests instead of
        // throwing "not mocked". The engine touches SystemClock.elapsedRealtime
        // and android.util.Log purely to time and log its data loads — nothing
        // the ranking tests care about — and without this that timing call
        // aborts every test that loads a dictionary.
        unitTests.isReturnDefaultValues = true
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
    // This file too, because TargetSdkInsetsTest reads targetSdk out of it.
    // Without the declaration the test is up-to-date across the one edit it
    // exists to catch -- a target-SDK bump changes no Kotlin, so the task
    // never re-runs and the ratchet is decoration. Found by making the bump
    // and watching nothing happen.
    inputs.file("build.gradle.kts").withPathSensitivity(PathSensitivity.RELATIVE)
    // The flavour resource dirs, because LauncherIconTest reads the per-build
    // launcher wordmarks out of them. Same reason as the line above: an icon
    // edit changes no Kotlin, so without this the ratchet is up to date across
    // exactly the change it exists to catch.
    inputs.dir("src/offline/res").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/online/res").withPathSensitivity(PathSensitivity.RELATIVE)
    // MorphologyGuardTest's corpus of ordinary Turkish. `src/test/fixtures` is
    // not a source-set directory, so without this Gradle does not know the
    // file exists: it was replaced with two junk words and the task reported
    // UP-TO-DATE with the suite green. Third time this trap has been walked
    // into here, and the first where the file the test depends on was added in
    // the same commit as the test.
    inputs.dir("src/test/fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    // The two documents at the project root, because ReadmeVersionTest reads
    // the version out of them. Fourth time, and found the same way as the
    // third: the test was written, the README was staled by hand to watch it
    // fail, and it passed -- a markdown edit changes no Kotlin, so the task was
    // up to date across exactly the change the test exists to catch. They are
    // above the module, hence the `..`.
    inputs.file("../README.md").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("../CHANGELOG.md").withPathSensitivity(PathSensitivity.RELATIVE)
    // The manifests, because NetGateTest reads all three and they are the
    // repository's most important ratchet: "No INTERNET in the offline build,
    // which is what makes its guarantee a guarantee rather than a promise."
    //
    // Fifth time, and the worst of the five. A manifest lives beside `res`
    // rather than inside it, so the declarations above did not reach it and a
    // manifest edit changes no Kotlin. Demonstrated rather than reasoned:
    // INTERNET was added to src/main/AndroidManifest.xml and the suite was run
    // without --rerun-tasks. NetGateTest reported six tests and no failures.
    //
    // `src/offline` is declared as a directory rather than a file because the
    // guarantee there is an *absence* -- NetGateTest asserts that the offline
    // flavor contributes no manifest at all, and a file that does not exist
    // cannot be declared as an input. A manifest appearing in that directory
    // marks the task out of date, which is the event worth catching.
    inputs.file("src/main/AndroidManifest.xml").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/online/AndroidManifest.xml").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/offline").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Test classpath only, and it never reaches the APK. The android.jar the
    // unit tests compile against ships org.json as a stub whose every method
    // throws "Stub!", so any test that parses a response body fails for a
    // reason that has nothing to do with the code under test. This puts a real
    // implementation in front of the stub; the app itself keeps using the one
    // built into Android.
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    // Reads the orientation tag from picked photos. Camera images are stored
    // rotated with an EXIF flag; decoding without honouring it put portrait
    // photos on the keyboard sideways.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Already arrives transitively via material, but the toolbar picker depends
    // on it directly (ItemTouchHelper), so pin it rather than inherit it.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Builds the style bundle an inline autofill request carries, so the
    // password manager's chips are drawn in this keyboard's colours rather
    // than the platform default. Nothing else in the library is used, and it
    // adds no permission: inline suggestions are handed over by the system.
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
}
