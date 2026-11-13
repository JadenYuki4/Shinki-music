// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    android {
       namespace = "io.github.jadenyuki4.orbitmusic.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
       compilerOptions {
           jvmTarget = JvmTarget.JVM_17
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    sourceSets {
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
                implementation("com.github.teamnewpipe:NewPipeExtractor:${libs.versions.newpipe.get()}") {
                    // NewPipe uses Rhino directly; the optional JSR-223 engine targets javax.script,
                    // which is absent on Android. No extractor source references that adapter.
                    exclude(group = "org.mozilla", module = "rhino-engine")
                }
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite)
        }
        androidMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.dependencies {
            implementation(libs.sqldelight.android)
        }
        commonMain.dependencies {
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.noArg)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.sqldelight.sqlite)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.testRunner)
        }
    }
}

sqldelight {
    databases {
        create("OrbitDatabase") {
            packageName.set("io.github.jadenyuki4.orbitmusic.db")
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("orbit.live", providers.gradleProperty("orbitLive").getOrElse("false"))
}

