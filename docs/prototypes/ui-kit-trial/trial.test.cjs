const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function open(browser, file = 'index.html', viewport = { width: 1440, height: 1000 }) {
  const page = await browser.newPage({ viewport });
  page.setDefaultTimeout(3000);
  await page.goto('file://' + path.resolve(__dirname, file).replace(/\\/g, '/'));
  return page;
}

test('书架搜索和筛选：无结果可恢复，关闭搜索不丢页面', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser);
    for (const platform of ['windows', 'android']) {
      const frame = page.frameLocator(`#preview-${platform}`);
      await frame.getByTestId('search-open').click();
      const input = frame.getByTestId('library-query');
      await input.fill('不存在的漫画');
      assert.equal(await frame.getByTestId('library-no-results').count(), 1);
      await frame.getByTestId('search-clear').click();
      assert.equal(await input.count(), 1);
      assert.ok(await frame.getByTestId('manga-card').count() > 0);
      await frame.getByTestId('search-close').click();
      assert.equal(await input.count(), 0);
      assert.equal(await frame.getByTestId('library-title').textContent(), '书架');
      await frame.getByTestId('filter-open').click();
      await frame.getByTestId('filter-unread').click();
      assert.equal(await frame.getByTestId('filter-active').count(), 1);
      await frame.getByTestId('filter-reset').click();
      assert.equal(await frame.getByTestId('filter-active').count(), 0);
    }
    await page.close();
  } finally { await browser.close(); }
});

test('详情多选：全选取消，移除前确认，取消无副作用', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser);
    for (const platform of ['windows', 'android']) {
      const frame = page.frameLocator(`#preview-${platform}`);
      await frame.getByTestId('manga-card').first().click();
      await frame.getByTestId('select-enter').click();
      assert.match(await frame.getByTestId('selection-count').textContent(), /1/);
      await frame.getByTestId('chapter-row').nth(1).click();
      assert.match(await frame.getByTestId('selection-count').textContent(), /2/);
      await frame.getByTestId('select-all').click();
      assert.match(await frame.getByTestId('selection-count').textContent(), /3/);
      await frame.getByTestId('remove-selected').click();
      assert.equal(await frame.getByRole('dialog', { name: '移除章节下载' }).count(), 1);
      await frame.getByTestId('remove-cancel').click();
      assert.equal(await frame.getByTestId('chapter-row').count(), 3);
      assert.match(await frame.getByTestId('chapter-row').first().textContent(), /已下载/);
      await frame.getByTestId('remove-selected').click();
      await frame.getByTestId('remove-confirm').click();
      assert.match(await frame.getByTestId('chapter-row').first().textContent(), /未下载/);
      await frame.getByTestId('select-enter').click();
      await frame.getByTestId('select-cancel').click();
      assert.equal(await frame.getByTestId('selection-count').count(), 0);
      await frame.getByTestId('detail-back').click();
      assert.equal(await frame.getByTestId('library-title').count(), 1);
    }
    await page.close();
  } finally { await browser.close(); }
});

test('同步设置：内部返回、关闭和开关均限制在本机', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser);
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    await pc.getByTestId('sync-open').click();
    await pc.getByTestId('sync-settings').click();
    assert.equal(await pc.getByTestId('sync-settings-title').count(), 1);
    await pc.getByTestId('startup-toggle').click();
    assert.equal(await pc.getByTestId('startup-toggle').isChecked(), false);
    await pc.locator('body').press('Escape');
    assert.equal(await pc.getByTestId('sync-title').count(), 1);
    await pc.locator('body').press('Escape');
    assert.equal(await pc.getByRole('dialog').count(), 0);
    await phone.getByTestId('sync-open').click();
    await phone.getByTestId('sync-settings').click();
    assert.equal(await phone.getByTestId('startup-toggle').isChecked(), true);
    await phone.getByTestId('sync-close').click();
    assert.equal(await phone.getByTestId('library-title').count(), 1);
    await page.close();
  } finally { await browser.close(); }
});

