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

test('独立契约：Ctrl滚轮分类不循环、同段不连跳，移除方向键', () => review(async page => {
  const active = () => page.locator('.categories button.active').getAttribute('data-testid');
  const scroll = page.getByTestId('library-scroll');
  await page.getByTestId('manga-B').click({ modifiers: ['Control'] });
  await page.getByTestId('category-2').click();
  const prevented = await scroll.evaluate(node => {
    const event = new WheelEvent('wheel', { deltaY: -80, ctrlKey: true, bubbles: true, cancelable: true });
    node.dispatchEvent(event);
    return event.defaultPrevented;
  });
  assert.equal(prevented, true, '分类滚轮必须阻止浏览器默认缩放');
  assert.equal(await active(), 'category-1');
  await scroll.dispatchEvent('wheel', { deltaY: -80, ctrlKey: true });
  assert.equal(await active(), 'category-1');
  await page.waitForTimeout(280);
  await scroll.dispatchEvent('wheel', { deltaY: -80, ctrlKey: true });
  assert.equal(await active(), 'category-0');
  await page.waitForTimeout(280);
  await scroll.dispatchEvent('wheel', { deltaY: -80, ctrlKey: true });
  assert.equal(await active(), 'category-0');
  await scroll.dispatchEvent('wheel', { deltaY: 80, ctrlKey: true });
  assert.equal(await active(), 'category-1');
  assert.match(await page.getByTestId('selection-count').textContent(), /1/);
  await page.keyboard.press('Control+ArrowRight');
  await page.keyboard.press('Control+ArrowLeft');
  assert.equal(await active(), 'category-1');
  assert.equal(await page.getByTestId('wheel-hint').textContent(), '');
  assert.equal(await page.getByTestId('update-details').count(), 0);
}));

test('独立契约：Ctrl滚轮保留搜索焦点、弹层和输入法边界', () => review(async page => {
  const scroll = page.getByTestId('library-scroll');
  const active = () => page.locator('.categories button.active').getAttribute('data-testid');
  await page.getByTestId('search-open').click();
  await page.getByTestId('library-query').fill('星海');
  await scroll.dispatchEvent('wheel', { deltaY: 80, ctrlKey: true });
  assert.equal(await active(), 'category-1');
  await scroll.focus();
  await page.evaluate(() => document.dispatchEvent(new CompositionEvent('compositionstart')));
  await scroll.dispatchEvent('wheel', { deltaY: 80, ctrlKey: true });
  assert.equal(await active(), 'category-1');
  await page.evaluate(() => document.dispatchEvent(new CompositionEvent('compositionend')));
  for (const event of [
    { deltaY: 80, ctrlKey: true, altKey: true },
    { deltaY: 80, ctrlKey: true, shiftKey: true },
    { deltaY: 0, deltaX: 80, ctrlKey: true },
  ]) await scroll.dispatchEvent('wheel', event);
  assert.equal(await active(), 'category-1');
  await page.getByTestId('panel-open').click();
  await scroll.dispatchEvent('wheel', { deltaY: 80, ctrlKey: true });
  assert.equal(await active(), 'category-1');
  await page.keyboard.press('Escape');
  await scroll.focus();
  await scroll.dispatchEvent('wheel', { deltaY: 80, ctrlKey: true });
  assert.equal(await active(), 'category-2');
  assert.equal(await page.getByTestId('library-query').inputValue(), '星海');
}));

test('独立契约：真实Ctrl鼠标滚轮切分类且不缩放页面', () => review(async page => {
  const scroll = page.getByTestId('library-scroll');
  await scroll.focus();
  const bounds = await scroll.boundingBox();
  const before = await page.evaluate(() => [innerWidth, devicePixelRatio, visualViewport.scale]);
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  await page.keyboard.down('Control');
  await page.mouse.wheel(0, 100);
  await page.waitForFunction(() => window.demo.state.category === 2);
  await page.keyboard.up('Control');
  assert.deepEqual(await page.evaluate(() => [innerWidth, devicePixelRatio, visualViewport.scale]), before);
  assert.equal(await page.getByTestId('update-details').count(), 0);
}));

test('独立契约：Ctrl滚轮允许更新中切类，单分类与详情不越界', () => review(async page => {
  await page.getByTestId('refresh').click();
  await page.getByTestId('library-scroll').dispatchEvent('wheel', { deltaY: 80, ctrlKey: true });
  assert.equal(await page.locator('.categories button.active').getAttribute('data-testid'), 'category-2');
  await page.waitForFunction(() => window.demo.state.job?.status === 'done');
  assert.deepEqual(await page.evaluate(() => window.demo.state.job.ids), ['A', 'B', 'C', 'D', 'E']);
  await page.evaluate(() => window.demo.scenario('single-custom'));
  await page.getByTestId('library-scroll').dispatchEvent('wheel', { deltaY: -80, ctrlKey: true });
  assert.equal(await page.locator('.categories button.active').getAttribute('data-testid'), 'category-1');
  await page.getByTestId('manga-A').click();
  await page.locator('.detail').dispatchEvent('wheel', { deltaY: 80, ctrlKey: true });
  assert.equal(await page.getByTestId('detail-back').count(), 1);
  assert.equal(await page.evaluate(() => window.demo.state.category), 1);
}));

