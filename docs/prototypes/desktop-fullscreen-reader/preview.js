/**
 * Fullscreen Reader Prototype - Preview Controller
 * Connects the DOM views with FullscreenReaderController
 */

(function () {
  const controller = new window.FullscreenReaderController({
    totalPages: 36,
    initialPage: 12,
    chapterTitle: '第 45 话 · 决战前夕 (双页模式)',
    mangaTitle: '葬送的芙莉莲',
    topMenuHeight: 56,
    topHideThreshold: 110,
    bottomMenuHeight: 56,
    bottomHideThreshold: 110,
    edgeTriggerDistance: 24, // 24px 灵敏热区
  });

  // DOM Elements
  const appWindow = document.getElementById('app-window');
  const readerViewport = document.getElementById('reader-viewport');
  const readerTopBar = document.getElementById('reader-topbar');
  const readerBottomBar = document.getElementById('reader-bottombar');
  const edgeHotzoneTop = document.getElementById('edge-hotzone-top');
  const edgeHotzoneBottom = document.getElementById('edge-hotzone-bottom');
  const fullscreenBubble = document.getElementById('fullscreen-bubble');
  const bubbleCloseBtn = document.getElementById('bubble-close-btn');
  
  // TopBar buttons
  const btnFullscreen = document.getElementById('btn-fullscreen');
  const btnSettings = document.getElementById('btn-settings');
  const btnBack = document.getElementById('btn-back');
  const iconFullscreen = document.getElementById('icon-fullscreen');
  const iconFullscreenExit = document.getElementById('icon-fullscreen-exit');
  
  // BottomBar elements
  const pageCounter = document.getElementById('page-counter');
  const readerSlider = document.getElementById('reader-slider');
  const btnPrevChapter = document.getElementById('btn-prev-chapter');
  const btnNextChapter = document.getElementById('btn-next-chapter');
  const btnAdjustSpread = document.getElementById('btn-adjust-spread');

  // Diagnostics elements
  const diagMode = document.getElementById('diag-mode');
  const diagPos = document.getElementById('diag-pos');
  const diagTop = document.getElementById('diag-top');
  const diagBottom = document.getElementById('diag-bottom');
  const diagNote = document.getElementById('diag-note');

  // Review tools
  const toolToggleFullscreen = document.getElementById('tool-toggle-fullscreen');
  const toolBrowserFullscreen = document.getElementById('tool-browser-fullscreen');
  const toolHoverTop = document.getElementById('tool-hover-top');
  const toolHoverBottom = document.getElementById('tool-hover-bottom');
  const toolReset = document.getElementById('tool-reset');
  const toolThemeDark = document.getElementById('tool-theme-dark');
  const toolThemeLight = document.getElementById('tool-theme-light');
  const toolThemeAmoled = document.getElementById('tool-theme-amoled');

  // Manga spread pages & interaction
  const mangaCanvas = document.getElementById('manga-canvas');
  const leftPageNum = document.getElementById('left-page-num');
  const rightPageNum = document.getElementById('right-page-num');
  const tapZoneLeft = document.getElementById('tap-zone-left');
  const tapZoneRight = document.getElementById('tap-zone-right');

  // Settings sheet
  const settingsOverlay = document.getElementById('settings-overlay');
  const settingsCloseBtn = document.getElementById('settings-close-btn');

  // Sync state to DOM
  function render(state) {
    // 1. Fullscreen window state
    if (state.isFullscreen) {
      appWindow.classList.add('is-fullscreen');
      toolToggleFullscreen.classList.add('is-active');
      toolToggleFullscreen.innerHTML = `
        <svg viewBox="0 0 24 24"><path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-14v3h3v2h-5V5h2z"/></svg>
        退出全屏
      `;
      if (diagMode) {
        diagMode.textContent = '全屏沉浸模式';
        diagMode.className = 'diag-badge is-fullscreen';
      }
      if (diagNote) {
        diagNote.textContent = '⚡ 已进入全屏：顶底栏已收起。将鼠标移至屏幕最上方或最下方边缘即可自动滑出显示！';
      }
    } else {
      appWindow.classList.remove('is-fullscreen');
      toolToggleFullscreen.classList.remove('is-active');
      toolToggleFullscreen.innerHTML = `
        <svg viewBox="0 0 24 24"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>
        进入全屏
      `;
      if (diagMode) {
        diagMode.textContent = '窗口模式';
        diagMode.className = 'diag-badge';
      }
      if (diagNote) {
        diagNote.textContent = '💡 提示：点击右上角全屏按钮或按 F11 进入全屏后，顶底栏自动收起，鼠标移至顶底边缘即可自动滑出。';
      }
    }

    // 2. Fullscreen Button (in TopAppBar to the left of Settings)
    btnFullscreen.setAttribute('aria-pressed', state.fullscreenButtonAriaPressed);
    btnFullscreen.title = state.fullscreenButtonTitle;
    if (state.fullscreenButtonActive) {
      btnFullscreen.classList.add('is-active');
      iconFullscreen.style.display = 'none';
      iconFullscreenExit.style.display = 'block';
    } else {
      btnFullscreen.classList.remove('is-active');
      iconFullscreen.style.display = 'block';
      iconFullscreenExit.style.display = 'none';
    }

    // 3. Top-right Prompt Bubble
    if (state.bubbleVisible) {
      fullscreenBubble.classList.add('is-visible');
    } else {
      fullscreenBubble.classList.remove('is-visible');
    }

    // 4. Menu reveals (使用 .is-hidden 控制，不使用任何 inline style 覆盖)
    if (state.topBarRevealed) {
      readerTopBar.classList.remove('is-hidden');
      if (diagTop) {
        diagTop.textContent = '滑出显示';
        diagTop.className = 'diag-badge is-revealed';
      }
    } else {
      readerTopBar.classList.add('is-hidden');
      if (diagTop) {
        diagTop.textContent = '已收起隐藏';
        diagTop.className = 'diag-badge is-hidden';
      }
    }

    if (state.bottomBarRevealed) {
      readerBottomBar.classList.remove('is-hidden');
      if (diagBottom) {
        diagBottom.textContent = '滑出显示';
        diagBottom.className = 'diag-badge is-revealed';
      }
    } else {
      readerBottomBar.classList.add('is-hidden');
      if (diagBottom) {
        diagBottom.textContent = '已收起隐藏';
        diagBottom.className = 'diag-badge is-hidden';
      }
    }

    // 5. Page and Slider update
    pageCounter.textContent = `${state.currentPage} / ${state.totalPages}`;
    readerSlider.value = state.currentPage;
    readerSlider.max = state.totalPages;
    if (leftPageNum && rightPageNum) {
      const leftP = state.currentPage % 2 === 0 ? state.currentPage : state.currentPage + 1;
      const rightP = leftP - 1;
      leftPageNum.textContent = `P. ${Math.min(leftP, state.totalPages)}`;
      rightPageNum.textContent = `P. ${Math.max(1, rightP)}`;
    }

    // 6. Settings dialog
    if (state.showSettings) {
      settingsOverlay.classList.add('is-open');
    } else {
      settingsOverlay.classList.remove('is-open');
    }

    // 7. Theme
    document.documentElement.setAttribute('data-theme', state.theme);
  }

  // Subscribe render to state changes
  controller.subscribe(render);

  // Initial render
  render(controller.state);

  // Event Listeners: Fullscreen button
  btnFullscreen.addEventListener('click', (e) => {
    e.stopPropagation();
    controller.toggleFullscreen();
  });

  // Settings button
  btnSettings.addEventListener('click', (e) => {
    e.stopPropagation();
    controller.toggleSettings();
  });

  settingsCloseBtn.addEventListener('click', () => {
    controller.toggleSettings();
  });

  settingsOverlay.addEventListener('click', (e) => {
    if (e.target === settingsOverlay) {
      controller.toggleSettings();
    }
  });

  // Back button
  btnBack.addEventListener('click', (e) => {
    e.stopPropagation();
    if (controller.state.isFullscreen) {
      controller.toggleFullscreen(false);
    } else {
      alert('已触发返回上一页操作 (Navigator.pop)');
    }
  });

  // Bubble close
  bubbleCloseBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    controller.dismissBubble();
  });

  // 边缘热区直接悬浮触发展示
  if (edgeHotzoneTop) {
    edgeHotzoneTop.addEventListener('mouseenter', () => {
      controller.revealTopBar();
    });
  }
  if (edgeHotzoneBottom) {
    edgeHotzoneBottom.addEventListener('mouseenter', () => {
      controller.revealBottomBar();
    });
  }

  // 鼠标移动监听（全局监听保证贴边移动无死角）
  function handlePointerMove(e) {
    const rect = readerViewport.getBoundingClientRect();
    const relX = Math.round(e.clientX - rect.left);
    const relY = Math.round(e.clientY - rect.top);
    const inViewport = relX >= 0 && relX <= rect.width && relY >= 0 && relY <= rect.height;

    if (diagPos) {
      if (inViewport || controller.state.isFullscreen) {
        diagPos.textContent = `Y: ${relY}px (X: ${relX}px)`;
      } else {
        diagPos.textContent = `外部 (移入视口体验)`;
      }
    }

    if (inViewport || controller.state.isFullscreen) {
      controller.handleMouseMove({
        y: relY,
        viewportHeight: rect.height,
      });
    }
  }

  window.addEventListener('mousemove', handlePointerMove);

  // Canvas click toggles UI
  mangaCanvas.addEventListener('click', (e) => {
    // If clicking on controls, don't toggle UI
    if (e.target.closest('.reader-topbar') || e.target.closest('.reader-bottombar') || e.target.closest('.fullscreen-bubble')) {
      return;
    }
    controller.toggleUI();
  });

  // Page turns
  tapZoneLeft.addEventListener('click', (e) => {
    e.stopPropagation();
    controller.prevPage();
  });

  tapZoneRight.addEventListener('click', (e) => {
    e.stopPropagation();
    controller.nextPage();
  });

  btnPrevChapter.addEventListener('click', (e) => {
    e.stopPropagation();
    controller.goToPage(1);
  });

  btnNextChapter.addEventListener('click', (e) => {
    e.stopPropagation();
    controller.goToPage(controller.state.totalPages);
  });

  btnAdjustSpread.addEventListener('click', (e) => {
    e.stopPropagation();
    alert('调整跨页配对 (C)');
  });

  readerSlider.addEventListener('input', (e) => {
    controller.goToPage(parseInt(e.target.value, 10));
  });

  // Global Keyboard Navigation (F11, Escape, Arrows)
  window.addEventListener('keydown', (e) => {
    if (e.key === 'F11') {
      e.preventDefault(); // 阻止浏览器默认F11，以便本交互演示接管
      controller.handleKeyDown('F11');
      return;
    }
    if (e.key === 'Escape') {
      if (controller.state.isFullscreen || controller.state.showSettings) {
        e.preventDefault();
        controller.handleKeyDown('Escape');
      }
      return;
    }
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft' || e.key === ' ' || e.key === 'PageDown' || e.key === 'PageUp') {
      controller.handleKeyDown(e.key);
    }
  });

  // Review Tools
  toolToggleFullscreen.addEventListener('click', () => {
    controller.toggleFullscreen();
  });

  toolBrowserFullscreen.addEventListener('click', async () => {
    if (!document.fullscreenElement) {
      try {
        await document.documentElement.requestFullscreen();
        controller.toggleFullscreen(true);
      } catch (err) {
        console.warn('浏览器全屏被拦截，采用模拟全屏:', err);
        controller.toggleFullscreen(true);
      }
    } else {
      if (document.exitFullscreen) {
        await document.exitFullscreen();
      }
      controller.toggleFullscreen(false);
    }
  });

  toolHoverTop.addEventListener('click', () => {
    if (!controller.state.isFullscreen) {
      controller.toggleFullscreen(true);
    }
    controller.revealTopBar();
  });

  toolHoverBottom.addEventListener('click', () => {
    if (!controller.state.isFullscreen) {
      controller.toggleFullscreen(true);
    }
    controller.revealBottomBar();
  });

  toolReset.addEventListener('click', () => {
    controller.toggleFullscreen(false);
    controller.dismissBubble();
    controller.goToPage(12);
    controller.setTheme('dark');
  });

  toolThemeDark.addEventListener('click', () => controller.setTheme('dark'));
  toolThemeLight.addEventListener('click', () => controller.setTheme('light'));
  toolThemeAmoled.addEventListener('click', () => controller.setTheme('amoled'));

  // Listen to browser fullscreen change event to stay in sync if user uses native browser exit
  document.addEventListener('fullscreenchange', () => {
    if (!document.fullscreenElement && controller.state.isFullscreen) {
      controller.toggleFullscreen(false);
    }
  });
})();
