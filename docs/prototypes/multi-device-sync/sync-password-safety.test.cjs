const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test('密码安全视觉：双端深浅禁用反馈与实心警告图标', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/') + '?review=password');
    const failures = [];
    for (const platform of ['windows', 'android']) {
      const frame = page.frameLocator('#preview-' + platform);
      await frame.getByTestId('ix-password-enable').click();
      await frame.getByTestId('ix-field-password').fill('demo-only');
      const submit = frame.getByTestId('ix-password-confirm');
      const acknowledge = frame.getByTestId('ix-password-acknowledge');
      const style = () => submit.evaluate(el => {
        const css = getComputedStyle(el);
        return { background: css.backgroundColor, color: css.color, cursor: css.cursor, filter: css.filter };
      });
      for (const theme of ['light', 'dark']) {
        await page.getByTestId('theme-' + theme).click();
        await acknowledge.check();
        assert.equal(await submit.isDisabled(), false);
        const enabled = await style();
        await acknowledge.uncheck();
        assert.equal(await submit.isDisabled(), true);
        await submit.hover();
        const disabled = await style();
        const label = `${platform}/${theme}`;
        if (enabled.background === disabled.background) failures.push(label + ' 禁用背景应区别于可用主按钮');
        if (enabled.color === disabled.color) failures.push(label + ' 禁用文字应使用语义弱化颜色');
        if (disabled.cursor !== 'default') failures.push(label + ' 禁用鼠标应为 default');
        if (disabled.filter !== 'none') failures.push(label + ' 禁用 hover 不应保留主按钮反馈');
        const icon = await frame.getByTestId('ix-password-warning').locator('svg').evaluate(el => {
          const css = getComputedStyle(el);
          return { fill: css.fill, stroke: css.stroke, color: css.color };
        });
        if (icon.fill !== icon.color || icon.stroke !== 'none') failures.push(label + ' Material 警告应实心填充且无描边');
      }
    }
    assert.deepEqual(failures, []);
  } finally { await browser.close(); }
});

