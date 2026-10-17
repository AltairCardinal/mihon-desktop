/**
 * Fullscreen Reader Interaction Logic for Mihon Desktop
 * 
 * Implements:
 * 1. Fullscreen toggle with active/pressed button state
 * 2. Top-right bubble notification "按F11以进入/退出全屏" on every fullscreen entry
 * 3. Mouse hover edge detection for top and bottom reader menu reveal and disappearance
 * 4. Keyboard shortcuts (F11, Escape, Arrow keys)
 */

class FullscreenReaderController {
  constructor(options = {}) {
    this.topMenuHeight = options.topMenuHeight || 56;
    this.topHideThreshold = options.topHideThreshold || 110; // > menuHeight (56px)
    this.bottomMenuHeight = options.bottomMenuHeight || 56;
    this.bottomHideThreshold = options.bottomHideThreshold || 110;
    this.edgeTriggerDistance = options.edgeTriggerDistance || 24; // 24px comfortable trigger zone
    this.bubbleDurationMs = options.bubbleDurationMs || 3200;

    this.state = {
      isFullscreen: false,
      fullscreenButtonActive: false,
      fullscreenButtonAriaPressed: 'false',
      fullscreenButtonIcon: 'fullscreen',
      fullscreenButtonTitle: '进入全屏 (F11)',
      
      // Bubble state
      bubbleVisible: false,
      bubbleText: '按F11以进入/退出全屏',
      bubblePosition: 'top-right',
      bubbleEntryCount: 0,

      // Menu reveal state
      topBarRevealed: true, // visible initially in windowed mode
      bottomBarRevealed: true, // visible initially in windowed mode
      showUI: true,

      // Reading progress
      currentPage: options.initialPage || 1,
      totalPages: options.totalPages || 36,
      chapterTitle: options.chapterTitle || '第 45 话 · 决战前夕',
      mangaTitle: options.mangaTitle || '葬送的芙莉莲',
      readingMode: options.readingMode || 'DUAL', // 'SINGLE', 'DUAL', 'WEBTOON'
      theme: options.theme || 'dark', // 'dark', 'light', 'amoled'
      showSettings: false,
    };

    this.bubbleTimeoutId = null;
    this.listeners = new Set();
  }

