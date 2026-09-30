const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
async function run(fn) {
 const browser = await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try {const p=await browser.newPage({viewport:{width:1440,height:1000}});await p.goto(pathToFileURL(path.join(__dirname,'index.html')).href);const w=p.frames().find(f=>f.url().includes('platform=windows'));const a=p.frames().find(f=>f.url().includes('platform=android'));await fn(p,w,a);}finally{await browser.close();}
}
async function open(frame) {await frame.getByTestId('nav-more').click();await frame.getByTestId('settings-open').click();assert.equal(await frame.getByTestId('settings-appearance').count(),1,'设置必须导航到原版分类菜单');await frame.getByTestId('settings-appearance').click();}
test('更多→设置→外观完整主题簇通过真实控件更新当前端并正确返回',()=>run(async(p,w,a)=>{
 await open(w);
 assert.equal(await w.getByTestId('appearance-page').count(),1);
 assert.equal(await w.locator('[data-theme-card]').count(),15);
 await w.getByTestId('appearance-mode-system').click(); assert.equal(await w.getByTestId('appearance-mode-system').getAttribute('aria-pressed'),'true');
 await w.getByTestId('appearance-mode-light').click();
 assert.equal(await w.getByTestId('appearance-amoled').count(),0);
 await w.getByTestId('appearance-theme-NORD').click();
 const facts=await w.evaluate(()=>({group:getComputedStyle(document.querySelector('.appearance-group')).color,weight:getComputedStyle(document.querySelector('.appearance-group')).fontWeight,cardInnerWidth:document.querySelector('.appearance-theme-card').clientWidth,minHeight:getComputedStyle(document.querySelector('.appearance-pref')).minHeight}));
 assert.equal(facts.group,'rgb(129, 161, 193)');assert.equal(facts.weight,'400');assert.equal(facts.cardInnerWidth,106);assert.equal(facts.minHeight,'56px');
 assert.equal(await w.evaluate(()=>document.body.dataset.theme),'NORD');
 assert.equal(await a.evaluate(()=>document.body.dataset.theme),'DEFAULT');
 await w.getByTestId('appearance-mode-dark').click();
 const switchBox=await w.getByTestId('appearance-amoled').boundingBox();assert.equal(switchBox.height,32);assert.equal(switchBox.width,52);
 await w.getByTestId('appearance-amoled').check();
 assert.equal(await w.evaluate(()=>getComputedStyle(document.body).backgroundColor),'rgb(0, 0, 0)');
 const card=await w.getByTestId('appearance-theme-NORD').boundingBox(); assert.equal(Math.round(card.width),114);
 await p.keyboard.press('Escape');assert.equal(await w.getByTestId('settings-open').isVisible(),true);
}));
test('显示偏好与语言子页保持本端状态，日期6项即时选择和取消边界',()=>run(async(p,w,a)=>{
 await open(a);
 await a.getByTestId('appearance-date').click();assert.equal(await a.locator('[data-appearance-date]').count(),6);
 await a.getByTestId('appearance-date-yyyy-MM-dd').click();assert.match(await a.getByTestId('appearance-date').textContent(),/yyyy-MM-dd/);
 await a.getByTestId('appearance-date').click();await a.getByTestId('appearance-cancel').click();assert.match(await a.getByTestId('appearance-date').textContent(),/yyyy-MM-dd/);
 await a.getByTestId('appearance-tablet').click();assert.equal(await a.locator('[data-appearance-tablet]').count(),4);await a.getByTestId('appearance-tablet-ALWAYS').click();assert.match(await a.getByTestId('notice').textContent(),/重启/);
 await a.getByTestId('appearance-relative').uncheck();await a.getByTestId('appearance-images').uncheck();
 await a.getByTestId('appearance-language').click();assert.equal(await a.getByTestId('appearance-language-page').count(),1);await a.getByTestId('appearance-language-zh-CN').click();await p.keyboard.press('Escape');
 assert.equal(await a.getByTestId('appearance-relative').isChecked(),false);assert.equal(await a.getByTestId('appearance-images').isChecked(),false);
 await open(w);assert.equal(await w.getByTestId('appearance-relative').isChecked(),true);assert.equal(await w.getByTestId('appearance-images').isChecked(),true);
}));

