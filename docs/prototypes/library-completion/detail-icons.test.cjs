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
    for (const [testid, source] of [["detail-back", "AutoMirrored.Outlined.ArrowBack"], ["detail-download-menu", "Outlined.Download"], ["detail-filter-menu", "Outlined.FilterList"], ["detail-overflow", "Outlined.MoreVert"], ["detail-continue", "Filled.PlayArrow"]]) {
      assert.equal(await namedIcon(testid), source);
    }
    assert.equal(await namedIcon("detail-library"), "Filled.Favorite");
    assert.equal(await namedIcon("detail-fetch-interval"), "Filled.HourglassEmpty");
    assert.equal(await namedIcon("detail-tracking"), "Outlined.Done");
    assert.equal(await namedIcon("detail-open-link"), "Outlined.Public");
    assert.equal(await namedIcon("chapter-delete-A-1"), "Filled.CheckCircle");
    assert.equal(await namedIcon("chapter-download-A-3"), "Drawable.ic_download_chapter_24dp");
    assert.equal(await page.locator("#app svg:not([data-mihon-icon])").count(), 0);
    await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
    assert.equal(await namedIcon("detail-select-all"), "Outlined.SelectAll");
    assert.equal(await namedIcon("detail-select-invert"), "Outlined.FlipToBack");
    assert.equal(await namedIcon("detail-select-close"), "Outlined.Close");
    assert.equal(await namedIcon("detail-batch-bookmark"), "Outlined.BookmarkAdd");
    assert.equal(await namedIcon("detail-batch-read"), "Outlined.DoneAll");
    assert.equal(await namedIcon("detail-batch-previous"), "Drawable.ic_done_prev_24dp");
    assert.equal(await namedIcon("detail-batch-download"), "Outlined.Download");
    assert.equal(await page.locator("#app svg:not([data-mihon-icon])").count(), 0);
    const inversePath = await page.getByTestId("detail-select-invert").locator("svg path").getAttribute("d");
    assert.match(inversePath, /^M 9 7 L 7 7 v 2 h 2/, "应使用仓库 Android Icons.Outlined.FlipToBack 的路径");
    await page.getByTestId("detail-select-invert").click();
    assert.equal((await page.getByTestId("detail-selection-count").textContent()).trim(), "2");
    await page.getByTestId("detail-select-close").click();
    await page.getByTestId("chapter-row-A-1").click({ modifiers: ["Control"] });
    assert.equal(await namedIcon("detail-batch-bookmark"), "Outlined.BookmarkRemove");
    assert.equal(await namedIcon("detail-batch-unread"), "Outlined.RemoveDone");
    assert.equal(await namedIcon("detail-batch-delete"), "Outlined.Delete");
    assert.equal(await page.locator("#app svg:not([data-mihon-icon])").count(), 0);
    await page.getByTestId("detail-select-close").click();

    await page.getByTestId("detail-cover-open").click();
    for (const [testid, source] of [["modal-close", "Outlined.Close"], ["detail-cover-save", "Outlined.Save"], ["detail-cover-share", "Outlined.Share"], ["detail-cover-menu", "Outlined.Edit"]]) {
      assert.equal(await namedIcon(testid), source);
    }
    assert.equal(await page.locator("#app svg:not([data-mihon-icon])").count(), 0);
    await page.getByTestId("modal-close").click();

    await page.evaluate(() => {
      window.demo.state.books[0].favorite = false;
      window.demo.state.books[0].chapters[2].downloadStatus = "error";
      window.demo.command("noop");
    });
    assert.equal(await namedIcon("detail-library"), "Outlined.FavoriteBorder");
    assert.equal(await namedIcon("chapter-retry-A-3"), "Outlined.ErrorOutline");
    assert.equal(await page.locator("#app svg:not([data-mihon-icon])").count(), 0);
    await page.evaluate(() => {
      window.demo.state.books[0].tracks = [];
      window.demo.command("noop");
    });
    assert.equal(await namedIcon("detail-tracking"), "Outlined.Sync");

    await page.getByTestId("detail-back").click();
    await page.getByTestId("manga-C").click();
    assert.equal(await namedIcon("chapter-progress-C-3"), "Outlined.ArrowDownward");
    await page.getByTestId("detail-back").click();
    await page.getByTestId("manga-D").click();
    assert.equal(await namedIcon("chapter-progress-D-3"), "Outlined.ArrowDownward");
    assert.equal(await page.locator("#app svg:not([data-mihon-icon])").count(), 0);
  } finally {
    await browser.close();
  }
});
