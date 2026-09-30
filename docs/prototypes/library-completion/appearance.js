(() => {
  "use strict";
  const translations = MihonAppearanceLocales.translations;
  const vectors = {
  "check": "<svg class=\"filled\" aria-hidden=\"true\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 -960 960 960\"><path d=\"m382-354 339-339q12-12 28-12t28 12q12 12 12 28.5T777-636L410-268q-12 12-28 12t-28-12L182-440q-12-12-11.5-28.5T183-497q12-12 28.5-12t28.5 12l142 143Z\"/></svg>",
  "arrow_back": "<svg class=\"filled\" aria-hidden=\"true\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 -960 960 960\"><path d=\"m313-440 196 196q12 12 11.5 28T508-188q-12 11-28 11.5T452-188L188-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l264-264q11-11 27.5-11t28.5 11q12 12 12 28.5T508-715L313-520h447q17 0 28.5 11.5T800-480q0 17-11.5 28.5T760-440H313Z\"/></svg>",
  "palette": "<svg class=\"filled\" aria-hidden=\"true\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 -960 960 960\"><path d=\"M480-80q-82 0-155-31.5t-127.5-86Q143-252 111.5-325T80-480q0-83 32.5-156t88-127Q256-817 330-848.5T488-880q80 0 151 27.5t124.5 76q53.5 48.5 85 115T880-518q0 115-70 176.5T640-280h-74q-9 0-12.5 5t-3.5 11q0 12 15 34.5t15 51.5q0 50-27.5 74T480-80Zm0-400Zm-220 40q26 0 43-17t17-43q0-26-17-43t-43-17q-26 0-43 17t-17 43q0 26 17 43t43 17Zm120-160q26 0 43-17t17-43q0-26-17-43t-43-17q-26 0-43 17t-17 43q0 26 17 43t43 17Zm200 0q26 0 43-17t17-43q0-26-17-43t-43-17q-26 0-43 17t-17 43q0 26 17 43t43 17Zm120 160q26 0 43-17t17-43q0-26-17-43t-43-17q-26 0-43 17t-17 43q0 26 17 43t43 17ZM480-160q9 0 14.5-5t5.5-13q0-14-15-33t-15-57q0-42 29-67t71-25h70q66 0 113-38.5T800-518q0-121-92.5-201.5T488-800q-136 0-232 93t-96 227q0 133 93.5 226.5T480-160Z\"/></svg>"
};
  const dates = ["", "MM/dd/yy", "dd/MM/yy", "yyyy-MM-dd", "dd MMM yyyy", "MMM dd, yyyy"];
  const tablets = { AUTOMATIC: "自动", ALWAYS: "始终开启", LANDSCAPE: "横屏", NEVER: "关闭" };
  const menu = [["appearance","外观","主题、日期格式","settings"],["library","书架","分类、全局更新","library"],["reader","阅读器","阅读模式、显示、导航","reader"],["downloads","下载","下载位置、自动下载","getApp"],["tracking","追踪","同步进度、追踪服务","sync"],["browse","浏览","图源、扩展","browse"],["data","数据与存储","备份、存储空间","storage"],["security","安全","应用锁、隐私","lock"],["advanced","高级","开发者选项、实验功能","settings"],["about","关于","Mihon","info"]];
  function create({ esc, icon, button, navigate, render, openModal, closeModal, notice, applyTheme, selection }) {
    const state = { mode: "SYSTEM", theme: selection.theme, amoled: selection.amoled, language: "", tablet: "AUTOMATIC", date: "", relative: true, images: true };
    let activeTablet = state.tablet;
    function wide() { const landscape = innerWidth > innerHeight; return activeTablet === "ALWAYS" || activeTablet === "LANDSCAPE" && landscape || activeTablet === "AUTOMATIC" && Math.min(innerWidth,innerHeight) >= (landscape ? 600 : 700); }
    let systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", event => { systemDark = event.matches; if (state.mode === "SYSTEM") { apply(); render(); } });
    const scroll = {};
    const english = () => state.language.startsWith("en");
    const labels = {"设置": "label_settings", "外观": "pref_category_appearance", "主题": "pref_category_theme", "显示": "pref_category_display", "跟随系统": "theme_system", "浅色": "theme_light", "深色": "theme_dark", "纯黑深色模式": "pref_dark_theme_pure_black", "应用语言": "pref_app_language", "平板界面": "pref_tablet_ui_mode", "日期格式": "pref_date_format", "相对时间戳": "pref_relative_format", "在漫画注释中显示图片": "pref_display_images_description", "默认": "label_default", "取消": "action_cancel", "今天": "relative_time_today"};
    const tr = key => translations[locale()]?.[key] || translations.en?.[key] || translations["zh-CN"]?.[key] || key;
    const text = (zh, en) => labels[zh] ? tr(labels[zh]) : english() ? en : zh;
    const locale = () => state.language || "zh-CN";
    function formatDate(value = new Date(), format = state.date) {
      const d = new Date(value), pad = v => String(v).padStart(2, "0");
      const tokens = { yyyy: String(d.getFullYear()), yy: String(d.getFullYear()).slice(-2), MM: pad(d.getMonth()+1), dd: pad(d.getDate()), MMM: d.toLocaleDateString(locale(), { month: "short" }) };
      return format ? format.replace(/yyyy|MMM|MM|dd|yy/g, k => tokens[k]) : new Intl.DateTimeFormat(locale(), {dateStyle:"short"}).format(d);
    }
    function chapterDate(value, now = new Date()) {
      const day = date => Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / 86400000;
      const difference = day(new Date(now)) - day(new Date(value));
      if (!state.relative || difference >= 7 || difference < -7) return formatDate(value);
      if (difference === 0) return tr("relative_time_today");
      const key = difference < 0 ? "upcoming_relative_time" : "relative_time";
      const number = Math.abs(difference);
      const quantity = new Intl.PluralRules(locale()).select(number);
      const resource = MihonAppearanceLocales.plurals[locale()]?.[key] || MihonAppearanceLocales.plurals.en[key];
      return (resource[quantity] || resource.other).replace(/%1\$d|%d/g,String(number));
    }
    function apply() {
      if (state.mode === "SYSTEM") systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
      document.documentElement.lang = locale();
      applyTheme({ theme: state.theme, mode: state.mode === "SYSTEM" ? systemDark ? "dark" : "light" : state.mode.toLowerCase(), amoled: state.amoled });
    }
    function external(next) {
      state.theme = next.theme; state.amoled = next.amoled;
      state.mode = next.preferenceMode || (next.mode === "light" ? "LIGHT" : "DARK");
    }
    function header(title, back) { return `<header class="bar appearance-toolbar">${back ? `<button class="icon" data-action="${back}" data-testid="${back}" aria-label="返回">${vectors.arrow_back}</button>` : ""}<h1>${esc(title)}</h1>${back === "settings-back" ? `<button class="icon" data-testid="settings-search" disabled title="设置搜索不在本次外观审阅范围内">${icon("search")}</button>` : ""}</header>`; }
    function row(id,title,subtitle="") { return `<button class="appearance-pref" data-action="appearance-${id}" data-testid="appearance-${id}"><span><span class="appearance-pref-title">${title}</span>${subtitle ? `<small>${subtitle}</small>` : ""}</span></button>`; }
    function toggle(id,title,subtitle="",disabled=false) { return `<label class="appearance-pref ${disabled ? "disabled" : ""}"><span><span class="appearance-pref-title">${title}</span>${subtitle ? `<small>${subtitle}</small>` : ""}</span><input class="appearance-switch" type="checkbox" data-action="appearance-${id}" data-testid="appearance-${id}" ${state[id] ? "checked" : ""} ${disabled ? "disabled" : ""}></label>`; }
    function cards() {
      const mode = state.mode === "SYSTEM" ? systemDark ? "dark" : "light" : state.mode.toLowerCase();
      return MihonThemes.catalog.map(({id,name}) => {
        const resource = id === "DEFAULT" ? "label_default" : "theme_" + ({MONET:"monet",STRAWBERRY_DAIQUIRI:"strawberrydaiquiri",MIDNIGHT_DUSK:"midnightdusk",GREEN_APPLE:"greenapple",TEALTURQUOISE:"tealturquoise",TIDAL_WAVE:"tidalwave",TOKYONIGHT:"tokyonight"}[id] || id.toLowerCase());
        name = tr(resource);
        const c = MihonThemes.scheme(id,mode,state.amoled), selected = state.theme === id;
        return `<div class="appearance-theme-item"><button class="appearance-theme-card" data-theme-card="${id}" data-action="appearance-theme" data-id="${id}" data-testid="appearance-theme-${id}" aria-label="${esc(name)}" aria-pressed="${selected}" style="--card-background:${c.background};--card-primary:${c.primary};--card-text:${c.onSurface};--card-outline:${selected ? c.primary : c.outlineVariant};--card-cover:${c.outlineVariant};--card-container:${c.surfaceContainer};--card-secondary:${c.secondary};--card-tertiary:${c.tertiary}"><span class="appearance-mini-appbar"><span></span>${selected ? `<span class="appearance-mini-check">${`<svg class="filled" aria-hidden="true" xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960"><path d="m424-408-86-86q-11-11-28-11t-28 11q-11 11-11 28t11 28l114 114q12 12 28 12t28-12l226-226q11-11 11-28t-11-28q-11-11-28-11t-28 11L424-408Zm56 328q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Z"/></svg>`}</span>` : ""}</span><span class="appearance-mini-cover"><span class="appearance-mini-badges"><i></i><i></i></span></span><span class="appearance-mini-bottom"><i></i><span></span></span></button><span class="appearance-theme-name">${esc(name)}</span></div>`;
      }).join("");
    }
    function viewContent(route) {
      if (route === "settings") return `${header(text("设置","Settings"),"settings-back")}<div class="appearance-scroll settings-menu" data-testid="settings-page">${menu.map(([id,title,subtitle,ico])=>`<button class="settings-menu-item ${id === "appearance" && wide() ? "selected" : ""}" data-action="settings-${id}" data-testid="settings-${id}" ${!["appearance","library"].includes(id) ? 'disabled title="此设置不在本次外观审阅范围内"' : ""}>${id === "appearance" ? vectors.palette : icon(ico)}<span><span>${esc(id === "appearance" ? tr("pref_category_appearance") : id === "library" ? tr("pref_category_library") : title)}</span><small>${esc(id === "appearance" ? tr("pref_appearance_summary") : id === "library" ? tr("pref_library_summary") : subtitle)}</small></span></button>`).join("")}</div>`;
      if (route === "appearance-language") return `${header(text("应用语言","App language"),"appearance-back")}<div class="appearance-scroll" data-testid="appearance-language-page">${languages().map(([id,name,localized])=>`<button class="appearance-language-row" data-action="appearance-language-choice" data-id="${id}" data-testid="appearance-language-${id || "default"}" aria-pressed="${state.language===id}"><span>${esc(name)}${localized ? `<small>${esc(localized)}</small>` : ""}</span>${state.language===id ? vectors.check : ""}</button>`).join("")}</div>`;
      return `${header(text("外观","Appearance"),wide() ? null : "appearance-back")}<div class="appearance-scroll" data-testid="appearance-page"><h2 class="appearance-group">${text("主题","Theme")}</h2><div class="appearance-mode-row">${[["SYSTEM","跟随系统","System"],["LIGHT","浅色","Light"],["DARK","深色","Dark"]].map(([id,zh,en])=>`<button data-action="appearance-mode" data-id="${id}" data-testid="appearance-mode-${id.toLowerCase()}" aria-pressed="${state.mode===id}">${state.mode===id ? vectors.check : ""}${text(zh,en)}</button>`).join("")}</div><div class="appearance-themes" data-testid="appearance-themes">${cards()}</div>${state.mode === "LIGHT" ? "" : toggle("amoled",text("纯黑深色模式","Pure black dark mode"))}<div class="appearance-group-spacer"></div><h2 class="appearance-group">${text("显示","Display")}</h2>${row("language",text("应用语言","App language"))}${row("tablet",text("平板界面","Tablet layout"),tr({AUTOMATIC:"automatic_background",ALWAYS:"lock_always",LANDSCAPE:"landscape",NEVER:"lock_never"}[state.tablet]))}${row("date",text("日期格式","Date format"),esc(`${state.date || text("默认","Default")} (${formatDate()})`))}${toggle("relative",text("相对时间戳","Relative timestamps"),tr("pref_relative_format_summary").replace("%1$s",tr("relative_time_today")).replace("%2$s",formatDate()))}${toggle("images",text("在漫画注释中显示图片","Show images in descriptions"))}</div>`;
    }
    function view(route) {
      if (!wide()) return viewContent(route);
      return `<div class="settings-two-pane"><aside class="settings-panel">${viewContent("settings")}</aside><section class="appearance-panel ${route === "appearance-language" ? "language-panel" : ""}">${viewContent(route === "settings" ? "appearance" : route)}</section></div>`;
    }
    function languages() {
      const current = new Intl.DisplayNames([locale()], {type:"language"});
      const names = MihonAppearanceLocales.languages.map(({tag,name,localized}) => [tag,name,locale() === "zh-CN" ? localized : current.of(tag)]);
      return [["",text("默认","Default"),""],...names];
    }
    function dialog(kind) {
      const entries = kind === "date" ? dates.map(id=>[id,`${id || text("默认","Default")} (${formatDate(new Date(),id)})`]) : Object.entries(tablets).map(([id,label]) => [id,tr({AUTOMATIC:"automatic_background",ALWAYS:"lock_always",LANDSCAPE:"landscape",NEVER:"lock_never"}[id])]);
      return `<div class="appearance-list-options">${entries.map(([id,label])=>`<button role="radio" aria-checked="${state[kind]===id}" data-action="appearance-${kind}-choice" data-id="${id}" data-appearance-${kind} data-testid="appearance-${kind}-${id || "default"}"><span class="appearance-radio" aria-hidden="true"></span>${esc(label)}</button>`).join("")}</div><button data-action="modal-close" data-testid="appearance-cancel" class="appearance-cancel">${text("取消","Cancel")}</button>`;
    }
    function handle(action, el) {
      if (action === "settings-back") { navigate("more","settings-open"); return true; }
      if (action === "settings-appearance") { navigate("appearance","appearance-back"); return true; }
      if (action === "settings-library") { openModal("settings"); return true; }
      if (action === "appearance-back") { if (wide() && !document.querySelector('[data-testid="appearance-language-page"]')) { navigate("more","settings-open"); return true; } navigate(document.querySelector('[data-testid="appearance-language-page"]') ? "appearance" : "settings", document.querySelector('[data-testid="appearance-language-page"]') ? "appearance-language" : "settings-appearance"); return true; }
      if (!action.startsWith("appearance-")) return false;
      if (action === "appearance-language") { navigate("appearance-language","appearance-back"); return true; }
      if (action === "appearance-date" || action === "appearance-tablet") { openModal(action); return true; }
      if (action === "appearance-date-choice" || action === "appearance-tablet-choice") {
        const key = action.includes("date") ? "date" : "tablet";
        if (state[key]===el.dataset.id) return true;
        state[key] = el.dataset.id;
        closeModal();
        if (key === "tablet") notice(tr("requires_app_restart"));
        render();return true;
      }
      if (action === "appearance-theme") state.theme = el.dataset.id;
      if (action === "appearance-mode") state.mode = el.dataset.id;
      if (action === "appearance-language-choice") state.language = el.dataset.id;
      if (["appearance-amoled","appearance-relative","appearance-images"].includes(action)) state[action.slice(11)] = el.checked;
      apply();render();return true;
    }
    function capture() { for(const id of ["appearance-page","appearance-themes","appearance-language-page","settings-page"]) {const el=document.querySelector(`[data-testid="${id}"]`);if(el)scroll[id]={top:el.scrollTop,left:el.scrollLeft};} }
    function restore() { for(const [id,pos] of Object.entries(scroll)) {const el=document.querySelector(`[data-testid="${id}"]`);if(el){el.scrollTop=pos.top;el.scrollLeft=pos.left;}} }
    return {state,view,dialog,handle,apply,external,capture,restore,chapterDate,formatDate, title: kind => tr(kind === "appearance-date" ? "pref_date_format" : "pref_tablet_ui_mode"), restart: () => {activeTablet = state.tablet;} };
  }
  window.MihonAppearance = {create};
})();
