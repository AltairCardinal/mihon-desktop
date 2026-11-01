export const SCREENSHOT_LIMITS = Object.freeze({ maxScreenshots: 2, maxScreenshotBytes: 524288,
  maxTotalScreenshotBytes: 1048576, maxScreenshotSide: 1920, maxBodyBytes: 2097152, maxLogBytes: 262144 });
export const SCREENSHOT_POOL_BYTES = 32 * 1024 * 1024;
export const CATEGORY_NAMES = Object.freeze({
  startup_crash: "崩溃或无法启动", performance: "卡顿、无响应或资源占用过高",
  source_network: "漫画源、扩展或网络连接", browse_details: "搜索、浏览或漫画详情",
  reader: "阅读器、图片或翻页", library_progress: "书架、分类或阅读进度",
  download_local: "下载、离线阅读或本地文件", backup_sync: "备份、恢复或多设备同步",
  update_install: "应用更新或安装", interface: "界面显示或操作异常", feature_request: "功能建议", other: "其他／不确定",
});
export function categoryTitle(id) {
  return typeof id === "string" && Object.hasOwn(CATEGORY_NAMES, id) ? `【问题反馈】${CATEGORY_NAMES[id]}` : null;
}
export async function sha256Bytes(bytes) {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)), (b) => b.toString(16).padStart(2, "0")).join("");
}

