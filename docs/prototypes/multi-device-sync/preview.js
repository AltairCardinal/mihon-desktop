(function () {
  'use strict';
  const model = window.MihonSyncModel;
  const root = document.getElementById('preview');
  let sharedState = model.createDemoState();
  const frames = () => [...root.querySelectorAll('iframe')];
  const apps = () => frames().map(frame => frame.contentWindow?.__mihonSyncDemo).filter(Boolean);
  // Share the existing operation model, while navigation, timers and selection stay in each frame.
  const localKeys = new Set(['ui', 'selectedDevice']);
  window.MihonPreview = {
    theme: 'dark',
    connect() {
      const local = {};
      return new Proxy(local, {
        get: (_, key) => (localKeys.has(key) ? local : sharedState)[key],
        set: (_, key, value) => { (localKeys.has(key) ? local : sharedState)[key] = value; return true; },
        deleteProperty: (_, key) => delete (localKeys.has(key) ? local : sharedState)[key],
        ownKeys: () => [...new Set([...Object.keys(sharedState), ...Object.keys(local)])],
        getOwnPropertyDescriptor: () => ({ enumerable: true, configurable: true }),
      });
    },
    refreshOthers(source) {
      frames().filter(frame => frame.contentWindow !== source).forEach(frame => frame.contentWindow?.__mihonSyncDemo?.render(false));
    },
  };
  root.innerHTML = `<details class="preview-tools" data-testid="preview-tools" open>
    <summary><span class="tool-summary-icon">${window.MihonSyncView.icon('settings')}</span><strong>演示预览</strong><span class="tool-summary-muted">Windows Desktop 与 Android 并列</span></summary>
    <div class="preview-tool-panel">
      <div class="tool-group"><span>双端主题</span><div class="tool-choice"><button class="m-button" data-theme="light" data-testid="theme-light">浅色</button><button class="m-button is-selected" data-theme="dark" data-testid="theme-dark">深色</button></div></div>
      <div class="tool-group"><label for="trigger-device">触发设备</label><select id="trigger-device" aria-label="触发设备"><option value="windows">电脑 B</option><option value="android">手机 A</option></select><button class="m-button" data-trigger="startup" data-testid="startup-sync">模拟启动同步</button><button class="m-button" data-trigger="periodic" data-testid="periodic-sync">模拟定期到期</button></div>
      <div class="tool-group tool-actions"><button class="m-button" data-command="network" data-testid="network-toggle">切换离线</button><button class="m-button" data-command="many" data-testid="many-pending">120 项待处理</button><button class="m-button tool-reset" data-command="reset" data-testid="reset-demo">重置演示</button></div>
      <div class="tool-group"><label for="interaction-scene">交互场景</label><select id="interaction-scene" aria-label="交互场景"><option value="pending-upload">待确认期间继续同步</option><option value="mixed">自动处理分歧</option><option value="setup">GitHub 登录与首次设置</option><option value="auth-expired">设备验证码过期</option><option value="import">已有数据首次合并</option><option value="empty-device">空设备加入</option><option value="interrupted">合并中断与继续</option><option value="network">连接暂时中断</option><option value="access">需要重新连接 GitHub</option><option value="key">恢复密钥不匹配</option><option value="empty">空仓库待初始化</option><option value="unknown">上传结果待核对</option><option value="batch">批量部分完成</option></select><button class="m-button" data-command="scene" data-testid="show-interaction-scene">显示场景</button></div>
      <div class="tool-group"><label for="extension-scene">插件建议</label><select id="extension-scene" aria-label="插件建议场景"><option value="sample">多源收藏样本</option><option value="incomplete">目录不完整</option><option value="private">Android 私有安装</option><option value="permission">Android 缺少安装权限</option><option value="failure">下一项安装失败</option><option value="reset">重置插件建议</option></select><button class="m-button" data-command="extension-scene" data-testid="show-extension-scene">显示插件场景</button></div>
      <small class="preview-boundary">场景显示在所选设备。GitHub 授权在本地模拟窗口完成，无需真实账号；恢复资料可填示例文字。仅供交互审阅。</small>
    </div>
  </details><div class="parallel-scroll"><div class="device-pair"></div></div>`;

  function mountFrames(afterLoad) {
    frames().forEach(frame => {
      const timer = frame.contentWindow?.__mihonSyncDemo?.state.ui.timerId;
      if (timer) frame.contentWindow.clearTimeout(timer);
    });
    const pair = root.querySelector('.device-pair');
    pair.replaceChildren();
    for (const platform of ['windows', 'android']) {
      const column = document.createElement('section');
      column.className = 'device-column device-' + platform;
      const label = document.createElement('div');
      label.className = 'device-label';
      label.textContent = platform === 'windows' ? 'Windows Desktop · 电脑 B' : 'Android · 手机 A';
      const frame = document.createElement('iframe');
      frame.id = 'preview-' + platform;
      frame.dataset.platform = platform;
      frame.title = label.textContent;
      frame.srcdoc = '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="./styles.css"></head><body><div id="app" class="app-shell"></div><script src="./ui-view.js"></script><script src="./sync-interactions.js"></script><script src="./extension-suggestions.js"></script><script src="./app.js"></script></body></html>';
      frame.addEventListener('load', () => { if (afterLoad) afterLoad(frame); }, { once: true });
      column.append(label, frame);
      pair.append(column);
    }
  }
  root.addEventListener('click', event => {
    const button = event.target.closest('button');
    if (!button) return;
    const targetPlatform = root.querySelector('#trigger-device').value;
    const target = root.querySelector('#preview-' + targetPlatform)?.contentWindow?.__mihonSyncDemo;
    if (button.dataset.theme) {
      window.MihonPreview.theme = button.dataset.theme;
      root.querySelectorAll('[data-theme]').forEach(el => el.classList.toggle('is-selected', el.dataset.theme === button.dataset.theme));
      apps().forEach(app => { app.state.ui.theme = button.dataset.theme; app.render(false); });
    } else if (button.dataset.trigger && target) {
      target.scheduleSync(button.dataset.trigger);
    } else if (button.dataset.command === 'extension-scene' && target) {
      target.extensions.scene(root.querySelector('#extension-scene').value);
    } else if (button.dataset.command === 'scene' && target) {
      target.showInteractionScenario(root.querySelector('#interaction-scene').value);
    } else if (button.dataset.command === 'network') {
      model.setOnline(sharedState, !sharedState.online);
      button.textContent = sharedState.online ? '切换离线' : '恢复在线';
      apps().forEach(app => app.render(false));
    } else if (button.dataset.command === 'reset' || button.dataset.command === 'many') {
      if (button.dataset.command === 'reset') apps().forEach(app => app.extensions.reset());
      sharedState = model.createDemoState();
      root.querySelector('[data-testid="network-toggle"]').textContent = '切换离线';
      mountFrames(button.dataset.command === 'many' ? frame => {
        if (frame.dataset.platform === targetPlatform) frame.contentDocument.querySelector('[data-testid="many-pending"]').click();
      } : null);
    }
  });
  mountFrames();
})();
