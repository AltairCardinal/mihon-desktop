const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

// Browser checks for the interaction storyboard, not for Git or the sync model.
for (const platform of ['windows', 'android']) {
  test(`${platform}：设备授权、单行状态、自动处理和独立取消确认`, async () => {
    const browser = await chromium.launch({ channel: 'chrome', headless: true });
    try {
      const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
      const frame = page.frameLocator('#preview-' + platform);
      await frame.getByTestId('library-sync').click();
      await frame.getByTestId('sync-settings').click();
      assert.doesNotMatch(await frame.getByRole('dialog').innerText(), /访问令牌/, '用户无需管理令牌');
      await page.selectOption('#trigger-device', platform);
      async function scene(value) {
        await page.selectOption('#interaction-scene', value);
        await page.getByTestId('show-interaction-scene').click();
      }
      const click = name => frame.getByTestId('ix-' + name).click();
      async function fits() {
        assert.equal(await frame.getByRole('dialog').count(), 1);
        assert.ok(await frame.locator('.sync-panel-sheet').evaluate(el => el.scrollWidth <= el.clientWidth + 1));
        if (await frame.locator('.ix-page').count()) assert.ok(await frame.locator('.ix-page').evaluate(el => el.scrollWidth <= el.clientWidth + 1));
      }
      async function authorize(deny = false) {
        const popupPromise = page.waitForEvent('popup');
        await click('open-github');
        const popup = await popupPromise;
        await popup.waitForLoadState();
        assert.match(popup.url(), /github-authorization-demo\.html/);
        await popup.getByLabel('设备验证码').fill('DEMO-CODE');
        await popup.getByRole('button', { name: '继续', exact: true }).click();
        if (deny) await popup.getByRole('button', { name: '取消授权', exact: true }).click();
        else {
          await popup.getByRole('button', { name: '授权 Mihon', exact: true }).click();
        }
        if (deny) await frame.getByTestId('ix-auth-restart').waitFor();
        await popup.close();
      }
      await scene('setup-existing');
      await click('setup');
      assert.equal(await frame.locator('input[type="password"]').count(), 0);
      await authorize();
      await frame.getByTestId('manual-sync').waitFor();
      await fits();
      await scene('import');
      await click('import-start');
      await click('import-pause');
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click();
      await click('import-resume');
      await frame.getByTestId('ix-import-done').waitFor();
      await click('import-done');
      assert.equal(await frame.locator('[data-conflict], [data-item]').count(), 0);
      await scene('pending-upload');
      const pending = await frame.locator('[data-confirm]').count();
      assert.ok(pending > 0);
      assert.equal(await frame.getByTestId('sync-status-row').count(), 1);
      assert.match(await frame.getByTestId('sync-status-row').innerText(), /1小时后同步/);
      assert.match(await frame.getByTestId('sync-status-row').innerText(), /收藏与关注 3 项.*阅读记录 2 项/s);
      await frame.getByTestId('manual-sync').click();
      await frame.getByTestId('sync-result').waitFor();
      assert.equal(await frame.locator('[data-confirm]').count(), pending, '无需先确认取消就能同步');
      assert.equal(await frame.getByTestId('sync-progress-title').innerText(), '同步完成');
      await fits();
      await frame.locator('[data-ignore]').first().click();
      assert.equal(await frame.locator('[data-ignore]').count(), pending - 1);
      await scene('mixed');
      assert.equal(await frame.locator('[data-conflict], [data-item], [data-action="adopt-remote"]').count(), 0);
      await click('activity');
      assert.match(await frame.locator('.ix-page').innerText(), /自动保留关注/);
      assert.match(await frame.locator('.ix-page').innerText(), /来源恢复后自动关联/);
      assert.equal(await frame.locator('[data-action="ix-resolve"]').count(), 0);
      await fits();
      await scene('access');
      await click('issue');
      await click('reconnect');
      await authorize();
      await frame.getByTestId('manual-sync').waitFor();
      assert.equal(await frame.getByTestId('ix-issue').count(), 0);
      await frame.getByTestId('sync-settings').click();
      await click('frequency');
      await frame.locator('[data-minutes="360"]').click();
      assert.match(await frame.getByTestId('ix-frequency').innerText(), /6 小时/);
      assert.match(await frame.getByRole('dialog').innerText(), /同步密码\s*未设置/);
      assert.equal(await frame.getByTestId('ix-recovery').count(), 0);
      await fits();
      await scene('network');
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click();
      assert.equal(await frame.getByTestId('sync-result').count(), 0);
      await click('issue');
      await click('retry');
      await scene('batch');
      await click('batch-resume');
      await click('batch-stop');
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click();
      assert.match(await frame.getByTestId('ix-batch-progress').innerText(), /部分完成/);
      await click('batch-resume');
      await frame.getByTestId('ix-batch-dismiss').waitFor();
      assert.deepEqual(errors, []);
    } finally { await browser.close(); }
  });
}

test('定时同步倒计时：双端格式、时间推进、开关与周期变更', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.addInitScript(() => {
      window.demoNow = Date.now();
      Date.now = () => window.demoNow;
    });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
    for (const platform of ['windows', 'android']) {
      const frame = page.frameLocator('#preview-' + platform);
      await frame.getByTestId('library-sync').click();
      const title = frame.getByTestId('sync-status-row').locator('strong');
      assert.equal(await title.innerText(), '1小时后同步');
      for (const [minutes, expected] of [[1505, '1天1小时5分后同步'], [1440, '1天后同步'], [65, '1小时5分后同步'], [5, '5分后同步'], [0, '即将同步']]) {
        await frame.locator('body').evaluate((_, remaining) => {
          const app = window.__mihonSyncDemo;
          app.state.ui.interactions.nextSyncAt = Date.now() + remaining * 60000;
          app.render();
        }, minutes);
        assert.equal(await title.innerText(), expected);
      }
      await frame.locator('body').evaluate(() => {
        window.__mihonSyncDemo.state.ui.interactions.nextSyncAt = Date.now() + 2 * 60000;
        window.__mihonSyncDemo.render();
        window.demoNow += 60000;
      });
      await frame.locator('[data-sync-countdown]').filter({ hasText: '1分后同步' }).waitFor();
      await frame.getByTestId('sync-settings').click();
      await frame.getByTestId('periodic-setting').click();
      await frame.getByTestId('sync-settings-back').click();
      assert.doesNotMatch(await title.innerText(), /后同步|即将同步/);
      await frame.getByTestId('sync-settings').click();
      await frame.getByTestId('periodic-setting').click();
      await frame.getByTestId('ix-frequency').click();
      await frame.locator('[data-minutes="1440"]').click();
      await frame.getByTestId('sync-settings-back').click();
      assert.equal(await title.innerText(), '1天后同步');
      await frame.getByTestId('manual-sync').click();
      assert.equal(await frame.getByTestId('sync-progress-title').innerText(), '同步中');
      await frame.getByTestId('sync-result').waitFor();
      assert.match(await frame.getByTestId('sync-progress-title').innerText(), /^(同步完成|已是最新)$/);
      assert.equal(await frame.locator('body').evaluate(() => window.__mihonSyncDemo.state.ui.interactions.nextSyncAt - Date.now()), 1440 * 60000);
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click();
      assert.match(await frame.getByTestId('sync-progress-title').innerText(), /^(同步完成|已是最新)$/);
    }
  } finally { await browser.close(); }
});
