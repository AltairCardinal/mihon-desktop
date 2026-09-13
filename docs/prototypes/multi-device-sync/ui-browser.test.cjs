const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const playwrightCandidates = [
  process.env.PLAYWRIGHT_CORE_PATH,
  path.join(__dirname, 'node_modules', 'playwright-core'),
  'C:/Users/feeli/AppData/Local/OpenAI/Codex/runtimes/cua_node/a708e72b10c27b59/bin/node_modules/playwright-core',
].filter(Boolean);
const playwrightPath = playwrightCandidates.find((candidate) => fs.existsSync(candidate));
const playwright = playwrightPath ? require(playwrightPath) : null;

const fileUrl = `file://${path.resolve(__dirname, 'index.html').replace(/\\/g, '/')}`;

test('同步设置子页面：双端开关、返回、焦点与异步重绘', { skip: !playwright && '缓存 Playwright 未找到' }, async () => {
  const browser = await playwright.chromium.launch({ headless: true, channel: 'chrome' });
  try {
    for (const platform of ['windows', 'android']) {
      const page = await browser.newPage({ viewport: { width: platform === 'windows' ? 1440 : 320, height: 900 } });
      await page.goto(fileUrl);
      if (platform === 'android') {
        await page.getByTestId('preview-tools').locator('summary').click();
        await page.getByTestId('platform-android').click();
      }
      await page.getByTestId('library-sync').click();
      assert.equal(await page.getByTestId('startup-setting').count(), 0, '设置不再平铺在同步页面');
      await page.getByTestId('sync-settings').click();
      const sheet = page.getByRole('dialog', { name: '同步设置', exact: true });
      await sheet.waitFor({ state: 'visible' });
      await sheet.evaluate(el => Promise.all(el.getAnimations().map(animation => animation.finished)));
      const box = await sheet.boundingBox();
      const app = await page.getByTestId('app-window').boundingBox();
      assert.ok(Math.abs(box.y + box.height - (app.y + app.height - (platform === 'windows' ? 1 : 0))) < 2);
      assert.ok(box.width <= 560 && box.x >= app.x && box.x + box.width <= app.x + app.width);
      assert.equal(await page.locator('.app-body').evaluate(el => el.inert), true);
      await page.getByTestId('startup-setting').click();
      assert.equal(await page.getByTestId('startup-setting').getAttribute('aria-checked'), 'false');
      assert.equal(await page.evaluate(() => document.activeElement.dataset.testid), 'startup-setting');
      await page.getByTestId('periodic-setting').click();
      assert.equal(await page.getByTestId('periodic-setting').getAttribute('aria-checked'), 'false');
      await page.keyboard.press('Tab');
      assert.equal(await page.evaluate(() => document.activeElement.dataset.testid), 'sync-settings-back');
      await page.keyboard.press('Escape');
      assert.equal(await sheet.count(), 0);
      assert.equal(await page.evaluate(() => document.activeElement.dataset.testid), 'sync-settings');
      await page.getByTestId('sync-settings').click();
      assert.equal(await page.getByTestId('startup-setting').getAttribute('aria-checked'), 'false');
      await page.getByTestId('sync-scrim').click({ position: { x: 10, y: 10 } });
      assert.equal(await sheet.count(), 0);
      await page.getByTestId('library-sync').click();
      // A running sync must not dismiss the sheet or revert its device-local switches.
      await page.getByTestId('manual-sync').click();
      await page.getByTestId('sync-settings').click();
      await page.waitForFunction(() => !window.__mihonSyncDemo.state.ui.busy);
      assert.equal(await sheet.count(), 1);
      assert.equal(await page.getByTestId('startup-setting').getAttribute('aria-checked'), 'false');
      const grip = await page.getByTestId('sync-drag').boundingBox();
      await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
      await page.mouse.down();
      await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2 + 85, { steps: 8 });
      await page.mouse.up();
      assert.equal(await sheet.count(), 0);
      await page.getByTestId('preview-tools').locator('summary').click();
      await page.getByTestId(platform === 'windows' ? 'platform-android' : 'platform-windows').click();
      await page.getByTestId('library-sync').click();
      await page.getByTestId('sync-settings').click();
      assert.equal(await page.getByTestId('startup-setting').getAttribute('aria-checked'), 'true');
      assert.equal(await page.getByTestId('periodic-setting').getAttribute('aria-checked'), 'true');
      await page.getByTestId('sync-settings-back').click();
      assert.equal(await sheet.count(), 0);
      await page.close();
    }
  } finally { await browser.close(); }
});

