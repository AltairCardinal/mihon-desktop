const test = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

const url = pathToFileURL(path.join(__dirname, 'sync-lifecycle-review.html'));

async function review(run) {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.setDefaultTimeout(3000);
    await page.clock.install({ time: new Date('2026-11-19T10:00:00+08:00') });
    await page.clock.pauseAt(new Date('2026-11-19T10:00:01+08:00'));
    await page.goto(url.href + '?device=both');
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    await run(page, pc, phone);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
}

const card = device => device.getByTestId('sync-progress-card');
const title = device => device.getByTestId('sync-progress-title').innerText();
const progress = device => device.getByTestId('sync-progress-confirmed').innerText();
const percent = device => card(device).getByRole('progressbar').evaluate(track =>
  Number(track.getAttribute('aria-valuenow')) / Number(track.getAttribute('aria-valuemax')) * 100);
const click = (device, name) => card(device).getByRole('button', { name, exact: true }).click();
const start = device => device.getByRole('button', { name: /^(开始同步|立即同步)$/ }).click();
const pause = device => click(device, '暂停同步');
const resume = device => click(device, '继续同步');

async function reopen(device) {
  await device.getByTestId('sync-close').click();
  await device.getByTestId('library-sync').click();
}

async function choose(page, name) {
  await page.getByLabel('同步时序场景').selectOption(name);
  await page.getByRole('button', { name: '载入场景', exact: true }).click();
}

test('双端同步面板沿用客户端的结果详情、紧凑运行卡及图标按钮', async () => {
  await review(async (page, pc, phone) => {
    for (const device of [pc, phone]) {
      const surface = card(device);
      const details = surface.getByRole('button', { name: '查看详情' });
      assert.ok(await details.isVisible(), '结果卡保留原生的查看详情入口');
      assert.ok(await device.getByRole('button', { name: '立即同步' }).isVisible());
      assert.ok(await device.getByText('当前没有待确认的操作', { exact: true }).isVisible());
      await details.click();
      await surface.getByRole('button', { name: '收起详情' }).click();
      await start(device);
      await page.clock.runFor(7000);
      assert.match(await progress(device), /总条目已处理\s*\d+\s*\/\s*48\s*条/);
      assert.ok(await surface.getByText(/已用时.*预计剩余/).isVisible());
      const pauseButton = surface.getByRole('button', { name: '暂停同步' });
      assert.ok(await pauseButton.locator('svg').isVisible());
      const [buttonBounds, cardBounds, trackBounds] = await Promise.all([
        pauseButton.boundingBox(), surface.boundingBox(), surface.getByRole('progressbar').boundingBox(),
      ]);
      assert.ok(Math.abs(trackBounds.height - 4) <= 1);
      assert.ok(Math.abs(buttonBounds.width - 168) <= 1 && buttonBounds.height >= 48);
      assert.ok(Math.abs(cardBounds.x + cardBounds.width - buttonBounds.x - buttonBounds.width - 19) <= 2);
    }
  });
});

