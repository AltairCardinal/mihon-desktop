import { execFile } from "node:child_process";
import { constants } from "node:fs";
import { lstat, open, realpath, stat, unlink } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { MAX_LOG_BYTES, crashLogBytes, validCrashLogText } from "../public/crash-log.mjs";

const execute = promisify(execFile);
const serviceDirectory = fileURLToPath(new URL("../", import.meta.url));
const repositoryDirectory = fileURLToPath(new URL("../../../", import.meta.url));
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

class ExportError extends Error {}

async function repositoryWorktrees() {
  try {
    const { stdout } = await execute("git", ["worktree", "list", "--porcelain", "-z"], {
      cwd: repositoryDirectory, encoding: "utf8", windowsHide: true, timeout: 10000, maxBuffer: 1024 * 1024,
    });
    return [repositoryDirectory, ...stdout.split("\0").filter((field) => field.startsWith("worktree ")).map((field) => field.slice(9))];
  } catch { throw new ExportError("Cannot verify repository boundaries; no log was exported."); }
}

function inside(candidate, root) {
  const relative = path.relative(root, candidate);
  return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

async function checkedDestination(output, roots) {
  if (typeof output !== "string" || !output || /[\x00-\x1f\x7f]/.test(output)) {
    throw new ExportError("A valid --output path outside the repository is required.");
  }
  const requested = path.resolve(output);
  try {
    const parent = await realpath(path.dirname(requested));
    const parentStat = await stat(parent, { bigint: true });
    if (!parentStat.isDirectory()) throw new Error("not_directory");
    const canonical = path.join(parent, path.basename(requested));
    for (const root of roots) {
      const absoluteRoot = path.resolve(root);
      let canonicalRoot = absoluteRoot;
      try { canonicalRoot = await realpath(absoluteRoot); } catch { /* A listed prunable worktree may no longer exist. */ }
      if (inside(requested, absoluteRoot) || inside(requested, canonicalRoot) || inside(canonical, absoluteRoot) || inside(canonical, canonicalRoot)) {
        throw new ExportError("Refusing to export a private log into a repository or linked worktree.");
      }
    }
    try {
      await lstat(canonical);
      throw new ExportError("Refusing to overwrite an existing file or symbolic link.");
    } catch (error) { if (error.code !== "ENOENT") throw error; }
    return { requested, canonical, parent, device: parentStat.dev, inode: parentStat.ino };
  } catch (error) {
    if (error instanceof ExportError) throw error;
    throw new ExportError("Cannot use the output location; no log was exported.");
  }
}

async function wranglerQuery(sql) {
  const environment = { ...process.env, CI: "1", WRANGLER_SEND_METRICS: "false", NO_COLOR: "1" };
  // Use the maintainer's existing Wrangler OAuth session. Do not silently select
  // an unrelated API-token identity or launch an interactive login from export.
  for (const name of ["CLOUDFLARE_API_TOKEN", "CLOUDFLARE_API_KEY", "CLOUDFLARE_EMAIL", "CF_API_TOKEN", "CF_API_KEY", "CF_EMAIL"]) delete environment[name];
  try {
    const { stdout } = await execute(process.execPath, [
      fileURLToPath(new URL("../node_modules/wrangler/bin/wrangler.js", import.meta.url)),
      "d1", "execute", "mihon-feedback", "--remote", "--command", sql, "--json",
    ], {
      cwd: serviceDirectory, env: environment, encoding: "utf8", windowsHide: true,
      timeout: 60000, maxBuffer: 3 * 1024 * 1024,
    });
    return JSON.parse(stdout);
  } catch { throw new ExportError("Cannot retrieve the log. Verify the maintainer's Wrangler authorization."); }
}

export async function exportCrashLog({ submissionId, output, runner = wranglerQuery, repositoryRoots } = {}) {
  if (typeof submissionId !== "string" || !UUID.test(submissionId)) {
    throw new ExportError("A strict UUIDv4 --submission-id is required.");
  }
  const roots = repositoryRoots ?? await repositoryWorktrees();
  if (!Array.isArray(roots) || roots.length === 0 || roots.some((root) => typeof root !== "string" || !root)) {
    throw new ExportError("Cannot verify repository boundaries; no log was exported.");
  }
  const destination = await checkedDestination(output, roots);
  let result;
  try {
    result = await runner(`SELECT crash_log FROM reports WHERE submission_id = '${submissionId.toLowerCase()}' AND crash_log_consent = 1 LIMIT 1`);
  } catch { throw new ExportError("Cannot retrieve the log. Verify the maintainer's Wrangler authorization."); }
  const log = Array.isArray(result) && result.length === 1 && Array.isArray(result[0]?.results) && result[0].results.length === 1
    ? result[0].results[0]?.crash_log : undefined;
  if (typeof log !== "string" || log === "" || !validCrashLogText(log) || crashLogBytes(log) > MAX_LOG_BYTES) {
    throw new ExportError("No valid authorized crash log is available for that submission.");
  }
  // The remote query can take up to a minute. Re-resolve the requested path
  // after that await: a parent might have become a junction into a repository.
  // Also reject replacement by a different directory at the same path.
  const current = await checkedDestination(output, roots);
  if (current.requested !== destination.requested || current.canonical !== destination.canonical ||
      current.parent !== destination.parent || current.device !== destination.device || current.inode !== destination.inode) {
    throw new ExportError("The output directory changed during retrieval; no log was exported.");
  }
  let file;
  let created = false;
  try {
    file = await open(current.canonical, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | (constants.O_NOFOLLOW ?? 0), 0o600);
    created = true;
    await file.writeFile(log, { encoding: "utf8" });
    await file.close();
    file = undefined;
  } catch {
    if (file) await file.close().catch(() => {});
    if (created) await unlink(current.canonical).catch(() => {});
    throw new ExportError("Cannot write a new output file; no log was exported.");
  }
  return { filename: path.basename(current.canonical) };
}

async function main() {
  const args = process.argv.slice(2);
  const options = {};
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index] === "--submission-id" ? "submissionId" : args[index] === "--output" ? "output" : null;
    if (!key || !args[index + 1] || options[key] !== undefined) throw new ExportError("Usage: node scripts/export-crash-log.mjs --submission-id <UUIDv4> --output <outside-file>");
    options[key] = args[index + 1];
  }
  const result = await exportCrashLog(options);
  process.stdout.write(`Crash log saved: ${result.filename}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch((error) => {
    process.stderr.write(`${error instanceof ExportError ? error.message : "Export failed; no log was exported."}\n`);
    process.exitCode = 1;
  });
}
