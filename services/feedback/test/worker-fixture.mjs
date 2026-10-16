import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker from "../src/worker.mjs";

const migration = readFileSync(new URL("../migrations/0001_feedback.sql", import.meta.url), "utf8");
const privateLogMigration = readFileSync(new URL("../migrations/0002_private_crash_log.sql", import.meta.url), "utf8");
export const origin = "https://feedback.example.org";
export const valid = () => ({
  submissionId: crypto.randomUUID(),
  title: "Reader problem report",
  description: "The application cannot display my fictional test page.",
  summary: "Application: Mihon\nVersion: 0.19.4\nOS: Android 16\nArchitecture: x86_64\nRuntime: Android SDK 36",
  consent: true,
  turnstileToken: "challenge-token",
});

export function fixture(overrides = {}) {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(migration);
  sqlite.exec(privateLogMigration);
  sqlite.exec(readFileSync(new URL("../migrations/0003_native_verification.sql", import.meta.url), "utf8"));
  const calls = [];
  const reads = [];
  const env = {
    DB: {
      async batch(statements) {
        sqlite.exec("BEGIN");
        try {
          const results = [];
          for (const statement of statements) results.push(statement.execute());
          sqlite.exec("COMMIT"); return results;
        } catch (error) { sqlite.exec("ROLLBACK"); throw error; }
      },
      prepare(sql) {
        let args = [];
        return {
          bind(...values) { args = values; return this; },
          async first() { const result = sqlite.prepare(sql).get(...args) ?? null; if (result) reads.push(Object.keys(result)); return result; },
          async all() { const results = sqlite.prepare(sql).all(...args); reads.push(...results.map((row) => Object.keys(row))); return { results }; },
          execute() { const result = sqlite.prepare(sql).run(...args); return { meta: { changes: Number(result.changes) } }; },
          async run() { return this.execute(); },
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
    env, sqlite, calls, reads,
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