test('浏览器加载真实双端 DOM、几何和漫画详情入口', { skip: !playwright && '缓存 Playwright 未找到' }, async () => {
  const browser = await playwright.chromium.launch({ headless: true, channel: 'chrome' });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const pageErrors = [];
    const externalRequests = [];
    page.on('pageerror', (error) => pageErrors.push(error));
    page.on('request', (request) => { if (/^https?:/i.test(request.url())) externalRequests.push(request.url()); });
    await page.goto(fileUrl);
    await page.locator('[data-testid="preview-tools"] summary').click();

    const windowsBox = await page.locator('.windows-shell').boundingBox();
    assert.equal(Math.round(windowsBox.width), 1024);
    assert.equal(Math.round(windowsBox.height), 768);
    assert.match(await page.locator('.desktop-title').textContent(), /Mihon Desktop 0\.11\.19/);
    assert.equal(await page.locator('.desktop-title img[alt="Mihon Desktop 图标"]').count(), 1);
    const navigationBox = await page.locator('.windows-shell .native-navigation').boundingBox();
    assert.ok(Math.abs(Math.round(navigationBox.y + navigationBox.height) - Math.round(windowsBox.y + windowsBox.height)) <= 1);
    assert.equal(await page.locator('.windows-shell .native-nav-item').count(), 6);
    assert.equal(await page.locator('.windows-shell .native-nav-item [data-icon="updates"]').count(), 1);

    await page.getByTestId('nav-updates').click();
    assert.equal(Math.round((await page.locator('.updates-route .native-appbar').boundingBox()).height), 56);
    const windowsCover = await page.locator('.windows-cover').first().boundingBox();
    assert.equal(Math.round(windowsCover.width), 48);
    assert.equal(Math.round(windowsCover.height), 68);
    const filterStyles = await page.getByTestId('updates-filter').locator('svg').evaluate((node) => {
      const style = getComputedStyle(node);
      return { fill: style.fill, stroke: style.stroke };
    });
    assert.notEqual(filterStyles.stroke, 'none');

    await page.getByTestId('nav-library').click();
    assert.equal(await page.locator('.manga-card').count(), 3);
    await page.getByTestId('manga-card-manga-star').locator('.card-bookmark').click();
    assert.equal(await page.locator('.manga-card').count(), 2);
    await page.getByTestId('manga-card-manga-dawn').locator('.manga-cover').click();
    assert.equal(await page.getByTestId('manga-card-manga-dawn').count(), 0);
    assert.equal(await page.locator('.detail-route').count(), 1);
    assert.equal(Math.round((await page.locator('.detail-cover').boundingBox()).width), 120);
    await page.getByTestId('app-back').click();
    await page.getByTestId('manga-card-manga-dawn').focus();
    await page.keyboard.press('Enter');
    assert.equal(await page.locator('.detail-route').count(), 1);
    await page.getByTestId('continue-reading-fab').click();
    assert.equal(await page.evaluate(() => window.__mihonSyncDemo.state.devices['desktop-b'].readingActive), true);
    await page.getByTestId('app-back').click();
    assert.equal(await page.evaluate(() => window.__mihonSyncDemo.state.devices['desktop-b'].readingActive), false);

    await page.getByTestId('nav-browse').click();
    assert.deepEqual(await page.locator('.browse-tab').allTextContents(), ['图源', '插件']);
    await page.getByTestId('nav-more').click();
    assert.deepEqual(await page.locator('.more-row strong').allTextContents(), ['无痕模式', '下载队列', '迁移', '统计', '设置', '关于']);
    assert.equal(await page.locator('.app-window [data-testid="startup-sync"]').count(), 0);
    assert.equal(await page.locator('.preview-tools [data-testid="startup-sync"]').count(), 1);
    assert.deepEqual(pageErrors, []);
    assert.deepEqual(externalRequests, []);
  } finally {
    await browser.close();
  }
});

