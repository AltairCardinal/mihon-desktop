const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const url = 'file://' + path.resolve(__dirname, 'progress-review.html').replace(/\\/g, '/');

test('源码对齐：历史结果、顶栏操作、普通空态和记录入口，提案只增加调度说明', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    page.setDefaultTimeout(5000);
    await page.clock.install();
    await page.goto(url);
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    for (const frame of [pc, phone]) {
      await frame.getByTestId('sync-native-history').waitFor({ timeout: 3000 });
      assert.equal(await frame.getByTestId('sync-progress-title').innerText(), '上次同步已完成');
      assert.match(await frame.getByTestId('sync-progress-confirmed').innerText(), /上次已确认 14434 条/);
      assert.equal(await frame.getByTestId('sync-progress-action').innerText(), '已确认结果会保留');
      assert.match(await frame.getByTestId('sync-progress-elapsed').innerText(), /上次历时 661:53/);
      const geometry = await frame.getByTestId('sync-native-history').evaluate(el => {
        const b = id => el.querySelector(`[data-testid="${id}"]`).getBoundingClientRect();
        return { actionBeforeCount: b('sync-progress-primary').bottom <= b('sync-progress-confirmed').top,
          detailsAfterTime: b('sync-progress-toggle').top > b('sync-progress-elapsed').bottom,
          radius: getComputedStyle(el.closest('.sync-progress-card')).borderTopLeftRadius };
      });
      assert.ok(geometry.actionBeforeCount && geometry.detailsAfterTime);
      assert.equal(geometry.radius, '12px');
      await frame.getByTestId('sync-progress-toggle').click();
      await frame.getByTestId('sync-progress-details').waitFor();
      await frame.getByTestId('sync-progress-toggle').click();
      await frame.getByTestId('ix-activity').click();
      assert.equal(await frame.locator('#sync-sheet-title').innerText(), '同步记录');
      await frame.getByTestId('sync-settings-back').click();
    }
    assert.equal(await phone.getByTestId('sync-drag').count(), 0);
    await page.selectOption('#sync-review-mode', 'baseline');
    assert.equal(await phone.getByTestId('sync-next-auto').count(), 0);
    assert.equal(await phone.locator('.sync-empty').evaluate(el => getComputedStyle(el).backgroundColor), 'rgba(0, 0, 0, 0)');
    await page.selectOption('#sync-review-mode', 'proposal');
    await phone.getByTestId('sync-next-auto').waitFor();
    await page.screenshot({ path: '.gradle-coordinator/sync-native-review-dark.png', fullPage: true });
    await page.selectOption('#progress-scene', 'compact-unknown');
    await page.getByTestId('show-progress-both').click();
    assert.equal(await phone.getByTestId('sync-compact-summary').innerText(), '正在统计数据');
    assert.equal(await phone.getByTestId('sync-progress-track').count(), 0);
  } finally { await browser.close(); }
});

test('源码设置：内联单选、即时设备名、启动开关和返回，320px与深浅主题', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    page.setDefaultTimeout(5000);
    await page.clock.install();
    await page.goto(url);
    const phone = page.frameLocator('#preview-android');
    await phone.getByTestId('sync-settings').click();
    assert.equal(await phone.locator('[role="radio"]').count(), 5);
    assert.equal(await phone.getByTestId('ix-frequency').count(), 0);
    assert.equal(await phone.getByTestId('sync-period-60').getAttribute('aria-checked'), 'true');
    await phone.getByTestId('sync-period-15').click();
    await phone.getByTestId('sync-device-name').fill('我的手机');
    await phone.getByTestId('startup-setting').click();
    await phone.getByTestId('sync-settings-back').click();
    assert.match(await phone.locator('.sheet-title').innerText(), /我的手机 · 书架/);
    await phone.getByTestId('sync-settings').click();
    assert.equal(await phone.getByTestId('sync-device-name').inputValue(), '我的手机');
    assert.equal(await phone.getByTestId('sync-period-15').getAttribute('aria-checked'), 'true');
    await phone.getByTestId('ix-diagnostics').click();
    await phone.getByTestId('ix-diagnostic-capture').click();
    assert.match(await phone.getByTestId('native-diagnostic-feedback').innerText(), /快照已采集/);
    await phone.getByTestId('ix-diagnostic-session').click();
    assert.match(await phone.getByTestId('native-diagnostic-feedback').innerText(), /诊断会话已开启/);
    assert.match(await phone.getByTestId('native-diagnostic-details').innerText(), /prototype/);
    const [download] = await Promise.all([page.waitForEvent('download'), phone.getByTestId('ix-diagnostic-export').click()]);
    assert.equal(download.suggestedFilename(), 'mihon-sync-demo-diagnostics.json');
    await phone.getByTestId('sync-settings-back').click();
    assert.equal(await phone.locator('[role="radio"]').count(), 5);
    await phone.getByTestId('sync-settings-back').click();
    await page.locator('#preview-android').evaluate(el => el.style.width = '320px');
    await phone.locator('body').evaluate(el => el.classList.add('native-large-text'));
    await page.getByTestId('theme-light').click();
    assert.ok(await phone.getByTestId('sync-native-history').evaluate(el => el.scrollWidth <= el.clientWidth));
    await phone.getByTestId('sync-progress-primary').click();
    await phone.getByTestId('sync-progress-primary').click();
    assert.equal(await phone.getByTestId('sync-native-history').count(), 0);
    await phone.getByTestId('sync-compact-summary').waitFor();
    assert.ok(await phone.getByTestId('sync-compact-summary').evaluate(el => { const s = getComputedStyle(el); return parseFloat(s.lineHeight) >= parseFloat(s.fontSize); }));
    await page.screenshot({ path: '.gradle-coordinator/sync-native-review-light-320.png', fullPage: true });
  } finally { await browser.close(); }
});


test('待确认需二次确认；首次合并暂停和完成遵循同一个实机卡片分支', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    page.setDefaultTimeout(5000);
    await page.clock.install();
    await page.goto(url);
    const pc = page.frameLocator('#preview-windows');
    await pc.locator('[data-ignore]').click();
    await pc.getByRole('alertdialog').waitFor();
    await pc.getByTestId('batch-cancel').click();
    assert.equal(await pc.locator('[data-ignore]').count(), 1);
    await page.selectOption('#interaction-scene', 'user-paused');
    await page.getByTestId('show-interaction-scene').click();
    await pc.getByTestId('sync-compact-summary').waitFor();
    assert.equal(await pc.getByTestId('sync-progress-toggle').count(), 0);
    await pc.getByTestId('sync-progress-primary').click();
    await page.clock.runFor(4000);
    await pc.getByTestId('sync-native-history').waitFor();
    assert.equal(await pc.locator('#sync-sheet-title').innerText(), '同步');
    await page.selectOption('#progress-scene', 'partial');
    await page.getByTestId('show-progress-both').click();
    assert.match(await pc.getByTestId('sync-progress-problems').innerText(), /40/);
    await pc.getByTestId('sync-progress-failures').click();
    assert.match(await pc.getByTestId('sync-failure-log').innerText(), /无法还原/);
  } finally { await browser.close(); }
});