test('显示开关接入真实章节日期及本地简介图片，语言即时翻译而不返回',()=>run(async(p,w,a)=>{
 await open(a);
 await a.getByTestId('appearance-language').click();
 assert.equal(await a.locator('[data-action="appearance-language-choice"]').count(),69);
 await a.getByTestId('appearance-language-en').click();
 assert.equal(await a.getByTestId('appearance-language-page').count(),1);
 await p.keyboard.press('Escape');
 assert.equal(await a.locator('.appearance-toolbar h1').textContent(),'Appearance');
 await a.getByTestId('appearance-language').click();await a.getByTestId('appearance-language-zh-CN').click();await p.keyboard.press('Escape');
 await a.getByTestId('appearance-date').click();await a.getByTestId('appearance-date-yyyy-MM-dd').click();await a.getByTestId('appearance-relative').uncheck();
 await a.getByTestId('appearance-back').click();await a.getByTestId('settings-back').click();
 await a.evaluate(()=>window.demo.scenario('detail-content'));await a.getByTestId('manga-A').click();
 assert.ok(await a.locator('.detail-description img').count(),'含图简介样本必须提供真实本地图片');
 assert.match(await a.locator('.chapter-main small').first().textContent(),/\d{4}-\d{2}-\d{2}/);
 await a.getByTestId('detail-back').click();await open(a);await a.getByTestId('appearance-images').uncheck();
 await a.getByTestId('appearance-back').click();await a.getByTestId('settings-back').click();await a.evaluate(()=>window.demo.scenario('detail-content'));await a.getByTestId('manga-A').click();
 assert.equal(await a.locator('.detail-description img').count(),0);
}));

test('实际日期适配器按日历午夜与7天边界格式化，不以24小时推算',()=>{
 const vm=require('node:vm'),fs=require('node:fs');
 const context={Intl,Date,window:{matchMedia:()=>({matches:false,addEventListener(){}})}};
 context.window.window=context.window;vm.createContext(context);
 vm.runInContext(fs.readFileSync(path.join(__dirname,'appearance-locales.js'),'utf8'),context);
 context.MihonAppearanceLocales=context.window.MihonAppearanceLocales;
 vm.runInContext(fs.readFileSync(path.join(__dirname,'appearance.js'),'utf8'),context);
 const adapter=context.window.MihonAppearance.create({selection:{theme:'DEFAULT',amoled:false}});
 const now=new Date(2026,8,30,0,5);
 assert.equal(adapter.chapterDate(new Date(2026,8,29,23,55),now),'1 天前');
 assert.equal(adapter.chapterDate(new Date(2026,8,24,12),now),'6 天前');
 assert.equal(adapter.chapterDate(new Date(2026,8,23,12),now),adapter.formatDate(new Date(2026,8,23,12)));
 assert.equal(adapter.chapterDate(new Date(2026,9,7,12),now),'7 天后');
 assert.equal(adapter.chapterDate(new Date(2026,9,8,12),now),adapter.formatDate(new Date(2026,9,8,12)));
 adapter.state.language='en';assert.equal(adapter.formatDate(new Date(2026,8,30)),'9/30/26');
});

test('SYSTEM重新读取真实媒体偏好，系统变化更新当前页面和主题卡',()=>run(async(p,w,a)=>{
 await p.emulateMedia({colorScheme:'light'});
 await open(w);
 await w.getByTestId('appearance-mode-system').click();
 assert.equal(await w.evaluate(()=>document.body.dataset.mode),'light');
 await p.emulateMedia({colorScheme:'dark'});
 await w.waitForFunction(()=>document.body.dataset.mode==='dark');
 await w.getByTestId('appearance-mode-light').click();
 await p.emulateMedia({colorScheme:'light'});await p.emulateMedia({colorScheme:'dark'});
 assert.equal(await w.evaluate(()=>document.body.dataset.mode),'light');
}));

test('Android320设置、外观和语言顶栏返回与标题始终同一行',()=>run(async(p,w,a)=>{
 await a.getByTestId('nav-more').click();await a.getByTestId('settings-open').click();
 async function sameRow() {
  const back=await a.locator('.appearance-toolbar button').first().boundingBox();
  const title=await a.locator('.appearance-toolbar h1').boundingBox();
  assert.ok(Math.abs((back.y+back.height/2)-(title.y+title.height/2))<2,JSON.stringify({back,title}));
 }
 await sameRow();await a.getByTestId('settings-appearance').click();await sameRow();await a.getByTestId('appearance-language').click();await sameRow();
}));

test('Android320字号200%日期弹窗单选圆保持20px且当前项内点12px',()=>run(async(p,w,a)=>{
 await p.getByTestId('font-toggle').click();
 await open(a);await a.getByTestId('appearance-date').click();
 for (const radio of await a.locator('.appearance-radio').all()) {
  const box=await radio.boundingBox();assert.equal(box.width,20);assert.equal(box.height,20);
 }
 const selected=await a.locator('[aria-checked="true"] .appearance-radio').evaluate(e=>({width:getComputedStyle(e,'::after').width,height:getComputedStyle(e,'::after').height,text:e.textContent}));
 assert.equal(selected.width,'12px');assert.equal(selected.height,'12px');assert.equal(selected.text,'');
 await a.getByTestId('appearance-cancel').click();assert.equal(await a.getByTestId('appearance-page').count(),1);
}));
