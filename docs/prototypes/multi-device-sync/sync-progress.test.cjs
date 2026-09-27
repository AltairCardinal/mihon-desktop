const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test('Android：系统中断返回应用后自动恢复，用户主动暂停才手动继续', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
    await page.selectOption('#trigger-device', 'android');
    await page.selectOption('#interaction-scene', 'interrupted');
    await page.getByTestId('show-interaction-scene').click();

    const frame = page.frameLocator('#preview-android');
    await frame.getByTestId('sync-import-progress').waitFor();
    assert.match(await frame.getByRole('heading', { level: 3 }).innerText(), /正在恢复同步|正在继续同步/);
    assert.match(await frame.getByTestId('sync-import-count').innerText(), /已处理 24 \/ 60 项/);
    assert.equal(await frame.getByTestId('sync-import-progress').getAttribute('value'), '24');
    assert.equal(await frame.getByTestId('sync-import-progress').getAttribute('max'), '60');
    assert.ok(await frame.getByTestId('sync-item-log').locator('li').count() >= 4);
    assert.match(await frame.getByTestId('sync-item-log').innerText(), /星海骑士|黎明邮局/);
    assert.equal(await frame.getByTestId('ix-import-resume').count(), 0, '系统中断恢复不要求点击继续');
    await frame.getByTestId('ix-import-done').waitFor();

    await page.selectOption('#interaction-scene', 'user-paused');
    await page.getByTestId('show-interaction-scene').click();
    assert.match(await frame.getByRole('heading', { level: 3 }).innerText(), /同步已暂停/);
    assert.equal(await frame.getByTestId('ix-import-resume').count(), 1);

    await frame.getByTestId('ix-import-resume').click();
    assert.match(await frame.getByRole('heading', { level: 3 }).innerText(), /正在恢复同步|正在合并数据/);
    await frame.getByTestId('ix-import-done').waitFor();
  } finally {
    await browser.close();
  }
});

test('Android：等待网络、未知总量、重试耗尽和授权阻塞都保留可解释状态', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
    await page.selectOption('#trigger-device', 'android');
    const frame = page.frameLocator('#preview-android');
    async function scene(value) {
      await page.selectOption('#interaction-scene', value);
      await page.getByTestId('show-interaction-scene').click();
    }

    await scene('waiting-network');
    assert.match(await frame.getByRole('heading', { level: 3 }).innerText(), /等待网络连接/);
    assert.match(await frame.getByTestId('sync-import-count').innerText(), /已处理 24 \/ 60 项/);
    await page.getByTestId('network-toggle').click();
    await page.getByTestId('network-toggle').click();
    await frame.getByTestId('ix-import-done').waitFor();

    await scene('unknown-total');
    assert.match(await frame.getByRole('heading', { level: 3 }).innerText(), /正在获取同步数据/);
    assert.match(await frame.getByTestId('sync-import-count').innerText(), /已处理 24 项/);
    assert.equal(await frame.getByTestId('sync-import-progress').getAttribute('value'), null);
    assert.equal(await frame.getByTestId('sync-import-progress').getAttribute('max'), null);

    await scene('retry-exhausted');
    assert.match(await frame.getByRole('heading', { level: 3 }).innerText(), /连接失败，已保留进度/);
    assert.equal(await frame.getByTestId('ix-import-retry').count(), 1);

    await scene('auth-blocked');
    assert.match(await frame.getByRole('dialog').innerText(), /需要重新连接 GitHub/);
  } finally {
    await browser.close();
  }
});

test('双端：同步进度只出现在触发设备，成功收口不留下继续入口', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
    await page.selectOption('#trigger-device', 'android');
    await page.selectOption('#interaction-scene', 'success');
    await page.getByTestId('show-interaction-scene').click();
    const phone = page.frameLocator('#preview-android');
    const desktop = page.frameLocator('#preview-windows');
    assert.match(await phone.getByRole('dialog').innerText(), /同步空间已就绪/);
    assert.equal(await phone.getByTestId('sync-import-progress').count(), 0);
    assert.equal(await desktop.getByTestId('sync-recovery-state').count(), 0);
    await phone.getByTestId('ix-import-done').click();
    assert.equal(await phone.getByRole('dialog').count(), 1, '收口后保留同步面板，用户可自行关闭');
    assert.equal(await phone.getByTestId('ix-import-resume').count(), 0);
  } finally {
    await browser.close();
  }
});