test('批内逐条处理推进总进度，批中暂停继续保留位置，跨批不重置', async () => {
  await review(async (page, pc) => {
    await start(pc);
    await page.clock.runFor(6500);
    const readCount = async () => Number((await progress(pc)).match(/(\d+)\s*\//)[1]);
    const first = await readCount();
    const firstPercent = await percent(pc);
    assert.ok(first > 0 && first < 12, `第一批处理到 ${first} 条`);
    await page.clock.runFor(500);
    const middle = await readCount();
    assert.ok(middle > first && middle < 12);
    assert.ok(await percent(pc) > firstPercent);
    await pause(pc);
    await page.clock.runFor(3000);
    assert.equal(await readCount(), middle);
    await resume(pc);
    await page.clock.runFor(2300);
    assert.equal(await readCount(), middle);
    await page.clock.runFor(1400);
    assert.ok(await readCount() > 12 && await readCount() < 24);
  });
});

test('Windows 准备时暂停先反馈再停止，空检查点只允许重新开始且另一端无变化', async () => {
  await review(async (page, pc, phone) => {
    assert.equal(await title(pc), '上次同步完成于 12 分钟前');
    await start(pc);
    assert.equal(await title(pc), '正在准备同步');
    assert.equal(await pc.getByTestId('sync-progress-confirmed').count(), 0);
    assert.equal(await card(pc).getByRole('progressbar').getAttribute('aria-valuenow'), null);
    await pause(pc);
    assert.equal(await title(pc), '正在暂停');
    assert.ok(await card(pc).getByRole('button').isDisabled());
    await page.clock.runFor(700);
    assert.equal(await title(pc), '本次同步已停止');
    assert.equal(await card(pc).getByRole('button', { name: '开始同步' }).count(), 1);
    assert.equal(await card(pc).getByRole('button', { name: '继续同步' }).count(), 0);
    await page.clock.runFor(8000);
    assert.equal(await title(pc), '本次同步已停止');
    assert.equal(await title(phone), '上次同步完成于 12 分钟前');
    await start(pc);
    assert.equal(await title(pc), '正在准备同步');
  });
});

test('Android 保存检查点后继续原轮，恢复失败可重试，重开只显示更新后的成功历史', async () => {
  await review(async (page, pc, phone) => {
    await choose(page, 'resume-failure');
    await start(phone);
    await page.clock.runFor(8100);
    assert.match(await title(phone), /正在接收并校验数据|正在获取同步数据/);
    const checkpoint = await progress(phone);
    const checkpointPercent = await percent(phone);
    assert.match(checkpoint, /12\s*\/\s*48/);
    assert.ok(await card(phone).getByText('本次新增确认12条', { exact: true }).isVisible());
    assert.ok(checkpointPercent > 0 && checkpointPercent < 100);
    await pause(phone);
    assert.equal(await title(phone), '正在暂停');
    await page.clock.runFor(700);
    assert.equal(await title(phone), '正在保存进度');
    assert.equal(await progress(phone), checkpoint);
    assert.equal(await percent(phone), checkpointPercent);
    await page.clock.runFor(1700);
    assert.equal(await title(phone), '同步已暂停');
    await reopen(phone);
    assert.equal(await title(phone), '同步已暂停');
    await resume(phone);
    assert.equal(await title(phone), '正在继续同步');
    assert.equal(await progress(phone), checkpoint);
    assert.ok(await card(phone).getByRole('button').isDisabled());
    await page.clock.runFor(2400);
    assert.equal(await title(phone), '继续同步失败');
    assert.equal(await progress(phone), checkpoint);
    await reopen(phone);
    assert.equal(await title(phone), '继续同步失败');
    await click(phone, '重试');
    assert.equal(await title(phone), '正在继续同步');
    await page.clock.runFor(2400);
    assert.match(await title(phone), /正在接收并校验数据|正在获取同步数据/);
    assert.equal(await progress(phone), checkpoint);
    assert.ok(await card(phone).getByText('已保留12条确认结果', { exact: true }).isVisible());
    await page.clock.runFor(6050);
    assert.equal(await title(phone), '正在结束同步');
    await page.clock.runFor(5000);
    assert.equal(await title(phone), '刚刚完成同步');
    assert.equal(await card(phone).getByText('上次同步', { exact: false }).count(), 0);
    await reopen(phone);
    assert.equal(await title(phone), '上次同步刚刚完成');
    assert.equal(await card(phone).getByRole('progressbar').count(), 0);
    await page.clock.runFor(61000);
    assert.equal(await title(phone), '上次同步完成于 1 分钟前');
    await phone.getByRole('button', { name: '同步记录', exact: true }).click();
    assert.equal(await phone.getByTestId('sync-lifecycle-record').first().innerText(), '上次同步完成于 1 分钟前');
    assert.equal(await title(pc), '上次同步完成于 13 分钟前');
  });
});

test('首次同步收尾不能冒充完成，实际完成可赢过暂停，面板关闭时也只更新历史', async () => {
  await review(async (page, pc, phone) => {
    await choose(page, 'first-sync');
    assert.equal(await title(pc), '尚未完成过同步');
    await start(pc);
    await page.clock.runFor(14100);
    assert.equal(await title(pc), '正在结束同步');
    assert.ok(await percent(pc) < 100);
    assert.equal(await card(pc).getByText('刚刚完成同步', { exact: true }).count(), 0);
    await page.clock.runFor(4400);
    await pause(pc);
    assert.equal(await title(pc), '正在暂停');
    await page.clock.runFor(600);
    assert.equal(await title(pc), '刚刚完成同步');
    assert.equal(await card(pc).getByRole('progressbar').count(), 0);
    assert.ok(await card(pc).getByRole('button', { name: '查看详情' }).isVisible());
    await page.clock.runFor(2400);
    assert.equal(await title(pc), '刚刚完成同步');

    await start(phone);
    await phone.getByTestId('sync-close').click();
    await page.clock.runFor(20000);
    await phone.getByTestId('library-sync').click();
    assert.equal(await title(phone), '上次同步刚刚完成');
  });
});

test('收尾暂停若先落地，迟到的完成不能覆盖暂停，也不能写成新历史', async () => {
  await review(async (page, pc) => {
    await start(pc);
    await page.clock.runFor(14100);
    assert.equal(await title(pc), '正在结束同步');
    const beforePause = await percent(pc);
    assert.ok(beforePause > 0 && beforePause < 100);
    await pause(pc);
    await page.clock.runFor(6000);
    assert.equal(await title(pc), '同步已暂停');
    assert.match(await progress(pc), /48\s*\/\s*48/);
    assert.equal(await percent(pc), beforePause);
    assert.equal(await pc.getByRole('button', { name: '同步记录', exact: true }).count(), 0);
    await reopen(pc);
    assert.equal(await title(pc), '同步已暂停');
    assert.equal(await percent(pc), beforePause);
    await resume(pc);
    assert.equal(await percent(pc), beforePause);
    await page.clock.runFor(2400);
    assert.equal(await title(pc), '正在结束同步');
    assert.equal(await percent(pc), beforePause);
    await page.clock.runFor(5000);
    assert.equal(await title(pc), '刚刚完成同步');
  });
});

test('成功卡让出主操作，只有新增待同步操作才突出立即同步', async () => {
  await review(async (page, pc, phone) => {
    for (const device of [pc, phone]) {
      assert.equal(await card(device).getByRole('button', { name: '开始同步' }).count(), 0);
      const manual = device.getByRole('button', { name: '立即同步', exact: true });
      assert.ok(await manual.isVisible());
      assert.ok(await manual.evaluate(el => el.classList.contains('m-button-text')));
    }
    await choose(page, 'pending');
    const primary = card(pc).getByRole('button', { name: '立即同步' });
    assert.ok(await card(pc).getByText('有 3 条操作待同步').isVisible());
    assert.equal(await pc.getByRole('button', { name: '立即同步' }).count(), 1);
    await primary.click();
    await page.clock.runFor(20000);
    assert.equal(await title(pc), '刚刚完成同步');
    assert.equal(await pc.getByRole('button', { name: /立即同步|开始同步/ }).count(), 0);
    assert.ok(await card(pc).getByText(/本次新增确认 48 条/).isVisible());
    await reopen(pc);
    assert.equal(await title(pc), '上次同步刚刚完成');
    assert.equal(await card(pc).getByRole('button', { name: '立即同步' }).count(), 0);
    await pc.getByRole('button', { name: '立即同步' }).click();
    assert.equal(await title(pc), '正在准备同步');
  });
});
