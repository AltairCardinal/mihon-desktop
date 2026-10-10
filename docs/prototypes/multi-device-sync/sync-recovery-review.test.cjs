const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const url = 'file://' + path.resolve(__dirname, 'progress-review.html').replace(/\\/g, '/');

async function open(run, progress = 'compact-complete') {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1180 } });
    page.setDefaultTimeout(5000);
    await page.clock.install({ time: new Date('2026-10-03T10:00:00+08:00') });
    await page.goto(url + '?device=both&progress=' + progress);
    const phone = page.frameLocator('#preview-android');
    const pc = page.frameLocator('#preview-windows');
    await phone.getByTestId('sync-compact-summary').waitFor();
    async function scene(value, platform = 'android') {
      await page.locator('#preview-' + platform).evaluate((el, name) => el.contentWindow.__mihonSyncDemo.showInteractionScenario(name), value);
    }
    await run(page, phone, pc, scene);
  } finally { await browser.close(); }
}

test('恢复页按事实推荐下一步，检查反馈与授权状态关闭重开仍保留，双端隔离', async () => {
  await open(async (page, phone, pc, scene) => {
    await scene('space-deleted');
    assert.equal(await phone.getByTestId('recovery-primary').innerText(), '创建新的同步空间');
    await phone.getByTestId('recovery-recheck').click();
    assert.equal(await phone.getByTestId('recovery-recheck').isDisabled(), true);
    assert.equal(await phone.getByTestId('recovery-result').getAttribute('aria-busy'), 'true');
    await page.clock.fastForward(900);
    assert.match(await phone.getByTestId('recovery-result').innerText(), /仍无法访问/);
    assert.equal(await phone.getByTestId('recovery-primary').innerText(), '创建新的同步空间');
    await phone.getByTestId('recovery-authorization').click();
    await page.clock.fastForward(1600);
    assert.match(await phone.getByTestId('recovery-auth-state').innerText(), /已确认/);
    const checked = await phone.getByTestId('recovery-result').innerText();
    await phone.getByTestId('sync-close').click();
    await phone.getByTestId('library-sync').click();
    await phone.getByTestId('recovery-open').click();
    assert.equal(await phone.getByTestId('recovery-result').innerText(), checked);
    assert.match(await phone.getByTestId('recovery-auth-state').innerText(), /已确认/);
    assert.match(await pc.getByTestId('sync-compact-summary').innerText(), /同步已完成/);
  });
});

test('授权检查失败保留之前有效的授权结论，失效授权不会被离线结果提升为已连接', async () => {
  await open(async (page, phone, pc, scene) => {
    await scene('space-auth');
    assert.match(await phone.getByTestId('recovery-auth-state').innerText(), /需要重新授权/);
    await page.getByTestId('network-toggle').click();
    await phone.getByTestId('recovery-authorization').click();
    await page.clock.fastForward(600);
    assert.match(await phone.getByTestId('recovery-result').innerText(), /授权检查未完成/);
    assert.match(await phone.getByTestId('recovery-auth-state').innerText(), /需要重新授权/);
    assert.equal(await phone.getByTestId('recovery-primary').innerText(), '重新连接 GitHub');
  });
});

test('等待浏览器授权禁止重复启动，返回后自动检查且已授权不能被误报为空间恢复', async () => {
  await open(async (page, phone, pc, scene) => {
    await scene('space-auth');
    assert.match(await phone.getByTestId('recovery-primary').innerText(), /GitHub/);
    await phone.getByTestId('recovery-primary').click();
    const opened = page.waitForEvent('popup');
    await phone.getByTestId('ix-open-github').click();
    const popup = await opened;
    assert.equal(await phone.getByTestId('ix-open-github').isDisabled(), true);
    await phone.getByTestId('sync-close').click();
    await phone.getByTestId('library-sync').click();
    await phone.getByTestId('recovery-open').click();
    assert.match(await phone.getByTestId('recovery-auth-state').innerText(), /等待/);
    await phone.getByTestId('recovery-primary').click();
    assert.match(await phone.getByRole('dialog').innerText(), /等待.*授权/);
    await popup.getByLabel('设备验证码').fill('DEMO-CODE');
    await popup.getByRole('button', { name: '继续', exact: true }).click();
    await popup.getByRole('button', { name: '授权 Mihon', exact: true }).click();
    await phone.getByTestId('recovery-auth-state').waitFor();
    await page.clock.runFor(1600);
    assert.match(await phone.getByTestId('recovery-auth-state').innerText(), /已确认/);
    assert.match(await phone.getByTestId('recovery-result').innerText(), /仍无法访问/);
    assert.equal(await phone.getByTestId('recovery-primary').innerText(), '创建新的同步空间');
    assert.equal(await phone.getByTestId('recovery-authorize').count(), 0);
    await popup.close();
  });
});

