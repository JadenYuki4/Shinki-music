# Phase (a) dependency inventory

The latest additions, including exact Media3, libmpv and JNA versions, are in [Phase C](PHASE_C.md).

Historical foundation inventory; see [PHASE_B.md](PHASE_B.md) for the current additions and changes. Both Android and desktop now target JVM 17; NIO desugaring preserves Android minSdk 26. NewPipe, Koin and coroutines-test are now installed; later-phase statements below describe the phase-a baseline only.

No new external runtime dependency was added. Versions below are pinned by `gradle/libs.versions.toml` and retained from the starter project; Gradle compilation verifies resolution with this toolchain. Estimates are order-of-magnitude compressed contribution, not per-library measurements: transitive graphs overlap and R8 removes unused code. Refer to measured total artifacts in VALIDATION.md.

## Direct runtime/test dependencies and exact Gradle lines

| Coordinate / version | Exact line and location | Purpose / size rationale |
| --- | --- | --- |
| `org.jetbrains.compose.runtime:runtime:1.12.2` | shared: `implementation(libs.compose.runtime)` | Compose state/recomposition; part of roughly 1–5 MiB shared UI graph before shrinking |
| `org.jetbrains.compose.foundation:foundation:1.12.2` | shared: `implementation(libs.compose.foundation)` | layouts, scrolling, shapes, drawing; same shared UI graph |
| `org.jetbrains.compose.ui:ui:1.12.2` | shared: `implementation(libs.compose.ui)` | input, rendering, semantics; same shared UI graph |
| `org.jetbrains.compose.material3:material3:1.12.0-alpha03` | shared: `implementation(libs.compose.material3)` | accessible controls; pre-existing alpha retained, evaluate stable migration before release; roughly 1–3 MiB before shrinking |
| `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0` | shared: `implementation(libs.androidx.lifecycle.viewmodelCompose)` | lifecycle-owned theme ViewModel; roughly 0.1–0.5 MiB combined lifecycle graph |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0` | shared: `implementation(libs.androidx.lifecycle.runtimeCompose)` | lifecycle-aware state consumption in subsequent features; shares lifecycle graph |
| `androidx.activity:activity-compose:1.13.0` | androidApp: `implementation(libs.androidx.activity.compose)` | native Activity Compose host + edge-to-edge; roughly 0.1–0.5 MiB graph |
| Compose Desktop `1.12.2`, platform-resolved JVM/Skiko artifacts | desktopApp: `implementation(compose.desktop.currentOs)` | native Skia renderer/window host; runtime+Skia dominate the desktop package (tens of MiB) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0` | desktopApp: `implementation(libs.kotlinx.coroutinesSwing)` | desktop main dispatcher required by lifecycle; tiny adapter, coroutine core roughly 1–2 MiB unshrunk |
| `org.jetbrains.kotlin:kotlin-test:2.4.21` | shared commonTest: `implementation(libs.kotlin.test)` | cross-platform assertions; test only, zero release bytes |
| `androidx.test:runner:1.7.0` | shared androidDeviceTest: `implementation(libs.androidx.testRunner)` | retained starter instrumentation runner; test APK only |
| `org.jetbrains.compose.ui:ui-tooling-preview:1.12.2` | androidApp and desktopApp: `implementation(libs.compose.uiToolingPreview)` | retained IDE preview annotations; negligible release footprint after R8 |
| `org.jetbrains.compose.ui:ui-tooling:1.12.2` | androidApp: `debugImplementation(libs.compose.uiTooling)` | inspector/debug previews; zero release bytes |
| Shared project | androidApp and desktopApp: `implementation(project(":shared"))` | first-party shared code |

Kotlin stdlib 2.4.21 is added by the Kotlin plugin. Compose, Material and lifecycle bring platform-specific AndroidX, coroutine and Skiko transitives. **The table lists every declared dependency, not a fabricated flattened transitive bill of materials.** Obtain the full resolved graph with:

```powershell
.\gradlew.bat :desktopApp:dependencies --configuration runtimeClasspath
.\gradlew.bat :androidApp:dependencies --configuration releaseRuntimeClasspath
```

The catalog contains additional unused starter aliases (AppCompat, Espresso, core-ktx, JUnit, resources). An unused alias installs nothing. Shared release tooling dependencies were removed; Android debug tooling remains debug-only. `kotlin-test` supplies the platform test integration through Gradle variant resolution.

## Build-only plugins/tools

| Tool | Version | Exact declaration |
| --- | --- | --- |
| Kotlin Multiplatform | 2.4.21 | root/shared: `alias(libs.plugins.kotlinMultiplatform)` (root `apply false`) |
| Kotlin JVM | 2.4.21 | root/desktopApp: `alias(libs.plugins.kotlinJvm)` (root `apply false`) |
| Compose compiler | 2.4.21 | `alias(libs.plugins.composeCompiler)` |
| Compose Multiplatform | 1.12.2 | `alias(libs.plugins.composeMultiplatform)` |
| Android application | 9.1.1 | `alias(libs.plugins.androidApplication)` |
| Android KMP library | 9.1.1 | `alias(libs.plugins.androidMultiplatformLibrary)` |
| Foojay toolchain resolver | 1.0.0 | settings: `id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"` |
| Gradle wrapper | 9.5.1 | `distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip` |
| Android SDK | compile/target 37, min 26 | `compileSdk = libs.versions.android.compileSdk.get().toInt()`; analogous min/target lines |
| Desktop JDK | 17 toolchain | `kotlin { jvmToolchain(17) }` |

Shared Android bytecode targets JVM 11; desktop targets JVM 17. Building AGP 9 requires an appropriate Gradle runtime JDK (use JDK 21); packaging needs a full JDK with `jmods`, `jlink` and `jpackage`. No global package installation is required for a.

Later-phase libraries are intentionally absent: NewPipe, Koin, SQLDelight, settings, Media3, serialization, image loader and desktop media bridge get their own exact pinned inventory and size review when approved. No dependency is silently committed in advance.
