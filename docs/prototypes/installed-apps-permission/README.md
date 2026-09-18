# 插件列表权限 · Android 交互原型

打开 [index.html](index.html) 即可并列审阅 Windows 与 Android。支持直接打开本地 HTML，不需要账号、服务器或网络。

对应[需求文档](../../2026-09-17-installed-apps-permission-requirements.md)。本次仅完成需求细化和交互演示，**没有修改 Android 生产代码，也没有生成新 APK**。

## 审阅顺序

1. 默认 Android「浏览 → 图源」显示未开放权限提示，主文案为：需要允许“获取已安装应用列表”权限，否则无法读取已安装的插件列表。提示位于列表上方，本地和私有示例源保留。
2. 点击“获取权限”，进入标明“本地交互模拟”的系统设置模态页。选择“允许并返回 Mihon”，观察读取中状态，然后恢复绅士漫画与拷贝漫画两个样本。没有下载、安装或真实网络请求。
3. 点击应用外“模拟杀进程后启动”：保留模拟系统权限，清除瞬时界面状态后重新检查。已经允许时图源恢复；未允许时再次显示提示。
4. 切换“未开放权限”再进入模拟设置，选择“保持不允许并返回”或按 Escape，提示保留且不会再次自动弹窗。可以在其他页签、书架间切换，提示只在图源页出现。
5. 用应用外场景选择器检查已开放、设备无需额外权限、权限撤销、设置跳转失败、权限检查失败和扫描失败。重试按钮执行对应本地状态变化；不将读取失败误报为缺权限。
6. 切换双端主题，检查窄屏布局和键盘焦点；Windows 不展示 Android 权限卡，也不接收 Android 的权限操作。

## 复用与文件职责

- `index.html` / `preview.js`：应用外场景控件、双端 iframe 容器，各设备保持独立状态。
- `permission.js`：仅为交互审阅的 Android 权限状态、提示、授权模拟及重试；不作为 production 权限检测实现。
- `permission.css`：权限卡和模拟系统弹窗样式，沿用现有 Mihon 主题 token。
- `permission.test.cjs`：真实浏览器页面测试，覆盖授权/拒绝/重启/撤权/失败/窄屏/键盘/设备隔离及无远端请求。

直接复用 `../multi-device-sync/` 的 `styles.css`、`ui-view.js`、`app.js`、`sync-model.js`、`sync-interactions.js` 与 `extension-suggestions.js`，没有复制外壳。原 `app.js` 仅增加一个可选图源 renderer 接入点；原同步 DEMO 不加载本目录脚本，行为不变。

新功能与同步无直接关系，因此建立同级目录；原同步 DEMO 继续用于原有交互审阅。两端平台导航差异以原外壳为准，不虚构 Windows 的授权需求。

## 边界

- 系统授权页是示意，不声称还原所有厂商的页面、权限名或 Activity。真实授权方式与失败回退见需求文档。
- 权限只保存在当前浏览器页面内存。模拟冷启动保留此状态以表达系统权限生命周期；刷新整个 HTML 会恢复默认未授权场景，不代表系统权限被重置。
- 授权后的图源及私有图源均是演示样本。图源行只确认列表恢复，不证明真实图源浏览、登录、下载或网站可用。
- “撤销权限”场景模拟从系统返回后的检查；不实现真实前后台系统事件。不适用权限的设备正常显示列表，权限检查失败单独处理。
- 其他继承页面保留原 DEMO 示例，不把其插件建议/已安装样本当作本原型检测结果；本轮只审阅「浏览 → 图源」权限交互。
- 不使用真实权限、设备标识、令牌、网络、CDN、系统包扫描或安装。现有同步模型未修改，授权状态不参与设备间同步。

## 验证

先设置 `PLAYWRIGHT_CORE_PATH` 为本机 `playwright-core` 目录，使用本机 Chrome：

```powershell
node --test docs/prototypes/installed-apps-permission/permission.test.cjs
node --check docs/prototypes/installed-apps-permission/permission.js
node --check docs/prototypes/installed-apps-permission/preview.js
node --check docs/prototypes/multi-device-sync/app.js
git diff --check
```

共享外壳接入点回归使用 `multi-device-sync/parallel-preview.test.cjs` 与 `multi-device-sync/extension-suggestions.test.cjs`，不运行 Android/Desktop 构建。原 README 已记录部分同步测试基线失败；如再次遇到须记录真实结果，不能写为全部通过。

2026-09-17 验证结果：初始浏览器测试因图源页缺少权限卡而失败；实现后最终新增 5 项浏览器测试全部通过。原建议安装 3 项回归通过；原并列同步测试 1 项失败，断言预期包含“离线”，实际为“同步尚未完成已保存现有数据，可以稍后重试”，与原 README 已记载的失败一致，未扩改同步功能。脚本语法与 `git diff --check` 通过，已目视检查深色双端和浅色 320px 模拟授权弹窗；无远端请求、缺失资源或页面脚本错误。

本批次涉及需求文档、原需求约束补充、同级双端原型、共享外壳两行可选接入、测试和说明，超过 8 个文件但属于同一个可独立审阅的交互功能。主要风险是相对资源路径、Android/Windows 状态隔离、焦点和窄屏布局，已由上述浏览器测试覆盖；未改 Kotlin、APK、数据库或同步模型。
