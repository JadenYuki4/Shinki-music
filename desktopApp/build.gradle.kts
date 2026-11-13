// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation("net.java.dev.jna:jna:5.17.0")
    testImplementation(kotlin("test-junit"))
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

tasks.test {
    systemProperty("orbit.mediaKeysQA", providers.gradleProperty("mediaKeysQA").getOrElse("false"))
    systemProperty("orbit.live", providers.gradleProperty("orbitLive").getOrElse("false"))
}

compose.desktop {
    application {
        mainClass = "io.github.jadenyuki4.orbitmusic.MainKt"
        nativeDistributions.appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
        // Linux uses the distribution-provided libmpv ABI 2.

        nativeDistributions {
            outputBaseDir.set(project.layout.buildDirectory.dir("shinki-music-dist"))
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Shinki music"
            packageVersion = "5.2.7"
            // Explicit UI/source/storage modules, verified with suggestModules; never bundle all JDK modules.
            modules(
                "java.desktop",
                "java.logging",
                "java.instrument",
                "java.net.http",
                "java.prefs",
                "java.sql",
                "jdk.dynalink",
                "jdk.unsupported",
            )
        }
    }
}

if (System.getProperty("os.name").startsWith("Windows")) {
    val buildTransport = tasks.register<Exec>("buildWindowsTransport") {
        inputs.files("native/ShinkiTransport.cs", "../scripts/build-transport.ps1")
        outputs.file("resources/windows-x64/ShinkiTransport.exe")
        commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "../scripts/build-transport.ps1")
    }
    tasks.matching { it.name in setOf("prepareAppResources", "run", "test") }.configureEach { dependsOn(buildTransport) }
}

