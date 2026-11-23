// Standalone build so the desktop app never needs the Android SDK.
// Run it from the repo root with: ./gradlew -p desktop run
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven { setUrl("https://jitpack.io") }
    }
    // same versions as the Android app, so both stay in sync
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "mochy-desktop"
