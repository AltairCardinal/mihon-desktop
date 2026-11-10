/*
   * Standalone prototype, no external dependencies.
   * This component edits preferences only; it does not implement a manga renderer.
   * Local defaults are a demo fixture. A host app can supply real defaults through
   * element.defaultSettings = { mode, layout, match, direction, gap }.
   * All presentation is scoped to Shadow DOM to preserve the theme when embedded.
   */
  (() => {
    'use strict';
    const DEFAULTS = Object.freeze({
      mode: 'paged', layout: 'auto', match: true, direction: 'rtl', gap: false
    });
    const DIRECTIONS = Object.freeze({ ltr: '从左到右', rtl: '从右到左', vertical: '从上到下' });
    const LAYOUTS = Object.freeze({ auto: '自动', single: '单页', double: '双页' });
    const clone = value => JSON.parse(JSON.stringify(value));
    const normalizePrefs = (value, fallback = DEFAULTS) => {
      const v = value && typeof value === 'object' && !Array.isArray(value) ? value : {};
      const prefs = {
        mode: ['paged', 'webtoon'].includes(v.mode) ? v.mode : fallback.mode,
        layout: ['auto', 'single', 'double'].includes(v.layout) ? v.layout : fallback.layout,
        match: typeof v.match === 'boolean' ? v.match : fallback.match,
        direction: ['ltr', 'rtl', 'vertical'].includes(v.direction) ? v.direction : fallback.direction,
        gap: typeof v.gap === 'boolean' ? v.gap : fallback.gap
      };
      if (prefs.mode === 'paged' && prefs.layout === 'double' && prefs.direction === 'vertical') prefs.direction = 'rtl';
      return prefs;
    };
    const svg = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><use href="#i-${name}"></use></svg>`;
    const radio = (name, value, content, type = 'segment') => `
      <label class="${type}">
        <input type="radio" name="${name}" value="${value}" data-setting="${name}">
        <span class="${type}-surface">${content}</span>
      </label>`;
    const template = document.createElement('template');
    template.innerHTML = `
      <style>
        :host {
          color-scheme: dark;
          display: block;
          container-type: inline-size;
          min-width: 0;
          --surface: #252630;
          --field: #2d2e3a;
          --field-low: #20212b;
          --card: #2d2f3b;
          --line: #424654;
          --divider: #ffffff13;
          --text: #eef0f8;
          --secondary: #aeb4c7;
          --accent: #afc7ff;
          --on-accent: #173466;
          --selected: #293f67;
          --selected-top: #304a76;
          --selected-text: #eef4ff;
          --selected-border: #96b7f1;
          --ease: cubic-bezier(.2, .75, .25, 1);
          --font: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", system-ui, sans-serif;
          font-family: var(--font);
          font-size: 15px;
          font-weight: 400;
          line-height: 1.5;
          color: var(--text);
          -webkit-font-smoothing: antialiased;
          text-rendering: optimizeLegibility;
          -webkit-tap-highlight-color: transparent;
        }
        *, *::before, *::after { box-sizing: border-box; }
        [hidden] { display: none !important; }
        p, h1, h2, dl, dt, dd { margin: 0; }
        svg { display: block; flex-shrink: 0; }
        .sprite { position: absolute; width: 0; height: 0; overflow: hidden; }
        .icon { width: 21px; height: 21px; fill: none; stroke: currentColor; stroke-width: 1.65; stroke-linecap: round; stroke-linejoin: round; }
        .panel {
          width: 100%;
          position: relative;
          isolation: isolate;
          overflow: hidden;
          padding: 25px 26px 27px;
          border: 1px solid #ffffff15;
          border-radius: 26px;
          background: linear-gradient(140deg, #ffffff02, transparent 48%), var(--surface);
          box-shadow: 0 24px 64px #080a1447, 0 4px 12px #080a142b, inset 0 1px 0 #ffffff04;
        }
        .panel::before {
          content: ''; position: absolute; z-index: -1; top: 0; left: 10%; right: 10%; height: 1px;
          background: linear-gradient(90deg, transparent, #b8cbff32, transparent);
        }
        .context {
          display: flex; align-items: center; gap: 9px; margin-bottom: 24px;
          font-size: 12px; font-weight: 500; letter-spacing: .025em; color: var(--secondary);
        }
        .context .icon { width: 17px; height: 17px; color: var(--accent); }
        .context .dot { width: 3px; height: 3px; border-radius: 50%; background: #7c8395; margin: 0 1px; }
        fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
        legend {
          float: none; display: block; width: 100%; padding: 0; margin: 0 0 10px;
          color: #d9deed; font-size: 14px; line-height: 1.5; font-weight: 550;
        }
        .help { margin-top: 9px; color: var(--secondary); font-size: 12px; line-height: 1.6; }
        .divider { height: 1px; border: 0; margin: 22px 0; background: var(--divider); }
        .segments {
          --count: 2; --index: 1;
          display: grid; grid-template-columns: repeat(var(--count), minmax(0, 1fr));
          position: relative; isolation: isolate;
          padding: 4px; border: 1px solid #ffffff06; border-radius: 15px;
          background: var(--field-low); box-shadow: inset 0 1px 3px #0709141c;
        }
        .segments::before {
          content: ''; position: absolute; z-index: -1; top: 4px; bottom: 4px; left: 4px;
          width: calc((100% - 8px) / var(--count)); border-radius: 11px;
          transform: translateX(calc(var(--index) * 100%));
          background: linear-gradient(150deg, #3d4356, #343947);
          border: 1px solid #ffffff11; box-shadow: 0 2px 5px #0e102740, inset 0 1px 0 #ffffff06;
          transition: transform 230ms var(--ease), background 180ms ease;
        }
        .segments.layout { --count: 3; --index: 0; }
        .segments.layout::before {
          background: linear-gradient(140deg, var(--selected-top), var(--selected));
          border-color: #85a8e633; box-shadow: 0 2px 5px #071a3133, inset 0 1px 0 #abc7ff0b;
        }
        .segment, .choice, .direction { position: relative; display: block; min-width: 0; cursor: pointer; }
        .segment input, .choice input, .direction input {
          position: absolute; z-index: 3; inset: 0; opacity: 0; width: 100%; height: 100%; margin: 0;
          cursor: pointer;
        }
        .segment-surface {
          display: flex; align-items: center; justify-content: center; gap: 9px;
          min-height: 43px; padding: 8px 5px; border-radius: 11px;
          color: var(--secondary); font-size: 15px; font-weight: 500; white-space: nowrap;
          transition: color 160ms ease, background 160ms ease;
        }
        .segment-surface .icon { width: 19px; height: 19px; }
        .segment input:checked + .segment-surface { color: #f1f4ff; }
        .segment input:checked + .segment-surface .icon { color: var(--accent); }
        .segment input:not(:checked):hover + .segment-surface { color: var(--text); background: #ffffff05; }
        input:focus-visible + .segment-surface,
        input:focus-visible + .choice-surface,
        input:focus-visible + .direction-surface {
          outline: 2px solid var(--accent); outline-offset: 3px;
        }
        .mode-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
        .choice-surface {
          position: relative; display: flex; align-items: center; gap: 12px; min-height: 85px;
          padding: 16px; border: 1px solid var(--line); border-radius: 17px;
          background: linear-gradient(135deg, #ffffff02, transparent), var(--card);
          transition: background 180ms ease, border-color 180ms ease, box-shadow 180ms ease, transform 150ms var(--ease);
        }
        .choice-icon { color: #c7cddc; display: flex; align-items: center; }
        .choice-icon .icon { width: 25px; height: 25px; }
        .choice-copy { display: grid; gap: 3px; min-width: 0; }
        .choice-title { font-size: 17px; font-weight: 550; line-height: 1.4; }
        .choice-detail { font-size: 12px; color: var(--secondary); white-space: nowrap; }
        .radio-dot {
          display: grid; place-items: center; margin-left: auto; flex-shrink: 0;
          width: 17px; height: 17px; border: 1.5px solid #798095; border-radius: 50%;
          transition: border-color 180ms ease, background 180ms ease;
        }
        .radio-dot::after {
          content: ''; width: 6px; height: 6px; background: var(--on-accent); border-radius: 50%;
          transform: scale(0); transition: transform 180ms var(--ease);
        }
        .choice input:checked + .choice-surface {
          color: var(--selected-text); border-color: var(--selected-border);
          background: linear-gradient(130deg, var(--selected-top), var(--selected));
          box-shadow: 0 3px 12px #07162d29, inset 0 1px 0 #dce8ff0b;
        }
        .choice input:checked + .choice-surface .choice-icon { color: #ccdcff; }
        .choice input:checked + .choice-surface .choice-detail { color: #c7d7f4; }
        .choice input:checked + .choice-surface .radio-dot { background: var(--accent); border-color: var(--accent); }
        .choice input:checked + .choice-surface .radio-dot::after { transform: scale(1); }
        .choice input:hover + .choice-surface { border-color: #a6b4d680; }
        .choice input:checked:hover + .choice-surface { border-color: #bdd2ff; }
        .choice input:active + .choice-surface { transform: scale(.988); }
        .layout-field { margin-top: 22px; }
        .layout-help { display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 8px; margin-top: 9px; }
        .layout-help p { color: var(--secondary); font-size: 12px; line-height: 1.6; }
        .adaptive-tag {
          display: inline-flex; align-items: center; gap: 5px; flex-shrink: 0; color: #b6cbf8;
          font-size: 11px; line-height: 1.7; padding: 0 7px; border-radius: 6px;
          background: #9cbcff0b; border: 1px solid #9cbcff13;
        }
        .adaptive-tag::before { content: ''; width: 4px; height: 4px; border-radius: 50%; background: #a7c5ff; }
        .dependent { margin-top: 14px; }
        .toggle-row {
          min-height: 81px; display: flex; align-items: center; justify-content: space-between; gap: 12px;
          padding: 15px 16px; border-radius: 16px;
          background: linear-gradient(130deg, #ffffff02, transparent), #2e303d;
          border: 1px solid #ffffff06;
          cursor: pointer; transition: background 170ms ease, border-color 170ms ease;
        }
        .toggle-row:hover { background-color: #333644; border-color: #ffffff10; }
        .toggle-copy { min-width: 0; display: grid; gap: 5px; }
        .toggle-title { color: #eef0f8; font-size: 15px; font-weight: 500; }
        .toggle-description { color: var(--secondary); font-size: 12px; line-height: 1.6; }
        .toggle-control { display: inline-flex; align-items: center; justify-content: flex-end; gap: 9px; flex-shrink: 0; }
        .toggle-state { color: var(--secondary); font-size: 12px; white-space: nowrap; min-width: 24px; text-align: right; }
        .switch { position: relative; display: inline-grid; place-items: center; width: 48px; height: 44px; flex-shrink: 0; }
        .switch input { position: absolute; z-index: 1; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; margin: 0; }
        .switch-track {
          display: block; position: relative; width: 48px; height: 28px;
          border: 1.5px solid #737c92; border-radius: 99px; background: #252936;
          transition: border-color 180ms ease, background 180ms ease, box-shadow 180ms ease;
        }
        .switch-track::before {
          content: ''; position: absolute; left: 3px; top: 3px; width: 19px; height: 19px;
          border-radius: 50%; background: #aeb5c6;
          transition: transform 220ms var(--ease), background 160ms ease;
        }
        .switch input:checked + .switch-track { background: var(--accent); border-color: var(--accent); box-shadow: 0 1px 6px #9ab9ff15; }
        .switch input:checked + .switch-track::before { background: #173d76; transform: translateX(20px); }
        .switch input:focus-visible + .switch-track { outline: 2px solid var(--accent); outline-offset: 4px; }
        .direction-field { margin-top: 22px; }
        .direction-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
        .direction-surface {
          display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 7px;
          min-height: 74px; border-radius: 14px; padding: 11px 4px;
          border: 1px solid var(--line); background: var(--card); color: #c4cadb;
          transition: color 180ms ease, background 180ms ease, border-color 180ms ease, transform 150ms var(--ease);
        }
        .direction-surface .icon { width: 23px; height: 23px; }
        .direction-title { font-size: 13px; line-height: 1.5; font-weight: 500; white-space: nowrap; }
        .direction input:checked + .direction-surface {
          color: var(--selected-text); border-color: var(--selected-border);
          background: linear-gradient(140deg, var(--selected-top), var(--selected)); box-shadow: inset 0 1px 0 #ffffff06;
        }
        .direction input:checked + .direction-surface .icon { color: #c9dbff; }
        .direction input:not(:checked):hover + .direction-surface { border-color: #8a96b0; background: #343745; color: var(--text); }
        .direction input:active + .direction-surface { transform: scale(.985); }
        .webtoon-settings { margin-top: 22px; }
        .inherited {
          border: 1px solid #8baaf32b; border-radius: 18px;
          padding: 19px; background: linear-gradient(125deg, #293448, #272c3a);
        }
        .inherited-heading { display: flex; align-items: center; gap: 10px; font-size: 15px; font-weight: 550; color: #d5e3ff; }
        .inherited-heading .icon { width: 20px; height: 20px; color: var(--accent); }
        .inherited-description { color: #aebbd3; font-size: 12px; margin-top: 8px; line-height: 1.8; }
        .inherited dl { display: grid; gap: 0; margin-top: 17px; font-size: 13px; }
        .inherited dl > div { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 11px 0; border-top: 1px solid #ffffff0a; }
        .inherited dt { color: #b4bdd1; }
        .inherited dd { text-align: right; color: #edf2fc; font-weight: 500; }
        .inherited-footnote { margin-top: 13px; font-size: 12px; line-height: 1.7; color: var(--secondary); }
        .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); clip-path: inset(50%); white-space: nowrap; }
        @container (max-width: 560px) {
          .panel { padding: 22px 20px 24px; border-radius: 23px; }
          .context { margin-bottom: 21px; }
          .choice-surface { padding: 13px; gap: 9px; min-height: 82px; }
          .choice-title { font-size: 16px; }
          .choice-icon .icon { width: 22px; height: 22px; }
          .mode-grid { gap: 10px; }
          .toggle-row { padding: 14px; gap: 10px; }
          .toggle-control { gap: 7px; }
          .toggle-description { max-width: 22em; }
        }
        @container (max-width: 390px) {
          .panel { padding: 21px 16px 23px; }
          .choice-surface { padding: 13px 10px; gap: 8px; }
          .choice-detail { font-size: 11px; }
          .choice-icon .icon { width: 21px; height: 21px; }
          .radio-dot { width: 15px; height: 15px; }
          .mode-grid { gap: 9px; }
          .segment-surface { gap: 7px; font-size: 14px; }
          .toggle-row { padding: 13px 12px; }
          .toggle-control { gap: 7px; }
          .toggle-state { display: none; }
          .direction-grid { gap: 8px; }
          .direction-title { font-size: 12px; }
          .layout-help p { font-size: 11px; }
          .adaptive-tag { font-size: 10px; padding: 0 5px; }
        }
        @media (prefers-reduced-motion: reduce) {
          *, *::before, *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
        }
        @media (forced-colors: active) {
          .panel, .choice-surface, .direction-surface, .inherited, .segments, .toggle-row { border: 1px solid CanvasText; }
          input:checked + .segment-surface, input:checked + .choice-surface, input:checked + .direction-surface { outline: 2px solid Highlight; outline-offset: -3px; }
          .switch-track { forced-color-adjust: none; }
        }
      </style>
      <svg class="sprite" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
        <defs>
          <symbol id="i-book" viewBox="0 0 24 24"><path d="M12 5.5c-2.9-2.2-6.1-2.2-9-1v14c2.9-1.1 6.1-1.1 9 1.2 2.9-2.3 6.1-2.3 9-1.2v-14c-2.9-1.2-6.1-1.2-9 1Z"/><path d="M12 5.5v14.2"/></symbol>
          <symbol id="i-link" viewBox="0 0 24 24"><path d="M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></symbol>
          <symbol id="i-sliders" viewBox="0 0 24 24"><path d="M4 6h5m4 0h7M4 12h11m4 0h1M4 18h2m4 0h10M9 3v6m6 0v6M6 15v6"/></symbol>
          <symbol id="i-strip" viewBox="0 0 24 24"><rect x="4" y="2.75" width="16" height="18.5" rx="2.5"/><path d="M4 8h16M4 16h16"/></symbol>
          <symbol id="i-auto" viewBox="0 0 24 24"><path d="M8 3H5a2 2 0 0 0-2 2v3m13-5h3a2 2 0 0 1 2 2v3M3 16v3a2 2 0 0 0 2 2h3m8 0h3a2 2 0 0 0 2-2v-3"/></symbol>
          <symbol id="i-single" viewBox="0 0 24 24"><rect x="6" y="2.75" width="12" height="18.5" rx="1.8"/></symbol>
          <symbol id="i-double" viewBox="0 0 24 24"><rect x="2.5" y="3.75" width="19" height="16.5" rx="1.8"/><path d="M12 3.75v16.5"/></symbol>
          <symbol id="i-right" viewBox="0 0 24 24"><path d="M5 12h14m-6-6 6 6-6 6"/></symbol>
          <symbol id="i-left" viewBox="0 0 24 24"><path d="M19 12H5m6-6-6 6 6 6"/></symbol>
          <symbol id="i-down" viewBox="0 0 24 24"><path d="M12 5v14m-6-6 6 6 6-6"/></symbol>
        </defs>
      </svg>
      <section class="panel" aria-label="当前作品的阅读模式设置">
        <div class="context">${svg('book')}<span>阅读模式</span><span class="dot" aria-hidden="true"></span><span>当前作品</span></div>
        <fieldset class="scope-field">
          <legend>阅读模式设置</legend>
          <div class="segments" data-segments="scope">
            ${radio('scope', 'default', `${svg('link')}<span>跟随默认</span>`)}
            ${radio('scope', 'custom', `${svg('sliders')}<span>单独设置</span>`)}
          </div>
          <p class="help" data-scope-help>下方设置仅对当前作品生效</p>
        </fieldset>
        <div class="divider" aria-hidden="true"></div>
        <div data-custom-settings>
          <fieldset>
            <legend>阅读模式选择</legend>
            <div class="mode-grid">
              ${radio('mode', 'paged', `<span class="choice-icon">${svg('book')}</span><span class="choice-copy"><span class="choice-title">翻页</span><span class="choice-detail">按页切换</span></span><span class="radio-dot" aria-hidden="true"></span>`, 'choice')}
              ${radio('mode', 'webtoon', `<span class="choice-icon">${svg('strip')}</span><span class="choice-copy"><span class="choice-title">条漫</span><span class="choice-detail">连续滚动</span></span><span class="radio-dot" aria-hidden="true"></span>`, 'choice')}
            </div>
          </fieldset>
          <div data-paged-settings>
            <fieldset class="layout-field">
              <legend>单双页</legend>
              <div class="segments layout" data-segments="layout">
                ${radio('layout', 'auto', `${svg('auto')}<span>自动</span>`)}
                ${radio('layout', 'single', `${svg('single')}<span>单页</span>`)}
                ${radio('layout', 'double', `${svg('double')}<span>双页</span>`)}
              </div>
              <div class="layout-help">
                <p data-layout-help>根据可用阅读区域自动切换</p>
                <span class="adaptive-tag" data-auto-tag>自适应</span>
              </div>
            </fieldset>
            <div class="dependent" data-match-row>
              <label class="toggle-row">
                <span class="toggle-copy"><span class="toggle-title" id="match-title">双页自动匹配</span><span class="toggle-description" id="match-help">在双页显示时，自动调整页面配对</span></span>
                <span class="toggle-control"><span class="toggle-state" data-match-state aria-hidden="true">开启</span><span class="switch"><input type="checkbox" role="switch" data-setting="match" aria-labelledby="match-title" aria-describedby="match-help"><span class="switch-track" aria-hidden="true"></span></span></span>
              </label>
            </div>
            <fieldset class="direction-field">
              <legend>翻页方向</legend>
              <div class="direction-grid">
                ${radio('direction', 'ltr', `${svg('right')}<span class="direction-title">从左到右</span>`, 'direction')}
                ${radio('direction', 'rtl', `${svg('left')}<span class="direction-title">从右到左</span>`, 'direction')}
                ${radio('direction', 'vertical', `${svg('down')}<span class="direction-title">从上到下</span>`, 'direction')}
              </div>
            </fieldset>
          </div>
          <div class="webtoon-settings" data-webtoon-settings hidden>
            <label class="toggle-row">
              <span class="toggle-copy"><span class="toggle-title" id="gap-title">页间有空隙</span><span class="toggle-description" id="gap-help">在相邻页面之间留出间隔</span></span>
              <span class="toggle-control"><span class="toggle-state" data-gap-state aria-hidden="true">否</span><span class="switch"><input type="checkbox" role="switch" data-setting="gap" aria-labelledby="gap-title" aria-describedby="gap-help"><span class="switch-track" aria-hidden="true"></span></span></span>
            </label>
          </div>
        </div>
        <div data-inherited-settings hidden>
          <div class="inherited">
            <h2 class="inherited-heading">${svg('link')}<span>跟随全局阅读偏好</span></h2>
            <p class="inherited-description">默认设置变更后，当前作品同步采用。</p>
            <dl>
              <div><dt>阅读模式</dt><dd data-default-mode>翻页</dd></div>
              <div data-default-layout-row><dt>单双页</dt><dd data-default-layout>自动</dd></div>
              <div data-default-match-row><dt>双页自动匹配</dt><dd data-default-match>开启</dd></div>
              <div data-default-direction-row><dt>翻页方向</dt><dd data-default-direction>从右到左</dd></div>
              <div data-default-gap-row hidden><dt>页间有空隙</dt><dd data-default-gap>否</dd></div>
            </dl>
          </div>
          <p class="inherited-footnote">单独设置的值已保留，切回后可继续使用。</p>
        </div>
        <span class="sr-only" role="status" aria-live="polite" data-live></span>
      </section>`;

    class MihonReaderSettings extends HTMLElement {
      constructor() {
        super();
        this.attachShadow({ mode: 'open' }).appendChild(template.content.cloneNode(true));
        this._defaults = clone(DEFAULTS);
        this._state = { version: 3, scope: 'custom', custom: clone(DEFAULTS) };
        this._initialized = false;
        this._heightAnimation = null;
        this._reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
        this.shadowRoot.addEventListener('change', event => this._change(event));
      }
      connectedCallback() {
        if (!this._initialized) {
          this._load();
          this._render(false);
          this._initialized = true;
        }
      }
      disconnectedCallback() { this._heightAnimation?.cancel(); }
      get value() { return clone(this._state); }
      set value(value) {
        if (!value || typeof value !== 'object') return;
        this._state = {
          version: 3,
          scope: value.scope === 'default' ? 'default' : 'custom',
          custom: normalizePrefs(value.custom, this._defaults)
        };
        this._render(this._initialized);
        this._save();
      }
      get effectiveSettings() {
        return clone(this._state.scope === 'default' ? this._defaults : this._state.custom);
      }
      get defaultSettings() { return clone(this._defaults); }
      set defaultSettings(value) {
        this._defaults = normalizePrefs(value);
        this._render(this._initialized);
      }
      _load() {
        const key = this.getAttribute('storage-key');
        if (!key) return;
        try {
          const stored = JSON.parse(localStorage.getItem(key) || 'null');
          if (stored && stored.version === 3 && stored.custom) {
            this._state = {
              version: 3,
              scope: stored.scope === 'default' ? 'default' : 'custom',
              custom: normalizePrefs(stored.custom)
            };
          }
        } catch (_) { /* Storage may be unavailable for private / local-file contexts. */ }
      }
      _save() {
        const key = this.getAttribute('storage-key');
        if (!key) return;
        try { localStorage.setItem(key, JSON.stringify(this._state)); }
        catch (_) { /* The prototype remains interactive without persistence. */ }
      }
      _change(event) {
        const input = event.target;
        if (!(input instanceof HTMLInputElement) || !input.dataset.setting) return;
        const key = input.dataset.setting;
        if (key === 'scope') this._state.scope = input.value;
        else this._state.custom[key] = input.type === 'checkbox' ? input.checked : input.value;
        this._state.custom = normalizePrefs(this._state.custom);
        this._save();
        this._render(true);
        const c = this._state.custom;
        const messages = {
          scope: this._state.scope === 'default' ? '已跟随默认设置，单独设置的值已保留。' : '已恢复此作品的单独设置。',
          mode: c.mode === 'paged' ? '已选择翻页模式。' : '已选择条漫模式。',
          layout: `已选择${LAYOUTS[c.layout]}布局。`,
          match: `双页自动匹配已${c.match ? '开启' : '关闭'}。`,
          direction: `翻页方向已设为${DIRECTIONS[c.direction]}。`,
          gap: c.gap ? '页面间隙已开启。' : '页面间隙已关闭。'
        };
        this.shadowRoot.querySelector('[data-live]').textContent = messages[key] || '设置已更新。';
        this.dispatchEvent(new CustomEvent('settings-change', {
          detail: { ...this.value, effective: this.effectiveSettings }, bubbles: true, composed: true
        }));
      }
      _render(animate) {
        const root = this.shadowRoot;
        const panel = root.querySelector('.panel');
        const beforeHeight = panel.getBoundingClientRect().height;
        this._heightAnimation?.cancel();
        const s = this._state;
        const c = s.custom;
        const d = this._defaults;
        const show = (selector, visible) => { root.querySelector(selector).hidden = !visible; };
        const text = (selector, value) => { root.querySelector(selector).textContent = value; };
        root.querySelectorAll('[data-setting]').forEach(input => {
          const key = input.dataset.setting;
          const value = key === 'scope' ? s.scope : c[key];
          input.checked = input.type === 'checkbox' ? value : input.value === value;
          if (input.getAttribute('role') === 'switch') input.setAttribute('aria-checked', String(input.checked));
        });
        root.querySelector('[data-segments="scope"]').style.setProperty('--index', s.scope === 'default' ? 0 : 1);
        root.querySelector('[data-segments="layout"]').style.setProperty('--index', ['auto', 'single', 'double'].indexOf(c.layout));
        show('[data-custom-settings]', s.scope === 'custom');
        show('[data-inherited-settings]', s.scope === 'default');
        show('[data-paged-settings]', c.mode === 'paged');
        show('[data-webtoon-settings]', c.mode === 'webtoon');
        show('[data-match-row]', c.mode === 'paged' && c.layout !== 'single');
        show('[data-auto-tag]', c.layout === 'auto');
        const vertical = root.querySelector('[data-setting="direction"][value="vertical"]');
        vertical.closest('label').hidden = c.layout === 'double';
        vertical.disabled = c.layout === 'double';
        root.querySelector('.direction-grid').style.gridTemplateColumns = `repeat(${c.layout === 'double' ? 2 : 3}, minmax(0, 1fr))`;
        text('[data-scope-help]', s.scope === 'custom' ? '下方设置仅对当前作品生效' : '当前作品使用全局默认设置');
        text('[data-match-state]', c.match ? '开启' : '关闭');
        text('[data-gap-state]', c.gap ? '是' : '否');
        text('[data-layout-help]', c.layout === 'auto' ? '根据可用阅读区域自动切换' : c.layout === 'single' ? '每次显示一页' : '每次显示两页，仅支持左右翻页');
        text('[data-default-mode]', d.mode === 'paged' ? '翻页' : '条漫');
        text('[data-default-layout]', LAYOUTS[d.layout]);
        text('[data-default-match]', d.match ? '开启' : '关闭');
        text('[data-default-direction]', DIRECTIONS[d.direction]);
        text('[data-default-gap]', d.gap ? '是' : '否');
        show('[data-default-layout-row]', d.mode === 'paged');
        show('[data-default-match-row]', d.mode === 'paged' && d.layout !== 'single');
        show('[data-default-direction-row]', d.mode === 'paged');
        show('[data-default-gap-row]', d.mode === 'webtoon');
        const afterHeight = panel.getBoundingClientRect().height;
        if (animate && !this._reducedMotion.matches && Math.abs(beforeHeight - afterHeight) > 1) {
          this._heightAnimation = panel.animate(
            [{ height: `${beforeHeight}px` }, { height: `${afterHeight}px` }],
            { duration: 230, easing: 'cubic-bezier(.2,.75,.25,1)' }
          );
        }
      }
    }
    if (!customElements.get('mihon-reader-settings')) customElements.define('mihon-reader-settings', MihonReaderSettings);
  })();
