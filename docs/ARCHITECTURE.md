# Orbit Music — implementation plan

Phases A–C implement theming, search and playback. See [Phase C](PHASE_C.md) for the current backend and tests.

Working product name: **Orbit Music** (reversible). Kotlin + Compose Multiplatform stays as requested. Android is a native Android app; Linux/Windows use a desktop JVM and Skia UI, not Kotlin/Native. No WebView or Electron runtime.

## Phase boundaries

| Phase | Deliverable | Decision gate |
| --- | --- | --- |
| a — current | Platform entry points, shared theme workbench, tokens, five presets + Custom model, contrast warnings, reduced-effects policy, ViewModel/reducer tests, release shrinking configuration | Review this foundation before b |
| b | NewPipe-backed `MusicSource`, network adapter, typed failures/retries, bounded paged search, debounced suggestions, data-layer unit tests, Koin graph | Confirm extractor/version/network stack after compatibility and license review |
| c | Media3 Android foreground service/session, audio focus, notification/lock-screen controls; desktop audio, tray/media keys | Approve desktop audio backend and packaging cost before implementation |
| d | Discover/history, search, library, full/mini player, queue, shuffle/repeat, lyrics where supplied, sleep timer, JSON library transfer, optional bounded offline cache | Review navigation and caching behavior |
| e | Full customization editor, validated/versioned theme JSON, settings persistence, custom images, art-derived colors, live preview, OS accessibility integration | Review import schema and image permissions |
| f | Profiling, data/integration tests, cache limits, accessibility review, release signing instructions, Linux DEB/AppImage, Windows MSI, Android APK, GitHub Actions matrix | Verify artifacts on each native OS before release |

No future-phase feature is represented as working in the current UI. Controls in the workbench are real; changes survive activity recreation through the ViewModel, but not process restart. Custom colors and remaining tokens are editable in code until e. Album-art, blur, animation intensity and dynamic color are model fields; no blur renderer, image loader or particle system is installed in a.

## Dependency direction and state

`Compose screen -> ThemeViewModel -> pure reducer -> immutable ThemeSettings`

`Future feature ViewModel -> use case/repository -> MusicSource or storage port -> platform adapter`

Keep a single shared Gradle module initially to reduce configuration overhead. Organize by packages with explicit boundaries; split modules only when compile-time enforcement justifies their cost. Source contracts contain no NewPipe types, Android contexts, player objects or Compose state. The source adapter translates upstream failures into `SourceError`; cancellation must propagate rather than become a user-visible error. Page size validation and token handling belong to the adapter and its tests in b. Stream URLs are ephemeral and must be re-resolved, never persisted as track identity.

Shared UI owns immutable state and events. The ViewModel exposes read-only observable state; only `dispatch` mutates it. Koin will wire service lifetimes once actual repositories/adapters exist in b; the no-dependency theme ViewModel currently uses the standard Compose factory. No `expect/actual` has been added: platform entry points inject OS-specific information directly.

## Current file structure

```text
androidApp/
  build.gradle.kts                   # SDK 26–37, R8 + resource shrinking
  src/main/.../MainActivity.kt       # Android window and OS animation preference
desktopApp/
  build.gradle.kts                   # JVM 17 target, jlink modules, MSI/DEB targets
  src/main/.../main.kt               # desktop window and close lifecycle
shared/
  src/commonMain/kotlin/com/example/myapplication/
    App.kt                          # ViewModel host and appearance workbench
    MusicShell.kt                   # Limusic-inspired responsive navigation/dock/panes
    domain/MusicSource.kt            # source-neutral interface/models, no adapter yet
    theme/ThemeTokens.kt             # colors, spacing, density, shapes, effects, contrast
    theme/OrbitTheme.kt              # Material mapping + typography + composition locals
    theme/ThemeViewModel.kt          # state, actions, pure reducer
  src/commonTest/.../theme/ThemeTest.kt
docs/
  ARCHITECTURE.md
  DEPENDENCIES.md
  VALIDATION.md
```

Starter greeting/platform sample files remain unused and are not part of the music architecture. Package/application IDs stay unchanged to avoid an unapproved identity migration.

## Proposals requiring approval in later phases

* **Storage:** SQLDelight for generated, typed SQL and bounded queries, plus Multiplatform Settings for small preferences; Android app-private directories, Linux XDG data/cache/config and Windows LocalAppData. No database is bundled in a.
* **Desktop playback** uses the libmpv client API through JNA on Windows and Linux, per the user’s choice of the Limusic backend.
* **DI:** Koin core + Compose integration with singleton source/storage services and scoped ViewModels. Approve exact versions with b's dependency inventory.

## Platform differences to implement explicitly

| Concern | Android | Linux | Windows 10/11 |
| --- | --- | --- | --- |
| UI | safe insets; compact bottom navigation in d | shared sidebar/panes, keyboard focus | shared sidebar/panes, keyboard focus |
| Fonts | built-in sans/monospace | system sans/monospace fallback | system sans/monospace fallback |
| Files | app storage, SAF import/export | XDG directories, native chooser | LocalAppData, native chooser |
| Playback | Media3 service, audio focus | audio backend + MPRIS/D-Bus | audio backend + SMTC/media-key bridge |
| Tray | notification instead | detect tray support; graceful fallback | AWT tray if supported |
| Motion | Android animator-enabled flag injected in a; live changes observed on resume | user toggle in a; desktop-environment preference adapter in e | user toggle in a; OS preference adapter in e |
| Packages | R8 release APK; signing external | jpackage DEB + AppImage tooling in f | jpackage MSI + WiX in f |

Resizable player/library panes and music shortcuts are d/c work. Fonts are system families to avoid font downloads and asset cost; geometric styling currently uses monospace headings. The light preset uses darker accent equivalents on `#F3F7FF` so text remains readable.

## Lightweight budgets

No network, images, polling, infinite animations, blur passes, particles or audio thread in the current shell. Preset changes are direct state transitions. Future artwork loader: bounded memory/disk LRU, thumbnail decoding at display size, cancellation offscreen. Lists: lazy rendering, 30-item pages, bounded retained pages. Dynamic album palettes are computed off the UI thread and cached by artwork identity. Optional audio cache gets an explicit byte budget, eviction policy and clear-cache action.

Baseline targets to validate in f: idle CPU near zero, cold/warm start and memory measured on named hardware, 60 Hz interactions without sustained recomposition. These are goals, not measurements.

Desktop distributions use the Compose plugin's jlink runtime, with explicit extra modules rather than `includeAllModules`. Run `suggestModules` after adding playback, database, networking or tray bridges. See [JetBrains packaging documentation](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html). CI must build on native runners: desktop packaging is not cross-compilation. API 37 is retained from the starter; [Android's target policy](https://developer.android.com/google/play/requirements/target-sdk) currently requires at least 36 for Play submissions.

## Confirmed visual reference

The sidebar, bottom dock and Queue/Lyrics pane follow Limusic v1.3.0. The current dock controls playback. Click the track to open its expanded view.
