const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

// Review navigation, visible choices and layout only; this is not a sync-engine test.
for (const platform of ['windows', 'android']) {
  test(`${platform}：同步配置、详情、恢复和进度的交互入口`, async () => {
    const browser = await chromium.launch({ channel: 'chrome', headless: true });
    try {
      const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
      const frame = page.frameLocator('#preview-' + platform);
      await frame.getByTestId('library-sync').waitFor();
      await page.selectOption('#trigger-device', platform);
      async function scene(value) {
        await page.selectOption('#interaction-scene', value);
        await page.getByTestId('show-interaction-scene').click();
      }
      async function click(name) { await frame.getByTestId('ix-' + name).click(); }
      async function fits() {
        assert.equal(await frame.getByRole('dialog').count(), 1, '子页共用一个同步面板');
        assert.ok(await frame.locator('.sync-panel-sheet').evaluate(el => el.scrollWidth <= el.clientWidth + 1), '面板没有横向溢出');
        const content = frame.locator('.ix-page');
        if (await content.count()) assert.ok(await content.evaluate(el => el.scrollWidth <= el.clientWidth + 1), '手机表单与按钮没有横向溢出');
      }

      await scene('setup');
      await click('setup');
      await click('create');
      await frame.getByTestId('ix-field-token').fill('demo-token');
      assert.equal(await frame.getByTestId('ix-field-token').getAttribute('type'), 'password');
      await click('test');
      assert.match(await frame.locator('.ix-feedback').innerText(), /连接成功/);
      await click('connection-next');
      await click('key-next');
      assert.match(await frame.locator('.ix-feedback').innerText(), /先确认/);
      await frame.locator('[data-ix-field="saved"]').check();
      await click('key-next');
      assert.match(await frame.locator('.ix-page').innerText(), /没有的收藏或关注，不代表取消/);
      await fits();
      await click('import-start');
      await click('import-pause');
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click();
      await click('import-view');
      await click('import-resume');
      await frame.getByTestId('ix-import-done').waitFor();
      await click('import-done');
      assert.equal(await frame.locator('[data-item="restore"]').count(), 1);

      await frame.getByTestId('sync-settings').click();
      await click('frequency');
      await frame.locator('[data-minutes="360"]').click();
      assert.match(await frame.getByTestId('ix-frequency').innerText(), /6 小时/);
      await click('recovery');
      assert.doesNotMatch(await frame.locator('.ix-key').innerText(), /DEMO-ONLY/);
      await click('reveal-key');
      assert.match(await frame.locator('.ix-key').innerText(), /DEMO-ONLY/);
      await fits();
      await frame.getByTestId('sync-settings-back').click();
      await click('switch');
      assert.match(await frame.locator('.ix-page').innerText(), /不会直接混合/);
      await click('disconnect-confirm');
      await click('join');
      await frame.getByTestId('ix-field-token').fill('demo-token');
      await click('connection-next');
      await frame.getByTestId('ix-field-key').fill('demo-key');
      await click('key-next');
      assert.equal(await frame.locator('[data-ix-screen="import"]').count(), 1);

      await scene('mixed');
      for (const id of ['position', 'follow', 'read', 'restore', 'page', 'author']) {
        await frame.locator('[data-item="' + id + '"]').click();
        await fits();
        await frame.getByTestId('ix-resolve').first().click();
        assert.equal(await frame.locator('[data-item="' + id + '"]').count(), 0);
      }
      await frame.locator('[data-item="source"]').click();
      await click('match-retry');
      assert.match(await frame.locator('.ix-feedback').innerText(), /来源仍不可用/);
      await click('source');
      await click('source-enable');
      await click('source-return');
      await click('match-retry');
      assert.equal(await frame.locator('[data-item="source"]').count(), 0);

      for (const name of ['network', 'access', 'key', 'empty', 'unknown']) {
        await scene(name);
        await frame.getByTestId('sync-close').click();
        await frame.getByTestId('library-sync').click();
        assert.equal(await frame.getByTestId('sync-result').count(), 0, '旧临时通知不回放');
        await click('issue');
        await fits();
        if (name === 'network' || name === 'unknown') await click('retry');
        if (name === 'access') {
          await click('credentials');
          await frame.getByTestId('ix-field-token').fill('new-demo-token');
          await click('credentials-save');
          assert.equal(await frame.getByTestId('ix-issue').count(), 0);
        }
        if (name === 'key') {
          await click('repair-key');
          await frame.getByTestId('ix-field-key').fill('demo-key');
          await click('key-next');
        }
        if (name === 'empty') { await click('initialize'); assert.equal(await frame.locator('.ix-check').count(), 1); }
      }

      await scene('empty-device');
      assert.match(await frame.locator('.ix-page').innerText(), /空书架不会上传取消操作/);
      await scene('interrupted');
      assert.equal(await frame.getByTestId('ix-import-resume').count(), 1);
      await scene('batch');
      await click('batch-resume');
      await click('batch-stop');
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click();
      assert.match(await frame.getByTestId('ix-batch-progress').innerText(), /部分完成/);
      await click('batch-resume');
      await frame.getByTestId('ix-batch-dismiss').waitFor();
      await fits();
      assert.deepEqual(errors, []);
    } finally { await browser.close(); }
  });
}
