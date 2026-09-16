const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test('建议安装：真实双端入口、持久折叠、忽略隔离、Android 逐项确认', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    for (const frame of [pc, phone]) {
      await frame.getByTestId('nav-browse').click();
      await frame.getByRole('tab', { name: '插件', exact: true }).click();
      await frame.getByTestId('ext-toggle').waitFor({ timeout: 3000 });
      assert.equal(await frame.getByTestId('ext-toggle').getAttribute('aria-expanded'), 'true');
    }
    await pc.getByTestId('ext-toggle').click();
    assert.equal(await phone.getByTestId('ext-toggle').getAttribute('aria-expanded'), 'true');
    await pc.getByTestId('ext-toggle').click();
    await pc.getByTestId('ext-ignore-aurora').click();
    assert.equal(await pc.getByTestId('ext-row-aurora').count(), 0);
    assert.equal(await phone.getByTestId('ext-row-aurora').count(), 1);
    await pc.getByTestId('ext-undo').click();
    await phone.getByTestId('ext-all').click();
    await phone.getByTestId('ext-start').click();
    await phone.getByTestId('ext-system-cancel').waitFor();
    await phone.getByTestId('ext-system-cancel').click();
    assert.match(await phone.getByTestId('ext-progress').innerText(), /已暂停/);
    assert.equal(await phone.getByTestId('ext-system-install').count(), 0);
    await pc.getByTestId('ext-toggle').click();
    await page.reload();
    for (const frame of [pc, phone]) { await frame.getByTestId('nav-browse').click(); await frame.getByRole('tab', { name: '插件', exact: true }).click(); }
    assert.equal(await pc.getByTestId('ext-toggle').getAttribute('aria-expanded'), 'false');
    assert.equal(await phone.getByTestId('ext-toggle').getAttribute('aria-expanded'), 'true');
  } finally { await browser.close(); }
});

test('建议派生、收藏变化、多源网站、故障重试、安装去重和同步隔离', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
    const pc = page.frameLocator('#preview-windows'); const phone = page.frameLocator('#preview-android');
    async function scene(value, device = 'windows') { await page.selectOption('#trigger-device', device); await page.selectOption('#extension-scene', value); await page.getByTestId('show-extension-scene').click(); }
    await scene('sample');
    assert.equal(await pc.getByTestId('ext-row-archive').count(), 0, '已安装但加载失败不推荐');
    await pc.getByTestId('ext-web-aurora').click();
    await pc.getByRole('dialog', { name: '选择图源网站' }).waitFor();
    await pc.getByTestId('ext-site-9007199254740995').click();
    assert.match(await pc.getByRole('dialog').innerText(), /short.example/);
    await pc.getByTestId('ext-close').click();
    const pcFrame = await page.locator('#preview-windows').elementHandle().then(h => h.contentFrame());
    const before = await pcFrame.evaluate(() => JSON.stringify(window.__mihonSyncDemo.state.devices));
    await scene('incomplete');
    await pc.getByTestId('ext-catalog-retry').click();
    await scene('failure');
    await pc.getByTestId('ext-row-aurora').getByTestId('ext-install-aurora').click();
    await pc.getByTestId('ext-row-aurora').getByText('安装失败，请重试', { exact: true }).waitFor();
    await pc.getByTestId('ext-row-aurora').getByRole('button', { name: '重试', exact: true }).click();
    await pc.getByTestId('ext-row-aurora').waitFor({ state: 'detached' });
    assert.equal(await pc.getByTestId('ext-available-aurora').count(), 0);
    assert.equal(await pcFrame.evaluate(() => JSON.stringify(window.__mihonSyncDemo.state.devices)), before, '安装与忽略不修改同步模型');
    await scene('reset');
    await pcFrame.evaluate(() => { const app = window.__mihonSyncDemo; app.model.localUnfavorite(app.state, app.state.selectedDevice, 'manga-star'); app.render(); });
    assert.equal(await pc.getByTestId('ext-row-aurora').count(), 0, '取消收藏后重算');
    await scene('sample', 'android');
    await phone.getByTestId('ext-all').click(); await phone.getByTestId('ext-start').click();
    await phone.getByTestId('ext-system-cancel').click();
    await phone.getByTestId('ext-resume').click(); await phone.getByTestId('ext-system-install').click();
    await phone.getByTestId('ext-progress').getByText(/本次处理已完成/).waitFor();
    assert.match(await phone.getByTestId('ext-progress').innerText(), /成功 1.*取消 1/s);
    await scene('private', 'android'); await phone.getByTestId('ext-row-aurora').getByTestId('ext-install-aurora').click();
    await phone.getByTestId('ext-row-aurora').waitFor({ state: 'detached' });
    assert.equal(await phone.getByTestId('ext-system-install').count(), 0);
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});

test('单端 320px：搜索焦点、权限暂停、停止后续与忽略持久', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 320, height: 900 } });
    await page.goto('file://' + path.resolve(__dirname, 'device.html').replace(/\\/g, '/'));
    await page.getByTestId('preview-tools').locator('summary').click();
    await page.getByTestId('platform-android').click();
    await page.evaluate(() => window.__mihonSyncDemo.extensions.scene('sample'));
    await page.getByLabel('搜索插件', { exact: true }).pressSequentially('星海');
    assert.equal(await page.getByLabel('搜索插件', { exact: true }).inputValue(), '星海');
    await page.getByLabel('搜索插件', { exact: true }).fill('');
    const bounds = await page.getByTestId('ext-row-aurora').boundingBox(); assert.ok(bounds.width <= 320);
    await page.getByTestId('ext-ignore-aurora').click();
    await page.reload();
    await page.evaluate(() => { const app = window.__mihonSyncDemo; app.state.ui.platform = 'android'; app.state.selectedDevice = 'phone-a'; app.extensions.scene('sample'); });
    assert.equal(await page.getByTestId('ext-row-aurora').count(), 0);
    await page.evaluate(() => window.__mihonSyncDemo.extensions.scene('permission'));
    await page.getByTestId('ext-all').click(); await page.getByTestId('ext-start').click();
    await page.getByTestId('ext-grant').click();
    assert.match(await page.getByTestId('ext-progress').innerText(), /已暂停/);
    await page.getByTestId('ext-resume').click();
    await page.getByTestId('ext-system-install').waitFor();
    await page.getByTestId('ext-system-install').click();
    await page.getByTestId('ext-progress').getByText(/本次处理已完成/).waitFor();
    await page.evaluate(() => { const app = window.__mihonSyncDemo; app.extensions.scene('reset'); app.extensions.scene('sample'); });
    await page.getByTestId('ext-all').click(); await page.getByTestId('ext-start').click();
    await page.getByTestId('ext-system-cancel').click();
    await page.getByTestId('ext-stop').click();
    assert.match(await page.getByTestId('ext-progress').innerText(), /取消 1.*跳过 1/s);
    assert.equal(await page.getByTestId('ext-system-install').count(), 0);
  } finally { await browser.close(); }
});
