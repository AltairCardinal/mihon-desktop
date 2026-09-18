const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

for (const platform of ['windows', 'android']) {
  test(`${platform}：自动同步空间、可选密码与失败恢复`, async () => {
    const browser = await chromium.launch({ channel: 'chrome', headless: true });
    try {
      const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
      page.setDefaultTimeout(5000);
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
      const frame = page.frameLocator('#preview-' + platform);
      const click = name => frame.getByTestId('ix-' + name).click();
      async function scene(value) {
        await page.selectOption('#trigger-device', platform);
        await page.selectOption('#interaction-scene', value);
        await page.getByTestId('show-interaction-scene').click();
      }
      async function authorize(deny = false) {
        const opened = page.waitForEvent('popup');
        await click('open-github');
        const popup = await opened;
        await popup.getByLabel('设备验证码').fill('DEMO-CODE');
        await popup.getByRole('button', { name: '继续', exact: true }).click();
        assert.equal(await popup.locator('select').count(), 0, '授权不选择仓库');
        await popup.getByRole('button', { name: deny ? '取消授权' : '授权 Mihon', exact: true }).click();
        await popup.close();
      }
      async function ready() {
        await frame.getByTestId('manual-sync').waitFor();
        await frame.getByTestId('sync-settings').click();
        assert.doesNotMatch(await frame.getByRole('dialog').innerText(), /恢复资料|恢复密钥/);
      }
      await scene('setup'); await click('setup'); await authorize();
      const password = frame.getByTestId('ix-field-password');
      await password.waitFor();
      assert.match(await frame.getByRole('dialog').innerText(), /将创建同步空间，是否需要设置密码/);
      assert.equal(await frame.getByTestId('ix-password-confirm').innerText(), '不设置密码');
      await password.pressSequentially('abc 123');
      assert.equal(await password.inputValue(), 'abc 123');
      assert.equal(await password.evaluate(el => el === document.activeElement), true);
      assert.equal(await frame.getByTestId('ix-password-confirm').innerText(), '确认密码');
      await password.evaluate(el => el.setSelectionRange(2, 5));
      await click('password-toggle');
      assert.equal(await password.getAttribute('type'), 'text');
      assert.deepEqual(await password.evaluate(el => [el.selectionStart, el.selectionEnd]), [2, 5]);
      assert.equal(await password.evaluate(el => el === document.activeElement), true);
      await password.fill(' ');
      assert.equal(await frame.getByTestId('ix-password-confirm').innerText(), '确认密码');
      await password.fill('');
      assert.equal(await frame.getByTestId('ix-password-confirm').innerText(), '不设置密码');
      await password.fill('discard-me');
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click();
      await click('setup');
      assert.equal(await password.inputValue(), '');
      assert.equal(await password.getAttribute('type'), 'password');
      await click('password-confirm');
      await frame.getByTestId('ix-import-pause').waitFor();
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click();
      await frame.getByTestId('manual-sync').waitFor();
      assert.equal(await frame.locator('[data-ix-screen="imported"]').count(), 0);
      await ready();
      assert.match(await frame.getByRole('dialog').innerText(), /密码保护未开启/);

      await scene('setup'); await click('setup'); await authorize();
      await password.fill('my demo password'); await click('password-confirm'); await ready();
      assert.match(await frame.getByRole('dialog').innerText(), /密码保护已开启/);
      await scene('setup-existing'); await click('setup'); await authorize(); await ready();
      assert.match(await frame.getByRole('dialog').innerText(), /密码保护未开启/);
      await scene('setup-protected'); await click('setup'); await authorize();
      await password.waitFor();
      assert.equal(await frame.getByTestId('ix-password-confirm').isDisabled(), true);
      assert.equal(await frame.getByTestId('ix-password-confirm').innerText(), '连接同步空间');
      await password.fill('wrong'); await click('password-confirm');
      assert.match(await frame.locator('.ix-feedback').innerText(), /密码不正确/);
      await password.fill('mihon-demo'); await click('password-confirm'); await ready();
      assert.match(await frame.getByRole('dialog').innerText(), /密码保护已开启/);

      await scene('auth-expired'); await click('auth-restart'); await authorize(true);
      await click('auth-restart'); await authorize(); await password.waitFor();
      for (const value of ['setup-find-failed', 'setup-create-failed']) {
        await scene(value); await click('setup'); await authorize();
        if (value === 'setup-create-failed') { await password.waitFor(); await click('password-confirm'); }
        await frame.getByTestId('ix-setup-retry').waitFor();
        assert.match(await frame.getByRole('dialog').innerText(), /未覆盖已有数据/);
        await click('setup-retry');
        if (value === 'setup-find-failed') { await password.waitFor(); await click('password-confirm'); }
        await ready();
      }
      assert.deepEqual(errors, []);
    } finally { await browser.close(); }
  });
}
