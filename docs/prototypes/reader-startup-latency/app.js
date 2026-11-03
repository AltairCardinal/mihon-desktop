/* Local-only browser wiring. Images are decoded before enabling any playback. */
'use strict';
const M = window.ReaderTiming;
let assetsReady = false;
const cards = [];
const fmt = n => n.toFixed(1);
const slowdown = () => Number(document.querySelector('#speed').value);
function field(card, container, label, value, key, change) {
  const wrapper = document.createElement('div');
  wrapper.className = 'field';
  const id = `${card.key}-${key}`;
  wrapper.innerHTML = `<label for="${id}">${label}</label><div><input type="range" min="0" max="2000" step="0.1" value="${value}" aria-label="${label}滑块"><input id="${id}" type="number" min="0" max="10000" step="0.1" value="${value}" aria-label="${label}"></div>`;
  const [range, number] = wrapper.querySelectorAll('input');
  const update = source => {
    const v = Math.max(0, Math.min(10000, Number(source.value) || 0));
    number.value = v; range.max = Math.max(2000, v); range.value = v;
    change(v); card.modified = true; reset(card); updateSummary(card);
  };
  range.addEventListener('input', () => update(range));
  number.addEventListener('input', () => update(number));
  container.append(wrapper);
}
function updateSummary(c) {
  const t = M.timeline(c.preset);
  c.el.querySelector('.first-time').textContent = `${fmt(t.firstPixel)} ms`;
  const p = M.presets.find(p => p.id === c.preset.id);
  c.el.querySelector('.sample-note').textContent = `${c.modified ? '自定义假设 · ' : '实测预设 · '}原样本采样区间 ${fmt(p.bounds[0])}–${fmt(p.bounds[1])} ms。${t.visualGate > t.ready ? `转场额外等待 ${fmt(t.visualGate-t.ready)} ms。` : ''}`;
  const track = c.el.querySelector('.phase-track');
  track.replaceChildren();
  (c.preset.stages || [c.preset.captureMs]).forEach((n, i) => {
    const bar = document.createElement('i');
    bar.style.flex = Math.max(n, .001);
    bar.title = `${c.preset.stages ? M.labels[i] : '首图观测总时长'} ${fmt(n)} ms`;
    track.append(bar);
  });
}
function renderFields(c) {
  const internal = c.el.querySelector('.internal'), visual = c.el.querySelector('.visual');
  internal.replaceChildren(); visual.replaceChildren();
  if (c.preset.stages) c.preset.stages.forEach((n, i) => field(c, internal, M.labels[i], n, `stage-${i}`, v => c.preset.stages[i] = v));
  else {
    const note = document.createElement('p'); note.className = 'unknown';
    note.textContent = '官方内部四段未测得。仅回放观测总时长；要逐段手调，请选择探针对齐样本。';
    internal.append(note);
    field(c, internal, '点击 → 首漫画（观测总时长）', c.preset.captureMs, 'capture', v => c.preset.captureMs = v);
  }
  [['hold','旧页保持'],['fade','旧页淡出'],['black','近黑后最短等待'],['reveal','首图渐显时长']].forEach(([key,label]) => field(c,visual,label,c.preset.visual[key],key,v=>c.preset.visual[key]=v));
  c.el.querySelector('.visual-note').textContent = c.preset.visualNote + ' 近黑后最短等待控制视觉允许出现首图的时刻；内部尚未就绪时继续等待。渐显只改变整页进入，不延迟首次漫画像素。';
  updateSummary(c);
}
function paint(c, time) {
  const f = M.frame(c.preset, time), t = M.timeline(c.preset);
  c.el.querySelector('.details').style.opacity = f.oldOpacity;
  c.el.querySelector('.comic').style.opacity = f.imageOpacity;
  c.el.querySelector('.clock').innerHTML = `${fmt(Math.min(time,t.complete))} <small>ms</small>`;
  c.el.dataset.elapsed = time;
  c.el.dataset.firstPixel = t.firstPixel;
  let start = 0;
  c.el.querySelectorAll('.phase-track i').forEach((bar, i) => {
    const n = (c.preset.stages || [c.preset.captureMs])[i];
    bar.style.opacity = time >= start + n ? '1' : time >= start ? '.55' : '.2'; start += n;
  });
  if (f.done) c.el.querySelector('.state').textContent = '漫画已显示';
  else if(time >= t.ready && time < t.visualGate) c.el.querySelector('.state').textContent = '等待视觉转场';
  else if(c.preset.stages) {
    let edge = 0;
    const idx = c.preset.stages.findIndex(n => (edge += n) > time);
    c.el.querySelector('.state').textContent = idx < 0 ? '首图入场' : M.labels[idx];
  } else c.el.querySelector('.state').textContent = '观测时长回放';
  return f.done;
}
function reset(c) {
  cancelAnimationFrame(c.raf); c.raf = 0;
  c.el.querySelector('.screen').classList.remove('playing','finished');
  paint(c,0); c.el.querySelector('.state').textContent = '等待播放';
  c.el.dataset.playing = 'false';
}
function play(c, start = performance.now()) {
  if (!assetsReady) return;
  reset(c); c.el.querySelector('.screen').classList.add('playing'); c.el.dataset.playing = 'true';
  const factor = slowdown();
  const tick = now => {
    if (paint(c, M.elapsed(now-start, factor))) {
      c.el.querySelector('.screen').classList.replace('playing','finished'); c.el.dataset.playing = 'false'; c.raf = 0;
    } else c.raf = requestAnimationFrame(tick);
  };
  c.raf = requestAnimationFrame(tick);
}
for (const [i,id] of ['tablet-official-first','tablet-shared-on-first'].entries()) {
  const el = document.querySelector('#card-template').content.firstElementChild.cloneNode(true);
  const c = {el,key:i?'B':'A',preset:M.copyPreset(id),modified:false,raf:0}; cards.push(c);
  el.dataset.card = c.key; el.querySelector('.badge').textContent=c.key;
  const select=el.querySelector('.preset');
  M.presets.forEach(p=>select.add(new Option(p.name,p.id))); select.value=id;
  select.addEventListener('change',()=>{c.preset=M.copyPreset(select.value);c.modified=false;reset(c);renderFields(c);});
  el.querySelector('.screen').addEventListener('click',()=>play(c));
  el.querySelector('.play').addEventListener('click',()=>play(c));
  el.querySelector('.reset').addEventListener('click',()=>reset(c));
  el.querySelector('.restore').addEventListener('click',()=>{c.preset=M.copyPreset(select.value);c.modified=false;reset(c);renderFields(c);});
  document.querySelector('#cards').append(el); renderFields(c); reset(c);
}
document.querySelector('#play-both').addEventListener('click',()=>{const now=performance.now();cards.forEach(c=>play(c,now));});
document.querySelector('#reset-both').addEventListener('click',()=>cards.forEach(reset));
document.querySelector('#speed').addEventListener('change',()=>cards.forEach(reset));
Promise.all(Array.from(document.querySelectorAll('.screen img'),img=>img.decode())).then(()=>{
  assetsReady=true;document.querySelectorAll('.play,.screen,#play-both').forEach(el=>el.disabled=false);
  document.querySelector('#asset-status').textContent='画面已解码 · 点击画面或同时播放 · 调整参数会重置本卡，重新播放生效';
}).catch(()=>{document.querySelector('#asset-status').textContent='画面载入失败：请保留 assets 中的两张本地图片后重新打开。';});
