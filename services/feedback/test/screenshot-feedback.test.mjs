import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import worker, { nativeContentsHash } from "../src/worker.mjs";
import { fixture, origin, valid } from "./worker-fixture.mjs";

const jpeg = readFileSync(new URL("./fixtures/fictional-screenshot.jpg", import.meta.url));
const digest = async (bytes) => Buffer.from(await crypto.subtle.digest("SHA-256", bytes)).toString("hex");

test("v2 migration preserves populated v1 reports, private logs and outstanding verification sessions", () => {
  const db = new DatabaseSync(":memory:");
  try {
    for (const migration of ["0001_feedback.sql", "0002_private_crash_log.sql", "0003_native_verification.sql"]) {
      db.exec(readFileSync(new URL("../migrations/" + migration, import.meta.url), "utf8"));
    }
    db.prepare(`INSERT INTO reports(submission_id,content_hash,receipt,title,description,summary,ip_key,created_at,
      crash_log,crash_log_consent,status,issue_url) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`)
      .run("fictional-legacy", "hash", "receipt", "Old title", "Old description", "OS: Windows 11", "hmac", 1234,
        "FICTIONAL_PRIVATE_LEGACY_LOG", 1, "SENT", "https://example.org/issue/1");
    db.prepare("INSERT INTO native_sessions VALUES(?,?,?,?,?,?,?)")
      .run("fictional-session", "secret-hash", "content-hash", 1234, 1834, 1, null);
    const report = db.prepare("SELECT * FROM reports").get();
    const session = db.prepare("SELECT * FROM native_sessions").get();
    db.exec(readFileSync(new URL("../migrations/0004_private_screenshots.sql", import.meta.url), "utf8"));
    const migrated = db.prepare("SELECT * FROM reports").get();
    for (const [key, value] of Object.entries(report)) assert.equal(migrated[key], value);
    assert.equal(migrated.protocol_version, 1);
    assert.equal(migrated.category_id, null);
    assert.equal(migrated.screenshot_consent, 0);
    assert.deepEqual(db.prepare("SELECT * FROM native_sessions").get(), session);
    assert.equal(db.prepare("SELECT bytes_used FROM screenshot_budget").get().bytes_used, 0);
    assert.equal(db.prepare("SELECT count(*) AS n FROM report_screenshots").get().n, 0);
  } finally { db.close(); }
});
async function input() {
  const { turnstileToken, ...fields } = valid();
  return { ...fields, protocolVersion: 2, categoryId: "reader", title: "【问题反馈】阅读器、图片或翻页",
    crashLog: "FICTIONAL_PRIVATE_READER_TRACE", crashLogConsent: true, screenshotConsent: true,
    screenshots: [{ id: "123e4567-e89b-42d3-a456-426614174111", mime: "image/jpeg", width: 16, height: 12,
      byteLength: jpeg.length, sha256: await digest(jpeg), dataBase64: jpeg.toString("base64") }] };
}
async function call(f, route, body, headers = {}) {
  return worker.fetch(new Request(origin + route, { method: body === undefined ? "GET" : "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.45", ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) }), f.env);
}
async function authenticated(f, payload) {
  const response = await call(f, "/api/native-session", { contentHash: await nativeContentsHash(payload) });
  assert.equal(response.status, 201);
  const session = await response.json();
  assert.equal((await call(f, "/api/native-verify", { sessionId: session.sessionId, turnstileToken: "fictional" }, { Origin: origin })).status, 200);
  return { ...payload, nativeSessionId: session.sessionId, nativeSessionSecret: session.secret };
}

test("v2 Unicode whitespace and real JPEG manifest agree with the cross-platform fixed digest", async () => {
  const value = { ...await input(), description: " \uFEFF报告😀问题\r\n ", summary: "\r\n", crashLog: " \r\n" };
  assert.equal(await nativeContentsHash(value), "c8a4297b233a97ccfddac19e843ac68b217877584b524015eb0e150a35c3a07d");
});

