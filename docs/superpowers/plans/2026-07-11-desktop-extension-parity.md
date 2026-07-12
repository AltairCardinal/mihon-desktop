# Mihon Desktop Extension Parity Implementation Plan

**Goal:** Bring the Windows desktop extension workflow to feature parity with Mihon's user intent while replacing Android-only package operations with native Windows equivalents.

**Architecture:** Extend the existing desktop extension pipeline instead of creating a second manager. Persist repository identity and install provenance in the existing sidecar metadata, run installs through a transactional task coordinator, reuse the existing source preferences and cookie jar, and expose the capabilities through the current extension list plus a Voyager details screen.

**Platform mapping:** Android package info becomes a Windows file-information dialog and Explorer reveal action. PackageInstaller becomes an atomic user-directory installer. Root and Shizuku modes become writable-directory diagnostics. APK signatures are represented by repository-identity continuity and artifact structure checks; the UI must not claim cryptographic APK-signature equivalence when the repository provides no digest.

## Delivery batches

1. **Metadata and install safety**
   - Add repository URL/name/fingerprint, install time, origin, extension class and artifact digest to `ExtensionMeta`.
   - Preserve compatibility with old sidecars.
   - Download to unique temporary files, validate ZIP shape and declared package, convert if needed, then atomically replace the installed JAR and metadata.
   - Keep the previous working version when any stage fails.

2. **Install task coordinator**
   - Model pending, downloading, converting, installing and failed states.
   - Prevent duplicate package jobs and support cancellation, retry and update-all through one queue.
   - Keep task state outside composition so tab changes do not discard it.

3. **Extension list parity**
   - Render cached/remote icons with fallback.
   - Add search and compose it with language and NSFW filters.
   - Show compatibility, version and install state; expose cancel and retry.
   - Open the first valid source website and the owning repository with failure feedback.

4. **Extension details and Windows tools**
   - Add a type-safe Voyager details screen with header metadata and source rows.
   - Reuse `SourcePreferencesScreen` for configurable sources.
   - Add repository/source links, uninstall confirmation, Explorer reveal, SHA-256 and redacted diagnostic copy.

5. **Runtime source controls**
   - Persist source enabled state with existing sources enabled by default.
   - Apply it in source browse, global search and update paths while keeping library records visible.
   - Preserve states across extension updates and clean them on uninstall.

6. **Cookies and incognito**
   - Extend the existing `DesktopCookieJar` with domain-scoped clearing.
   - Derive domains from extension source base URLs and report partial failures.
   - Reuse the existing global incognito preference; do not disable authentication cookies.

## Required verification

- Red/green tests for metadata compatibility, transaction rollback, identity conflict, malformed downloads, task transitions, cancellation and retry.
- MockWebServer integration tests for repository index, icon and JAR/APK responses including 403, 429, 500 and malformed bodies.
- Screen instantiation, Voyager navigation and DI wiring tests.
- Runtime integration tests for source enablement and domain cookie clearing.
- `spotlessCheck`, `:app-desktop:jvmTest`, desktop smoke tests and `./scripts/build-desktop.sh feature`.
- Manual unbundled-app pass for install, update, details, links, disable/re-enable, restart persistence, file reveal and uninstall.
