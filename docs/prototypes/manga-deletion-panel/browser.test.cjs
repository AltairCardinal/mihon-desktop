module.exports = async function verify(pw) {
  const assert = require('node:assert/strict');
  const desktop = pw.frameLocator('#desktop'), phone = pw.frameLocator('#phone');
  const results = [];
  const layout = await phone.locator('body').evaluate(body => {
    const panel = body.querySelector('#panel'), last = body.querySelector('#bookmark');
    return { bottomPadding: +getComputedStyle(panel).paddingBottom.replace('px',''),
      bottom: panel.getBoundingClientRect().bottom, height: body.ownerDocument.documentElement.clientHeight,
      gap: panel.getBoundingClientRect().bottom-last.getBoundingClientRect().bottom,
      overflow: body.scrollWidth > body.ownerDocument.documentElement.clientWidth,
      saves: [...panel.querySelectorAll('button')].filter(b=>b.textContent==='保存').length };
  });
  assert.ok(layout.bottomPadding >= 32, JSON.stringify(layout));
  assert.ok(Math.abs(layout.bottom-layout.height)<2, JSON.stringify(layout));
  assert.ok(layout.gap>=32); assert.equal(layout.overflow,false); assert.equal(layout.saves,0);
  results.push('手机底部32px留白、底部面板、无横向溢出');
  for (const frame of [desktop,phone]) {
    await frame.locator('input[value=off]').click();
    assert.equal(await frame.locator('#details').getAttribute('aria-hidden'),'true');
    await frame.locator('input[value=on]').click();
    await frame.locator('#timing').click();
    await frame.locator('input[name=timing][value="2"]').click();
    await frame.locator('#bookmark').click();
    const saved=JSON.parse(await frame.locator('body').getAttribute('data-saved'));
    assert.deepEqual(saved,{mode:'on',slots:2,bookmarked:true});
    await frame.locator('#bookmark').press('Escape');
    assert.equal(await frame.locator('#panel').isVisible(),false);
    await frame.locator('#open').click();
    assert.equal(await frame.locator('#timing-value').innerText(),'倒数第三个已读章节');
    assert.equal(await frame.locator('#bookmark').getAttribute('aria-checked'),'true');
    await frame.locator('#timing').click();
    await frame.locator('input[name=timing]:checked').press('Escape');
    assert.equal(await frame.locator('#picker').isVisible(),false);
    assert.equal(await frame.locator('#panel').isVisible(),true);
  }
  results.push('双端修改立即生效、关闭重开保留、内层返回');
  await phone.locator('input[value=off]').click();
  assert.equal(await desktop.locator('#details').getAttribute('aria-hidden'),'false');
  await phone.locator('input[value=on]').click();
  await pw.locator('#theme').click();
  assert.equal(await phone.locator('html').getAttribute('data-theme'),'light');
  assert.equal(await phone.locator('#bookmark').getAttribute('aria-checked'),'true');
  await pw.locator('#theme').click();
  results.push('双端状态隔离、深浅色切换');
  return results;
};