test('320 宽和桌面宽：内容无横向溢出，焦点仍留在操作端', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser);
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    assert.equal(await pc.getByTestId('nav-authors').count(), 1);
    assert.equal(await phone.getByTestId('nav-authors').count(), 0);
    assert.equal(await pc.getByTestId('nav-updates').isDisabled(), true, '范围外导航明确禁用');
    assert.notEqual(await pc.getByTestId('search-open').locator('svg').evaluate(el => getComputedStyle(el).strokeWidth), '0px', '描边图标须可见');
    await pc.getByTestId('search-open').click();
    assert.equal(await pc.getByTestId('library-query').evaluate(el => el === document.activeElement), true);
    assert.equal(await phone.getByTestId('library-query').count(), 0);
    for (const platform of ['windows', 'android']) {
      const frame = page.frameLocator(`#preview-${platform}`);
      assert.equal(await frame.locator('body').evaluate(el => el.scrollWidth <= el.clientWidth), true);
    }
    const small = await open(browser, 'device.html?platform=android', { width: 320, height: 700 });
    assert.equal(await small.locator('body').evaluate(el => el.scrollWidth <= el.clientWidth), true);
    await small.getByTestId('sync-open').click();
    assert.equal(await small.locator('body').evaluate(el => el.scrollWidth <= el.clientWidth), true);
    await small.close();
    await page.close();
  } finally { await browser.close(); }
});

test('弹层焦点圈定与返回触发器，零选择退出，多漫画下载状态隔离', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser);
    const pc = page.frameLocator('#preview-windows');
    await pc.getByTestId('sync-open').click();
    assert.equal(await pc.getByRole('dialog').evaluate(el => el.contains(document.activeElement)), true);
    assert.equal(await pc.locator('.app-content').getAttribute('inert'), '');
    await pc.locator('body').press('Tab');
    assert.equal(await pc.getByRole('dialog').evaluate(el => el.contains(document.activeElement)), true);
    await pc.locator('body').press('Escape');
    assert.equal(await pc.getByTestId('sync-open').evaluate(el => el === document.activeElement), true);
    await pc.getByTestId('manga-card').first().click();
    await pc.getByTestId('select-enter').click();
    await pc.getByTestId('chapter-row').first().click();
    assert.equal(await pc.getByTestId('selection-count').count(), 0);
    await pc.getByTestId('select-enter').click();
    await pc.getByTestId('select-all').click();
    await pc.getByTestId('remove-selected').click();
    assert.equal(await pc.getByRole('dialog', { name: '移除章节下载' }).evaluate(el => el.contains(document.activeElement)), true);
    await pc.locator('body').press('Escape');
    assert.equal(await pc.getByTestId('remove-selected').evaluate(el => el === document.activeElement), true);
    await pc.getByTestId('remove-selected').click();
    await pc.getByTestId('remove-confirm').click();
    await pc.getByTestId('detail-back').click();
    await pc.getByTestId('manga-card').nth(1).click();
    assert.match(await pc.getByTestId('chapter-row').first().textContent(), /已下载/);
    await page.close();
  } finally { await browser.close(); }
});

test('混合下载状态只移除适用章节并说明跳过数量', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser);
    const pc = page.frameLocator('#preview-windows');
    await pc.getByTestId('manga-card').first().click();
    await pc.getByTestId('select-enter').click();
    await pc.getByTestId('remove-selected').click();
    await pc.getByTestId('remove-confirm').click();
    assert.match(await pc.getByTestId('chapter-row').first().textContent(), /未下载/);
    await pc.getByTestId('select-enter').click();
    await pc.getByTestId('select-all').click();
    await pc.getByTestId('remove-selected').click();
    assert.match(await pc.getByRole('dialog', { name: '移除章节下载' }).textContent(), /已选 3 个章节，其中 2 个已下载/);
    await pc.getByTestId('remove-confirm').click();
    assert.match(await pc.locator('[role="status"]').textContent(), /已移除 2 个章节/);
    await page.close();
  } finally { await browser.close(); }
});

