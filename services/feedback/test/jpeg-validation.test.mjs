import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { jpegDimensions, validateScreenshots, sha256Bytes } from "../src/screenshots.mjs";

const jpeg = readFileSync(new URL("./fixtures/fictional-screenshot.jpg", import.meta.url));
function segment(marker, payload) {
  const header = Buffer.from([0xff, marker, 0, 0]);
  header.writeUInt16BE(payload.length + 2, 2);
  return Buffer.concat([header, payload]);
}
const inject = (marker, payload) => Buffer.concat([jpeg.subarray(0, 2), segment(marker, payload), jpeg.subarray(2)]);
function without(marker) {
  let result = Buffer.from(jpeg), at;
  while ((at = result.indexOf(Buffer.from([0xff, marker]))) !== -1) {
    const size = result.readUInt16BE(at + 2);
    result = Buffer.concat([result.subarray(0, at), result.subarray(at + 2 + size)]);
  }
  return result;
}

test("real generated JPEG has bounded dimensions; metadata, trailing payload and malformed containers are refused", () => {
  assert.deepEqual(jpegDimensions(jpeg), { width: 16, height: 12 });
  for (const bad of [inject(0xe1, Buffer.from("Exif\0\0PRIVATE_GPS")), inject(0xe2, Buffer.from("ICC_PROFILE\0private")),
    inject(0xfe, Buffer.from("private comment")), Buffer.concat([jpeg, Buffer.from("trailing")]),
    jpeg.subarray(0, jpeg.length - 1), Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0xff, 0xff]),
    Buffer.alloc(524289), Buffer.from("not an image")]) assert.equal(jpegDimensions(bad), null);
});

test("JPEG rejects missing quantization and Huffman tables instead of treating marker signatures as a valid image", () => {
  assert.equal(jpegDimensions(without(0xdb)), null);
  assert.equal(jpegDimensions(without(0xc4)), null);
  const invalidTree = segment(0xc4, Buffer.from([0, 3, ...Array(15).fill(0), 0, 1, 2]));
  assert.equal(jpegDimensions(Buffer.concat([jpeg.subarray(0, 2), invalidTree, jpeg.subarray(2)])), null);
});

test("media manifest shape, canonical Base64, exact bytes and independent hash all remain enforced", async () => {
  const image = { id: "123e4567-e89b-42d3-a456-426614174111", mime: "image/jpeg", width: 16, height: 12,
    byteLength: jpeg.length, sha256: await sha256Bytes(jpeg), dataBase64: jpeg.toString("base64") };
  assert.equal((await validateScreenshots([image], true)).length, 1);
  for (const bad of [null, {}, [image, image], [image, { ...image, id: "uppercase-uuid" }],
    [{ ...image, dataBase64: image.dataBase64 + "\n" }], [{ ...image, sha256: image.sha256.toUpperCase() }],
    [{ ...image, byteLength: 524289 }], [{ ...image, width: 0 }], [{ ...image, width: 1921 }],
    [{ ...image, name: "private.jpg" }]]) assert.equal(await validateScreenshots(bad, true), null);
  assert.equal(await validateScreenshots([image], false), null);
  assert.deepEqual(await validateScreenshots([], false), []);
});
