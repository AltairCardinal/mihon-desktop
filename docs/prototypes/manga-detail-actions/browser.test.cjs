const {test, before, after} = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const {pathToFileURL} = require('node:url');
const verifySortSlot = require('./edit-review.test.cjs');
const {chromium} = require(process.env.PLAYWRIGHT_CORE_PATH || 'playwright-core');

const screenshots = process.env.PROTOTYPE_SCREENSHOTS || path.join(require('node:os').tmpdir(), 'mihon-detail-actions-prototype-20261114');
let browser;
before(async () => { browser = await chromium.launch({channel:'chrome', headless:true}); fs.mkdirSync(screenshots, {recursive:true}); });
after(async () => { await browser?.close(); });

async function fixture(t) {
  const page = await browser.newPage({viewport:{width:1480,height:1060}, reducedMotion:'reduce'});
  const errors = [];
  const remote = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (/^https?:/.test(request.url())) remote.push(request.url()); });
  t.after(async () => { await page.close(); assert.deepEqual(errors, []); assert.deepEqual(remote, []); });
  await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href);
  const pc = page.frameLocator('#preview-windows');
  const phone = page.frameLocator('#preview-android');
  await pc.locator('[data-testid=action-library]').waitFor();
  await phone.locator('[data-testid=action-library]').waitFor();
  await page.locator('#theme').click();
  return {page, pc, phone};
}
async function wheel(page, frame, delta, quiet = true) {
  if (quiet) await page.waitForTimeout(350);
  const box = await frame.locator('.action-deck').boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.wheel(0, delta);
  await page.waitForTimeout(50);
}
async function custom(page, frame) {
  const target = frame.locator('[data-testid=action-library]');
  await target.focus();
  await page.keyboard.press('Shift+F10');
  await frame.locator('dialog[open]').waitFor();
}
async function drag(page, frame, direction) {
  const box = await frame.locator('.action-deck').boundingBox();
  const start = direction === 'left' ? .82 : .18;
  const end = 1 - start;
  await page.mouse.move(box.x + box.width * start, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * end, box.y + box.height / 2, {steps:8});
  await page.mouse.up();
}