  subscribe(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  notify() {
    for (const listener of this.listeners) {
      try {
        listener(this.state);
      } catch (err) {
        console.error('Error in state listener:', err);
      }
    }
  }

  toggleFullscreen(forceState) {
    const nextFullscreen = typeof forceState === 'boolean' ? forceState : !this.state.isFullscreen;
    
    if (nextFullscreen === this.state.isFullscreen) {
      return;
    }

    this.state.isFullscreen = nextFullscreen;
    this.state.fullscreenButtonActive = nextFullscreen;
    this.state.fullscreenButtonAriaPressed = nextFullscreen ? 'true' : 'false';
    this.state.fullscreenButtonIcon = nextFullscreen ? 'fullscreen_exit' : 'fullscreen';
    this.state.fullscreenButtonTitle = nextFullscreen ? '退出全屏 (F11)' : '进入全屏 (F11)';

    if (nextFullscreen) {
      // 每次进入全屏：弹出气泡提示
      this.state.bubbleEntryCount += 1;
      this.state.bubbleVisible = true;
      this.state.bubbleText = '按F11以进入/退出全屏';
      
      // 全屏模式下，菜单栏默认自动收起隐藏
      this.state.topBarRevealed = false;
      this.state.bottomBarRevealed = false;

      // 气泡展示 3.2 秒后自动淡出
      if (this.bubbleTimeoutId) {
        clearTimeout(this.bubbleTimeoutId);
      }
      if (typeof setTimeout !== 'undefined') {
        this.bubbleTimeoutId = setTimeout(() => {
          this.dismissBubble();
        }, this.bubbleDurationMs);
      }
    } else {
      // 退出全屏：关闭气泡，恢复普通窗口工具栏
      this.dismissBubble();
      this.state.topBarRevealed = true;
      this.state.bottomBarRevealed = true;
      this.state.showUI = true;
    }

    this.notify();
  }

  dismissBubble() {
    if (this.bubbleTimeoutId) {
      clearTimeout(this.bubbleTimeoutId);
      this.bubbleTimeoutId = null;
    }
    if (this.state.bubbleVisible) {
      this.state.bubbleVisible = false;
      this.notify();
    }
  }

  handleMouseMove({ y, viewportHeight }) {
    if (!this.state.isFullscreen) {
      // 非全屏模式下不执行边缘感应自动隐藏
      return;
    }

    let changed = false;

    // 1. 顶部菜单感应
    if (y <= this.edgeTriggerDistance) {
      // 移到屏幕最上方：显示阅读器菜单的上半部分
      if (!this.state.topBarRevealed) {
        this.state.topBarRevealed = true;
        changed = true;
      }
    } else if (this.state.topBarRevealed && y > this.topHideThreshold) {
      // 下移一段距离（大于菜单高度）后消失
      this.state.topBarRevealed = false;
      changed = true;
    }

    // 2. 底部菜单感应
    const distFromBottom = viewportHeight - y;
    if (distFromBottom <= this.edgeTriggerDistance) {
      // 移到屏幕最下方：显示阅读器菜单的下半部分
      if (!this.state.bottomBarRevealed) {
        this.state.bottomBarRevealed = true;
        changed = true;
      }
    } else if (this.state.bottomBarRevealed && distFromBottom > this.bottomHideThreshold) {
      // 上移一段距离（大于菜单高度）后消失
      this.state.bottomBarRevealed = false;
      changed = true;
    }

    if (changed) {
      this.notify();
    }
  }

  revealTopBar() {
    if (!this.state.topBarRevealed) {
      this.state.topBarRevealed = true;
      this.notify();
    }
  }

  revealBottomBar() {
    if (!this.state.bottomBarRevealed) {
      this.state.bottomBarRevealed = true;
      this.notify();
    }
  }

  hideTopBar() {
    if (this.state.topBarRevealed) {
      this.state.topBarRevealed = false;
      this.notify();
    }
  }

  hideBottomBar() {
    if (this.state.bottomBarRevealed) {
      this.state.bottomBarRevealed = false;
      this.notify();
    }
  }

  handleKeyDown(key) {
    if (key === 'F11') {
      this.toggleFullscreen();
      return true;
    }

    if (key === 'Escape') {
      if (this.state.showSettings) {
        this.state.showSettings = false;
        this.notify();
        return true;
      }
      if (this.state.isFullscreen) {
        this.toggleFullscreen(false);
        return true;
      }
      return false;
    }

    if (key === 'ArrowRight' || key === 'd' || key === 'D' || key === 'PageDown' || key === ' ') {
      this.nextPage();
      return true;
    }

    if (key === 'ArrowLeft' || key === 'a' || key === 'A' || key === 'PageUp') {
      this.prevPage();
      return true;
    }

    return false;
  }

  goToPage(page) {
    const clamped = Math.max(1, Math.min(page, this.state.totalPages));
    if (clamped !== this.state.currentPage) {
      this.state.currentPage = clamped;
      this.notify();
    }
  }

  nextPage() {
    const step = this.state.readingMode === 'DUAL' ? 2 : 1;
    this.goToPage(this.state.currentPage + step);
  }

  prevPage() {
    const step = this.state.readingMode === 'DUAL' ? 2 : 1;
    this.goToPage(this.state.currentPage - step);
  }

  toggleUI() {
    if (this.state.isFullscreen) {
      // 全屏模式下点击正文：同时切换顶底栏显示/隐藏
      const next = !(this.state.topBarRevealed || this.state.bottomBarRevealed);
      this.state.topBarRevealed = next;
      this.state.bottomBarRevealed = next;
    } else {
      this.state.showUI = !this.state.showUI;
      this.state.topBarRevealed = this.state.showUI;
      this.state.bottomBarRevealed = this.state.showUI;
    }
    this.notify();
  }

  toggleSettings() {
    this.state.showSettings = !this.state.showSettings;
    this.notify();
  }

  setReadingMode(mode) {
    this.state.readingMode = mode;
    this.notify();
  }

  setTheme(theme) {
    this.state.theme = theme;
    this.notify();
  }
}

// CommonJS and browser compatibility export
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { FullscreenReaderController };
}
if (typeof window !== 'undefined') {
  window.FullscreenReaderController = FullscreenReaderController;
}
