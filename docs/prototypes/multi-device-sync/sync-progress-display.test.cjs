const test = require('node:test');
const assert = require('node:assert/strict');
const { createDisplay } = require('./sync-progress.js');

test('动作稳定800ms，2秒三次变化合并，稳定2秒才解除；范围变更立即撤下比例', () => {
  let now = 0;
  const display = createDisplay(() => now);
  const fact = { state: 'running', action: '上传', scope: 'a', direction: '上传', confirmed: 24, percent: 64, startedAt: 0, lastProgressAt: 0 };
  assert.equal(display(fact).action, '正在交换数据');
  now = 799; assert.equal(display(fact).percent, null);
  now = 800; assert.equal(display(fact).percent, 64);
  now = 900; fact.scope = 'b'; assert.equal(display(fact).percent, null);
  now = 1100; fact.action = '校验'; display(fact);
  now = 1300; fact.action = '合并'; display(fact);
  now = 2100; assert.equal(display(fact).action, '正在交换数据');
  now = 3300; assert.equal(display(fact).action, '正在合并数据');
  fact.state = 'paused'; assert.equal(display(fact).title, '已暂停');
  fact.state = 'succeeded'; assert.equal(display(fact).title, '同步完成');
});

test('ETA可信2秒才显示，10秒无进展立即撤下，局部ETA不充当整体；终态冻结', () => {
  let now = 0; const display = createDisplay(() => now);
  const fact = { state: 'running', action: '上传', scope: 'a', confirmed: null, startedAt: 0, lastProgressAt: 0, wholeEta: 20, stageEta: 5 };
  assert.equal(display(fact).eta, '估算中');
  now = 2000; assert.equal(display(fact).eta, '约 20 秒');
  now = 10000; assert.equal(display(fact).eta, '暂无法估算');
  fact.lastProgressAt = now; fact.wholeEta = null; assert.equal(display(fact).eta, '暂无法估算');
  fact.state = 'succeeded'; fact.endedAt = now;
  const elapsed = display(fact).elapsed; now += 50000; assert.equal(display(fact).elapsed, elapsed);
  fact.confirmed = 0; fact.state = 'partial'; assert.equal(display(fact).title, '部分完成');
  fact.state = 'succeeded'; fact.noWork = true; assert.equal(display(fact).title, '已是最新');
});
