const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
async function setup(run) {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1120 } });
    const errors = [], remote = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', req => { if (/^https?:/.test(req.url())) remote.push(req.url()); });
    await page.goto(pathToFileURL(path.resolve(__dirname, 'index.html')).href);
    const pc = page.frameLocator('#preview-windows'), phone = page.frameLocator('#preview-android');
    await pc.getByTestId('signature').waitFor(); await phone.getByTestId('signature').waitFor();
    await run(page, pc, phone);
    assert.deepEqual(errors, []); assert.deepEqual(remote, []);
  } finally { await browser.close(); }
}
const action = (f, a) => f.locator(`[data-action="${a}"]`);
test('作品日期与首次发现明确区分，刷新不推迟，单源未知信息不编造', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check();
  for (const [target, f] of [['windows', pc], ['android', phone]]) {
    await page.locator('#target').selectOption(target);
    await f.getByTestId('signature').click();
    const published = f.locator('[data-action="work"][data-work="w0"] .work-date');
    const firstSeen = f.locator('[data-action="work"][data-work="w2"] .work-date');
    assert.equal(await published.innerText(), '上架 2017-03-18');
    assert.equal(await published.getAttribute('title'), '日期来源：漫画柜');
    assert.equal(await firstSeen.innerText(), '首次发现 2026-09-18');
    await page.locator('#refresh-dates').click();
    assert.equal(await firstSeen.innerText(), '首次发现 2026-09-18');
    await f.locator('[data-action="work"][data-work="w0"]').click();
    assert.equal(await f.getByRole('dialog').locator('[data-version]').count(), 1);
    assert.match(await f.locator('[data-version="v0"]').innerText(), /日期待核实/);
    await f.getByRole('button', { name: '取消', exact: true }).click();
    await action(f, 'merge-select').click(); await f.locator('[data-select="tw"]').check(); await action(f, 'add').click();
    await f.locator('[data-action="work"][data-work="w1"]').click();
    assert.match(await f.locator('[data-version="tw"]').innerText(), /章节数未知/);
    assert.doesNotMatch(await f.locator('[data-version="tw"]').innerText(), /0 章/);
    assert.equal(await f.getByRole('dialog').evaluate(e => e.scrollWidth > e.clientWidth), false);
    await f.getByRole('button', { name: '取消', exact: true }).click();
  }
}));
test('收藏徽标、书架显示方式及逐版本来源选择在双端独立工作', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check();
  for (const f of [pc, phone]) {
    if (f === pc) await f.getByTestId('nav-authors').click();
    else { await f.getByTestId('nav-browse').click(); await action(f, 'authors').click(); }
    assert.equal(await f.locator('[data-author="a"] .collected-badge').count(), 1);
    await f.locator('[data-author="a"]').click();
    assert.equal(await f.locator('.work-row .collected-badge').count(), 1);
    await action(f, 'merge-select').click(); await f.locator('[data-select="en"]').check(); await f.locator('[data-select="tw"]').check(); await action(f, 'add').click();
    assert.equal(await f.locator('.work-row .collected-badge').count(), 2);
    await f.getByRole('button', { name: 'MangaDex', exact: true }).click();
    const work = f.locator('[data-action="work"][data-work="w0"]');
    assert.equal(await work.locator('.collected-badge').count(), 1);
    await work.click();
    assert.equal(await f.getByRole('dialog').locator('[data-version]').count(), 2);
    const local = f.locator('[data-version="v0"]'), other = f.locator('[data-version="md"]');
    assert.match(await local.innerText(), /300 章/); assert.match(await local.innerText(), /已收藏/);
    assert.match(await other.innerText(), /未收藏/);
    assert.equal(await local.locator('.cover').count(), 1);
    await f.getByRole('button', { name: '关闭', exact: true }).press('Escape');
    assert.equal(await work.evaluate(e => e === document.activeElement), true);
    await work.click(); await other.click();
    assert.equal(await f.locator('.manga-title').innerText(), 'Parallel Paradise');
    await action(f, 'back').click();
    for (const mode of ['comfortable', 'compact', 'list']) {
      await action(f, 'display').click(); await f.locator(`[data-display="${mode}"]`).click();
      assert.equal(await f.locator('.work-collection').getAttribute('data-display'), mode);
      assert.equal(await action(f, 'display').evaluate(e => e === document.activeElement), true);
      assert.equal(await f.locator('body').evaluate(e => e.scrollWidth > innerWidth), false);
    }
    await action(f, 'display').click(); await f.locator('[data-display="comfortable"]').click();
    await action(f, 'back').click(); await f.locator('[data-author="a"]').click();
    assert.equal(await f.locator('.work-collection').getAttribute('data-display'), 'comfortable');
    if (f === pc) { await phone.getByTestId('signature').click(); assert.equal(await phone.locator('.work-collection').getAttribute('data-display'), 'list'); }
  }
}));
test('B布局展示最多三部去重作品，单作品不补位，封面点击进入作者', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check();
  for (const f of [pc, phone]) {
    if (f === pc) await f.getByTestId('nav-authors').click();
    else { await f.getByTestId('nav-browse').click(); await action(f, 'authors').click(); }
    const row = f.locator('[data-author="a"]');
    assert.equal(await row.locator('.representative-work').count(), 3);
    const covers = await row.locator('.representative-cover').evaluateAll(items => items.map(e => e.getBoundingClientRect().toJSON()));
    assert.ok(covers.every(box => Math.abs(box.y - covers[0].y) < 1));
    assert.ok(covers[1].x >= covers[0].x + covers[0].width);
    assert.deepEqual(await row.locator('.representative-title').allTextContents(), ['平行天堂', '极黑的布伦希尔德', '变异体少女']);
    assert.ok(await row.locator('strong').evaluate(e => e.getBoundingClientRect().y) < covers[0].y);
    await row.locator('.representative-cover').first().click();
    assert.equal(await f.locator('.bar h1').innerText(), '作者详情');
    await action(f, 'merge-select').click(); await f.locator('[data-select="en"]').check(); await action(f, 'add').click();
    await action(f, 'back').click();
    assert.equal(await row.locator('.representative-work').count(), 3);
    assert.deepEqual(await row.locator('.representative-work').evaluateAll(items => items.map(e => e.dataset.work)), ['w0', 'w1', 'w2']);
    await f.getByRole('tab', { name: '全部', exact: true }).click();
    assert.equal(await f.locator('[data-author="tw"] .representative-work').count(), 1);
    assert.equal(await f.locator('body').evaluate(e => e.scrollWidth > innerWidth), false);
  }
}));

