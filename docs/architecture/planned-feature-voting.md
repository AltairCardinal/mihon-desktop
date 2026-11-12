# 规划新增功能投票：网站与服务

状态（2026-11-13）：投票 API 已部署，网站已配置真实 API，并通过隔离托管验收。静态页面通过 main 的 `Documentation site` 工作流发布；Pages 上线结果以该工作流及实际站点为准。此文件不声明 Android/Desktop 规划已实现。

## 公共入口

- 中文：`/mihon-desktop/zh/planned-features.html`，标题“规划新增功能投票”。
- 英文：`/mihon-desktop/planned-features.html`。
- 导航/侧栏从既有 VitePress 配置生成；两端语言共用稳定 feature IDs 和真实服务端票数。
- 本次只公布“Desktop 已读删除设置对齐”“双端延迟删除”两项，依据 [封存设计](../design/2026-11-13-read-download-deletion-frozen.md)。不将其他会话或未审核 roadmap 自动加入公开内容。

## 静态站点与动态服务

GitHub Pages 仅托管静态页面，不能独立实施服务端 IP 去重。VitePress 页面使用 `FeatureVote.vue` → `voting-client.mjs` → 独立 Cloudflare Worker → D1 SQL。

- `GET /features`：返回每项票数和当前公网 IP 的已投状态。
- `POST /votes/:featureId`：201 表示接受，409 表示这个 IP 已投过该项；不接受浏览器提交的 IP 或票数。
- 用 SQL 主键 `(feature_id, voter_hash)` 防止并发重复入票。IP 只来自直接 Cloudflare 边缘请求的 `CF-Connecting-IP`，规范化 IPv4/IPv6 后按 feature ID 计算 HMAC-SHA256。密钥保存在 Worker secret，原始 IP 不写投票表，不公开摘要。
- 只允许 Pages 的确切 Origin；HTTPS 请求、预检、无缓存和有限已公布 feature IDs。不把 CORS 当作抗机器人身份认证。
- 同一 NAT 公网 IP 共享机会，换 IP 可以再投；不宣称一人一票或完全防刷。
- Cloudflare 跨 zone Worker 子请求可能把该头替换为共享 Worker IP。必须让浏览器直接请求部署后的投票 Worker，不能未经核验将其他代理转发头当成可信访客 IP。
- 不能用 localStorage、Cookie 或 GitHub reaction 替代此 IP 限制。客户端只在真实成功/重复响应后展示已投；失败不能显示伪造的 0 票或成功。

## 部署与维护

1. 使用已有获授权 Cloudflare 账号的免费资源，不新建账号或购买服务。维护现有服务使用 `website/voting-service/wrangler.jsonc`；它指向本任务专用数据库，与既有反馈服务分开。
2. 仅在创建新的独立环境时复制 `wrangler.example.jsonc` 并填入该环境真实 D1 ID；不要把示例占位符当作资源。生产 Worker 与 D1 均名为 `mihon-feature-votes`，数据库 ID 位于生产配置中，不包含凭据。
3. 为 Worker 配置至少 32 字符随机 `VOTE_HMAC_SECRET`，只用 secret 存储，不写 Git、网站配置或构建输出；更换密钥会改变去重身份，不能无迁移直接轮换。
4. 应用 `migrations/0001_votes.sql` 后部署 `worker.mjs`。schema 不在请求处理中创建。
5. 修改身份或持久化边界时，在独立的临时 Worker/D1 中验证可信 IP、201/409、另一 feature 独立投票和真实 D1 唯一约束；验收后移除临时资源，不在公开票数中保留测试票。
6. `website/public/voting-config.json` 的 `apiBaseUrl` 已配置为 `https://mihon-feature-votes.mihon-altaircardinal.workers.dev`。若撤下服务，将该值清空，界面会显示“投票尚未开放”，不会发起投票或制造本地计数。
7. 按既有 Git 交付规则核对全套提交和固定 SHA，推送 main；核对 Documentation site 工作流成功及实际页面、服务才报告上线。

## 验证

```powershell
npm --prefix website run test:voting
npm --prefix website run docs:build
git diff --check
```

服务测试执行真实 Worker Request/Response 和迁移创建的 SQLite 表，D1 adapter 只桥接 prepare/bind/run/first，不复制去重实现。客户端测试执行生产 HTTP wrapper。测试最低运行环境为 Node.js 22.13.0；与 `package.json`、锁文件一致。

