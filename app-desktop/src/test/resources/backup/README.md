# Android backup fixture provenance

`android-full.tachibk` is generated from the Android backup model sources at commit
`d376fa62fcbdc7f108251762f9645da0e23b89db`, immediately before the shared-schema migration.
The generator extracts those sources with `git show`, compiles their generated `Backup.serializer()`
with the historical model annotations, serializes via `ProtoBuf`, and gzip-compresses the result.
It does not import the current common backup schema or `BackupCodec`.

Regenerate offline from the repository root:

```powershell
./scripts/generate-android-backup-fixture.ps1
```

The fixture covers manga, chapters, categories, history, tracking, application preferences,
source preferences, sources, extension repositories, and both legacy `viewer` and modern
`viewer_flags`. Its SHA-256 is
`43FA65A3469932F4DA2794E8BDF69C7BEF7D65D4E77FE894E1B1798ED1EFAD8D` and is asserted by
`BackupCodecContractTest`.

## Desktop first-writer fixture provenance

`desktop-first-writer.tachibk` is generated from the complete Desktop model at the first writer commit
`8c6d18c20bf86c37a11da274f12eb65f31378a8b`. Run
`./scripts/generate-desktop-first-writer-fixture.ps1`; the script extracts the historical source with `git show`,
compiles its generated `Backup.serializer()` in isolation, and writes gzip-compressed protobuf directly. It does
not import the current common schema or `BackupCodec`.

SHA-256: `45949D2FD91F443CAB4BBF2BFFA6FE37E039A3CE8E7EAED032F32FA935E87D2D`.
