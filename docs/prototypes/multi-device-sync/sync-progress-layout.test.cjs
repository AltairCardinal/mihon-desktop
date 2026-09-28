const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test('双端共享进度：固定摘要、单轨道、独立详情、暂停恢复与真实入口', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/') + '?progress=continuous');
    const desktop = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    await desktop.getByTestId('sync-progress-card').waitFor({ timeout: 2500 });
    await phone.getByTestId('sync-progress-card').waitFor({ timeout: 2500 });
    assert.equal(await phone.getByTestId('sync-progress-details').isVisible(), false);
    await desktop.getByTestId('sync-progress-toggle').click();
    assert.equal(await phone.getByTestId('sync-progress-details').isVisible(), false);
    await phone.getByTestId('sync-progress-primary').click();
    assert.equal(await phone.getByTestId('sync-progress-title').innerText(), '已暂停');
    assert.equal(await phone.getByTestId('sync-progress-track').getAttribute('data-active'), 'false');
    await phone.getByTestId('sync-progress-primary').click();
    assert.equal(await phone.getByTestId('sync-progress-title').innerText(), '正在核对进度');
    await page.selectOption('#progress-scene', 'local-eta');
    await page.getByTestId('show-progress-scene').click();
    await page.waitForTimeout(2100);
    assert.equal(await desktop.getByTestId('sync-progress-eta').innerText(), '暂无法估算');
    assert.equal(await desktop.getByTestId('sync-progress-card').getByRole('progressbar').count(), 1);
    await desktop.getByTestId('sync-progress-toggle').click();
    assert.match(await desktop.getByTestId('sync-progress-details').innerText(), /当前请求剩余/);
    await page.selectOption('#progress-scene', 'partial');
    await page.getByTestId('show-progress-scene').click();
    assert.equal(await desktop.getByTestId('sync-progress-title').innerText(), '部分完成');
    await desktop.getByTestId('sync-progress-failures').click();
    assert.match(await desktop.getByTestId('sync-failure-log').innerText(), /无法还原/);
  } finally { await browser.close(); }
});


test('高频事实保持节点、按钮坐标、详情滚动和另一端输入焦点；320px及大字可滚动', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').replace(/\\/g, '/') + '?progress=rapid');
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    await pc.getByTestId('sync-progress-toggle').click();
    await phone.getByTestId('sync-settings').click();
    await phone.getByTestId('ix-device').click();
    await phone.getByTestId('ix-field-device').fill('手机审阅中');
    await pc.getByTestId('sync-progress-details').evaluate(el => { el.scrollTop = 140; window.savedDetail = el; window.savedCard = el.closest('section'); window.savedScroll = el.scrollTop; });
    const before = await pc.getByTestId('sync-progress-toggle').boundingBox();
    await page.waitForTimeout(2400);
    const after = await pc.getByTestId('sync-progress-toggle').boundingBox();
    assert.equal(after.y, before.y);
    assert.deepEqual(await pc.getByTestId('sync-progress-details').evaluate(el => [el === window.savedDetail, el.closest('section') === window.savedCard, el.scrollTop === window.savedScroll]), [true, true, true]);
    assert.equal(await phone.getByTestId('ix-field-device').evaluate(el => el === document.activeElement), true);
    assert.equal(await phone.getByTestId('ix-field-device').inputValue(), '手机审阅中');
    const narrow = await browser.newPage({ viewport: { width: 320, height: 900 }, reducedMotion: 'reduce' });
    await narrow.goto('file://' + path.resolve(__dirname, 'device.html').replace(/\\/g, '/') + '?progress=local-eta');
    await narrow.addStyleTag({ content: '.sync-progress-card { font-size: 28px; }' });
    await narrow.getByTestId('sync-progress-toggle').click();
    assert.ok(await narrow.getByTestId('sync-progress-card').evaluate(el => el.scrollWidth <= el.clientWidth), '200%字量无横向溢出');
    assert.equal(await narrow.getByTestId('sync-progress-track').locator('span').evaluate(el => getComputedStyle(el).animationName), 'none');
    await narrow.getByTestId('sync-close').click();
    assert.equal(await narrow.getByRole('dialog').count(), 0);
  } finally { await browser.close(); }
});