test('创建和连接保留确认、密码及中断续办，取消不丢恢复结果，空列表不会假装找到空间', async () => {
  await open(async (page, phone, pc, scene) => {
    await scene('space-deleted');
    await phone.getByTestId('recovery-primary').click();
    await phone.getByTestId('recovery-cancel-confirm').click();
    assert.equal(await phone.getByTestId('recovery-primary').innerText(), '创建新的同步空间');
    await phone.getByTestId('recovery-connect').click();
    await page.clock.fastForward(900);
    assert.match(await phone.getByTestId('recovery-spaces').innerText(), /没有可连接/);
    await phone.getByTestId('sync-settings-back').click();
    await phone.getByTestId('recovery-primary').click();
    await phone.getByTestId('recovery-confirm').click();
    await phone.getByTestId('ix-password-enable').click();
    await phone.getByTestId('ix-field-password').fill('tablet-pass');
    await phone.getByTestId('sync-close').click();
    await phone.getByTestId('library-sync').click();
    await phone.getByTestId('recovery-open').click();
    assert.match(await phone.getByTestId('recovery-primary').innerText(), /继续创建/);
    await phone.getByTestId('recovery-primary').click();
    assert.equal(await phone.getByTestId('ix-password-enable').getAttribute('aria-checked'), 'false', '关闭后清空密码与风险确认，保留待继续步骤');
    await phone.getByTestId('ix-password-enable').click();
    assert.equal(await phone.getByTestId('ix-field-password').inputValue(), '', '关闭后清空密码草稿');
    await phone.getByTestId('ix-field-password').fill('tablet-pass');
    assert.equal(await phone.getByTestId('ix-password-confirm').isDisabled(), true);
    await phone.getByTestId('ix-password-acknowledge').check();
    await phone.getByTestId('ix-password-confirm').click();
    await page.clock.runFor(4000);
    assert.match(await phone.getByTestId('sync-compact-summary').innerText(), /同步已完成/);
    await scene('space-existing');
    await phone.getByTestId('recovery-primary').click();
    await page.clock.fastForward(900);
    await phone.getByTestId('recovery-space-choice').click();
    await phone.getByTestId('recovery-confirm').click();
    await phone.getByTestId('ix-field-password').fill('mihon-demo');
    await phone.getByTestId('ix-password-confirm').click();
    await page.clock.runFor(4000);
    assert.match(await phone.getByTestId('sync-compact-summary').innerText(), /同步已完成/);
    await scene('space-switch-pending');
    assert.match(await phone.getByTestId('recovery-primary').innerText(), /继续连接/);
    await phone.getByTestId('recovery-primary').click();
    assert.equal(await phone.getByTestId('ix-field-password').count(), 1);
  });
});

test('统计阶段循环等待且无计时，暂停后动画停止，完成统计才从00:00计时', async () => {
  await open(async (page, phone) => {
    const track = phone.getByTestId('sync-progress-track');
    assert.equal(await track.getAttribute('aria-valuenow'), null);
    assert.equal(await track.locator('span').evaluate(el => getComputedStyle(el).animationName), 'sync-progress-slide');
    assert.equal(await phone.getByTestId('sync-compact-time').count(), 0);
    assert.doesNotMatch(await phone.getByTestId('sync-progress-card').innerText(), /已用|剩余/);
    await page.clock.fastForward(2500);
    await phone.getByTestId('sync-progress-primary').click();
    assert.match(await phone.getByTestId('sync-compact-summary').innerText(), /已暂停统计/);
    assert.equal(await track.locator('span').evaluate(el => getComputedStyle(el).animationName), 'none');
    await page.clock.fastForward(60000);
    assert.equal(await phone.getByTestId('sync-compact-time').count(), 0);
    await phone.getByTestId('sync-progress-primary').click();
    await page.clock.fastForward(3500);
    assert.equal(await phone.getByTestId('sync-compact-summary').innerText(), '同步中，已完成0/16384条');
    assert.equal(await phone.getByTestId('sync-compact-time').innerText(), '已用00:00，剩余估时—');
    assert.equal(await track.getAttribute('aria-valuenow'), '0');
    await page.clock.fastForward(2200);
    await phone.getByTestId('sync-progress-primary').click();
    const time = await phone.getByTestId('sync-compact-time').innerText();
    await page.clock.fastForward(60000);
    assert.equal(await phone.getByTestId('sync-compact-time').innerText(), time);
  }, 'compact-unknown');
});

test('暂停按钮使用与卡片一致的浅底样式、图标和48px热区，检查失败保留结论且改名可恢复', async () => {
  await open(async (page, phone, pc, scene) => {
    const button = phone.getByTestId('sync-progress-primary');
    const style = await button.evaluate(el => ({ height: el.getBoundingClientRect().height, bg: getComputedStyle(el).backgroundColor, radius: getComputedStyle(el).borderRadius, icon: el.querySelector('svg') !== null, right: el.getBoundingClientRect().right, cardRight: el.closest('section').getBoundingClientRect().right }));
    assert.ok(style.height >= 48);
    assert.notEqual(style.bg, 'rgba(0, 0, 0, 0)');
    assert.ok(parseFloat(style.radius) >= 12);
    assert.equal(style.icon, true);
    assert.ok(style.cardRight - style.right < 32);
    await scene('space-check-failed');
    await phone.getByTestId('recovery-recheck').click();
    await page.clock.fastForward(900);
    assert.match(await phone.getByTestId('recovery-result').innerText(), /检查未完成/);
    assert.match(await phone.getByTestId('recovery-space-state').innerText(), /上次.*不可用/);
    await scene('space-renamed');
    await phone.getByTestId('recovery-recheck').click();
    await page.clock.fastForward(900);
    assert.match(await phone.getByTestId('sync-panel').innerText(), /原同步空间已恢复/);
    assert.equal(await phone.getByTestId('recovery-primary').count(), 0);
  }, 'compact-upload');
});
