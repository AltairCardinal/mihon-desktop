# HTML 双端交互原型

本页是[根规则](../../AGENTS.md)按动作加载的项目约束。仅阅读本次动作适用章节；代码命令与普通路径以仓库根为基准，Markdown 链接按本页位置解析。

准备新增、修改、打开或验证原型前使用；本页保留多设备同步原型的规则，具体交互基线仍以该原型既有 README 为准。

## HTML 双端交互 DEMO

### 位置与用途

- DEMO 位于 `docs/prototypes/multi-device-sync/`，并列审阅入口是 `index.html`；`device.html` 仅用于单端隔离调试。完整交互基线、演示顺序和已知边界以同目录 `README.md` 为准。
- 该目录是 Windows Desktop 与 Android 共用的交互原型资产，可在设计其他双端功能时复用 Mihon 外壳、导航、主题、图标、面板、列表和演示工具栏。新增独立原型时优先在 `docs/prototypes/` 下建立语义清晰的同级目录；只有与多设备同步直接相关的交互才继续写入 `multi-device-sync/`。
- DEMO 只用于确认信息架构、界面状态和操作路径。它不连接真实 GitHub/Git 服务，不代表生产同步、持久化、系统后台任务或跨设备通信已实现，也不能代替 Android/Desktop 构建与运行验收。

### 文件职责

| 文件 | 职责 |
|---|---|
| `index.html`、`preview.js` | Windows 与 Android 并列容器、共享场景和应用外演示工具栏 |
| `device.html`、`app.js` | 单端入口、Mihon 页面与交互 wiring |
| `styles.css`、`ui-view.js` | 双端外观、主题、图标及可复用渲染组件 |
| `sync-interactions.js` | 同步面板及仅用于交互审阅的场景状态 |
| `sync-model.js` | 本地内存操作日志与设备间演示模型 |
| `*.test.cjs` | 模型、双端契约、浏览器布局及交互验证 |

### 开发规范

- 修改前先阅读该目录 `README.md`，确认当前已审核的交互、演示边界和对应源码依据。涉及长期交互规则、入口、状态或已知限制的变化，须同步更新 README。
- 默认同时维护并列的 Windows 与 Android 预览。共享语义、数据和操作结果保持一致；导航、尺寸或平台原生控件确有差异时限制在视图层，并在 README 说明原因。不得为了演示方便改变生产需求语义。
- 优先复用现有外壳、组件、图标和 `data-testid`，避免另建视觉体系。界面应贴合仓库当前 Mihon 实现；需要还原现有界面时先查对应 Compose 源码，不凭印象重画。
- 交互原型可使用满足审阅所需的最小模拟逻辑，但必须清楚区分 UI 场景与同步模型。纯展示场景放在 `sync-interactions.js`；操作因果、设备隔离或队列语义才进入 `sync-model.js`。不要为修饰界面顺带修复或扩张未获要求的模型能力。
- `index.html` 的两个 iframe 共享演示数据，但导航、焦点、滚动、选择、临时通知和计时器保持设备隔离。修改一端或重绘另一端时，不得抢焦点、泄漏未同步操作或重置对方的局部 UI 状态。
- 保持原型完全本地可运行：不引入 CDN、真实账号、真实令牌、远端请求或生产密钥。GitHub 授权只能使用明确标注的本地模拟页和演示数据。
- 临时反馈只在当前面板会话内显示，收起后不回放；危险或批量操作须提供与 Mihon 现有交互一致的确认和结果反馈。无编程背景用户能看到的文案应使用产品语言，不暴露 Git、提交、分支或队列实现细节。
- 与其他会话并行开发时，只编辑和提交本任务涉及的原型文件；已有未提交改动视为其他工作，禁止清理、覆盖或混入提交。

### 打开与验证

可直接打开 `docs/prototypes/multi-device-sync/index.html`。需要 HTTP 预览时，从仓库根目录运行：

```powershell
python -m http.server 50943 --directory docs/prototypes/multi-device-sync
```

验证按改动范围选择，不将 DEMO 变更升级为全量产品构建：

- 纯文案或说明：检查目标页面和 `git diff --check`。
- JavaScript 交互：运行受影响的 `*.test.cjs`，并对修改的脚本执行 `node --check`。
- 双端布局、导航或状态隔离：至少运行对应浏览器测试；共享预览改动包含 `parallel-preview.test.cjs`。
- `sync-model.js` 行为：先按红绿重构更新 `sync-model.test.cjs`，再运行受影响的浏览器交互测试。

浏览器测试使用本机 Chrome 与 `playwright-core`；需要时在当前会话设置 `PLAYWRIGHT_CORE_PATH`。常用完整 DEMO 验证为：

```powershell
node --test docs/prototypes/multi-device-sync/sync-model.test.cjs docs/prototypes/multi-device-sync/ui-view.test.cjs docs/prototypes/multi-device-sync/ui-browser.test.cjs docs/prototypes/multi-device-sync/library-sync.test.cjs docs/prototypes/multi-device-sync/batch-sync.test.cjs docs/prototypes/multi-device-sync/parallel-preview.test.cjs docs/prototypes/multi-device-sync/sync-interactions.test.cjs
```
