import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mkdtemp, mkdir, readFile, rm, writeFile, symlink } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { exportScreenshot } from "../scripts/export-screenshot.mjs";
import { sha256Bytes } from "../src/screenshots.mjs";

const id = "123e4567-e89b-42d3-a456-426614174111";
const bytes = readFileSync(new URL("./fixtures/fictional-screenshot.jpg", import.meta.url));
async function setup() {
  const directory = await mkdtemp(path.join(os.tmpdir(), "mihon-private-screenshot-export-"));
  const repository = path.join(directory, "repository");
  await mkdir(repository);
  const row = { mime: "image/jpeg", width: 16, height: 12, byte_length: bytes.length,
    sha256: await sha256Bytes(bytes), data_hex: bytes.toString("hex").toUpperCase() };
  return { directory, repository, row,
    options: { submissionId: id, ordinal: 0, output: path.join(directory, "screenshot-1.jpg"), repositoryRoots: [repository] },
    close: () => rm(directory, { recursive: true, force: true }) };
}

test("authorized screenshot export writes exact verified JPEG bytes to a new private file", async () => {
  const f = await setup();
  try {
    const queries = [];
    const result = await exportScreenshot({ ...f.options, runner: async (query) => { queries.push(query); return [{ results: [f.row] }]; } });
    assert.deepEqual(await readFile(f.options.output), bytes);
    assert.deepEqual(result, { filename: "screenshot-1.jpg" });
    assert.equal(queries.length, 1);
    assert.match(queries[0], /screenshot_consent = 1/);
    assert.match(queries[0], /ordinal = 0/);
    assert.ok(!JSON.stringify(result).includes(f.row.data_hex));
  } finally { await f.close(); }
});

test("screenshot export rejects invalid identity ordinal destination and unauthorised or corrupt records", async () => {
  const f = await setup();
  try {
    let calls = 0;
    const runner = async () => { calls++; return [{ results: [f.row] }]; };
    for (const change of [{ submissionId: "' OR 1=1 --" }, { ordinal: -1 }, { ordinal: 2 }, { ordinal: "0" },
      { output: path.join(f.repository, "private.jpg") }, { output: path.join(f.directory, "private.png") }]) {
      await assert.rejects(exportScreenshot({ ...f.options, ...change, runner }));
    }
    assert.equal(calls, 0);
    for (const result of [[], [{ results: [] }], [{ results: [{ ...f.row, sha256: "0".repeat(64) }] }],
      [{ results: [{ ...f.row, data_hex: "bad" }] }], [{ results: [{ ...f.row, width: 17 }] }],
      [{ results: [{ ...f.row, byte_length: 524289 }] }]]) {
      await assert.rejects(exportScreenshot({ ...f.options, runner: async () => result }));
    }
    await assert.rejects(readFile(f.options.output));
  } finally { await f.close(); }
});

test("screenshot export does not overwrite existing files and rechecks linked parent after remote wait", async () => {
  const f = await setup();
  try {
    await writeFile(f.options.output, "existing", "utf8");
    let queried = false;
    await assert.rejects(exportScreenshot({ ...f.options, runner: async () => { queried = true; return [{ results: [f.row] }]; } }));
    assert.equal(queried, false);
    assert.equal(await readFile(f.options.output, "utf8"), "existing");
    const parent = path.join(f.directory, "output");
    await mkdir(parent);
    await assert.rejects(exportScreenshot({ ...f.options, output: path.join(parent, "new.jpg"), runner: async () => {
      await rm(parent, { recursive: true });
      await symlink(f.repository, parent, process.platform === "win32" ? "junction" : "dir");
      return [{ results: [f.row] }];
    } }));
    await assert.rejects(readFile(path.join(f.repository, "new.jpg")));
  } finally { await f.close(); }
});
