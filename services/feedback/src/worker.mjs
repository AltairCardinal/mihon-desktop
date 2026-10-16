import { MAX_LOG_BYTES, crashLogBytes, normalizeCrashLog, validCrashLogText } from "../public/crash-log.mjs";

const encoder = new TextEncoder();
const ISSUE_ENDPOINT = "https://api.github.com/repos/AltairCardinal/mihon-desktop/issues";
const MAX_BODY = 640 * 1024;
const RETENTION_SECONDS = 30 * 86400;
const HEADERS = {
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
  "Content-Security-Policy": "default-src 'self'; script-src 'self' https://challenges.cloudflare.com; style-src 'self'; frame-src https://challenges.cloudflare.com; connect-src 'self' https://challenges.cloudflare.com; img-src 'self' data:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
};

function json(body, status = 200, extra = {}) {
  return Response.json(body, { status, headers: { ...HEADERS, ...extra } });
}

function ready(env) {
  try {
    const url = new URL(env.PUBLIC_ORIGIN);
    return Boolean(env.DB && url.protocol === "https:" && url.origin === env.PUBLIC_ORIGIN &&
      typeof env.IP_HASH_SECRET === "string" && env.IP_HASH_SECRET.length >= 32 &&
      typeof env.GITHUB_TOKEN === "string" && env.GITHUB_TOKEN.length >= 16 &&
      typeof env.TURNSTILE_SITE_KEY === "string" && env.TURNSTILE_SITE_KEY.length >= 10 &&
      typeof env.TURNSTILE_SECRET_KEY === "string" && env.TURNSTILE_SECRET_KEY.length >= 16 &&
      !/^[123]x0{8,}/.test(env.TURNSTILE_SITE_KEY) && !/^[123]x0{8,}/.test(env.TURNSTILE_SECRET_KEY));
  } catch { return false; }
}

async function readBody(request) {
  const declared = Number(request.headers.get("Content-Length"));
  if (declared > MAX_BODY) throw new RangeError("body_too_large");
  if (!request.body) throw new SyntaxError("invalid_input");
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY) {
        await reader.cancel();
        throw new RangeError("body_too_large");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}

function validate(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const allowed = ["submissionId", "title", "description", "summary", "consent", "turnstileToken", "crashLog", "crashLogConsent"];
  if (Object.keys(value).some((key) => !allowed.includes(key))) return null;
  if (typeof value.submissionId !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.submissionId)) return null;
  if (value.consent !== true || typeof value.title !== "string" || typeof value.description !== "string" ||
      (value.summary !== undefined && typeof value.summary !== "string") || typeof value.turnstileToken !== "string" ||
      (value.crashLog !== undefined && typeof value.crashLog !== "string") ||
      (value.crashLogConsent !== undefined && typeof value.crashLogConsent !== "boolean")) return null;
  const title = value.title.trim();
  const description = value.description.trim();
  const summary = (value.summary ?? "").replace(/\r\n/g, "\n").trim();
  const crashLog = normalizeCrashLog(value.crashLog ?? "");
  if (!validCrashLogText(crashLog) || crashLogBytes(crashLog) > MAX_LOG_BYTES ||
      (crashLog !== "" && value.crashLogConsent !== true)) return null;
  if ([...title].length < 5 || [...title].length > 120 || /[\p{Cc}\p{Cf}]/u.test(title) ||
      [...description].length < 20 || [...description].length > 6000 ||
      /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(description) ||
      [...summary].length > 1000 || !value.turnstileToken || value.turnstileToken.length > 2048) return null;
  const fields = new Set();
  if (summary) {
    for (const line of summary.split("\n")) {
      const match = /^(Application|Version|OS|Architecture|Runtime): (.{1,200})$/u.exec(line);
      if (!match || fields.has(match[1])) return null;
      const sentinel = (match[1] === "Version" && match[2] === "[redacted]") ||
        (match[1] === "OS" && /^(?:Android|Windows|macOS|Linux|Unknown) \[redacted\]$/.test(match[2])) ||
        (match[1] === "Runtime" && /^(?:Android SDK|Java) \[redacted\]$/.test(match[2]));
      if (!/^[\p{L}\p{N} ._+()/:\-]+$/u.test(match[2]) && !sentinel) return null;
      fields.add(match[1]);
    }
  }
  return { submissionId: value.submissionId.toLowerCase(), title, description, summary, crashLog, turnstileToken: value.turnstileToken };
}

