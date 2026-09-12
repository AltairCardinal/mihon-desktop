const test = require('node:test');
const assert = require('node:assert/strict');

const view = require('./ui-view.js');

test('Windows 视图提供源码对应的六项底栏和窗口标题', () => {
  const spec = view.platformSpec('windows');
  assert.deepEqual(spec.nav.map((item) => item.label), ['书架', '更新', '历史', '浏览', '作者', '更多']);
  assert.equal(spec.windowTitle, 'Mihon Desktop');
  assert.deepEqual(spec.browseTabs, ['图源', '插件']);
  assert.match(view.renderNav(spec, 'updates'), /data-route="authors"/);
  assert.match(view.renderNav(spec, 'updates'), /MihonIcon/);
});

test('Android 视图提供五项底栏和浏览内作者页签', () => {
  const spec = view.platformSpec('android');
  assert.deepEqual(spec.nav.map((item) => item.label), ['书架', '更新', '历史', '浏览', '更多']);
  assert.equal(spec.bottomBarHeight, 80);
  assert.deepEqual(spec.browseTabs, ['图源', '作者', '插件', '迁移']);
  assert.doesNotMatch(view.renderNav(spec, 'browse'), /data-route="authors"/);
});

test('双端主题使用冻结的 Tachiyomi 默认颜色，并提供原生尺寸标记', () => {
  assert.equal(view.themeTokens.light.primary, '#0058CA');
  assert.equal(view.themeTokens.dark.primary, '#B0C6FF');
  assert.equal(view.platformSpec('windows').update.cover.width, 48);
  assert.equal(view.platformSpec('android').update.cover.width, 44);
  for (const name of ['library', 'updates', 'history', 'browse', 'authors', 'more', 'wifi', 'signal', 'battery']) {
    assert.match(view.icon(name), new RegExp(`data-icon="${name}"`));
  }
});

test('更新导航投影独立渲染内容数字和同步状态角标', () => {
  const html = view.renderNav(view.platformSpec('windows'), 'updates', {
    unreadCount: 120,
    sync: { kind: 'attention', label: '有2项同步操作待确认' },
  });
  assert.match(html, /data-testid="nav-updates-content-badge"[^>]*>99\+</);
  assert.match(html, /data-testid="nav-updates-sync-badge"/);
  assert.match(html, /data-icon="checklist"/);
  assert.match(html, /aria-label="更新；120 条未读内容；有2项同步操作待确认"/);

  const empty = view.renderNav(view.platformSpec('android'), 'updates', { unreadCount: 0, sync: null });
  assert.doesNotMatch(empty, /nav-updates-content-badge/);
  assert.doesNotMatch(empty, /nav-updates-sync-badge/);
});