test('改进书架：三态筛选、搜索叠加与详情回跳恢复', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser, 'device.html?platform=android', { width: 320, height: 390 });
    await page.getByTestId('filter-open').click();
    const cycle = page.getByTestId('filter-unread');
    assert.equal(await cycle.getAttribute('aria-pressed'), 'false');
    await cycle.click();
    assert.equal(await cycle.getAttribute('aria-pressed'), 'true');
    assert.equal(await page.getByTestId('manga-card').count(), 2);
    await cycle.click();
    assert.equal(await cycle.getAttribute('aria-pressed'), 'mixed');
    assert.equal(await page.getByTestId('manga-card').count(), 1);
    await cycle.click();
    assert.equal(await cycle.getAttribute('aria-pressed'), 'false');
    await cycle.click();
    await page.getByTestId('search-open').click();
    await page.getByTestId('library-query').fill('星海');
    assert.equal(await page.getByTestId('manga-card').count(), 1);
    await page.locator('.scroll-content').evaluate(el => { el.scrollTop = 40; });
    await page.getByTestId('manga-card').first().scrollIntoViewIfNeeded();
    const before = await page.locator('.scroll-content').evaluate(el => el.scrollTop);
    await page.getByTestId('manga-card').first().click();
    await page.getByTestId('detail-back').click();
    assert.equal(await page.getByTestId('library-query').inputValue(), '星海');
    assert.equal(await page.getByTestId('filter-active').count(), 1);
    assert.equal(await page.locator('.scroll-content').evaluate(el => el.scrollTop), before);
    assert.equal(await page.getByTestId('manga-card').first().evaluate(el => el === document.activeElement), true);
    await page.getByTestId('search-close').click();
    await page.getByTestId('filter-reset').first().click();
    assert.equal(await page.getByTestId('manga-card').count(), 3);
  } finally { await browser.close(); }
});

test('改进章节：反选、Desktop Shift 范围、阅读预览与下载确认快照', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser, 'device.html?platform=windows');
    await page.getByTestId('manga-card').first().click();
    await page.getByTestId('continue-read').click();
    assert.equal(await page.getByTestId('reader-preview').count(), 1);
    await page.getByTestId('reader-next').click();
    await page.getByTestId('reader-back').click();
    assert.match(await page.getByTestId('detail-progress').textContent(), /第 2 页/);
    await page.getByTestId('select-enter').click();
    assert.equal(await page.getByTestId('continue-read').count(), 0);
    await page.getByTestId('select-invert').click();
    assert.match(await page.getByTestId('selection-count').textContent(), /2/);
    await page.getByTestId('select-cancel').click();
    await page.getByTestId('select-enter').click();
    await page.getByTestId('chapter-row').last().click({ modifiers: ['Shift'] });
    assert.match(await page.getByTestId('selection-count').textContent(), /3/);
    await page.getByTestId('remove-selected').click();
    assert.match(await page.getByRole('dialog', { name: '移除章节下载' }).textContent(), /已选 3 个章节/);
    await page.getByTestId('remove-cancel').click();
    assert.match(await page.getByTestId('selection-count').textContent(), /3/);
  } finally { await browser.close(); }
});

test('改进 Android：真实触摸长按选择连续章节', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 320, height: 700 }, hasTouch: true, isMobile: true });
    const page = await context.newPage();
    page.setDefaultTimeout(3000);
    await page.goto('file://' + path.resolve(__dirname, 'device.html').replace(/\\/g, '/') + '?platform=android');
    await page.getByTestId('manga-card').first().click();
    await page.getByTestId('select-enter').click();
    const box = await page.getByTestId('chapter-row').last().boundingBox();
    const client = await context.newCDPSession(page);
    const x = box.x + box.width / 2, y = box.y + box.height / 2;
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    await new Promise(resolve => setTimeout(resolve, 520));
    await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    assert.match(await page.getByTestId('selection-count').textContent(), /3/);
  } finally { await browser.close(); }
});

