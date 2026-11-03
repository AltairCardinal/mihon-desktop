(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.ReaderTiming=api;})(globalThis,function(){
'use strict';
const labels=['点击 → 初始化','初始化 → 列表就绪','列表 → 调用当前页','调用当前页 → 提交解码','提交 → 首次绘制','绘制 → 视频捕获'];
const presets=[
{id:'tablet-official-first',name:'真机 · 官方 · 首次',captureMs:284.275959,bounds:[267.502178,284.277741],stages:null,visual:{hold:135.753,fade:115.146,black:0,reveal:0},visualNote:'旧页135.753ms；250.899ms近黑，来自人工核对的源视频帧。两帧间的淡出为示意。'},
{id:'tablet-official-warm',name:'真机 · 官方 · 暖态',captureMs:289.346552,bounds:[273.076771,289.348333],stages:null,visual:{hold:124.009,fade:129.472,black:0,reveal:0},visualNote:'旧页124.009ms；253.481ms近黑，来自人工核对的源视频帧。两帧间的淡出为示意。'},
{id:'tablet-shared-on-first',name:'真机 · 探针对齐 · 首次',captureMs:366.246971,bounds:[332.02619,366.248753],stages:[55.302344,120.584375,46.121875,12.257291,93.336979,38.644107],visual:{hold:164.022,fade:149.101,black:0,reveal:0},visualNote:'旧页164.022ms；313.123ms近黑，来自人工核对的源视频帧。两帧间的淡出为示意。'},
{id:'tablet-shared-on-warm',name:'真机 · 探针对齐 · 暖态',captureMs:308.575662,bounds:[289.688881,308.577444],stages:[46.253385,115.536459,34.460416,6.061459,85.644791,20.619152],visual:{hold:140.381,fade:131.674,black:0,reveal:0},visualNote:'旧页140.381ms；272.055ms近黑。308.576ms首漫画为部分入场，本卡简化为整页显示，323.549ms才实际整页进入。'},
{id:'overhead-joint-first',name:'模拟器 · 联合探针 · 首次',captureMs:1020.32271,bounds:[987.179487,1020.323933],stages:[92.78477,696.542454,31.26833,4.918045,139.124447,55.684664],visual:{hold:350,fade:300,black:0,reveal:0},visualNote:'旧页/淡出为体验示意，未逐帧标定。联合采集可能有扰动，不代表日常无采集速度。'},
{id:'overhead-joint-warm',name:'模拟器 · 联合探针 · 暖态',captureMs:623.954116,bounds:[573.506913,623.955319],stages:[35.187925,32.757746,273.868105,7.369375,254.778145,19.99282],visual:{hold:220,fade:230,black:0,reveal:0},visualNote:'旧页/淡出为体验示意，未逐帧标定。首漫画早于FRAME_COMMIT回调，该回调不能作呈现端点。'}
];
const safe=v=>Math.max(0,Number.isFinite(Number(v))?Number(v):0);
const sum=vs=>vs.reduce((a,b)=>a+safe(b),0);
function copyPreset(id){const p=presets.find(p=>p.id===id);if(!p)throw new Error('未知预设');return JSON.parse(JSON.stringify(p));}
function timeline(p){const ready=p.stages?sum(p.stages):safe(p.captureMs),visualGate=sum([p.visual.hold,p.visual.fade,p.visual.black]),firstPixel=Math.max(ready,visualGate);return{ready,visualGate,firstPixel,complete:firstPixel+safe(p.visual.reveal)};}
function frame(p,time){const t=timeline(p),v=p.visual,old=time<=safe(v.hold)?1:Math.max(0,1-(time-safe(v.hold))/Math.max(.001,safe(v.fade))),image=time<t.firstPixel?0:v.reveal>0?Math.min(1,(time-t.firstPixel)/v.reveal):1;return{oldOpacity:image>0?0:old,imageOpacity:image,done:time>=t.complete};}
return{labels,presets,copyPreset,timeline,frame,elapsed:(wall,slow)=>Math.max(0,wall)/Math.max(1,slow)};
});
