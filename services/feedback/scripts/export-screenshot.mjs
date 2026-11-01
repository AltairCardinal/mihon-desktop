import path from "node:path";
import { pathToFileURL } from "node:url";
import { ExportError, writePrivateExport, wranglerQuery } from "./export-crash-log.mjs";
import { jpegDimensions, sha256Bytes, SCREENSHOT_LIMITS } from "../src/screenshots.mjs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export async function exportScreenshot({ submissionId, ordinal, output, runner = wranglerQuery, repositoryRoots } = {}) {
  if (typeof submissionId !== "string" || !UUID.test(submissionId) || !Number.isInteger(ordinal) || ordinal < 0 || ordinal > 1 ||
    typeof output !== "string" || !/\.jpg$/i.test(output)) throw new ExportError("A strict submission UUID, ordinal 0 or 1 and new outside .jpg file are required.");
  return writePrivateExport({ output, repositoryRoots, load: async () => {
    let result;
    try {
      result = await runner(`SELECT s.mime, s.width, s.height, s.byte_length, s.sha256, hex(s.data) AS data_hex
        FROM report_screenshots s JOIN reports r ON r.submission_id = s.submission_id
        WHERE r.submission_id = '${submissionId.toLowerCase()}' AND r.screenshot_consent = 1 AND s.ordinal = ${ordinal} LIMIT 1`);
    } catch { throw new ExportError("Cannot retrieve the screenshot. Verify the maintainer's Wrangler authorization."); }
    const row = Array.isArray(result) && result.length === 1 && Array.isArray(result[0]?.results) && result[0].results.length === 1
      ? result[0].results[0] : null;
    if (!row || row.mime !== "image/jpeg" || !Number.isInteger(row.byte_length) || row.byte_length < 1 ||
      row.byte_length > SCREENSHOT_LIMITS.maxScreenshotBytes || typeof row.data_hex !== "string" ||
      row.data_hex.length !== row.byte_length * 2 || !/^[0-9A-Fa-f]+$/.test(row.data_hex)) {
      throw new ExportError("No valid authorized screenshot is available for that submission.");
    }
    const bytes = Buffer.from(row.data_hex, "hex");
    const dimensions = jpegDimensions(bytes);
    if (!dimensions || dimensions.width !== row.width || dimensions.height !== row.height ||
      await sha256Bytes(bytes) !== row.sha256) throw new ExportError("Screenshot integrity validation failed; no file was exported.");
    return bytes;
  } });
}

async function main() {
  const args = process.argv.slice(2), options = {};
  for (let index = 0; index < args.length; index += 2) {
    const name = { "--submission-id": "submissionId", "--ordinal": "ordinal", "--output": "output" }[args[index]];
    if (!name || !args[index + 1] || options[name] !== undefined || (name === "ordinal" && !/^[01]$/.test(args[index + 1]))) {
      throw new ExportError("Usage: node scripts/export-screenshot.mjs --submission-id <UUIDv4> --ordinal <0|1> --output <new-outside-file.jpg>");
    }
    options[name] = name === "ordinal" ? Number(args[index + 1]) : args[index + 1];
  }
  const result = await exportScreenshot(options);
  process.stdout.write(`Screenshot saved: ${result.filename}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch((error) => { process.stderr.write(`${error instanceof ExportError ? error.message : "Screenshot export failed."}\n`); process.exitCode = 1; });
}