test('改进弹层与主题：本机设置重开保持，焦点/滚动/Tab 圈定和字体放大', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser);
    const pc = page.frameLocator('#preview-windows');
    const phone = page.frameLocator('#preview-android');
    await pc.getByTestId('sync-open').click();
    await pc.getByTestId('sync-settings').click();
    await pc.getByTestId('startup-toggle').click();
    assert.equal(await pc.getByTestId('startup-toggle').evaluate(el => el === document.activeElement), true, '点击后开关持有焦点');
    const originalToggle = await pc.getByTestId('startup-toggle').elementHandle();
    await page.getByTestId('theme-toggle').click();
    await pc.locator('body.theme-light').waitFor();
    assert.equal(await pc.getByTestId('startup-toggle').isChecked(), false);
    assert.equal(await originalToggle.evaluate(el => el.isConnected), true, '主题切换保留输入节点');
    assert.equal(await page.getByTestId('theme-toggle').evaluate(el => el === document.activeElement), true, '应用外主题按钮保留焦点');
    await pc.getByTestId('sync-close').focus();
    await pc.locator('body').press('Shift+Tab');
    assert.equal(await pc.getByRole('dialog').evaluate(el => el.contains(document.activeElement)), true);
    await pc.getByTestId('sync-close').click();
    await pc.getByTestId('sync-open').click();
    await pc.getByTestId('sync-settings').click();
    assert.equal(await pc.getByTestId('startup-toggle').isChecked(), false);
    await phone.getByTestId('sync-open').click();
    await phone.getByTestId('sync-settings').click();
    assert.equal(await phone.getByTestId('startup-toggle').isChecked(), true);
    await page.setViewportSize({ width: 1100, height: 800 });
    assert.equal(await pc.getByTestId('sync-settings-title').count(), 1);
    const small = await open(browser, 'device.html?platform=android', { width: 320, height: 700 });
    await small.evaluate(() => { document.body.style.zoom = '2'; });
    await small.getByTestId('sync-open').click();
    await small.getByTestId('sync-close').click();
    assert.equal(await small.getByRole('dialog').count(), 0);
  } finally { await browser.close(); }
});

test('改进视觉：Desktop 7比10、Android 2比3 封面与主题语义色', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser);
    for (const [platform, expected] of [['windows', 0.7], ['android', 2 / 3]]) {
      const frame = page.frameLocator(`#preview-${platform}`);
      const cover = frame.locator('.book-cover').first();
      const box = await cover.boundingBox();
      assert.ok(Math.abs(box.width / box.height - expected) < 0.01, `${platform} 书架封面比例`);
      await frame.getByTestId('manga-card').first().click();
      const hero = await frame.locator('.hero-cover').boundingBox();
      assert.ok(Math.abs(hero.width / hero.height - expected) < 0.01, `${platform} 详情封面比例`);
      await frame.getByTestId('detail-back').click();
      await frame.getByTestId('sync-open').click();
      assert.equal(await frame.locator('.sheet').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(41, 39, 48)');
      await frame.getByTestId('sync-close').click();
    }
    await page.getByTestId('theme-toggle').click();
    const phone = page.frameLocator('#preview-android');
    await phone.locator('body.theme-light').waitFor();
    await phone.getByTestId('sync-open').click();
    assert.equal(await phone.locator('.sheet').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(252, 247, 255)');
  } finally { await browser.close(); }
});

test('契约补充：筛选先退、反选归零、反向范围、无候选隐藏继续入口', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser, 'device.html?platform=windows');
    await page.getByTestId('filter-open').click();
    await page.getByTestId('filter-unread').click();
    await page.locator('body').press('Escape');
    assert.equal(await page.getByTestId('filter-unread').count(), 0);
    assert.equal(await page.getByTestId('filter-active').count(), 1);
    await page.getByTestId('filter-open').click();
    await page.getByTestId('filter-reset').click();
    await page.getByTestId('manga-card').nth(1).click();
    assert.equal(await page.getByTestId('continue-read').count(), 0, '已读作品无继续候选');
    await page.getByTestId('detail-back').click();
    await page.getByTestId('manga-card').first().click();
    await page.getByTestId('select-enter').click();
    await page.getByTestId('select-all').click();
    await page.getByTestId('select-invert').click();
    assert.equal(await page.getByTestId('selection-count').count(), 0);
    await page.getByTestId('select-enter').click();
    await page.getByTestId('chapter-row').first().click();
    await page.getByTestId('select-enter').click();
    await page.getByTestId('chapter-row').last().click();
    await page.getByTestId('chapter-row').first().click({ modifiers: ['Shift'] });
    assert.match(await page.getByTestId('selection-count').textContent(), /3/);
  } finally { await browser.close(); }
});

