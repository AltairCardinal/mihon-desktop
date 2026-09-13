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
      async function authorize(space = 'existing', deny = false) {
        const popupPromise = page.waitForEvent('popup');
        await click('open-github');
        const popup = await popupPromise;
        await popup.waitForLoadState();
        assert.match(popup.url(), /github-authorization-demo\.html/);
        await popup.getByLabel('设备验证码').fill('DEMO-CODE');
        await popup.getByRole('button', { name: '继续', exact: true }).click();
        if (deny) await popup.getByRole('button', { name: '取消授权', exact: true }).click();
        else {
          if (await popup.getByLabel('同步使用的私有仓库').isEnabled()) await popup.getByLabel('同步使用的私有仓库').selectOption(space);
          await popup.getByRole('button', { name: '授权 Mihon', exact: true }).click();
        }
        await frame.getByTestId(deny ? 'ix-auth-restart' : 'ix-auth-continue').waitFor();
        await popup.close();
      }
      await scene('setup');
      await click('setup');
      assert.equal(await frame.locator('input[type="password"]').count(), 0);
      await authorize('empty');
      await click('auth-continue');
      await click('key-next');
      assert.match(await frame.locator('.ix-feedback').innerText(), /先确认/);
      await frame.locator('[data-ix-field="saved"]').check();
      await click('key-next');
      await fits();
      await click('import-start');
      await click('import-pause');
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click();
      await click('import-view');
      await click('import-resume');
      await frame.getByTestId('ix-import-done').waitFor();
      await click('import-done');
      assert.equal(await frame.locator('[data-conflict], [data-item]').count(), 0);
      await scene('auth-expired');
      await click('auth-restart');
      await authorize('existing', true);
      await frame.getByTestId('ix-auth-restart').waitFor();
      await click('auth-restart');
      await authorize('existing');
      await click('auth-continue');
      await frame.getByTestId('ix-field-key').fill('demo-recovery');
      await click('key-next');
      await fits();
      await scene('pending-upload');
      const pending = await frame.locator('[data-confirm]').count();
      assert.ok(pending > 0);
      assert.equal(await frame.getByTestId('sync-status-row').count(), 1);
      assert.match(await frame.getByTestId('sync-status-row').innerText(), /5 项变动/);
      assert.match(await frame.getByTestId('sync-status-row').innerText(), /收藏与关注 3 项.*阅读记录 2 项/s);
      await frame.getByTestId('manual-sync').click();
      await frame.getByTestId('sync-result').waitFor();
      assert.equal(await frame.locator('[data-confirm]').count(), pending, '无需先确认取消就能同步');
      assert.match(await frame.getByTestId('sync-status-row').innerText(), /数据交换已完成/);
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
      await click('auth-continue');
      assert.equal(await frame.getByTestId('ix-issue').count(), 0);
      await frame.getByTestId('sync-settings').click();
      await click('frequency');
      await frame.locator('[data-minutes="360"]').click();
      assert.match(await frame.getByTestId('ix-frequency').innerText(), /6 小时/);
      await click('recovery');
      await click('reveal-key');
      assert.match(await frame.locator('.ix-key').innerText(), /DEMO-ONLY/);
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
