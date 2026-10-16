# Anonymous feedback service

Small, framework-free Cloudflare Worker, D1 database, Turnstile form and five-minute GitHub dispatch job. Both native applications open the same public webpage. **Users need no account**, and no credentials are shipped in either application or the webpage.

## Runtime contract

- `GET /` serves a mobile/desktop form with system dark/light theme and Chinese/English switch.
- `GET /api/config` returns only `accepting` and, when configured, the public Turnstile site key. Missing credentials or known Cloudflare test keys disable submission.
- `POST /api/feedback` accepts only `submissionId` (UUIDv4), `title` (5–120 Unicode code points), `description` (20–6000), optional `summary` (≤1000), `consent: true`, `turnstileToken` (≤2048), and optional `crashLog`/`crashLogConsent`. The body is bounded to **640 KiB** while streaming, independent of `Content-Length`, to accommodate JSON escaping of a 256 KiB text log.
- The optional manually pasted summary allows unique `Application`, `Version`, `OS`, `Architecture`, `Runtime` lines, with a restricted value alphabet. The native summary's literal `[redacted]` is accepted only as `Version: [redacted]`, an existing OS platform followed by `[redacted]`, or `Runtime: Java/Android SDK [redacted]`; arbitrary bracketed values remain rejected. No clipboard read, URL payload, local storage, email field or general attachment support.
- Optional **private crash logs** accept UTF-8 `.txt`, `.log` and desktop `.log.1/.2/.3` files. Files over **4 MiB** are refused before reading. Strict local decoding removes a UTF-8 BOM, rejects invalid encoding and binary control characters, then presents an editable preview. Selecting a file only reads it locally; there is no upload until submission.
- Log submissions are limited to **256 KiB of UTF-8 bytes**, with no silent truncation. CRLF is normalized to LF; leading/trailing spaces, tabs, lone CR and LF are not trimmed. NUL, binary control characters (except TAB/LF/CR), unpaired surrogates and replacement characters are rejected. A large preview can be edited smaller or removed.
- Nonempty `crashLog` requires independent `crashLogConsent: true`. Its separate checkbox starts unchecked and resets after editing, file replacement or removal. This grants **project maintainers only** permission to view the log, with normal 30-day database retention; it does not authorize GitHub publication. Busy/received submissions lock all report and log inputs to preserve the captured snapshot.
- Consent to **public GitHub publication** starts unchecked and covers only the title, description and safe summary. A successful response is `202 {status:"received_pending",receipt:"..."}` after D1 persistence, not a claim that an Issue exists. The random 256-bit receipt is for the user's records; there is deliberately no report lookup/list/log-download API. Public responses never contain a private log, file name, local path or download link.
- Same UUID and canonical contents return the original receipt only after a fresh valid Turnstile response. Different contents, including a changed/removed private log, with the same UUID return 409. The original no-log content hash is retained for pre-migration retries. Failed submissions retain the report, log draft and UUID only in the open page's memory. Reloading/closing loses the draft.
- HMAC-SHA256 of the connecting IP is stored for limits; the raw IP and Turnstile token are never persisted or forwarded. Siteverify receives the token/secret without an IP parameter. Cloudflare still processes the network request and challenge under its own policies.
- Every report insert includes both limit checks in **one SQLite write statement**: three reports per IP per UTC clock hour, fifty across the service per UTC day. Concurrent requests cannot bypass the quotas. Retried existing reports do not consume additional report slots.
- Cron claims at most five pending reports per invocation, then posts to the fixed `AltairCardinal/mihon-desktop` Issues endpoint. It explicitly returns only public report columns and the private-log consent flag, never reads `crash_log`, and only notes that a maintainer-only log is attached. No private log contents, file name, local path or download link is sent to GitHub. User descriptions are indented plain-text blocks; title `@` signs become full-width to prevent mention notifications.

## Required deployment configuration

The exact binding names in `wrangler.jsonc` are:

| Name | Kind | Purpose |
| --- | --- | --- |
| `DB` | D1 binding | `mihon-feedback`, with migration applied |
| `ASSETS` | Workers static-assets binding | `public/`, with `run_worker_first: true` so security headers apply |
| `PUBLIC_ORIGIN` | public var | Exact deployed HTTPS origin, without trailing slash |
| `TURNSTILE_SITE_KEY` | public var | Production Turnstile widget, limited to that origin's hostname |
| `TURNSTILE_SECRET_KEY` | Worker secret | Matching production Siteverify secret |
| `IP_HASH_SECRET` | Worker secret | Random private value of at least 32 characters |
| `GITHUB_TOKEN` | Worker secret | Dedicated fine-grained token, only this repository, Issues read/write permission |

Turnstile's widget and Siteverify must agree on action `feedback` and the exact hostname. Cloudflare's well-known testing keys are rejected even when another setting is present. Do not reuse a broad personal `gh` token, write secrets to the repository, or place them in command arguments, URL query strings or logs. Configure secrets through Cloudflare's dashboard or stdin/API with redacted output.

From this directory, after maintainer authorization:

```text
npm ci
npm test
npx wrangler d1 migrations apply mihon-feedback --remote
npm run check
npm run deploy
```

Configure the three secrets before enabling a public submission acceptance check. `npm run check` bundles only; it is not proof of deployed bindings, valid credentials, an actual challenge or GitHub delivery. A real acceptance run must exercise the public form, confirm a persisted receipt, and confirm the maintenance job's resulting Issue using fictional content. Keep the browser's GitHub session logged out for the user path.

