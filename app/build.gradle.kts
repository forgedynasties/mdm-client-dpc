plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build-time defaults for server URL + device API key. Override without editing source via
// gradle.properties / -P flags / local.properties, e.g.:
//   ./gradlew assembleDebug -PmdmServerUrl=http://10.0.2.2:8080 -PmdmApiKey=dev-device-key
// These are only *defaults*; the onboarding screen can override them at runtime (stored in prefs).
val mdmServerUrl: String = (project.findProperty("mdmServerUrl") as String?) ?: "https://mdm.example.com"
val mdmApiKey: String = (project.findProperty("mdmApiKey") as String?) ?: ""

android {
    namespace = "aio.app.mdmclient.dpc"
    compileSdk = 35

    defaultConfig {
        applicationId = "aio.app.mdmclient.dpc"
        minSdk = 28
        targetSdk = 35
        versionCode = 54
        versionName = "0.15.0"

        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$mdmServerUrl\"")
        buildConfigField("String", "DEFAULT_API_KEY", "\"$mdmApiKey\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // Release signing from the environment (KEYSTORE_FILE / KEYSTORE_PASS / KEY_ALIAS /
    // KEY_PASS); without them the release build is unsigned. The key must never change:
    // Android only updates an app whose new APK is signed with the same certificate.
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = System.getenv("KEY_ALIAS") ?: "aio-mdm-dpc"
                keyPassword = System.getenv("KEY_PASS") ?: System.getenv("KEYSTORE_PASS")
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (System.getenv("KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        // The agent is a Device Owner: it grants itself location at runtime
        // (DeviceOwner.ensureLocationAccess) and receives READ_LOGS, READ_DROPBOX_DATA and
        // the rest from tools/enroll-adb.sh. Lint only reads the manifest, so every one of
        // those calls looks unpermitted to it. Downgraded to a warning deliberately, so the
        // remaining lint errors stay meaningful enough to fail the build on.
        warning += "MissingPermission"
        abortOnError = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Transport: OkHttp gives us HTTP + WebSocket + gzip + retries, replacing the hand-rolled
    // RFC-6455 client used by the system-app client.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3") // S3: the badge's QR to the dashboard page

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Lightweight JSON via org.json (bundled in Android) — no extra dep needed for now.

    // JVM unit tests: the decisions that must not be wrong (UpdateCheck) are kept free
    // of Android types precisely so they can be tested without a device or Robolectric.
    testImplementation("junit:junit:4.13.2")
    // The real org.json: android.jar's copy is stubs that throw under a JVM test.
    testImplementation("org.json:json:20240303")
}
