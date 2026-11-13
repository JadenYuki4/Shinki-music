# Shinki music de JadenYuki4

Una app para escuchar musica y que tenga mis delirios.

En **Ajustes** podés buscar la última versión estable en GitHub Releases, copiar un diagnóstico local y editar preferencias de artistas, grupos y géneros. Los Me gusta de canciones se administran en Favoritos. El bloqueo de géneros depende del texto disponible en el título o la búsqueda. La versión y el número de compilación se definen en `gradle.properties`.

Personal, open-source, ad-free music player foundation for Android, Linux and Windows, using Kotlin + Compose Multiplatform. The primary UX reference is [Limusic v1.3.0](https://github.com/SimoHypers/limusic/releases/tag/v1.3.0), as selected by the user.

Search songs, albums, artists and playlists, then select a song to play it. Android uses Media3, and Linux and Windows use libmpv. The responsive cyberpunk player includes a persistent transport, seek bar, expandable player and editable theme presets. The wide layout retains Limusic’s sidebar and right panel.

Theme edits are session-only. Full custom token editing, theme JSON, art-derived colors, custom backgrounds and preference persistence arrive in (e). Desktop OS reduced-motion detection is also pending; the app's explicit toggle works on all targets. Android reads the animator preference at launch and on resume. NewPipe initializes lazily on the first request.

## Ediciones

| Versión | Nombre de edición |
| --- | --- |
| 1.0 (versión inicial de Android) | Origen |
| 5.2.7 | Pulso |
| 5.2.8 | Órbita |
| 5.3.0 (compilación 168) | Aurora |

Los nombres de las ediciones anteriores son retrospectivos. Las etiquetas y descargas publicadas conservan sus números originales.

## Project documentation

- [Queue controls, discovery algorithm, playlists and validation](docs/PHASE_D.md)

- [Plan, architecture, folder structure and phase gates](docs/ARCHITECTURE.md)
- [Every direct dependency: versions, exact Gradle lines and size rationale](docs/DEPENDENCIES.md)
- [Phase (b): data architecture, added dependencies and test instructions](docs/PHASE_B.md)
- [Phase (c): playback, native dependencies and validation](docs/PHASE_C.md)
- [Resolved Desktop dependency tree](docs/desktop-runtime-dependencies.txt) and [Android tree](docs/android-runtime-dependencies.txt)
- [Validation, size report and performance checklist](docs/VALIDATION.md)
- [Limusic design reference and scope](docs/DESIGN_REFERENCE.md)
- [License notice](NOTICE.md) and [full GPLv3 text](LICENSE)

Complete source files are in the workspace; no pseudocode or manual copy step is required. Shared code lives in `shared/src/commonMain/kotlin/io/github/jadenyuki4/orbitmusic`, with Android/Desktop entry points in their respective modules. See the architecture document's file index.

## Prerequisites

Use Android Studio with Kotlin Multiplatform support, Android SDK platform 37, and a full JDK 21 as the Gradle runtime. Desktop code targets JDK 17; Gradle's Foojay resolver can provision its compiler toolchain. Native packaging needs a full JDK with `jmods`, `jlink`, and `jpackage`; some Android Studio JBR distributions omit `jmods`. Set `JAVA_HOME` to that full JDK. Windows MSI packaging uses WiX (the Compose plugin provisions it here). Android supports API 26+.

Do not commit `local.properties`. Let Android Studio create it with your SDK path, or set `ANDROID_HOME`. The Gradle wrapper pins its distribution and SHA-256. Commands below run from the repository root. On Windows use PowerShell and `.\gradlew.bat`; on Linux use `./gradlew` (run `chmod +x gradlew` if the checkout lost its executable bit).

## Android

```powershell
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:installDebug
.\gradlew.bat :androidApp:assembleRelease
```

Use an attached device/emulator for installDebug, or run androidApp in Android Studio. APKs appear under `androidApp/build/outputs/apk/`. Release is R8-minified/resource-shrunk and **unsigned**; sign with your own key before installation/distribution. No keystore or secret is embedded.

## Windows 10/11 desktop

```powershell
.\scripts\prepare-libmpv.ps1
.\gradlew.bat :desktopApp:run
.\gradlew.bat :desktopApp:createDistributable
.\gradlew.bat :desktopApp:packageMsi
```

The portable app image is in `desktopApp/build/compose/binaries/main/app/OrbitMusic/`; the MSI is in `desktopApp/build/compose/binaries/main/msi/`. The app image includes its jlink-trimmed runtime, so users do not need Java installed. MSI installation has not been tested on a clean VM yet.

## Linux desktop

```sh
sudo apt install libmpv2 python3-gi gir1.2-glib-2.0
./gradlew :desktopApp:run
./gradlew :desktopApp:createDistributable
./gradlew :desktopApp:packageDeb
```

Build on Linux with a graphical X11/Wayland-compatible desktop and the native packaging prerequisites from your distribution (including `fakeroot` for DEB tooling where required). Output is in `desktopApp/build/compose/binaries/main/deb/`. Linux native packages cannot be built by the Windows host. AppImage tooling and the GitHub Actions Android/Linux/Windows artifact matrix are scheduled for phase (f); no AppImage is claimed in (a).

## Tests

```powershell
.\gradlew.bat :shared:jvmTest :androidApp:assembleDebug :androidApp:assembleRelease :desktopApp:classes
.\gradlew.bat :desktopApp:suggestModules
```

Run common theme/controller tests and JVM data-adapter tests, then follow the manual/profiling checklist. Normal tests skip the real-network test. To opt in: `.\gradlew.bat :shared:jvmTest --tests '*LiveSourceSmokeTest' -PorbitLive=true`. No CPU, RAM or cold-start claim is made from compilation alone.

## Intended use and disclaimer

This project is intended for personal and educational use. Third-party YouTube Music access may violate YouTube's Terms of Service. It is not affiliated with, endorsed by, or sponsored by YouTube or Google. It must not circumvent DRM, harvest credentials, or bypass paid-subscription restrictions. Respect applicable service terms and content rights.

Licensed under **GPL-3.0-or-later**, as chosen by the project owner. The intended-use statement does not restrict the rights granted by the GPL. Third-party components retain their licenses; see the Phase (b) dependency/license notes for the extractor.

Open a track from the bottom player for shuffle, repeat, speed, volume, sleep timer, lyrics and recommendation autoplay. Home adapts to recent searches, listens and favorites. Library manages playlists, JSON exchange, incognito and history removal.