test("v2 config advertises exact limits and persists real JPEG privately with category-derived title", async () => {
  const f = fixture();
  try {
    const config = await (await call(f, "/api/config")).json();
    assert.deepEqual(config.capabilities, { nativeV2: true, screenshots: true });
    assert.equal(config.limits.maxScreenshotSide, 1920);
    assert.equal(config.limits.maxBodyBytes, 2097152);
    const payload = await authenticated(f, await input());
    const response = await call(f, "/api/feedback", payload);
    assert.equal(response.status, 202);
    const receipt = await response.json();
    assert.deepEqual(Object.keys(receipt).sort(), ["receipt", "status"]);
    const row = f.sqlite.prepare("SELECT * FROM report_screenshots").get();
    assert.deepEqual(Buffer.from(row.data), jpeg);
    assert.equal(row.byte_length, jpeg.length);
    assert.equal(row.sha256, payload.screenshots[0].sha256);
    assert.equal(f.sqlite.prepare("SELECT title, category_id, screenshot_consent FROM reports").get().title, payload.title);
    assert.equal(f.sqlite.prepare("SELECT bytes_used FROM screenshot_budget WHERE singleton=1").get().bytes_used, jpeg.length);
    assert.equal(f.sqlite.prepare("SELECT used_submission_id FROM native_sessions").get().used_submission_id, payload.submissionId);
  } finally { f.close(); }
});

test("v2 binds category and ordered JPEG manifests, retries idempotently and rejects changed attachments", async () => {
  const f = fixture();
  try {
    const payload = await authenticated(f, await input());
    assert.equal((await call(f, "/api/feedback", payload)).status, 202);
    assert.equal((await call(f, "/api/feedback", payload)).status, 202);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM report_screenshots").get().n, 1);
    assert.equal(f.sqlite.prepare("SELECT bytes_used FROM screenshot_budget").get().bytes_used, jpeg.length);
    const changed = { ...payload, screenshots: [{ ...payload.screenshots[0], id: crypto.randomUUID() }] };
    assert.equal((await call(f, "/api/feedback", changed)).status, 409);
    const other = { ...payload, categoryId: "interface", title: "【问题反馈】界面显示或操作异常" };
    assert.equal((await call(f, "/api/feedback", other)).status, 409);
  } finally { f.close(); }
});

test("a new verified session consumed by an existing report retry cannot then submit another UUID in v1 or v2", async () => {
  for (const version of [1, 2]) {
    const f = fixture();
    try {
      const { turnstileToken, ...legacy } = valid();
      const body = version === 2 ? await input() : legacy;
      assert.equal((await call(f, "/api/feedback", await authenticated(f, body))).status, 202);
      const retry = await authenticated(f, body);
      assert.equal((await call(f, "/api/feedback", retry)).status, 202);
      assert.equal((await call(f, "/api/feedback", { ...retry, submissionId: crypto.randomUUID() })).status, 409);
      assert.equal(f.sqlite.prepare("SELECT used_submission_id FROM native_sessions WHERE session_id=?").get(retry.nativeSessionId).used_submission_id, body.submissionId);
      assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 1);
    } finally { f.close(); }
  }
});

test("v2 rejects forged media metadata unsafe JPEG and consent or category omissions before persistence", async () => {
  const f = fixture();
  try {
    const body = await authenticated(f, await input());
    const mutations = [
      { ...body, screenshotConsent: false }, { ...body, categoryId: "unknown" }, { ...body, title: "untrusted custom title" },
      { ...body, screenshots: [body.screenshots[0], body.screenshots[0]] },
      ...[ { mime: "image/png" }, { width: 1921 }, { height: 13 }, { byteLength: jpeg.length + 1 },
        { sha256: "0".repeat(64) }, { dataBase64: "data:image/jpeg;base64," + jpeg.toString("base64") },
        { dataBase64: Buffer.concat([jpeg, Buffer.from("PRIVATE_TRAILING_DATA")]).toString("base64") },
        { filename: "private-source-name.jpg" }, { dataBase64: jpeg.subarray(0, jpeg.length - 2).toString("base64") }]
        .map((change) => ({ ...body, screenshots: [{ ...body.screenshots[0], ...change }] })),
    ];
    for (const mutation of mutations) assert.equal((await call(f, "/api/feedback", mutation)).status, 400);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
    assert.equal(f.sqlite.prepare("SELECT used_submission_id FROM native_sessions").get().used_submission_id, null);
  } finally { f.close(); }
});

