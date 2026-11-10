const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const output = path.resolve(__dirname, '../../../.gradle-coordinator/reader-mode-v3');
async function session(run) {
  const browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : { channel: 'chrome' }) });
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 1100 }, reducedMotion: 'reduce' });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href);
    fs.mkdirSync(output, { recursive: true });
    await run(page, page.locator('[data-device="desktop"] mihon-reader-settings'), page.locator('[data-device="android"] mihon-reader-settings'));
    assert.deepEqual(errors, [], '无页面脚本错误');
  } finally { await browser.close(); }
}
const pick = (host, key, value) => host.locator(`input[data-setting="${key}"][value="${value}"]`).check();
const value = host => host.evaluate(el => el.value);
const shot = (page, name) => page.screenshot({ path: path.join(output, name + '.png'), fullPage: true });

test('双页不提供纵向，切换、键盘和旧配置均保持合法方向', () => session(async (page, pc, mobile) => {
  for (const host of [pc, mobile]) {
    await pick(host, 'layout', 'single');
    await pick(host, 'direction', 'vertical');
    await pick(host, 'layout', 'double');
    assert.equal(await host.locator('[data-setting="direction"][value="vertical"]').isVisible(), false);
    assert.equal((await value(host)).custom.direction, 'rtl');
    await host.locator('[data-setting="direction"][value="rtl"]').focus();
    await page.keyboard.press('ArrowRight');
    assert.equal((await value(host)).custom.direction, 'ltr', '键盘跳过纵向选项');
    await host.evaluate(el => { el.value = { scope: 'custom', custom: { mode: 'paged', layout: 'double', direction: 'vertical' } }; });
    assert.equal((await value(host)).custom.direction, 'rtl');
    await host.evaluate(el => { el.defaultSettings = { mode: 'paged', layout: 'double', direction: 'vertical' }; });
    await pick(host, 'scope', 'default');
    assert.equal(await host.locator('[data-default-direction]').textContent(), '从右到左');
    await pick(host, 'scope', 'custom');
    await pick(host, 'layout', 'single');
    assert.equal(await host.locator('[data-setting="direction"][value="vertical"]').isVisible(), true);
  }
  await page.reload();
  await pick(pc, 'layout', 'double');
  await pick(mobile, 'layout', 'double');
  await shot(page, 'double-horizontal-only');
}));

test('v3 双端同一交互：模式与单双页分层，隐藏项及选择值保留一致', () => session(async (page, pc, mobile) => {
  assert.equal(await pc.locator('input[data-setting="mode"]').count(), 2, '阅读模式只有翻页和条漫');
  for (const host of [pc, mobile]) {
    assert.equal(await host.locator('[data-setting="scope"][value="custom"]').isChecked(), true);
    await pick(host, 'layout', 'single');
    assert.equal(await host.locator('[data-match-row]').isVisible(), false);
    await pick(host, 'layout', 'double');
    assert.equal(await host.locator('[data-match-row]').isVisible(), true);
    await host.getByRole('switch', { name: '双页自动匹配' }).uncheck();
    await pick(host, 'direction', 'ltr');
    await pick(host, 'mode', 'webtoon');
    assert.equal(await host.locator('[data-paged-settings]').isVisible(), false);
    await host.getByRole('switch', { name: '页间有空隙' }).check();
    await pick(host, 'mode', 'paged');
    assert.equal(await host.locator('[data-setting="layout"][value="double"]').isChecked(), true);
    assert.equal(await host.locator('[data-setting="direction"][value="ltr"]').isChecked(), true);
    assert.equal(await host.getByRole('switch', { name: '双页自动匹配' }).isChecked(), false);
  }
  assert.deepEqual(await value(pc), await value(mobile));
  await shot(page, 'both-paged');
}));

