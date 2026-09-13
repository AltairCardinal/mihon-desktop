const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const url = 'file://' + path.resolve(__dirname, 'device.html').replace(/\\/g, '/');
async function seed(page, android) {
  await page.goto(url);
  if (android) { await page.getByTestId('preview-tools').locator('summary').click(); await page.getByTestId('platform-android').click(); }
  await page.getByTestId('preview-tools').locator('summary').click();
  await page.getByTestId('many-pending').click();
}
test('章节式多选：勾选、全选、反选、取消及批量确认/忽略', async () => {
 const browser = await chromium.launch({channel:'chrome',headless:true});
 try {
  for (const width of [1024,320]) {
   const p = await browser.newPage({viewport:{width,height:900}});
   await seed(p,width===320);
   assert.equal(await p.getByTestId('batch-select').count(),1);
   assert.equal(await p.locator('.pending-list > .pending-toolbar').count(),1, '多选条属于条目列表');
   const toolbar = p.locator('.pending-toolbar');
   let toolsBox = await toolbar.boundingBox();
   let firstBox = await p.locator('.native-confirmation').first().boundingBox();
   assert.ok(Math.abs(toolsBox.y + toolsBox.height - firstBox.y) < 2, '工具条紧接首条上方');
   await p.getByTestId('batch-select').click();
   assert.equal(await toolbar.getByTestId('selection-count').count(),1);
   assert.equal(await toolbar.getByTestId('batch-confirm').count(),1);
   assert.equal(await p.locator('.sync-panel-sheet > .batch-action-bar').count(),0);
   assert.equal(await p.locator('.sync-panel-sheet > .sheet-header h2').textContent(),'同步');
   await p.locator('[data-select-id]').nth(0).click();
   await p.locator('[data-select-id]').nth(1).click();
   assert.equal(await p.getByTestId('selection-count').textContent(),'已选 2 项');
   await p.getByTestId('selection-all').click();
   assert.equal(await p.getByTestId('selection-count').textContent(),'已选 120 项');
   await p.getByTestId('selection-invert').click();
   assert.equal(await p.getByTestId('selection-count').textContent(),'已选 0 项');
   assert.equal(await p.getByTestId('batch-confirm').isDisabled(),true);
   await p.locator('[data-select-id]').nth(0).click();
   await p.locator('[data-select-id]').nth(1).click();
   await p.getByTestId('batch-confirm').click();
   assert.equal(await p.getByRole('alertdialog').count(),1);
   assert.match(await p.getByRole('alertdialog').textContent(),/2 本漫画/);
   await p.keyboard.press('Escape');
   assert.equal(await p.locator('.native-confirmation').count(),120);
   assert.equal(await p.getByTestId('selection-count').textContent(),'已选 2 项');
   await p.getByTestId('batch-confirm').click();
   await p.getByTestId('batch-run').click();
   await p.getByTestId('ix-batch-dismiss').waitFor();
   assert.equal(await p.locator('.native-confirmation').count(),118);
   assert.match(await p.getByTestId('ix-batch-progress').textContent(),/2 项/);
   await p.getByTestId('batch-menu').click();
   await p.getByTestId('batch-all-ignore').click();
   assert.match(await p.getByRole('alertdialog').textContent(),/78 本漫画/);
   assert.match(await p.getByRole('alertdialog').textContent(),/40 位作者/);
   await p.getByTestId('batch-run').click();
   await p.getByTestId('ix-batch-dismiss').waitFor();
   assert.equal(await p.locator('.native-confirmation').count(),0);
   assert.equal(await p.getByTestId('library-sync-count').count(),0);
   const device=await p.evaluate(()=>window.__mihonSyncDemo.state.devices[window.__mihonSyncDemo.state.selectedDevice]);
   assert.equal(device.favorites.filter(id=>id.startsWith('demo-many-')).length,78);
   assert.equal(device.following.filter(id=>id.startsWith('demo-many-')).length,40);
   assert.equal(await p.getByTestId('selection-count').count(),0);
   assert.ok(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   await p.close();
  }
 } finally {await browser.close();}
});
test('长按范围选择及全部确认的快照不吞入新事项', async()=>{
 const b=await chromium.launch({channel:'chrome',headless:true});
 try{
 const p=await b.newPage({viewport:{width:1024,height:900}});
 await seed(p,false);
 const row=p.locator('.native-confirmation').first();await row.scrollIntoViewIfNeeded();
 const box=await row.locator('.row-copy').boundingBox();
 await p.mouse.move(box.x+8,box.y+8);await p.mouse.down();
 await p.waitForTimeout(550);await p.mouse.up();
 assert.equal(await p.getByTestId('selection-count').textContent(),'已选 1 项');
 const fifth=p.locator('.native-confirmation').nth(4);
 await fifth.scrollIntoViewIfNeeded();
 const fifthBox=await fifth.locator('.row-copy').boundingBox();
 await p.mouse.move(fifthBox.x+8,fifthBox.y+8);await p.mouse.down();
 await p.waitForTimeout(550);await p.mouse.up();
 assert.equal(await p.getByTestId('selection-count').textContent(),'已选 5 项');
 await p.getByTestId('selection-cancel').click();
 await p.getByTestId('batch-menu').click();await p.getByTestId('batch-all-confirm').click();
 assert.match(await p.getByRole('alertdialog').textContent(),/80 本漫画/);
 // New arrival while reviewing must remain unprocessed.
 await p.evaluate(()=>{
  const d=window.__mihonSyncDemo, id=d.state.selectedDevice;
  d.model.localFavorite(d.state,'phone-a','manga-new');
  d.model.syncDevice(d.state,'phone-a','manual');d.model.syncDevice(d.state,id,'manual');
  d.model.localUnfavorite(d.state,'phone-a','manga-new');
  d.model.syncDevice(d.state,'phone-a','manual');d.model.syncDevice(d.state,id,'manual');d.render();
 });
 await p.getByTestId('batch-run').click();
 await p.getByTestId('ix-batch-dismiss').waitFor();
 assert.equal(await p.locator('.native-confirmation').count(),1);
 assert.equal(await p.getByTestId('library-sync-count').textContent(),'1');
 const remaining=await p.evaluate(()=>window.__mihonSyncDemo.state.devices['desktop-b'].confirmations[0].objectId);
 assert.equal(remaining,'manga-new');
 await p.getByTestId('batch-select').click();
 await p.getByTestId('selection-all').click();
 await p.getByTestId('batch-ignore').click();
 // A remote re-add invalidates the old cancellation while review is open.
 await p.evaluate(()=>{
  const d=window.__mihonSyncDemo;
  d.model.localFavorite(d.state,'phone-a','manga-new');
  d.model.syncDevice(d.state,'phone-a','manual');
  d.model.syncDevice(d.state,'desktop-b','manual');d.render();
 });
 await p.getByTestId('batch-run').click();
 await p.getByTestId('ix-batch-dismiss').waitFor();
 assert.equal(await p.locator('.native-confirmation').count(),0);
 assert.match(await p.getByTestId('ix-batch-progress').textContent(),/跳过 1 项/);
 assert.equal(await p.evaluate(()=>window.__mihonSyncDemo.state.devices['desktop-b'].favorites.includes('manga-new')),true);
 await p.close();
 }finally{await b.close();}
});
