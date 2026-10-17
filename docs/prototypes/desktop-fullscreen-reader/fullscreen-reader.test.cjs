const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

// Import the fullscreen reader controller/logic
const { FullscreenReaderController } = require('./fullscreen-reader-logic.js');

test('1. 全屏按钮交互与状态：初始非全屏，切换全屏后变为激活态', () => {
  const reader = new FullscreenReaderController({ totalPages: 42 });
  
  // 初始状态：窗口模式，非全屏，全屏按钮为非激活态
  assert.equal(reader.state.isFullscreen, false);
  assert.equal(reader.state.fullscreenButtonActive, false);
  assert.equal(reader.state.fullscreenButtonAriaPressed, 'false');
  assert.equal(reader.state.fullscreenButtonIcon, 'fullscreen');

  // 进入全屏
  reader.toggleFullscreen();
  assert.equal(reader.state.isFullscreen, true);
  assert.equal(reader.state.fullscreenButtonActive, true);
  assert.equal(reader.state.fullscreenButtonAriaPressed, 'true');
  assert.equal(reader.state.fullscreenButtonIcon, 'fullscreen_exit');

  // 再次点击：退出全屏
  reader.toggleFullscreen();
  assert.equal(reader.state.isFullscreen, false);
  assert.equal(reader.state.fullscreenButtonActive, false);
  assert.equal(reader.state.fullscreenButtonAriaPressed, 'false');
  assert.equal(reader.state.fullscreenButtonIcon, 'fullscreen');
});

test('2. 每次进入全屏时都在屏幕右上方用气泡提示【按F11以进入/退出全屏】', () => {
  const reader = new FullscreenReaderController();
  
  // 初始无气泡
  assert.equal(reader.state.bubbleVisible, false);

  // 第一次进入全屏：弹出气泡
  reader.toggleFullscreen(true);
  assert.equal(reader.state.isFullscreen, true);
  assert.equal(reader.state.bubbleVisible, true);
  assert.equal(reader.state.bubbleText, '按F11以进入/退出全屏');
  assert.equal(reader.state.bubblePosition, 'top-right');
  assert.equal(reader.state.bubbleEntryCount, 1);

  // 模拟气泡自动淡出/关闭
  reader.dismissBubble();
  assert.equal(reader.state.bubbleVisible, false);

  // 退出全屏
  reader.toggleFullscreen(false);
  assert.equal(reader.state.isFullscreen, false);
  assert.equal(reader.state.bubbleVisible, false);

  // 第二次进入全屏：必须再次弹出气泡（每次进入全屏都要提示）
  reader.toggleFullscreen(true);
  assert.equal(reader.state.isFullscreen, true);
  assert.equal(reader.state.bubbleVisible, true);
  assert.equal(reader.state.bubbleText, '按F11以进入/退出全屏');
  assert.equal(reader.state.bubbleEntryCount, 2);
});

test('3. 鼠标移到屏幕最上方显示顶部菜单，下移超过菜单高度后消失', () => {
  const viewportHeight = 800;
  const menuHeight = 56;
  const threshold = 110; // 大于菜单高度(56px)的消失距离
  const reader = new FullscreenReaderController({
    topMenuHeight: menuHeight,
    topHideThreshold: threshold,
  });

  // 进入全屏，初始状态下顶底菜单自动隐藏
  reader.toggleFullscreen(true);
  assert.equal(reader.state.topBarRevealed, false);

  // 鼠标在阅读正文区域 (y = 300px)：不显示顶部菜单
  reader.handleMouseMove({ y: 300, viewportHeight });
  assert.equal(reader.state.topBarRevealed, false);

  // 鼠标移到屏幕最上方 (y = 4px <= 8px 触发区域)：顶部菜单滑出显示
  reader.handleMouseMove({ y: 4, viewportHeight });
  assert.equal(reader.state.topBarRevealed, true);

  // 鼠标在顶部菜单内或靠近顶部 (y = 45px < 110px)：保持显示
  reader.handleMouseMove({ y: 45, viewportHeight });
  assert.equal(reader.state.topBarRevealed, true);

  // 鼠标在菜单边缘略下方 (y = 80px < 110px)：仍然保持显示（防误触）
  reader.handleMouseMove({ y: 80, viewportHeight });
  assert.equal(reader.state.topBarRevealed, true);

  // 鼠标下移一段距离（y = 120px > 110px，大于菜单高度 56px）：顶部菜单消失
  reader.handleMouseMove({ y: 120, viewportHeight });
  assert.equal(reader.state.topBarRevealed, false);
});

