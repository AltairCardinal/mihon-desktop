const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function setup(t) {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  t.after(() => browser.close());
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href);
  const pc = page.frameLocator('#preview-windows');
  const phone = page.frameLocator('#preview-android');
  await pc.getByTestId('sync-open').waitFor();
  await phone.getByTestId('sync-open').waitFor();
  return { page, pc, phone };
}

// Independent acceptance: expected values come from the page contract/source, not trial.js.
test('oracle S1/S3/S4：主题更新不抢外层焦点、不清双端局部状态', async t => {
  const { page, pc, phone } = await setup(t);
  await phone.getByTestId('search-open').click();
  await phone.getByTestId('library-query').fill('星');
  await pc.getByTestId('sync-open').click();
  await pc.getByTestId('sync-settings').click();
  await pc.getByTestId('startup-toggle').click();
  const toggle = page.getByTestId('theme-toggle');
  await toggle.click();
  await pc.locator('body.theme-light').waitFor();
  await phone.locator('body.theme-light').waitFor();
  assert.equal(await toggle.evaluate(el => el === document.activeElement), true, '设备重绘不能抢主题工具栏焦点');
  assert.equal(await phone.getByTestId('library-query').inputValue(), '星');
  assert.equal(await pc.getByTestId('startup-toggle').isChecked(), false);
  await pc.getByTestId('periodic-toggle').focus();
  await page.setViewportSize({ width: 1100, height: 850 });
  assert.equal(await pc.getByTestId('periodic-toggle').evaluate(el => el === document.activeElement), true);
  for (const key of ['Tab', 'Shift+Tab']) {
    for (let index = 0; index < 8; index++) {
      await page.keyboard.press(key);
      assert.equal(await pc.getByRole('dialog').evaluate(el => el.contains(document.activeElement)), true, `${key} must stay inside the modal`);
    }
  }
  await pc.getByTestId('periodic-toggle').press('Escape');
  assert.equal(await pc.getByTestId('sync-title').count(), 1);
  await pc.getByTestId('sync-settings').click();
  assert.equal(await pc.getByTestId('startup-toggle').isChecked(), false);
  await pc.getByTestId('sync-close').click();
  assert.equal(await pc.getByTestId('sync-open').evaluate(el => el === document.activeElement), true);
  assert.equal(await phone.getByTestId('library-query').inputValue(), '星');
});

test('oracle V1/V2：实际封面几何与两种主题弹层源码颜色', async t => {
  const { page, pc, phone } = await setup(t);
  for (const [frame, ratio] of [[pc, 7 / 10], [phone, 2 / 3]]) {
    const box = await frame.locator('.book-cover').first().boundingBox();
    assert.ok(Math.abs(box.width / box.height - ratio) < 0.005, 'Cover geometry must match its platform source');
    assert.deepEqual(await frame.locator('body').evaluate(el => {
      const style = getComputedStyle(el);
      return [style.backgroundColor, style.color];
    }), ['rgb(27, 27, 31)', 'rgb(227, 226, 230)']);
    assert.equal(await frame.locator('.native-nav-item.is-selected').evaluate(el => getComputedStyle(el).color), 'rgb(176, 198, 255)');
    await frame.getByTestId('sync-open').click();
    const color = await frame.getByRole('dialog').evaluate(el => getComputedStyle(el).backgroundColor);
    assert.equal(color, 'rgb(41, 39, 48)', 'dark surfaceContainerHigh');
  }
  await page.getByTestId('theme-toggle').click();
  for (const frame of [pc, phone]) {
    await frame.locator('body.theme-light').waitFor();
    assert.equal(await frame.getByRole('dialog').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(252, 247, 255)');
    assert.deepEqual(await frame.locator('body').evaluate(el => {
      const style = getComputedStyle(el);
      return [style.backgroundColor, style.color];
    }), ['rgb(254, 251, 255)', 'rgb(27, 27, 31)']);
    assert.equal(await frame.locator('.native-nav-item.is-selected').evaluate(el => getComputedStyle(el).color), 'rgb(0, 88, 202)');
  }
});

test('oracle wiring：阻断真实开关事件时同一行为断言必须失败', async t => {
  const { pc } = await setup(t);
  await pc.getByTestId('sync-open').click();
  await pc.getByTestId('sync-settings').click();
  const control = pc.getByTestId('startup-toggle');
  const toggleAndReopen = async () => {
    await control.click();
    await pc.getByTestId('sync-back').click();
    await pc.getByTestId('sync-settings').click();
    assert.equal(await control.isChecked(), false, '开关必须真正更新会话偏好');
  };
  await pc.locator('body').evaluate(() => {
    window.__oracleBlock = event => {
      if (event.target.matches('[data-testid="startup-toggle"]')) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    };
    document.addEventListener('click', window.__oracleBlock, true);
  });
  await assert.rejects(toggleAndReopen, { code: 'ERR_ASSERTION' });
  await pc.locator('body').evaluate(() => {
    document.removeEventListener('click', window.__oracleBlock, true);
    delete window.__oracleBlock;
  });
  await toggleAndReopen();
});
