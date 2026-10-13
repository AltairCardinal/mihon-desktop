---
title: Contribute
description: Help with reading cases, testing, code, design, documentation, and translations.
---

# Contribute

You do not have to write code to help. Reproducible reading cases, clear issue reports, thoughtful design, testing, and translations are all useful contributions.

This is an independently maintained fork. Please use [this repository’s Issues](https://github.com/AltairCardinal/mihon-desktop/issues) for its additions and problems.

## Find a useful starting point

| Area | Ways to help |
| --- | --- |
| Reader and image processing | Share reproducible spread-pairing cases; improve matching and reader interaction. |
| Sync and shared Kotlin | Check cross-device behavior and interruption recovery; add focused regression coverage. |
| Authors and language | Improve author matching, source metadata handling, language correction, and filtering. |
| Desktop experience | Improve Windows/macOS behavior, keyboard access, accessibility, and packaging. |
| Documentation and translation | Clarify setup, correct stale instructions, and keep English and Chinese aligned. |

For a substantial change, open an issue explaining the problem, expected behavior, and platform scope before implementation. Check whether the existing source, use case, component, or shared module can be reused.

## Report a problem {#report-a-problem}

Include your operating system, application version or commit, exact steps, expected result, and actual result. Add relevant screenshots or logs after removing credentials and personal information.

For reader pairing issues, include page numbers, reading direction, and the expected page arrangement. For sync issues, include the platforms involved and the last operation on each device. Share only content you have permission to share.

## Work on the code

Start with the repository’s [contribution guide](https://github.com/AltairCardinal/mihon-desktop/blob/main/CONTRIBUTING.md) and [agent and engineering rules](https://github.com/AltairCardinal/mihon-desktop/blob/main/AGENTS.md).

| Directory | Responsibility |
| --- | --- |
| [app-desktop](https://github.com/AltairCardinal/mihon-desktop/tree/main/app-desktop) | Desktop UI, adapters, runtime, and desktop tests. |
| [app](https://github.com/AltairCardinal/mihon-desktop/tree/main/app) | This fork’s Android app. |
| [domain](https://github.com/AltairCardinal/mihon-desktop/tree/main/domain) / [data](https://github.com/AltairCardinal/mihon-desktop/tree/main/data) | Shared behavior, data models, and persistence. |
| [source-api](https://github.com/AltairCardinal/mihon-desktop/tree/main/source-api) / [source-local](https://github.com/AltairCardinal/mihon-desktop/tree/main/source-local) | Source contracts and local content. |
| [website](https://github.com/AltairCardinal/mihon-desktop/tree/main/website) | This bilingual public documentation site. |
| [docs](https://github.com/AltairCardinal/mihon-desktop/tree/main/docs) | Architecture, plans, evidence, and technical guides. |

Behavior changes require red → green → refactor: first observe a meaningful failing test, add the smallest implementation, then refactor and rerun the focused scope. Cover real production wiring for navigation, dependency injection, and network changes. Keep the change focused and report the tests actually run. Pure documentation changes do not require product tests.

For desktop integration testing, the repository also has a headless HTTP control interface that must be explicitly enabled with Test Mode. It is a developer tool, not a reader-facing remote service. Follow the existing [Test Mode boundaries](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md#test-mode-屏幕录制权限边界) and repository test instructions.

## Update public docs after acceptance {#accepted-documentation}

Public feature documentation describes the current public baseline, not every commit on the development branch.

1. **Implement and verify.** Keep the proposed behavior, limitations, and test evidence in the relevant internal plan or handoff. Do not advance public feature claims while owner acceptance is pending.
2. **Obtain explicit owner acceptance.** A successful build, a passing CI run, a merged pull request, or an agent’s completion message is not acceptance. Record which behavior and source revision the owner accepted.
3. **Update the current guide in place.** Replace the affected parts of the English and Chinese feature guides, including platform scope, user entry points, feedback, and limitations. Update the homepage, setup instructions, and README summaries only where needed.
4. **Add one concise history entry.** Link the implementation revision and a public issue or pull request when available. If acceptance happened in private chat, record only its date and a minimal scope summary; do not publish private chat links or transcripts. Say what changed without pasting another full feature guide or inventing a release version.
5. **Build and review the site.** Check links, language alignment, navigation, and the affected page. Publication follows the repository’s Pages workflow.

The initial October 13, 2026 baseline is a migration of already-public descriptions. It does not retroactively accept other work in progress.

The [public documentation maintenance policy](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/architecture/public-documentation.md) defines the agent workflow, evidence, deployment, and failure handling. Acceptance of an unrelated feature does not authorize publishing every pending feature.

## Preview a documentation change

From the repository root, using Node.js 22 or newer:

~~~bash
npm --prefix website ci
npm --prefix website run docs:build
npm --prefix website run docs:preview
~~~

Open the local address printed by VitePress with the project base path `/mihon-desktop/`. Built output is in `website/.vitepress/dist/`; do not commit dependencies or generated output.

English is the default at the site root; Simplified Chinese lives under `/zh/`. Keep matching page names and section anchors across both languages. Each page links to its Git history so earlier wording can be inspected without multiplying “current” guides.

## Attribution

Mihon Desktop builds on [Mihon](https://github.com/mihonapp/mihon), [Tachiyomi](https://github.com/tachiyomiorg/tachiyomi), and their contributors. Contributions follow the repository’s [Apache-2.0 license](https://github.com/AltairCardinal/mihon-desktop/blob/main/LICENSE); preserve existing copyright and attribution.
