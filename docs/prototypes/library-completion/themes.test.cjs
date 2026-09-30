const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const themePath = path.join(__dirname, 'themes.js');
const T = fs.existsSync(themePath) ? require(themePath) : {};
test('上游15个主题、完整角色及AMOLED遵循真实定义', () => {
  assert.equal(typeof T.scheme, 'function', '缺少实际主题解析与应用模块');
  assert.equal(T.catalog.length, 15);
  assert.equal(T.scheme('NORD', 'dark').secondary, '#81a1c1');
  assert.equal(T.scheme('NORD', 'dark').error, '#f2b8b5');
  assert.equal(T.scheme('DEFAULT', 'light').secondaryContainer, '#d9e2ff');
  assert.equal(T.scheme('MONOCHROME', 'dark').surfaceBright, '#ffffff');
  assert.equal(T.scheme('MONOCHROME', 'dark').surfaceTint, '#ffffff');
  assert.equal(T.scheme('TAKO', 'dark').surfaceTint, '#66577e');
  for (const id of ['MIDNIGHT_DUSK', 'YINYANG', 'YOTSUBA']) {
    assert.equal(T.scheme(id, 'dark').error, '#f2b8b5');
    assert.equal(T.scheme(id, 'light').error, '#b3261e');
  }
  assert.equal(T.scheme('NORD', 'dark', true).surfaceContainer, '#0c0c0c');
  assert.equal(T.scheme('NORD', 'dark', true).onSurface, '#ffffff');
  assert.equal(T.scheme('MONET', 'dark', true).surfaceContainer, '#211f26');
  assert.deepEqual(T.scheme('DEFAULT', 'light', true), T.scheme('DEFAULT', 'light'));
});
test('每项实际主题与归档上游角色一一对应且原始文件校验有效', () => {
  assert.equal(typeof T.scheme, 'function', '缺少实际主题解析与应用模块');
  const manifest = require('./upstream-themes/manifest.json');
  const crypto = require('node:crypto');
  for (const file of manifest.files) assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname, 'upstream-themes', file.local))).digest('hex'), file.sha256);
  for (const theme of T.catalog.filter(x => x.id !== 'MONET')) {
    const source = fs.readFileSync(path.join(__dirname, 'upstream-themes', theme.source), 'utf8');
    for (const mode of ['light', 'dark']) {
      const block = source.split(`override val ${mode}Scheme`)[1].split(/\n    \)/)[0];
      for (const match of block.matchAll(/(\w+)\s*=\s*Color\(0xFF([\da-fA-F]{6})\)/g)) assert.equal(T.scheme(theme.id, mode)[match[1]], '#' + match[2].toLowerCase(), `${theme.id} ${mode} ${match[1]}`);
    }
  }
});
test('真实双端主题选择、深浅、纯黑与重载保留主题且不重置局部状态', async () => {
  const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  try {
    const p = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    await p.goto(pathToFileURL(path.join(__dirname, 'index.html')).href);
    await p.waitForSelector('#preview-windows');
    assert.equal(await p.getByTestId('theme-select').count(), 1, '缺少真实主题选择入口');
    const w = p.frames().find(x => x.url().includes('platform=windows'));
    const a = p.frames().find(x => x.url().includes('platform=android'));
    await w.getByTestId('manga-A').click({ modifiers: ['Control'] });
    await a.getByTestId('manga-A').click();
    const before = await w.evaluate(() => ({ selected: [...window.demo.state.selected] }));
    const winNode = await w.getByTestId('manga-A').elementHandle();
    await w.getByTestId('panel-open').count();
    await p.getByTestId('theme-select').selectOption('NORD');
    for (const frame of [w, a]) await frame.waitForFunction(() => document.body.dataset.theme === 'NORD');
    assert.deepEqual(await w.evaluate(() => window.demo.state.selected), before.selected);
    assert.equal(await a.getByTestId('detail-back').count(), 1);
    for (const frame of [w, a]) assert.equal(await frame.evaluate(() => getComputedStyle(document.body).getPropertyValue('--secondary').trim()), '#81a1c1');
    assert.equal(await w.locator('.navigation .active .nav-icon').evaluate(e => getComputedStyle(e).backgroundColor), 'rgb(80, 98, 117)');
    assert.equal(await w.getByTestId('unread-A').evaluate(e => getComputedStyle(e).backgroundColor), 'rgb(129, 161, 193)');
    assert.equal(await w.locator('.badge.downloaded').first().evaluate(e => getComputedStyle(e).backgroundColor), 'rgb(94, 129, 172)');
    assert.equal(await winNode.evaluate(e => e === document.querySelector('[data-testid="manga-A"]')), true);
    await p.getByTestId('theme-toggle').click();
    for (const frame of [w, a]) await frame.waitForFunction(() => document.body.dataset.mode === 'light');
    await p.getByTestId('amoled-toggle').check();
    assert.equal(await w.evaluate(() => getComputedStyle(document.body).getPropertyValue('--surface').trim()), '#e5e9f0');
    await p.getByTestId('theme-toggle').click();
    for (const frame of [w, a]) await frame.waitForFunction(() => document.body.style.getPropertyValue('--surface') === '#000000');
    for (const theme of T.catalog) {
      await p.getByTestId('theme-select').selectOption(theme.id);
      for (const frame of [w, a]) {
        await frame.waitForFunction(id => document.body.dataset.theme === id, theme.id);
        assert.equal(await frame.evaluate(() => getComputedStyle(document.body).getPropertyValue('--primary').trim()), T.scheme(theme.id, 'dark', true).primary);
      }
    }
    await a.getByTestId('detail-overflow').click();
    await a.getByTestId('detail-notes').click();
    await a.getByTestId('detail-notes-input').fill('保留输入与光标');
    const notes = await a.getByTestId('detail-notes-input').elementHandle();
    await p.getByTestId('theme-select').evaluate(e => { e.value = 'NORD'; e.dispatchEvent(new Event('change', { bubbles: true })); });
    await a.waitForFunction(() => document.body.dataset.theme === 'NORD');
    assert.equal(await a.getByTestId('detail-notes-input').inputValue(), '保留输入与光标');
    assert.equal(await notes.evaluate(e => e === document.activeElement), true);
    assert.equal(await notes.evaluate(e => e === document.querySelector('[data-testid="detail-notes-input"]')), true);
    await p.getByTestId('theme-select').selectOption('MONET');
    await p.waitForFunction(() => !document.querySelector('#theme-note').hidden);
    assert.match(await p.getByTestId('theme-note').textContent(), /上游默认回退/);
    await p.getByTestId('theme-select').selectOption('NORD');
    await Promise.all([w.waitForNavigation({ waitUntil: 'load' }), w.evaluate(() => location.reload())]);
    await w.waitForFunction(() => document.body.dataset.theme === 'NORD');
    assert.equal(await w.evaluate(() => document.body.dataset.amoled), 'true');
    await p.goto(pathToFileURL(path.join(__dirname, 'device.html')).href + '?platform=android&theme=TOKYONIGHT&mode=light&amoled=true');
    assert.equal(await p.evaluate(() => document.body.dataset.theme), 'TOKYONIGHT');
    assert.equal(await p.evaluate(() => document.body.dataset.mode), 'light');
  } finally { await browser.close(); }
});
