import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import { initializeNativeVerification } from "../public/verify.mjs";

const html = readFileSync(new URL("../public/verify.html", import.meta.url), "utf8");
const id = "a1a2a3a4-b1b2-41b4-81b2-c1c2c3c4c5c6";
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
async function fixture(handler = async () => Response.json({ verified: true }), fragment = id) {
  const dom = new JSDOM(html, { url: `https://feedback.example.org/verify.html#${fragment}` });
  const { window } = dom;
  let widget; let resets = 0;
  const calls = [];
  window.turnstile = { render(_element, options) { widget = options; return 1; }, reset() { resets++; } };
  window.fetch = async (url, options) => {
    calls.push({ url, options });
    return url === "/api/config" ? Response.json({ accepting: true, siteKey: "production-site-key" }) : handler();
  };
  await initializeNativeVerification(window, window.document);
  return { dom, window, calls, get widget() { return widget; }, get resets() { return resets; },
    button: window.document.getElementById("verify"), status: window.document.getElementById("status"),
    async click() { this.button.click(); await tick(); },
  };
}

test("verification button waits for a challenge and sends only public session ID and proof", async () => {
  const f = await fixture();
  try {
    assert.equal(f.widget.action, "feedback"); assert.equal(f.button.disabled, true);
    f.widget.callback("fictional-token"); await f.click();
    assert.equal(f.calls.length, 2);
    assert.deepEqual(JSON.parse(f.calls[1].options.body), { sessionId: id, turnstileToken: "fictional-token" });
    assert.equal(f.calls[1].url, "/api/native-verify");
    assert.match(f.status.textContent, /返回应用/); assert.equal(f.button.disabled, true);
    f.window.document.getElementById("language").click();
    assert.match(f.status.textContent, /return to the app/i);
  } finally { f.dom.window.close(); }
});

test("invalid fragments never request config or challenge verification", async () => {
  for (const fragment of ["", "not-a-uuid", id + "?log=private", id + "/extra"]) {
    const f = await fixture(undefined, fragment);
    try { assert.equal(f.calls.length, 0); assert.match(f.status.textContent, /无效|invalid/i); }
    finally { f.dom.window.close(); }
  }
});

test("failed verification resets proof and allows a fresh challenge without sending app contents", async () => {
  const f = await fixture(async () => Response.json({ error: "verification_failed" }, { status: 400 }));
  try {
    f.widget.callback("expired-token"); await f.click();
    assert.equal(f.resets, 1); assert.equal(f.button.disabled, true);
    assert.match(f.status.textContent, /失败|过期/);
    f.widget.callback("fresh-token"); assert.equal(f.button.disabled, false);
    f.widget["expired-callback"](); assert.equal(f.button.disabled, true);
  } finally { f.dom.window.close(); }
});

test("pending verification blocks duplicate clicks", async () => {
  let finish;
  const f = await fixture(() => new Promise((resolve) => { finish = resolve; }));
  try {
    f.widget.callback("fictional-token"); await f.click(); await f.click();
    assert.equal(f.calls.length, 2);
    finish(Response.json({ verified: true })); await tick();
    assert.match(f.status.textContent, /返回应用/);
  } finally { f.dom.window.close(); }
});
