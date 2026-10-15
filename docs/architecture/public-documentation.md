# 公共文档与 GitHub Pages 维护

## 目的与边界

网站向读者说明本 fork 相对上游 Mihon 的新增能力、使用入口、平台差异和参与方式。它采用 VitePress 和 Git 管理，不从 HEAD、roadmap checkbox、测试报告或 parity manifest 自动生成产品承诺。

- `README.md` 是默认英文简介，`README.zh-CN.md` 是对应中文简介；两者只保留摘要和入口。
- `website/features.md` 和 `website/zh/features.md` 是同一份当前功能说明的两种语言，原位更新，不按日期或版本复制当前说明。
- `website/changelog.md` 和中文对应页记录已公开文档基线及后续已验收变更的差异摘要。每页的 Git history 可查看精确旧文本、提交和差异；不复制一整套版本目录。
- `website/getting-started.md` 与中文对应页说明当前可获得的构建，不把本地预览包、测试签名包或源码提交称为公开发行包。
- `website/contributing.md` 与中文对应页面向贡献者；根目录 `CONTRIBUTING.md` 和 `AGENTS.md` 分别提供协作入口与执行约束。
- 内部设计、roadmap、证据及 `parity-manifest.json` 继续履行原职责；公共文档不反向改变 capability 状态，也不建立新的机器状态权威。

本轮初始内容依据用户已经公开的 README 建立文档基线。它不是对所有历史功能重新验收，不补写不存在的用户批准、产品版本或发布日期。初始历史条目明确记录这一边界。尚未获得用户验收的新实现继续保留在内部设计和计划中。

## Agent 必须执行的验收后更新流程

1. **实施时定位页面。** 在功能计划或 PR 中记录受影响的现有功能小节、操作入口、平台和边界。尚未验收的说明留在该任务已有内部计划/交接中，不改写网站当前功能和 README 的可用性承诺，不额外建立待发布状态系统。
2. **等待用户验收。** 只有用户明确表示本次具体功能/候选“验收通过”“可以发布这项功能说明”等同义意图，才能推进公共功能说明。测试通过、Agent 自审/独立审查通过、构建成功、PR 合并、roadmap 全勾选均不能替代用户验收。对范围不清的“可以”只澄清涉及的功能，不替用户扩大验收范围。
3. **收到验收后主动完成文档。** Agent 不再另问是否需要更新文档：读取当前版本，原位替换相关功能小节，同步英文和中文，更新入口、结果、限制及受影响平台；删除被新行为取代的说法。验收只覆盖某个平台时不得宣传另一平台也完成。仅在摘要变化时同步 README；仅在操作步骤变化时同步开始使用页。
4. **追加一条差异记录。** 在双语 changelog 中记录实际验收日期、可核对的候选版本/源码提交、变化与限制，以及批准依据（公开 issue/PR 链接；若在私有聊天验收，写日期与最小范围摘要，不公开私有聊天链接或全文）。没有产品发布版本时使用日期和源码提交，不自行分配版本。初次公开说明与产品发布必须区分。
5. **核验与集中提交。** 按下文完成文档构建、链接和必要浏览器核验，检查双语语义相同。公共内容与该次历史记录放在一个实质文档提交/PR 中；不要为 advance/checkoff 再产生空洞状态提交。通常产品实现先交付验收，因此后续文档提交有独立内容，是必要的验收后发布动作。
6. **按授权发布并核实。** 遵守受保护分支，文档 PR 合入 `main` 后由 `Documentation site` 工作流构建和部署；没有远端写入授权时仍完成本地可审阅产物并记录待合入状态。核对工作流、实际部署 URL 和变更页后才能宣称上线。若正在进行的任务只有实现完成，结束时明确“待用户验收，公共功能说明尚未更新”。

纯翻译、拼写、失效链接或网站样式修正，可以在现有已公开语义内更新，不需要重新做产品验收；不得借“文档修正”宣传未验收功能。发现错误的可用性承诺，应及时撤回或更正并记录文档勘误，不能因等待验收而继续展示已知错误。

## 本地开发与验证

网站目录独立于产品构建，使用 Node.js 22 或更高版本。依赖由 `website/package-lock.json` 锁定。

```powershell
npm --prefix website ci --no-audit --no-fund
npm --prefix website run docs:build
npm --prefix website run docs:preview -- --host 127.0.0.1 --port 4173
git diff --check
```

访问 `http://127.0.0.1:4173/mihon-desktop/`。产物位于 `website/.vitepress/dist/`，不提交生成文件或 `node_modules`。首版及导航/布局变更检查桌面和窄屏、语言切换、搜索、浅色/深色、Git history；纯文字变更检查目标页与链接即可。VitePress 构建必须保留内部断链检查，不使用 `ignoreDeadLinks` 掩盖失败。外部链接按本次变更定向核验，不全站抓取。

