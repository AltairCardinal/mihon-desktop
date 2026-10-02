const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const url = 'file://' + path.resolve(__dirname, 'progress-review.html').replace(/\\/g, '/');

test('完整审阅：完成后显示下一次时间，设置即时生效且双端隔离，重开不重置', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.clock.install({ time: new Date(2026, 9, 2, 23, 30, 0) });
    await page.goto(url);
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    await pc.getByTestId('sync-next-auto').waitFor({ timeout: 3000 });
    assert.match(await pc.getByTestId('sync-next-auto').innerText(), /明天 00:30/);
    assert.match(await phone.getByTestId('sync-next-auto').innerText(), /预计/);
    await pc.getByTestId('ix-activity').click();
    await pc.getByTestId('sync-settings-back').click();
    await pc.getByTestId('sync-settings').click();
    await pc.getByTestId('sync-period-0').click();
    await pc.getByTestId('sync-settings-back').click();
    assert.match(await pc.getByTestId('sync-next-auto').innerText(), /自动同步已关闭/);
    const completed = await pc.getByTestId('sync-compact-summary').innerText();
    await page.getByTestId('periodic-sync').click();
    await page.clock.fastForward(1000);
    assert.equal(await pc.getByTestId('sync-compact-summary').innerText(), completed);
    assert.match(await phone.getByTestId('sync-next-auto').innerText(), /明天 00:30/);
    await pc.getByTestId('sync-settings').click();
    await pc.getByTestId('sync-period-15').click();
    await pc.getByTestId('sync-settings-back').click();
    assert.match(await pc.getByTestId('sync-next-auto').innerText(), /今天 23:45/);
    await page.clock.fastForward(60000);
    await pc.getByTestId('sync-close').click();
    await pc.getByTestId('library-sync').click();
    assert.match(await pc.getByTestId('sync-next-auto').innerText(), /今天 23:45/);
    assert.match(await pc.getByTestId('sync-next-auto').innerText(), /14分后/);
    await page.clock.fastForward(14 * 60000);
    assert.match(await pc.getByTestId('sync-next-auto').innerText(), /即将同步/);
    await pc.getByTestId('sync-progress-primary').click();
    assert.match(await pc.getByTestId('sync-compact-summary').innerText(), /同步中/);
    assert.equal(await pc.getByTestId('sync-next-auto').count(), 0);
    await page.clock.fastForward(1000);
    assert.match(await pc.getByTestId('sync-next-auto').innerText(), /今天|明天/);
    await pc.getByTestId('ix-activity').click();
  } finally { await browser.close(); }
});

test('连续过程：同步与暂停隐藏安排，完成才重新计时并恢复完整入口', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.clock.install({ time: new Date(2026, 9, 2, 12, 0, 0) });
    await page.goto(url + '?progress=compact-upload');
    const pc = page.frameLocator('#preview-windows');
    await pc.getByTestId('sync-progress-primary').click();
    const time = await pc.getByTestId('sync-compact-time').innerText();
    await page.clock.fastForward(60000);
    assert.equal(await pc.getByTestId('sync-compact-time').innerText(), time);
    assert.equal(await pc.getByTestId('sync-next-auto').count(), 0);
    await pc.getByTestId('sync-progress-primary').click();
    await page.clock.runFor(21000);
    assert.match(await pc.getByTestId('sync-compact-summary').innerText(), /上次同步已完成/);
    assert.match(await pc.getByTestId('sync-next-auto').innerText(), /今天 13:01/);
    await pc.getByTestId('ix-activity').click();
    await pc.getByTestId('sync-settings-back').click();
    await page.selectOption('#progress-scene', 'compact-failed');
    await page.getByTestId('show-progress-scene').click();
    assert.equal(await pc.getByTestId('sync-next-auto').count(), 0);
    assert.match(await pc.getByTestId('sync-progress-primary').innerText(), /重试/);
  } finally { await browser.close(); }
});


test('完整入口保留首次配置与断网重试，320px大字及主题切换可审阅', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto(url);
    const phone = page.frameLocator('#preview-android');
    await phone.getByTestId('sync-next-auto').waitFor();
    await page.locator('#preview-android').evaluate(el => el.style.width = '320px');
    await phone.locator('body').evaluate(el => {
      const style = document.createElement('style');
      style.textContent = '.sync-compact-card {font-size:28px;}'; el.append(style);
    });
    await page.getByTestId('theme-light').click();
    assert.ok(await phone.getByTestId('sync-progress-card').evaluate(el => el.scrollWidth <= el.clientWidth));
    await page.screenshot({ path: '.gradle-coordinator/sync-schedule-review-light.png', fullPage: true });
    await page.getByTestId('theme-dark').click();
    await page.screenshot({ path: '.gradle-coordinator/sync-schedule-review-dark.png', fullPage: true });
    await page.getByTestId('network-toggle').click();
    assert.match(await phone.getByTestId('sync-next-auto').innerText(), /离线/);
    await phone.getByTestId('sync-progress-primary').click();
    await phone.getByTestId('sync-compact-summary').filter({ hasText: '同步未完成' }).waitFor();
    assert.equal(await phone.getByTestId('sync-next-auto').count(), 0);
    await page.getByTestId('network-toggle').click();
    await phone.getByTestId('sync-progress-primary').click();
    await phone.getByTestId('sync-next-auto').waitFor();
    await page.selectOption('#trigger-device', 'android');
    await page.selectOption('#interaction-scene', 'setup');
    await page.getByTestId('show-interaction-scene').click();
    await phone.getByTestId('ix-setup').click();
    await phone.getByTestId('ix-open-github').waitFor();
    await phone.getByTestId('sync-close').click();
    await phone.getByTestId('library-sync').click();
    await phone.getByTestId('ix-setup').waitFor();
  } finally { await browser.close(); }
});
