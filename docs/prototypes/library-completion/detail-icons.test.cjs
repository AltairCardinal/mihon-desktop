const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test("详情按钮使用原版语义与轮廓，反选显示 FlipToBack 而非双向箭头", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 800 } });
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows&review=detail-parity");
    const namedIcon = async (testid) => page.getByTestId(testid).locator("svg").getAttribute("data-mihon-icon");
    for (const [testid, source] of [["detail-back", "arrow_back"], ["detail-download-menu", "download"], ["detail-filter-menu", "filter_list"], ["detail-overflow", "more_vert"], ["detail-continue", "play_arrow"]]) {
      assert.equal(await namedIcon(testid), source);
    }
    assert.equal(await namedIcon("detail-library"), "favorite_fill1");
    assert.equal(await namedIcon("detail-fetch-interval"), "hourglass_empty");
    assert.equal(await namedIcon("detail-tracking"), "done");
    assert.equal(await namedIcon("detail-open-link"), "public");
    await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
    assert.equal(await namedIcon("detail-select-all"), "select_all");
    assert.equal(await namedIcon("detail-select-invert"), "flip_to_back");
    assert.equal(await namedIcon("detail-select-close"), "close");
    assert.equal(await namedIcon("detail-batch-bookmark"), "bookmark_add");
    assert.equal(await namedIcon("detail-batch-read"), "done_all");
    assert.equal(await namedIcon("detail-batch-previous"), "done_previous");
    assert.equal(await namedIcon("detail-batch-download"), "download");
    const inversePath = await page.getByTestId("detail-select-invert").locator("svg path").getAttribute("d");
    assert.match(inversePath, /^M200-120q-33 0-56\.5-23\.5/, "应使用原版 FlipToBack 路径而不是双向箭头");
    await page.getByTestId("detail-select-invert").click();
    assert.equal((await page.getByTestId("detail-selection-count").textContent()).trim(), "2");
    await page.getByTestId("detail-select-close").click();
    await page.getByTestId("chapter-row-A-1").click({ modifiers: ["Control"] });
    assert.equal(await namedIcon("detail-batch-bookmark"), "bookmark_remove");
    assert.equal(await namedIcon("detail-batch-unread"), "remove_done");
    assert.equal(await namedIcon("detail-batch-delete"), "delete");
  } finally {
    await browser.close();
  }
});