test("atomic screenshot pool failure preserves sessions, no-image v2 still works, and cleanup frees budget", async () => {
  const f = fixture();
  try {
    const payload = await authenticated(f, await input());
    f.sqlite.prepare("UPDATE screenshot_budget SET bytes_used=33554432").run();
    const rejected = await call(f, "/api/feedback", payload);
    assert.equal(rejected.status, 507);
    assert.equal((await rejected.json()).error, "attachment_capacity");
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
    assert.equal(f.sqlite.prepare("SELECT used_submission_id FROM native_sessions").get().used_submission_id, null);
    assert.equal((await (await call(f, "/api/config")).json()).capabilities.screenshots, false);
    const text = { ...await input(), submissionId: crypto.randomUUID(), screenshots: [], screenshotConsent: false };
    assert.equal((await call(f, "/api/feedback", await authenticated(f, text))).status, 202);
    f.sqlite.prepare("UPDATE screenshot_budget SET bytes_used=0").run();
    assert.equal((await call(f, "/api/feedback", payload)).status, 202);
    f.sqlite.prepare("UPDATE reports SET created_at=1 WHERE submission_id=?").run(payload.submissionId);
    await worker.scheduled({}, { ...f.env, GITHUB_TOKEN: "" });
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM report_screenshots").get().n, 0);
    assert.equal(f.sqlite.prepare("SELECT bytes_used FROM screenshot_budget").get().bytes_used, 0);
  } finally { f.close(); }
});

test("GitHub dispatch uses only a private attachment existence flag and never reads JPEG bytes", async () => {
  const f = fixture();
  try {
    const payload = await authenticated(f, await input());
    assert.equal((await call(f, "/api/feedback", payload)).status, 202);
    f.reads.length = 0;
    await worker.scheduled({}, f.env);
    const github = f.calls.find((c) => c.url.includes("api.github.com"));
    assert.ok(github);
    assert.match(github.options.body, /maintainer-only screenshot/);
    assert.match(github.options.body, /maintainer-only diagnostic log/);
    for (const secret of [jpeg.toString("base64"), payload.screenshots[0].sha256, payload.screenshots[0].id,
      payload.nativeSessionSecret, payload.crashLog]) assert.ok(!github.options.body.includes(secret));
    assert.ok(f.reads.every((columns) => !columns.includes("data") && !columns.includes("sha256")));
  } finally { f.close(); }
});

test("screenshot storage invariant failure rolls back report bytes quota and session consumption", async () => {
  const f = fixture();
  try {
    const payload = await authenticated(f, await input());
    f.sqlite.exec(`CREATE TRIGGER test_budget_changes BEFORE INSERT ON report_screenshots BEGIN
      UPDATE screenshot_budget SET bytes_used=33554432 WHERE singleton=1; END;`);
    assert.equal((await call(f, "/api/feedback", payload)).status, 503);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM report_screenshots").get().n, 0);
    assert.equal(f.sqlite.prepare("SELECT bytes_used FROM screenshot_budget").get().bytes_used, 0);
    assert.equal(f.sqlite.prepare("SELECT used_submission_id FROM native_sessions").get().used_submission_id, null);
  } finally { f.close(); }
});

test("v2 accepts a bounded large request while legacy and oversized streams retain their limits", async () => {
  const f = fixture();
  try {
    const payload = await authenticated(f, await input());
    const large = JSON.stringify(payload) + " ".repeat(660000);
    assert.equal((await f.submit(large)).status, 202);
    const legacy = JSON.stringify(valid()) + " ".repeat(660000);
    assert.equal((await f.submit(legacy)).status, 413);
    assert.equal((await f.submit(JSON.stringify(payload) + " ".repeat(2097152))).status, 413);
  } finally { f.close(); }
});
