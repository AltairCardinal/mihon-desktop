# Android source/extension runtime evidence (current consumer)

Date: 2026-07-20

Scope: `align-sources-extensions` Tasks 7D24, 7D24R and 7D24F

Authority boundary: this report verifies the current Android consumer only. Fixed-main commit `6fbf6dfc` remains the original Mihon authority; none of the runtime or controlled-source evidence below replaces it.

## Artifact and device

- Base APK build record HEAD: `de3e4adfe1597fae802816bde539644442e7eb7e`; test APK compilation worktree: `d7648781b3561f5cf832b7b5374955a1ebb72c28` plus the Task 7D24R instrumentation change; repair fresh-AVD execution worktree HEAD: `334af6130bad2dbb094384a3b809202f1b62f9f2`.
- Build: `./gradlew :app:assembleDebug --stacktrace` — GREEN in 6m36s, 287 tasks (61 executed, 226 up-to-date).
- APK: `app/build/outputs/apk/debug/app-universal-debug.apk`
- APK SHA-256: `8e1892fe68cdcd1138ccce96517391b22401d49894ab04af76e8069be82b3460`
- Test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Test APK SHA-256: `1fce797f052d35834e6cd56df58bd2946ae5c8958462be301b3c0f321ec74773`
- Package/version: `app.mihon.dev`, `0.19.4-8352`; device install reported `Success`.
- Real-fixture AVD: `mihon-api36`; repair fresh instrumentation AVD: `mihon-7d24f-repair-api36`, serial `emulator-5556`; model `sdk_gphone64_x86_64`; Android 16 / API 36.
- Fingerprint: `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`.
- Real-fixture runtime crash scan: logcat matches for `FATAL EXCEPTION|OutOfMemoryError|SIGSEGV|Fatal signal` = 0.

## Real extension fixtures

| Fixture | SHA-256 | Runtime result |
|---|---|---|
| TCB Scans 1.4.12 | `bf5a2bfd907d54c1ab5438f09a3a45693b597fcc27fc914241d9cd3e491ce1d2` | installed and loaded as English source |
| MangaDex 1.4.211 | `eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236` | appeared as UNTRUSTED, accepted through the real Trust dialog, then loaded as Multi/English source |

The Extensions dump after trust shows both packages under Installed. The Sources dump on the same process shows TCB Scans and MangaDex without restarting or reinstalling the app.

## Runtime observations

| Required observation | Result | Evidence |
|---|---|---|
| Representative extension loading and source membership | PASS | `06-extensions-trusted.*`, `07-sources.*` |
| Source projection | PASS | after selecting TCB and pinning it, `08-sources-pinned.xml` renders `Last used` → `Pinned` → `English`; TCB is duplicated only for the explicit last-used projection and MangaDex remains in English |
| Global-search source order | PASS | `10-global-empty.xml` lists pinned TCB Scans before alphabetically earlier MangaDex under All, matching pinned-first then normalized name/language ordering |
| Affected browse entry | PASS | both TCB Scans and MangaDex open their real Browse screens; dumps include source title, Popular and source-specific actions |
| Failure feedback and Retry intent | PASS | unreachable provider requests render localized `No Internet connection` plus Retry/WebView/Help; tapping Retry leaves the error state, enters loading, and issues a new request before the provider fails again |
| First-page empty on latest real fixture | PASS | `adb reverse tcp:10809 tcp:10808` plus device proxy `127.0.0.1:10809` routed MangaDex through the host proxy; query `zzzz7d24rnoresult` rendered localized `No results found` |
| Append-empty retry and successful recovery | PASS | the [tracked fresh-AVD transcript](evidence/2026-07-20-7d24f-android-runtime.txt) records the corrected instrumentation through production `SharedSourcePagingSource`/Pager/Compose/Scaffold wiring: page 2 returned empty, page-1 rows remained visible, Retry was visible, its click requested page 2 again, and recovered content rendered |

OpenSpec 4.4 is complete from the combined real-fixture Task 7D24 evidence and deterministic production-wiring Task 7D24R/7D24F evidence. OpenSpec 3.4.3 remains pending for Windows/macOS Desktop final verification and cross-check.

## Emulator cross-check

The existing production-wiring instrumentation was built without adding infrastructure:

```text
./gradlew :app:assembleDebugAndroidTest --stacktrace
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r \
  -e class eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiWiringTest \
  app.mihon.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Task 7D24R compiled this test APK; Task 7D24F reused it byte-for-byte without Gradle, production changes or test-design changes. The fresh AVD was created from the project SDK's `system-images;android-36;google_apis;x86_64`, started with `-no-snapshot -no-snapshot-save`, reached `device` and `sys.boot_completed=1`, and had no global proxy configured.

Result:

```text
INSTRUMENTATION_STATUS: numtests=4
Time: 17.567
OK (4 tests)
INSTRUMENTATION_CODE: -1
```

The complete command transcript, both APK hashes, device/API/fingerprint, both install outputs and every instrumentation status line are preserved in [`evidence/2026-07-20-7d24f-android-runtime.txt`](evidence/2026-07-20-7d24f-android-runtime.txt). The four cases cover the two existing Browse UI boundaries, localized first-page `No results found`, and deterministic append-empty recovery. The controlled catalogue source is not a real/original fixture; its value is that it executes the current consumer's production paging and visible Snackbar retry wiring on-device.

## Environment diagnosis and local evidence

The real-fixture proxy path was proven with `adb reverse tcp:10809 tcp:10808` and device global proxy `127.0.0.1:10809`: opening MangaDex increased host-port established connections from 54 to 60, loaded real titles, and query `zzzz7d24rnoresult` rendered `No results found`. The original AVD later remained offline, so Task 7D24F isolated that environment failure with one fresh cold/no-snapshot AVD. The fresh AVD needed no proxy because its catalogue source is instrumentation-controlled and performs no external request.

Reproducible command output, UI dumps, screenshots and logs are under `.test-tmp/7d24-android-runtime/`, notably:

- `device-summary.txt`, `install.log`, `instrumentation-browse-retry.log`
- `04-extensions.*`, `06-extensions-trusted.*`, `07-sources.*`, `08-sources-pinned.xml`
- `10-global-empty.*`, `14-tcb-popular-wait.*`, `18-mangadex-popular.*`
- `7d24r-mangadex-empty.*`, `7d24r-instrumentation.log`

Those untracked files document the earlier real-fixture run and the original offline/failed instrumentation diagnosis. They are not proof of the Task 7D24F fresh-AVD pass; the tracked raw transcript above is the authoritative evidence for the 17.567s `OK (4 tests)` result.
