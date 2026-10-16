# Anonymous feedback service

Small, framework-free Cloudflare Worker, D1 database, Turnstile form and five-minute GitHub dispatch job. Both native applications open the same public webpage. **Users need no account**, and no credentials are shipped in either application or the webpage.

## Runtime contract

- `GET /` serves a mobile/desktop form with system dark/light theme and Chinese/English switch.
- `GET /api/config` returns only `accepting` and, when configured, the public Turnstile site key. Missing credentials or known Cloudflare test keys disable submission.
- `POST /api/feedback` accepts only `submissionId` (UUIDv4), `title` (5–120 Unicode code points), `description` (20–6000), optional `summary` (≤1000), `consent: true`, and `turnstileToken` (≤2048). The body is bounded to 32 KiB while streaming, independent of `Content-Length`.
- The optional manually pasted summary allows unique `Application`, `Version`, `OS`, `Architecture`, `Runtime` lines, with a restricted value alphabet. The native summary's literal `[redacted]` is accepted only as `Version: [redacted]`, an existing OS platform followed by `[redacted]`, or `Runtime: Java/Android SDK [redacted]`; arbitrary bracketed values remain rejected. No clipboard read, URL payload, local storage, email field or attachment support.
- Consent to **public GitHub publication** starts unchecked. A successful response is `202 {status:"received_pending",receipt:"..."}` after D1 persistence, not a claim that an Issue exists. The random 256-bit receipt is for the user's records; there is deliberately no report lookup/list API.
- Same UUID and canonical contents return the original receipt only after a fresh valid Turnstile response. Different contents with the same UUID return 409. Failed submissions retain the draft and UUID only in the open page's memory. Reloading/closing loses the draft.
- HMAC-SHA256 of the connecting IP is stored for limits; the raw IP and Turnstile token are never persisted or forwarded. Siteverify receives the token/secret without an IP parameter. Cloudflare still processes the network request and challenge under its own policies.
- Every report insert includes both limit checks in **one SQLite write statement**: three reports per IP per UTC clock hour, fifty across the service per UTC day. Concurrent requests cannot bypass the quotas. Retried existing reports do not consume additional report slots.
- Cron claims at most five pending reports per invocation, then posts to the fixed `AltairCardinal/mihon-desktop` Issues endpoint. User descriptions are indented plain-text blocks; title `@` signs become full-width to prevent mention notifications.

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

## Failures, duplicate prevention and maintenance

- CAPTCHA rejection, wrong hostname/action, malformed input, wrong origin, quota exhaustion and temporary storage/verification failures return generic codes without echoing contents or server details. There is no fallback to GitHub login.
- A 429 from GitHub is an explicit rejection, so the record goes back to `PENDING` with bounded delay (at least five minutes). HTTP 5xx, timeouts, malformed successful responses and other uncertain outcomes become `REVIEW`; they are **never automatically POSTed again**.
- `CLAIMED` records older than 15 minutes become `REVIEW`, covering a worker crash after GitHub may already have accepted an Issue. Do not automatically reset these records. Reconcile manually against the hidden `feedback-submission:<UUID>` marker in Issues. If an Issue exists, mark `SENT` with its URL; only after proving no delivery should a maintainer explicitly reset it to `PENDING`.
- Expired or revoked GitHub credentials require replacing the secret and manually reconciling `REVIEW` records. `accepting` checks credential presence, not token validity/permissions; deployments must verify the token separately. Missing credentials stop new reports and dispatch.
- D1 stores reports, receipts, IP HMACs and dispatch state for **at most 30 days while cron is operating**. Each cron first deletes records older than 30 days, including undelivered reports; an outage of cron extends retention until the next invocation. Public GitHub Issues are outside that database retention and remain governed by the repository policy. Users cannot withdraw through the form; maintainers handle sensitive-content removal through GitHub.
- Keep observability/request-body logging disabled. The Worker contains no application logging of payloads, credentials or IPs. Platform billing/usage and `REVIEW` rows should be checked by maintainers; quotas limit accepted reports, not arbitrary incoming HTTP traffic. Workers Free exhaustion makes the service unavailable until Cloudflare's reset.
- Existing public site keys, database IDs and the origin are not secrets. If the origin changes, update it and the Turnstile hostname together; native apps use the project's stable website entry.

## Focused verification

`npm test` runs the production Worker against the actual SQLite schema using Node 24's `node:sqlite` D1 adapter; only outbound network edges are mocked. It covers persistence, malformed/oversized input, safe summaries, CAPTCHA rejection, missing credentials/test keys, concurrent quotas and dispatch claims, idempotency, 429 deferral, uncertain 500/timeout delivery, retention and public text escaping. The jsdom tests execute the production form module and real submit/click events for consent, successful receipts, retry/expiry, draft preservation, language switching and duplicate-click prevention. They do not replace live Turnstile or Cloudflare deployment acceptance.