for (const platform of ['windows', 'android']) {
  test(`${platform}：可选密码风险确认、草稿隔离和帮助返回`, async () => {
    const browser = await chromium.launch({ channel: 'chrome', headless: true });
    try {
      const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
      page.setDefaultTimeout(5000);
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto('file://' + path.resolve(__dirname, platform === 'windows' ? 'password-review.html' : 'index.html').replace(/\\/g, '/') + (platform === 'windows' ? '' : '?review=password'));
      const frame = page.frameLocator('#preview-' + platform);
      const other = page.frameLocator('#preview-' + (platform === 'windows' ? 'android' : 'windows'));
      const click = name => frame.getByTestId('ix-' + name).click();
      const password = frame.getByTestId('ix-field-password');
      const toggle = frame.getByTestId('ix-password-enable');
      const acknowledge = frame.getByTestId('ix-password-acknowledge');
      const submit = frame.getByTestId('ix-password-confirm');
      await toggle.waitFor();
      assert.equal(await frame.locator('#sync-sheet-title').innerText(), '创建同步空间');
      assert.equal(await toggle.getAttribute('aria-checked'), 'false');
      assert.equal(await password.count(), 0);
      assert.equal(await submit.innerText(), '创建并开启同步');
      assert.match(await frame.getByRole('dialog').innerText(), /GitHub 私有空间/);
      await click('password-enable');
      assert.equal(await submit.isDisabled(), true);
      const warning = await frame.getByTestId('ix-password-warning').innerText();
      for (const text of ['不同于 GitHub', '无法恢复远端加密数据', '重新登录', '本地现有内容', '找回或重置']) assert.ok(warning.includes(text), text);
      assert.equal(await other.getByTestId('ix-password-enable').getAttribute('aria-checked'), 'false');
      await password.fill('  demo secret  ');
      assert.equal(await submit.isDisabled(), true);
      // Even a synthetic click cannot bypass the handler's confirmation guard.
      await submit.evaluate(el => el.dispatchEvent(new MouseEvent('click', { bubbles: true })));
      assert.equal(await frame.locator('[data-ix-screen="password"]').count(), 1);
      await acknowledge.check();
      assert.equal(await submit.isDisabled(), false);
      await password.fill(' ');
      assert.equal(await acknowledge.isChecked(), false);
      await acknowledge.check();
      assert.equal(await submit.isDisabled(), false, '非空密码不做 trim');
      await password.fill('  demo secret  ');
      await acknowledge.check();
      await password.focus();
      await password.evaluate(el => { el.setSelectionRange(2, 6); window.__passwordNode = el; });
      await page.getByTestId('theme-light').click();
      assert.equal(await password.evaluate(el => el === window.__passwordNode), true);
      assert.equal(await password.inputValue(), '  demo secret  ');
      assert.equal(await acknowledge.isChecked(), true);
      assert.equal(await page.getByTestId('theme-light').evaluate(el => el === document.activeElement), true);
      await other.getByTestId('ix-password-enable').click();
      assert.equal(await password.inputValue(), '  demo secret  ');
      assert.equal(await password.evaluate(el => el === window.__passwordNode), true);
      assert.equal(await other.getByTestId('ix-password-enable').evaluate(el => el === document.activeElement), true);
      await click('password-toggle');
      assert.equal(await password.getAttribute('type'), 'text');
      assert.equal(await password.evaluate(el => el === window.__passwordNode), true, '显隐保留输入节点');
      await click('password-enable');
      assert.equal(await password.count(), 0);
      await click('password-enable');
      assert.equal(await password.inputValue(), '');
      assert.equal(await password.getAttribute('type'), 'password');
      assert.equal(await acknowledge.isChecked(), false);
      await password.fill('discard'); await acknowledge.check();
      await frame.getByTestId('sync-settings-back').click();
      await click('setup');
      assert.equal(await toggle.getAttribute('aria-checked'), 'false');
      await click('password-enable'); await password.fill('discard'); await acknowledge.check();
      await frame.getByTestId('sync-close').click();
      await frame.getByTestId('library-sync').click(); await click('setup');
      assert.equal(await toggle.getAttribute('aria-checked'), 'false');
      await click('password-confirm');
      await frame.getByTestId('manual-sync').waitFor();
      await frame.getByTestId('sync-settings').click();
      assert.match(await frame.getByRole('dialog').innerText(), /同步密码\s*未设置/);

      await page.selectOption('#trigger-device', platform);
      await page.selectOption('#interaction-scene', 'setup-protected');
      await page.getByTestId('show-interaction-scene').click(); await click('setup');
      const opened = page.waitForEvent('popup'); await click('open-github');
      const popup = await opened;
      await popup.getByLabel('设备验证码').fill('DEMO-CODE');
      await popup.getByRole('button', { name: '继续', exact: true }).click();
      await popup.getByRole('button', { name: '授权 Mihon', exact: true }).click(); await popup.close();
      await password.waitFor();
      assert.equal(await toggle.count(), 0);
      await password.fill('wrong'); await click('password-confirm');
      assert.match(await frame.locator('.ix-feedback').innerText(), /密码不正确/);
      await click('password-help');
      assert.match(await frame.getByRole('dialog').innerText(), /密码管理器/);
      assert.match(await frame.getByRole('dialog').innerText(), /保留仍能同步的设备/);
      await frame.getByTestId('sync-settings-back').press('Escape');
      assert.equal(await password.inputValue(), '');
      assert.equal(await frame.getByTestId('ix-password-help').evaluate(el => el === document.activeElement), true);
      await click('password-help'); await click('back');
      assert.equal(await password.inputValue(), '');
      await password.fill('mihon-demo'); await click('password-confirm');
      await frame.getByTestId('manual-sync').waitFor();
      await frame.getByTestId('sync-settings').click();
      assert.match(await frame.getByRole('dialog').innerText(), /同步密码\s*已设置/);
      await click('password-help'); await click('back');
      assert.equal(await frame.locator('#sync-sheet-title').innerText(), '同步设置');
      assert.equal(await frame.getByTestId('ix-password-help').evaluate(el => el === document.activeElement), true);
      assert.deepEqual(errors, []);
    } finally { await browser.close(); }
  });
}