test('作者子页键盘切换、返回滚动恢复及固定顶部栏', () => setup(async (page, pc, phone) => {
  await page.locator('#preview-windows, #preview-android').evaluateAll(frames => frames.forEach(frame => { frame.style.height = '350px'; }));
  for (const f of [pc, phone]) {
    const enterAuthors = async () => {
      if (f === pc) await f.getByTestId('nav-authors').click();
      else { await f.getByTestId('nav-browse').click(); await action(f, 'authors').click(); }
    };
    await enterAuthors();
    const following = f.getByRole('tab', { name: '关注', exact: true }), all = f.getByRole('tab', { name: '全部', exact: true });
    await following.focus(); await following.press('ArrowRight');
    assert.equal(await all.getAttribute('aria-selected'), 'true');
    assert.equal(await all.evaluate(e => e === document.activeElement), true);
    assert.equal(await all.getAttribute('aria-controls'), 'author-list');
    await all.press('ArrowLeft'); assert.equal(await following.getAttribute('aria-selected'), 'true');
    await following.press('ArrowRight');
    await f.locator('[data-author="tw"]').scrollIntoViewIfNeeded();
    const scroll = await f.locator('main').evaluate(e => e.scrollTop);
    assert.ok(scroll > 0);
    await f.locator('[data-author="tw"]').click();
    const top = (await f.locator('.bar').boundingBox()).y;
    await f.locator('main').evaluate(e => { e.scrollTop = e.scrollHeight; });
    assert.ok(await f.locator('main').evaluate(e => e.scrollTop > 0));
    assert.equal((await f.locator('.bar').boundingBox()).y, top);
    await action(f, 'back').click();
    assert.equal(await all.getAttribute('aria-selected'), 'true');
    assert.equal(await f.locator('main').evaluate(e => e.scrollTop), scroll);
    await enterAuthors();
    assert.equal(await following.getAttribute('aria-selected'), 'true');
  }
}));
test('关注与全部子页、空状态、返回及横向来源筛选遵循双端交互', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check();
  for (const f of [pc, phone]) {
    if (f === pc) await f.getByTestId('nav-authors').click();
    else { await f.getByTestId('nav-browse').click(); await action(f, 'authors').click(); }
    assert.equal(await f.getByRole('tab', { name: '关注', exact: true }).getAttribute('aria-selected'), 'true');
    assert.equal(await f.locator('[data-author]').count(), 1);
    await f.locator('[data-author="a"]').click();
    assert.equal(await f.locator('.bar h1').innerText(), '作者详情');
    assert.equal(await f.getByRole('button', { name: '返回', exact: true }).isVisible(), true);
    assert.doesNotMatch(await f.locator('main').innerText(), /CONFIRMED|PROBABLE|UNKNOWN|CONFLICT/);
    assert.equal(await f.locator('#source-filter').count(), 0);
    const chips = f.getByRole('group', { name: '来源筛选' });
    assert.deepEqual(await chips.getByRole('button').allTextContents(), ['全部来源', '漫画柜', '文字图源（演示）']);
    const geometry = await chips.getByRole('button').evaluateAll(items => items.map(e => ({ y: e.getBoundingClientRect().y, radius: getComputedStyle(e).borderRadius, height: e.getBoundingClientRect().height })));
    assert.ok(geometry.every(item => item.y === geometry[0].y && item.radius === '8px' && item.height === 32));
    await chips.getByRole('button', { name: '文字图源（演示）', exact: true }).click();
    assert.equal(await f.locator('.work-row').count(), 1);
    assert.equal(await f.locator('.work-row strong').innerText(), '极黑的布伦希尔德');
    assert.equal(await chips.getByRole('button', { name: '文字图源（演示）', exact: true }).getAttribute('aria-pressed'), 'true');
    assert.equal(await f.locator('body').evaluate(e => e.scrollWidth > innerWidth), false);
    await chips.getByRole('button', { name: '全部来源', exact: true }).click();
    await f.getByTestId('follow').click(); await action(f, 'unfollow-confirm').click();
    await action(f, 'back').click();
    assert.equal(await f.locator('.bar h1').innerText(), '作者');
    assert.equal(await f.locator('[data-author]').count(), 0);
    assert.equal(await f.getByText('关注作者以获取他们的新作提示', { exact: true }).isVisible(), true);
    await f.getByRole('button', { name: '查看全部作者', exact: true }).click();
    assert.equal(await f.getByRole('tab', { name: '全部', exact: true }).getAttribute('aria-selected'), 'true');
    assert.equal(await f.locator('[data-author]').count(), 3);
    await f.locator('[data-author="en"]').click(); await f.getByTestId('follow').click(); await action(f, 'back').click();
    assert.equal(await f.getByRole('tab', { name: '全部', exact: true }).getAttribute('aria-selected'), 'true');
    await f.getByRole('tab', { name: '关注', exact: true }).click();
    assert.deepEqual(await f.locator('[data-author]').evaluateAll(items => items.map(e => e.dataset.author)), ['en']);
    if (f === pc) { await phone.getByTestId('signature').click(); assert.equal(await phone.getByTestId('follow').innerText(), '已关注'); }
  }
}));
test('直接添加无需预览且全局设置只在双端作者列表右上角', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check();
  for (const f of [pc, phone]) {
    await f.getByTestId('signature').click(); assert.equal(await f.getByRole('button', { name: '作者设置', exact: true }).count(), 0);
    await action(f, 'merge-select').click(); assert.equal(await f.getByRole('button', { name: '添加', exact: true }).count(), 1);
    await f.locator('[data-select="en"]').check(); await f.getByRole('button', { name: '添加', exact: true }).click();
    assert.equal(await f.getByRole('dialog').count(), 0); assert.match(await f.locator('.hero').innerText(), /3 部作品 · 4 个来源版本/);
    assert.equal(await f.locator('body').evaluate(e => e.scrollWidth > innerWidth), false);
  }
  await pc.getByTestId('nav-authors').click(); await phone.getByTestId('nav-browse').click(); await action(phone, 'authors').click();
  for (const f of [pc, phone]) { const gear = f.getByRole('button', { name: '作者设置', exact: true }); const g = await gear.boundingBox(), b = await f.locator('.bar').boundingBox(); assert.ok(g.x > b.x + b.width / 2); }
}));
test('别名使用署名链接样式，确认切换显示名，取消和旧名入口不变', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check();
  for (const f of [pc, phone]) {
    await f.getByTestId('signature').click();
    assert.equal(await f.getByRole('button', { name: '修改名称', exact: true }).count(), 0);
    assert.equal(await f.getByTestId('author-aliases').count(), 0);
    await action(f, 'merge-select').click();
    await f.locator('[data-select="en"]').check(); await f.locator('[data-select="tw"]').check(); await action(f, 'add').click();
    const aliases = f.getByTestId('author-aliases');
    assert.equal(await aliases.getByRole('button').count(), 2);
    const english = aliases.getByRole('button', { name: 'Okamoto Lynn', exact: true });
    assert.equal(await english.evaluate(e => getComputedStyle(e).fontSize), '14px');
    await english.click();
    assert.match(await f.getByRole('dialog').innerText(), /要把Okamoto Lynn设为该作者的显示名称吗/);
    assert.equal(await f.getByRole('dialog').locator('input, select').count(), 0);
    await f.getByRole('button', { name: '取消', exact: true }).click();
    assert.equal(await f.getByTestId('author-name').innerText(), '冈本伦');
    assert.equal(await english.evaluate(e => e === document.activeElement), true);
    await english.click(); await f.getByRole('button', { name: '确定', exact: true }).click();
    assert.equal(await f.getByTestId('author-name').innerText(), 'Okamoto Lynn');
    assert.equal(await f.getByTestId('author-name').evaluate(e => e === document.activeElement), true);
    assert.equal(await aliases.getByRole('button', { name: '冈本伦', exact: true }).count(), 1);
    assert.equal(await aliases.getByRole('button', { name: '岡本倫', exact: true }).count(), 1);
    assert.equal(await f.getByTestId('follow').innerText(), '已关注');
    await f.locator('[data-action="work"][data-work="w0"]').click(); await f.locator('[data-version="v0"]').click(); await f.getByTestId('signature').click();
    assert.equal(await f.getByTestId('author-name').innerText(), 'Okamoto Lynn');
    await aliases.getByRole('button', { name: '冈本伦', exact: true }).click();
    await f.getByRole('button', { name: '确定', exact: true }).click();
    assert.equal(await f.getByTestId('author-name').innerText(), '冈本伦');
    assert.equal(await f.locator('body').evaluate(e => e.scrollWidth > innerWidth), false);
  }
}));