async function hexDigest(value, secret) {
  const bytes = secret
    ? await crypto.subtle.sign("HMAC", await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]), encoder.encode(value))
    : await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function newReceipt() {
  return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
    .replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

async function submit(request, env) {
  if (!ready(env)) return json({ error: "unavailable" }, 503);
  if (new URL(request.url).origin !== env.PUBLIC_ORIGIN || request.headers.get("Origin") !== env.PUBLIC_ORIGIN) {
    return json({ error: "origin_rejected" }, 403);
  }
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("Content-Type") ?? "")) {
    return json({ error: "json_required" }, 415);
  }
  let value;
  try { value = validate(await readBody(request)); }
  catch (error) { return json({ error: error instanceof RangeError ? "body_too_large" : "invalid_input" }, error instanceof RangeError ? 413 : 400); }
  if (!value) return json({ error: "invalid_input" }, 400);
  const ip = request.headers.get("CF-Connecting-IP");
  if (!ip) return json({ error: "unavailable" }, 503);
  let captcha;
  try {
    const verified = await fetch("https://challenges.cloudflare.com/turnstile/v0/siteverify", {
      method: "POST",
      body: new URLSearchParams({ secret: env.TURNSTILE_SECRET_KEY, response: value.turnstileToken }),
      signal: AbortSignal.timeout(10000),
    });
    if (!verified.ok) return json({ error: "verification_unavailable" }, 503);
    captcha = await verified.json();
  } catch { return json({ error: "verification_unavailable" }, 503); }
  if (captcha.success !== true || captcha.hostname !== new URL(env.PUBLIC_ORIGIN).hostname || captcha.action !== "feedback") {
    return json({ error: "verification_failed" }, 400);
  }
  // Preserve pre-migration retries for reports without a log. A private log,
  // when present, is part of the immutable submission contents.
  const contents = [value.title, value.description, value.summary];
  if (value.crashLog !== "") contents.push(value.crashLog);
  const contentHash = await hexDigest(JSON.stringify(contents));
  const receipt = newReceipt();
  const now = Math.floor(Date.now() / 1000);
  const hour = Math.floor(now / 3600) * 3600;
  const day = Math.floor(now / 86400) * 86400;
  const ipKey = await hexDigest(ip, env.IP_HASH_SECRET);
  // D1 serializes writes. Quota checks and insertion are one SQLite statement,
  // so concurrent requests cannot pass a separate check then overfill a quota.
  await env.DB.prepare(`
    INSERT OR IGNORE INTO reports
      (submission_id, content_hash, receipt, title, description, summary, ip_key, created_at, crash_log, crash_log_consent)
    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
    WHERE (SELECT count(*) FROM reports WHERE ip_key = ? AND created_at >= ?) < 3
      AND (SELECT count(*) FROM reports WHERE created_at >= ?) < 50
  `).bind(value.submissionId, contentHash, receipt, value.title, value.description, value.summary, ipKey, now, value.crashLog, value.crashLog !== "" ? 1 : 0, ipKey, hour, day).run();
  const row = await env.DB.prepare("SELECT content_hash, receipt FROM reports WHERE submission_id = ?").bind(value.submissionId).first();
  if (!row) return json({ error: "rate_limited" }, 429, { "Retry-After": "3600" });
  if (row.content_hash !== contentHash) return json({ error: "submission_conflict" }, 409);
  return json({ status: "received_pending", receipt: row.receipt }, 202);
}

