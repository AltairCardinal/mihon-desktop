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
    crashLog: document.getElementById("crash-log"),
    crashLogConsent: document.getElementById("crash-log-consent"),
  };
  fields.title.value = "Reader feedback example";
  fields.description.value = "This is a fictional reproduction of a reader problem.";
  fields.summary.value = "Application: Mihon\nOS: Windows 11";
  return {
    dom, window, document, fields, requests,
    get widget() { return widget; },
    get resets() { return resets; },
    get clipboardReads() { return clipboardReads; },
    async chooseFile(contents, name = "fictional-crash.log") {
      const input = document.getElementById("crash-log-file");
      const file = new window.File([contents], name, { type: "text/plain" });
      Object.defineProperty(input, "files", { configurable: true, value: [file] });
      input.dispatchEvent(new window.Event("change", { bubbles: true }));
      await tick();
      for (let attempt = 0; document.getElementById("crash-log-status").dataset.state === "reading" && attempt < 200; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 5));
      }
      assert.notEqual(document.getElementById("crash-log-status").dataset.state, "reading", "file read must finish within one second");
    },
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

test("real File change strictly decodes BOM UTF-8 into editable preview without uploading until separate consent", async () => {
  const f = await fixture();
  try {
    const text = "  Fictional crash\r\n at example.Frame\n";
    await f.chooseFile(new Uint8Array([0xef, 0xbb, 0xbf, ...new TextEncoder().encode(text)]), "fictional-desktop.log.3");
    assert.equal(f.fields.crashLog.value, text.replace(/\r\n/g, "\n"));
    assert.equal(f.fields.crashLogConsent.checked, false);
    assert.equal(f.requests.length, 0);
    assert.match(f.document.getElementById("crash-log-bytes").textContent, /262144/);
    f.fields.consent.checked = true;
    f.widget.callback("captcha-token");
    await f.submit();
    assert.equal(f.requests.length, 0);
    assert.match(f.document.getElementById("status").textContent, /日志|log/);
    f.fields.crashLogConsent.checked = true;
    await f.submit();
    const body = JSON.parse(f.requests[0].options.body);
    assert.equal(body.crashLog, text.replace(/\r\n/g, "\n"));
    assert.equal(body.crashLogConsent, true);
    assert.doesNotMatch(f.requests[0].options.body, /fictional-desktop\.log\.3/);
    assert.match(f.document.getElementById("crash-log-status").textContent, /已接收.*仅.*维护者/);
    assert.doesNotMatch(f.document.getElementById("crash-log-status").textContent, /尚未上传/);
    f.document.getElementById("language").click();
    assert.match(f.document.getElementById("crash-log-status").textContent, /Received.*project maintainers only/i);
    assert.doesNotMatch(f.document.getElementById("crash-log-status").textContent, /not uploaded/i);
    assert.equal(f.clipboardReads, 0);
    assert.equal(f.window.localStorage.length, 0);
  } finally { f.dom.window.close(); }
});

test("editing, replacing and removing a log reset private consent; removal submits no private fields", async () => {
  const f = await fixture();
  try {
    await f.chooseFile("Fictional first crash\n");
    f.fields.crashLogConsent.checked = true;
    f.fields.crashLog.value = "Edited fictional crash\n";
    f.fields.crashLog.dispatchEvent(new f.window.Event("input", { bubbles: true }));
    assert.equal(f.fields.crashLogConsent.checked, false);
    f.fields.crashLogConsent.checked = true;
    await f.chooseFile("Fictional replacement crash\n", "fictional.log.1");
    assert.equal(f.fields.crashLogConsent.checked, false);
    f.fields.crashLogConsent.checked = true;
    f.document.getElementById("crash-log-remove").click();
    assert.equal(f.fields.crashLog.value, "");
    assert.equal(f.fields.crashLogConsent.checked, false);
    assert.equal(f.fields.crashLogConsent.disabled, true);
    f.fields.consent.checked = true;
    f.widget.callback("captcha-token");
    await f.submit();
    const body = JSON.parse(f.requests[0].options.body);
    assert.equal(body.crashLog, undefined);
    assert.equal(body.crashLogConsent, undefined);
  } finally { f.dom.window.close(); }
});