test('Android 真实手机视口、五项底栏、状态栏图标和作者入口', { skip: !playwright && '缓存 Playwright 未找到' }, async () => {
  const browser = await playwright.chromium.launch({ headless: true, channel: 'chrome' });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await page.goto(fileUrl);
    await page.getByTestId('preview-tools').locator('summary').click();
    await page.getByTestId('platform-android').click();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    const phoneBox = await page.locator('.android-shell').boundingBox();
    assert.equal(Math.round(phoneBox.width), 390);
    assert.ok(Math.round(phoneBox.height) <= 844);
    const toolBox = await page.getByTestId('preview-tools').locator('summary').boundingBox();
    assert.ok(toolBox.y + toolBox.height <= phoneBox.y);
    assert.equal(await page.locator('.android-shell .native-nav-item').count(), 5);
    const phoneNavigationBox = await page.locator('.android-shell .native-navigation').boundingBox();
    const gestureBox = await page.locator('.android-shell .gesture-area').boundingBox();
    assert.ok(phoneNavigationBox.y + phoneNavigationBox.height <= 844);
    assert.ok(gestureBox.y + gestureBox.height <= 844);
    for (const name of ['wifi', 'signal', 'battery']) {
      assert.equal(await page.locator(`.android-statusbar [data-icon="${name}"]`).count(), 1);
    }
    await page.getByTestId('nav-updates').click();
    assert.equal(Math.round((await page.locator('.updates-route .native-appbar').boundingBox()).height), 64);
    const androidCover = await page.locator('.android-cover').first().boundingBox();
    assert.equal(Math.round(androidCover.width), 44);
    assert.equal(Math.round(androidCover.height), 44);
    assert.equal(Math.round((await page.locator('.android-update-row').first().boundingBox()).height), 56);
    await page.getByTestId('nav-library').click();
    assert.equal(await page.locator('.manga-card').count(), 1);
    await page.getByTestId('manga-card-manga-star').locator('.manga-cover').click();
    assert.equal(await page.locator('.android-shell .gesture-area').count(), 1);
    await page.getByTestId('app-back').click();
    await page.getByTestId('nav-browse').click();
    assert.deepEqual(await page.locator('.browse-tab').allTextContents(), ['图源', '作者', '插件', '迁移']);
    await page.getByTestId('nav-more').click();
    assert.equal(await page.locator('.more-logo-header').count(), 1);
    assert.deepEqual(await page.locator('.more-row strong').allTextContents(), ['已下载', '无痕模式', '下载队列', '分类', '统计', '数据与存储', '设置', '关于', '帮助', '捐赠']);
  } finally {
    await browser.close();
  }
});

test('退出阅读：失焦 Escape、双端切换与远端位置同步', { skip: !playwright && '缓存 Playwright 未找到' }, async () => {
  const browser = await playwright.chromium.launch({ headless: true, channel: 'chrome' });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    await page.goto(fileUrl);
    const state = () => page.evaluate(() => {
      const { devices } = window.__mihonSyncDemo.state;
      return {
        ui: { ...window.__mihonSyncDemo.state.ui },
        desktop: {
          readingActive: devices['desktop-b'].readingActive,
          position: devices['desktop-b'].readPositions['manga-star'],
          mode: devices['desktop-b'].modes.manga['manga-star'],
        },
        phone: {
          readingActive: devices['phone-a'].readingActive,
          position: devices['phone-a'].readPositions['manga-star'],
          mode: devices['phone-a'].modes.manga['manga-star'],
        },
      };
    });
    const switchPlatform = async (testId) => {
      await page.getByTestId('preview-tools').locator('summary').click();
      await page.getByTestId(testId).click();
    };

    await page.getByTestId('nav-library').click();
    await page.getByTestId('manga-card-manga-star').locator('.manga-cover').click();
    await page.getByTestId('continue-reading-fab').click();
    assert.equal((await state()).desktop.readingActive, true);
    await page.locator('select[data-action="reader-mode"]').selectOption({ label: '条漫' });
    await page.evaluate(() => document.activeElement?.blur());
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('.reader-route').count(), 0);
    assert.equal((await state()).desktop.readingActive, false);

    await page.getByTestId('nav-library').click();
    await page.getByTestId('manga-card-manga-star').focus();
    await page.keyboard.press('Enter');
    assert.equal(await page.locator('.detail-route').count(), 1);
    await page.getByTestId('continue-reading-fab').click();
    await page.getByTestId('reader-next').click();
    assert.equal((await state()).desktop.readingActive, true);
    await switchPlatform('platform-android');
    assert.equal((await state()).desktop.readingActive, false);
    assert.equal((await state()).phone.readingActive, false);

    await page.getByTestId('nav-library').click();
    await page.getByTestId('manga-card-manga-star').locator('.manga-cover').click();
    await page.getByTestId('continue-reading-fab').click();
    await page.locator('select[data-action="reader-mode"]').selectOption({ label: '单页' });
    await page.getByTestId('reader-next').click();
    const phoneReading = await state();
    assert.equal(phoneReading.phone.readingActive, true);
    assert.equal(phoneReading.phone.mode, '单页');
    await switchPlatform('platform-windows');
    const afterReverseSwitch = await state();
    assert.equal(afterReverseSwitch.phone.readingActive, false);
    assert.equal(afterReverseSwitch.desktop.readingActive, false);

    await switchPlatform('platform-android');
    await page.getByTestId('library-sync').click();
    await page.getByTestId('manual-sync').click();
    assert.equal(await page.getByTestId('library-sync').getAttribute('data-syncing'), 'true');
    await page.waitForTimeout(550);
    await switchPlatform('platform-windows');
    await page.getByTestId('library-sync').click();
    await page.getByTestId('manual-sync').click();
    await page.waitForTimeout(550);
    const synced = await state();
    assert.ok(synced.desktop.position.page >= 19);
    assert.equal(synced.desktop.readingActive, false);
    assert.equal(synced.phone.readingActive, false);
    assert.notEqual(synced.desktop.mode, synced.phone.mode);
  } finally {
    await browser.close();
  }
});
