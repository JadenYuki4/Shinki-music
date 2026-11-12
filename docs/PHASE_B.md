# Phase (b) — NewPipe + search

The shared Limusic-inspired shell now has a real Search page. It supports songs, albums, artists and playlists through YouTube Music search filters, live text suggestions, Enter/IME Search, Ctrl+K navigation, paged results, empty/loading/error states and retry. The lower playback dock remains disabled until (c). Artwork URLs are mapped but thumbnails are not downloaded yet; the bounded image cache belongs to (d).

## Architecture and files

```text
commonMain/
  domain/MusicSource.kt            source-neutral contracts and typed failures
  di/AppGraph.kt                   isolated Koin container; preview has no network
  search/SearchViewModel.kt        lifecycle ViewModel + testable SearchController
  search/SearchScreen.kt           shared lazy results and suggestion UI
jvmAndAndroidMain/
  data/NewPipeGateway.kt           upstream API/filter/item translation
  data/NewPipeMusicSource.kt       validation, cursor bounds, cancellation, retry
  data/UrlConnectionDownloader.kt  system TLS + bounded blocking HTTP adapter
commonTest/search/SearchControllerTest.kt
jvmTest/data/NewPipeMusicSourceTest.kt
jvmTest/data/LiveSourceSmokeTest.kt
```

Both Android and desktop are JVM platforms, so NewPipe and Java's HTTP implementation live in the explicitly shared `jvmAndAndroidMain` source set. The manual source hierarchy disables Kotlin's automatic hierarchy template to avoid contradictory edges. No expect/actual is needed. Platform entry points supply the real source; IDE previews supply an unsupported stub. Koin creates the source singleton binding and lifecycle ViewModel factories in an isolated application container; data classes contain no service locators.

Search defaults to songs; the four chips select a specific category. There is no misleading combined "All" search. Suggestions use NewPipe's **YouTube suggestion endpoint**, while category results use its **YouTube Music extractor**. Suggestions are phrases, not preloaded song/album objects. No account or cookie import is implemented.

## Limits and failure behavior

- Query: trim before sending, maximum 200 characters; suggestions require at least two characters.
- Suggestions: 300 ms debounce, distinct nonblank phrases, maximum eight.
- Results: 30 per UI page; source callers may request 1–50. Overflow from an upstream page is preserved for subsequent pages.
- Source cursors: opaque, process-local, bound to query/category/page size; at most 16 retained cursors. Expired cursors restart search through an explicit action.
- UI retains at most 300 distinct results; beyond that it asks the user to refine the query.
- Two blocking source workers maximum, 12-second connect/read timeouts, eight MiB response-body ceiling. Connections/streams close in finally/use blocks. Java socket I/O may take until its timeout to release after cancellation; stale jobs cannot overwrite current UI state.
- One automatic retry after 400 ms for transient IO/5xx failures. 429/verification/unavailable/parser errors are not automatically retried. Manual retry is available in the UI.
- Job cancellation propagates; generation checks also reject a non-cooperative source returning stale results.
- Verification/captcha, unavailable content, malformed source responses, expired cursors, rate limits and network errors get separate messages. No credentials, response bodies or user queries are logged.

`discover` and `stream` explicitly return Unsupported until their implementation phases. A successful source search does not claim playback, home recommendations, persistence or offline support.

## Versions, exact dependency lines and size rationale

These additions complement the full Phase (a) inventory in DEPENDENCIES.md. JAR lengths describe resolved files, not additive APK cost after R8.

| Dependency | Version | Exact Gradle declaration | Purpose / footprint |
| --- | --- | --- | --- |
| NewPipe Extractor | `v0.27.1` | `implementation("com.github.teamnewpipe:NewPipeExtractor:${libs.versions.newpipe.get()}") { exclude(group = "org.mozilla", module = "rhino-engine") }` | Swappable maintained extractor; resolved JAR 818,711 bytes; R8 retains used portions |
| Koin core | `4.2.2` | `implementation(libs.koin.core)` | Isolated DI; JVM JAR 435,897 bytes; no annotation processor or full kotlin-reflect |
| Coroutines core | `1.11.0` | `implementation(libs.kotlinx.coroutines.core)` | Explicit cancellation/StateFlow/IO APIs; already present transitively through Compose/lifecycle |
| Coroutines test | `1.11.0` | `implementation(libs.kotlinx.coroutines.test)` in commonTest | Deterministic virtual time; zero release bytes |
| JUnit | `4.13.2` | `implementation(libs.junit)` in jvmTest | JVM test runner and opt-in assumption; zero release bytes |
| NIO desugaring | `2.1.5` | `coreLibraryDesugaring(libs.desugar.jdk)` in androidApp | NewPipe's Java APIs on API 26; final byte cost depends on L8/R8 reachability |
| SQLDelight Gradle plugin | `2.4.1` | `alias(libs.plugins.sqldelight)` in root (`apply false`) and `shared` | Compile-time SQL verification and typed Kotlin query generation for `search_history`, `play_event` and `favorite` |
| SQLDelight runtime + coroutines | `2.4.1` | `implementation(libs.sqldelight.runtime)` and `implementation(libs.sqldelight.coroutines)` in `commonMain` | Lightweight query execution and `Flow` observation (~60 KiB combined before R8) |
| SQLDelight Android driver | `2.4.1` | `implementation(libs.sqldelight.android)` in `androidMain` | Delegates to Android platform SQLite (`SupportSQLiteOpenHelper`); no bundled native SQLite engine in APK |
| SQLDelight JVM SQLite driver | `2.4.1` | `implementation(libs.sqldelight.sqlite)` in `jvmMain` and `jvmTest` | Desktop JDBC SQLite driver (pulls `org.xerial:sqlite-jdbc`); adds `java.sql` jlink module |
| Multiplatform Settings | `1.3.0` | `implementation(libs.multiplatform.settings)` and `implementation(libs.multiplatform.settings.noArg)` in `commonMain`; `implementation(libs.multiplatform.settings.test)` in `commonTest` | Key-value preferences (`SharedPreferences` on Android, `java.util.prefs.Preferences` on JVM); adds `java.prefs` jlink module |

