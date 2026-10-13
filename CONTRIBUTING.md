# Contributing to Mihon Desktop

Mihon Desktop is an independently maintained fork of [Mihon](https://github.com/mihonapp/mihon), with Windows as its primary desktop target, macOS build support, and its own Android app. Contributions to reading, cross-device continuity, author discovery, language information, accessibility, documentation, and translation are welcome.

[简体中文贡献指南](https://altaircardinal.github.io/mihon-desktop/zh/contributing.html) · [Project website](https://altaircardinal.github.io/mihon-desktop/) · [Development setup](./README.md#get-started)

## Start with the problem

Use [this fork’s Issues](https://github.com/AltairCardinal/mihon-desktop/issues) for bug reports, feature proposals, and development discussions. Please keep fork-specific reports here. Upstream Mihon’s issue tracker and community are maintained separately.

For a substantial change, describe the user problem, expected behavior, affected platforms, and proposed scope before implementation. You can comment on an existing issue to avoid overlapping work. Small documentation corrections can go directly to a pull request.

Useful contributions include:

- Reproducible page-pairing cases, reader interaction improvements, and accessible keyboard behavior.
- Shared Kotlin behavior and recovery tests for synchronization and persistence.
- Author identity, source metadata, discovery, and language-information improvements.
- Windows and macOS usability reports, packaging fixes, and Android compatibility checks.
- Clear setup instructions, English/Chinese documentation, and translations.

For a bug report, include the operating system, app version or commit, reproduction steps, expected and actual results, and relevant logs or screenshots. Remove tokens, passwords, private repository names, and personal library data. Use shareable fixtures for image or metadata examples. Pairing reports should include page numbers, reading direction, and expected layout; sync reports should include the platforms and the last action on each device.

## Set up and find the code

The [README](./README.md#get-started) describes preview builds and prerequisites. Android identity, signing, and candidate builds are documented in the [Android build guide](./docs/architecture/android-build-and-acceptance.md). Preview builds are for development feedback; they do not run product tests or prove release readiness.

| Path | Responsibility |
| --- | --- |
| [`app-desktop/`](./app-desktop/) | Desktop UI, platform adapters, runtime wiring, and tests. |
| [`app/`](./app/) | This fork’s Android application and platform integration. |
| [`domain/`](./domain/) and [`data/`](./data/) | Shared business logic, models, and persistence. |
| [`source-api/`](./source-api/) and [`source-local/`](./source-local/) | Source contracts and local-content support. |
| [`website/`](./website/) | English and Chinese public documentation. |
| [`docs/`](./docs/) | Design, architecture, technical guides, and validation evidence. |

Read [AGENTS.md](./AGENTS.md) before changing code or delegating work to an agent. Desktop UI work also starts with the [Desktop UI guide](./docs/design/mihon-desktop-ui/README.md).

## Implement and validate a focused change

Reuse existing use cases, repositories, services, navigation, dependency injection, and shared Kotlin contracts. Preserve upstream Mihon semantics wherever platform differences do not prevent reuse, while keeping this fork’s additional capabilities. Put necessary platform differences in adapters and explain their scope.

Every user-facing capability needs a real entry point and visible feedback. Dangerous or bulk actions need the appropriate confirmation. Infrastructure must be used by the production application and covered at its integration points.

Behavior changes require red–green–refactor:

1. Write a focused test and confirm it fails for the intended reason.
2. Make the smallest production change that passes the selected tests.
3. Refactor, then rerun the same focused scope.

Tests must execute production behavior and wiring. Navigation, dependency injection, HTTP parsing, and persistence changes need the relevant integration coverage. Shared Android/Desktop behavior needs shared contract coverage; platform adapters need platform-specific coverage. Source-text scans or copied implementations do not substitute for behavior tests.

At completion, run the affected regression, integration, and formatting checks. Document the commands, results, and any remaining limitations. Documentation-only changes need link/content checks and `git diff --check`, not product tests.

Use the smallest sufficient scope. A completed task, commit, or roadmap does not authorize full-module tests, the full suite, a formal release build, or a version increment. If focused tests cannot cover a concrete remaining risk, explain why and obtain approval for the minimum expanded scope. A full release matrix requires an explicitly chosen release milestone.

Heavy Gradle validation in one worktree must be serialized through the repository coordinator. For example, on Windows, substitute the actual affected test class:

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$ErrorActionPreference = 'Stop'
python scripts/gradle-coordinator.py run --key my-focused-check -- .\gradlew.bat :app-desktop:jvmTest --tests "fully.qualified.AffectedTest"
```

Do not start a second Gradle command merely because an outer wait timed out. Check the coordinator’s status first; see [AGENTS.md](./AGENTS.md) for the complete lifecycle and platform rules.

## Prepare a pull request

Keep the change centered on one independently reviewable behavior. Explain the user-visible result, affected platforms, reuse decisions, test evidence, and known limits. Include screenshots or a short recording when they clarify UI changes. Never include credentials, signing material, personal configuration, or private data.

Update technical documentation when architecture, maintenance rules, or failure handling change. Data migration, security boundaries, release identity, and shared protocols need independent review before dependent work uses them. Follow the repository’s task and review budgets; do not expand a change just to address unrelated findings.

## Publish accepted feature documentation

The website maintains one current [feature guide](https://altaircardinal.github.io/mihon-desktop/features.html) in each language and a separate [change history](https://altaircardinal.github.io/mihon-desktop/changelog.html). Git preserves earlier document revisions; the current guide should describe current behavior without accumulating obsolete instructions.

During implementation, record evidence and proposed public-documentation changes in the development handoff. **Do not update the public feature description until the project owner explicitly accepts that iteration.** Passing tests, a review, a merged pull request, or a completed roadmap is not that acceptance. An agent cannot accept its own work on the owner’s behalf.

After explicit acceptance, update the affected English and Chinese feature sections in place, add a concise history entry with the accepted scope and relevant version or commit, and keep README summaries consistent. Preserve platform limits and incomplete capabilities. Follow the [public-documentation workflow](./docs/architecture/public-documentation.md); do not present an unaccepted implementation or a preview build as a stable release. The initial website is a documentation baseline based on the existing public README, not a claim that every capability has passed a new acceptance review.

## Translations and attribution

Keep this fork’s README and website translations aligned in meaning, feature status, and links. Product strings live under [`i18n/`](./i18n/); follow the existing resource conventions and coordinate larger translation work in this fork’s Issues. Upstream Mihon’s translation process is separate and does not publish this fork’s documentation.

Contributions must comply with the [Apache License 2.0](./LICENSE) and preserve existing notices and attribution. Further forks must maintain a distinct application identity and their own release/signing configuration; do not reuse upstream branding or services in a way that implies affiliation.
