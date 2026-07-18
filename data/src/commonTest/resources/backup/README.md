# Android backup fixture provenance

`android-full.tachibk` is generated from the original Mihon Android backup model sources at
`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`.
The generator reads this SHA from `android-full.original-mihon-ref`, its single authority-ref input.
The generator extracts those sources with `git show`, compiles their generated `Backup.serializer()`
with the historical model annotations, serializes via `ProtoBuf`, and gzip-compresses the result.
It does not import the current common backup schema or `BackupCodec`.

The generator extracts the nine files in the fixed ref's
`app/src/main/java/eu/kanade/tachiyomi/data/backup/models` tree
`2e86d6d1f626b349473e1e71e833215aac0c92e3`. That source tree was compared with the former,
non-authoritative `d376fa62fcbdc7f108251762f9645da0e23b89db` ref: every extracted file has the
same Git blob, so this provenance correction does not change the historical model schema.

| Extracted source | Git blob at fixed main and former ref |
| --- | --- |
| `Backup.kt` | `87606a407f7df3d8d0952c6801a8400992a3cb73` |
| `BackupCategory.kt` | `c078bf03587a49c49a6fc505845fb80fa818f21c` |
| `BackupChapter.kt` | `b9c5381b687c94b6fb0013bd8d0539a89f54fc7e` |
| `BackupExtensionRepos.kt` | `a3bd1f8774a336f2b1671ef15d8ca2994f890453` |
| `BackupHistory.kt` | `18934a2f700fbccd22049199fa648957ba919b25` |
| `BackupManga.kt` | `84e0543087e6db59c1411f80d974e391bf477f47` |
| `BackupPreference.kt` | `520d6f2d461085f8f862cf987f11b2f8ece120de` |
| `BackupSource.kt` | `894b5c7cb06e4d7e62b19c0bedd8fb6812e1be75` |
| `BackupTracking.kt` | `0303946c6c9b35daa6bbfc3b000716a6eda1215d` |

Regenerate offline from the repository root:

```powershell
./scripts/generate-android-backup-fixture.ps1
```

The fixture covers manga, chapters, categories, history, tracking, application preferences,
source preferences, sources, extension repositories, and both legacy `viewer` and modern
`viewer_flags`. Its SHA-256 is
`43FA65A3469932F4DA2794E8BDF69C7BEF7D65D4E77FE894E1B1798ED1EFAD8D` and is asserted by
`BackupCodecContractTest`.
Regeneration from the fixed main ref produced the same SHA-256 and byte-identical output, so this
remains the single canonical artifact for Android and Desktop compatibility.

## Desktop first-writer fixture provenance

`desktop-first-writer.tachibk` is generated from the complete Desktop model at the first writer commit
`8c6d18c20bf86c37a11da274f12eb65f31378a8b`. Run
`./scripts/generate-desktop-first-writer-fixture.ps1`; the script extracts the historical source with `git show`,
compiles its generated `Backup.serializer()` in isolation, and writes gzip-compressed protobuf directly. It does
not import the current common schema or `BackupCodec`.

SHA-256: `45949D2FD91F443CAB4BBF2BFFA6FE37E039A3CE8E7EAED032F32FA935E87D2D`.
