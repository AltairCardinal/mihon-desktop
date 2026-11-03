const test = require('node:test');
const assert = require('node:assert/strict');
const m = require('./timing-model.js');
test('all six defaults preserve measured same-sample capture endpoint', () => {
  assert.equal(m.presets.length, 6);
  for (const preset of m.presets) assert.ok(Math.abs(m.timeline(preset).firstPixel - preset.captureMs) < 0.001, preset.id);
});
test('official unknown internal phases remain unknown; total alone controls capture', () => {
  const p = m.copyPreset('tablet-official-first');
  assert.equal(p.stages, null);
  p.captureMs = 1000;
  assert.equal(m.timeline(p).firstPixel, 1000);
});
test('real pipeline changes alter first image; visual runs concurrently, never summed', () => {
  const p = m.copyPreset('tablet-shared-on-first');
  const before = m.timeline(p).firstPixel;
  p.stages[1] += 300;
  assert.ok(Math.abs(m.timeline(p).firstPixel - before - 300) < 0.001);
  p.visual = { hold: 100, fade: 200, black: 50, reveal: 80 };
  assert.equal(m.timeline(p).firstPixel, m.timeline(p).ready);
  p.visual.hold = 2000;
  assert.equal(m.timeline(p).firstPixel, 2250);
});
test('reveal and slow motion preserve independent real elapsed units and reset state', () => {
  const p = m.copyPreset('tablet-shared-on-warm');
  p.visual.reveal = 100;
  const t = m.timeline(p);
  assert.equal(m.frame(p, 0).oldOpacity, 1);
  assert.equal(m.frame(p, t.firstPixel - 0.01).imageOpacity, 0);
  assert.equal(m.frame(p, t.firstPixel + 100).imageOpacity, 1);
  assert.equal(m.elapsed(1000, 5), 200);
  p.stages[0] = 999;
  assert.notEqual(m.copyPreset(p.id).stages[0], 999);
});
