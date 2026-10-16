import assert from "node:assert/strict";
import { test } from "node:test";
import worker from "../src/worker.mjs";
import * as workerModule from "../src/worker.mjs";
import { fixture, origin, valid } from "./worker-fixture.mjs";

async function digest(input) {
  const segments = [input.title.trim(), input.description.trim(), (input.summary ?? "").replace(/\r\n/g, "\n").trim(), (input.crashLog ?? "").replace(/\r\n/g, "\n")];
  return Buffer.from(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(segments.map((s) => `${Buffer.byteLength(s)}:${s}`).join("")))).toString("hex");
}
async function call(f, route, body, headers = {}) {
  return worker.fetch(new Request(origin + route, {
    method: body === undefined ? "GET" : "POST", headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.99", ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }), f.env);
}
async function create(f, input = valid()) {
  const response = await call(f, "/api/native-session", { contentHash: await digest(input) });
  assert.equal(response.status, 201);
  return response.json();
}
async function verify(f, session) {
  return call(f, "/api/native-verify", { sessionId: session.sessionId, turnstileToken: "fictional-challenge" }, { Origin: origin });
}
function native(input, session) {
  const { turnstileToken, ...payload } = input;
  return { ...payload, nativeSessionId: session.sessionId, nativeSessionSecret: session.secret };
}

test("native sessions persist only hashes, not drafts or bearer secrets", async () => {
  const f = fixture();
  try {
    const input = { ...valid(), crashLog: "PRIVATE_NATIVE_LOG", crashLogConsent: true };
    const session = await create(f, input);
    assert.match(session.secret, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(session.verificationUrl, `${origin}/verify.html#${session.sessionId}`);
    const row = f.sqlite.prepare("SELECT * FROM native_sessions").get();
    assert.equal(row.content_hash, await digest(input));
    assert.equal(row.expires_at - row.created_at, 600);
    for (const privateText of [input.title, input.description, input.crashLog, session.secret]) assert.ok(!JSON.stringify(row).includes(privateText));
    assert.equal((await call(f, `/api/native-session/${session.sessionId}`)).status, 403);
    assert.deepEqual(await (await call(f, `/api/native-session/${session.sessionId}`, undefined, { Authorization: `Bearer ${session.secret}` })).json(), { verified: false, expiresAt: session.expiresAt });
  } finally { f.close(); }
});

test("verified native submission persists private logs and retries idempotently", async () => {
  const f = fixture();
  try {
    const input = { ...valid(), crashLog: "PRIVATE_NATIVE_LOG\n", crashLogConsent: true };
    const session = await create(f, input);
    assert.equal((await call(f, "/api/feedback", native(input, session))).status, 400);
    assert.equal((await verify(f, session)).status, 200);
    const first = await call(f, "/api/feedback", native(input, session));
    assert.equal(first.status, 202);
    const receipt = await first.json();
    assert.deepEqual(await (await call(f, "/api/feedback", native(input, session))).json(), receipt);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 1);
    await worker.scheduled({}, f.env);
    assert.equal(f.sqlite.prepare("SELECT crash_log FROM reports").get().crash_log, input.crashLog);
    assert.ok(!JSON.stringify(f.calls).includes(input.crashLog));
  } finally { f.close(); }
});

test("browser verification enforces origin, hostname, action and expiry", async () => {
  const f = fixture();
  try {
    const session = await create(f);
    assert.equal((await call(f, "/api/native-verify", { sessionId: session.sessionId, turnstileToken: "x" }, { Origin: "https://evil.example" })).status, 403);
    for (const proof of [{ success: false }, { success: true, hostname: "evil.example", action: "feedback" }, { success: true, hostname: "feedback.example.org", action: "wrong" }]) {
      f.setVerify(proof); assert.equal((await verify(f, session)).status, 400);
    }
    f.sqlite.prepare("UPDATE native_sessions SET expires_at=0").run();
    assert.equal((await verify(f, session)).status, 400);
  } finally { f.close(); }
});

test("session binds immutable contents and atomically permits only one UUID", async () => {
  const f = fixture();
  try {
    const input = valid(); const session = await create(f, input); await verify(f, session);
    assert.equal((await call(f, "/api/feedback", native({ ...input, crashLog: "changed", crashLogConsent: true }, session))).status, 409);
    const results = await Promise.all(Array.from({ length: 2 }, () => call(f, "/api/feedback", native({ ...input, submissionId: crypto.randomUUID() }, session))));
    assert.deepEqual(results.map((r) => r.status).sort(), [202, 409]);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 1);
  } finally { f.close(); }
});

test("mixed web/native credentials, wrong secret and cross-origin native calls fail closed", async () => {
  const f = fixture();
  try {
    const input = valid(); const session = await create(f, input); await verify(f, session);
    assert.equal((await call(f, "/api/feedback", { ...native(input, session), turnstileToken: "x" })).status, 400);
    assert.equal((await call(f, "/api/feedback", native(input, { ...session, secret: "x".repeat(43) }))).status, 400);
    assert.equal((await call(f, "/api/feedback", native(input, session), { Origin: "https://evil.example" })).status, 403);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
  } finally { f.close(); }
});

test("session creation quota is atomic and expired sessions clear even without GitHub credentials", async () => {
  const f = fixture();
  try {
    const contentHash = await digest(valid());
    const results = await Promise.all(Array.from({ length: 20 }, () => call(f, "/api/native-session", { contentHash })));
    assert.equal(results.filter((r) => r.status === 201).length, 10);
    assert.equal(results.filter((r) => r.status === 429).length, 10);
    assert.equal((await call(f, "/api/native-session", { contentHash, crashLog: "forbidden" })).status, 400);
    f.sqlite.prepare("UPDATE native_sessions SET expires_at=0").run(); f.env.GITHUB_TOKEN = "";
    await worker.scheduled({}, f.env);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM native_sessions").get().n, 0);
  } finally { f.close(); }
});

test("production content digest agrees with the native Unicode and whitespace contract", async () => {
  assert.equal(typeof workerModule.nativeContentsHash, "function");
  assert.equal(await workerModule.nativeContentsHash({ title: " 问😀 ", description: " d ", summary: "\r\n", crashLog: " \r\n" }),
    "3e3b0f36e7413c053b28fd633c3d898796f82cb5be5ff1f0c842f1527264a811");
});

test("native endpoints enforce JSON primitive credential shapes and stream bounds", async () => {
  const f = fixture();
  try {
    const contentHash = await digest(valid());
    for (const value of [null, [], {}, { contentHash: [contentHash] }, { contentHash: "z".repeat(64) }])
      assert.equal((await call(f, "/api/native-session", value)).status, 400);
    assert.equal((await call(f, "/api/native-session", { contentHash, padding: "x".repeat(8192) })).status, 413);
    const session = await create(f); await verify(f, session);
    const input = valid();
    assert.equal((await call(f, "/api/feedback", native(input, { ...session, sessionId: [session.sessionId] }))).status, 400);
    assert.equal((await call(f, "/api/feedback", native(input, { ...session, secret: [session.secret] }))).status, 400);
    assert.equal((await call(f, "/api/native-verify", { sessionId: [session.sessionId], turnstileToken: "x" }, { Origin: origin })).status, 400);
  } finally { f.close(); }
});

test("native reports retain existing quotas; rejection does not consume the session", async () => {
  const f = fixture();
  try {
    for (let i = 0; i < 3; i++) assert.equal((await f.submit(valid(), "192.0.2.99")).status, 202);
    const input = valid(); const session = await create(f, input); await verify(f, session);
    assert.equal((await call(f, "/api/feedback", native(input, session))).status, 429);
    assert.equal(f.sqlite.prepare("SELECT used_submission_id FROM native_sessions").get().used_submission_id, null);
  } finally { f.close(); }
});

test("session creation hourly quota survives ten-minute metadata expiry", async () => {
  const f = fixture();
  try {
    for (let i = 0; i < 10; i++) await create(f);
    f.sqlite.prepare("UPDATE native_sessions SET expires_at=0").run();
    assert.equal((await call(f, "/api/native-session", { contentHash: await digest(valid()) })).status, 429);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM native_sessions").get().n, 0);
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM native_session_events").get().n, 10);
  } finally { f.close(); }
});

test("native insert and session consumption roll back together on D1 failure without disclosing the draft", async () => {
  const f = fixture();
  try {
    const input = { ...valid(), crashLog: "PRIVATE_ROLLBACK_LOG", crashLogConsent: true };
    const session = await create(f, input); await verify(f, session);
    const batch = f.env.DB.batch.bind(f.env.DB);
    f.env.DB.batch = (statements) => batch([...statements, f.env.DB.prepare("INSERT INTO deliberately_missing VALUES (1)")]);
    const response = await call(f, "/api/feedback", native(input, session));
    assert.equal(response.status, 503); assert.deepEqual(await response.json(), { error: "unavailable" });
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
    assert.equal(f.sqlite.prepare("SELECT used_submission_id FROM native_sessions").get().used_submission_id, null);
  } finally { f.close(); }
});

test("native public fields reject unpaired surrogates instead of hashing platform-specific replacements", async () => {
  const f = fixture();
  try {
    for (const field of ["title", "description"]) {
      const input = valid(); input[field] += "\uD800";
      const session = await create(f, input); await verify(f, session);
      assert.equal((await call(f, "/api/feedback", native(input, session))).status, 400);
    }
  } finally { f.close(); }
});

test("native creation enforces global hourly and daily limits across different IPs", async () => {
  const f = fixture();
  try {
    const contentHash = await digest(valid());
    const now = Math.floor(Date.now() / 1000);
    const insert = f.sqlite.prepare("INSERT INTO native_session_events VALUES(?,?,?)");
    for (let i = 0; i < 200; i++) insert.run(`hour-${i}`, `other-${i}`, now);
    assert.equal((await call(f, "/api/native-session", { contentHash })).status, 429);
    f.sqlite.prepare("DELETE FROM native_session_events").run();
    // The day guard is independent of the hour guard. Freeze time away from the
    // day's first hour so every synthetic row is inside today and outside this hour.
    const originalNow = Date.now;
    const midday = Math.floor(now / 86400) * 86400 + 12 * 3600;
    Date.now = () => midday * 1000;
    try {
      for (let i = 0; i < 1000; i++) insert.run(`day-${i}`, `other-${i}`, midday - 3600);
      assert.equal((await call(f, "/api/native-session", { contentHash })).status, 429);
    } finally { Date.now = originalNow; }
  } finally { f.close(); }
});