async function dispatch(env) {
  if (!env.DB) return;
  const now = Math.floor(Date.now() / 1000);
  await env.DB.prepare("DELETE FROM reports WHERE created_at < ?").bind(now - RETENTION_SECONDS).run();
  if (!ready(env)) return;
  // A crashed or timed-out invocation might have created an Issue already.
  // Stale claims are deliberately not leased for automatic resubmission.
  await env.DB.prepare("UPDATE reports SET status = 'REVIEW' WHERE status = 'CLAIMED' AND claimed_at < ?").bind(now - 900).run();
  const pending = await env.DB.prepare("SELECT submission_id FROM reports WHERE status = 'PENDING' AND next_attempt <= ? ORDER BY created_at LIMIT 5").bind(now).all();
  for (const candidate of pending.results) {
    const row = await env.DB.prepare("UPDATE reports SET status = 'CLAIMED', claimed_at = ? WHERE submission_id = ? AND status = 'PENDING' RETURNING submission_id, title, description, summary, crash_log_consent")
      .bind(now, candidate.submission_id).first();
    if (!row) continue;
    let status = "REVIEW";
    let issueUrl = null;
    let nextAttempt = 0;
    try {
      const response = await fetch(ISSUE_ENDPOINT, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.GITHUB_TOKEN}`,
          Accept: "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28",
          "User-Agent": "mihon-anonymous-feedback",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          title: `[Feedback] ${row.title.replaceAll("@", "＠")}`,
          body: `Anonymous feedback. The submitter explicitly consented to public GitHub publication.\n\n${row.description.replace(/\r\n?/g, "\n").split("\n").map((line) => `    ${line}`).join("\n")}\n\n${row.summary ? `Safe application summary:\n\n\`\`\`text\n${row.summary}\n\`\`\`\n\n` : ""}${row.crash_log_consent === 1 ? "A maintainer-only crash log is attached. It is not published to GitHub.\n\n" : ""}<!-- feedback-submission:${row.submission_id} -->`,
        }),
        signal: AbortSignal.timeout(15000),
      });
      if (response.status === 201) {
        const result = await response.json();
        if (/^https:\/\/github\.com\/AltairCardinal\/mihon-desktop\/issues\/\d+$/.test(result.html_url ?? "")) {
          status = "SENT";
          issueUrl = result.html_url;
        }
      } else if (response.status === 429) {
        status = "PENDING";
        const seconds = Number(response.headers.get("Retry-After"));
        nextAttempt = now + (Number.isFinite(seconds) && seconds > 0 ? Math.min(Math.max(seconds, 300), 86400) : 3600);
      }
    } catch { /* Unknown delivery outcome: retain for manual reconciliation. */ }
    await env.DB.prepare("UPDATE reports SET status = ?, issue_url = ?, next_attempt = ? WHERE submission_id = ? AND status = 'CLAIMED'")
      .bind(status, issueUrl, nextAttempt, row.submission_id).run();
  }
}

export default {
  async fetch(request, env) {
    try {
      const path = new URL(request.url).pathname;
      if (path === "/api/config" && request.method === "GET") {
        const accepting = ready(env);
        return json(accepting ? { accepting, siteKey: env.TURNSTILE_SITE_KEY } : { accepting });
      }
      if (path === "/api/feedback" && request.method === "POST") return await submit(request, env);
      if (path.startsWith("/api/")) return json({ error: "not_found" }, 404);
      if (env.ASSETS && (request.method === "GET" || request.method === "HEAD")) {
        const response = await env.ASSETS.fetch(request);
        const headers = new Headers(response.headers);
        for (const [key, value] of Object.entries(HEADERS)) headers.set(key, value);
        return new Response(response.body, { status: response.status, headers });
      }
      return json({ error: "not_found" }, 404);
    } catch { return json({ error: "unavailable" }, 503); }
  },
  async scheduled(_event, env) { await dispatch(env); },
};
