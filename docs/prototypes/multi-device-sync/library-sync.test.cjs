const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const url = 'file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/');

test('书架同步子面板：三态、后台更新、待处理计数及更新入口隔离', async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    for (const platform of ['windows', 'android']) {
      const page = await browser.newPage({ viewport: { width: platform === 'windows' ? 1024 : 320, height: 844 } });
      await page.goto(url);
      if (platform === 'android') {
        await page.getByTestId('preview-tools').locator('summary').click();
        await page.getByTestId('platform-android').click();
      }
      await page.getByTestId('nav-library').click();
      assert.equal(await page.getByTestId('library-sync').count(), 1, '书架顶栏提供同步入口');
      assert.equal(await page.getByTestId('sync-tab').count(), 0);
      assert.equal(await page.getByTestId('nav-updates-sync-badge').count(), 0);
      const initial = platform === 'windows' ? 1 : 0;
      assert.equal(await page.getByTestId('library-sync-count').count(), initial ? 1 : 0);
      await page.getByTestId('library-sync').click();
      const sheet = page.getByRole('dialog', { name: '同步', exact: true });
      assert.equal(await sheet.count(), 1);
      assert.equal(await page.evaluate(() => window.__mihonSyncDemo.state.ui.route), 'library');
      assert.equal(await page.locator('.app-body').evaluate(el => el.inert), true);
      await sheet.evaluate(el => Promise.all(el.getAnimations().map(a => a.finished)));
      const box = await sheet.boundingBox();
      const app = await page.getByTestId('app-window').boundingBox();
      assert.ok(box.width <= 560 && box.y > app.y && box.y + box.height <= app.y + app.height);
      await page.getByTestId('manual-sync').click();
      assert.equal(await page.getByTestId('library-sync').getAttribute('data-syncing'), 'true');
      if (initial) assert.equal(await page.getByTestId('library-sync-count').textContent(), '1');
      await page.getByTestId('sync-close').click();
      assert.equal(await sheet.count(), 0);
      await page.getByTestId('nav-updates').click();
      assert.equal(await page.getByTestId('sync-tab').count(), 0);
      assert.equal(await page.getByTestId('manual-sync').count(), 0);
      await page.waitForFunction(() => !window.__mihonSyncDemo.state.ui.busy);
      assert.equal(await page.locator('.updates-route').count(), 1, '后台完成不抢占页面');
      await page.getByTestId('nav-library').click();
      await page.getByTestId('library-sync').click();
      if (initial) {
        await page.locator('[data-ignore]').first().click();
        assert.equal(await page.getByTestId('library-sync-count').count(), 0);
      }
      await page.keyboard.press('Escape');
      assert.equal(await page.evaluate(() => document.activeElement.dataset.testid), 'library-sync');
      assert.equal(await page.getByTestId('library-sync').getAttribute('data-syncing'), 'false');
      await page.getByTestId('library-sync').click();
      await page.getByTestId('sync-scrim').click({ position: { x: 5, y: 5 } });
      assert.equal(await sheet.count(), 0);
      // Pending uploads and past errors do not create a fourth toolbar state.
      await page.getByTestId('library-sync').click();
      const grip = await page.locator('[data-sheet-drag]').boundingBox();
      await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
      await page.mouse.down();
      await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2 + 85, { steps: 8 });
      await page.mouse.up();
      assert.equal(await sheet.count(), 0);
      await page.getByTestId('nav-updates').click();
      assert.equal(await page.getByTestId('nav-updates-content-badge').textContent(), '2');
      if (platform === 'windows') {
        await page.getByTestId('updates-mark-all').click();
        assert.equal(await page.getByTestId('nav-updates-content-badge').count(), 0);
      } else {
        await page.getByTestId('android-update-manga-star').click();
        await page.getByTestId('continue-reading-fab').click();
        await page.getByTestId('app-back').click();
        await page.getByTestId('app-back').click();
        assert.equal(await page.getByTestId('nav-updates-content-badge').textContent(), '1');
      }
      await page.getByTestId('nav-library').click();
      await page.evaluate(() => {
        const d = window.__mihonSyncDemo; const device = d.model.getDevice(d.state, d.state.selectedDevice);
        d.model.localFavorite(d.state, device.id, 'manga-night');
        device.lastResult = { ok: false, message: '离线' }; d.render();
      });
      assert.equal(await page.getByTestId('library-sync-count').count(), 0);
      assert.equal(await page.getByTestId('library-sync').getAttribute('data-syncing'), 'false');
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.close();
    }
  } finally { await browser.close(); }
});


test('120项待处理：长列表处理、设置同面板返回与滚动位置保留', async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    for (const width of [1024, 320]) {
      const page = await browser.newPage({ viewport: { width, height: 900 } });
      await page.goto(url);
      if (width === 320) {
        await page.getByTestId('preview-tools').locator('summary').click();
        await page.getByTestId('platform-android').click();
      }
      await page.getByTestId('preview-tools').locator('summary').click();
      assert.equal(await page.getByTestId('many-pending').count(), 1, '提供大量待处理演示入口');
      await page.getByTestId('many-pending').click();
      assert.equal(await page.locator('[role="dialog"]').count(), 1);
      assert.equal(await page.locator('.native-confirmation').count(), 120);
      assert.equal(await page.getByTestId('library-sync-count').textContent(), '99+');
      assert.match(await page.getByTestId('sync-pending-summary').textContent(), /120/);
      const frame = await page.getByRole('dialog').boundingBox();
      const scroll = page.locator('.sync-panel-scroll');
      await scroll.evaluate(el => { el.scrollTop = el.scrollHeight / 2; });
      const position = await scroll.evaluate(el => el.scrollTop);
      await page.getByTestId('sync-settings').click();
      assert.equal(await page.locator('[role="dialog"]').count(), 1);
      assert.deepEqual(await page.getByRole('dialog').boundingBox(), frame, '设置沿用原面板尺寸');
      assert.equal(await page.getByTestId('sync-settings-back').count(), 1);
      await page.getByTestId('startup-setting').click();
      await page.getByTestId('sync-settings-back').click();
      assert.ok(Math.abs(await scroll.evaluate(el => el.scrollTop) - position) < 2);
      const target = page.locator('[data-confirm]').nth(65);
      await target.scrollIntoViewIfNeeded();
      const operation = await target.getAttribute('data-confirm');
      const objectId = await page.evaluate(id => window.__mihonSyncDemo.state.devices[window.__mihonSyncDemo.state.selectedDevice].confirmations.find(x => x.id === id).objectId, operation);
      await target.click();
      assert.equal(await page.locator('.native-confirmation').count(), 119);
      assert.equal(await page.evaluate(id => window.__mihonSyncDemo.state.devices[window.__mihonSyncDemo.state.selectedDevice].favorites.includes(id), objectId), false);
      await page.locator('[data-ignore]').last().click();
      assert.equal(await page.locator('.native-confirmation').count(), 118);
      assert.match(await page.getByTestId('sync-pending-summary').textContent(), /118/);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.getByTestId('sync-close').click();
      await page.close();
    }
  } finally { await browser.close(); }
});