test('独立契约：Android不接入Windows的Ctrl滚轮分类', () => review(async page => {
  await page.getByTestId('library-scroll').dispatchEvent('wheel', { deltaY: 80, ctrlKey: true });
  assert.equal(await page.locator('.categories button.active').getAttribute('data-testid'), 'category-1');
  assert.equal(await page.getByTestId('update-details').count(), 0);
}, 'device.html?platform=android', 320));

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
  await page.getByTestId('detail-cover-menu').click();
  await page.getByTestId('cover-replace').click();
  const first = await page.locator('.hero .cover').screenshot();
  await page.getByTestId('detail-cover-menu').click();
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

test('独立详情契约：当前顶栏的全部已读是可读文字，取消收藏后书架不再列出作品', () => review(async page => {
  await page.getByTestId('manga-A').click();
  assert.match(await page.getByTestId('detail-mark-all').textContent(), /全部标为已读/);
  await page.getByTestId('detail-library').click();
  await page.getByTestId('confirm-yes').click();
  await page.getByTestId('detail-back').click();
  assert.equal(await page.getByTestId('manga-A').count(), 0);
}));

test('独立详情契约：Android 窄屏滚动到章节菜单后，每个菜单项均可实际点击', () => review(async page => {
  await page.getByTestId('manga-A').click();
  await page.getByTestId('detail-filter-menu').click();
  const menuReachableWithoutAutoscroll = await page.getByTestId('chapter-filter-unread').evaluate(node => {
    const rect = node.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
    return hit === node || node.contains(hit);
  });
  assert.equal(menuReachableWithoutAutoscroll, true, '展开后首个筛选项应直接出现在可见顶层');
  await page.getByTestId('chapter-filter-unread').click();
  assert.match(await page.getByTestId('chapter-count').textContent(), /2\/3/);
  await page.getByTestId('chapter-settings-tab-display').click();
  await page.getByTestId('chapter-display-number').click();
  await page.keyboard.press('Escape');
  assert.match(await page.getByTestId('chapter-row-A-3').textContent(), /第 3 话/);
}, 'device.html?platform=android', 320, 700));

test('独立叠加契约：目录已同步后开启作品信息刷新，下一次检查仍更新简介与源封面', () => review(async page => {
  await page.evaluate(() => window.demo.scenario('chapter-change'));
  await page.getByTestId('manga-A').click();
  const before = await page.locator('.detail-description').textContent();
  await page.getByTestId('detail-refresh').click();
  await page.waitForFunction(() => window.demo.state.job?.status === 'done');
  assert.equal(await page.locator('.detail-description').textContent(), before);
  await page.getByTestId('detail-back').click();
  await page.getByTestId('settings-open').click();
  await page.getByTestId('pref-metadata').check();
  await page.getByTestId('modal-close').click();
  await page.getByTestId('manga-A').click();
  await page.getByTestId('detail-refresh').click();
  await page.waitForFunction(() => window.demo.state.job?.status === 'done');
  assert.match(await page.locator('.detail-description').textContent(), /已从图源刷新简介/);
  assert.match(await page.locator('.hero .cover').textContent(), /源封面 · 2/);
  await page.getByTestId('update-details').click();
  assert.match(await page.getByTestId('update-results').textContent(), /目录无变化：新增 0/);
}));

test('独立叠加契约：章节源顺序与章节号排序可来回切换', () => review(async page => {
  await page.evaluate(() => window.demo.scenario('chapter-change'));
  await page.getByTestId('manga-A').click();
  await page.getByTestId('detail-refresh').click();
  await page.waitForFunction(() => window.demo.state.job?.status === 'done');
  const order = () => page.locator('[data-testid^="chapter-row-"]').evaluateAll(rows => rows.map(row => row.dataset.chapterId));
  assert.deepEqual(await order(), ['A-3', 'A-4', 'A-1']);
  await page.getByTestId('detail-filter-menu').click();
  await page.getByTestId('chapter-settings-tab-sort').click();
  await page.getByTestId('chapter-sort-number').click();
  assert.deepEqual(await order(), ['A-4', 'A-3', 'A-1']);
  await page.getByTestId('chapter-sort-source').click();
  assert.deepEqual(await order(), ['A-3', 'A-4', 'A-1']);
}));
