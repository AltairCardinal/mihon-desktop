const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const url = scene => 'file://' + path.resolve(__dirname, 'progress-review.html').replace(/\\/g, '/') + '?progress=' + scene;
async function open(scene, run) {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    page.setDefaultTimeout(5000);
    await page.goto(url(scene));
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    await phone.getByTestId('sync-compact-summary').waitFor();
    await run(page, pc, phone);
  } finally { await browser.close(); }
}
test('现代完成摘要、独立调度、详情与设置分组保留真实双端隔离和源码对照', async () => {
  await open('compact-complete', async (page, pc, phone) => {
    for (const frame of [pc, phone]) {
      assert.equal(await frame.getByTestId('app-window').getAttribute('data-sync-design'), 'modern');
      assert.equal(await frame.getByTestId('sync-compact-summary').innerText(), '同步已完成');
      assert.equal(await frame.getByTestId('sync-progress-track').count(), 0);
      assert.equal(await frame.getByTestId('sync-progress-eta').count(), 0);
      assert.match(await frame.getByTestId('sync-progress-confirmed').innerText(), /已完成14434条/);
      assert.equal(await frame.getByTestId('sync-next-auto').count(), 1);
      await frame.getByTestId('sync-progress-toggle').click();
      assert.equal(await frame.getByTestId('sync-progress-details').isVisible(), true);
      await frame.getByTestId('sync-settings').click();
      for (const heading of ['自动同步', '账号与设备', '更多']) assert.equal(await frame.getByRole('heading', { name: heading, exact: true }).count(), 1);
    }
    await phone.getByTestId('sync-period-360').click();
    assert.equal(await phone.getByTestId('sync-period-360').getAttribute('aria-checked'), 'true');
    assert.equal(await pc.getByTestId('sync-period-360').getAttribute('aria-checked'), 'false');
    await phone.getByTestId('sync-settings-back').click();
    assert.match(await phone.getByTestId('sync-next-auto').innerText(), /6小时/);
    await page.selectOption('#sync-review-mode', 'baseline');
    assert.equal(await phone.getByTestId('app-window').getAttribute('data-sync-design'), 'source');
    assert.equal(await phone.getByTestId('sync-progress-track').count(), 1);
  });
});
test('现代运行只保留两条信息和比例，暂停60秒冻结，失败与部分完成可恢复和查看日志', async () => {
  await open('compact-upload', async (page, pc, phone) => {
    await page.clock.install();
    await phone.getByTestId('sync-progress-primary').click();
    const summary = await phone.getByTestId('sync-compact-summary').innerText();
    const time = await phone.getByTestId('sync-compact-time').innerText();
    assert.match(summary, /已暂停/);
    await page.clock.fastForward(60000);
    assert.equal(await phone.getByTestId('sync-compact-summary').innerText(), summary);
    assert.equal(await phone.getByTestId('sync-compact-time').innerText(), time);
    assert.equal(await phone.locator('[data-compact-line]').count(), 2);
    assert.equal(await phone.getByTestId('ix-activity').count(), 0);
    await phone.getByTestId('sync-progress-primary').click();
    assert.match(await phone.getByTestId('sync-compact-summary').innerText(), /同步中/);
    for (const scene of ['compact-failed', 'partial']) {
      await page.selectOption('#progress-scene', scene);
      await page.getByTestId('show-progress-both').click();
      assert.equal(await phone.getByTestId('app-window').getAttribute('data-sync-design'), 'modern');
      assert.doesNotMatch(await phone.getByTestId('sync-compact-summary').innerText(), /^同步已完成$/);
      assert.match(await phone.getByTestId('sync-progress-primary').innerText(), /重试/);
      if (scene === 'partial') {
        await phone.getByTestId('sync-progress-failures').click();
        assert.equal(await phone.getByTestId('sync-failure-log').isVisible(), true);
      }
    }
  });
});
test('现代双端深浅与320、390、560大字不横裁且操作热区可达', async () => {
  await open('compact-complete', async (page, pc, phone) => {
    for (const width of [320, 390, 560]) {
      await page.selectOption('#android-review-width', String(width));
      for (const theme of ['dark', 'light']) {
        await page.getByTestId('theme-' + theme).click();
        await phone.locator('body').evaluate(el => el.classList.add('native-large-text'));
        assert.equal(await phone.getByTestId('app-window').getAttribute('data-sync-design'), 'modern');
        const sizes = await phone.getByTestId('sync-panel').evaluate(el => ({ width: el.clientWidth, scroll: el.scrollWidth }));
        assert.ok(sizes.scroll <= sizes.width + 1, JSON.stringify(sizes));
        const button = await phone.getByTestId('sync-progress-primary').boundingBox();
        assert.ok(button.height >= 48);
        await page.locator('#preview-android').screenshot({ path: path.resolve(__dirname, '../../../.gradle-coordinator/sync-modern-' + width + '-' + theme + '.png') });
      }
    }
  });
});
test('普通待同步也折叠详情，诊断技术信息渐进展开，设备名独立且开关轨道不占满热区', async () => {
  await open('compact-complete', async (page, pc, phone) => {
    await page.selectOption('#trigger-device', 'android');
    await page.selectOption('#interaction-scene', 'pending-upload');
    await page.getByTestId('show-interaction-scene').click();
    await phone.getByTestId('manual-sync').waitFor();
    assert.equal(await phone.getByTestId('sync-queue-summary').isVisible(), false);
    await phone.getByTestId('sync-progress-toggle').click();
    assert.equal(await phone.getByTestId('sync-queue-summary').isVisible(), true);
    await phone.getByTestId('batch-select').click();
    assert.ok((await phone.locator('[data-select-id]').first().boundingBox()).height >= 48);
    await phone.getByTestId('selection-cancel').click();
    await phone.getByTestId('sync-settings').click();
    const field = await phone.locator('.native-outlined-field').boundingBox();
    const group = await phone.locator('.modern-group').filter({ hasText: '账号与设备' }).boundingBox();
    const reconnect = await phone.getByTestId('ix-reconnect').boundingBox();
    assert.ok(field.y >= reconnect.y + reconnect.height);
    assert.ok(field.x + field.width <= group.x + group.width);
    assert.ok((await phone.getByTestId('startup-setting').boundingBox()).height >= 48);
    assert.equal(await phone.getByTestId('startup-setting').evaluate(el => getComputedStyle(el, '::before').height), '32px');
    assert.equal(await phone.getByRole('heading', { name: '定期同步' }).isVisible(), true);
    await page.locator('#preview-android').screenshot({ path: path.resolve(__dirname, '../../../.gradle-coordinator/sync-modern-settings.png') });
    assert.equal(await phone.getByTestId('startup-setting').evaluate(el => getComputedStyle(el).borderTopWidth), '0px');
    await phone.getByTestId('ix-diagnostics').click();
    assert.equal(await phone.locator('details.modern-diagnostic').count(), 1);
    await phone.getByTestId('ix-diagnostic-capture').click();
    assert.equal(await phone.getByTestId('native-diagnostic-details').isVisible(), false);
    await phone.locator('details.modern-diagnostic summary').click();
    assert.equal(await phone.getByTestId('native-diagnostic-details').isVisible(), true);
    await phone.getByTestId('ix-diagnostic-session').click();
    assert.equal(await phone.getByTestId('ix-diagnostic-export').isVisible(), true);
    await phone.getByTestId('ix-diagnostic-export').click();
    assert.equal(await phone.getByTestId('native-diagnostic-details').isVisible(), true);
    await page.getByTestId('theme-light').click();
    assert.equal(await phone.getByTestId('native-diagnostic-details').isVisible(), true);
    await phone.getByTestId('sync-settings-back').focus();
    await page.keyboard.press('Escape');
    assert.equal(await phone.getByTestId('sync-period-360').count(), 1);
  });
});
