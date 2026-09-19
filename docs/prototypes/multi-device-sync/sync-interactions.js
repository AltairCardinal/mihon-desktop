(function () {
  'use strict';

  // UI storyboards only. Authorization, password protection, lookup and import do not call a service.
  window.MihonSyncInteractions = { create };
  function create({ state, currentDevice, isWindows, esc, view, button, render }) {
    const titles = { authorize: '连接 GitHub', password: '同步密码', lookup: '查找同步空间', creating: '创建同步空间', 'setup-error': '同步空间未连接', import: '首次合并', importing: '合并进度', imported: '合并完成', frequency: '同步频率', device: '设备名称', disconnect: '断开同步', switch: '更换同步空间', issue: '同步详情', activity: '同步记录', privacy: '阅读与历史' };
    const cancellationExample = Object.values(state.devices).flatMap(device => device.confirmations)[0];
    function data() {
      if (!state.ui.interactions) state.ui.interactions = { connected: true, stack: [], fields: { repo: 'reader/mihon-sync', password: '', device: currentDevice().name }, automatic: false, message: '', issue: null, mode: 'join', importKind: 'merge', lastSync: '14:32', authStatus: 'idle', spaceScenario: 'new', setupStage: null, passwordProtected: false };
      if (state.ui.interactions.nextSyncAt == null) state.ui.interactions.nextSyncAt = Date.now() + currentDevice().settings.periodMinutes * 60000;
      return state.ui.interactions;
    }
    function resetCountdown() { data().nextSyncAt = Date.now() + currentDevice().settings.periodMinutes * 60000; }
    function countdownTitle() {
      const minutes = Math.max(0, Math.ceil((data().nextSyncAt - Date.now()) / 60000));
      if (!minutes) return '即将同步';
      return [[Math.floor(minutes / 1440), '天'], [Math.floor(minutes % 1440 / 60), '小时'], [minutes % 60, '分']]
        .filter(([value]) => value > 0).map(([value, unit]) => `${value}${unit}`).join('') + '后同步';
    }
    // Update only the visible label so countdown ticks preserve list scroll and selection.
    window.setInterval(() => {
      const label = document.querySelector('[data-sync-countdown]');
      if (label) label.textContent = countdownTitle();
    }, 1000);
    const screen = () => data().stack.at(-1);
    function go(name) { data().stack.push(name); data().message = ''; }
    function back() { data().stack.pop(); data().message = ''; }
    function home(message = '') { data().stack = []; data().message = message; state.ui.syncSettingsOpen = false; }
    function close() { data().stack = []; data().message = ''; data().fields.password = ''; data().showPassword = false; if (data().batch?.done === data().batch?.total) data().batch = null; }
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
      return section('同步账号') + row(d.connected ? 'GitHub · reader' : '连接 GitHub', d.connected ? '已连接 · ' + d.fields.repo : '在浏览器中登录并授权', d.connected ? 'connection-info' : 'setup', 'cloud') + (d.connected ? row('检查连接', d.issue ? '上次连接未完成' : '本设备可以访问同步空间', 'test') + row('重新连接 GitHub', '更换账号或重新授权', 'reconnect') + pair('同步密码', d.passwordProtected ? '密码保护已开启' : '密码保护未开启') : '') + message() + section('此设备') + row('设备名称', d.fields.device, 'device');
    }
    function settingsFooter() {
      const minutes = currentDevice().settings.periodMinutes;
      return row('同步频率', minutes < 60 ? `${minutes} 分钟` : `${minutes / 60} 小时`, 'frequency') + note(isWindows() ? '定期同步在应用运行期间执行。关闭自动同步后，仍可手动同步。' : '系统允许时自动同步，实际执行时间可能延后。关闭自动同步后，仍可手动同步。') + section('数据范围') + row('阅读与历史', '阅读记录参与同步，阅读模式仅保存在此设备', 'privacy') + (data().connected ? section('管理连接') + row('更换同步空间', '', 'switch') + row('断开此设备', '', 'disconnect') : '');
    }
    function unconfigured() {
      return `<section class="sync-content ix-welcome"><div class="ix-hero-icon">${view.icon('sync')}</div><h3>让阅读跟随你</h3>${note('在设备之间同步收藏、作者关注和阅读记录。登录 GitHub 后，将自动查找并连接你的专用同步空间。')}${actions(action(data().setupStage ? '继续连接同步空间' : '连接 GitHub', 'setup', true))}${note('阅读模式保留为各设备独立设置。')}</section>`;
    }
    function issues() {
      const unknown = data().issue === 'unknown';
      return data().issue ? `<button class="ix-status-link" data-action="ix-issue" data-testid="ix-issue">${view.icon(unknown ? 'sync' : 'info')}<span><strong>${unknown ? '正在核对上传结果' : '上次同步未完成'}</strong><small>${unknown ? '待上传数量会保留到核对完成' : '查看详情与恢复操作'}</small></span>${view.icon('chevron')}</button>` : '';
    }
    function summary() { return issues() + renderBatch() + message(); }
    function activityPage() {
      const entries = data().automatic ? [
        ['白河 · 自动保留关注', '两端同时作出不同操作时，优先保留收藏或关注。'],
        ['星海骑士 · 阅读记录已合并', '正在阅读的页面保持不变，下次打开时采用最近位置。其他位置可从阅读历史查看。'],
        ['星海骑士 · 第 12 话保留未读', '两端同时操作时，优先保留明确的未读标记。'],
        ['黎明邮局 · 旧备份已合并', '旧收藏不覆盖已有取消；本设备的取消仍可在确认列表处理。'],
        ['远山来信 · 记录已保存', '来源恢复后自动关联，其他数据照常同步。'],
        ['青木 · 保留各来源的作者记录', '同名作者不自动合并，也无需在同步时选择。'],
        ['夜行纪事 · 已保留第 8 话位置', '页码无法验证，下次打开时从本章开头继续。'],
      ] : [['数据已交换', '收藏、作者关注和阅读记录通过同一个同步按钮交换。'], ['取消确认独立处理', '待确认的取消不阻止其他变动同步。保留在本设备不会反向改变其他设备。']];
      return entries.map(([title, detail]) => `<article class="ix-activity-entry"><span class="row-leading">${view.icon('check')}</span><div><strong>${title}</strong><p>${detail}</p></div></article>`).join('');
    }
    function status({ total, membership, reading, pending, busy, online }) {
      const d = data();
      if (d.changes) { membership = d.changes.membership; reading = d.changes.reading; total = membership + reading; }
      let title = total ? `有 ${total} 项变动等待同步` : pending ? '数据交换已完成' : '已同步';
      let detail = total ? `收藏与关注 ${membership} 项 · 阅读记录 ${reading} 项` : pending ? `${pending} 项取消操作待确认，其他数据已同步` : `上次同步 ${d.lastSync}`;
      const showCountdown = currentDevice().settings.periodicSync && !busy && online && !d.issue;
      if (showCountdown) title = countdownTitle();
      if (busy) { title = '正在同步'; detail = total ? `收藏与关注 ${membership} 项 · 阅读记录 ${reading} 项` : '正在接收其他设备的变动'; }
      else if (!online || d.issue) { title = d.issue === 'unknown' ? '正在核对同步结果' : '同步尚未完成'; detail = total ? `已保留 ${total} 项变动，稍后继续同步` : '已保存现有数据，可以稍后重试'; }
      return `<div class="native-sync-status sync-status-single" data-testid="sync-status-row"><div class="sync-symbol">${view.icon('sync')}</div><div class="sync-status-copy"><strong${showCountdown ? ' data-sync-countdown' : ''}>${title}</strong><small>${detail}</small></div>${button('立即同步', 'data-action="sync-manual" data-testid="manual-sync"', 'm-button-primary')}</div>`;
    }
    function didSync(ok) { if (ok) { data().changes = null; data().lastSync = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }); resetCountdown(); } }
    function importPage() {
      const d = data(); const device = currentDevice(); const empty = d.importKind === 'empty';
      return `<h3>${empty ? '接收已有数据' : '合并此设备的数据'}</h3>` + note('两端数据将合并。本设备没有的收藏或关注，不代表取消其他设备的收藏或关注。') + `<div class="ix-comparison">${pair('本机收藏', empty ? '0 本' : `${device.favorites.length} 本`)}${pair('本机关注', empty ? '0 位' : `${device.following.length} 位`)}${pair('本机阅读记录', empty ? '0 条' : `${Object.keys(device.readPositions).length} 条`)}</div>` + note(empty ? '空书架不会上传取消操作。完成接收后即可继续阅读。' : '若旧备份中的收藏与其他设备当前的取消状态不同，将放入待手动处理列表。') + actions(action(empty ? '开始接收' : '开始合并', 'import-start', true));
    }
    function importLog() {
      const d = data();
      const entries = d.importLog || [
        { title: '同步空间索引', detail: '已读取远端变动', status: 'done' },
        { title: '《星海骑士》收藏', detail: '已合并到本设备', status: 'done' },
        { title: '《黎明邮局》阅读记录', detail: '已保存第 12 页', status: 'active' },
        { title: '《远山来信》作者关注', detail: '等待继续处理', status: 'pending' },
        { title: '《夜行纪事》阅读记录', detail: '等待继续处理', status: 'pending' },
      ];
      const status = { done: ['已完成', 'check'], active: ['处理中', 'sync'], pending: ['待处理', 'more'] };
      return `<section class="sync-item-log" data-testid="sync-item-log" aria-label="同步条目日志"><div class="sync-item-log-heading"><strong>条目记录</strong><span>最近 ${entries.length} 项</span></div><ol>${entries.map(entry => { const [label, icon] = status[entry.status] || status.pending; return `<li class="sync-log-item ${entry.status}"><span class="sync-log-status" aria-label="${label}">${view.icon(icon)}</span><div><strong>${esc(entry.title)}</strong><small>${esc(entry.detail)}</small></div><em>${label}</em></li>`; }).join('')}</ol></section>`;
    }
    function progressPage() {
      const d = data();
      const total = d.importTotal || 60;
      const processed = d.importProcessed ?? Math.round((d.importProgress || 0) / 100 * total);
      const recovery = d.importRecovery === 'sleep'
        ? '设备待机后已暂停同步'
        : d.importRecovery === 'resuming'
          ? '正在继续同步'
          : d.importPaused
            ? '同步已暂停'
            : '正在合并数据';
      const detail = recovery === '设备待机后已暂停同步'
        ? '设备待机后已暂停同步。设备刚从待机恢复，已完成的条目已保留；继续后会从未完成条目接着处理。'
        : recovery === '正在继续同步'
          ? '正在继续同步，已从上次未完成的条目继续。'
          : '已完成的条目会保留，可以收起面板继续使用书架。';
      return `<h3>${recovery}</h3><p class="sync-recovery-state" data-testid="sync-recovery-state">${detail}</p><div class="sync-import-progress" data-testid="sync-import-progress-card"><div class="sync-import-progress-heading"><strong>合并同步空间</strong><span data-testid="sync-import-count">已处理 ${processed} / ${total} 项</span></div><progress aria-label="同步进度" data-testid="sync-import-progress" value="${processed}" max="${total}"></progress></div>${importLog()}${actions(d.importPaused ? action('继续同步', 'import-resume', true) : action('暂停同步', 'import-pause'))}`;
    }
    let authWindow = null;
    let authRequest = 0;
    function startAuth(reconnect = false) {
      authWindow?.close(); authWindow = null;
      data().reconnecting = reconnect; data().authStatus = 'ready'; authRequest += 1;
      go('authorize');
    }
    function openAuthorization() {
      const url = new URL('./github-authorization-demo.html', document.baseURI);
      url.searchParams.set('request', String(authRequest));
      if (data().reconnecting) url.searchParams.set('reconnect', '1');
      authWindow = window.open(url.href, '_blank', 'popup,width=540,height=760');
      if (authWindow) {
        data().authStatus = 'waiting';
        if (navigator.clipboard?.writeText) navigator.clipboard.writeText('DEMO-CODE').catch(() => {});
      } else data().message = '请允许打开授权窗口，然后重试。也可以手动复制上方验证码。';
    }
    window.addEventListener('message', event => {
      const reply = event.data;
      if (event.source !== authWindow || !reply || reply.type !== 'mihon-demo-authorization' || reply.request !== String(authRequest)) return;
      if (reply.allowed) {
        data().authStatus = 'success';
        if (data().reconnecting) { data().issue = null; data().connected = true; home(state.ui.syncOpen ? 'GitHub 已重新连接，可以继续同步。' : ''); }
        else findSpace();
      } else data().authStatus = 'denied';
      render();
    });
    function authorizationPage() {
      const d = data();
      if (d.authStatus === 'expired' || d.authStatus === 'denied') return `<h3>${d.authStatus === 'expired' ? '验证码已过期' : '本次授权已取消'}</h3>` + note('本设备的数据保持原样，你可以重新连接。') + actions(action(d.authStatus === 'expired' ? '重新获取验证码' : '重新授权', 'auth-restart', true));
      return `<h3>在浏览器中登录 GitHub</h3>` + note('打开 GitHub 后输入下面的设备验证码，并授权 Mihon 连接同步空间。') + '<div class="ix-device-code" aria-label="设备验证码">DEMO-CODE</div>' + actions(action('复制验证码并打开 GitHub', 'open-github', true)) + note(d.authStatus === 'waiting' ? '等待你在 GitHub 完成授权，完成后这里会自动更新。' : '验证码在 15 分钟内有效。你也可以手动复制验证码。') + message();
    }
    function renderScreen() {
      const d = data(); let body = '';
      switch (screen()) {
        case 'authorize': body = authorizationPage(); break;
        case 'lookup': body = '<h3>正在查找同步空间</h3>' + note('正在检查专用私有同步空间。可以收起面板，稍后继续。'); break;
        case 'creating': body = '<h3>正在创建同步空间</h3>' + note('正在准备专用私有同步空间。可以收起面板，稍后继续。'); break;
        case 'setup-error': body = `<h3>${d.setupFailure === 'find' ? '暂时无法查找同步空间' : '同步空间创建未完成'}</h3>` + note('本设备的数据已保留，未覆盖已有数据。重试会先检查同步空间是否已经存在。') + actions(action('重试', 'setup-retry', true)); break;
        case 'password': body = `<h3>${d.mode === 'create' ? '设置同步密码' : '输入同步密码'}</h3>` + note(d.mode === 'create' ? '将创建同步空间，是否需要设置密码？设置后，其他设备必须输入正确密码才能连接这个同步空间。' : '已找到受密码保护的同步空间。请输入正确的同步密码，连接后将自动合并数据。') + field('password', d.mode === 'create' ? '同步密码（可选）' : '同步密码', d.showPassword ? 'text' : 'password') + actions(action(d.showPassword ? '隐藏密码' : '显示密码', 'password-toggle')) + message() + actions(action(d.mode === 'join' ? '连接同步空间' : d.fields.password === '' ? '不设置密码' : '确认密码', 'password-confirm', true, d.mode === 'join' && d.fields.password === '' ? 'disabled' : '')); break;
        case 'import': body = section('确认合并 · 3 / 3') + importPage(); break;
        case 'importing': body = progressPage(); break;
        case 'imported': body = `<div class="ix-hero-icon">${view.icon('check')}</div><h3>同步空间已就绪</h3>` + note('收藏、关注和阅读记录已完成合并。阅读模式保持本设备原有设置。') + note('如有取消收藏或关注，仍由你决定是否在此设备取消，其他数据已同步。') + actions(action('查看同步', 'import-done', true)); break;
        case 'frequency': body = note(isWindows() ? '应用运行期间按此频率执行。' : '系统允许时执行，所选时间是期望间隔。') + [15, 60, 360, 1440].map(minutes => choice(`${minutes === currentDevice().settings.periodMinutes ? '✓ ' : ''}${minutes < 60 ? minutes + ' 分钟' : minutes / 60 + ' 小时'}`, minutes === 60 ? '默认' : '仅此设备', 'period', `data-minutes="${minutes}"`)).join(''); break;
        case 'device': body = field('device', '此设备的名称') + note('此名称帮助你识别待处理条目来自哪台设备。') + actions(action('保存名称', 'device-save', true)); break;
        case 'switch':
        case 'disconnect': body = `<h3>${screen() === 'switch' ? '连接另一个同步空间？' : '断开此设备？'}</h3>` + note('本机书架、关注和阅读记录会保留。其他设备与远端数据不受影响，尚未上传的操作不会继续上传到此空间。') + (screen() === 'switch' ? note('将重新登录 GitHub，自动查找该账号的专用同步空间；受密码保护时需输入正确密码。') : '') + actions(action('返回设置', 'back') + action(screen() === 'switch' ? '更换空间' : '断开连接', 'disconnect-confirm', true)); break;
        case 'issue': body = issuePage(); break;
        case 'activity': body = activityPage(); break;
        case 'privacy': body = `<h3>阅读记录的同步范围</h3>` + note('续读位置、章节已读与明确未读、阅读历史会参与同步。阅读模式与其他阅读器显示设置仅保存在本设备。') + section('无痕阅读') + note('在“更多 → 无痕模式”开启。无痕期间的阅读不加入上传队列，退出后也不会补上传。') + section('清除历史') + note('只清除此设备的历史，不删除其他设备的记录。已清除的历史不会因旧记录再次到达而立即出现。'); break;
      }
      return `<div class="sheet-settings-content sync-settings-page ix-page" data-ix-screen="${screen()}">${body}</div>`;
    }
    function issuePage() {
      const kind = data().issue || 'network';
      const labels = { network: ['暂时无法连接', '本设备的变动已保留。连接恢复后可以重试。', '重新同步', 'retry'], access: ['需要重新连接 GitHub', '登录授权已失效，或同步空间的访问权限发生变化。本设备的变动已保留。', '重新连接 GitHub', 'reconnect'], key: ['同步密码不正确', '无法连接这个同步空间。请输入设置空间时使用的同步密码。', '重新输入同步密码', 'repair-password'], empty: ['尚未创建同步空间', '可以自动创建专用私有同步空间。', '创建同步空间', 'initialize'], unknown: ['正在核对同步结果', '上次连接在上传后中断。核对结果后继续同步，本设备的变动暂时保留。', '继续核对', 'retry'] };
      const [title, detail, label, name] = labels[kind];
      return `<h3>${title}</h3>` + note(detail) + pair('同步空间', data().fields.repo) + message() + actions(action(label, name, true) + action('返回同步', 'home'));
    }
    function renderBatch() {
      const batch = data().batch;
      if (!batch) return '';
      return `<div class="ix-progress" role="status" data-testid="ix-batch-progress"><strong>${batch.running ? '正在批量处理' : batch.done === batch.total ? '本次处理已完成' : '本次处理部分完成'}</strong><span>已处理 ${batch.done} / ${batch.total}</span><progress aria-label="批量进度" value="${batch.done}" max="${batch.total}"></progress>${note(batch.running ? '收起面板后继续处理；新到达的条目不包含在本次选择内。' : `已完成 ${batch.done - (batch.skipped || 0)} 项 · 跳过 ${batch.skipped || 0} 项 · 失败 ${batch.failed || 0} 项，剩余 ${batch.total - batch.done} 项待处理`)}${actions(batch.running ? action('停止后续处理', 'batch-stop') : batch.done < batch.total ? action('继续处理剩余项', 'batch-resume', true) : action('收起结果', 'batch-dismiss'))}</div>`;
    }
    let setupTimer = null;
    function setupPage(name) {
      const d = data(); d.setupStage = name; d.message = '';
      if (state.ui.syncOpen && (!screen() || ['authorize', 'lookup', 'creating', 'password', 'setup-error', 'issue'].includes(screen()))) d.stack = [name];
    }
    function findSpace() {
      clearTimeout(setupTimer); setupPage('lookup');
      setupTimer = setTimeout(() => {
        const d = data();
        if (d.setupFailure === 'find') setupPage('setup-error');
        else if (d.spaceScenario === 'existing') { d.passwordProtected = false; beginImport(); }
        else if (d.spaceScenario === 'new' && d.retryCreation) { d.retryCreation = false; createSpace(); }
        else { d.retryCreation = false; d.mode = d.spaceScenario === 'protected' ? 'join' : 'create'; setupPage('password'); }
        render();
      }, 350);
    }
    function createSpace() {
      const d = data(); clearTimeout(setupTimer); setupPage('creating');
      setupTimer = setTimeout(() => {
        if (d.setupFailure === 'create') setupPage('setup-error');
        else { d.spaceScenario = d.passwordProtected ? 'protected' : 'existing'; beginImport(); }
        render();
      }, 350);
    }
    function beginImport() {
      const d = data(); d.fields.password = ''; d.showPassword = false; d.importProgress = 0; d.importTotal = d.importKind === 'empty' ? 48 : 60; d.importProcessed = 0; d.importReady = false; d.importRecovery = 'active'; d.importLog = null;
      setupPage('importing'); advanceImport();
    }
    let importTimer = null;
    function advanceImport() {
      clearTimeout(importTimer);
      data().importPaused = false;
      data().importRecovery = data().importRecovery === 'resuming' ? 'resuming' : 'active';
      importTimer = setTimeout(() => {
        const d = data();
        const total = d.importTotal || 60;
        d.importProcessed = Math.min(total, (d.importProcessed || 0) + Math.max(1, Math.ceil(total / 5)));
        d.importProgress = Math.round(d.importProcessed / total * 100);
        d.importRecovery = 'active';
        if (d.importLog) {
          const completed = Math.floor(d.importProcessed / total * d.importLog.length);
          d.importLog.forEach((entry, index) => { entry.status = index < completed ? 'done' : index === completed ? 'active' : 'pending'; });
        }
        if (d.importProgress === 100) { d.connected = true; d.importReady = true; if (d.setupStage) { d.setupStage = null; d.importProgress = null; d.importReady = false; if (screen() === 'importing') home(state.ui.syncOpen ? '同步已开启' : ''); } else if (screen() === 'importing') d.stack[d.stack.length - 1] = 'imported'; }
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
      clearTimeout(importTimer); clearTimeout(batchTimer); clearTimeout(setupTimer);
      authWindow?.close(); authWindow = null; authRequest += 1;
      batchStep = null;
      state.ui.interactions = null; const d = data();
      state.ui.syncOpen = true; state.ui.syncSettingsOpen = false; state.ui.route = 'library'; state.ui.detail = null; state.ui.reader = false;
      state.ui.batchReview = null; state.ui.selecting = false; state.ui.syncResult = null; state.ui.batchResult = null;
      if (name.startsWith('setup')) { d.connected = false; d.spaceScenario = name === 'setup-existing' ? 'existing' : name === 'setup-protected' ? 'protected' : 'new'; d.setupFailure = name === 'setup-find-failed' ? 'find' : name === 'setup-create-failed' ? 'create' : null; }
      else if (name === 'mixed') d.automatic = true;
      else if (name === 'pending-upload') {
        d.changes = { membership: 3, reading: 2 };
        // Seed the visible cancellation example on either preview, without changing merge rules.
        if (!currentDevice().confirmations.length && cancellationExample) {
          currentDevice().confirmations.push({ ...cancellationExample, sourceName: isWindows() ? '手机 A' : '电脑 B' });
          if (!currentDevice().favorites.includes(cancellationExample.objectId)) currentDevice().favorites.push(cancellationExample.objectId);
        }
      }
      else if (name === 'auth-expired') { d.connected = false; d.authStatus = 'expired'; go('authorize'); }
      else if (name === 'import' || name === 'empty-device') { d.importKind = name === 'import' ? 'merge' : 'empty'; go('import'); }
      else if (name === 'interrupted') {
        d.importTotal = 60;
        d.importProcessed = 24;
        d.importProgress = 40;
        d.importPaused = true;
        d.importRecovery = 'sleep';
        d.importLog = [
          { title: '同步空间索引', detail: '已读取远端变动', status: 'done' },
          { title: '《星海骑士》收藏', detail: '已合并到本设备', status: 'done' },
          { title: '《黎明邮局》阅读记录', detail: '设备待机前已保存第 12 页', status: 'active' },
          { title: '《远山来信》作者关注', detail: '等待继续处理', status: 'pending' },
          { title: '《夜行纪事》阅读记录', detail: '等待继续处理', status: 'pending' },
        ];
        go('importing');
      }
      else if (name === 'batch') d.batch = { total: 120, done: 72, skipped: 2, failed: 3, running: false };
      else if (['network', 'access', 'key', 'empty', 'unknown'].includes(name)) d.issue = name;
      render();
    }
    function handle(name, target) {
      if (!name.startsWith('ix-')) return false;
      const actionName = name.slice(3); const d = data();
      if (['frequency', 'device', 'disconnect', 'switch', 'issue', 'activity', 'privacy'].includes(actionName)) { go(actionName); }
      else if (actionName === 'setup' || actionName === 'reconnect') { if (actionName === 'setup' && d.setupStage) d.stack = [d.setupStage]; else startAuth(actionName === 'reconnect'); }
      else if (actionName === 'open-github') openAuthorization();
      else if (actionName === 'auth-restart') { d.authStatus = 'ready'; authWindow?.close(); authWindow = null; authRequest += 1; }
      else if (actionName === 'password-toggle') {
        d.showPassword = !d.showPassword;
        const input = document.querySelector('[data-ix-field="password"]');
        if (input) { const selection = [input.selectionStart, input.selectionEnd]; input.type = d.showPassword ? 'text' : 'password'; input.focus({ preventScroll: true }); input.setSelectionRange(...selection); }
        target.textContent = d.showPassword ? '隐藏密码' : '显示密码';
        return true;
      }
      else if (actionName === 'setup-retry') { d.retryCreation = d.setupFailure === 'create'; d.setupFailure = null; findSpace(); }
      else if (actionName === 'password-confirm') {
        if (d.mode === 'join' && d.fields.password === '') d.message = '请输入同步密码。';
        else if (d.mode === 'join' && d.fields.password !== 'mihon-demo') d.message = '密码不正确，请重试。';
        else { d.passwordProtected = d.fields.password !== ''; d.fields.password = ''; d.showPassword = false; d.issue = null; if (d.mode === 'create') createSpace(); else beginImport(); }
      }
      else if (actionName === 'back') back();
      else if (actionName === 'home') home();
      else if (actionName === 'connection-info') { d.message = '当前账号 reader，已连接到 ' + d.fields.repo + '。'; }
      else if (actionName === 'test') d.message = d.issue === 'access' ? '暂时无法访问同步空间，请重新连接 GitHub。' : '连接正常，可以同步。';
      else if (actionName === 'import-start') { d.connected = true; d.importProgress = 0; d.importReady = false; go('importing'); advanceImport(); }
      else if (actionName === 'import-resume') { d.importRecovery = 'resuming'; advanceImport(); }
      else if (actionName === 'import-pause') { clearTimeout(importTimer); d.importPaused = true; d.importRecovery = d.importRecovery === 'sleep' ? 'sleep' : 'manual'; }
      else if (actionName === 'import-done') { d.automatic = true; d.importReady = false; d.importProgress = null; home(); }
      else if (actionName === 'import-view') go(d.importReady ? 'imported' : 'importing');
      else if (actionName === 'period') { currentDevice().settings.periodMinutes = Number(target.dataset.minutes); resetCountdown(); back(); }
      else if (actionName === 'device-save') { if (!d.fields.device.trim()) d.fields.device = currentDevice().name; currentDevice().name = d.fields.device; back(); }
      else if (actionName === 'disconnect-confirm') { const switching = screen() === 'switch'; d.connected = false; d.fields.password = ''; d.setupStage = null; home(); if (switching) startAuth(); }
      else if (actionName === 'initialize') { d.mode = 'create'; d.issue = null; setupPage('password'); }
      else if (actionName === 'repair-password') { d.mode = 'join'; d.spaceScenario = 'protected'; setupPage('password'); }
      else if (actionName === 'retry') { d.issue = null; didSync(true); home('同步已恢复，待确认的取消操作仍然保留。'); }
      else if (actionName === 'batch-stop') { clearTimeout(batchTimer); d.batch.running = false; }
      else if (actionName === 'batch-resume') advanceBatch();
      else if (actionName === 'batch-dismiss') d.batch = null;
      return true;
    }
    function input(target) {
      if (!target.dataset.ixField) return;
      data().fields[target.dataset.ixField] = target.value;
      if (target.dataset.ixField === 'password') { const submit = document.querySelector('[data-testid="ix-password-confirm"]'); if (submit) { submit.textContent = data().mode === 'join' ? '连接同步空间' : target.value === '' ? '不设置密码' : '确认密码'; submit.disabled = data().mode === 'join' && target.value === ''; } }
    }
    return { screen, title: () => titles[screen()], back, close, settings, settingsFooter, renderScreen, unconfigured, connected: () => data().connected, status, didSync, resetCountdown, summary, handle, input, showScenario, startBatch, batchActive: () => data().batch && data().batch.done < data().batch.total,
      importStatus: () => data().importProgress != null ? row(data().importReady ? '合并已完成' : data().importPaused ? '合并尚未完成' : '正在合并数据', '查看进度与继续操作', 'import-view') : '',
      busy: () => data().issue === 'unknown' || (data().importProgress != null && !data().importPaused && !data().importReady),
    };
  }
})();