网站检查不运行 Gradle，不分配应用版本，也不能作为 Android/Desktop 产品行为的验收证据。产品功能仍须完成仓库要求的 TDD、wiring 和按风险验证。

## GitHub Pages 配置与发布

站点路径为 `https://altaircardinal.github.io/mihon-desktop/`，VitePress `base` 固定为 `/mihon-desktop/`。英文在根路径，中文在 `/zh/`；保留 `.html` 页面路径以适应 Pages 静态文件托管。切换仓库名或域名时同步 `base`、站点 URL、导航、README 和历史链接，并复验直接打开内页及静态资源。

管理员首次在仓库 **Settings → Pages → Build and deployment → Source** 选择 **GitHub Actions**。工作流位于 `.github/workflows/pages.yml`：

- 文档 PR 执行网站构建以及[更新索引校验](application-updates-and-feedback.md)；PR、分支和 fork 均不能进入部署 job。
- `main` 中网站、本工作流或更新索引校验工具变化后自动部署；其他产品代码单独变化不会更新站点。
- `workflow_dispatch` 可重部署已有内容，但同样只允许本仓库 `main` 部署，不能把未合入分支作为正式站点。
- 构建 job 只有 `contents: read`；仅部署 job 获得 `pages: write` 与 `id-token: write`，使用 `github-pages` environment，不引入 PAT 或产品签名凭据。
- 网站内容验收由上述人工事实与 Agent 流程负责；路径触发器和成功的构建**不能判定用户是否验收了功能**。若将来需要服务端强制第二道人工审批，可以由维护者为 environment 配置 required reviewers，但不能把尚未配置的保护宣称已生效。

2026-10-13，远端 Pages 已配置为 GitHub Actions 部署；配置成功不代表网站已经上线，部署结果仍须单独核实。`main` 保留 `Build & Test App` 与 `Desktop JVM Tests` 两个 required checks，不通过直接写 commit status 或跳过分支保护发布。

### 文档与产品 CI 分流

两个既有 required job 均在 checkout 完整 Git 历史后运行 `scripts/ci-scope.py`；不使用 workflow 级路径过滤，以免纯文档 PR 缺少 required check 而无法合入。它们是真实工作流结果，不是人工伪造的成功状态。

- 纯文档范围：`website/**`、`docs/**`、根目录 Markdown、`.github/pull_request_template.md` 和 `.github/workflows/pages.yml`。
- CI 控制面范围仅额外包含这两个既有工作流，以及 `scripts/ci-scope.py` 和 `scripts/tests/ci-scope-test.py`。不宽泛排除整个 `.github/` 或 `scripts/`。控制面规则修改必须先通过真实 Git fixture 的定向回归和独立审查。
- 上述范围的提交在两个 required job 中实际运行路由回归；产品工具链、SDK 安装、Gradle、产品测试和应用候选构建不会启动。Summary 明确说明产品测试未运行；网站构建由独立 Documentation site 工作流承担。Android 的 PR Dependency Review 继续执行，也覆盖网站依赖变化。
- 任意其它文件、产品与文档混合修改均进入原有产品流程；手动 `workflow_dispatch` 强制执行原有产品流程，不受路径豁免。
- push 比较 `before..after`，PR 比较 `base...head`，禁止 rename detection 隐藏移入文档目录的产品删除；使用 NUL 分隔读取变更路径。零 SHA、未知事件保守进入产品流程；缺少/错误 SHA、无法读取提交或无法计算 diff 时 job 失败，不生成“无需产品验证”的结果。

修改分流规则时运行 `python scripts/tests/ci-scope-test.py`，检查真实 push/PR 历史、删除/重命名、混合变化、未知路径、手动触发和失败边界。合入后仍应核对两项 required job 的实际步骤与 Pages 部署结果；本地测试不能代替远端上线证据。

## 失败、回滚与维护

- 安装或构建失败：保留错误，修复锁文件/文档/配置后补验受影响范围，不关闭断链检查。
- Pages 权限或 environment 阻塞：报告 GitHub 的具体错误与需维护者处理的设置，不借其他仓库发布，不新增有更高权限的密钥。
- 部署失败：旧站仍是公开版本，不把本地构建成功称为已发布；修复后重跑失败工作流，并核对实际 URL。
- 内容回退：通过新的文档提交恢复上个正确说明，同时追加勘误/回退记录；不重写 Git 历史。保留旧内容的可追溯性，但默认入口始终指向唯一当前说明。
- 更换文档引擎或依赖：保留功能 URL 或重定向，检查双语、搜索、子路径、历史链接与构建产物；与产品运行时分开验证。

实现依据：[VitePress GitHub Pages 部署](https://vitepress.dev/guide/deploy#github-pages)、[GitHub Pages 自定义工作流](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)。这些文档说明托管机制，不替代本仓库用户验收规则。
