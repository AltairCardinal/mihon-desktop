const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { createCompactDisplay } = require('./sync-progress.js');

test('简洁进度只用已确认条数与同范围总数，传输百分比不冒充完成', () => {
  let now = 0;
  const display = createCompactDisplay(() => now);
  const fact = { state: 'running', direction: '本轮上传', action: '上传', scope: 'first-upload', confirmed: 8000, total: 16000, percent: 100, startedAt: 0, lastProgressAt: 0, wholeEta: 600 };
  assert.equal(display(fact).summary, '同步中，已完成8000/16000条');
  assert.equal(display(fact).completionPercent, 50);
  now = 2000;
  assert.equal(display(fact).time, '已用00:02，剩余估时10:00');
  fact.action = '核对';
  assert.equal(display(fact).summary, '同步中，已完成8000/16000条');
  assert.equal(display(fact).completionPercent, 50);
  fact.state = 'paused';
  assert.equal(display(fact).summary, '已暂停，已完成8000/16000条');
  assert.equal(display(fact).completionPercent, 50);
});

test('上传下载与双向同步从第一帧使用同一整轮总数，切换方向不重置比例', () => {
  const display = createCompactDisplay(() => 0);
  const fact = { state: 'running', direction: '本轮上传', action: '上传', scope: 'whole-run', confirmed: 0, total: 300, startedAt: 0, lastProgressAt: 0 };
  assert.equal(display(fact).summary, '同步中，已完成0/300条');
  for (const direction of ['本轮上传', '本轮下载', '双向同步']) {
    Object.assign(fact, { direction, confirmed: 100 });
    assert.equal(display(fact).summary, '同步中，已完成100/300条');
    assert.equal(display(fact).completionPercent, 100 / 300 * 100);
  }
});

test('未知与无效总数静止不伪造比例，终态时间冻结且失败不填满', () => {
  let now = 0;
  const display = createCompactDisplay(() => now);
  const fact = { state: 'running', direction: '本轮下载', action: '接收', scope: 'download', confirmed: 30, total: null, percent: 80, startedAt: 0, lastProgressAt: 0, stageEta: 4 };
  assert.equal(display(fact).summary, '同步中，已完成30/—条');
  assert.equal(display(fact).completionPercent, null);
  assert.equal(display(fact).time, '已用00:00，剩余估时—');
  fact.total = 20;
  assert.equal(display(fact).completionPercent, null);
  fact.total = 60;
  fact.state = 'failed';
  fact.endedAt = 10000;
  now = 20000;
  assert.equal(display(fact).completionPercent, 50);
  assert.equal(display(fact).time, '已用00:10，剩余估时—');
});

const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const fileUrl = name => 'file://' + path.resolve(__dirname, name).replace(/\\/g, '/');

test('新入口双端两行信息与真实完成比例，无详情无循环动画，暂停恢复互不干扰', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto(fileUrl('progress-review.html') + '?progress=compact-upload');
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    for (const frame of [pc, phone]) {
      await frame.getByTestId('sync-compact-summary').waitFor();
      await frame.getByTestId('sync-progress-primary').click();
      const card = frame.getByTestId('sync-progress-card');
      assert.equal(await card.locator('[data-compact-line]').count(), 2);
      assert.equal(await card.getByTestId('sync-progress-toggle').count(), 0);
      assert.equal(await card.getByTestId('sync-progress-action').count(), 0);
      assert.equal(await card.getByTestId('sync-progress-explanation').count(), 0);
      assert.equal(await frame.getByTestId('sync-panel').locator('.sync-empty, .pending-list, .sync-record-link').count(), 0);
      const values = await card.getByTestId('sync-compact-summary').innerText();
      const [, completed, total] = values.match(/已完成(\d+)\/(\d+)条/);
      const expected = Number(completed) / Number(total) * 100;
      assert.equal(Number(await card.getByTestId('sync-progress-track').getAttribute('aria-valuenow')), expected);
      assert.equal(await card.getByTestId('sync-progress-track').locator('span').evaluate(el => getComputedStyle(el).animationName), 'none');
    }
    const paused = await phone.getByTestId('sync-compact-summary').innerText();
    await pc.getByTestId('sync-progress-primary').click();
    await page.waitForTimeout(2600);
    assert.equal(await phone.getByTestId('sync-compact-summary').innerText(), paused);
    assert.match(await pc.getByTestId('sync-compact-summary').innerText(), /同步中/);
    await phone.getByTestId('sync-close').click();
    await phone.getByTestId('library-sync').click();
    assert.equal(await phone.getByTestId('sync-compact-summary').innerText(), paused);
    await page.selectOption('#progress-scene', 'compact-complete');
    await page.getByTestId('show-progress-both').click();
    assert.equal(await pc.getByTestId('sync-progress-track').getAttribute('aria-valuenow'), '100');
    await pc.getByTestId('sync-progress-primary').click();
    assert.match(await pc.getByTestId('sync-compact-summary').innerText(), /同步中，已完成0\/\d+条/);
    assert.equal(await pc.getByTestId('sync-progress-track').getAttribute('aria-valuenow'), '0');
  } finally { await browser.close(); }
});

test('未知总数静态轨道，320px大字可读，主题切换与设置返回不丢进度', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto(fileUrl('progress-review.html') + '?progress=compact-unknown');
    await page.locator('#preview-android').evaluate(el => el.style.width = '320px');
    const phone = page.frameLocator('#preview-android');
    const track = phone.getByTestId('sync-progress-track');
    await phone.getByTestId('sync-compact-summary').waitFor();
    assert.equal(await track.count(), 0, '实机未知总数不绘制比例轨道');
    await phone.locator('body').evaluate(el => {
      const style = document.createElement('style');
      style.textContent = '.sync-compact-card {font-size:28px;}';
      el.append(style);
    });
    assert.ok(await phone.getByTestId('sync-progress-card').evaluate(el => el.scrollWidth <= el.clientWidth));
    assert.ok(await phone.locator('.android-shell').evaluate(el => el.getBoundingClientRect().right <= innerWidth));
    await page.getByTestId('theme-light').click();
    await phone.getByTestId('sync-settings').click();
    await phone.getByTestId('sync-settings-back').click();
    assert.match(await phone.getByTestId('sync-compact-summary').innerText(), /正在统计数据/);
  } finally { await browser.close(); }
});

test('双向场景第一工作帧已有上传下载合计，安全完成推动固定分母', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto(fileUrl('progress-review.html') + '?progress=compact-bidirectional');
    const frame = page.frameLocator('#preview-windows');
    const summary = frame.getByTestId('sync-compact-summary');
    await summary.waitFor();
    assert.equal(await summary.innerText(), '同步中，已完成0/24576条');
    assert.equal(await frame.getByTestId('sync-progress-track').getAttribute('aria-valuenow'), '0');
    await page.waitForTimeout(1500);
    const [, completed, total] = (await summary.innerText()).match(/已完成(\d+)\/(\d+)条/);
    assert.equal(total, '24576');
    assert.ok(Number(completed) > 0);
    assert.equal(Number(await frame.getByTestId('sync-progress-track').getAttribute('aria-valuenow')), Number(completed) / 24576 * 100);
  } finally { await browser.close(); }
});