test('审查回归：高频暂停继续累计量不回退，Android 320px大字控件完整可达', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    await page.goto('file://' + path.resolve(__dirname, 'index.html').split(String.fromCharCode(92)).join('/') + '?progress=rapid');
    const phone = page.frameLocator('#preview-android');
    await page.waitForTimeout(1400);
    const count = () => phone.getByTestId('sync-progress-confirmed').innerText().then(text => Number(text.replace(/\D/g, '')));
    await phone.getByTestId('sync-progress-primary').click();
    const paused = await count();
    await phone.getByTestId('sync-progress-primary').click();
    await page.waitForTimeout(1300);
    assert.ok(await count() >= paused, '同一运行恢复后累计确认不能回退');
    await page.locator('#preview-android').evaluate(el => el.style.width = '320px');
    await phone.locator('body').evaluate(el => { const style = document.createElement('style'); style.textContent = '.sync-progress-card {font-size:28px;}'; el.append(style); });
    assert.ok(await phone.locator('.android-shell').evaluate(el => el.getBoundingClientRect().right <= innerWidth), '真实Android外壳不得裁切');
    await phone.getByTestId('sync-progress-toggle').click();
    assert.ok(await phone.getByTestId('sync-progress-card').evaluate(el => el.scrollWidth <= el.clientWidth));
    await phone.getByTestId('sync-close').click();
    assert.equal(await phone.getByRole('dialog').count(), 0);
  } finally { await browser.close(); }
});

test('审查回归：真实操作队列分类、纯接收确认与待决定不混计', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1024, height: 1000 } });
    await page.goto('file://' + path.resolve(__dirname, 'device.html').split(String.fromCharCode(92)).join('/') + '?progress=paused');
    await page.evaluate(() => {
      const a = window.__mihonSyncDemo, id = a.state.selectedDevice;
      a.model.localFavorite(a.state, id, 'queue-book');
      a.model.localFollow(a.state, id, 'queue-author');
      a.model.localRead(a.state, id, 'manga-star', 'c1', 3);
      a.render();
    });
    await page.getByTestId('sync-progress-toggle').click();
    assert.match(await page.getByTestId('sync-progress-details').innerText(), /1 条收藏 · 1 条关注/);
    assert.match(await page.getByTestId('sync-progress-details').innerText(), /1 条阅读记录/);
    await page.goto('file://' + path.resolve(__dirname, 'device.html').split(String.fromCharCode(92)).join('/'));
    await page.evaluate(() => {
      const a = window.__mihonSyncDemo;
      a.model.localFavorite(a.state, 'phone-a', 'new-received-book');
      a.model.syncDevice(a.state, 'phone-a', 'manual');
    });
    await page.getByTestId('library-sync').click();
    await page.getByTestId('manual-sync').click();
    await page.waitForFunction(() => !window.__mihonSyncDemo.state.ui.busy);
    const result = await page.evaluate(() => window.__mihonSyncDemo.state.devices['desktop-b'].lastResult);
    assert.equal(result.sent, 0);
    assert.ok(result.applied > 0);
    assert.equal(await page.getByTestId('sync-progress-confirmed').innerText(), `本次已确认 ${result.applied} 条`);
    assert.ok(await page.locator('[data-confirm]').count() > 0, '取消仍在独立待决定列表');
    await page.evaluate(() => window.__mihonSyncDemo.showInteractionScenario('progress-partial'));
    await page.getByTestId('sync-progress-failures').click();
    assert.doesNotMatch(await page.getByTestId('sync-failure-log').innerText(), /漫画源暂不可用/);
  } finally { await browser.close(); }
});
