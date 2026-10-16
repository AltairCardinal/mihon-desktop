import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import worker from "../src/worker.mjs";
import { fixture, origin, valid } from "./worker-fixture.mjs";
const migration = readFileSync(new URL("../migrations/0001_feedback.sql", import.meta.url), "utf8");
const privateLogMigration = readFileSync(new URL("../migrations/0002_private_crash_log.sql", import.meta.url), "utf8");
const redactedSummary = readFileSync(new URL("./fixtures/redacted-summary.txt", import.meta.url), "utf8").trim();

test("real worker persists anonymous feedback and returns an unguessable pending receipt", async () => {
  const f = fixture();
  try {
    const response = await f.submit();
    assert.equal(response.status, 202);
    const body = await response.json();
    assert.equal(body.status, "received_pending");
    assert.match(body.receipt, /^[A-Za-z0-9_-]{40,}$/);
    assert.equal(body.issueUrl, undefined);
    const row = f.sqlite.prepare("SELECT * FROM reports").get();
    assert.equal(row.status, "PENDING");
    assert.notEqual(row.ip_key, "192.0.2.10");
    assert.match(row.ip_key, /^[0-9a-f]{64}$/);
    assert.equal(f.calls.length, 1);
  } finally { f.close(); }
});

test("same submission is idempotent after fresh verification; changed content conflicts", async () => {
  const f = fixture();
  try {
    const input = valid();
    const first = await (await f.submit(input)).json();
    const second = await (await f.submit(input)).json();
    assert.equal(second.receipt, first.receipt);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 1);
    assert.equal((await f.submit({ ...input, description: `${input.description} changed` })).status, 409);
    f.setVerify({ success: false });
    assert.equal((await f.submit(input)).status, 400);
    assert.equal(f.calls.length, 4);
  } finally { f.close(); }
});

test("parallel requests cannot exceed per-IP quota", async () => {
  const f = fixture();
  try {
    const statuses = await Promise.all(Array.from({ length: 10 }, async () => (await f.submit()).status));
    assert.equal(statuses.filter((status) => status === 202).length, 3);
    assert.equal(statuses.filter((status) => status === 429).length, 7);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 3);
  } finally { f.close(); }
});

test("parallel requests cannot exceed daily site quota", async () => {
  const f = fixture();
  try {
    const statuses = await Promise.all(Array.from({ length: 60 }, async (_, i) => (await f.submit(valid(), `192.0.2.${i + 1}`)).status));
    assert.equal(statuses.filter((status) => status === 202).length, 50);
    assert.equal(statuses.filter((status) => status === 429).length, 10);
  } finally { f.close(); }
});

test("validation rejects malformed, extra, non-consenting, unsafe and oversized input before storage", async () => {
  const f = fixture();
  try {
    const bad = [
      "{", [], null,
      { ...valid(), extra: "unexpected" },
      { ...valid(), submissionId: "guessable" },
      { ...valid(), consent: false },
      { ...valid(), title: "short".slice(0, 4) },
      { ...valid(), title: "x".repeat(121) },
      { ...valid(), description: "too short" },
      { ...valid(), description: "x".repeat(6001) },
      { ...valid(), summary: "Email: private@example.org" },
      { ...valid(), summary: "OS: Android\nOS: duplicate" },
      { ...valid(), summary: "OS: <script>evil</script>" },
      { ...valid(), turnstileToken: "" },
    ];
    for (const input of bad) assert.equal((await f.submit(input)).status, 400);
    assert.equal((await f.submit(" ".repeat(640 * 1024 + 1))).status, 413);
    assert.equal((await f.submit(valid(), "192.0.2.10", { Origin: "https://attacker.example" })).status, 403);
    assert.equal((await f.submit(valid(), "192.0.2.10", { "Content-Type": "text/plain" })).status, 415);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
  } finally { f.close(); }
});

test("Unicode length counts code points, optional summary can be empty", async () => {
  const f = fixture();
  try {
    assert.equal((await f.submit({ ...valid(), title: "😀".repeat(120), summary: "" })).status, 202);
    assert.equal((await f.submit({ ...valid(), title: "😀".repeat(121) })).status, 400);
  } finally { f.close(); }
});

