(function () {
  'use strict';

  // UI storyboards only. Credentials, matching, import and recovery do not call a service.
  window.MihonSyncInteractions = { create };
  function create({ state, currentDevice, isWindows, esc, view, button, render }) {
    const titles = { setup: '设置同步', connection: '连接同步空间', key: '恢复密钥', import: '首次合并', importing: '合并进度', imported: '合并完成', credentials: '访问令牌', recovery: '恢复资料', frequency: '同步频率', device: '设备名称', disconnect: '断开同步', switch: '更换同步空间', issue: '同步详情', item: '处理同步', source: '查看来源', privacy: '阅读与历史' };
    const sampleKey = 'DEMO-ONLY · meadow-river-moon · 2026';
    function data() {
      if (!state.ui.interactions) state.ui.interactions = { connected: true, stack: [], fields: { repo: 'reader/mihon-sync', token: '', key: '', device: currentDevice().name }, items: [], message: '', issue: null, mode: 'join', importKind: 'merge' };
      return state.ui.interactions;
    }
    const screen = () => data().stack.at(-1);
    function go(name) { data().stack.push(name); data().message = ''; }
    function back() { data().stack.pop(); data().message = ''; }
    function home(message = '') { data().stack = []; data().message = message; state.ui.syncSettingsOpen = false; }
    function close() { data().stack = []; data().message = ''; data().fields.token = ''; data().fields.key = ''; data().showKey = false; if (data().batch?.done === data().batch?.total) data().batch = null; }
    const action = (label, name, primary = false, attrs = '') => button(label, `data-action="ix-${name}" data-testid="ix-${name}" ${attrs}`, primary ? 'm-button-primary' : 'm-button-text');
    const note = text => `<p class="ix-note">${text}</p>`;
    const section = text => `<p class="settings-section-label">${text}</p>`;
    const actions = content => `<div class="ix-actions">${content}</div>`;
    const message = () => data().message ? `<div class="ix-feedback" role="status">${esc(data().message)}</div>` : '';
    const field = (name, label, type = 'text', placeholder = '') => `<label class="ix-field">${label}<input type="${type}" data-ix-field="${name}" data-testid="ix-field-${name}" value="${esc(data().fields[name] || '')}" placeholder="${esc(placeholder)}" autocomplete="off" spellcheck="false"></label>`;
    const row = (label, value, name, icon = 'chevron') => `<button class="ix-setting-row" data-action="ix-${name}" data-testid="ix-${name}"><span><strong>${label}</strong>${value ? `<small>${esc(value)}</small>` : ''}</span>${view.icon(icon)}</button>`;
    const pair = (label, value) => `<div class="ix-pair"><span>${label}</span><strong>${esc(value)}</strong></div>`;
    const choice = (label, detail, name, attrs = '') => `<button class="ix-choice" data-action="ix-${name}" data-testid="ix-${name}" ${attrs}><strong>${label}</strong><small>${detail}</small>${view.icon('chevron')}</button>`;
    function settings() {
      const d = data();
      return section('同步服务') + row(d.connected ? 'GitHub · 已连接' : '尚未设置同步', d.connected ? d.fields.repo : '创建空间或加入已有空间', d.connected ? 'connection-info' : 'setup', 'cloud') + (d.connected ? row('测试连接', d.issue ? '上次连接未完成' : '本设备可访问同步空间', 'test') + row('访问令牌', '仅保存在本设备 · ••••••••', 'credentials') + row('恢复资料', '用于新设备加入和恢复访问', 'recovery') : '') + message() + section('此设备') + row('设备名称', d.fields.device, 'device');
    }
    function settingsFooter() {
      const minutes = currentDevice().settings.periodMinutes;
      return row('同步频率', minutes < 60 ? `${minutes} 分钟` : `${minutes / 60} 小时`, 'frequency') + note(isWindows() ? '定期同步在应用运行期间执行。关闭自动同步后，仍可手动同步。' : '系统允许时自动同步，实际执行时间可能延后。关闭自动同步后，仍可手动同步。') + section('数据范围') + row('阅读与历史', '阅读记录参与同步，阅读模式仅保存在此设备', 'privacy') + (data().connected ? section('管理连接') + row('更换同步空间', '', 'switch') + row('断开此设备', '', 'disconnect') : '');
    }
    function unconfigured() {
      return `<section class="sync-content ix-welcome"><div class="ix-hero-icon">${view.icon('sync')}</div><h3>让阅读跟随你</h3>${note('在设备之间同步收藏、作者关注和阅读记录。你可以创建一个同步空间，也可以加入已有空间。')}${actions(action('设置同步', 'setup', true))}${note('阅读模式保留为各设备独立设置。')}</section>`;
    }
    function issues() {
      const unknown = data().issue === 'unknown';
      return data().issue ? `<button class="ix-status-link" data-action="ix-issue" data-testid="ix-issue">${view.icon(unknown ? 'sync' : 'info')}<span><strong>${unknown ? '正在核对上传结果' : '上次同步未完成'}</strong><small>${unknown ? '待上传数量会保留到核对完成' : '查看详情与恢复操作'}</small></span>${view.icon('chevron')}</button>` : '';
    }
    function summary() { return issues() + renderBatch() + message(); }
    function items() {
      return data().items.map(item => `<article class="native-confirmation ix-pending"><span class="row-leading">${view.icon(item.icon || 'sync')}</span><div class="row-copy"><strong>${esc(item.title)}</strong><small>${esc(item.subtitle)}</small></div>${action('查看', 'item', false, `data-item="${item.id}"`)}</article>`).join('');
    }
    function samples() {
      const remote = isWindows() ? '手机 A' : '电脑 B';
      return [
        { id: 'position', title: '选择《星海骑士》的阅读位置', subtitle: `${remote} · 第 12 话，第 19 页`, icon: 'reader', local: '第 11 话 · 第 8 页', remote: '第 12 话 · 第 19 页', options: ['采用此位置并同步', '保留本机位置并同步'], detail: '当前阅读画面保持不变，选定后才切换。此决定将同步到其他设备。' },
        { id: 'follow', title: '选择对白河的关注状态', subtitle: `本设备关注 · ${remote} 取消关注`, icon: 'authors', local: '关注白河', remote: '取消关注白河', options: ['保留关注并同步', '取消关注并同步'], detail: '选择会形成新的同步决定。选择取消时，其他接收设备仍需各自确认。' },
        { id: 'read', title: '选择章节的已读状态', subtitle: '《星海骑士》· 第 12 话', icon: 'reader', local: '已读', remote: '明确标为未读', options: ['标为已读并同步', '标为未读并同步'], detail: '只调整此章节的阅读状态，不更改阅读模式。' },
        { id: 'restore', title: '处理从旧备份恢复的收藏', subtitle: '《黎明邮局》· 其他设备已取消', icon: 'bookmark', local: '旧备份中有收藏', remote: '当前有效状态为取消收藏', options: ['保留取消', '重新收藏并同步'], detail: '旧备份中的收藏不会自动覆盖当前取消。你可以明确选择是否重新收藏。' },
        { id: 'source', title: '《远山来信》的来源不可用', subtitle: 'Mihon 演示源 · 尚未找到对应漫画', icon: 'browse', options: ['查看来源', '重试匹配'], detail: '同步数据已接收。在找到对应来源前，保留此事项，稍后可以继续匹配。' },
        { id: 'author', title: '关联作者「青木」', subtitle: '找到 2 位同名作者，需要确认', icon: 'authors', options: [], detail: '请选择同一位作者。作品和来源可帮助你区分同名记录。' },
        { id: 'page', title: '《夜行纪事》的页码待确认', subtitle: '第 8 话已找到 · 原第 26 页不可验证', icon: 'reader', options: ['从本章开头继续'], detail: '来源的分页可能有变化。保留章节位置，从本章开头继续阅读。' },
      ];
    }
    function itemPage() {
      const item = data().items.find(item => item.id === data().selectedItem);
      if (!item) return note('此事项已处理。') + actions(action('返回同步', 'home'));
      const remote = isWindows() ? '手机 A' : '电脑 B';
      return `<h3>${esc(item.title)}</h3>` + note(esc(item.subtitle)) + (item.local ? `<div class="ix-comparison">${pair('此设备', item.local)}${pair(remote, item.remote)}</div>` : '') + note(esc(item.detail)) + (item.id === 'author' ? choice('青木 · 演示源 A', '代表作：《森林里的钟表店》', 'resolve', 'data-label="已关联演示源 A 的青木"') + choice('青木 · 演示源 B', '代表作：《月下航线》', 'resolve', 'data-label="已关联演示源 B 的青木"') : actions(item.options.map((label, i) => action(label, item.id === 'source' ? (i ? 'match-retry' : 'source') : 'resolve', i === 0, `data-label="${esc(label)}"`)).join(''))) + message() + actions(action('稍后处理', 'home'));
    }
    function importPage() {
      const d = data(); const device = currentDevice(); const empty = d.importKind === 'empty';
      return `<h3>${empty ? '接收已有数据' : '合并此设备的数据'}</h3>` + note('两端数据将合并。本设备没有的收藏或关注，不代表取消其他设备的收藏或关注。') + `<div class="ix-comparison">${pair('本机收藏', empty ? '0 本' : `${device.favorites.length} 本`)}${pair('本机关注', empty ? '0 位' : `${device.following.length} 位`)}${pair('本机阅读记录', empty ? '0 条' : `${Object.keys(device.readPositions).length} 条`)}</div>` + note(empty ? '空书架不会上传取消操作。完成接收后即可继续阅读。' : '若旧备份中的收藏与其他设备当前的取消状态不同，将放入待手动处理列表。') + actions(action(empty ? '开始接收' : '开始合并', 'import-start', true));
    }
    function progressPage() {
      const d = data();
      return `<h3>${d.importPaused ? '合并尚未完成' : '正在合并数据'}</h3><progress aria-label="合并进度" value="${d.importProgress || 0}" max="100"></progress>` + note(`已完成 ${d.importProgress || 0}% · 已合并的数据会保留`) + note('可以收起面板，继续使用书架。') + actions(d.importPaused ? action('继续合并', 'import-resume', true) : action('暂停合并', 'import-pause'));
    }
    function renderScreen() {
      const d = data(); let body = '';
      switch (screen()) {
        case 'setup':
          body = note('使用你的 GitHub 私有仓库保存同步数据。每台设备都可以独立同步。') + choice('创建同步空间', '第一次设置 · 使用空的专用仓库', 'create') + choice('加入已有空间', '已在其他设备设置 · 准备好恢复密钥', 'join'); break;
        case 'connection':
          body = section(d.mode === 'create' ? '创建空间 · 1 / 3' : '加入空间 · 1 / 3') + field('repo', 'GitHub 私有仓库', 'text', '用户名/仓库名') + field('token', '本设备访问令牌', 'password', '输入访问令牌') + note('令牌只需拥有此仓库的读写权限，仅保存在本设备。') + message() + actions(action('测试连接', 'test') + action('继续', 'connection-next', true)); break;
        case 'key':
          body = section(`${d.mode === 'create' ? '创建' : '加入'}空间 · 2 / 3`) + (d.mode === 'create' ? `<h3>保存恢复密钥</h3>${note('新设备加入时需要这份密钥。请保存在你能找到的安全位置。')}<div class="ix-key">${esc(sampleKey)}</div><label class="ix-check"><input type="checkbox" data-ix-field="saved" ${d.saved ? 'checked' : ''}>我已保存恢复密钥</label>` : field('key', '已有空间的恢复密钥', 'password', '粘贴恢复密钥') + note('请从已连接的设备 → 同步设置 → 恢复资料中获取。')) + message() + actions(action(d.mode === 'create' ? '继续' : '验证并继续', 'key-next', true)); break;
        case 'import': body = section('确认合并 · 3 / 3') + importPage(); break;
        case 'importing': body = progressPage(); break;
        case 'imported': body = `<div class="ix-hero-icon">${view.icon('check')}</div><h3>同步空间已就绪</h3>` + note('收藏、关注和阅读记录已完成合并。阅读模式保持本设备原有设置。') + note(d.importKind === 'empty' ? '已接收其他设备的数据。' : '有 1 项旧备份收藏需要你决定，已加入待处理列表。') + actions(action('查看同步', 'import-done', true)); break;
        case 'credentials': body = note('更新本设备的访问令牌，不影响其他设备的凭据。') + field('token', '新的访问令牌', 'password', '输入新的令牌') + message() + actions(action('验证并保存', 'credentials-save', true)); break;
        case 'recovery': body = note('只有在新设备加入或恢复访问时才需要查看。此密钥对应当前同步空间。') + pair('同步空间', d.fields.repo) + (d.showKey ? `<div class="ix-key">${esc(sampleKey)}</div>` : '<div class="ix-key">•••• •••• •••• ••••</div>') + actions(action(d.showKey ? '隐藏密钥' : '查看恢复密钥', 'reveal-key')); break;
        case 'frequency': body = note(isWindows() ? '应用运行期间按此频率执行。' : '系统允许时执行，所选时间是期望间隔。') + [15, 60, 360, 1440].map(minutes => choice(`${minutes === currentDevice().settings.periodMinutes ? '✓ ' : ''}${minutes < 60 ? minutes + ' 分钟' : minutes / 60 + ' 小时'}`, minutes === 60 ? '默认' : '仅此设备', 'period', `data-minutes="${minutes}"`)).join(''); break;
        case 'device': body = field('device', '此设备的名称') + note('此名称帮助你识别待处理条目来自哪台设备。') + actions(action('保存名称', 'device-save', true)); break;
        case 'switch':
        case 'disconnect': body = `<h3>${screen() === 'switch' ? '连接另一个同步空间？' : '断开此设备？'}</h3>` + note('本机书架、关注和阅读记录会保留。其他设备与远端数据不受影响，尚未上传的操作不会继续上传到此空间。') + (screen() === 'switch' ? note('新空间需重新验证密钥，并确认首次合并范围。不会直接混合两个空间。') : '') + actions(action('返回设置', 'back') + action(screen() === 'switch' ? '更换空间' : '断开连接', 'disconnect-confirm', true)); break;
        case 'issue': body = issuePage(); break;
        case 'item': body = itemPage(); break;
        case 'source': body = `<h3>Mihon 演示源</h3>` + pair('关联漫画', '远山来信') + pair('可用状态', d.sourceReady ? '已启用' : '尚未启用') + note('启用对应图源后，再匹配已接收的漫画记录。') + message() + actions(action(d.sourceReady ? '返回并重试匹配' : '启用图源', d.sourceReady ? 'source-return' : 'source-enable', true)); break;
        case 'privacy': body = `<h3>阅读记录的同步范围</h3>` + note('续读位置、章节已读与明确未读、阅读历史会参与同步。阅读模式与其他阅读器显示设置仅保存在本设备。') + section('无痕阅读') + note('在“更多 → 无痕模式”开启。无痕期间的阅读不加入上传队列，退出后也不会补上传。') + section('清除历史') + note('只清除此设备的历史，不删除其他设备的记录。已清除的历史不会因旧记录再次到达而立即出现。'); break;
      }
      return `<div class="sheet-settings-content sync-settings-page ix-page" data-ix-screen="${screen()}">${body}</div>`;
    }
    function issuePage() {
      const kind = data().issue || 'network';
      const labels = { network: ['暂时无法连接', '待上传操作已保留。连接恢复后可以重试。', '重新同步', 'retry'], access: ['访问令牌需要更新', '当前令牌已失效，或没有此仓库的读写权限。待上传操作已保留。', '更新访问令牌', 'credentials'], key: ['恢复密钥不匹配', '无法解锁这个同步空间。请核对仓库和已连接设备提供的恢复密钥。', '重新输入恢复密钥', 'repair-key'], empty: ['这个仓库还没有同步空间', '可以在此初始化一个空间，或返回输入已有空间的仓库。', '初始化同步空间', 'initialize'], unknown: ['正在核对上传结果', '上次连接在上传后中断。先核对结果，再继续同步；待上传数量暂时保留。', '继续核对', 'retry'] };
      const [title, detail, label, name] = labels[kind];
      return `<h3>${title}</h3>` + note(detail) + pair('同步空间', data().fields.repo) + message() + actions(action(label, name, true) + action('返回同步', 'home'));
    }
    function renderBatch() {
      const batch = data().batch;
      if (!batch) return '';
      return `<div class="ix-progress" role="status" data-testid="ix-batch-progress"><strong>${batch.running ? '正在批量处理' : batch.done === batch.total ? '本次处理已完成' : '本次处理部分完成'}</strong><span>已处理 ${batch.done} / ${batch.total}</span><progress aria-label="批量进度" value="${batch.done}" max="${batch.total}"></progress>${note(batch.running ? '收起面板后继续处理；新到达的条目不包含在本次选择内。' : `已完成 ${batch.done - (batch.skipped || 0)} 项 · 跳过 ${batch.skipped || 0} 项 · 失败 ${batch.failed || 0} 项，剩余 ${batch.total - batch.done} 项待处理`)}${actions(batch.running ? action('停止后续处理', 'batch-stop') : batch.done < batch.total ? action('继续处理剩余项', 'batch-resume', true) : action('收起结果', 'batch-dismiss'))}</div>`;
    }
    let importTimer = null;
    function advanceImport() {
      clearTimeout(importTimer); data().importPaused = false;
      importTimer = setTimeout(() => {
        data().importProgress = Math.min(100, (data().importProgress || 0) + 25);
        if (data().importProgress === 100) { data().connected = true; data().importReady = true; if (screen() === 'importing') data().stack[data().stack.length - 1] = 'imported'; }
        else advanceImport();
        render();
      }, 650);
    }
    let batchTimer = null;
    let batchStep = null;
    function startBatch(total, step) {
      data().batch = { total, done: 0, skipped: 0, failed: 0, running: true };
      batchStep = step;
      advanceBatch();
    }
    function advanceBatch() {
      const batch = data().batch; if (!batch) return;
      clearTimeout(batchTimer); batch.running = true;
      batchTimer = setTimeout(() => {
        const next = Math.min(batch.total, batch.done + Math.max(1, Math.ceil(batch.total / 6)));
        if (batchStep) batch.skipped += batchStep(batch.done, next);
        batch.done = next;
        batch.failed = 0;
        if (batch.done < batch.total) advanceBatch(); else batch.running = false;
        render();
      }, 650);
    }
    function showScenario(name) {
      clearTimeout(importTimer); clearTimeout(batchTimer);
      batchStep = null;
      state.ui.interactions = null; const d = data();
      state.ui.syncOpen = true; state.ui.syncSettingsOpen = false; state.ui.route = 'library'; state.ui.detail = null; state.ui.reader = false;
      state.ui.batchReview = null; state.ui.selecting = false; state.ui.syncResult = null; state.ui.batchResult = null;
      if (name === 'setup') d.connected = false;
      else if (name === 'mixed') d.items = samples();
      else if (name === 'import' || name === 'empty-device') { d.importKind = name === 'import' ? 'merge' : 'empty'; go('import'); }
      else if (name === 'interrupted') { d.importProgress = 50; d.importPaused = true; go('importing'); }
      else if (name === 'batch') d.batch = { total: 120, done: 72, skipped: 2, failed: 3, running: false };
      else if (['network', 'access', 'key', 'empty', 'unknown'].includes(name)) d.issue = name;
      render();
    }
    function handle(name, target) {
      if (!name.startsWith('ix-')) return false;
      const actionName = name.slice(3); const d = data();
      if (['setup', 'credentials', 'recovery', 'frequency', 'device', 'disconnect', 'switch', 'issue', 'source', 'privacy'].includes(actionName)) { if (actionName === 'recovery') d.showKey = false; go(actionName); }
      else if (actionName === 'back') back();
      else if (actionName === 'home') home();
      else if (actionName === 'connection-info') { d.message = '此设备连接到 ' + d.fields.repo + '。共享空间相同，各设备的访问令牌独立保存。'; }
      else if (actionName === 'create' || actionName === 'join') { d.mode = actionName; d.saved = false; go('connection'); }
      else if (actionName === 'test') d.message = d.issue === 'access' ? '无法访问仓库，请更新令牌并确认读写权限。' : '连接成功，仓库可读写。';
      else if (actionName === 'connection-next') {
        if (!d.fields.repo.trim() || !d.fields.token.trim()) d.message = '请填写仓库和访问令牌。演示可填写任意示例值。';
        else go('key');
      } else if (actionName === 'key-next') {
        if (d.mode === 'create' && !d.saved) d.message = '请先确认已保存恢复密钥。';
        else if (d.mode === 'join' && !d.fields.key.trim()) d.message = '请填写恢复密钥。';
        else { d.issue = null; if (d.repairingKey) { d.repairingKey = false; home('恢复密钥已验证，可以继续同步。'); } else go('import'); }
      } else if (actionName === 'import-start') { d.connected = true; d.importProgress = 0; d.importReady = false; go('importing'); advanceImport(); }
      else if (actionName === 'import-resume') advanceImport();
      else if (actionName === 'import-pause') { clearTimeout(importTimer); d.importPaused = true; }
      else if (actionName === 'import-done') { if (d.importKind !== 'empty') d.items = [samples().find(item => item.id === 'restore')]; d.importReady = false; d.importProgress = null; home(); }
      else if (actionName === 'import-view') go(d.importReady ? 'imported' : 'importing');
      else if (actionName === 'credentials-save') {
        if (!d.fields.token.trim()) d.message = '请填写新的访问令牌。';
        else { d.fields.token = ''; d.issue = null; back(); if (screen() === 'issue') home(); d.message = '访问令牌已更新，连接测试通过。'; }
      } else if (actionName === 'reveal-key') d.showKey = !d.showKey;
      else if (actionName === 'period') { currentDevice().settings.periodMinutes = Number(target.dataset.minutes); back(); }
      else if (actionName === 'device-save') { if (!d.fields.device.trim()) d.fields.device = currentDevice().name; currentDevice().name = d.fields.device; back(); }
      else if (actionName === 'disconnect-confirm') { const switching = screen() === 'switch'; d.connected = false; d.fields.token = ''; d.fields.key = ''; home(); if (switching) go('setup'); }
      else if (actionName === 'initialize') { d.mode = 'create'; d.issue = null; go('key'); }
      else if (actionName === 'repair-key') { d.mode = 'join'; d.repairingKey = true; go('key'); }
      else if (actionName === 'retry') { d.issue = null; home('同步已恢复。上传 3 条，接收 2 条，1 项待手动处理。'); }
      else if (actionName === 'item') { d.selectedItem = target.dataset.item; go('item'); }
      else if (actionName === 'resolve') { d.items = d.items.filter(item => item.id !== d.selectedItem); home('已处理：' + target.dataset.label); }
      else if (actionName === 'match-retry') { if (d.sourceReady) { d.items = d.items.filter(item => item.id !== 'source'); home('《远山来信》已匹配到对应来源。'); } else d.message = '来源仍不可用，请先查看并启用对应图源。'; }
      else if (actionName === 'source-enable') { d.sourceReady = true; d.message = '图源已启用，可以重试匹配。'; }
      else if (actionName === 'source-return') back();
      else if (actionName === 'batch-stop') { clearTimeout(batchTimer); d.batch.running = false; }
      else if (actionName === 'batch-resume') advanceBatch();
      else if (actionName === 'batch-dismiss') d.batch = null;
      return true;
    }
    function input(target) {
      if (!target.dataset.ixField) return;
      if (target.dataset.ixField === 'saved') data().saved = target.checked;
      else data().fields[target.dataset.ixField] = target.value;
    }
    return { screen, title: () => titles[screen()], back, close, settings, settingsFooter, renderScreen, unconfigured, connected: () => data().connected, count: () => data().items.length, items, summary, handle, input, showScenario, startBatch, batchActive: () => data().batch && data().batch.done < data().batch.total,
      importStatus: () => data().importProgress != null ? row(data().importReady ? '合并已完成' : data().importPaused ? '合并尚未完成' : '正在合并数据', '查看进度与继续操作', 'import-view') : '',
      busy: () => data().issue === 'unknown' || (data().importProgress != null && !data().importPaused && !data().importReady),
    };
  }
})();
