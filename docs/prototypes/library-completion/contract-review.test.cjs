// 独立审核：由主代理维护，直接驱动交付页面，不复制演示实现。
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function review(fn, entry = 'device.html?platform=windows', width = 1000, height = 800) {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width, height } });
    page.setDefaultTimeout(4000);
    const [file, query] = entry.split('?');
    await page.goto(pathToFileURL(path.join(__dirname, file)).href + (query ? `?${query}` : ''));
    await fn(page);
  } finally {
    await browser.close();
  }
}

async function selected(page) {
  return page.locator('.manga-hit[aria-pressed="true"]').evaluateAll(nodes =>
    nodes.map(node => node.getAttribute('data-id')).sort());
}

test('独立契约：完整 Windows 选择示例、隐藏锚点、跨分类与四布局', () => review(async page => {
  for (const layout of ['compact', 'comfortable', 'cover-only', 'list']) {
    await page.evaluate(() => window.demo.scenario('baseline'));
    await page.getByTestId('panel-open').click();
    await page.getByTestId('panel-tab-display').click();
    await page.getByTestId(`layout-${layout}`).click();
    await page.keyboard.press('Escape');
    await page.getByTestId('manga-B').click({ modifiers: ['Control'] });
    await page.getByTestId('manga-E').click({ modifiers: ['Shift'] });
    assert.deepEqual(await selected(page), ['B', 'C', 'D', 'E']);
    await page.getByTestId('manga-C').click({ modifiers: ['Shift'] });
    assert.deepEqual(await selected(page), ['B', 'C']);
    await page.getByTestId('manga-E').click({ modifiers: ['Control'] });
    await page.getByTestId('manga-D').click({ modifiers: ['Control', 'Shift'] });
    assert.deepEqual(await selected(page), ['B', 'C', 'D', 'E']);
    await page.getByTestId('category-2').click();
    await page.getByTestId('manga-F').click({ modifiers: ['Shift'] });
    assert.match(await page.getByTestId('selection-count').textContent(), /5/);
    await page.getByTestId('category-1').click();
    await page.getByTestId('search-open').click();
    await page.getByTestId('library-query').fill('星海');
    await page.getByTestId('manga-A').click({ modifiers: ['Shift'] });
    assert.deepEqual(await selected(page), ['A']);
    assert.match(await page.getByTestId('selection-count').textContent(), /6/);
    await page.getByTestId('manga-A').click();
    assert.deepEqual(await selected(page), []);
    assert.match(await page.getByTestId('selection-count').textContent(), /5/);
    assert.equal(await page.getByTestId('detail-back').count(), 0);
    await page.getByTestId('manga-A').click();
    assert.deepEqual(await selected(page), ['A']);
    assert.match(await page.getByTestId('selection-count').textContent(), /6/);
    await page.keyboard.press('Escape');
    await page.getByTestId('manga-A').click();
    await page.getByTestId('detail-back').click();
    assert.equal(await page.getByTestId('selection-count').count(), 0);
  }
}));

test('独立契约：分类键盘不循环、不连发、不抢输入或模态', () => review(async page => {
  const active = () => page.locator('.categories button.active').getAttribute('data-testid');
  await page.getByTestId('library-scroll').focus();
  await page.keyboard.down('Control');
  await page.keyboard.down('ArrowRight');
  await page.keyboard.down('ArrowRight');
  await page.keyboard.up('ArrowRight');
  await page.keyboard.up('Control');
  assert.equal(await active(), 'category-2');
  await page.keyboard.press('Control+ArrowRight');
  assert.equal(await active(), 'category-2');
  await page.keyboard.press('Control+ArrowLeft');
  assert.equal(await active(), 'category-1');
  await page.getByTestId('search-open').click();
  await page.getByTestId('library-query').fill('星海 手记');
  await page.keyboard.press('Control+ArrowRight');
  assert.equal(await active(), 'category-1');
  await page.getByTestId('panel-open').click();
  await page.keyboard.press('Control+ArrowRight');
  assert.equal(await active(), 'category-1');
}));

test('独立契约：真实鼠标滚轮分段才刷新且不按搜索裁剪工作集', () => review(async page => {
  await page.getByTestId('search-open').click();
  await page.getByTestId('library-query').fill('星海');
  const bounds = await page.getByTestId('library-scroll').boundingBox();
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  await page.mouse.wheel(0, -90);
  await page.getByTestId('wheel-hint').filter({ hasText: '再次' }).waitFor();
  assert.equal(await page.getByTestId('update-details').count(), 0);
  await page.mouse.wheel(0, -60);
  assert.equal(await page.getByTestId('update-details').count(), 0);
  await page.waitForTimeout(450);
  await page.mouse.wheel(0, -60);
  await page.getByTestId('update-details').click();
  await page.waitForTimeout(1800);
  const results = await page.getByTestId('update-results').textContent();
  assert.match(results, /星海手记/);
  assert.match(results, /云端旅人/);
  assert.doesNotMatch(results, /岛屿日记/);
  assert.match(results, /成功 5/);
}));