本次范围 19 个文件，涵盖冻结设计、双语说明、共享投票组件、唯一约束 API、部署配置及对应测试；它们属于同一项网站能力。没有修改 Android/Desktop 产品行为或已有未提交内容。主要风险为服务端身份来源与并发唯一性，已完成独立审查、本地真实 SQL/HTTP/Vue 接线测试，以及下述公网托管验收。

### 本地验证记录

- 已观察 Worker 未接线时 7 项真实行为失败、客户端未实现时 5 项失败，再实现通过。
- 已观察共享 Worker 占位 IP 错误接受为 201 的失败测试，修正为 503 且不写票。
- 挂载实际 `FeatureVote.vue`，调用其按钮事件，贯通生产 client → Worker → SQLite，确认投票后禁用及重新挂载保持已投；没有 live API 时只显示未开放。
- 初次本地 VitePress 客户端/SSR 构建及内部链接检查通过；浏览器验证见下节。
- 旧会话曾遇到 Git 代理连接、DNS 线程及本地 clone 子进程失败；当时没有推送。旧会话连接器读取 main 为 `f1eec27b39c456aa2738c308e7cc793886beec93`，这些历史观察不代表当前网络或部署状态。

### 继续核验（2026-11-13）

- 在隔离 main 快照 `5dfe224bf1a4fc85bacb9f6ac861e52c22542946` 上重新运行 15 项投票 focused tests 和 VitePress 构建，全部通过；没有执行 Gradle 或产品测试。
- 完成一轮独立只读安全/持久化审查，未发现阻塞缺陷。修正审查发现的 Node 最低版本声明偏低问题；没有改变网页或 API 的运行行为。
- 本轮应用内浏览器核对了中英文页面、键盘 Enter 触发语言切换、深浅主题与 390px 窄屏布局；空 API 配置时，两项均显示尚未开放，不显示票数或投票按钮。
- 本轮原生 Git 网络已恢复，`gh` 认证账号为 AltairCardinal，仓库管理员/推送权限已确认；实际远端 main 仍为上述父提交。上一节的 Git 失败为旧会话记录，不再作为当前阻塞。

### 托管验收与资源边界（2026-11-13）

- 通过官方 OAuth 完成最小所需授权，确认账号当前为免费套餐；CLI 凭据使用加密文件及 Windows Credential Manager，没有写入仓库。服务端随机 64 字符 HMAC secret 通过标准输入上传，仅保存为 Worker secret。
- Wrangler 4.161.0 本地 dry-run 通过；生产 `0001_votes.sql` 迁移通过，生产 Worker 版本为 `4fd377b1-3c66-4a70-ad60-645a1fbf7841`。兼容日期使用已发生的 UTC 日期 `2026-11-12`，关闭版本预览 URL。
- 使用同一生产源码的临时 Worker 与独立 D1，先验证 Pages Origin：8 个并发 POST 只有 1 个返回 201，其余 7 个为 409，票数为 1；第二项独立返回 201；新 GET 保留两项已投状态。伪造 `CF-Connecting-IP` 被 Cloudflare 边缘拒绝（403 / 1000）；伪造 `X-Forwarded-For`、`X-Real-IP` 仍返回 409。非法 Origin 返回 403，合法 CORS 预检和 no-store 通过。
- 再将临时环境 Origin 改为本地预览地址，在真实浏览器中执行指针点击第一项、Enter 投第二项；均显示 1 票并禁用按钮。刷新后及切换英文后，两项仍显示已投。此阶段只改临时 Origin 和未跟踪的预览配置，生产 Origin 始终为 Pages。
- 验收用 Worker `mihon-feature-votes-acceptance-20261113` 和 D1 已删除；Worker 不存在状态（404 / 10007）及剩余 D1 列表已核对。Wrangler 删除命令在删除 Worker 后查询未授权 KV 时失败，已用官方 Worker API 确认资源确实不存在，没有为清理扩大权限。
- 正式 HTTPS `GET /features` 返回两项 0 票、未投状态及正确 Pages CORS；未向正式数据库写入验收票。Pages 发布后仍需核对中英文页面加载该正式 API；不将此 API 证据代报为静态页面已经上线。

参考：[GitHub Pages 静态托管](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages)、[Cloudflare 访客 IP 头](https://developers.cloudflare.com/fundamentals/reference/http-headers/)、[D1 prepared statements](https://developers.cloudflare.com/d1/worker-api/prepared-statements/)。
