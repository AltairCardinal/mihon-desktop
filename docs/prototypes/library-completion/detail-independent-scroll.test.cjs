const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test("宽窗口详情双栏独立滚动，只有章节栏显示滚动条；窄窗口恢复单列滚动", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true, ignoreDefaultArgs: ["--hide-scrollbars"] });
  try {
    const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows");
    await page.evaluate(() => {
      window.demo.scenario("detail-long-chapters");
      window.demo.state.books[0].notes = Array.from({ length: 80 }, (_, i) => `笔记第 ${i + 1} 行`).join("\n");
    });
    await page.getByTestId("manga-A").click();
    const info = page.getByTestId("detail-info-scroll");
    const chapters = page.getByTestId("detail-chapter-scroll");
    const whole = page.getByTestId("detail-scroll");
    const initial = await page.evaluate(() => {
      const left = document.querySelector('[data-testid="detail-info-scroll"]');
      const right = document.querySelector('[data-testid="detail-chapter-scroll"]');
      return {
        leftLong: left.scrollHeight > left.clientHeight,
        rightLong: right.scrollHeight > right.clientHeight,
        leftScrollbar: getComputedStyle(left).scrollbarWidth,
        rightScrollbar: getComputedStyle(right).scrollbarWidth,
      };
    });
    assert.equal(initial.leftLong, true);
    assert.equal(initial.rightLong, true);
    assert.equal(initial.leftScrollbar, "none");
    assert.notEqual(initial.rightScrollbar, "none");

    await chapters.hover();
    await page.mouse.wheel(0, 550);
    await page.waitForFunction(() => document.querySelector('[data-testid="detail-chapter-scroll"]').scrollTop > 0);
    assert.ok(await chapters.evaluate((node) => node.scrollTop) > 0);
    assert.equal(await info.evaluate((node) => node.scrollTop), 0);
    assert.equal(await whole.evaluate((node) => node.scrollTop), 0);

    await info.hover();
    await page.mouse.wheel(0, 300);
    await page.waitForFunction(() => document.querySelector('[data-testid="detail-info-scroll"]').scrollTop > 0);
    assert.ok(await info.evaluate((node) => node.scrollTop) > 0);
    const rightTop = await chapters.evaluate((node) => node.scrollTop);
    await page.mouse.wheel(0, 300);
    assert.equal(await chapters.evaluate((node) => node.scrollTop), rightTop);

    const leftTop = await info.evaluate((node) => node.scrollTop);
    await page.evaluate(() => window.demo.command("noop"));
    assert.equal(await info.evaluate((node) => node.scrollTop), leftTop);
    assert.equal(await chapters.evaluate((node) => node.scrollTop), rightTop);

    await info.evaluate((node) => { node.scrollTop = 0; });
    await info.hover();
    await page.mouse.wheel(0, -100);
    assert.equal(await page.getByTestId("detail-pull-tip").textContent(), "");
    assert.equal(await page.evaluate(() => window.demo.state.job), null);

    await page.setViewportSize({ width: 360, height: 700 });
    await chapters.hover();
    await page.mouse.wheel(0, 350);
    await page.waitForFunction(() => document.querySelector('[data-testid="detail-scroll"]').scrollTop > 0);
    assert.ok(await whole.evaluate((node) => node.scrollTop) > 0);
    assert.equal(await chapters.evaluate((node) => node.scrollTop), 0);
  } finally {
    await browser.close();
  }
});

test("宽窗口在右侧列表顶部用真实滚轮分两段触发详情刷新", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows");
    await page.getByTestId("manga-A").click();
    await page.getByTestId("detail-chapter-scroll").hover();
    await page.mouse.wheel(0, -90);
    assert.match(await page.getByTestId("detail-pull-tip").textContent(), /再次/);
    assert.equal(await page.evaluate(() => window.demo.state.job), null);
    await page.waitForTimeout(350);
    await page.mouse.wheel(0, -50);
    await page.waitForFunction(() => window.demo.state.job?.status === "done");
  } finally {
    await browser.close();
  }
});
