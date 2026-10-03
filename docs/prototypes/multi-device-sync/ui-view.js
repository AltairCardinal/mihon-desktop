(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.MihonSyncView = factory();
})(typeof globalThis === 'object' ? globalThis : this, function () {
  const themeTokens = {
    light: { primary: '#0058CA', onPrimary: '#FFFFFF', surface: '#FEFBFF', onSurface: '#1B1B1F', secondaryContainer: '#D9E2FF', surfaceContainer: '#F3EDF7' },
    dark: { primary: '#B0C6FF', onPrimary: '#002D6E', surface: '#1B1B1F', onSurface: '#E3E2E6', secondaryContainer: '#00429B', surfaceContainer: '#211F26' },
  };

  const paths = {
    // These are the Filled Material paths used by the platform navigation.
    library: 'M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-1 11-3-2-3 2V4h6v9z',
    updates: 'M23 12l-2.44-2.78.34-3.68-3.61-.82-1.89-3.18L12 3 8.6 1.54 6.71 4.72l-3.61.82.34 3.68L1 12l2.44 2.78-.34 3.69 3.61.82 1.89 3.18L12 21l3.4 1.46 1.89-3.18 3.61-.82-.34-3.69L23 12zm-10 5h-2v-2h2v2zm0-4h-2V7h2v6z',
    history: 'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.95-2.05l-1.42 1.42A8.996 8.996 0 0 0 22 12c0-4.97-4.03-9-9-9zm-1 5v5l4.25 2.52.77-1.28-3.52-2.09V8h-2z',
    browse: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4.5 6.5-2.79 6.21L7.5 17.5l2.79-6.21L16.5 8.5zM12 13.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z',
    authors: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
    more: 'M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    back: 'M19 12H5m6-6-6 6 6 6',
    checklist: 'M4 4h16v16H4zM8 8h.01M11 8h5M8 12h.01M11 12h5M8 16h.01M11 16h5',
    selectAll: 'M3 5V3h2m4 0h2m4 0h2m2 0h2v2m0 4v2m0 4v2m0 2v2h-2m-4 0h-2m-4 0H7m-2 0H3v-2m0-4v-2m0-4V7M7 7h10v10H7z',
    flipToBack: 'M3 7v12a2 2 0 0 0 2 2h12M7 5V3h2m4 0h2m4 0h2v2m0 4v2m0 4v2h-2m-4 0h-2m-4 0H7v-2m0-4V9',
    upload: 'M12 15V3m0 0-4 4m4-4 4 4M4 17v3h16v-3',
    filter: 'M4 6h16M7 12h10M10 18h4',
    calendar: 'M5 4v3m14-3v3M4 9h16M5 5h14a1 1 0 0 1 1 1v13H4V6a1 1 0 0 1 1-1M8 13h2m2 0h2m2 0h2m-8 3h2m2 0h2m2 0h2',
    refresh: 'M20 11a8 8 0 0 0-14.9-3L3 11m0-5v5h5M4 13a8 8 0 0 0 14.9 3L21 13m0 5v-5h-5',
    check: 'm5 12 4 4L19 6',
    download: 'M12 3v11m0 0 4-4m-4 4-4-4M4 17v3h16v-3',
    category: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
    chart: 'M5 19V5h2v14H5zm6 0V9h2v10h-2zm6 0V3h2v16h-2z',
    storage: 'M4 5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2H4V5zm0 4h16v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V9zm3 3v2h2v-2H7z',
    help: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 17h-2v-2h2v2zm2.07-7.25-.9.92C13.45 13.4 13 14 13 15h-2v-.5c0-.8.45-1.55 1.17-2.27l1.24-1.26A2 2 0 1 0 10 9H8a4 4 0 1 1 7.07 2.75z',
    donate: 'M12 21s-7-4.35-7-10.2C5 7.58 6.79 6 9 6c1.28 0 2.45.6 3 1.54C12.55 6.6 13.72 6 15 6c2.21 0 4 1.58 4 4.8C19 16.65 12 21 12 21z',
    external: 'M14 3h7v7h-2V6.41l-9.29 9.3-1.42-1.42L17.59 5H14V3zM5 5h5v2H7v10h10v-3h2v5H5V5z',
    visibilityOff: 'M2 4.27 3.28 3 21 20.72 19.73 22l-2.1-2.1A10.9 10.9 0 0 1 12 22C6.48 22 2 17.52 2 12c0-1.86.5-3.6 1.37-5.1L2 4.27zM12 4c5.52 0 10 4.48 10 10 0 1.3-.25 2.55-.7 3.68l-1.54-1.54c.15-.68.24-1.39.24-2.14 0-4.41-3.59-8-8-8-.75 0-1.46.09-2.14.24L8.3 4.7C9.43 4.25 10.7 4 12 4z',
    swap: 'M16 3l5 5-5 5V9H4V7h12V3zM8 21l-5-5 5-5v4h12v2H8v4z',
    bookmark: 'M6 4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18l-6-3-6 3z',
    search: 'm19 19-4-4m2-5a7 7 0 1 1-14 0 7 7 0 0 1 14 0',
    plus: 'M12 5v14M5 12h14',
    // Material Icons Filled Settings, matching Icons.Default.Settings in Mihon.
    settings: 'M19.43 12.98c.04-.32.07-.65.07-.98s-.03-.66-.08-.98l2.11-1.65c.19-.15.24-.42.12-.64l-2-3.46c-.12-.22-.37-.31-.6-.22l-2.49 1c-.52-.4-1.07-.73-1.68-.98l-.38-2.65A.488.488 0 0 0 14 2h-4c-.25 0-.45.18-.49.42l-.38 2.65c-.61.25-1.17.59-1.68.98l-2.49-1c-.23-.08-.48 0-.6.22l-2 3.46c-.13.22-.07.49.12.64l2.11 1.65c-.05.32-.09.66-.09.98s.03.66.08.98l-2.11 1.65c-.19.15-.24.42-.12.64l2 3.46c.12.22.37.31.6.22l2.49-1c.52.4 1.07.73 1.68.98l.38 2.65c.05.24.25.42.5.42h4c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.58 1.68-.98l2.49 1c.23.08.48 0 .6-.22l2-3.46c.12-.22.07-.49-.12-.64l-2.09-1.65zM12 15.5c-1.93 0-3.5-1.57-3.5-3.5s1.57-3.5 3.5-3.5 3.5 1.57 3.5 3.5-1.57 3.5-3.5 3.5z',
    play: 'm8 5 11 7-11 7z',
    pause: 'M8 5v14M16 5v14',
    close: 'm6 6 12 12M18 6 6 18',
    chevron: 'm9 6 6 6-6 6',
    sync: 'M17 2l4 4-4 4M3 12a9 9 0 0 1 15-6M7 22l-4-4 4-4m14-2a9 9 0 0 1-15 6',
    cloud: 'M7 18h10a4 4 0 0 0 .7-7.94A6 6 0 0 0 6.1 8.2 4 4 0 0 0 7 18',
    wifi: 'M1 9l2 2c5-5 13-5 18 0l2-2C17.9 2.9 6.1 2.9 1 9zm8 8 3 3 3-3c-1.65-1.66-4.35-1.66-6 0zm-4-4 2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.86 9.14 5 13z',
    signal: 'M2 22h20V2L2 22z',
    battery: 'M17 5h-1V3h-6v2H9c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 14H9V7h8v12z',
    info: 'M12 17v-5m0-4h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
    reader: 'M4 5.5A2.5 2.5 0 0 1 6.5 3H12v16H6.5A2.5 2.5 0 0 0 4 21zM20 5.5A2.5 2.5 0 0 0 17.5 3H12v16h5.5a2.5 2.5 0 0 1 2.5 2z',
  };

  const specs = {
    windows: {
      id: 'windows', windowTitle: 'Mihon Desktop', bottomBarHeight: 80,
      nav: [
        { route: 'library', label: '书架', icon: 'library' }, { route: 'updates', label: '更新', icon: 'updates' },
        { route: 'history', label: '历史', icon: 'history' }, { route: 'browse', label: '浏览', icon: 'browse' },
        { route: 'authors', label: '作者', icon: 'authors' }, { route: 'more', label: '更多', icon: 'more' },
      ],
      browseTabs: ['图源', '插件'],
      update: { cover: { width: 48, height: 68 }, rowHeight: 84, horizontalPadding: 12, contentPadding: 8 },
    },
    android: {
      id: 'android', windowTitle: 'Mihon', bottomBarHeight: 80,
      nav: [
        { route: 'library', label: '书架', icon: 'library' }, { route: 'updates', label: '更新', icon: 'updates' },
        { route: 'history', label: '历史', icon: 'history' }, { route: 'browse', label: '浏览', icon: 'browse' },
        { route: 'more', label: '更多', icon: 'more' },
      ],
      browseTabs: ['图源', '作者', '插件', '迁移'],
      update: { cover: { width: 44, height: 44 }, rowHeight: 56, horizontalPadding: 16, contentPadding: 6 },
    },
  };

  function platformSpec(platform) { return specs[platform] || specs.windows; }
  function icon(name, label, className) {
    const path = paths[name] || paths.info;
    return `<svg class="MihonIcon ${className || ''}" data-icon="${name}" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="${path}"></path></svg>${label ? `<span class="icon-label">${label}</span>` : ''}`;
  }
  function renderNav(spec, active, indicators) {
    const navState = indicators || {};
    const unreadCount = Math.max(0, Number(navState.unreadCount) || 0);
    const contentText = unreadCount > 99 ? '99+' : String(unreadCount);
    const label = (item) => {
      if (item.route !== 'updates') return item.label;
      const parts = [item.label];
      if (unreadCount > 0) parts.push(`${unreadCount} 条未读内容`);
      return parts.join('；');
    };
    const contentBadge = (item) => item.route !== 'updates' ? '' : `${unreadCount > 0 ? `<span class="nav-badge nav-badge-content" data-testid="nav-updates-content-badge" title="${unreadCount} 条未读内容" aria-hidden="true">${contentText}</span>` : ''}`;
    return `<nav class="native-navigation" aria-label="${spec.id === 'windows' ? 'Desktop 主导航' : '手机主导航'}">${spec.nav.map((item) => `<button class="native-nav-item ${item.route === active ? 'is-selected' : ''}" data-route="${item.route}" data-testid="nav-${item.route}" aria-current="${item.route === active ? 'page' : 'false'}" aria-label="${label(item)}" title="${label(item)}"><span class="nav-icon-anchor"><span class="nav-icon-wrap">${icon(item.icon)}</span>${contentBadge(item)}</span><span class="nav-label"><span class="nav-label-text">${item.label}</span></span></button>`).join('')}</nav>`;
  }

  return { platformSpec, renderNav, icon, themeTokens, paths };
});
