import assert from "node:assert/strict";
import test from "node:test";

import {
    acquireClearance,
    clearancePermissionFor,
} from "../cookie-helper.mjs";

test("builds one scheme-and-host permission for the active page", () => {
    assert.deepEqual(
        clearancePermissionFor("https://reader.example.com/chapter?id=1"),
        {
            host: "reader.example.com",
            origin: "https://reader.example.com/*",
            url: "https://reader.example.com/chapter?id=1",
        },
    );
    assert.equal(clearancePermissionFor("about:debugging"), null);
});

test("copies only the current tab clearance value from its cookie store", async () => {
    const calls = [];
    let copied = null;
    const api = {
        permissions: {
            request: async (request) => {
                calls.push(["permission", request]);
                return true;
            },
            remove: async (request) => {
                calls.push(["permission-removed", request]);
                return true;
            },
        },
        cookies: {
            getAllCookieStores: async () => [{ id: "container-2", tabIds: [17] }],
            get: async (details) => {
                calls.push(["cookie", details]);
                return { name: "cf_clearance", value: "secret-clearance" };
            },
        },
    };

    const result = await acquireClearance({
        api,
        clipboard: { writeText: async (value) => { copied = value; } },
        tab: { id: 17, url: "https://reader.example.com/chapter" },
    });

    assert.deepEqual(result, { status: "copied", host: "reader.example.com" });
    assert.equal(copied, "secret-clearance");
    assert.deepEqual(calls, [
        ["permission", { origins: ["https://reader.example.com/*"] }],
        ["cookie", { url: "https://reader.example.com/chapter", name: "cf_clearance", storeId: "container-2" }],
        ["permission-removed", { origins: ["https://reader.example.com/*"] }],
    ]);
    assert.equal(JSON.stringify(result).includes("secret-clearance"), false);
});

test("does not inspect cookies after current-site permission is denied", async () => {
    let cookieCalls = 0;
    const result = await acquireClearance({
        api: {
            permissions: { request: async () => false },
            cookies: {
                getAllCookieStores: async () => { cookieCalls += 1; },
                get: async () => { cookieCalls += 1; },
            },
        },
        clipboard: { writeText: async () => assert.fail("clipboard must stay untouched") },
        tab: { id: 3, url: "https://denied.example/" },
    });

    assert.deepEqual(result, { status: "permission-denied", host: "denied.example" });
    assert.equal(cookieCalls, 0);
});

test("reports a missing clearance cookie without writing the clipboard", async () => {
    let removed = null;
    const result = await acquireClearance({
        api: {
            permissions: {
                request: async () => true,
                remove: async (request) => { removed = request; },
            },
            cookies: {
                getAllCookieStores: async () => [],
                get: async () => null,
            },
        },
        clipboard: { writeText: async () => assert.fail("clipboard must stay untouched") },
        tab: { id: 9, url: "https://plain.example/" },
    });

    assert.deepEqual(result, { status: "cookie-missing", host: "plain.example" });
    assert.deepEqual(removed, { origins: ["https://plain.example/*"] });
});
