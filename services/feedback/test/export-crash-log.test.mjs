import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { exportCrashLog } from "../scripts/export-crash-log.mjs";

const id = "a1a2a3a4-b1b2-41b4-81b2-c1c2c3c4c5c6";

async function fixture() {
  const directory = await mkdtemp(path.join(os.tmpdir(), "mihon-private-log-export-"));
  const repository = path.join(directory, "repository");
  await mkdir(repository);
  return {
    directory, repository,
    options: { submissionId: id, output: path.join(directory, "fictional-crash.log"), repositoryRoots: [repository] },
    async close() { await rm(directory, { recursive: true, force: true }); },
  };
}

test("authorized exporter writes only the requested private log to a new real outside file", async () => {
  const f = await fixture();
  try {
    const queries = [];
    const result = await exportCrashLog({ ...f.options, runner: async (query) => {
      queries.push(query);
      return [{ results: [{ crash_log: "  Fictional private crash\n\tFrame\n " }] }];
    } });
    assert.equal(await readFile(f.options.output, "utf8"), "  Fictional private crash\n\tFrame\n ");
    assert.deepEqual(result, { filename: "fictional-crash.log" });
    assert.equal(queries.length, 1);
    assert.equal(queries[0], `SELECT crash_log FROM reports WHERE submission_id = '${id}' AND crash_log_consent = 1 LIMIT 1`);
  } finally { await f.close(); }
});

test("export rejects invalid UUID and missing output before accessing the database", async () => {
  const f = await fixture();
  try {
    let calls = 0;
    const runner = async () => { calls++; return []; };
    for (const options of [
      { ...f.options, submissionId: "' OR 1=1 --" },
      { ...f.options, submissionId: "a1a2a3a4-b1b2-11b4-81b2-c1c2c3c4c5c6" },
      { ...f.options, output: undefined },
    ]) await assert.rejects(exportCrashLog({ ...options, runner }));
    assert.equal(calls, 0);
  } finally { await f.close(); }
});

test("export refuses repository paths, directory links back into repository and existing files without querying", async () => {
  const f = await fixture();
  try {
    const link = path.join(f.directory, "linked-repository");
    await symlink(f.repository, link, process.platform === "win32" ? "junction" : "dir");
    await writeFile(f.options.output, "existing fictional file", "utf8");
    let calls = 0;
    const runner = async () => { calls++; return []; };
    for (const output of [path.join(f.repository, "private.log"), path.join(link, "private.log"), f.options.output]) {
      await assert.rejects(exportCrashLog({ ...f.options, output, runner }));
    }
    assert.equal(calls, 0);
    assert.equal(await readFile(f.options.output, "utf8"), "existing fictional file");
  } finally { await f.close(); }
});

test("export refuses symlink outputs and never overwrites their target", async () => {
  const f = await fixture();
  try {
    const target = path.join(f.repository, "existing-fictional.log");
    await writeFile(target, "existing fictional protected file", "utf8");
    await symlink(process.platform === "win32" ? f.repository : target, f.options.output, process.platform === "win32" ? "junction" : "file");
    let calls = 0;
    await assert.rejects(exportCrashLog({ ...f.options, runner: async () => { calls++; return []; } }));
    assert.equal(calls, 0);
    assert.equal(await readFile(target, "utf8"), "existing fictional protected file");
  } finally { await f.close(); }
});

test("missing logs, malformed results and authorization failures disclose neither logs nor service errors", async () => {
  const f = await fixture();
  try {
    const sensitive = "fictional-secret-log-and-token";
    for (const runner of [
      async () => [{ results: [] }],
      async () => [{ results: [{ crash_log: "" }] }],
      async () => [{ results: [{ crash_log: 123 }] }],
      async () => { throw new Error(sensitive); },
    ]) {
      await assert.rejects(exportCrashLog({ ...f.options, runner }), (error) => {
        assert.doesNotMatch(error.message, new RegExp(sensitive));
        return true;
      });
    }
    await assert.rejects(readFile(f.options.output), { code: "ENOENT" });
  } finally { await f.close(); }
});

test("export refuses a parent directory redirected into the repository during the remote query", async () => {
  const f = await fixture();
  try {
    const parent = path.join(f.directory, "outside-destination");
    await mkdir(parent);
    const output = path.join(parent, "fictional-private.log");
    await assert.rejects(exportCrashLog({ ...f.options, output, runner: async () => {
      await rm(parent, { recursive: true });
      await symlink(f.repository, parent, process.platform === "win32" ? "junction" : "dir");
      return [{ results: [{ crash_log: "Fictional private race fixture\n" }] }];
    } }));
    await assert.rejects(readFile(path.join(f.repository, "fictional-private.log")), { code: "ENOENT" });
  } finally { await f.close(); }
});
