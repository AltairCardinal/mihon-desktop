(() => {
  const root = document.getElementById('reader-settings-v3-review');
  const picker = root.querySelector('[data-global]');
  const hosts = [...root.querySelectorAll('mihon-reader-settings')];
  const key = 'mihon.reader-settings.v3-review.defaults';
  const samples = {
    'paged-auto': { mode: 'paged', layout: 'auto', match: true, direction: 'rtl', gap: false },
    'paged-single': { mode: 'paged', layout: 'single', match: true, direction: 'rtl', gap: false },
    'paged-double': { mode: 'paged', layout: 'double', match: true, direction: 'rtl', gap: false },
    webtoon: { mode: 'webtoon', layout: 'auto', match: true, direction: 'rtl', gap: true },
  };
  try {
    const stored = localStorage.getItem(key);
    if (Object.hasOwn(samples, stored)) picker.value = stored;
  } catch (_) { /* 本地存储不可用时仍可演示。 */ }
  function updateDefaults() {
    for (const host of hosts) host.defaultSettings = samples[picker.value];
  }
  function saveWidget() {
    if (window.openai?.setWidgetState) {
      window.openai.setWidgetState({ modelContent: { version: 3, defaults: picker.value, devices: hosts.map(host => host.value) }, privateContent: null }).catch(() => {});
    }
  }
  function restoreWidget(content) {
    if (content?.version !== 3 || !Object.hasOwn(samples, content.defaults)) return;
    picker.value = content.defaults;
    updateDefaults();
    hosts.forEach((host, index) => { if (content.devices?.[index]) host.value = content.devices[index]; });
  }
  updateDefaults();
  restoreWidget(window.openai?.widgetState?.modelContent);
  picker.addEventListener('change', () => {
    updateDefaults();
    try { localStorage.setItem(key, picker.value); } catch (_) { /* 允许无存储演示。 */ }
    saveWidget();
  });
  for (const host of hosts) host.addEventListener('settings-change', saveWidget);
  window.addEventListener('openai:set_globals', event => restoreWidget(event.detail?.globals?.widgetState?.modelContent));
})();