test("native five-line redacted summary is accepted without permitting arbitrary brackets", async () => {
  const f = fixture();
  try {
    assert.equal((await f.submit({ ...valid(), summary: redactedSummary })).status, 202);
    assert.equal(f.sqlite.prepare("SELECT summary FROM reports").get().summary, redactedSummary);
    for (const summary of ["Version: [secret]", "OS: Windows [redacted] [private]", "Runtime: Java [redacted]evil"]) {
      assert.equal((await f.submit({ ...valid(), summary })).status, 400);
    }
  } finally { f.close(); }
});

test("captcha failure, wrong hostname, wrong action and verification errors fail closed", async () => {
  const f = fixture();
  try {
    for (const result of [
      { success: false },
      { success: true, hostname: "attacker.example", action: "feedback" },
      { success: true, hostname: "feedback.example.org", action: "other" },
    ]) {
      f.setVerify(result);
      assert.equal((await f.submit()).status, 400);
    }
    globalThis.fetch = async () => { throw new Error("network"); };
    assert.equal((await f.submit()).status, 503);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
  } finally { f.close(); }
});

test("missing credentials and Cloudflare testing keys disable config and submissions", async () => {
  for (const override of [
    { GITHUB_TOKEN: "" }, { IP_HASH_SECRET: "short" }, { TURNSTILE_SECRET_KEY: "" },
    { TURNSTILE_SITE_KEY: "1x00000000000000000000AA" },
    { TURNSTILE_SECRET_KEY: "1x0000000000000000000000000000000AA" },
    { PUBLIC_ORIGIN: "http://feedback.example.org" }, { DB: null },
  ]) {
    const f = fixture(override);
    try {
      const config = await worker.fetch(new Request(`${origin}/api/config`), f.env);
      assert.equal((await config.json()).accepting, false);
      assert.equal((await f.submit()).status, 503);
      assert.equal(f.calls.length, 0);
    } finally { f.close(); }
  }
});

test("configuration exposes only public metadata, receipt and unknown paths reveal no report", async () => {
  const f = fixture();
  try {
    const config = await worker.fetch(new Request(`${origin}/api/config`), f.env);
    assert.deepEqual(await config.json(), { accepting: true, siteKey: "production-site-key" });
    const accepted = await (await f.submit()).json();
    assert.equal((await worker.fetch(new Request(`${origin}/api/feedback/${accepted.receipt}`), f.env)).status, 404);
  } finally { f.close(); }
});

test("scheduled dispatch uses fixed repository, marks sent and does not repeat", async () => {
  const f = fixture();
  try {
    await f.submit();
    await worker.scheduled({}, f.env);
    await worker.scheduled({}, f.env);
    const github = f.calls.filter((call) => call.url.includes("api.github.com"));
    assert.equal(github.length, 1);
    assert.equal(github[0].url, "https://api.github.com/repos/AltairCardinal/mihon-desktop/issues");
    assert.equal(github[0].options.method, "POST");
    const body = JSON.parse(github[0].options.body);
    assert.match(body.body, /consented to public/);
    assert.doesNotMatch(body.body, /192\.0\.2\.10/);
    assert.equal(f.sqlite.prepare("SELECT status FROM reports").get().status, "SENT");
  } finally { f.close(); }
});

