(function () {
  'use strict';
  const warning = '需要允许“获取已安装应用列表”权限，否则无法读取已安装的插件列表';
  let appState;
  let repaint;
  let permission = 'denied';
  let status = 'checking';
  let settingsUnavailable = false;
  let scanFails = false;
  let checkFails = false;
  let dialogOpen = false;
  let feedback = '';
  let timer;
  let started = false;

  const button = (label, action, id) => `<button class="m-button m-button-tonal" data-permission-action="${action}" data-testid="${id}">${label}</button>`;
  function update(focusId) {
    if (!repaint) return;
    repaint(false);
    if (focusId && document.hasFocus()) document.querySelector(`[data-testid="${focusId}"]`)?.focus();
  }
  function later(work) {
    window.clearTimeout(timer);
    timer = window.setTimeout(work, 450);
  }
  function scan() {
    status = 'loading';
    update();
    later(() => {
      status = scanFails ? 'scan-failed' : 'ready';
      update();
    });
  }
  function check(restoreFocus = false) {
    dialogOpen = false;
    status = 'checking';
    update();
    later(() => {
      if (checkFails) { status = 'check-failed'; update(); }
      else if (permission === 'denied') { status = 'denied'; update(restoreFocus ? 'permission-get' : null); }
      else scan();
    });
  }
  function rows(systemVisible) {
    const sources = systemVisible ? [['绅士漫画', '中文 · 已安装插件'], ['拷贝漫画', '中文 · 已安装插件']] : [];
    sources.push(['私有示例源', '应用内安装 · 不依赖此权限'], ['本地图源', '本地文件']);
    return sources.map(([name, detail]) => `<div class="source-row"><span class="source-logo" aria-hidden="true">${name[0]}</span><span class="row-copy"><strong>${name}</strong><small>${detail}</small></span></div>`).join('');
  }
  function systemDialog() {
    if (!dialogOpen) return '';
    window.queueMicrotask(() => {
      const dialog = document.getElementById('permission-system-dialog');
      if (dialog && !dialog.open) {
        dialog.showModal();
        dialog.addEventListener('cancel', event => { event.preventDefault(); returnFromSettings(false); });
        dialog.addEventListener('keydown', event => {
          if (event.key !== 'Tab') return;
          const controls = [...dialog.querySelectorAll('button')];
          const current = controls.indexOf(document.activeElement);
          const next = (current + (event.shiftKey ? -1 : 1) + controls.length) % controls.length;
          event.preventDefault();
          event.stopPropagation();
          controls[next].focus();
        });
        document.querySelector('[data-testid="permission-back"]')?.focus();
      }
    });
    return `<dialog id="permission-system-dialog" class="permission-dialog" data-testid="permission-system-dialog" aria-labelledby="permission-dialog-title">
      <span class="permission-demo-label">本地交互模拟 · 非真实系统页面</span>
      <h2 id="permission-dialog-title">系统权限设置（模拟）</h2>
      <div class="permission-app">Mihon</div>
      <h3>获取已安装应用列表</h3>
      <p>允许后，Mihon 可以查找系统中已安装的插件。此权限与“安装未知应用”权限不同。</p>
      <p class="permission-muted">实际界面和权限名称由设备系统决定。此处只演示授权后返回应用的结果。</p>
      <div class="permission-dialog-actions">
        ${button('保持不允许并返回', 'deny', 'permission-back')}
        ${button('允许并返回 Mihon', 'allow', 'permission-allow')}
      </div>
    </dialog>`;
  }
  function returnFromSettings(allowed) {
    dialogOpen = false;
    if (allowed) permission = 'granted';
    feedback = allowed ? '' : '尚未开放权限。你仍可使用本地和私有图源。';
    check(true);
  }
  document.addEventListener('click', event => {
    const control = event.target.closest('[data-permission-action]');
    if (!control) return;
    event.stopImmediatePropagation();
    const action = control.dataset.permissionAction;
    if (action === 'get') {
      if (settingsUnavailable) {
        feedback = '无法打开权限设置。请在系统设置 → 应用 → Mihon → 权限中允许“获取已安装应用列表”，然后返回应用。不同设备的入口可能不同。';
      } else dialogOpen = true;
      update();
    } else if (action === 'allow' || action === 'deny') {
      returnFromSettings(action === 'allow');
    } else if (action === 'recheck') {
      checkFails = false;
      feedback = '';
      check();
    } else if (action === 'retry-scan') {
      scanFails = false;
      scan();
    }
  }, true);

  window.MihonInstalledAppsPermissionDemo = {
    renderSources(state, render) {
      if (state.ui.platform !== 'android') return null;
      appState = state;
      repaint = render;
      if (!started) {
        started = true;
        window.queueMicrotask(check);
      }
      let notice = '';
      if (status === 'denied') {
        notice = `<section class="permission-card" data-testid="permission-required" aria-labelledby="permission-title">
          <div class="permission-heading">${window.MihonSyncView.icon('info')}<h2 id="permission-title">无法读取已安装插件</h2></div>
          <p>${warning}</p>
          <p class="permission-muted">已安装的插件无需重新安装。本地和私有图源仍可使用。</p>
          ${button('获取权限', 'get', 'permission-get')}
          ${settingsUnavailable ? button('重新检查', 'recheck', 'permission-recheck') : ''}
          ${feedback ? `<p role="status" class="permission-feedback">${feedback}</p>` : ''}
        </section>`;
      } else if (status === 'checking' || status === 'loading') {
        notice = `<div class="permission-progress" role="status" data-testid="permission-progress"><span class="permission-spinner" aria-hidden="true"></span>${status === 'checking' ? '正在检查插件列表权限…' : '正在读取已安装插件…'}</div>`;
      } else if (status === 'check-failed') {
        notice = `<section class="permission-card" data-testid="permission-check-error"><h2>暂时无法确认权限状态</h2><p>请重试检查；这不表示插件已卸载。</p>${button('重新检查', 'recheck', 'permission-recheck')}</section>`;
      } else if (status === 'scan-failed') {
        notice = `<section class="permission-card" data-testid="permission-scan-error"><h2>插件读取失败</h2><p>已获得权限，但未能读取插件。请重试；无需再次授权或重新安装。</p>${button('重试读取', 'retry-scan', 'permission-retry-scan')}</section>`;
      }
      return `<div class="source-list permission-source-list">${notice}<div class="simple-list-heading">${status === 'ready' ? '已安装图源' : '当前可用图源'}</div>${rows(status === 'ready')}${systemDialog()}</div>`;
    },
    scene(name) {
      if (!appState) return;
      window.clearTimeout(timer);
      permission = name === 'unsupported' ? 'unsupported' : ['granted', 'scan-failed'].includes(name) ? 'granted' : 'denied';
      settingsUnavailable = name === 'settings-unavailable';
      scanFails = name === 'scan-failed';
      checkFails = name === 'check-failed';
      feedback = '';
      appState.ui.route = 'browse';
      appState.ui.browseTab = 'sources';
      check();
    },
    coldStart() {
      if (!appState) return;
      // OS permission survives an application process restart; transient UI does not.
      feedback = '';
      appState.ui.route = 'browse';
      appState.ui.browseTab = 'sources';
      check();
    },
  };
})();