test('默认显示摘要，默认变化不覆盖自定义，切回后恢复且双端隔离', () => session(async (page, pc, mobile) => {
  await pick(pc, 'mode', 'webtoon');
  await pc.getByRole('switch', { name: '页间有空隙' }).check();
  await pick(pc, 'scope', 'default');
  assert.equal(await pc.locator('[data-custom-settings]').isVisible(), false);
  assert.equal(await pc.locator('[data-default-mode]').textContent(), '翻页');
  assert.equal(await pc.locator('[data-default-layout]').textContent(), '自动');
  await page.getByLabel('演示全局默认').selectOption('webtoon');
  assert.equal(await pc.locator('[data-default-mode]').textContent(), '条漫');
  assert.equal(await pc.locator('[data-default-gap-row]').isVisible(), true);
  assert.equal(await pc.locator('[data-default-direction-row]').isVisible(), false);
  assert.equal((await value(mobile)).custom.mode, 'paged');
  await pick(pc, 'scope', 'custom');
  assert.equal(await pc.getByRole('switch', { name: '页间有空隙' }).isChecked(), true);
  await pick(mobile, 'scope', 'default');
  await shot(page, 'custom-and-default');
  const before = await value(pc);
  await page.reload();
  assert.deepEqual(await value(pc), before);
  assert.equal(await page.getByLabel('演示全局默认').inputValue(), 'webtoon');
}));

test('移动宽度、键盘与大字可操作，无裁切，两端焦点不抢占', () => session(async (page, pc, mobile) => {
  await pc.locator('[data-setting="scope"][value="custom"]').focus();
  await page.keyboard.press('ArrowLeft');
  assert.equal((await value(pc)).scope, 'default');
  assert.equal((await value(mobile)).scope, 'custom');
  await mobile.locator('[data-setting="scope"][value="custom"]').focus();
  await page.getByLabel('演示全局默认').evaluate(select => { select.value = 'paged-double'; select.dispatchEvent(new Event('change', { bubbles: true })); });
  assert.equal(await mobile.evaluate(el => el.shadowRoot.activeElement?.value), 'custom');
  await shot(page, 'default-and-custom');
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    await shot(page, 'width-' + width);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    for (const host of [pc, mobile]) assert.deepEqual(await host.evaluate(el => [...el.shadowRoot.querySelectorAll('fieldset, .toggle-row, .inherited, .choice-surface, .direction-surface')].filter(node => node.checkVisibility()).filter(node => {
      const b = node.getBoundingClientRect(); return b.left < 0 || b.right > innerWidth + 1 || node.scrollWidth > node.clientWidth + 1;
    }).map(node => node.className)), [], '控件及文字无内部裁切');
  }
  await page.setViewportSize({ width: 640, height: 1200 });
  for (const host of [pc, mobile]) await host.evaluate(el => {
    for (const child of el.shadowRoot.querySelectorAll('*:not(svg):not(svg *)')) if (child.checkVisibility()) child.dataset.font = String(parseFloat(getComputedStyle(child).fontSize));
    for (const child of el.shadowRoot.querySelectorAll('[data-font]')) child.style.fontSize = Number(child.dataset.font) * 2 + 'px';
  });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await shot(page, 'large-text');
  for (const host of [pc, mobile]) assert.deepEqual(await host.evaluate(el => [...el.shadowRoot.querySelectorAll('legend, .layout-help p, .direction-title')].filter(node => node.checkVisibility()).filter(node => parseFloat(getComputedStyle(node).lineHeight) < parseFloat(getComputedStyle(node).fontSize)).map(node => node.className || node.tagName)), [], '大字号行高不能小于字号');
  for (const host of [pc, mobile]) assert.deepEqual(await host.evaluate(el => [...el.shadowRoot.querySelectorAll('.layout-help, .choice-detail, .direction-title')].filter(node => node.checkVisibility()).filter(node => node.scrollWidth > node.clientWidth + 1).map(node => node.className)), [], '大字说明文字不相互挤压或裁切');
}));
