// Mochy Desktop: the Linux port of Mochy Music (github.com/realidkroo/mochy-music, by realidkroo).
// It compiles the app's own providers/innertube sources for the JVM and plays audio through mpv.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm").version(libs.versions.kotlin)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.compose") version "1.12.2"
}

kotlin {
    jvmToolchain(21)
    // innertube and lrclib are plain Kotlin, so the desktop build uses their sources as they are
    sourceSets.main {
        kotlin.srcDir("../providers/innertube/src/main/kotlin")
        kotlin.srcDir("../providers/lrclib/src/main/kotlin")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    // reads the browsers' cookie databases for the YouTube Music sign-in
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    // what providers/innertube needs
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.brotli)
    implementation(libs.newpipeextractor)

    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// the Android app's own fonts, icons and translated texts, copied in at build time so both apps look and read the same
tasks.processResources {
    // the window's icon: the Android launcher icon, drawn for the desktop in icons/mochy-desktop.svg
    from("icons/mochy-desktop.png") { rename { "icon.png" } }
    from("../app/src/main/res") {
        include("font/*.ttf", "raw/*.svg", "drawable/*.xml", "values*/strings.xml", "values*/mochy_strings.xml")
        into("res")
    }
}

compose.desktop.application {
    mainClass = "com.mochyy.desktop.MainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Rpm)
        packageName = "mochy-desktop"
        packageVersion = "1.0.0"
        description = "Mochy Music for Linux"
        licenseFile.set(rootProject.file("../LICENSE"))
        // ponytail: bundles the whole JRE (~bigger download), trim with suggestRuntimeModules if size matters
        includeAllModules = true
        linux {
            iconFile.set(rootProject.file("icons/mochy-desktop.png"))
            appCategory = "AudioVideo"
            shortcut = true
        }
    }
}