test("unsupported, invalid UTF-8 and binary files are rejected; oversized preview is rejected before reading", async () => {
  const f = await fixture();
  try {
    let reads = 0;
    const original = f.window.FileReader.prototype.readAsArrayBuffer;
    f.window.FileReader.prototype.readAsArrayBuffer = function (...args) { reads++; return original.apply(this, args); };
    await f.chooseFile("fictional ZIP", "fictional.zip");
    assert.equal(reads, 0);
    assert.equal(f.fields.crashLog.value, "");
    await f.chooseFile(new Uint8Array(4 * 1024 * 1024 + 1));
    assert.equal(reads, 0);
    assert.match(f.document.getElementById("crash-log-status").textContent, /4 MiB/);
    await f.chooseFile(new Uint8Array([0xc3, 0x28]));
    assert.equal(f.fields.crashLog.value, "");
    assert.match(f.document.getElementById("crash-log-status").textContent, /UTF-8/);
    await f.chooseFile("Fictional\u0000binary");
    assert.equal(f.fields.crashLog.value, "");
    assert.equal(f.requests.length, 0);
  } finally { f.dom.window.close(); }
});

test("preview above submission limit is not truncated and can be edited smaller before authorizing", async () => {
  const f = await fixture();
  try {
    const original = "x".repeat(256 * 1024 + 1);
    await f.chooseFile(original, "fictional.txt");
    assert.equal(f.fields.crashLog.value, original);
    f.fields.consent.checked = true;
    f.fields.crashLogConsent.checked = true;
    f.widget.callback("captcha-token");
    await f.submit();
    assert.equal(f.requests.length, 0);
    assert.match(f.document.getElementById("status").textContent, /256 KiB/);
    f.fields.crashLog.value = "Fictional shortened log\n";
    f.fields.crashLog.dispatchEvent(new f.window.Event("input", { bubbles: true }));
    assert.equal(f.fields.crashLogConsent.checked, false);
    f.fields.crashLogConsent.checked = true;
    await f.submit();
    assert.equal(JSON.parse(f.requests[0].options.body).crashLog, "Fictional shortened log\n");
  } finally { f.dom.window.close(); }
});

test("private log draft survives network failure and language switch with unchanged retry identity", async () => {
  const f = await fixture(async () => { throw new Error("offline"); });
  try {
    await f.chooseFile("Fictional retry log\n");
    f.fields.consent.checked = true;
    f.fields.crashLogConsent.checked = true;
    f.widget.callback("captcha-token");
    await f.submit();
    assert.equal(f.fields.crashLog.value, "Fictional retry log\n");
    assert.equal(f.fields.crashLogConsent.checked, true);
    assert.equal(f.fields.crashLog.disabled, false);
    assert.equal(f.document.activeElement.id, "status");
    const first = JSON.parse(f.requests[0].options.body);
    f.document.getElementById("language").click();
    assert.equal(f.document.getElementById("crash-log-heading").textContent, "Private crash log (optional)");
    assert.equal(f.fields.crashLog.value, first.crashLog);
    f.widget.callback("new-captcha-token");
    await f.submit();
    const second = JSON.parse(f.requests[1].options.body);
    assert.equal(second.submissionId, first.submissionId);
    assert.equal(second.crashLog, first.crashLog);
  } finally { f.dom.window.close(); }
});

test("in-flight and received snapshots disable file, preview, consent and report edits", async () => {
  let complete;
  const f = await fixture(() => new Promise((resolve) => { complete = resolve; }));
  try {
    await f.chooseFile("Fictional frozen log\n");
    f.fields.consent.checked = true;
    f.fields.crashLogConsent.checked = true;
    f.widget.callback("captcha-token");
    await f.submit();
    for (const id of ["crash-log-file", "crash-log", "crash-log-consent", "crash-log-remove", "title", "description", "summary", "consent"]) {
      assert.equal(f.document.getElementById(id).disabled, true, id);
    }
    const snapshot = JSON.parse(f.requests[0].options.body);
    complete(Response.json({ status: "received_pending", receipt: "receipt" }, { status: 202 }));
    await tick();
    assert.equal(f.fields.crashLog.disabled, true);
    assert.equal(f.document.getElementById("crash-log-file").disabled, true);
    assert.equal(snapshot.crashLog, "Fictional frozen log\n");
    await f.submit();
    assert.equal(f.requests.length, 1);
  } finally { f.dom.window.close(); }
});
