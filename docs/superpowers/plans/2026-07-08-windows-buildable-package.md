# Windows Buildable Package Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the current `app-desktop` project build a repeatable Windows MSI package from a Windows machine.

**Architecture:** Keep runtime behavior unchanged. Add release-engineering guard tests, derive native package version from `AppVersion`, and add a Windows PowerShell build script that runs desktop JVM tests before `:app-desktop:packageMsi`.

**Tech Stack:** Gradle Kotlin DSL, Compose Desktop native distributions, PowerShell, JUnit JVM tests.

## Global Constraints

- Follow repo TDD: write failing tests before production/script changes.
- The first Windows milestone is buildable MSI, not public release readiness.
- Installation, upgrade, uninstall, signing, and Windows Cloudflare/WebView remain separate acceptance work.

---

### Task 1: Roadmap State

**Files:**
- Modify: `docs/roadmap/2026-06-30-mihon-desktop-refactor-roadmap.md`

- [x] Mark `XW-01` and version alignment work as active for this implementation.
- [x] Keep install/upgrade/uninstall and signing outside this first milestone.

### Task 2: Release Guard Tests

**Files:**
- Create: `app-desktop/src/test/kotlin/mihon/desktop/release/WindowsReleaseConfigurationTest.kt`
- Modify: `app-desktop/build.gradle.kts`
- Create: `scripts/build-windows.ps1`

- [ ] Write failing tests that assert the Gradle config uses MSI, derives `packageVersion` from `AppVersion`, and the Windows script calls `:app-desktop:jvmTest` before `:app-desktop:packageMsi`.
- [ ] Run the focused test and confirm it fails for the current hardcoded/missing configuration.
- [ ] Implement the minimal Gradle and script changes.
- [ ] Re-run the focused test and confirm it passes.

### Task 3: Verification

**Files:**
- Verify: `app-desktop/src/test/kotlin/mihon/desktop/release/WindowsReleaseConfigurationTest.kt`
- Verify: `scripts/build-windows.ps1`

- [ ] Run focused JVM tests for the release guard.
- [ ] Run the available build command if Java is configured; otherwise report the environment blocker explicitly.
- [ ] Update roadmap progress notes with the verification evidence.
