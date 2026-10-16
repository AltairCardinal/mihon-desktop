import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import worker from "../src/worker.mjs";

const migration = readFileSync(new URL("../migrations/0001_feedback.sql", import.meta.url), "utf8");
const origin = "https://feedback.example.org";
const redactedSummary = readFileSync(new URL("./fixtures/redacted-summary.txt", import.meta.url), "utf8").trim();
const valid = () => ({
  submissionId: crypto.randomUUID(),
  title: "Reader problem report",
  description: "The application cannot display my fictional test page.",
  summary: "Application: Mihon\nVersion: 0.19.4\nOS: Android 16\nArchitecture: x86_64\nRuntime: Android SDK 36",
  consent: true,
  turnstileToken: "challenge-token",
});

function fixture(overrides = {}) {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(migration);
  const calls = [];
  const env = {
    DB: {
      prepare(sql) {
        let args = [];
        return {
          bind(...values) { args = values; return this; },
          async first() { return sqlite.prepare(sql).get(...args) ?? null; },
          async all() { return { results: sqlite.prepare(sql).all(...args) }; },
          async run() { const result = sqlite.prepare(sql).run(...args); return { meta: { changes: Number(result.changes) } }; },
        };
      },
    },
    PUBLIC_ORIGIN: origin,
    TURNSTILE_SITE_KEY: "production-site-key",
    TURNSTILE_SECRET_KEY: "production-secret-key",
    IP_HASH_SECRET: "random-private-secret-at-least-32-characters-long",
    GITHUB_TOKEN: "github-fine-grained-token",
    ...overrides,
  };
  const originalFetch = globalThis.fetch;
  let verify = { success: true, hostname: "feedback.example.org", action: "feedback" };
  let github = () => new Response(JSON.stringify({ html_url: "https://github.com/AltairCardinal/mihon-desktop/issues/99" }), { status: 201 });
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), options });
    if (String(url).includes("siteverify")) return Response.json(verify);
    return github();
  };
  return {
    env, sqlite, calls,
    setVerify(value) { verify = value; },
    setGithub(value) { github = value; },
    async submit(body = valid(), ip = "192.0.2.10", headers = {}) {
      return worker.fetch(new Request(`${origin}/api/feedback`, {
        method: "POST", headers: { "Content-Type": "application/json", Origin: origin, "CF-Connecting-IP": ip, ...headers },
        body: typeof body === "string" ? body : JSON.stringify(body),
      }), env);
    },
    close() { globalThis.fetch = originalFetch; sqlite.close(); },
  };
}

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
    assert.equal((await f.submit(" ".repeat(32769))).status, 413);
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
        if (emitted++ < 5) controller.enqueue(new Uint8Array(8192).fill(32));
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
