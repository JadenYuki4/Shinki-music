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
    systemProperty("orbit.live", providers.gradleProperty("orbitLive").getOrElse("false"))
}

compose.desktop {
    application {
        mainClass = "com.example.myapplication.MainKt"
        nativeDistributions.appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
        // Linux uses the distribution-provided libmpv ABI 2.

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "OrbitMusic"
            packageVersion = "1.0.0"
            // Explicit UI/source modules, verified with suggestModules; never bundle all JDK modules.
            modules("java.desktop", "java.logging", "java.instrument", "java.net.http", "jdk.dynalink", "jdk.unsupported")
        }
    }
}
