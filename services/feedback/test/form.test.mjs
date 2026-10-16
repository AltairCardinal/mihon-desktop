import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import { initializeFeedback } from "../public/app.mjs";

const html = readFileSync(new URL("../public/index.html", import.meta.url), "utf8");
const redactedSummary = readFileSync(new URL("./fixtures/redacted-summary.txt", import.meta.url), "utf8").trim();
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

async function fixture(handler = async () => Response.json({ status: "received_pending", receipt: "random-receipt" }, { status: 202 }), config = { accepting: true, siteKey: "public-key" }) {
  const dom = new JSDOM(html, { url: "https://feedback.example.org", pretendToBeVisual: true });
  const { window } = dom;
  const { document } = window;
  const requests = [];
  let widget;
  let resets = 0;
  let clipboardReads = 0;
  Object.defineProperty(window.navigator, "clipboard", { value: { readText() { clipboardReads++; throw new Error("clipboard forbidden"); } } });
  window.turnstile = {
    render(_container, options) { widget = options; return "widget-id"; },
    reset() { resets++; },
  };
  window.fetch = async (url, options) => {
    if (url === "/api/config") return Response.json(config);
    requests.push({ url, options });
    return handler(url, options);
  };
  await initializeFeedback(window, document);
  const fields = {
    title: document.getElementById("title"),
    description: document.getElementById("description"),
    summary: document.getElementById("summary"),
    consent: document.getElementById("consent"),
  };
  fields.title.value = "Reader feedback example";
  fields.description.value = "This is a fictional reproduction of a reader problem.";
  fields.summary.value = "Application: Mihon\nOS: Windows 11";
  return {
    dom, window, document, fields, requests,
    get widget() { return widget; },
    get resets() { return resets; },
    get clipboardReads() { return clipboardReads; },
    async submit() {
      document.getElementById("feedback").dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true }));
      await tick();
    },
  };
}

test("unchecked public consent prevents production submission", async () => {
  const f = await fixture();
  try {
    assert.equal(f.fields.consent.checked, false);
    f.widget.callback("captcha-token");
    await f.submit();
    assert.equal(f.requests.length, 0);
    assert.match(f.document.getElementById("status").textContent, /同意|consent/);
  } finally { f.dom.window.close(); }
});

test("real submit event sends same-origin JSON and displays pending receipt without clipboard or storage", async () => {
  const f = await fixture();
  try {
    f.fields.consent.checked = true;
    f.widget.callback("captcha-token");
    await f.submit();
    assert.equal(f.requests.length, 1);
    assert.equal(f.requests[0].url, "/api/feedback");
    assert.equal(f.requests[0].options.method, "POST");
    const body = JSON.parse(f.requests[0].options.body);
    assert.equal(body.summary, f.fields.summary.value);
    assert.equal(body.turnstileToken, "captcha-token");
    assert.equal(body.consent, true);
    assert.match(body.submissionId, /^[0-9a-f-]{36}$/);
    assert.match(f.document.getElementById("status").textContent, /random-receipt/);
    assert.match(f.document.getElementById("status").textContent, /待处理|pending/);
    assert.equal(f.clipboardReads, 0);
    assert.equal(f.window.localStorage.length, 0);
    assert.equal(f.window.location.search, "");
  } finally { f.dom.window.close(); }
});

test("native redacted summary follows the real submit path without weakening bracket validation", async () => {
  const f = await fixture();
  try {
    f.fields.consent.checked = true;
    f.widget.callback("captcha-token");
    f.fields.summary.value = "Version: [secret]";
    await f.submit();
    assert.equal(f.requests.length, 0);
    f.fields.summary.value = redactedSummary;
    await f.submit();
    assert.equal(f.requests.length, 1);
    assert.equal(JSON.parse(f.requests[0].options.body).summary, redactedSummary);
    assert.match(f.document.getElementById("status").textContent, /random-receipt/);
  } finally { f.dom.window.close(); }
});

test("network, rate limit, unavailable and expired verification keep draft and permit challenge retry", async () => {
  for (const kind of ["network", "429", "503", "400"]) {
    const f = await fixture(async () => {
      if (kind === "network") throw new Error("offline");
      return Response.json({ error: kind === "400" ? "verification_failed" : "unavailable" }, { status: Number(kind) });
    });
    try {
      f.fields.consent.checked = true;
      f.widget.callback("captcha-token");
      const before = f.fields.description.value;
      await f.submit();
      assert.equal(f.fields.description.value, before);
      assert.equal(f.fields.consent.checked, true);
      assert.match(f.document.getElementById("status").textContent, /保留|preserved/);
      assert.equal(f.resets, 1);
      const firstId = JSON.parse(f.requests[0].options.body).submissionId;
      f.widget.callback("new-captcha-token");
      await f.submit();
      assert.equal(JSON.parse(f.requests[1].options.body).submissionId, firstId);
      assert.equal(f.document.getElementById("submit").disabled, false);
    } finally { f.dom.window.close(); }
  }
});

test("in-flight submission and received report cannot be submitted twice", async () => {
  let complete;
  const f = await fixture(() => new Promise((resolve) => { complete = resolve; }));
  try {
    f.fields.consent.checked = true;
    f.widget.callback("captcha-token");
    await f.submit();
    await f.submit();
    assert.equal(f.requests.length, 1);
    assert.equal(f.document.getElementById("submit").disabled, true);
    complete(Response.json({ status: "received_pending", receipt: "receipt" }, { status: 202 }));
    await tick();
    await f.submit();
    assert.equal(f.requests.length, 1);
  } finally { f.dom.window.close(); }
});

test("unconfigured service cannot submit and clearly explains unavailability", async () => {
  const f = await fixture(undefined, { accepting: false });
  try {
    assert.equal(f.widget, undefined);
    assert.equal(f.document.getElementById("submit").disabled, true);
    assert.match(f.document.getElementById("status").textContent, /暂不可用|unavailable/);
    f.fields.consent.checked = true;
    await f.submit();
    assert.equal(f.requests.length, 0);
  } finally { f.dom.window.close(); }
});

test("language switch preserves draft and consent, captcha expiry blocks submission", async () => {
  const f = await fixture();
  try {
    f.fields.consent.checked = true;
    f.widget.callback("captcha-token");
    f.widget["expired-callback"]();
    await f.submit();
    assert.equal(f.requests.length, 0);
    const before = f.fields.description.value;
    f.document.getElementById("language").click();
    assert.equal(f.document.documentElement.lang, "en");
    assert.equal(f.fields.description.value, before);
    assert.equal(f.fields.consent.checked, true);
    assert.equal(f.document.querySelector("h1").textContent, "Report a problem");
  } finally { f.dom.window.close(); }
});
