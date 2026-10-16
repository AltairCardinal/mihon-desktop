export const MAX_LOG_BYTES = 256 * 1024;
export const MAX_PREVIEW_BYTES = 4 * 1024 * 1024;

export function normalizeCrashLog(value) {
  return value.replace(/\r\n/g, "\n");
}

export function crashLogBytes(value) {
  return new TextEncoder().encode(normalizeCrashLog(value)).byteLength;
}

export function validCrashLogText(value) {
  // Unicode-aware matching accepts paired supplementary code points, but rejects
  // lone surrogates, decoder replacement characters and binary control bytes.
  return typeof value === "string" && !/[\uD800-\uDFFF\uFFFD\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]/u.test(value);
}