test("public Issue contents cannot trigger mentions or render user-supplied Markdown", async () => {
  const f = fixture();
  try {
    await f.submit({ ...valid(), title: "Report for @maintainer", description: "@maintainer [click](https://evil.example)\n```\n<script>fictional</script>" });
    await worker.scheduled({}, f.env);
    const call = f.calls.find((item) => item.url.includes("api.github.com"));
    const body = JSON.parse(call.options.body);
    assert.equal(body.title, "[Feedback] Report for ＠maintainer");
    assert.match(body.body, /\n    @maintainer \[click\]/);
    assert.match(body.body, /\n    ```\n    <script>/);
  } finally { f.close(); }
});

test("concurrent scheduled invocations claim a report only once", async () => {
  const f = fixture();
  try {
    await f.submit();
    await Promise.all([worker.scheduled({}, f.env), worker.scheduled({}, f.env)]);
    assert.equal(f.calls.filter((call) => call.url.includes("api.github.com")).length, 1);
  } finally { f.close(); }
});

test("GitHub 429 is deferred safely; uncertain 500 and timeouts are never posted twice", async () => {
  for (const kind of ["429", "500", "timeout"]) {
    const f = fixture();
    try {
      f.setGithub(() => {
        if (kind === "timeout") throw new DOMException("aborted", "TimeoutError");
        return new Response("unavailable", { status: Number(kind), headers: { "Retry-After": "600" } });
      });
      await f.submit();
      await worker.scheduled({}, f.env);
      await worker.scheduled({}, f.env);
      assert.equal(f.calls.filter((call) => call.url.includes("api.github.com")).length, 1);
      const row = f.sqlite.prepare("SELECT status, next_attempt FROM reports").get();
      assert.equal(row.status, kind === "429" ? "PENDING" : "REVIEW");
      if (kind === "429") assert.ok(row.next_attempt > Date.now() / 1000);
    } finally { f.close(); }
  }
});

test("stale claims require manual review, retention removes reports after 30 days", async () => {
  const f = fixture();
  try {
    await f.submit();
    f.sqlite.prepare("UPDATE reports SET status = 'CLAIMED', claimed_at = ?").run(Math.floor(Date.now() / 1000) - 1000);
    await worker.scheduled({}, f.env);
    assert.equal(f.sqlite.prepare("SELECT status FROM reports").get().status, "REVIEW");
    assert.equal(f.calls.filter((call) => call.url.includes("api.github.com")).length, 0);
    f.sqlite.prepare("UPDATE reports SET created_at = ?").run(Math.floor(Date.now() / 1000) - 31 * 86400);
    await worker.scheduled({}, f.env);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
  } finally { f.close(); }
});

test("retention cleanup continues when forwarding credentials are missing", async () => {
  const f = fixture();
  try {
    await f.submit();
    f.sqlite.prepare("UPDATE reports SET created_at = ?").run(Math.floor(Date.now() / 1000) - 31 * 86400);
    f.env.GITHUB_TOKEN = "";
    await worker.scheduled({}, f.env);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
    assert.equal(f.calls.length, 1);
  } finally { f.close(); }
});

test("streamed body limit does not trust a false Content-Length", async () => {
  const f = fixture();
  try {
    let emitted = 0;
    const stream = new ReadableStream({
      pull(controller) {
        if (emitted++ < 11) controller.enqueue(new Uint8Array(65536).fill(32));
        else controller.close();
      },
    });
    const request = new Request(`${origin}/api/feedback`, {
      method: "POST", body: stream, duplex: "half",
      headers: { Origin: origin, "Content-Type": "application/json", "Content-Length": "1", "CF-Connecting-IP": "192.0.2.10" },
    });
    assert.equal((await worker.fetch(request, f.env)).status, 413);
    assert.equal(f.calls.length, 0);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
  } finally { f.close(); }
});

test("non-destructive migration preserves populated legacy reports and defaults private logs to empty", () => {
  const db = new DatabaseSync(":memory:");
  try {
    db.exec(migration);
    db.prepare("INSERT INTO reports(submission_id,content_hash,receipt,title,description,summary,ip_key,created_at,status,issue_url) VALUES(?,?,?,?,?,?,?,?,?,?)")
      .run("legacy-report", "legacy-hash", "legacy-receipt", "Legacy title", "Legacy description", "OS: Windows 11", "legacy-ip-hash", 1234, "SENT", "https://github.com/AltairCardinal/mihon-desktop/issues/99");
    const before = db.prepare("SELECT * FROM reports").get();
    db.exec(privateLogMigration);
    const after = db.prepare("SELECT * FROM reports").get();
    for (const key of Object.keys(before)) assert.equal(after[key], before[key]);
    assert.equal(after.crash_log, "");
    assert.equal(after.crash_log_consent, 0);
  } finally { db.close(); }
});

test("private log requires independent explicit consent, normalizes CRLF without trimming, and remains private", async () => {
  const f = fixture();
  try {
    const input = { ...valid(), crashLog: "  Fictional crash fixture\r\n at example.Frame\r\n\tEnd\n ", crashLogConsent: true };
    assert.equal((await f.submit({ ...input, crashLogConsent: false })).status, 400);
    assert.equal((await f.submit({ ...input, crashLogConsent: undefined })).status, 400);
    const response = await f.submit(input);
    assert.equal(response.status, 202);
    const publicBody = await response.text();
    assert.doesNotMatch(publicBody, /Fictional crash|crashLog|crash_log|download/i);
    const row = f.sqlite.prepare("SELECT crash_log, crash_log_consent FROM reports").get();
    assert.equal(row.crash_log, input.crashLog.replace(/\r\n/g, "\n"));
    assert.equal(row.crash_log_consent, 1);
    f.reads.length = 0;
    await worker.scheduled({}, f.env);
    assert.ok(f.reads.length > 0);
    assert.ok(f.reads.every((keys) => !keys.includes("crash_log")));
    const forwarded = f.calls.find((call) => call.url.includes("api.github.com"));
    const issue = JSON.parse(forwarded.options.body);
    assert.doesNotMatch(issue.body, /Fictional crash|example.Frame|download/i);
    assert.match(issue.body, /maintainer.only crash log/i);
    for (const path of ["/api/config", "/api/feedback/legacy-receipt", "/api/crash-log"]) {
      const body = await (await worker.fetch(new Request(`${origin}${path}`), f.env)).text();
      assert.doesNotMatch(body, /Fictional crash|example.Frame|crash_log/);
    }
  } finally { f.close(); }
});

test("private log UTF-8 byte boundary accepts 256 KiB including JSON escapes and rejects larger or invalid text", async () => {
  const f = fixture();
  try {
    for (const crashLog of ["x".repeat(256 * 1024), "\t".repeat(256 * 1024), "中".repeat(87381) + "x"]) {
      assert.equal((await f.submit({ ...valid(), crashLog, crashLogConsent: true })).status, 202);
    }
    for (const crashLog of ["x".repeat(256 * 1024 + 1), "中".repeat(87382), "bad\u0000log", "bad\u001blog", "bad\u0085log", "bad\ud800log", "bad\udc00log", "bad\ufffdlog"]) {
      assert.equal((await f.submit({ ...valid(), crashLog, crashLogConsent: true })).status, 400);
    }
  } finally { f.close(); }
});

test("same submission cannot replace a private log, CRLF retry is idempotent and removal allows a fresh no-log report", async () => {
  const f = fixture();
  try {
    const input = { ...valid(), crashLog: "Fictional log\r\n", crashLogConsent: true };
    const first = await f.submit(input);
    assert.equal(first.status, 202);
    const receipt = (await first.json()).receipt;
    assert.equal((await (await f.submit({ ...input, crashLog: "Fictional log\n" })).json()).receipt, receipt);
    assert.equal((await f.submit({ ...input, crashLog: "Different fictional log" })).status, 409);
    assert.equal((await f.submit({ ...input, crashLog: "", crashLogConsent: false })).status, 409);
    assert.equal((await f.submit({ ...valid(), crashLog: "", crashLogConsent: false })).status, 202);
  } finally { f.close(); }
});

test("no-log retry of a pre-migration request keeps its original content hash and receipt", async () => {
  const f = fixture();
  try {
    const input = valid();
    const hash = Buffer.from(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(JSON.stringify([input.title, input.description, input.summary])))).toString("hex");
    f.sqlite.prepare("INSERT INTO reports(submission_id,content_hash,receipt,title,description,summary,ip_key,created_at) VALUES(?,?,?,?,?,?,?,?)")
      .run(input.submissionId, hash, "previous-receipt", input.title, input.description, input.summary, "previous-ip", Math.floor(Date.now() / 1000));
    const response = await f.submit(input);
    assert.equal(response.status, 202);
    assert.equal((await response.json()).receipt, "previous-receipt");
  } finally { f.close(); }
});

test("30-day report cleanup deletes the private log with its owning report", async () => {
  const f = fixture();
  try {
    assert.equal((await f.submit({ ...valid(), crashLog: "Fictional expired private log", crashLogConsent: true })).status, 202);
    assert.equal(f.sqlite.prepare("SELECT crash_log FROM reports").get().crash_log, "Fictional expired private log");
    f.sqlite.prepare("UPDATE reports SET created_at = ?").run(Math.floor(Date.now() / 1000) - 31 * 86400);
    await worker.scheduled({}, f.env);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
    assert.equal(f.calls.filter((call) => call.url.includes("api.github.com")).length, 0);
  } finally { f.close(); }
});
