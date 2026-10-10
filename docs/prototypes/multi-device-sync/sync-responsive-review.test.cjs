const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

const url = query => 'file://' + path.resolve(__dirname, 'progress-review.html').replace(/\\/g, '/') + (query ? '?' + query : '');
async function ready(page) {
  await page.frameLocator('#preview-android').getByTestId('sync-compact-summary').waitFor({ state: 'attached' });
  await page.frameLocator('#preview-windows').getByTestId('sync-compact-summary').waitFor({ state: 'attached' });
}
async function fitsSingle(page, platform) {
  await page.waitForFunction(platform => {
    const rect = document.getElementById('preview-' + platform).getBoundingClientRect();
    return Math.abs(rect.bottom - (innerHeight - 12)) <= 2;
  }, platform);
  const geometry = await page.evaluate(platform => {
    const root = document.getElementById('preview');
    const frame = document.getElementById('preview-' + platform);
    const rect = frame.getBoundingClientRect();
    const shell = frame.contentDocument.querySelector('[data-testid="app-window"]');
    const shellRect = shell.getBoundingClientRect();
    return {
      pageWidth: document.documentElement.clientWidth,
      pageScroll: document.documentElement.scrollWidth,
      availableWidth: root.clientWidth,
      frameWidth: rect.width, frameHeight: rect.height,
      frameBottom: rect.bottom,
      windowHeight: innerHeight,
      shellWidth: shellRect.width, shellHeight: shellRect.height,
      insideWidth: frame.contentDocument.documentElement.clientWidth,
      insideScroll: frame.contentDocument.documentElement.scrollWidth,
    };
  }, platform);
  assert.ok(geometry.pageScroll <= geometry.pageWidth + 1, JSON.stringify(geometry));
  assert.ok(geometry.frameWidth >= geometry.availableWidth - 1, JSON.stringify(geometry));
  assert.ok(Math.abs(geometry.shellWidth - geometry.frameWidth) <= 1, JSON.stringify(geometry));
  assert.ok(Math.abs(geometry.shellHeight - geometry.frameHeight) <= 1, JSON.stringify(geometry));
  assert.ok(geometry.insideScroll <= geometry.insideWidth + 1, JSON.stringify(geometry));
  assert.ok(geometry.insideWidth <= geometry.frameWidth + 1, JSON.stringify(geometry));
  assert.ok(Math.abs(geometry.frameBottom - (geometry.windowHeight - 12)) <= 2, JSON.stringify(geometry));
  const panel = page.frameLocator('#preview-' + platform).locator('.sync-settings-page, [data-testid="sync-panel"]').first();
  const sizes = await panel.evaluate(el => ({ width: el.clientWidth, scroll: el.scrollWidth }));
  assert.ok(sizes.scroll <= sizes.width + 1, JSON.stringify(sizes));
}
async function toolHotspots(page) {
  const buttons = page.locator('.preview-device-switch button, .preview-tools summary, .preview-tool-panel button, .preview-tool-panel select');
  for (let index = 0; index < await buttons.count(); index++) {
    const button = buttons.nth(index);
    if (!await button.isVisible()) continue;
    const rect = await button.boundingBox();
    assert.ok(rect.height >= 48 && rect.width >= 48, '触控区域不足：' + JSON.stringify(rect));
  }
}

test('完整审阅按窗口默认选端，平板横竖屏、1366px触控和390窄屏单端填满可用空间且无横溢', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    for (const viewport of [{ width: 800, height: 1280 }, { width: 1280, height: 800 }, { width: 390, height: 844 }, { width: 320, height: 844 }, { width: 1366, height: 1024, touch: true }]) {
      const page = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height }, hasTouch: Boolean(viewport.touch) });
      page.setDefaultTimeout(4000);
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(url());
      await ready(page);
      assert.equal(await page.locator('#preview').getAttribute('data-preview-device'), 'android');
      assert.equal(await page.locator('.device-windows').isVisible(), false);
      assert.equal(await page.getByTestId('preview-device-android').getAttribute('aria-pressed'), 'true');
      assert.equal(await page.getByTestId('preview-tools').getAttribute('open'), null);
      await fitsSingle(page, 'android');
      await toolHotspots(page);
      await page.screenshot({ path: path.resolve(__dirname, '../../../.gradle-coordinator/sync-responsive-' + viewport.width + 'x' + viewport.height + '.png') });
      await page.getByTestId('preview-tools').locator('summary').click();
      assert.equal(await page.locator('#android-review-width').isVisible(), false);
      assert.equal(await page.getByLabel('触发设备', { exact: true }).isVisible(), false);
      await toolHotspots(page);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1));
      await page.getByTestId('preview-tools').locator('summary').click();
      await fitsSingle(page, 'android');
      assert.deepEqual(errors, []);
      await page.close();
    }
    const desktop = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    await desktop.goto(url());
    await ready(desktop);
    assert.equal(await desktop.locator('#preview').getAttribute('data-preview-device'), 'both');
    assert.equal(await desktop.getByTestId('preview-tools').getAttribute('open'), '');
    const pc = await desktop.locator('#preview-windows').boundingBox();
    const phone = await desktop.locator('#preview-android').boundingBox();
    assert.equal(pc.y, phone.y);
    assert.ok(pc.x + pc.width <= phone.x);
    await desktop.close();
  } finally { await browser.close(); }
});