test('契约补充：读完本书章节后继续阅读入口消失', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await open(browser, 'device.html?platform=android');
    await page.getByTestId('manga-card').first().click();
    for (let index = 0; index < 3; index += 1) {
      await page.getByTestId('chapter-row').nth(index).click();
      await page.getByTestId('reader-next').click();
      await page.getByTestId('reader-next').click();
      await page.getByTestId('reader-back').click();
    }
    assert.equal(await page.getByTestId('continue-read').count(), 0);
  } finally { await browser.close(); }
});

test('真实200%字号：Android 320 与 Desktop 的设置、确认和返回可达', async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  async function doubleComputedFonts(page, rootSelector, selector) {
    const target = page.locator(selector);
    const before = await target.evaluate(el => parseFloat(getComputedStyle(el).fontSize));
    await page.evaluate(root => {
      const container = document.querySelector(root);
      const entries = [container, ...container.querySelectorAll('*')]
        .map(el => [el, parseFloat(getComputedStyle(el).fontSize)]);
      entries.forEach(([el, pixels]) => { if (Number.isFinite(pixels)) el.style.fontSize = `${pixels * 2}px`; });
    }, rootSelector);
    const after = await target.evaluate(el => parseFloat(getComputedStyle(el).fontSize));
    assert.ok(Math.abs(after / before - 2) < 0.01, `计算字号必须实际翻倍: ${before}→${after}`);
  }
  try {
    for (const [platform, width, height] of [['android', 320, 390], ['windows', 900, 500]]) {
      const page = await open(browser, `device.html?platform=${platform}`, { width, height });
      await page.getByTestId('sync-open').click();
      await page.getByTestId('sync-settings').click();
      await doubleComputedFonts(page, '.sheet', '.sheet-header h2');
      const scroll = await page.locator('.sheet-content').evaluate(el => {
        el.scrollTop = el.scrollHeight;
        return { top: el.scrollTop, overflow: el.scrollHeight > el.clientHeight };
      });
      if (scroll.overflow) assert.ok(scroll.top > 0, `${platform} 设置内容可滚到底部`);
      await page.getByTestId('periodic-toggle').click();
      await page.getByTestId('sync-back').click();
      assert.equal(await page.getByTestId('sync-title').count(), 1);
      await doubleComputedFonts(page, '.sheet', '.sheet-header h2');
      await page.getByTestId('sync-close').click();
      await page.getByTestId('manga-card').first().click();
      await page.getByTestId('select-enter').click();
      await page.getByTestId('remove-selected').click();
      await doubleComputedFonts(page, '.confirm-dialog', '.confirm-dialog h2');
      const box = await page.getByRole('dialog', { name: '移除章节下载' }).boundingBox();
      assert.ok(box.y >= 0 && box.y + box.height <= height, `${platform} 确认框保持在视口内`);
      await page.getByTestId('remove-cancel').click();
      assert.match(await page.getByTestId('chapter-row').first().textContent(), /已下载/);
      await page.getByTestId('remove-selected').click();
      await doubleComputedFonts(page, '.confirm-dialog', '.confirm-dialog h2');
      await page.getByTestId('remove-confirm').click();
      assert.match(await page.getByTestId('chapter-row').first().textContent(), /未下载/);
      await page.getByTestId('detail-back').click();
      assert.equal(await page.getByTestId('library-title').count(), 1);
      assert.equal(await page.locator('body').evaluate(el => el.scrollWidth <= el.clientWidth), true);
      await page.close();
    }
  } finally { await browser.close(); }
});
