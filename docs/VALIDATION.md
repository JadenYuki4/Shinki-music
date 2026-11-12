# Validation and performance checklist

Current playback tests and sizes are in [Phase C](PHASE_C.md). The figures below are historical.

## Phase (b) verification

The data/search suite adds ten JVM adapter tests and seven shared controller tests. Together with six theme tests and two starter tests, **25 deterministic tests pass**. The opt-in live test also passes: public text suggestions, all four YouTube Music categories, and a song-search continuation. Normal test runs skip that live test; `-PorbitLive=true` enables it explicitly.

Android debug and desktop compilation have passed. Final R8 release/MSI sizes and build results are recorded in the artifact table below. No physical Android/API-26 emulator or Linux runtime test has been performed. This phase did not resume desktop UI automation after the user stopped it during (a); programmatic source tests do not constitute a visual or accessibility audit.

Source details and exact commands: [PHASE_B.md](PHASE_B.md). Regressions specifically covered include stale results from a source ignoring cancellation, overflow skipped at page boundaries, expired cursors, unbounded retention and rate-limit retry loops. The live check depends on upstream/network availability and is intentionally separate from routine offline tests.

## Automated checks

```powershell
.\gradlew.bat :shared:jvmTest :androidApp:assembleDebug :androidApp:assembleRelease :desktopApp:classes
.\gradlew.bat :desktopApp:suggestModules
.\gradlew.bat :desktopApp:createDistributable
```

Six theme tests cover WCAG black/white reference contrast, preset body/button readability, independent accessibility/low-power overrides, unsafe custom palette warnings, out-of-range effects and reducer bounds/state preservation. Two untouched starter tests also run. These are foundation tests; data-layer tests are added with the actual adapter in b.

Release APK is unsigned by default; the debug APK uses the standard Android debug key. Release signing credentials must stay outside source control. R8 and resource shrinking are enabled for release. Desktop packaging uses a JVM 17 bytecode target and jlink runtime, not the developer machine's whole JDK.

## Manual checks

- [ ] Android API 26 and API 37 devices/emulators: launch, rotation, background/resume, edge-to-edge insets.
- [ ] Phone: workbench remains vertically scrollable at 320 dp and 200% font scaling.
- [ ] Desktop: sidebar appears at 840 dp; narrower windows stack cards and show the compact footer.
- [ ] All five presets update the whole workbench immediately; changing preset preserves reduced-effects preferences.
- [ ] Contrast warnings are visible, including accent and secondary text against each surface. This is a semantic text-pair check, not a complete WCAG audit or image-overlay check.
- [ ] Corner slider changes card radius and remains keyboard/screen-reader accessible.
- [ ] Tab traversal, keyboard preset selection, high DPI and system font fallback verified on Linux and Windows.
- [ ] Low-power/reduce-motion tokens disable decorative effects; no perpetual render loop or particles.
- [ ] Android system animator preference is read at launch and on resume. Desktop OS preference detection remains e work; explicit app toggles work now.
- [ ] Restart resets theme; recreation keeps ViewModel state. Persistence is intentionally e work.
- [ ] Verify runtime image launch independently of installed JDK; smoke-test MSI install/uninstall in a disposable VM in f.
- [ ] Native Linux DEB and AppImage smoke tests in f (not testable on this Windows host).

## Profiling method for f

Record hardware, OS, build hash, ABI, build type and thermal/power state. Measure cold launch with Android Macrobenchmark and startup tracing; use the release APK with baseline profiles. Record PSS after settling and during playback/search, CPU at idle/playback, frame timing and recomposition counts. Desktop: measure process working set/private bytes and CPU after 30 seconds idle; Java Flight Recorder for allocations and stalls. Include player child processes/native libraries in totals. Run ten cold launches, report median and p95. No RAM/CPU/startup numbers are claimed from compilation alone.

Use lazy paged results, bounded artwork LRU and audio-cache eviction tests when those features exist. Reject changes that add offscreen decode work, unbounded history queries or idle timers. Profile both normal and low-power settings before comparing effects costs.

## Artifact sizes

Phase (a) measured baseline: unsigned R8 release APK **1,094,664 bytes (1.04 MiB)**; Windows MSI **63,781,994 bytes (60.83 MiB)**. The phase-a desktop app image was approximately 124.6 MiB installed. These are historical values, not claims about later builds.

Future phases update this section with actual outputs where available and clearly separate estimates from measurements. Installer compression, CPU architecture, signing and JDK vendor affect size. Linux remains an estimate until a native Linux package is built; DEB/AppImage and cross-platform CI remain phase (f).

### Phase (b), final Windows-host build

| Artifact | Exact bytes | MiB | Status |
| --- | ---: | ---: | --- |
| Android debug APK | 14,648,928 | 13.97 | Built; debug-signed, installable for testing |
| Android release APK | 1,972,557 | 1.88 | Built with R8/L8 + resource shrinking; unsigned |
| Windows x64 MSI | 68,355,991 | 65.19 | Built; clean-VM installation not tested |
| Windows portable app image | 136,797,198 | 130.46 | Built with explicit jlink modules; size includes runtime |
| Linux DEB/AppImage | — | 55–90 estimated each | Not built or measured on this Windows host |

Final verification command succeeded with 26 passing tests, including the explicitly enabled live test:

```powershell
.\gradlew.bat :shared:jvmTest -PorbitLive=true :androidApp:assembleDebug :androidApp:assembleRelease :desktopApp:createDistributable :desktopApp:packageMsi
```

The packaged shared JAR and `shared/build/libs/shared-jvm.jar` have matching SHA-256: `969BAEF611E68609C11402D571A73504C5F49FC6BDF2DEA6C201BA3A70318781`. This checks that the portable image contains the current source build rather than an earlier preview. No UI automation was resumed in phase (b), and Android runtime/device validation remains pending.