test('双端主名标题、横向别名图源及封面顶部对齐，作者列表无头像', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check();
  const typography = locator => locator.evaluate(e => {
    const s = getComputedStyle(e);
    return [s.fontSize, s.fontWeight, s.lineHeight, s.letterSpacing, s.padding, s.borderWidth, s.color];
  });
  for (const f of [pc, phone]) {
    const signature = f.getByTestId('signature');
    const titleStyle = await typography(f.locator('.manga-title'));
    const expected = await typography(signature);
    assert.deepEqual(expected.slice(0, 6), ['14px', '500', '20px', '0.1px', '0px', '0px']);
    const row = signature.locator('..');
    assert.equal(await row.evaluate(e => getComputedStyle(e).opacity), '0.78');
    assert.equal(await row.locator('svg').evaluate(e => e.getBoundingClientRect().width), 16);
    await signature.click();
    await action(f, 'merge-select').click();
    await f.locator('[data-select="en"]').check(); await f.locator('[data-select="tw"]').check(); await action(f, 'add').click();
    assert.deepEqual(await typography(f.getByTestId('author-name')), titleStyle);
    assert.equal(await f.getByTestId('author-name').locator('..').locator(':scope > svg').count(), 0);
    assert.equal(await f.getByTestId('author-name').locator('..').evaluate(e => getComputedStyle(e).opacity), '1');
    const names = await f.getByTestId('author-aliases').getByRole('button').all();
    for (const name of names) assert.deepEqual(await typography(name), expected);
    const boxes = await Promise.all(names.map(name => name.boundingBox()));
    assert.ok(Math.abs(boxes[1].y - boxes[0].y) < 1);
    assert.ok(boxes[1].x > boxes[0].x + boxes[0].width);
    assert.equal(await f.getByRole('button', { name: '查看漫画', exact: true }).count(), 0);
    const work = f.locator('.work-row').first();
    const cover = await work.locator('.cover').boundingBox(), title = await work.locator('strong').boundingBox();
    assert.ok(Math.abs(cover.y - title.y) < 1);
    await work.click();
    const sources = f.getByRole('dialog').locator('[data-version]');
    assert.equal(await sources.count(), 2);
    assert.equal(await f.locator('body').evaluate(e => e.scrollWidth > innerWidth), false);
    await f.locator('[data-version="md"]').click();
    assert.equal(await f.locator('.manga-title').innerText(), 'Parallel Paradise');
    assert.equal(await f.locator('.hero .pill').innerText(), 'MangaDex');
    await f.getByTestId('signature').click();
    assert.equal(await f.getByTestId('author-name').innerText(), '冈本伦');
    if (f === pc) await f.getByTestId('nav-authors').click();
    else { await f.getByTestId('nav-browse').click(); await action(f, 'authors').click(); }
    assert.equal(await f.locator('[data-author]').count(), 1);
    assert.equal(await f.locator('[data-author] .avatar, [data-author] img').count(), 0);
  }
}));

