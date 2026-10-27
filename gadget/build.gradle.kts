// Pure-Kotlin Muse gadget protocol client (no Android dependencies).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // OkHttp for fetch_vms / token refresh HTTPS + the Noise WebSocket.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Bouncy Castle for X25519 (Noise handshake).
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // JSON for the realtime call events; Android has org.json built in, so it's
    // only needed to compile here (and in tests).
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// Keys avatar clip frames for res/raw (gadget/tools/make_avatar_clips.sh).
tasks.register<JavaExec>("keyFrames") {
    description = "Keys a folder of avatar frames: --args=\"IN_DIR OUT_DIR\""
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.luxwatch.gadget.tools.KeyFramesMain")
}
