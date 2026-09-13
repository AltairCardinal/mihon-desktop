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
      <small class="preview-boundary">两端可独立操作。同步时，先在来源端上传，再在另一端同步接收。大量待处理示例显示在所选设备。</small>
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
      frame.srcdoc = '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="./styles.css"></head><body><div id="app" class="app-shell"></div><script src="./ui-view.js"></script><script src="./app.js"></script></body></html>';
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
    } else if (button.dataset.command === 'network') {
      model.setOnline(sharedState, !sharedState.online);
      button.textContent = sharedState.online ? '切换离线' : '恢复在线';
      apps().forEach(app => app.render(false));
    } else if (button.dataset.command === 'reset' || button.dataset.command === 'many') {
      sharedState = model.createDemoState();
      root.querySelector('[data-testid="network-toggle"]').textContent = '切换离线';
      mountFrames(button.dataset.command === 'many' ? frame => {
        if (frame.dataset.platform === targetPlatform) frame.contentDocument.querySelector('[data-testid="many-pending"]').click();
      } : null);
    }
  });
  mountFrames();
})();
