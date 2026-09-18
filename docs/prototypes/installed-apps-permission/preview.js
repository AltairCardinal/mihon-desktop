(function () {
  'use strict';
  const root = document.getElementById('preview');
  const sharedAssets = new URL('../multi-device-sync/', document.baseURI).href;
  const ownAssets = new URL('./', document.baseURI).href;
  // Reuse the existing shell; this prototype has no cross-device permission state.
  window.MihonPreview = {
    theme: 'dark',
    connect: () => window.MihonSyncModel.createDemoState(),
    refreshOthers() {},
  };
  for (const platform of ['windows', 'android']) {
    const column = document.createElement('section');
    column.className = 'device-column device-' + platform;
    const label = document.createElement('div');
    label.className = 'device-label';
    label.textContent = platform === 'windows' ? 'Windows Desktop · 无此权限要求' : 'Android · 浏览 → 图源';
    const frame = document.createElement('iframe');
    frame.id = 'preview-' + platform;
    frame.dataset.platform = platform;
    frame.title = label.textContent;
    frame.srcdoc = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><base href="${sharedAssets}"><link rel="stylesheet" href="${sharedAssets}styles.css"><link rel="stylesheet" href="${ownAssets}permission.css"></head><body><div id="app" class="app-shell"></div><script src="${sharedAssets}ui-view.js"></script><script src="${sharedAssets}sync-interactions.js"></script><script src="${sharedAssets}extension-suggestions.js"></script><script src="${ownAssets}permission.js"></script><script src="${sharedAssets}app.js"></script></body></html>`;
    frame.addEventListener('load', () => {
      const app = frame.contentWindow.__mihonSyncDemo;
      app.state.ui.route = 'browse';
      app.state.ui.notice = '插件列表权限交互预览；页面内容和系统授权均为本地模拟。';
      app.render(false);
    }, { once: true });
    column.append(label, frame);
    root.querySelector('.device-pair').append(column);
  }
  const android = () => root.querySelector('#preview-android').contentWindow.MihonInstalledAppsPermissionDemo;
  root.querySelector('#show-scene').addEventListener('click', () => android().scene(root.querySelector('#permission-scene').value));
  root.querySelector('#cold-start').addEventListener('click', () => android().coldStart());
  root.querySelectorAll('[data-theme]').forEach(button => button.addEventListener('click', () => {
    window.MihonPreview.theme = button.dataset.theme;
    root.querySelectorAll('iframe').forEach(frame => {
      const app = frame.contentWindow.__mihonSyncDemo;
      app.state.ui.theme = button.dataset.theme;
      app.render(false);
    });
  }));
})();
