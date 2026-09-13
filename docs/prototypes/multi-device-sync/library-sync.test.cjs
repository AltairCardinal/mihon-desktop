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
