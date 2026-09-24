const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function open(browser, file = 'index.html', viewport = { width: 1440, height: 1000 }) {
  const page = await browser.newPage({ viewport });
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
