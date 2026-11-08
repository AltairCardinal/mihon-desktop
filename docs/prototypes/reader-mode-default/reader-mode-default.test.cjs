const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const url = pathToFileURL(path.join(__dirname, 'index.html')).href;
const output = path.resolve(__dirname, '../../../.gradle-coordinator/reader-mode-review');

async function session(run) {
  const browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : { channel: 'chrome' }) });
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 1000 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(url);
    await run(page, page.locator('[data-device="desktop"]'), page.locator('[data-device="android"]'));
    assert.deepEqual(errors, [], '页面没有 JavaScript 错误');
  } finally { await browser.close(); }
}

test('先二选一，具体模式只在单独设置出现，切回默认取消覆盖', () => session(async (page, pc, phone) => {
  assert.equal(await pc.getByLabel('跟随默认', { exact: true }).isChecked(), true);
  assert.equal(await pc.locator('[data-custom]').isVisible(), false);
  assert.equal(await pc.locator('[data-effective]').textContent(), '翻页式');
  assert.equal(await pc.locator('[data-source]').textContent(), '来自全局默认');
  fs.mkdirSync(output, { recursive: true });
  await page.screenshot({ path: path.join(output, 'default-light.png'), fullPage: true });
  await pc.getByText('单独设置', { exact: true }).click();
  assert.equal(await pc.getByLabel('跟随默认', { exact: true }).isChecked(), false);
  assert.equal(await pc.getByLabel('翻页式', { exact: true }).isChecked(), true);
  await pc.getByText('条漫', { exact: true }).click();
  assert.equal(await pc.locator('[data-effective]').textContent(), '条漫');
  assert.equal(await pc.locator('[data-paged]').isVisible(), false);
  assert.equal(await phone.locator('[data-effective]').textContent(), '翻页式');
  await page.screenshot({ path: path.join(output, 'custom-light.png'), fullPage: true });
  await pc.getByText('跟随默认', { exact: true }).click();
  assert.equal(await pc.locator('[data-effective]').textContent(), '翻页式');
  assert.equal(await pc.locator('[data-custom]').isVisible(), false);
  await pc.getByText('单独设置', { exact: true }).click();
  assert.equal(await pc.getByLabel('翻页式', { exact: true }).isChecked(), true, '不复活旧条漫覆盖');
  await pc.getByText('从左到右', { exact: true }).click();
  await pc.getByLabel('双页并排', { exact: true }).check();
  assert.match(await pc.locator('[data-meta]').textContent(), /从左到右 · 双页并排/);
}));

test('默认跟随全局场景，独立设置不受影响，自动时明确显示实际单页或双页', () => session(async (page, pc, phone) => {
  await pc.getByText('单独设置', { exact: true }).click();
  await page.getByLabel('演示全局默认').selectOption('webtoon');
  assert.equal(await phone.locator('[data-effective]').textContent(), '条漫');
  assert.equal(await pc.locator('[data-effective]').textContent(), '翻页式');
  await pc.getByText('跟随默认', { exact: true }).click();
  assert.equal(await pc.locator('[data-effective]').textContent(), '条漫');
  await page.getByLabel('演示全局默认').selectOption('auto');
  assert.equal(await pc.locator('[data-effective]').textContent(), '自动 → 翻页式');
  assert.match(await pc.locator('[data-meta]').textContent(), /双页并排/);
  assert.match(await phone.locator('[data-meta]').textContent(), /单页/);
  await page.getByLabel('演示桌面窗口').selectOption('narrow');
  assert.match(await pc.locator('[data-meta]').textContent(), /单页/);
  await pc.getByText('单独设置', { exact: true }).click();
  assert.equal(await pc.getByLabel('自动', { exact: true }).isChecked(), true);
  await pc.getByText('翻页式', { exact: true }).click();
  await page.getByLabel('演示桌面窗口').selectOption('wide');
  assert.match(await pc.locator('[data-meta]').textContent(), /单页/, '手动模式不随窗口演示变化');
}));

test('关闭重开保留，键盘与跨端焦点独立，窄屏大字浅深色无裁切', () => session(async (page, pc, phone) => {
  await pc.getByText('单独设置', { exact: true }).click();
  await pc.getByLabel('翻页式', { exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  assert.equal(await pc.getByLabel('条漫', { exact: true }).isChecked(), true);
  await page.keyboard.press('Escape');
  assert.equal(await pc.locator('.rm-panel').isVisible(), false);
  assert.equal(await phone.locator('.rm-panel').isVisible(), true);
  await pc.getByText('打开阅读器设置', { exact: true }).click();
  assert.equal(await pc.getByLabel('条漫', { exact: true }).isChecked(), true);
  assert.equal(await pc.getByLabel('单独设置', { exact: true }).evaluate(el => el === document.activeElement), true);
  await phone.getByLabel('跟随默认', { exact: true }).focus();
  await pc.getByText('跟随默认', { exact: true }).click();
  await phone.getByLabel('跟随默认', { exact: true }).focus();
  await page.evaluate(() => {
    const picker = document.querySelector('[data-global]');
    picker.value = 'auto'; picker.dispatchEvent(new Event('change', { bubbles: true }));
  });
  assert.equal(await phone.getByLabel('跟随默认', { exact: true }).evaluate(el => el === document.activeElement), true);
  for (const colorScheme of ['light', 'dark']) {
    await page.emulateMedia({ colorScheme });
    await page.setViewportSize({ width: 320, height: 1000 });
    await page.locator('#reader-mode-review').evaluate(el => {
      for (const child of el.querySelectorAll('*')) {
        const size = parseFloat(getComputedStyle(child).fontSize);
        child.dataset.originalSize ||= String(size);
      }
      for (const child of el.querySelectorAll('*')) child.style.fontSize = Number(child.dataset.originalSize) * 2 + 'px';
    });
    await page.screenshot({ path: path.join(output, `narrow-${colorScheme}.png`), fullPage: true });
    const overflow = await page.evaluate(() => [...document.querySelectorAll('*')].filter(el => el.getBoundingClientRect().right > window.innerWidth + 1).map(el => `${el.tagName}.${el.className}`).slice(0, 12));
    assert.deepEqual(overflow, [], '窄屏无横向溢出');
    for (const control of await page.locator('button:visible, select:visible, .rm-pick:visible').all()) {
      const bounds = await control.boundingBox();
      assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 320, '可见控件在窗口内');
    }
  }
}));
