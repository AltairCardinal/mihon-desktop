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

test('横向阅读只有左右方向，两个按钮铺满，自动说明准确', () => session(async (page, pc, mobile) => {
  for (const host of [pc, mobile]) {
    assert.equal(await host.locator('.mode-grid').getByText('横向阅读', { exact: true }).count(), 1);
    assert.equal(await host.getByText('适合传统漫画', { exact: true }).count(), 1);
    assert.equal(await host.getByText('适合条漫', { exact: true }).count(), 1);
    assert.equal(await host.locator('[data-layout-help]').textContent(), '根据阅读区域宽高比自动切换单/双页');
    assert.equal(await host.locator('[data-setting="direction"]').count(), 2);
    for (const layout of ['auto', 'single', 'double']) {
      await pick(host, 'layout', layout);
      const boxes = await host.evaluate(el => {
        const r = el.shadowRoot;
        return [r.querySelector('.direction-grid'), ...r.querySelectorAll('.direction')].map(n => { const b=n.getBoundingClientRect(); return {left:b.left,right:b.right,width:b.width}; });
      });
      assert.equal(boxes[0].left, boxes[1].left);
      assert.equal(boxes[0].right, boxes[2].right);
      assert.ok(Math.abs(boxes[1].width-boxes[2].width)<1);
    }
  }
  await shot(page, 'horizontal');
}));

test('纵向滚动方式互斥，只有连续显示页面有空隙且切换保留值', () => session(async (page, pc, mobile) => {
  for (const host of [pc, mobile]) {
    await pick(host, 'mode', 'webtoon');
    assert.equal(await host.locator('[data-paged-settings]').isVisible(), false);
    assert.equal(await host.locator('[data-setting="scroll"]').count(), 2);
    assert.equal(await host.locator('[data-segments="scroll"] .icon').count(), 2);
    assert.equal(await host.locator('[data-segments="scroll"] use').nth(0).getAttribute('href'), '#i-strip', '连续滚动图标保持不变');
    assert.equal(await host.locator('[data-segments="scroll"] use').nth(1).getAttribute('href'), '#i-gallery-vertical', '逐页采用当前页与上下页边界图标');
    assert.notEqual(await host.locator('[data-segments="scroll"] use').nth(0).getAttribute('href'), await host.locator('[data-segments="scroll"] use').nth(1).getAttribute('href'));
    assert.equal(await host.locator('[data-setting="scroll"][value="continuous"]').isChecked(), true);
    await host.getByRole('switch', { name: '页面有空隙' }).check();
    await pick(host, 'scroll', 'paged');
    assert.equal(await host.getByRole('switch', { name: '页面有空隙' }).isVisible(), false);
    assert.equal((await value(host)).custom.scroll, 'paged');
    await pick(host, 'mode', 'paged');
    await pick(host, 'mode', 'webtoon');
    assert.equal(await host.locator('[data-setting="scroll"][value="paged"]').isChecked(), true);
    await pick(host, 'scroll', 'continuous');
    assert.equal(await host.getByRole('switch', { name: '页面有空隙' }).isChecked(), true);
  }
  await pick(mobile, 'scroll', 'paged');
  await shot(page, 'vertical-scroll-methods');
  await page.setViewportSize({ width: 320, height: 1000 });
  for (const host of [pc, mobile]) {
    for (const icon of await host.locator('[data-segments="scroll"] .icon').all()) {
      const bounds = await icon.boundingBox();
      assert.ok(bounds.width >= 18 && bounds.x >= 0 && bounds.x + bounds.width <= 320);
    }
    await pick(host, 'scroll', 'continuous');
    assert.equal(await host.locator('[data-segments="scroll"] .segment input:checked + span .icon').evaluate(el => getComputedStyle(el).color), 'rgb(175, 199, 255)');
  }
  await shot(page, 'vertical-icons-narrow');
}));

test('默认摘要与旧纵向单页转换正确，恢复及双端状态隔离', () => session(async (page, pc, mobile) => {
  await pc.evaluate(el => { el.value={scope:'custom',custom:{mode:'paged',layout:'single',direction:'vertical',gap:true}}; });
  assert.equal((await value(pc)).custom.mode, 'webtoon');
  assert.equal((await value(pc)).custom.scroll, 'paged');
  assert.equal((await value(mobile)).custom.mode, 'paged');
  await pick(pc, 'scope', 'default');
  await page.getByLabel('演示全局默认').selectOption('vertical-paged');
  assert.equal(await pc.locator('[data-default-mode]').textContent(), '纵向阅读');
  assert.equal(await pc.locator('[data-default-scroll]').textContent(), '逐页翻动');
  assert.equal(await pc.locator('[data-default-gap-row]').isVisible(), false);
  await page.getByLabel('演示全局默认').selectOption('webtoon');
  assert.equal(await pc.locator('[data-default-scroll]').textContent(), '连续滚动');
  assert.equal(await pc.locator('[data-default-gap-row]').isVisible(), true);
  await pick(pc, 'scope', 'custom');
  assert.equal((await value(pc)).custom.scroll, 'paged');
  const before=await value(pc); await page.reload(); assert.deepEqual(await value(pc),before);
  await shot(page, 'restored');
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
