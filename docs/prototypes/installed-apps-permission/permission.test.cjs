const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function openDemo(run) {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
  const errors = [];
    const remoteRequests = [];
    const failedRequests = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => { if (/^https?:/.test(request.url())) remoteRequests.push(request.url()); });
    page.on('requestfailed', request => failedRequests.push(request.url()));
    await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href);
    const phone = page.frameLocator('#preview-android');
    const pc = page.frameLocator('#preview-windows');
    await phone.getByRole('tab', { name: '图源', exact: true }).waitFor();
    await run(page, phone, pc);
    assert.deepEqual(errors, []);
    assert.deepEqual(remoteRequests, [], 'The prototype must remain entirely local');
    assert.deepEqual(failedRequests, [], 'Shared shell assets must resolve in the sibling prototype');
  } finally { await browser.close(); }
}

test('未授权 → 设置授权 → 自动读取 → 冷启动保留；Windows 与本地图源不受影响', () => openDemo(async (page, phone, pc) => {
  const banner = phone.getByTestId('permission-required');
  await banner.waitFor({ timeout: 3000 });
  assert.ok((await banner.innerText()).includes('需要允许“获取已安装应用列表”权限，否则无法读取已安装的插件列表'));
  assert.equal(await phone.getByTestId('permission-system-dialog').count(), 0);
  assert.equal(await pc.getByTestId('permission-required').count(), 0);
  await phone.getByText('本地图源', { exact: true }).waitFor();
  await phone.getByText('私有示例源', { exact: true }).waitFor();
  await phone.getByTestId('permission-get').click();
  await phone.getByRole('dialog', { name: '系统权限设置（模拟）' }).waitFor();
  await phone.getByTestId('permission-allow').click();
  await phone.getByText('绅士漫画', { exact: true }).waitFor();
  await phone.getByText('拷贝漫画', { exact: true }).waitFor();
  assert.equal(await banner.count(), 0);
  await page.getByTestId('cold-start').click();
  await phone.getByText('绅士漫画', { exact: true }).waitFor();
  assert.equal(await banner.count(), 0);
  assert.equal(await pc.getByText('Mihon 演示源', { exact: true }).count(), 1);
}));

async function scene(page, name) {
  await page.locator('#permission-scene').selectOption(name);
  await page.getByTestId('show-permission-scene').click();
}

test('拒绝与 Escape 返回：不循环弹窗，只在图源页保留提醒', () => openDemo(async (page, phone) => {
  await phone.getByTestId('permission-get').click();
  await phone.getByTestId('permission-back').click();
  await phone.getByTestId('permission-required').waitFor();
  assert.ok((await phone.getByTestId('permission-required').innerText()).includes('尚未开放权限'));
  assert.equal(await phone.getByTestId('permission-system-dialog').count(), 0);
  await phone.getByRole('tab', { name: '作者', exact: true }).click();
  assert.equal(await phone.getByTestId('permission-required').count(), 0);
  await phone.getByRole('tab', { name: '图源', exact: true }).click();
  await phone.getByTestId('permission-get').click();
  await phone.getByTestId('permission-system-dialog').waitFor();
  await page.keyboard.press('Escape');
  await phone.getByTestId('permission-required').waitFor();
  await page.getByTestId('cold-start').click();
  await phone.getByTestId('permission-required').waitFor();
  assert.equal(await phone.getByTestId('permission-system-dialog').count(), 0);
}));

test('无需额外权限与系统撤销：不能把权限状态同步给 Windows', () => openDemo(async (page, phone, pc) => {
  await scene(page, 'unsupported');
  await phone.getByText('绅士漫画', { exact: true }).waitFor();
  assert.equal(await phone.getByTestId('permission-get').count(), 0);
  await scene(page, 'granted');
  await phone.getByText('拷贝漫画', { exact: true }).waitFor();
  await scene(page, 'revoked');
  await phone.getByTestId('permission-required').waitFor();
  assert.equal(await phone.getByText('绅士漫画', { exact: true }).count(), 0);
  await phone.getByText('本地图源', { exact: true }).waitFor();
  assert.equal(await pc.getByTestId('permission-required').count(), 0);
}));

test('权限检查失败、设置跳转失败和读取失败分别反馈并可重试', () => openDemo(async (page, phone) => {
  await scene(page, 'check-failed');
  await phone.getByTestId('permission-check-error').waitFor();
  assert.equal(await phone.getByTestId('permission-required').count(), 0);
  await phone.getByTestId('permission-recheck').click();
  await phone.getByTestId('permission-required').waitFor();
  await scene(page, 'settings-unavailable');
  await phone.getByTestId('permission-get').click();
  assert.ok((await phone.getByTestId('permission-required').innerText()).includes('无法打开权限设置'));
  assert.equal(await phone.getByTestId('permission-system-dialog').count(), 0);
  await phone.getByTestId('permission-recheck').click();
  await phone.getByTestId('permission-required').waitFor();
  await scene(page, 'scan-failed');
  await phone.getByTestId('permission-scan-error').waitFor();
  assert.equal(await phone.getByTestId('permission-get').count(), 0);
  await phone.getByTestId('permission-retry-scan').click();
  await phone.getByText('绅士漫画', { exact: true }).waitFor();
  assert.equal(await phone.getByTestId('permission-scan-error').count(), 0);
}));

test('320px 手机、双主题与键盘模态：不横向溢出，焦点留在当前设备', () => openDemo(async (page, phone, pc) => {
  await phone.getByTestId('permission-required').waitFor();
  await page.locator('#preview-android').evaluate(frame => { frame.style.width = '320px'; });
  for (const theme of ['light', 'dark']) {
    await page.getByTestId('theme-' + theme).click();
    await phone.getByTestId('permission-get').click();
    const dialog = phone.getByTestId('permission-system-dialog');
    await dialog.waitFor();
    for (let i = 0; i < 4; i++) {
      await page.keyboard.press('Tab');
      assert.equal(await dialog.evaluate(el => el.contains(el.ownerDocument.activeElement)), true);
    }
    assert.equal(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    assert.equal(await phone.locator('body').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    await page.keyboard.press('Escape');
    await phone.getByTestId('permission-get').waitFor();
  }
  await pc.getByTestId('nav-browse').click();
  assert.equal(await page.locator('#preview-windows').evaluate(el => el === document.activeElement), true);
}));