test('切换设备与横竖屏只调整外壳，保留输入、滚动、暂停进度及另一端导航', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 800, height: 1280 } });
    page.setDefaultTimeout(4000);
    await page.clock.install();
    await page.goto(url('device=android&progress=compact-paused'));
    await ready(page);
    assert.equal(await page.locator('#preview').getAttribute('data-preview-device'), 'android');
    const phone = page.frameLocator('#preview-android');
    const pc = page.frameLocator('#preview-windows');
    await page.evaluate(() => {
      window.reviewApps = ['windows', 'android'].map(platform => document.getElementById('preview-' + platform).contentWindow.__mihonSyncDemo);
    });
    const paused = await phone.getByTestId('sync-compact-summary').innerText();
    const time = await phone.getByTestId('sync-compact-time').innerText();
    await phone.getByTestId('sync-settings').click();
    await phone.getByTestId('sync-period-360').click();
    const input = phone.getByTestId('sync-device-name');
    await input.fill('我的平板');
    await input.focus();
    await input.evaluate(el => el.setSelectionRange(1, 3));
    const before = await phone.locator('.sheet-settings-content').evaluate(el => el.scrollTop);
    await page.setViewportSize({ width: 1280, height: 800 });
    assert.equal(await page.locator('#preview').getAttribute('data-preview-device'), 'android');
    assert.equal(await input.inputValue(), '我的平板');
    assert.equal(await input.evaluate(el => document.activeElement === el), true);
    assert.deepEqual(await input.evaluate(el => [el.selectionStart, el.selectionEnd]), [1, 3]);
    assert.equal(await phone.locator('.sheet-settings-content').evaluate(el => el.scrollTop), before);
    await fitsSingle(page, 'android');
    await page.getByTestId('preview-device-windows').click();
    await pc.getByTestId('sync-close').click();
    await pc.getByTestId('nav-updates').click();
    await page.getByTestId('preview-device-android').click();
    assert.equal(await input.inputValue(), '我的平板');
    assert.equal(await phone.getByTestId('sync-period-360').getAttribute('aria-checked'), 'true');
    await phone.getByTestId('sync-settings-back').click();
    await page.clock.fastForward(60000);
    assert.equal(await phone.getByTestId('sync-compact-summary').innerText(), paused);
    assert.equal(await phone.getByTestId('sync-compact-time').innerText(), time);
    await page.getByTestId('preview-device-both').click();
    assert.equal(await pc.getByTestId('nav-updates').getAttribute('aria-current'), 'page');
    assert.equal(await phone.getByRole('dialog', { name: '同步', exact: true }).count(), 1);
    assert.equal(await page.evaluate(() => ['windows', 'android'].every((platform, index) => document.getElementById('preview-' + platform).contentWindow.__mihonSyncDemo === window.reviewApps[index])), true);
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.locator('#preview').getAttribute('data-preview-device'), 'both', '用户选择不被旋转覆写');
  } finally { await browser.close(); }
});

test('query优先自动默认，单端场景按钮只触发可见设备且旋转保留用户选择', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 800, height: 1280 } });
    page.setDefaultTimeout(4000);
    await page.goto(url('device=windows'));
    await ready(page);
    assert.equal(await page.locator('#preview').getAttribute('data-preview-device'), 'windows');
    await fitsSingle(page, 'windows');
    await page.setViewportSize({ width: 1440, height: 1100 });
    assert.equal(await page.locator('#preview').getAttribute('data-preview-device'), 'windows');
    const phone = page.frameLocator('#preview-android');
    const pc = page.frameLocator('#preview-windows');
    const phoneSummary = await phone.getByTestId('sync-compact-summary').innerText();
    await page.getByTestId('preview-tools').locator('summary').click();
    await page.selectOption('#progress-scene', 'compact-paused');
    await page.getByTestId('show-progress-both').click();
    assert.match(await pc.getByTestId('sync-compact-summary').innerText(), /暂停/);
    assert.equal(await phone.getByTestId('sync-compact-summary').innerText(), phoneSummary, '隐藏端不被单端进度按钮修改');
    await page.getByTestId('preview-device-android').click();
    await page.selectOption('#progress-scene', 'compact-bidirectional');
    await page.getByTestId('show-progress-scene').click();
    assert.match(await phone.getByTestId('sync-compact-summary').innerText(), /24576/);
    assert.match(await pc.getByTestId('sync-compact-summary').innerText(), /暂停/);
    await page.selectOption('#interaction-scene', 'pending-upload');
    await page.getByTestId('show-interaction-scene').click();
    assert.equal(await phone.getByTestId('manual-sync').count(), 1);
    assert.match(await pc.getByTestId('sync-compact-summary').innerText(), /暂停/);
    await page.getByTestId('preview-tools').locator('summary').click();
    await page.setViewportSize({ width: 1280, height: 800 });
    assert.equal(await page.locator('#preview').getAttribute('data-preview-device'), 'android');
    await fitsSingle(page, 'android');
    await page.goto(url());
    await ready(page);
    await page.setViewportSize({ width: 1440, height: 1100 });
    await page.waitForFunction(() => document.getElementById('preview').dataset.previewDevice === 'both');
    assert.equal(await page.locator('#preview').getAttribute('data-preview-device'), 'both', '尚未选择时自动默认可随窗口变化');
    await page.setViewportSize({ width: 800, height: 1280 });
    await page.waitForFunction(() => document.getElementById('preview').dataset.previewDevice === 'android');
    assert.equal(await page.locator('#preview').getAttribute('data-preview-device'), 'android');
  } finally { await browser.close(); }
});
