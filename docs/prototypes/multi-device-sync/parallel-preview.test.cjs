const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test('并列双端：默认深色和展开，共享操作交换且导航、弹窗各自独立', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/'));
    assert.equal(await page.locator('iframe').count(), 2);
    assert.equal(await page.getByTestId('preview-tools').getAttribute('open'), '');
    assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), 'rgb(17, 18, 22)');
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    await pc.getByTestId('library-sync').waitFor();
    await phone.getByTestId('library-sync').waitFor();
    for (const frame of [pc, phone]) assert.equal(await frame.locator('.theme-dark').count(), 1);
    const a = await page.locator('#preview-windows').boundingBox();
    const b = await page.locator('#preview-android').boundingBox();
    assert.equal(a.y, b.y);
    assert.ok(a.x + a.width <= b.x);
    await pc.getByTestId('library-sync').click();
    await pc.locator('[data-ignore]').click();
    await phone.locator('[data-action="card-favorite"][data-object="manga-star"]').click();
    assert.equal(await pc.getByTestId('library-sync-count').count(), 0, '上传接收前不提前应用');
    await phone.getByTestId('library-sync').click();
    await phone.getByTestId('manual-sync').click();
    await phone.getByTestId('sync-result').waitFor();
    await pc.getByTestId('manual-sync').click();
    await pc.getByTestId('sync-result').waitFor();
    assert.equal(await pc.locator('[data-confirm]').count(), 1, '另一端接收到真实取消操作');
    assert.equal(await phone.getByRole('dialog', { name: '同步', exact: true }).count(), 1);
    await pc.getByTestId('sync-close').click();
    await pc.getByTestId('nav-updates').click();
    assert.equal(await phone.getByRole('dialog', { name: '同步', exact: true }).count(), 1, '导航和弹窗互不干扰');
    await page.getByTestId('theme-light').click();
    for (const frame of [pc, phone]) assert.equal(await frame.locator('.theme-light').count(), 1);
    await page.getByTestId('reset-demo').click();
    for (const frame of [pc, phone]) {
      await frame.getByTestId('library-sync').waitFor();
      assert.equal(await frame.getByRole('dialog').count(), 0);
    }
    assert.equal(await pc.getByTestId('library-sync-count').textContent(), '1');
    assert.equal(await phone.getByTestId('library-sync-count').count(), 0);
    await page.getByTestId('network-toggle').click();
    await phone.getByTestId('library-sync').click();
    await pc.getByTestId('library-sync').click();
    for (const frame of [pc, phone]) assert.match(await frame.locator('.sync-status-copy').textContent(), /离线/);
    await page.getByLabel('触发设备').selectOption('android');
    await page.getByTestId('startup-sync').click();
    assert.equal(await phone.getByTestId('library-sync').getAttribute('data-syncing'), 'true');
    assert.equal(await pc.getByTestId('library-sync').getAttribute('data-syncing'), 'false');
    await phone.getByTestId('sync-result').waitFor();
    assert.match(await phone.getByTestId('sync-result').textContent(), /离线/);
    await page.getByTestId('many-pending').click();
    await phone.locator('.native-confirmation').last().waitFor();
    assert.equal(await phone.locator('.native-confirmation').count(), 120);
    assert.equal(await phone.getByTestId('library-sync-count').textContent(), '99+');
    assert.equal(await pc.getByRole('dialog').count(), 0);
    assert.equal(await page.getByTestId('preview-tools').getAttribute('open'), '');
    await page.setViewportSize({ width: 835, height: 1100 });
    const narrowPC = await page.locator('#preview-windows').boundingBox();
    const narrowPhone = await page.locator('#preview-android').boundingBox();
    assert.equal(narrowPC.y, narrowPhone.y);
    assert.ok(narrowPC.x + narrowPC.width <= narrowPhone.x);
  } finally { await browser.close(); }
});
