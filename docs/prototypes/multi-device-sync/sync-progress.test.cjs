const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test('Android：设备待机后恢复时显示总体进度与条目日志', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
    await page.selectOption('#trigger-device', 'android');
    await page.selectOption('#interaction-scene', 'interrupted');
    await page.getByTestId('show-interaction-scene').click();

    const frame = page.frameLocator('#preview-android');
    await frame.getByTestId('sync-import-progress').waitFor();
    assert.match(await frame.getByTestId('sync-recovery-state').innerText(), /设备待机后已暂停/);
    assert.match(await frame.getByTestId('sync-import-count').innerText(), /已处理 24 \/ 60 项/);
    assert.equal(await frame.getByTestId('sync-import-progress').getAttribute('value'), '24');
    assert.equal(await frame.getByTestId('sync-import-progress').getAttribute('max'), '60');
    assert.ok(await frame.getByTestId('sync-item-log').locator('li').count() >= 4);
    assert.match(await frame.getByTestId('sync-item-log').innerText(), /星海骑士|黎明邮局/);

    await frame.getByTestId('ix-import-resume').click();
    assert.match(await frame.getByTestId('sync-recovery-state').innerText(), /正在继续同步/);
    await frame.getByTestId('ix-import-done').waitFor();
  } finally {
    await browser.close();
  }
});