test('全局三频率保存取消失败、跨作者操作及另一设备隔离', () => setup(async (page, pc, phone) => {
  const settings = f => f.getByRole('button', { name: '作者设置', exact: true });
  await pc.getByTestId('nav-authors').click();
  for (const frequency of ['weekly', 'monthly', 'daily', 'monthly']) { await settings(pc).click(); await pc.getByLabel('检查频率').selectOption(frequency); await action(pc, 'settings-save').click(); assert.equal(await settings(pc).evaluate(e => e === document.activeElement), true); await settings(pc).click(); assert.equal(await pc.getByLabel('检查频率').inputValue(), frequency); await pc.getByRole('button', { name: '取消', exact: true }).click(); }
  await settings(pc).click(); await pc.getByLabel('检查频率').selectOption('weekly'); await pc.getByRole('button', { name: '取消', exact: true }).click();
  await pc.getByRole('tab', { name: '全部', exact: true }).click();
  await pc.locator('[data-author="en"]').click(); await action(pc, 'merge-select').click(); await pc.locator('[data-select="a"]').check(); await action(pc, 'add').click(); await pc.getByTestId('author-aliases').getByRole('button', { name: '冈本伦', exact: true }).click(); await pc.getByRole('button', { name: '确定', exact: true }).click();
  await pc.getByTestId('nav-authors').click(); await settings(pc).click(); assert.equal(await pc.getByLabel('检查频率').inputValue(), 'monthly'); await pc.getByRole('button', { name: '关闭', exact: true }).click();
  await phone.getByTestId('nav-browse').click(); await action(phone, 'authors').click(); await settings(phone).click(); assert.equal(await phone.getByLabel('检查频率').inputValue(), 'daily');
  await page.locator('#scenario').selectOption('submit-error'); await page.locator('#apply-scenario').click(); await pc.getByTestId('nav-authors').click(); await settings(pc).click(); await pc.getByLabel('检查频率').selectOption('weekly'); await action(pc, 'settings-save').click(); assert.match(await pc.getByRole('alert').innerText(), /保存失败/); await pc.getByRole('button', { name: '取消', exact: true }).click(); await settings(pc).click(); assert.equal(await pc.getByLabel('检查频率').inputValue(), 'daily');
}));
test('直接添加失败保留选择、过期在当前窗刷新、搜索与键盘焦点', () => setup(async (page, pc) => {
  const scenario = async value => { await page.locator('#scenario').selectOption(value); await page.locator('#apply-scenario').click(); await pc.getByTestId('signature').click(); await action(pc, 'merge-select').click(); await pc.locator('[data-select="en"]').check(); };
  await scenario('submit-error'); await action(pc, 'add').click(); assert.match(await pc.getByRole('alert').innerText(), /未改变/); assert.equal(await pc.locator('[data-select="en"]').isChecked(), true);
  await pc.getByRole('button', { name: '关闭', exact: true }).press('Escape'); assert.equal(await action(pc, 'merge-select').evaluate(e => e === document.activeElement), true);
  await scenario('stale'); const first = pc.getByRole('button', { name: '关闭', exact: true }); await first.focus(); await first.press('Shift+Tab'); assert.equal(await action(pc, 'add').evaluate(e => e === document.activeElement), true); await action(pc, 'add').press('Tab'); assert.equal(await first.evaluate(e => e === document.activeElement), true); await action(pc, 'add').click(); assert.match(await pc.getByRole('alert').innerText(), /资料已更新/); assert.equal(await pc.getByRole('dialog').getAttribute('aria-labelledby'), 'dialog-title');
  await action(pc, 'refresh-candidates').click(); await pc.getByLabel('搜索名字或别名').fill('不存在'); assert.match(await pc.getByRole('dialog').innerText(), /没有匹配/); await pc.getByLabel('搜索名字或别名').fill(''); assert.equal(await pc.locator('[data-select="en"]').isChecked(), true);
  await action(pc, 'add').click(); assert.equal(await pc.getByRole('dialog').count(), 0);
}));