Catalog coordinates:

```kotlin
newpipe-extractor = { module = "com.github.teamnewpipe:NewPipeExtractor", version.ref = "newpipe" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
desugar-jdk = { module = "com.android.tools:desugar_jdk_libs_nio", version.ref = "desugar" }
```

NewPipe's runtime transitives: `com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996` (small JSON parser), `org.jsoup:jsoup:1.24.1` (HTML parser, 594,871-byte JAR), `com.google.code.findbugs:jsr305:3.0.2` (annotations), `com.google.protobuf:protobuf-javalite:4.36.2` (upstream protocol models) and `org.mozilla:rhino:1.8.1` (upstream JavaScript engine, 1,271,206-byte JAR). They are declared by the extractor rather than duplicated in our Gradle files. The optional `rhino-engine` JSR-223 adapter is excluded: Android has no `javax.script` and the extractor calls Rhino directly. This exclusion is checked by compilation and the source tests; upstream Rhino ProGuard rules remain in place.

Repository addition is restricted to the two upstream groups:

```kotlin
maven("https://jitpack.io") {
    content {
        includeGroup("com.github.teamnewpipe")
        includeGroup("com.github.TeamNewPipe")
    }
}
```

No OkHttp, Ktor, image-loader or JSON-serialization dependency was added. HTTP uses system HttpURLConnection. Android and desktop now target JVM 17 bytecode; Android remains minSdk 26 through desugaring. Java 17 targets fix D8 record-desugaring configuration for this extractor. R8 shrinking stays enabled.

Desktop `suggestModules` adds `java.net.http` and `jdk.dynalink` to the existing UI runtime modules. Android R8 narrowly suppresses Rhino's unused desktop JavaBeans/Dynalink references; NewPipe explicitly uses Rhino interpreted mode with safe standard objects. This follows the [upstream Android shrinker rules](https://github.com/TeamNewPipe/NewPipe/blob/dev/app/proguard-rules.pro). Actual API-26 device execution remains to be tested; a successful minified build alone does not establish that runtime coverage.

## Build, run and tests

Use the same JDK/SDK setup as README.md, then:

```powershell
.\gradlew.bat :shared:jvmTest :androidApp:assembleDebug :androidApp:assembleRelease :desktopApp:classes
.\gradlew.bat :desktopApp:run
.\gradlew.bat :desktopApp:packageMsi
```

Linux equivalents use `./gradlew`; `:desktopApp:packageDeb` must run on Linux. AppImage and all-platform CI remain (f).

Opt-in public-network smoke test (fixed nonpersonal query, no account):

```powershell
.\gradlew.bat :shared:jvmTest --tests '*LiveSourceSmokeTest' -PorbitLive=true
```

Normal tests skip that network test. The real-network check covers suggestions and all four Music categories. The deterministic data tests cover overflow preservation, cursor binding/eviction, input validation, deduplication, bounded retry, cancellation, error mapping, item types and unsupported features. Controller tests cover debounce, stale suggestions/results, category changes, append failures/retry, expired cursors, clearing and result retention bounds. This does not replace device testing on Android API 26/37 or Linux desktop testing.

Final result: **25 deterministic tests + one opt-in live smoke test passed**, including a song continuation. Android debug/release, Windows MSI and a current portable app image built successfully. Measured: release APK **1.88 MiB** unsigned; debug APK **13.97 MiB**; MSI **65.19 MiB**; desktop installed image **130.46 MiB**. Linux DEB/AppImage remain **55–90 MiB estimates**, not built here. Exact byte counts and limitations are in VALIDATION.md.

Full resolved dependency trees are saved in [desktop-runtime-dependencies.txt](desktop-runtime-dependencies.txt) and [android-runtime-dependencies.txt](android-runtime-dependencies.txt), including transitive versions. The table above identifies which Gradle lines install the new direct dependencies; transitive dependencies need no duplicate declaration.

## Upstream references and notices

- [NewPipe v0.27.1 release](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.27.1)
- [Upstream integration/desugaring/ProGuard instructions and GPL-3.0-or-later license](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.27.1/README.md)
- [Koin 4.2.2 release](https://github.com/InsertKoinIO/koin/releases/tag/4.2.2)
- [Google desugaring changelog](https://github.com/google/desugar_jdk_libs/blob/master/CHANGELOG.md)

Project licensing remains GPL-3.0-or-later. NewPipe is GPL-3.0-or-later; Rhino is MPL-2.0; jsoup is MIT; nanojson, protobuf, Koin, coroutines and desugaring use Apache-2.0; JSR305 carries its upstream BSD notice. Koin also brings Touchlab Stately concurrency/collections 2.1.0. Retain published dependency notices when distributing. This project does not implement DRM circumvention, credential collection or paid-tier bypass. The README's YouTube Terms of Service disclaimer continues to apply.
