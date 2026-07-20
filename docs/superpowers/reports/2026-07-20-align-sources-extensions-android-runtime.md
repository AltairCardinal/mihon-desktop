# Android source/extension runtime evidence (current consumer)

Date: 2026-07-20

Scope: `align-sources-extensions` Task 7D24

Authority boundary: this report verifies the current Android consumer only. It is not evidence for fixed-main original Mihon authority.

## Artifact and device

- HEAD: `de3e4adfe1597fae802816bde539644442e7eb7e`
- Build: `./gradlew :app:assembleDebug --stacktrace` — GREEN in 6m36s, 287 tasks (61 executed, 226 up-to-date).
- APK: `app/build/outputs/apk/debug/app-universal-debug.apk`
- APK SHA-256: `8e1892fe68cdcd1138ccce96517391b22401d49894ab04af76e8069be82b3460`
- Package/version: `app.mihon.dev`, `0.19.4-8352`; device install reported `Success`.
- AVD: `mihon-api36`; model `sdk_gphone64_x86_64`; Android 16 / API 36.
- Fingerprint: `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`.
- Runtime crash scan: logcat matches for `FATAL EXCEPTION|OutOfMemoryError|SIGSEGV|Fatal signal` = 0.

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
| First-page empty on latest real fixture | NOT VERIFIED | foreign provider HTTP could not be routed through the host-only `127.0.0.1:10808` proxy from the AVD, so both providers failed before returning an empty successful page |
| Append-empty retry and successful recovery | NOT VERIFIED | the tracked real fixtures expose no deterministic append-empty response, and the provider network limitation prevented a successful recovery cycle |

OpenSpec 3.4.3 and 4.4 therefore remain pending. Existing JVM/shared tests prove the reducer contract, but are not substituted for missing current-emulator evidence.

## Emulator cross-check

The existing production-wiring instrumentation was built without adding infrastructure:

```text
./gradlew :app:assembleDebugAndroidTest --stacktrace
adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -e shell am instrument -w -r \
  -e class eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiWiringTest \
  app.mihon.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Result: `OK (2 tests)` in 3.247s. The real Compose/Pager screen wiring covered 403 → Login navigation and failure → Retry → second request/empty result. This cross-check supports Retry wiring, but does not upgrade the missing append-empty real-fixture evidence.

## Environment diagnosis and local evidence

The first AVD boot lacked `NET_CAPABILITY_VALIDATED` because Google connectivity probes timed out. Setting captive-portal ignore mode and reconnecting produced a Wi-Fi network with `EVER_VALIDATED&IS_VALIDATED`. Raw IP/MangaDex DNS checks succeeded before proxying, but foreign HTTP still requires the host proxy. Restarting the AVD with proxy environment and disabling virtual cellular did not make extension OkHttp requests use `127.0.0.1:10808`; diagnosis stopped after that bounded retry.

Reproducible command output, UI dumps, screenshots and logs are under `.test-tmp/7d24-android-runtime/`, notably:

- `device-summary.txt`, `install.log`, `instrumentation-browse-retry.log`
- `04-extensions.*`, `06-extensions-trusted.*`, `07-sources.*`, `08-sources-pinned.xml`
- `10-global-empty.*`, `14-tcb-popular-wait.*`, `18-mangadex-popular.*`
