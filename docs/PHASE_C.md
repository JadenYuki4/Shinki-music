# Phase C · Playback

Search for a song and select its row. Click its sleeve or title in the bottom player to expand it. The dock keeps the transport and seek bar available in both layouts. Escape closes the expanded view. On desktops with a tray, closing the window during playback hides it. Use Open Orbit Music or Quit in the tray menu.

## Source and playback ownership

NewPipe resolves the selected song on demand. Progressive HTTPS audio streams are eligible and the adapter selects the highest advertised bitrate. A new selection cancels an outstanding resolution. Retry resolves a fresh URL. Missing compatible streams, verification requests and network failures have distinct messages. Signed media URLs stay in memory.

The shared Playback controller takes a MusicSource and an AudioEngine. The Android service owns ExoPlayer and MediaSession. The Activity connects through a MediaController. Audio focus, noisy-output handling, background playback, the media notification and system transport use Media3. Windows and Linux call libmpv's C client API on one dedicated worker. The UI observes position, metadata, transport state and failure. The Windows media play/pause key uses User32. On Linux a GIO helper exposes Play, Pause and PlayPause on the session MPRIS bus. Tray availability depends on the desktop environment. Multiple-track queue management belongs to phase D.

## Dependencies added in C

| Gradle declaration | Purpose and size impact |
| --- | --- |
| `implementation("androidx.media3:media3-exoplayer:1.11.1")` | Android decoding, buffering, focus and seek. Shares Media3 common classes with the session artifact. |
| `implementation("androidx.media3:media3-session:1.11.1")` | Foreground service, media notification, system controls and controller reconnection. Together the Media3 additions increase the shrunk APK by about 1 MiB. |
| `implementation("net.java.dev.jna:jna:5.17.0")` | Desktop C ABI and Windows hotkeys. About 2 MiB in the runtime image. |
| `testImplementation(kotlin("test-junit"))` | Kotlin 2.4.21 assertions and JUnit 4 integration in desktop tests. Test classpath only. |
| `androidTestImplementation("androidx.test:runner:1.7.0")` | Android instrumented tests. Test APK only. |
| `androidTestImplementation("junit:junit:4.13.2")` | Test assertions and discovery. Test APK only. |

Windows x64 uses the exact 117,532,160-byte libmpv-2.dll from the [Limusic 1.3.0 release](https://github.com/SimoHypers/limusic/releases/tag/v1.3.0). The preparation script verifies the release MSI and DLL digests. The DLL stays outside source control and is included in the MSI. A fresh Windows checkout needs `./scripts/prepare-libmpv.ps1` before running, testing or packaging. Linux uses the distribution's ABI 2 build. On Debian 12+/Ubuntu 24.04+ install `sudo apt install libmpv2 python3-gi gir1.2-glib-2.0`. The distribution controls the patch versions of these system packages. Linux tray support uses AWT. JNA loads libmpv on first playback.

## Verification

```powershell
.\scripts\prepare-libmpv.ps1
.\gradlew.bat :shared:jvmTest :desktopApp:test -PorbitLive=true
.\gradlew.bat :androidApp:connectedDebugAndroidTest
.\gradlew.bat :androidApp:assembleDebug :androidApp:assembleRelease :desktopApp:createDistributable :desktopApp:packageMsi
```

The desktop native test creates a local WAV, uses libmpv's null output and checks decoding, pause, seek and end of file. A second opt-in test searches YouTube Music and requires the actual libmpv playback clock to advance. The instrumented Android test uses a generated WAV, moves the Activity into the background, checks the media notification, pauses and seeks. Hardware speaker output, Bluetooth interruptions, lock-screen presentation, Linux MPRIS on a graphical session and a clean-machine MSI install require manual verification.

## Phase C build sizes (Windows host, 2026-11-11)

| Artifact | Exact bytes | MiB | Status |
| --- | ---: | ---: | --- |
| Android debug APK | 18,951,545 | 18.07 | Built; debug-signed |
| Android release APK | 3,073,956 | 2.93 | R8/L8 + resource shrinking; unsigned |
| Windows x64 MSI | 115,735,129 | 110.37 | Built; includes the 112 MiB libmpv-2.dll |
| Windows portable app image | 256,529,030 | 244.64 | jlink runtime + libmpv; uncompressed |
| Linux DEB/AppImage | — | 60–95 estimated | Uses the system libmpv2; not built on this host |

The +1.0 MiB release APK increase over phase B is Media3. On Windows, libmpv accounts for almost all the desktop growth. A smaller mpv build without video/filters could reduce it in phase F.

Automated results: 27 tests pass and 2 opt-in live tests are skipped (`:shared:jvmTest :desktopApp:test`). The instrumented Android test (`connectedDebugAndroidTest`) needs a device or emulator and was not run in this session.

The record sleeve is a static, token-colored Compose drawing. Low power and reduced motion omit the gradient lighting. This phase introduces no frame-driven decorative animation, remote cover downloads or bundled fonts.
