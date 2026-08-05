import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("manifest uses current-site optional access and wires the production popup", async () => {
    const manifestUrl = new URL("../manifest.json", import.meta.url);
    const manifest = JSON.parse(await readFile(manifestUrl, "utf8"));

    assert.equal(manifest.manifest_version, 3);
    assert.deepEqual(manifest.permissions.sort(), ["activeTab", "clipboardWrite", "cookies"]);
    assert.deepEqual(manifest.optional_host_permissions.sort(), ["http://*/*", "https://*/*"]);
    assert.equal("host_permissions" in manifest, false);
    assert.equal(manifest.action.default_popup, "popup.html");
    assert.equal(manifest.browser_specific_settings.gecko.strict_min_version, "140.0");
    assert.deepEqual(
        manifest.browser_specific_settings.gecko.data_collection_permissions.required,
        ["none"],
    );
    assert.equal(manifest.browser_specific_settings.gecko_android.strict_min_version, "142.0");

    const popup = await readFile(new URL(`../${manifest.action.default_popup}`, import.meta.url), "utf8");
    assert.match(popup, /popup\.mjs/);
});
