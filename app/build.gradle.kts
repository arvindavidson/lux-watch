plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.luxwatch.gadget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luxwatch.gadget"
        minSdk = 30 // Wear OS 3.0 (Galaxy Watch 4 launched on 3)
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // What goes on the watch: shrunk and optimised by R8 and not debuggable
        // (Compose runs several times faster, and its baseline profiles apply),
        // but the same package and key as debug, so it installs over a debug
        // build and keeps the pairing.
        create("optimized") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests {
            // Robolectric renders the Compose screens for the screenshot tests.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Protocol client: Noise, framing, Muse API. Brings OkHttp, Bouncy Castle
    // (X25519 — Android's bundled BC is stripped and doesn't reliably expose
    // it on Wear OS 3) and coroutines-core as api dependencies.
    implementation(project(":gadget"))

    // Wear OS Compose UI
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Tile + complication
    implementation("androidx.wear.tiles:tiles:1.4.0")
    // ListenableFuture helper for the Tile (tiles only has it at runtime scope).
    implementation("androidx.concurrent:concurrent-futures:1.1.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.0")
    implementation("androidx.wear.watchface:watchface:1.2.0")
    implementation("androidx.wear.watchface:watchface-complications-rendering:1.2.0")
    implementation("androidx.wear.watchface:watchface-editor:1.2.0")
    implementation("androidx.wear:wear-input:1.1.0")
    // QR code for the "Send token to watch" page (encoder only after R8).
    implementation("com.google.zxing:core:3.5.3")

    // Encrypted storage for identity.json / pairing.json equivalents
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Screenshot tests of the watch screens (JVM, via Robolectric native graphics).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.0")
}