async function reorder(page, frame, from, to) {
  const source = await frame.locator(`[data-choice=${from}]`).boundingBox();
  const target = await frame.locator(`[data-choice=${to}]`).boundingBox();
  await page.mouse.move(source.x + 25, source.y + source.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(600);
  await page.mouse.move(target.x + target.width / 2, target.y + target.height / 2, {steps:12});
  await page.mouse.up();
}
async function visibleActions(frame) {
  return frame.locator('.action-deck').evaluate(deck => {
    const bounds = deck.getBoundingClientRect();
    return [...deck.querySelectorAll('[data-action]')].filter(item => {
      const rect = item.getBoundingClientRect();
      return rect.left >= bounds.left-1 && rect.right <= bounds.right+1;
    }).map(item => item.dataset.action);
  });
}

test('Desktop arrows and wheel page through the rail with centered page dots', async t => {
  const {page,pc,phone} = await fixture(t);
  const rail = pc.locator('.action-deck');
  const prev = pc.getByRole('button', {name:'上一页'});
  const next = pc.getByRole('button', {name:'下一页'});
  const dots = pc.locator('.page-dot');
  const scroll = () => rail.evaluate(el => el.scrollLeft);
  assert.equal(await next.isVisible(), true);
  assert.equal(await prev.isVisible(), true);
  assert.equal(await prev.isDisabled(), true);
  assert.equal(await dots.count(), 2);
  assert.equal(await dots.first().getAttribute('aria-current'), 'page');
  const arrow = await next.boundingBox();
  const deck = await rail.boundingBox();
  const action = await pc.locator('[data-action=library]').boundingBox();
  const meter = await pc.locator('.page-indicator').boundingBox();
  assert.ok(arrow.x >= deck.x + deck.width && arrow.width < action.width);
  assert.equal(arrow.height, action.height);
  assert.ok(Math.abs((meter.x + meter.width / 2) - (deck.x + deck.width / 2)) < 1);
  await next.click();
  assert.equal(await scroll(), await rail.evaluate(el => el.scrollWidth - el.clientWidth));
  assert.equal(await next.isVisible(), true);
  assert.equal(await next.isDisabled(), true);
  assert.equal(await prev.isEnabled(), true);
  assert.equal(await dots.last().getAttribute('aria-current'), 'page');
  await prev.click();
  assert.equal(await scroll(), 0);
  await wheel(page,pc,7);
  assert.equal(await dots.last().getAttribute('aria-current'), 'page');
  await wheel(page,pc,-1000);
  assert.equal(await scroll(), 0);
  assert.equal(await phone.getByRole('button',{name:'下一页'}).count(), 0);
});

test('Android rail continuously scrolls to its last Sort slot and back in one held drag', async t => {
  const {page,pc,phone} = await fixture(t);
  for (const frame of [pc,phone]) {
    assert.equal(await frame.getByText(/(滑动浏览|滚轮翻页).*长按定制/).count(), 0);
    const last = frame.locator('.action-track > button').last();
    assert.equal(await last.textContent(), '排序');
    assert.equal(await last.getAttribute('data-testid'), 'customize');
  }
  const rail = phone.locator('.action-deck');
  const size = await rail.boundingBox();
  const meter = await phone.locator('.scroll-meter').boundingBox();
  assert.ok(Math.abs((meter.x + meter.width / 2) - (size.x + size.width / 2)) < 1);
  assert.equal(await phone.locator('.page-dot').count(), 0);
  await drag(page,phone,'left');
  const before = await rail.evaluate(el => el.scrollLeft);
  assert.ok(before > 0);
  const x = size.x + size.width * .82, y = size.y + size.height / 2;
  await page.mouse.move(x,y);
  await page.mouse.down();
  await page.mouse.move(x-20,y);
  assert.ok(Math.abs(await rail.evaluate(el => el.scrollLeft) - before - 20) < 2);
  await page.mouse.move(x-size.width*.64,y,{steps:8});
  const end = await rail.evaluate(el => el.scrollWidth - el.clientWidth);
  assert.equal(await rail.evaluate(el => el.scrollLeft), end);
  const sort = phone.getByRole('button',{name:'排序',exact:true});
  const sortBox = await sort.boundingBox();
  assert.ok(Math.abs(sortBox.x + sortBox.width - size.x - size.width) < 1);
  assert.equal(await sort.evaluate(el => getComputedStyle(el,'::before').width), '1px');
  await page.mouse.move(x-20,y,{steps:8});
  assert.ok(await rail.evaluate(el => el.scrollLeft) < end-150);
  await page.mouse.up();
  await drag(page,phone,'right');
  assert.equal(await rail.evaluate(el => el.scrollLeft), 0);
});

test('Sort opens on click but holding it has no action on either preview', async t => {
  const {page,pc,phone} = await fixture(t);
  await wheel(page,pc,120);
  await drag(page,phone,'left');
  await drag(page,phone,'left');
  for (const frame of [pc,phone]) {
    const sort = frame.getByRole('button',{name:'排序',exact:true});
    await verifySortSlot(sort);
    const box = await sort.boundingBox();
    await page.mouse.move(box.x + box.width / 2,box.y + box.height / 2);
    await page.mouse.down();
    await page.waitForTimeout(620);
    assert.equal(await frame.locator('dialog[open]').count(), 0);
    await page.mouse.up();
    assert.equal(await frame.locator('dialog[open]').count(), 0);
    await sort.click();
    assert.equal(await frame.locator('#modal-title').innerText(), '排序');
    await page.keyboard.press('Escape');
    assert.equal(await sort.evaluate(el => el === el.ownerDocument.activeElement), true);
  }
});

test('Review entry loads revisioned device documents and scripts on both platforms', async t => {
  const {page,pc,phone} = await fixture(t);
  for(const id of ['preview-windows','preview-android']) assert.ok((await page.locator('#'+id).getAttribute('src')).includes('v=action-sort-14'));
  for(const frame of [pc,phone]) assert.equal(await frame.locator('script[src]').getAttribute('src'),'app.js?v=action-sort-14');
  assert.ok((await page.locator('.review-foot').textContent()).includes('修订 14'));
});

test('Holding and dragging on the rail sorts in place and applies only on release', async t => {
  const {page,pc,phone} = await fixture(t);
  for (const frame of [pc,phone]) {
    const from = await frame.locator('[data-action=library]').boundingBox();
    const to = await frame.locator('[data-action=tracking]').boundingBox();
    await page.mouse.move(from.x+from.width/2,from.y+from.height/2);
    await page.mouse.down();
    await page.waitForTimeout(620);
    assert.equal(await frame.locator('dialog[open]').count(),0);
    await page.mouse.move(to.x+to.width/2,to.y+to.height/2,{steps:9});
    assert.deepEqual(await visibleActions(frame),['interval','tracking','library','web']);
    await page.mouse.up();
    assert.equal(await frame.locator('dialog[open]').count(),0);
    assert.deepEqual(await visibleActions(frame),['interval','tracking','library','web']);
    assert.equal(await frame.locator('[data-testid=action-library]').textContent(),'收藏');
    await custom(page,frame);
    assert.deepEqual(await frame.locator('[data-preview] [data-action]').evaluateAll(items => items.map(item => item.dataset.action)),['interval','tracking','library','web']);
    await page.keyboard.press('Escape');
  }
});

test('Long-press reorder can move collection and changes only the saved draft', async t => {
  const {page,pc,phone} = await fixture(t);
  await custom(page,pc);
  await reorder(page,pc,'library','interval');
  const preview = () => pc.locator('[data-preview] [data-action]').evaluateAll(items => items.map(el => el.dataset.action));
  assert.deepEqual(await preview(),['interval','library','tracking','web']);
  await pc.locator('button[data-close]').last().click();
  await custom(page,pc);
  assert.deepEqual(await preview(),['library','interval','tracking','web']);
  await reorder(page,pc,'library','web');
  assert.deepEqual(await preview(),['interval','tracking','web','library']);
  await pc.locator('[data-save]').click();
  assert.deepEqual(await pc.locator('.action-deck [data-action]').evaluateAll(items => items.slice(0,4).map(el => el.dataset.action)),['interval','tracking','web','library']);
  assert.deepEqual(await phone.locator('.action-deck [data-action]').evaluateAll(items => items.slice(0,4).map(el => el.dataset.action)),['library','interval','tracking','web']);
  await custom(page,pc);
  assert.equal(await pc.locator('[data-toggle=library]').isDisabled(),true);
  await pc.locator('[data-handle=library]').focus();
  await page.keyboard.press('Alt+ArrowUp');
  assert.deepEqual(await preview(),['interval','tracking','library','web']);
  await page.keyboard.press('Escape');
});

test('Desktop scroll ignores mouse drag, outside scrolling and modified wheel input', async t => {
  const {page,pc} = await fixture(t);
  await page.screenshot({path:path.join(screenshots, '01-light-overview.png'), fullPage:true});
  const scroll = () => pc.locator('.action-deck').evaluate(el => el.scrollLeft);
  const start = await scroll();
  await wheel(page,pc,120);
  const scrolled = await scroll();
  assert.ok(scrolled > start+110);
  await drag(page,pc,'left');
  assert.equal(await scroll(),scrolled);
  const chapters = await pc.locator('.chapter-list').boundingBox();
  await page.mouse.move(chapters.x+40,chapters.y+40);
  await page.mouse.wheel(0,400);
  assert.equal(await scroll(),scrolled);
  await pc.locator('.action-deck').dispatchEvent('wheel', {deltaY:120,ctrlKey:true});
  assert.equal(await scroll(),scrolled);
});

test('Collection is locked, maximum four, cancel and save are isolated', async t => {
  const {page,pc,phone} = await fixture(t);
  await custom(page,pc);
  assert.equal(await pc.locator('[data-toggle=library]').isDisabled(), true);
  // A real pointer can reach an aria-disabled option; it must still reject a fifth item.
  await pc.locator('[data-choice=delete]').click({force:true});
  assert.equal(await pc.locator('.selection-count').textContent(), '4 / 4');
  await pc.locator('[data-choice=web]').click();
  await pc.locator('button[data-close]').last().click();
  assert.deepEqual(await visibleActions(pc),['library','interval','tracking','web']);
  assert.equal(await pc.locator('[data-testid=action-library]').textContent(), '收藏');
  await custom(page,pc);
  for (const id of ['interval','tracking','web']) await pc.locator(`[data-choice=${id}]`).click();
  assert.equal(await pc.locator('.selection-count').textContent(), '1 / 4');
  await pc.locator('[data-save]').click();
  assert.deepEqual(await visibleActions(pc),['library']);
  assert.deepEqual(await visibleActions(phone),['library','interval','tracking','web']);
  assert.equal(await pc.locator('.page-dot').count(), 8);
  await wheel(page,pc,12);
  assert.deepEqual(await visibleActions(pc),['interval']);
  await wheel(page,pc,120,false);
  assert.deepEqual(await visibleActions(pc),['interval'],'wheel tail events cannot turn another page');
  await pc.getByRole('button',{name:'下一页'}).click();
  assert.deepEqual(await visibleActions(pc),['tracking']);
  await pc.getByRole('button',{name:'上一页'}).click();
  assert.deepEqual(await visibleActions(pc),['interval']);
  await page.locator('#sample').selectOption('letter');
  await pc.locator('.book-title').filter({hasText:'深蓝来信'}).waitFor();
  assert.deepEqual(await visibleActions(pc),['library']);
  await custom(page,pc);
  assert.equal(await pc.locator('[data-toggle=library]').getAttribute('aria-checked'), 'true');
  await page.keyboard.press('Escape');
  assert.equal(await pc.locator('dialog').getAttribute('open'), null);
});

test('Deletion rule is three-state, cancelable, scoped to each work and independent across devices', async t => {
  const {page,pc,phone} = await fixture(t);
  await wheel(page,pc,120);
  await pc.locator('[data-testid=action-delete]').click();
  assert.equal(await pc.locator('[role=radio]').count(), 3);
  await pc.locator('[data-rule=on]').click();
  await pc.locator('[data-save]').click();
  assert.equal(await pc.locator('[data-testid=action-delete]').getAttribute('title'), '读完即删：开启');
  await pc.locator('[data-testid=action-delete]').click();
  await pc.locator('[data-rule=off]').click();
  await page.keyboard.press('Escape');
  assert.equal(await pc.locator('[data-testid=action-delete]').getAttribute('title'), '读完即删：开启');
  await drag(page,phone,'left');
  assert.equal(await phone.locator('[data-testid=action-delete]').getAttribute('title'), '读完即删：跟随全局');
  await page.locator('#sample').selectOption('letter');
  await pc.locator('.book-title').filter({hasText:'深蓝来信'}).waitFor();
  await wheel(page,pc,120);
  assert.equal(await pc.locator('[data-testid=action-delete]').getAttribute('title'), '读完即删：跟随全局');
  await page.locator('#sample').selectOption('journey');
  await pc.locator('.book-title').filter({hasText:'长旅之后'}).waitFor();
  await wheel(page,pc,120);
  assert.equal(await pc.locator('[data-testid=action-delete]').getAttribute('title'), '读完即删：开启');
  await pc.locator('[data-testid=action-delete]').click();
  await page.screenshot({path:path.join(screenshots, '03-deletion-rule.png'),fullPage:true});
});

test('Android swipe, narrow layout, modal focus and theme preserve state', async t => {
  const {page,pc,phone} = await fixture(t);
  const scroll = () => phone.locator('.action-deck').evaluate(el => el.scrollLeft);
  const start = await scroll();
  await drag(page,phone,'left');
  const more = await scroll();
  assert.ok(more > start+100);
  await drag(page,phone,'right');
  assert.ok(await scroll()<more-100,'returning from later actions reaches the earlier ones first');
  await drag(page,phone,'left');
  await drag(page,phone,'left');
  await phone.locator('[data-testid=customize]').click();
  await phone.locator('[data-choice=web]').click();
  await custom(page,pc);
  await pc.locator('[data-choice=web]').click();
  await page.locator('#theme').click();
  assert.equal(await phone.locator('.selection-count').textContent(), '3 / 4');
  assert.equal(await pc.locator('.selection-count').textContent(), '3 / 4');
  await page.locator('#narrow').check();
  await page.waitForTimeout(320);
  const phoneFrame = page.frames().find(f => f.url().includes('platform=android'));
  assert.equal(await phoneFrame.evaluate(() => innerWidth), 320);
  assert.equal(await phoneFrame.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  const saveBox = await phone.locator('[data-save]').boundingBox();
  const frameBox = await page.locator('#preview-android').boundingBox();
  assert.ok(saveBox.y + saveBox.height <= frameBox.y + frameBox.height);
  await pc.locator('[data-handle=tracking]').focus();
  const pcFrame = page.frames().find(f => f.url().includes('platform=desktop'));
  for (let i=0;i<14;i++) {
    await page.keyboard.press('Tab');
    assert.equal(await pcFrame.evaluate(() => document.querySelector('dialog').contains(document.activeElement)),true);
  }
  await page.screenshot({path:path.join(screenshots,'02-dark-customize.png'),fullPage:true});
  await page.keyboard.press('Escape');
  assert.equal(await pcFrame.evaluate(() => document.activeElement?.dataset.testid), 'action-library');
  assert.equal(await phone.locator('dialog[open]').count(),1);
  await phone.locator('[data-save]').click();
  assert.deepEqual(await visibleActions(phone),['library','interval','tracking']);
  assert.deepEqual(await visibleActions(pc),['library','interval','tracking','web']);
});

test('Android native touch scroll and long-press collection reorder work without toggling selection', async t => {
  const context = await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,reducedMotion:'reduce'});
  t.after(() => context.close());
  const page = await context.newPage();
  await page.goto(pathToFileURL(path.join(__dirname,'device.html')).href+'?platform=android');
  await page.locator('[data-testid=action-library]').waitFor();
  const cdp = await context.newCDPSession(page);
  const touch = async (type,x,y) => cdp.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchEnd'||type==='touchCancel'?[]:[{x,y,id:1}]});
  const rail = page.locator('.action-deck');
  const box = await rail.boundingBox();
  const initial = await rail.evaluate(el=>el.scrollLeft);
  await touch('touchStart',box.x+box.width*.85,box.y+40);
  for(let i=1;i<=10;i++) {
    await touch('touchMove',box.x+box.width*(.85-i*.06),box.y+40);
    await page.waitForTimeout(25);
  }
  assert.ok(await rail.evaluate(el=>el.scrollLeft)>initial+80);
  await touch('touchEnd');
  const sort = page.getByRole('button', {name:'排序',exact:true});
  const end = await rail.evaluate(el => el.scrollWidth - el.clientWidth);
  for (let swipe = 0; swipe < 2; swipe++) {
    await touch('touchStart',box.x+box.width*.85,box.y+40);
    for (let step = 1; step <= 6; step++) {
      await touch('touchMove',box.x+box.width*(.85-step*.11),box.y+40);
    }
    await touch('touchEnd');
  }
  assert.equal(await rail.evaluate(el => el.scrollLeft), end);
  const sortBox = await sort.boundingBox();
  await touch('touchStart',sortBox.x+sortBox.width/2,sortBox.y+sortBox.height/2);
  await page.waitForTimeout(620);
  await touch('touchEnd');
  assert.equal(await page.locator('dialog[open]').count(),0);
  await sort.tap();
  assert.equal(await page.locator('#modal-title').innerText(),'排序');
  const source = await page.locator('[data-handle=library]').boundingBox();
  const target = await page.locator('[data-choice=interval]').boundingBox();
  await touch('touchStart',source.x+source.width/2,source.y+source.height/2);
  await page.waitForTimeout(600);
  await touch('touchMove',target.x+target.width/2,target.y+target.height/2);
  await touch('touchEnd');
  assert.deepEqual(await page.locator('[data-preview] [data-action]').evaluateAll(items=>items.map(el=>el.dataset.action)),['interval','library','tracking','web']);
  assert.equal(await page.locator('[data-toggle=library]').getAttribute('aria-checked'),'true');
  await page.screenshot({path:path.join(screenshots,'05-android-reorder.png')});
  // A canceled drag restores the previous draft order.
  const nextSource = await page.locator('[data-handle=library]').boundingBox();
  const nextTarget = await page.locator('[data-choice=tracking]').boundingBox();
  await touch('touchStart',nextSource.x+nextSource.width/2,nextSource.y+nextSource.height/2);
  await page.waitForTimeout(600);
  await touch('touchMove',nextTarget.x+nextTarget.width/2,nextTarget.y+nextTarget.height/2);
  await touch('touchCancel');
  assert.deepEqual(await page.locator('[data-preview] [data-action]').evaluateAll(items=>items.map(el=>el.dataset.action)),['interval','library','tracking','web']);
  // A short viewport requires the drag to scroll the choice list at its edge.
  await page.setViewportSize({width:390,height:640});
  await page.locator('.modal-body').evaluate(el=>el.scrollTop=0);
  const dragStart = await page.locator('[data-handle=library]').boundingBox();
  const body = await page.locator('.modal-body').boundingBox();
  await touch('touchStart',dragStart.x+dragStart.width/2,dragStart.y+dragStart.height/2);
  await page.waitForTimeout(600);
  await touch('touchMove',body.x+body.width/2,body.y+body.height-8);
  await page.waitForTimeout(650);
  assert.ok(await page.locator('.modal-body').evaluate(el=>el.scrollTop)>40,'edge dragging scrolls to off-screen choices');
  const last = await page.locator('[data-choice=share]').boundingBox();
  await touch('touchMove',last.x+last.width/2,last.y+last.height/2);
  await touch('touchEnd');
  assert.deepEqual(await page.locator('[data-preview] [data-action]').evaluateAll(items=>items.map(el=>el.dataset.action)),['interval','tracking','web','library']);
  await page.locator('[data-save]').click();
  assert.deepEqual(await visibleActions(page),['interval','tracking','web','library']);
});