// Bounded container validation, not pixel decoding or a malware scanner.
// Fresh platform encoders produce SOF0/SOF2 JPEG, optional thumbnail-free JFIF,
// quantization/Huffman tables and scans. Reject metadata and trailing payloads.
export function jpegDimensions(bytes) {
  if (!(bytes instanceof Uint8Array) || bytes.length < 20 || bytes.length > SCREENSHOT_LIMITS.maxScreenshotBytes ||
    bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  let cursor = 2, frame = null, scans = 0;
  const quantization = new Set(), huffman = new Set();
  const word = (at) => (bytes[at] << 8) | bytes[at + 1];
  while (cursor < bytes.length) {
    if (bytes[cursor++] !== 0xff) return null;
    while (bytes[cursor] === 0xff) cursor++;
    const marker = bytes[cursor++];
    if (marker === 0xd9) return frame && scans > 0 && cursor === bytes.length ? { width: frame.width, height: frame.height } : null;
    if (![0xe0, 0xdb, 0xc4, 0xc0, 0xc2, 0xdd, 0xda].includes(marker) || cursor + 2 > bytes.length) return null;
    const size = word(cursor), begin = cursor + 2, end = cursor + size;
    if (size < 2 || end > bytes.length) return null;
    if (marker === 0xe0) {
      if (size !== 16 || String.fromCharCode(...bytes.subarray(begin, begin + 5)) !== "JFIF\0" ||
        bytes[begin + 12] !== 0 || bytes[begin + 13] !== 0) return null;
    } else if (marker === 0xc0 || marker === 0xc2) {
      const channels = bytes[begin + 5];
      if (frame || ![1, 3].includes(channels) || size !== 8 + 3 * channels || bytes[begin] !== 8) return null;
      const height = word(begin + 1), width = word(begin + 3);
      if (width < 1 || height < 1 || Math.max(width, height) > SCREENSHOT_LIMITS.maxScreenshotSide) return null;
      const ids = Array.from({ length: channels }, (_, i) => bytes[begin + 6 + 3 * i]);
      if (new Set(ids).size !== channels) return null;
      const tables = [];
      for (let i = 0; i < channels; i++) {
        const sampling = bytes[begin + 7 + 3 * i], table = bytes[begin + 8 + 3 * i];
        if ((sampling >> 4) < 1 || (sampling >> 4) > 4 || (sampling & 15) < 1 || (sampling & 15) > 4 || table > 3) return null;
        tables.push(table);
      }
      frame = { width, height, channels, ids, tables, progressive: marker === 0xc2 };
    } else if (marker === 0xdb) {
      let at = begin;
      while (at < end) {
        const precision = bytes[at] >> 4, table = bytes[at++] & 15;
        if (precision > 1 || table > 3) return null;
        at += 64 * (precision + 1);
        quantization.add(table);
      }
      if (at !== end) return null;
    } else if (marker === 0xc4) {
      let at = begin;
      while (at < end) {
        const table = bytes[at++];
        if ((table >> 4) > 1 || (table & 15) > 3 || at + 16 > end) return null;
        let count = 0, available = 1;
        for (let i = 0; i < 16; i++) {
          const n = bytes[at++];
          available = available * 2 - n;
          if (available < 0) return null;
          count += n;
        }
        if (count === 0 || count > 256 || at + count > end) return null;
        at += count;
        huffman.add(table);
      }
      if (at !== end) return null;
    } else if (marker === 0xdd && size !== 4) return null;
    cursor = end;
    if (marker === 0xda) {
      const channels = bytes[begin];
      if (!frame || channels < 1 || channels > frame.channels || size !== 6 + 2 * channels) return null;
      const ids = Array.from({ length: channels }, (_, i) => bytes[begin + 1 + 2 * i]);
      if (new Set(ids).size !== channels || ids.some((id) => !frame.ids.includes(id))) return null;
      if (frame.tables.some((id) => !quantization.has(id))) return null;
      const spectralStart = bytes[end - 3], spectralEnd = bytes[end - 2];
      if (spectralStart > spectralEnd || spectralEnd > 63) return null;
      if (!frame.progressive && (spectralStart !== 0 || spectralEnd !== 63 || bytes[end - 1] !== 0)) return null;
      for (let i = 0; i < channels; i++) {
        const table = bytes[begin + 2 + 2 * i];
        if ((table >> 4) > 3 || (table & 15) > 3 ||
          (spectralStart === 0 && !huffman.has(table >> 4)) ||
          (spectralEnd > 0 && !huffman.has(0x10 | (table & 15)))) return null;
      }
      const scanStart = cursor;
      while (cursor < bytes.length) {
        if (bytes[cursor] !== 0xff) { cursor++; continue; }
        const next = bytes[cursor + 1];
        if (next === 0 || (next >= 0xd0 && next <= 0xd7)) { cursor += 2; continue; }
        break;
      }
      if (cursor === scanStart) return null;
      scans++;
    }
  }
  return null;
}

export async function validateScreenshots(value, consent) {
  if (!Array.isArray(value) || value.length > SCREENSHOT_LIMITS.maxScreenshots ||
    typeof consent !== "boolean" || (value.length > 0 && consent !== true)) return null;
  const ids = new Set(), result = [];
  let total = 0;
  const allowed = ["id", "mime", "width", "height", "byteLength", "sha256", "dataBase64"];
  for (const image of value) {
    if (!image || typeof image !== "object" || Array.isArray(image) || Object.keys(image).some((k) => !allowed.includes(k)) ||
      typeof image.id !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(image.id) ||
      ids.has(image.id) || image.mime !== "image/jpeg" || !Number.isInteger(image.width) || !Number.isInteger(image.height) ||
      !Number.isInteger(image.byteLength) || image.byteLength < 1 || image.byteLength > SCREENSHOT_LIMITS.maxScreenshotBytes ||
      typeof image.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(image.sha256) || typeof image.dataBase64 !== "string" ||
      image.dataBase64.length > 4 * Math.ceil(SCREENSHOT_LIMITS.maxScreenshotBytes / 3) ||
      image.dataBase64.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(image.dataBase64)) return null;
    let bytes;
    try {
      bytes = Buffer.from(image.dataBase64, "base64");
      if (bytes.toString("base64") !== image.dataBase64) return null;
    } catch { return null; }
    const dimensions = jpegDimensions(bytes);
    if (!dimensions || dimensions.width !== image.width || dimensions.height !== image.height ||
      bytes.length !== image.byteLength || await sha256Bytes(bytes) !== image.sha256) return null;
    total += bytes.length;
    if (total > SCREENSHOT_LIMITS.maxTotalScreenshotBytes) return null;
    ids.add(image.id);
    result.push({ id: image.id, mime: image.mime, ...dimensions, byteLength: bytes.length, sha256: image.sha256, bytes });
  }
  return result;
}
import { Buffer } from "node:buffer";
