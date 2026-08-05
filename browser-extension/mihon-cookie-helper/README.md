# Mihon 通行 Cookie 助手

这是供 Mihon Desktop“来源验证”流程使用的 Firefox/Chromium WebExtension。它只在用户点击按钮后读取当前标签页当前域名的 `cf_clearance`，把原始值复制到剪贴板，然后立即撤销该域名权限。

## 安装

### Chrome、Edge 及其他 Chromium 浏览器

1. 打开 `chrome://extensions`；Edge 使用 `edge://extensions`。
2. 启用“开发者模式”。
3. 选择“加载已解压的扩展程序”。
4. 选择本目录，即包含 `manifest.json` 的 `mihon-cookie-helper` 文件夹。

### Firefox

需要 Firefox 140 或更高版本。

1. 打开 `about:debugging#/runtime/this-firefox`。
2. 选择“临时载入附加组件”。
3. 选择本目录中的 `manifest.json`。

通过 `about:debugging` 载入的未签名扩展会在 Firefox 重启后移除。永久安装需要经过 Mozilla 签名；仓库内版本用于开发者模式和本地验收。

## 使用

1. 在 Mihon 的“来源验证”对话框中点击“打开浏览器”。
2. 在打开的来源页面完成 Cloudflare 验证。
3. 保持该页面为当前标签页，点击浏览器工具栏中的 “Mihon Cookie Helper”。
4. 点击“读取并复制”，并同意仅针对当前域名的临时权限。
5. 返回 Mihon，把剪贴板内容粘贴到 `cf_clearance Cookie 值` 输入框，再点击“手动导入”。

只粘贴值，例如 `abc123...`；不要粘贴 `cf_clearance=abc123...`，也不要粘贴完整 Cookie 请求头。

## 不安装扩展时手动获取

- Chrome/Edge：在来源页面按 `F12` → `Application`（应用程序）→ `Storage` → `Cookies` → 当前域名。
- Firefox：在来源页面按 `F12` → `Storage`（存储）→ `Cookies` → 当前域名。
- 找到名称为 `cf_clearance` 的条目，只复制 `Value`（值）列。

## 权限与数据边界

- `cookies`：读取用户明确授权的当前域名 Cookie。
- `optional_host_permissions`：安装时不持久索取所有站点；点击按钮时只请求当前协议与域名，并在读取后立即撤销。
- `activeTab`：只识别用户当前主动打开的标签页。
- `clipboardWrite`：把 `cf_clearance` 的原始值写入剪贴板，方便粘贴回 Mihon。
- 没有后台脚本、网络请求、远程代码、分析统计或本地存储；状态消息也不会包含 Cookie 值。

`cf_clearance` 是敏感凭据，应在复制后立即粘贴，不要发送给他人。部分 Cloudflare 配置会把凭据绑定到浏览器 User-Agent 或网络出口；此时单独复制 Cookie 仍可能被来源拒绝，应改用 Mihon 的 FlareSolverr 备用方式。

权限模型依据 [Chrome Cookies API](https://developer.chrome.com/docs/extensions/reference/api/cookies)、[Chrome activeTab](https://developer.chrome.com/docs/extensions/develop/concepts/activeTab) 和 [MDN Cookies API](https://developer.mozilla.org/docs/Mozilla/Add-ons/WebExtensions/API/cookies)。

## 测试与打包

```powershell
node --test test/*.test.mjs
./package.ps1
```

打包结果写入 `artifacts/mihon-cookie-helper-<version>.zip`。Chromium 需要先解压再加载；Firefox 可在临时调试页面选择解压后的 `manifest.json`。