test('独立契约：评分以十分制且排序方向与标签一致', () => review(async page => {
  await page.evaluate(() => window.demo.scenario('multi-tracker'));
  await page.getByTestId('panel-open').click();
  await page.getByTestId('panel-tab-sort').click();
  for (let pass = 0; pass < 2; pass++) {
    await page.getByTestId('sort-score').click();
    const direction = await page.getByTestId('sort-score').textContent();
    const scores = await page.locator('.book .score').allTextContents();
    const numbers = scores.filter(text => /\d/.test(text)).map(parseFloat);
    assert.deepEqual([...numbers].sort((a, b) => direction.includes('升序') ? a - b : b - a), numbers);
    assert.deepEqual([...numbers].sort(), [8, 8, 9]);
  }
}));

test('独立契约：双端焦点隔离、实际字号翻倍、320px弹层可达', () => review(async page => {
  const pc = page.frameLocator('#preview-windows');
  const phone = page.frameLocator('#preview-android');
  await phone.getByTestId('settings-open').click();
  const title = phone.getByRole('dialog').locator('h2');
  const before = await title.evaluate(node => parseFloat(getComputedStyle(node).fontSize));
  await pc.getByTestId('search-open').click();
  await pc.getByTestId('library-query').fill('星海');
  await page.getByTestId('font-toggle').click();
  await page.frame({ url: /platform=android/ }).waitForFunction(() =>
    document.documentElement.style.fontSize === '28px');
  const after = await title.evaluate(node => parseFloat(getComputedStyle(node).fontSize));
  assert.equal(after, before * 2);
  await page.getByTestId('theme-toggle').click();
  assert.equal(await page.getByTestId('theme-toggle').evaluate(node => node === document.activeElement), true);
  assert.equal(await pc.getByTestId('library-query').inputValue(), '星海');
  assert.equal(await phone.locator('body').evaluate(node => node.scrollWidth <= node.clientWidth), true);
  assert.equal(await phone.getByRole('dialog').evaluate(node => node.scrollWidth <= node.clientWidth), true);
  await phone.getByTestId('pref-interval').selectOption('72');
  await phone.getByTestId('modal-close').focus();
  await page.keyboard.press('Shift+Tab');
  assert.equal(await phone.getByRole('dialog').evaluate(node => node.contains(document.activeElement)), true);
  await phone.getByTestId('modal-close').click();
  assert.equal(await phone.getByTestId('settings-open').evaluate(node => node === document.activeElement), true);
  await phone.getByTestId('settings-open').click();
  assert.equal(await phone.getByTestId('pref-interval').inputValue(), '72');
}, 'index.html', 1440, 1000));

test('独立契约：分类排序方向隔离', () => review(async page => {
  await page.getByTestId('panel-open').click();
  await page.getByTestId('panel-tab-sort').click();
  await page.getByTestId('sort-title').click();
  assert.match(await page.getByTestId('sort-title').textContent(), /降序/);
  await page.keyboard.press('Escape');
  await page.getByTestId('category-2').click();
  await page.getByTestId('panel-open').click();
  await page.getByTestId('panel-tab-sort').click();
  assert.match(await page.getByTestId('sort-title').textContent(), /升序/);
}));

test('独立契约：自定义封面可再次替换', () => review(async page => {
  await page.getByTestId('manga-A').click();
  await page.getByTestId('cover-replace').click();
  const first = await page.locator('.hero .cover').screenshot();
  await page.getByTestId('cover-replace').click();
  const second = await page.locator('.hero .cover').screenshot();
  assert.notDeepEqual(first, second, '重复替换应有可见新封面版本');
}));

test('独立契约：刷新结束后任意方向滚轮都延续800ms静默冷却', () => review(async page => {
  const scroll = page.getByTestId('library-scroll');
  await scroll.dispatchEvent('wheel', { deltaY: -90 });
  await page.waitForTimeout(430);
  await scroll.dispatchEvent('wheel', { deltaY: -60 });
  await page.waitForFunction(() => window.demo.state.job?.status === 'done');
  for (let index = 0; index < 5; index++) {
    await page.waitForTimeout(240);
    await scroll.dispatchEvent('wheel', { deltaY: 1 });
  }
  await scroll.dispatchEvent('wheel', { deltaY: -90 });
  assert.equal(await page.getByTestId('wheel-hint').textContent(), '');
}));
