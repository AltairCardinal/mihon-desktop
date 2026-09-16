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
async function merge(f) {
  await f.getByTestId('signature').click(); await action(f, 'merge-select').click();
  await action(f, 'merge-preview').click(); await action(f, 'commit').click();
}
test('跨插件闭环：历史归集、同一来源版本、共用关注、文字选择与同名独立', () => setup(async (page, pc, phone) => {
  await merge(pc);
  assert.match(await pc.locator('.hero').innerText(), /3 部作品 · 3 个来源版本/);
  await action(pc, 'sources').click(); await pc.locator('#link-select').check();
  await action(pc, 'evidence').click(); await action(pc, 'sources-return').click();
  await action(pc, 'link-preview').click(); await action(pc, 'commit').click();
  assert.match(await pc.locator('.hero').innerText(), /3 部作品 · 4 个来源版本/);
  await pc.locator('[data-version="md"]').click(); await pc.getByTestId('signature').click();
  assert.equal(await pc.getByTestId('author-name').innerText(), '冈本伦');
  await pc.getByTestId('follow').click(); await action(pc, 'commit').click();
  assert.equal(await pc.getByTestId('follow').innerText(), '关注作者');
  await pc.getByTestId('follow').click();
  await action(pc, 'pending').click(); await pc.locator('[data-pending="t1"]').check();
  await action(pc, 'text-preview').click(); await action(pc, 'commit').click();
  await action(pc, 'pending').click();
  assert.equal(await pc.locator('[data-pending]').count(), 1);
  await action(pc, 'reject-preview').click(); await action(pc, 'commit').click();
  await action(pc, 'pending').click(); await action(pc, 'distinct-preview').click(); await action(pc, 'commit').click();
  assert.doesNotMatch(await pc.locator('.content').innerText(), /海边手记/);
  await pc.getByTestId('nav-authors').click(); assert.equal(await pc.locator('[data-author]').count(), 2);
  await phone.getByTestId('signature').click(); assert.equal(await phone.locator('[data-author]').count(), 3);
}));
test('窄屏取消与焦点、设备隔离、场景错误与原子失败', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check();
  await phone.getByTestId('signature').click();
  const first = phone.getByRole('dialog').locator('button').first();
  await first.focus(); await first.press('Shift+Tab');
  assert.equal(await action(phone, 'merge-select').evaluate(e => e === document.activeElement), true);
  await action(phone, 'merge-select').press('Tab');
  assert.equal(await first.evaluate(e => e === document.activeElement), true);
  await first.press('Escape');
  assert.equal(await phone.getByTestId('signature').evaluate(e => e === document.activeElement), true);
  await page.locator('#target').selectOption('windows'); await page.locator('#scenario').selectOption('submit-error'); await page.locator('#apply-scenario').click();
  await pc.getByTestId('signature').click(); await action(pc, 'merge-select').click(); await action(pc, 'merge-preview').click(); await action(pc, 'commit').click();
  assert.match(await pc.getByRole('alert').innerText(), /未改变任何/);
  await pc.getByRole('button', { name: '取消', exact: true }).click(); await pc.getByTestId('signature').click(); assert.equal(await pc.locator('[data-author]').count(), 3);
  await page.locator('#scenario').selectOption('sync'); await page.locator('#apply-scenario').click();
  await pc.getByTestId('signature').click(); await action(pc, 'merge-select').click(); await action(pc, 'merge-preview').click(); assert.equal(await action(pc, 'commit').isDisabled(), true);
  await page.locator('#target').selectOption('android'); await page.locator('#scenario').selectOption('confirmed'); await page.locator('#apply-scenario').click();
  await phone.getByTestId('author-name').waitFor(); await action(phone, 'pending').click();
  const overflow = await phone.locator('body').evaluate(e => e.scrollWidth > innerWidth);
  assert.equal(overflow, false);
  await page.locator('#theme').selectOption('light');
  assert.equal(await phone.locator('body').getAttribute('class'), 'device theme-light');
  assert.equal(await pc.getByRole('dialog').count(), 1);
}));
test('场景恢复：预览过期刷新、取证重试、空状态、卸载保留与目标重置', () => setup(async (page, pc, phone) => {
  async function scenario(value) { await page.locator('#scenario').selectOption(value); await page.locator('#apply-scenario').click(); }
  await scenario('stale');
  await pc.getByTestId('signature').click(); await action(pc, 'merge-select').click(); await action(pc, 'merge-preview').click(); await action(pc, 'commit').click();
  assert.match(await pc.getByRole('alert').innerText(), /预览已过期/);
  await action(pc, 'refresh-preview').click(); await action(pc, 'commit').click(); await pc.getByTestId('author-name').waitFor();
  await scenario('evidence-error'); await action(pc, 'sources').click(); await action(pc, 'retry-evidence').click(); await pc.locator('#link-select').waitFor();
  await scenario('loading'); await action(pc, 'sources').click(); assert.match(await pc.getByRole('dialog').innerText(), /正在获取/); await pc.locator('#link-select').waitFor();
  await scenario('empty'); await action(pc, 'sources').click(); assert.match(await pc.getByRole('dialog').innerText(), /暂无其他/);
  await scenario('unavailable'); await pc.getByText('漫画柜插件不可用', { exact: false }).waitFor(); assert.equal(await pc.getByTestId('follow').innerText(), '已关注');
  await page.locator('#reset').click(); await pc.getByTestId('signature').click(); assert.equal(await pc.locator('[data-author]').count(), 3);
  await phone.getByTestId('signature').click(); assert.equal(await phone.locator('[data-author]').count(), 3);
}));
test('审阅修复：同步绑定保护、关注失败、蒙层取消与真实刷新预览', () => setup(async (page, pc) => {
  const scenario = async value => { await page.locator('#scenario').selectOption(value); await page.locator('#apply-scenario').click(); await pc.getByTestId('signature').waitFor(); };
  await scenario('sync'); await pc.getByTestId('signature').click(); await pc.locator('[data-author="a"]').click();
  await action(pc, 'sources').click(); await pc.locator('#link-select').check(); await action(pc, 'link-preview').click();
  assert.equal(await action(pc, 'commit').isDisabled(), true);
  await pc.getByRole('button', { name: '关闭', exact: true }).click(); await action(pc, 'pending').click();
  await pc.locator('[data-pending="t1"]').check(); await action(pc, 'text-preview').click();
  assert.equal(await action(pc, 'commit').isDisabled(), true);
  await scenario('submit-error'); await pc.getByTestId('signature').click(); await pc.locator('[data-author="a"]').click(); await pc.getByTestId('follow').click();
  assert.equal(await pc.getByTestId('follow').innerText(), '关注作者');
  assert.match(await pc.getByRole('status').innerText(), /提交失败/);
  await scenario('history'); await pc.getByTestId('signature').click();
  await pc.locator('.modal-backdrop').click({ position: { x: 3, y: 3 } });
  assert.equal(await pc.getByRole('dialog').count(), 0);
  assert.equal(await pc.getByTestId('signature').evaluate(e => e === document.activeElement), true);
  await scenario('stale'); await pc.getByTestId('signature').click(); await action(pc, 'merge-select').click(); await action(pc, 'merge-preview').click();
  assert.equal(await pc.locator('#merge-follow').isChecked(), true);
  await action(pc, 'commit').click(); await action(pc, 'refresh-preview').click();
  assert.equal(await pc.locator('#merge-follow').isChecked(), false);
  assert.match(await pc.getByRole('dialog').innerText(), /0 个已关注/);
}));
test('先关联再部分归集：预览正确列出作品版本、关注范围与保留档案', () => setup(async (page, pc) => {
  await pc.getByTestId('signature').click(); await pc.locator('[data-author="a"]').click();
  await action(pc, 'sources').click(); await pc.locator('#link-select').check(); await action(pc, 'link-preview').click(); await action(pc, 'commit').click();
  await action(pc, 'merge-select').click(); await pc.locator('[data-merge="b"]').uncheck(); await action(pc, 'merge-preview').click();
  assert.match(await pc.getByRole('dialog').innerText(), /2 部作品、3 个来源版本/);
  assert.match(await pc.getByRole('dialog').innerText(), /0 个已关注，2 个未关注/);
  assert.equal(await pc.locator('#merge-follow').isChecked(), false);
  await pc.locator('#retain').selectOption('c'); await action(pc, 'commit').click();
  assert.match(await pc.locator('.hero').innerText(), /2 部作品 · 3 个来源版本/);
  assert.equal(await pc.getByTestId('follow').innerText(), '关注作者');
  await pc.getByTestId('nav-authors').click(); assert.equal(await pc.locator('[data-author]').count(), 2);
  await pc.locator('[data-author="b"]').click(); assert.equal(await pc.getByTestId('follow').innerText(), '已关注');
}));