test('4. 鼠标移到屏幕最下方显示底部菜单，上移一段距离后消失', () => {
  const viewportHeight = 800;
  const bottomMenuHeight = 56;
  const threshold = 110; // 大于菜单高度的消失距离
  const reader = new FullscreenReaderController({
    bottomMenuHeight: bottomMenuHeight,
    bottomHideThreshold: threshold,
  });

  // 进入全屏，初始状态下底栏隐藏
  reader.toggleFullscreen(true);
  assert.equal(reader.state.bottomBarRevealed, false);

  // 鼠标移到屏幕最下方 (y = 796px >= 800 - 8)：底部菜单滑出显示
  reader.handleMouseMove({ y: 796, viewportHeight });
  assert.equal(reader.state.bottomBarRevealed, true);

  // 鼠标在底部菜单内 (y = 760px, 距底 40px < 110px)：保持显示
  reader.handleMouseMove({ y: 760, viewportHeight });
  assert.equal(reader.state.bottomBarRevealed, true);

  // 鼠标上移一段距离 (y = 650px, 距底 150px > 110px，大于底栏高度)：底部菜单消失
  reader.handleMouseMove({ y: 650, viewportHeight });
  assert.equal(reader.state.bottomBarRevealed, false);
});

test('5. 键盘快捷键响应：F11 进入/退出全屏，Esc 退出全屏', () => {
  const reader = new FullscreenReaderController();

  // 按 F11 进入全屏
  const handledF11Enter = reader.handleKeyDown('F11');
  assert.equal(handledF11Enter, true);
  assert.equal(reader.state.isFullscreen, true);
  assert.equal(reader.state.bubbleVisible, true);

  // 全屏状态下按 Esc 退出全屏
  const handledEsc = reader.handleKeyDown('Escape');
  assert.equal(handledEsc, true);
  assert.equal(reader.state.isFullscreen, false);

  // 再次按 F11 再次进入全屏
  reader.handleKeyDown('F11');
  assert.equal(reader.state.isFullscreen, true);

  // 再次按 F11 退出全屏
  const handledF11Exit = reader.handleKeyDown('F11');
  assert.equal(handledF11Exit, true);
  assert.equal(reader.state.isFullscreen, false);
});

test('6. 边缘热区直接唤起与显隐辅助方法', () => {
  const reader = new FullscreenReaderController();
  reader.toggleFullscreen(true);
  assert.equal(reader.state.topBarRevealed, false);
  assert.equal(reader.state.bottomBarRevealed, false);

  // 直接触发顶部唤起
  reader.revealTopBar();
  assert.equal(reader.state.topBarRevealed, true);

  // 直接触发底部唤起
  reader.revealBottomBar();
  assert.equal(reader.state.bottomBarRevealed, true);

  // 隐藏
  reader.hideTopBar();
  assert.equal(reader.state.topBarRevealed, false);
  reader.hideBottomBar();
  assert.equal(reader.state.bottomBarRevealed, false);
});

test('7. 边缘感应灵敏度：24px 舒适热区内均可触发顶部与底部滑出', () => {
  const viewportHeight = 800;
  const reader = new FullscreenReaderController();
  reader.toggleFullscreen(true);

  // 顶部在 y = 20px (<= 24px 灵敏区) 应成功触发
  reader.handleMouseMove({ y: 20, viewportHeight });
  assert.equal(reader.state.topBarRevealed, true);

  // 底部在 y = 780px (距底部 20px <= 24px) 应成功触发
  reader.handleMouseMove({ y: 780, viewportHeight });
  assert.equal(reader.state.bottomBarRevealed, true);
});

