# Player and discovery

Search for a song and select it to play the search results as a queue. Use **+ Queue** to append individual songs instead. Open the current track from the dock for previous/next, shuffle, repeat one/all, playback speed, output volume, sleep timer and optional recommendation autoplay. The timer can be replaced or cancelled. Reorder or remove tracks from Queue. On a phone these controls live inside the expanded player.

Home starts with the extractor's trending music feed. Once activity exists, Orbit scores up to eight recent search phrases, twelve recent artist plays and eight favorite artists. Each signal's weight decreases with its position in the history. The top three distinct terms become song searches. Orbit interleaves the results and filters out duplicates, recent plays and favorites. Each card identifies its seed. Autoplay uses this same feed only after a natural end with no eligible queued track. Incognito disables personalization and autoplay and suppresses history writes. Search requests and recommendation seed queries go to the music provider. The ranking and history stay on device.

Library stores favorites, listening history and named playlists in SQLDelight. Save the current queue under a name, play or delete a playlist, or transfer the playlist collection using editable JSON in the Import/Export dialog. Import validates the complete collection before replacing it. History clearing preserves playlists and favorites. The queue, volume, speed and timer last for the running session. Version-two schema migration preserves existing data.

Track artwork is fetched lazily. Two concurrent requests, a 256 KiB response limit and sixteen cached compressed images bound transfer memory. Decode limits reject oversized artwork. The geometric record sleeve remains the fallback. Lyrics are plain text from LRCLIB, requested when the user opens the lookup action. Missing exact artist/title matches show an unavailable state.

## Dependencies

The existing NewPipe runtime already includes NanoJSON. Both JVM targets now declare it directly for playlist exchange and LRCLIB data. No additional runtime JAR is introduced.

```kotlin
implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
```

## Build sizes

Measured on Windows: Android debug APK 17.56 MiB, unsigned R8 release APK 3.00 MiB, Windows x64 MSI 122.28 MiB. Planning estimates for Linux are 65–100 MiB for a DEB with system libmpv, and 120–200 MiB for an AppImage with bundled native libraries. Linux packages were not built in this run.

## Verification

```powershell
.\gradlew.bat :shared:jvmTest :desktopApp:test -PorbitLive=true
.\gradlew.bat :androidApp:connectedDebugAndroidTest
.\gradlew.bat :androidApp:assembleDebug :androidApp:assembleRelease :desktopApp:createDistributable :desktopApp:packageMsi
```

Tests cover queue order, pause versus end, shuffle exhaustion, repeat one, timer replacement, autoplay and incognito, recommendation diversity and exclusions, JSON import rollback and previous-version database migration. The live test requests trends, plain lyrics, search results and an audio stream. Offline audio caching, synchronized lyrics and Linux packaging are separate from these controls.
