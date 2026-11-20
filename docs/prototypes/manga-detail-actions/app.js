(() => {
  'use strict';
  const android = new URLSearchParams(location.search).get('platform') === 'android';
  document.body.classList.toggle('android', android);
  const paths = {
    heart: '<path d="M20.5 4.8a5.2 5.2 0 0 0-7.4 0L12 6l-1.1-1.2a5.2 5.2 0 0 0-7.4 7.4L12 21l8.5-8.8a5.2 5.2 0 0 0 0-7.4Z"/>',
    clock: '<path d="M5 3h14M5 21h14M7 3v4l5 5-5 5v4M17 3v4l-5 5 5 5v4"/>',
    tracking: '<path d="M20 7a9 9 0 0 0-15-2L2 8m0-5v5h5M4 17a9 9 0 0 0 15 2l3-3m0 5v-5h-5"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c5 5 5 13 0 18-5-5-5-13 0-18Z"/>',
    trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
    link: '<path d="m10 13 4-4m-6 6-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0m2 2 1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0" transform="translate(1 1)"/>',
    share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 10.5 6.8-4m-6.8 7 6.8 4"/>',
    settings: '<path d="m9 3-1 3-3 1-2 5 2 5 3 1 1 3h6l1-3 3-1 2-5-2-5-3-1-1-3Z"/><circle cx="12" cy="12" r="3"/>',
    back: '<path d="m14 5-7 7 7 7M7 12h14"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
    pen: '<path d="m15 4 5 5M4 20l5-1L21 7l-5-5L4 14Z"/>',
    download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
    downloaded: '<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',
    more: '<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>',
    filter: '<path d="M3 5h18M6 12h12M10 19h4"/>',
    book: '<path d="M4 4h7l1 2 1-2h7v15h-7l-1 2-1-2H4Z"/><path d="M12 6v15"/>',
    play: '<path d="m9 5 10 7-10 7Z"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',
    sort: '<path d="M3 6h9M3 12h7M3 18h5M17 5v14m-4-4 4 4 4-4"/>',
    left: '<path d="m14 6-6 6 6 6"/>',
    right: '<path d="m10 6 6 6-6 6"/>',
    battery: '<rect x="2" y="7" width="17" height="10" rx="2"/><path d="M22 10v4M5 10h10v4H5Z"/>',
    wifi: '<path d="M2 8a16 16 0 0 1 20 0M5 12a11 11 0 0 1 14 0m-11 4a6 6 0 0 1 8 0m-4 4h.01"/>'
  };
  const icon = (name, extra = '') => `<svg class="icon ${extra}" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.info}</svg>`;
  const actions = [
    {id:'library', label:'收藏', icon:'heart', detail:'始终保留，位置可移动'},
    {id:'interval', label:'更新周期', icon:'clock', detail:'管理检查更新的频率'},
    {id:'tracking', label:'追踪', icon:'tracking', detail:'查看阅读进度与评分'},
    {id:'web', label:'网页', icon:'globe', detail:'查看来源网页'},
    {id:'delete', label:'读完即删', icon:'trash', detail:'仅设置当前作品'},
    {id:'copy', label:'复制链接', icon:'link', detail:'复制作品来源地址'},
    {id:'share', label:'分享链接', icon:'share', detail:'分享给其他应用'}
  ];
  const books = {
    journey: {title:'长旅之后', author:'伊原 遥', artist:'雨森 绘', count:28, tags:['奇幻', '旅行', '日常'], description:'最后一场冒险结束后，旅人回到了熟悉的山间小镇。那些未曾说出口的话，和一路珍藏的风景，都在平静的日子里慢慢有了回响。', chapter:['归途的风', '山间小镇', '寄往远方的信', '旧地图上的名字', '下雨之前', '一杯温热的茶', '没有终点的路', '晚霞与约定']},
    letter: {title:'深蓝来信', author:'青井 澄', artist:'川原 由', count:16, tags:['青春', '海边', '日常'], description:'一封没有署名的信，随着潮水来到旧灯塔。夏天还很漫长，而两个人的故事，才刚刚开始。', chapter:['潮声', '旧灯塔', '没有署名的信', '晴天的约定', '海边咖啡馆', '最后一班渡轮', '蓝色邮票', '风来的方向']}
  };
  const state = {book:'journey', primary:['library','interval','tracking','web'], order:actions.map(a => a.id), rules:{}, favorites:{}, modal:null};
  const app = document.querySelector('#app');
  const modal = document.querySelector('#modal');
  const toastElement = document.querySelector('#toast');
  let toastTimer;
  let pointer = null;
  let longPressTimer;
  let suppressClickUntil = 0;
  let railObserver;
  let sortPointer;
  let sortTimer;
  let sortGhost;
  let sortFrame;
  let sortSuppressUntil = 0;
  const ruleLabels = {inherit:'跟随全局', on:'开启', off:'关闭'};
  function toast(message) {
    clearTimeout(toastTimer);
    toastElement.textContent = message;
    toastElement.classList.add('is-visible');
    toastTimer = setTimeout(() => toastElement.classList.remove('is-visible'), 2200);
  }
  function actionMarkup(action, preview = false) {
    const active = action.id === 'library' && state.favorites[state.book] || action.id === 'delete' && state.rules[state.book] === 'on';
    const label = action.id === 'library' && active ? '已收藏' : action.label;
    const title = action.id === 'delete' ? `读完即删：${ruleLabels[state.rules[state.book] || 'inherit']}` : label;
    return `<button class="action-button ${active ? 'is-active' : ''}" data-action="${action.id}" ${preview ? 'tabindex="-1" aria-hidden="true"' : `data-testid="action-${action.id}" title="${title}"`} type="button">${icon(action.icon)}<span>${label}</span>${active && action.id === 'delete' ? '<i class="action-status"></i>' : ''}</button>`;
  }
  function rowMarkup(ids, preview = false) {
    return `<div class="action-row" style="--count:${ids.length}">${ids.map(id => actionMarkup(actions.find(a => a.id === id), preview)).join('')}</div>`;
  }
  function pageCount(deck) {
    return 1 + Math.ceil(Math.max(0, deck.scrollWidth - deck.clientWidth - 1) / Math.max(1, deck.clientWidth));
  }
  function currentPage(deck) {
    const end = deck.scrollWidth - deck.clientWidth;
    return deck.scrollLeft >= end - 1 ? pageCount(deck) - 1 : Math.round(deck.scrollLeft / Math.max(1, deck.clientWidth));
  }
  function updateScrollMeter(deck) {
    const shell = deck.closest('.deck-shell');
    const end = deck.scrollWidth - deck.clientWidth;
    if (android) {
      const thumb = shell.querySelector('.scroll-thumb');
      const width = 100 * deck.clientWidth / Math.max(1, deck.scrollWidth);
      thumb.style.width = `${width}%`;
      thumb.style.left = `${end > 0 ? deck.scrollLeft / end * (100 - width) : 0}%`;
      return;
    }
    const dots = shell.querySelector('.page-indicator');
    const count = pageCount(deck);
    const active = currentPage(deck);
    if (dots.children.length !== count) {
      dots.replaceChildren(...Array.from({length:count}, () => {
        const dot = document.createElement('span');
        dot.className = 'page-dot';
        return dot;
      }));
    }
    for (const [index, dot] of [...dots.children].entries()) {
      if (index === active) dot.setAttribute('aria-current', 'page');
      else dot.removeAttribute('aria-current');
    }
    dots.setAttribute('aria-label', `第 ${active + 1} 页，共 ${count} 页`);
    const prev = shell.querySelector('[data-page="-1"]');
    const next = shell.querySelector('[data-page="1"]');
    prev.disabled = deck.scrollLeft <= 1;
    next.disabled = deck.scrollLeft >= end - 1;
  }
  function sizeRail(reset = false) {
    const deck = document.querySelector('.action-deck');
    const previousSlot = Number(deck.dataset.slot);
    const slot = deck.clientWidth / state.primary.length;
    if (reset || slot !== previousSlot) {
      const position = reset || !previousSlot ? 0 : deck.scrollLeft / previousSlot;
      deck.dataset.slot = String(slot);
      deck.style.setProperty('--slot', `${slot}px`);
      deck.scrollLeft = position * slot;
    }
    updateScrollMeter(deck);
  }
  function renderDeck(reset = false) {
    const deck = document.querySelector('.action-deck');
    const position = deck.scrollLeft;
    deck.closest('.deck-shell').style.setProperty('--visible', state.primary.length);
    const ids = [...state.primary, ...state.order.filter(id => !state.primary.includes(id))];
    deck.innerHTML = `<div class="action-track">${ids.map(id => actionMarkup(actions.find(a => a.id === id))).join('')}<button class="action-button setup-entry" data-testid="customize" type="button">${icon('sort')}<span>排序</span></button></div>`;
    deck.scrollLeft = position;
    sizeRail(reset);
  }
  function pagingButton(direction) {
    return `<button class="page-arrow" type="button" data-page="${direction}" aria-label="${direction < 0 ? '上一页' : '下一页'}" disabled>${icon(direction < 0 ? 'left' : 'right')}</button>`;
  }
  function renderApp() {
    railObserver?.disconnect();
    const book = books[state.book];
    app.innerHTML = `<main class="app-shell">
      <div class="statusbar" aria-hidden="true"><span>9:41</span><span class="status-icons">${icon('wifi')}${icon('battery')}</span></div>
      <header class="app-toolbar"><span class="icon-button" aria-hidden="true">${icon('back')}</span><span class="toolbar-title">作品详情</span><span class="toolbar-trailing" aria-hidden="true">${icon('download')}${icon('filter')}${icon('more')}</span></header>
      <div class="detail-layout">
        <section class="book-panel" aria-label="作品信息">
          <div class="book-header"><div class="cover-wrap"><img class="book-cover" src="cover.svg" alt="虚构作品长旅之后的山间旅人封面" draggable="false" ${state.book === 'letter' ? 'style="filter:hue-rotate(35deg) saturate(.7)"' : ''}></div><div class="book-meta"><h1 class="book-title">${book.title}</h1><div class="book-byline"><span>${icon('pen')}${book.author}</span><span>${book.artist}</span></div><div class="source-line"><span>云间书屋</span><span class="source-dot">●</span><span>简体中文</span><span class="source-dot">●</span><span>连载中</span></div></div></div>
          <div class="deck-shell" data-testid="actions">
            <div class="rail-layout">
              ${android ? '' : pagingButton(-1)}
              <div class="rail-frame"><div class="action-deck" data-testid="action-deck" aria-label="作品操作"></div></div>
              ${android ? '' : pagingButton(1)}
            </div>
            <div class="deck-caption">${android ? '<span class="scroll-meter" aria-hidden="true"><i class="scroll-thumb"></i></span>' : '<div class="page-indicator" role="group"></div>'}</div>
          </div>
          <div class="book-description"><div class="tags">${book.tags.map(tag => `<span class="tag">${tag}</span>`).join('')}</div><p>${book.description}</p></div>
        </section>
        <section class="chapter-panel" aria-label="章节预览"><div class="chapter-heading"><h2>章节 <span>${book.count}</span></h2><span>按最新排序</span></div><div class="chapter-list">${book.chapter.map((title, index) => `<div class="chapter-row ${index > 3 ? 'is-read' : ''}"><span class="chapter-unread"></span><div class="chapter-copy"><strong>第 ${book.count - index} 话 · ${title}</strong><small>${index < 2 ? '今天' : `${index + 1} 天前`}　·　${index === 0 ? '32' : '28'} 页</small></div>${icon(index < 3 ? 'downloaded' : 'download', index < 3 ? 'downloaded' : '')}</div>`).join('')}</div><div class="chapter-footer"><span>还有 ${book.count - 8} 个章节</span><div class="continue-static">${icon('play')}继续阅读</div></div></section>
      </div><div class="phone-handle" aria-hidden="true"></div>
    </main>`;
    renderDeck(true);
    wireDeck();
  }
  function wireDeck() {
    const deck = document.querySelector('.deck-shell');
    const viewport = deck.querySelector('.action-deck');
    function turnPage(direction) {
      const width = Math.max(1, viewport.clientWidth);
      const offset = viewport.scrollLeft;
      const page = direction > 0 ? Math.floor((offset + 1) / width) + 1 : Math.ceil((offset - 1) / width) - 1;
      viewport.scrollLeft = page * width;
      updateScrollMeter(viewport);
    }
    viewport.addEventListener('scroll', () => updateScrollMeter(viewport));
    railObserver = new ResizeObserver(() => sizeRail());
    railObserver.observe(viewport);
    deck.addEventListener('click', event => {
      if (Date.now() < suppressClickUntil && event.detail) return;
      const arrow = event.target.closest('[data-page]');
      if (arrow) {
        if (!arrow.disabled) turnPage(Number(arrow.dataset.page));
        return;
      }
      if (event.target.closest('.setup-entry')) return openCustomize(event.target.closest('button'));
      const button = event.target.closest('[data-action]');
      if (!button) return;
      const action = button.dataset.action;
      if (action === 'delete') return openRule(button);
      if (action === 'library') {
        state.favorites[state.book] = !state.favorites[state.book];
        renderDeck();
        toast(state.favorites[state.book] ? '已加入示例书架' : '已从示例书架移除');
      } else {
        toast({interval:'更新周期：每 3 天检查一次（示例）',tracking:'阅读进度：第 24 话（示例）',web:'来源网页入口（原型演示）',copy:'作品链接已模拟复制',share:'分享链接入口（原型演示）'}[action]);
      }
    });
    let edgeFrame;
    function moveRailSort() {
      const {target, lastX, grabX} = pointer;
      const track = target.parentElement;
      const actions = [...track.querySelectorAll('[data-action]')];
      const slot = Number(viewport.dataset.slot);
      const logicalX = lastX - viewport.getBoundingClientRect().left + viewport.scrollLeft;
      const from = actions.indexOf(target);
      const to = Math.max(0, Math.min(actions.length - 1, Math.floor(logicalX / slot)));
      if (from !== to) track.insertBefore(target, from < to ? actions[to].nextElementSibling : actions[to]);
      target.style.setProperty('--drag-x', `${logicalX - grabX - to * slot}px`);
    }
    function scrollAtEdge(now) {
      if (!pointer?.sorting || !pointer.moved) return;
      const bounds = viewport.getBoundingClientRect();
      const slot = Number(viewport.dataset.slot);
      const x = pointer.lastX - bounds.left;
      const edge = x < slot * .35 ? -1 : x > bounds.width - slot * .35 ? 1 : 0;
      const elapsed = Math.min(now - (pointer.lastFrame ?? now), 32);
      pointer.lastFrame = now;
      if (edge) {
        const before = viewport.scrollLeft;
        // Axis offsets are rounded by the browser, so advance at least one pixel per frame.
        if (elapsed) viewport.scrollLeft += edge * Math.max(1, slot * elapsed / 300);
        if (viewport.scrollLeft !== before) moveRailSort();
      }
      edgeFrame = requestAnimationFrame(scrollAtEdge);
    }
    function clearPointer(commit = false) {
      clearTimeout(longPressTimer);
      cancelAnimationFrame(edgeFrame);
      if (pointer?.sorting) {
        if (commit) {
          state.order = [...viewport.querySelectorAll('[data-action]')].map(el => el.dataset.action);
          state.primary = state.order.slice(0, state.primary.length);
        }
        renderDeck();
      }
      pointer = null;
    }
    viewport.addEventListener('pointerdown', event => {
      if (event.button !== 0 || !event.isPrimary) return;
      suppressClickUntil = 0;
      const target = event.target.closest('.action-button');
      pointer = {id:event.pointerId, target, x:event.clientX, y:event.clientY, lastX:event.clientX, moved:false, held:false};
      if (target) longPressTimer = setTimeout(() => {
        pointer.held = true;
        if (target.matches('[data-action]')) {
          pointer.sorting = true;
          pointer.grabX = pointer.x - target.getBoundingClientRect().left;
          target.classList.add('is-rail-dragging');
          viewport.setPointerCapture(pointer.id);
        }
      }, 550);
    });
    viewport.addEventListener('pointermove', event => {
      if (pointer?.id !== event.pointerId) return;
      const wasMoved = pointer.moved;
      if (Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y) > 9) {
        clearTimeout(longPressTimer);
        pointer.moved = true;
      }
      if (pointer.sorting) {
        pointer.lastX = event.clientX;
        if (pointer.moved) {
          moveRailSort();
          if (!wasMoved) edgeFrame = requestAnimationFrame(scrollAtEdge);
        }
        return;
      }
      if (android && pointer.moved) {
        viewport.setPointerCapture(event.pointerId);
        viewport.scrollLeft += (wasMoved ? pointer.lastX : pointer.x) - event.clientX;
      }
      pointer.lastX = event.clientX;
    });
    viewport.addEventListener('pointerup', event => {
      if (pointer?.id !== event.pointerId) return;
      if (pointer.moved || pointer.held) suppressClickUntil = Date.now() + 350;
      clearPointer(pointer.moved);
    });
    for (const name of ['pointercancel', 'pointerleave']) {
      viewport.addEventListener(name, event => { if (pointer?.id === event.pointerId) clearPointer(); });
    }
    viewport.addEventListener('lostpointercapture', event => {
      if (event.target === viewport && pointer?.id === event.pointerId) clearPointer();
    });
    viewport.addEventListener('contextmenu', event => {
      if (event.pointerType === 'touch') event.preventDefault();
    });
    deck.addEventListener('keydown', event => {
      const action = event.target.closest('[data-action]');
      if (action && (event.key === 'ContextMenu' || event.key === 'F10' && event.shiftKey)) {
        event.preventDefault();
        openCustomize(action);
      }
    });
    if (!android) {
      let lastWheel = -Infinity;
      deck.addEventListener('wheel', event => {
        if (pointer?.sorting || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
        const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
        if (!delta) return;
        event.preventDefault();
        const now = performance.now();
        const newBurst = now - lastWheel >= 300;
        lastWheel = now;
        if (newBurst) turnPage(Math.sign(delta));
      }, {passive:false});
    }
  }
  function modalFrame(title, subtitle, content, note) {
    return `<div class="modal-shell"><header class="modal-header"><div><h2 id="modal-title">${title}</h2><p>${subtitle}</p></div><button class="icon-button" data-close aria-label="关闭弹窗">${icon('close')}</button></header><div class="modal-body">${content}</div><footer class="modal-footer"><small>${note}</small><div class="dialog-buttons"><button class="text-button" data-close>取消</button><button class="primary-button" data-save>保存</button></div></footer></div>`;
  }
  function rememberTrigger(trigger) {
    return trigger?.dataset.action ? `[data-testid="action-${trigger.dataset.action}"]` : '[data-testid="customize"]';
  }
  function openCustomize(trigger) {
    if (modal.open) return;
    sortSuppressUntil = 0;
    state.modal = {kind:'customize', draft:[...state.primary], order:[...state.order], trigger:rememberTrigger(trigger)};
    modal.classList.remove('deletion-modal');
    modal.innerHTML = modalFrame('排序', '勾选常用操作，长按拖动调整顺序。', `<div class="live-preview"><div class="live-preview-caption"><span>首屏预览</span><span class="selection-count"></span></div><div data-preview></div></div><div class="choice-grid">${state.order.map(id => {
      const a = actions.find(action => action.id === id);
      return `<div class="action-choice" data-choice="${a.id}" role="group" aria-label="${a.label}"><button class="sort-handle" data-handle="${a.id}" title="长按拖动排序；Alt + 方向键上下移动" aria-label="长按拖动排序：${a.label}"><svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5h.01M16 5h.01M8 12h.01M16 12h.01M8 19h.01M16 19h.01"/></svg></button>${icon(a.icon)}<span class="choice-copy"><strong>${a.label}</strong><small>${a.detail}</small></span><button class="choice-toggle" data-toggle="${a.id}" role="checkbox" aria-label="首屏显示${a.label}" ${a.id === 'library' ? 'disabled title="收藏始终保留，仍可长按拖动排序"' : ''}><span class="choice-check">${icon(a.id === 'library' ? 'lock' : 'check')}</span></button></div>`;
    }).join('')}</div><p class="choice-note" aria-live="polite">收藏始终保留，位置可移动 · 首屏最多 4 项</p>`, '应用于本机全部作品');
    updateDraft();
    modal.showModal();
  }
  function updateDraft() {
    const ids = state.modal.order.filter(id => state.modal.draft.includes(id));
    state.modal.draft = ids;
    modal.querySelector('[data-preview]').innerHTML = rowMarkup(ids, true);
    modal.querySelector('.selection-count').textContent = `${ids.length} / 4`;
    state.modal.order.forEach(id => {
      const row = modal.querySelector(`[data-choice="${id}"]`);
      modal.querySelector('.choice-grid').append(row);
      const selected = ids.includes(id);
      row.dataset.selected = String(selected);
      row.dataset.limit = String(ids.length === 4 && !selected);
      const toggle = row.querySelector('[data-toggle]');
      toggle.setAttribute('aria-checked', String(selected));
      toggle.setAttribute('aria-disabled', String(toggle.disabled || ids.length === 4 && !selected));
    });
  }
  function openRule(trigger) {
    if (modal.open) return;
    sortSuppressUntil = 0;
    state.modal = {kind:'rule', draft:state.rules[state.book] || 'inherit', book:state.book, trigger:rememberTrigger(trigger)};
    modal.classList.add('deletion-modal');
    const options = [
      ['inherit','settings','跟随全局','沿用全局设置，当前为关闭'],
      ['on','trash','开启','读完章节后，自动删除本作对应下载'],
      ['off','book','关闭','保留本作已下载的章节']
    ];
    modal.innerHTML = modalFrame('读完即删', `仅应用于《${books[state.book].title}》`, `<div class="rule-choices" role="radiogroup" aria-label="读完即删规则">${options.map(([value,glyph,label,detail]) => `<button class="rule-choice" data-rule="${value}" role="radio" aria-checked="${state.modal.draft === value}">${icon(glyph)}<span><strong>${label}</strong><small>${detail}</small></span><span class="rule-radio"></span></button>`).join('')}</div><div class="rule-info">${icon('info')}<span>保存后不会立即删除已下载内容。<br>此处为交互演示，不执行真实删除。</span></div>`, '作品专属设置');
    modal.showModal();
  }
  function moveSort() {
    if (!sortPointer?.started) return;
    const p = sortPointer;
    sortGhost.style.left = `${p.x - p.offsetX}px`;
    sortGhost.style.top = `${p.y - p.offsetY}px`;
    const target = document.elementFromPoint(p.x, p.y)?.closest('[data-choice]');
    if (!target || target.dataset.choice === p.id) return;
    const ids = state.modal.order;
    ids.splice(ids.indexOf(target.dataset.choice), 0, ids.splice(ids.indexOf(p.id), 1)[0]);
    updateDraft();
  }
  function sortAutoScroll() {
    if (!sortPointer?.started) return;
    const body = modal.querySelector('.modal-body');
    const rect = body.getBoundingClientRect();
    const y = sortPointer.y;
    if (y < rect.top + 28) body.scrollTop -= 8;
    else if (y > rect.bottom - 28) body.scrollTop += 8;
    moveSort();
    sortFrame = requestAnimationFrame(sortAutoScroll);
  }
  function finishSort(keep) {
    clearTimeout(sortTimer);
    cancelAnimationFrame(sortFrame);
    const p = sortPointer;
    sortPointer = null;
    if (!p) return;
    sortGhost?.remove();
    sortGhost = null;
    modal.querySelector('.is-dragging')?.classList.remove('is-dragging');
    if (modal.hasPointerCapture(p.pointerId)) modal.releasePointerCapture(p.pointerId);
    if (p.started) {
      sortSuppressUntil = Date.now() + 350;
      if (!keep) { state.modal.order = p.before; updateDraft(); }
      else modal.querySelector('.choice-note').textContent = `${actions.find(a => a.id === p.id).label}已移至第 ${state.modal.order.indexOf(p.id) + 1} 位，保存后生效`;
    } else if (p.scrolling) sortSuppressUntil = Date.now() + 350;
  }
  modal.addEventListener('pointerdown', event => {
    const row = event.target.closest('[data-choice]');
    if (!row || event.button !== 0 || state.modal?.kind !== 'customize' || event.target.closest('[data-toggle]')) return;
    const rect = row.getBoundingClientRect();
    sortPointer = {id:row.dataset.choice, pointerId:event.pointerId, x:event.clientX, y:event.clientY, startX:event.clientX, startY:event.clientY, offsetX:event.clientX-rect.left, offsetY:event.clientY-rect.top, scroll:modal.querySelector('.modal-body').scrollTop, before:[...state.modal.order], started:false};
    sortTimer = setTimeout(() => {
      if (!sortPointer) return;
      sortPointer.started = true;
      row.classList.add('is-dragging');
      sortGhost = row.cloneNode(true);
      sortGhost.removeAttribute('data-choice');
      sortGhost.classList.add('drag-ghost');
      sortGhost.classList.remove('is-dragging');
      sortGhost.setAttribute('aria-hidden', 'true');
      sortGhost.inert = true;
      sortGhost.style.width = `${rect.width}px`;
      modal.append(sortGhost);
      modal.setPointerCapture(event.pointerId);
      modal.querySelector('.choice-note').textContent = `正在移动${actions.find(a => a.id === sortPointer.id).label}`;
      moveSort();
      sortAutoScroll();
    }, 500);
  });
  modal.addEventListener('pointermove', event => {
    if (!sortPointer || event.pointerId !== sortPointer.pointerId) return;
    const p = sortPointer;
    p.x = event.clientX; p.y = event.clientY;
    if (p.started) { event.preventDefault(); moveSort(); }
    else if (Math.hypot(p.x-p.startX, p.y-p.startY) > 9) {
      clearTimeout(sortTimer);
      if (event.pointerType === 'touch') {
        p.scrolling = true;
        modal.setPointerCapture(event.pointerId);
        modal.querySelector('.modal-body').scrollTop = p.scroll + p.startY-p.y;
      }
    }
  });
  modal.addEventListener('pointerup', () => finishSort(true));
  modal.addEventListener('pointercancel', () => finishSort(false));
  modal.addEventListener('lostpointercapture', () => finishSort(false));
  window.addEventListener('blur', () => finishSort(false));
  function closeModal(save = false) {
    if (!state.modal) return;
    finishSort(false);
    const current = state.modal;
    if (save) {
      if (current.kind === 'customize') { state.primary = [...current.draft]; state.order = [...current.order]; }
      else state.rules[current.book] = current.draft;
      renderDeck(current.kind === 'customize');
    }
    modal.close();
    state.modal = null;
    const previous = document.querySelector(current.trigger);
    const target = save && current.kind === 'customize' ? document.querySelector('.action-deck button') : previous;
    target?.focus({preventScroll:true});
    if (save) toast(current.kind === 'customize' ? '首屏按钮已保存' : `读完即删：${ruleLabels[current.draft]}`);
  }
  modal.addEventListener('click', event => {
    if (event.target.closest('[data-close]')) return closeModal();
    if (event.target.closest('[data-save]')) return closeModal(true);
    if (Date.now() < sortSuppressUntil || event.target.closest('[data-handle]')) return;
    const choice = event.target.closest('[data-choice]');
    if (choice) {
      const ids = state.modal.draft;
      const id = choice.dataset.choice;
      if (id === 'library') return;
      if (ids.includes(id)) ids.splice(ids.indexOf(id), 1);
      else if (ids.length < 4) ids.push(id);
      else return toast('首屏最多 4 项，请先取消一项');
      updateDraft();
    }
    const rule = event.target.closest('[data-rule]');
    if (rule) {
      state.modal.draft = rule.dataset.rule;
      modal.querySelectorAll('[data-rule]').forEach(button => button.setAttribute('aria-checked', String(button === rule)));
    }
    if (event.target === modal) {
      const rect = modal.getBoundingClientRect();
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) closeModal();
    }
  });
  modal.addEventListener('cancel', event => { event.preventDefault(); closeModal(); });
  modal.addEventListener('keydown', event => {
    const handle = event.target.closest('[data-handle]');
    if (handle && event.altKey && ['ArrowUp', 'ArrowDown'].includes(event.key)) {
      event.preventDefault();
      const from = state.modal.order.indexOf(handle.dataset.handle);
      const to = Math.max(0, Math.min(state.modal.order.length - 1, from + (event.key === 'ArrowUp' ? -1 : 1)));
      state.modal.order.splice(to, 0, state.modal.order.splice(from, 1)[0]);
      updateDraft();
      handle.focus();
      return;
    }
    if (event.key !== 'Tab') return;
    // Keep keyboard navigation within this iframe's dialog, including its boundary.
    const targets = [...modal.querySelectorAll('button:not(:disabled):not([aria-disabled=true]):not([tabindex="-1"])')].filter(button => !button.closest('[inert]'));
    const current = targets.indexOf(document.activeElement);
    const next = (current + (event.shiftKey ? -1 : 1) + targets.length) % targets.length;
    event.preventDefault();
    targets[next]?.focus();
  });
  window.addEventListener('message', event => {
    if (event.source !== parent || event.data?.type !== 'prototype-settings') return;
    document.documentElement.dataset.theme = event.data.dark ? 'dark' : 'light';
    if (books[event.data.book] && event.data.book !== state.book) {
      if (modal.open) closeModal();
      state.book = event.data.book;
      renderApp();
    }
  });
  renderApp();
})();