Apply `0002_private_crash_log.sql` **before** deploying the log-capable Worker. It adds `crash_log TEXT NOT NULL DEFAULT ''` and `crash_log_consent INTEGER NOT NULL DEFAULT 0`; existing reports, receipts and dispatch state remain unchanged and their logs default to absent. Existing clients without either new field remain compatible. No additional Cloudflare resource, R2 bucket, GitHub permission or credential is needed. If deployment is rolled back after migration, the old Worker still accepts no-log reports; do not drop the new columns or overwrite retained private data.

At the configured maximum 50 accepted reports/day, 30 days of full 256 KiB logs use up to **375 MiB** of raw log text, plus SQLite/public-report overhead. Monitor D1 storage and platform limits. The limit is a bound, not a promise of unlimited free storage; exhausted Cloudflare capacity fails with a generic temporary error and preserves the browser draft.

## Maintainer-only log retrieval

Only maintainers with an existing authorized **Wrangler OAuth session** may retrieve a log. The submission UUID is in the Issue's hidden `feedback-submission:<UUID>` marker. There is no anonymous or receipt-based HTTP retrieval route. From this service directory:

```text
node scripts/export-crash-log.mjs --submission-id <UUIDv4> --output <new-file-in-a-private-directory-outside-the-repository>
```

The tool uses the fixed `mihon-feedback` D1 binding through the pinned local Wrangler installation. It selects only the one explicitly authorized log, requires a strict UUIDv4 and an output path, rejects the repository/all registered worktrees and directory links back into them, and refuses existing files or symbolic-link destinations. After the remote query, it resolves the requested path again and checks the canonical destination and parent directory's filesystem identity before creating the file; a directory changed during retrieval is refused. It never prints log contents, provider error bodies or credentials; success prints only the file basename. Subprocess output is captured in memory, not inherited. The export does not start login, silently use API-token environment credentials or broaden GitHub permissions; if OAuth is missing/expired, authorize Wrangler separately and retry.

Store exports in an OS-protected maintainer directory, not a shared/public folder. On systems supporting Unix modes, the file is created with mode `0600`; Windows uses the destination directory's ACL. The 30-day server cleanup does not delete locally exported copies; maintainers must remove those when no longer needed and must never paste private logs into public Issues. Missing logs, invalid UUIDs, expired records and authorization failures produce generic errors without creating a file.

## Failures, duplicate prevention and maintenance

- CAPTCHA rejection, wrong hostname/action, malformed input, wrong origin, quota exhaustion and temporary storage/verification failures return generic codes without echoing contents or server details. There is no fallback to GitHub login.
- A 429 from GitHub is an explicit rejection, so the record goes back to `PENDING` with bounded delay (at least five minutes). HTTP 5xx, timeouts, malformed successful responses and other uncertain outcomes become `REVIEW`; they are **never automatically POSTed again**.
- `CLAIMED` records older than 15 minutes become `REVIEW`, covering a worker crash after GitHub may already have accepted an Issue. Do not automatically reset these records. Reconcile manually against the hidden `feedback-submission:<UUID>` marker in Issues. If an Issue exists, mark `SENT` with its URL; only after proving no delivery should a maintainer explicitly reset it to `PENDING`.
- Expired or revoked GitHub credentials require replacing the secret and manually reconciling `REVIEW` records. `accepting` checks credential presence, not token validity/permissions; deployments must verify the token separately. Missing credentials stop new reports and dispatch.
- D1 stores reports, receipts, IP HMACs, private logs, explicit log authorization and dispatch state for **at most 30 days while cron is operating**. Each cron first deletes records older than 30 days, including undelivered reports and their logs, even when forwarding credentials are missing; an outage of cron extends retention until the next invocation. Public GitHub Issues are outside that database retention and remain governed by the repository policy. Users cannot withdraw through the form; maintainers handle private-record deletion through their Cloudflare authorization and sensitive public-content removal through GitHub.
- Keep observability/request-body logging disabled. The Worker contains no application logging of payloads, credentials or IPs. Platform billing/usage and `REVIEW` rows should be checked by maintainers; quotas limit accepted reports, not arbitrary incoming HTTP traffic. Workers Free exhaustion makes the service unavailable until Cloudflare's reset.
- Existing public site keys, database IDs and the origin are not secrets. If the origin changes, update it and the Turnstile hostname together; native apps use the project's stable website entry.

## Focused verification

`npm test` runs the production Worker against the actual SQLite schema using Node 24's `node:sqlite` D1 adapter; only outbound network edges are mocked. It covers persistence, old populated-schema migration, malformed/oversized input, private log authorization and byte/text boundaries, safe summaries, CAPTCHA rejection, missing credentials/test keys, concurrent quotas and dispatch claims, idempotency including changed logs, 429 deferral, uncertain 500/timeout delivery, retention and absence of private-log reads/content in public dispatch. The jsdom tests execute the production form module with real File/FileReader/change/input/submit/click events for local strict decoding, limits, editing/removal, separate consent, immutable submission snapshots, receipts, retry/expiry, draft preservation, language switching and duplicate-click prevention. Exporter tests execute the real filesystem write against temporary directories, including existing targets and real directory links, while injecting only the remote query edge. They do not replace live Turnstile, Cloudflare migration/deployment, D1 OAuth retrieval or visual/mobile browser acceptance.
