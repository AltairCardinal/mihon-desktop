# 规划新增功能投票：网站与服务

状态（2026-11-13）：已准备本地源码与定向测试，**尚未部署或开放投票**。Pages 推送与投票后端部署均须取得真实成功证据后才能改为上线。此文件不声明 Android/Desktop 规划已实现。

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

## 发布准备

1. 使用已有获授权 Cloudflare 账号创建 Worker 与 D1；无需为本任务新建账号或购买服务。本环境未发现可用的 Cloudflare 连接，尚未执行这些动作。
2. 复制 `website/voting-service/wrangler.example.jsonc` 为部署环境配置，填入真实 D1 ID；不要把示例占位符当作已部署资源。
3. 为 Worker 配置至少 32 字符随机 `VOTE_HMAC_SECRET`，只用 secret 存储，不写 Git、网站配置或构建输出；更换密钥会改变去重身份，不能无迁移直接轮换。
4. 应用 `migrations/0001_votes.sql` 后部署 `worker.mjs`。schema 不在请求处理中创建。
5. 从实际公网浏览器验证可信 IP、201/409、另一 feature 仍可投和真实 D1 唯一约束；不把本地 SQLite 测试当作 hosted D1 已验证。
6. 将已验证 HTTPS 服务地址写入 `website/public/voting-config.json` 的 `apiBaseUrl`。当前为空，界面明确显示“投票尚未开放”，不会发起投票或制造本地计数。
7. 按既有 Git 交付规则核对全套提交和固定 SHA，推送 main；核对 Documentation site 工作流成功及实际页面、服务才报告上线。

## 验证

```powershell
npm --prefix website run test:voting
npm --prefix website run docs:build
git diff --check
```

服务测试执行真实 Worker Request/Response 和迁移创建的 SQLite 表，D1 adapter 只桥接 prepare/bind/run/first，不复制去重实现。客户端测试执行生产 HTTP wrapper。仍需实际 Vue 点击/键盘、窄屏/深浅主题、语言切换和线上 Pages/Worker 接线证据。

本次范围约 16 个文件，涵盖冻结设计、双语说明、共享投票组件、唯一约束 API 及对应测试；它们属于同一项网站能力。没有修改 Android/Desktop 产品行为或已有未提交内容。主要风险为服务端身份来源与并发唯一性，已经用真实 SQL、HTTP handler 和挂载 Vue 按钮到 SQL 的集成覆盖；公网 Cloudflare 边缘身份仍须部署后另验。

### 本地验证记录

- 已观察 Worker 未接线时 7 项真实行为失败、客户端未实现时 5 项失败，再实现通过。
- 已观察共享 Worker 占位 IP 错误接受为 201 的失败测试，修正为 503 且不写票。
- 挂载实际 `FeatureVote.vue`，调用其按钮事件，贯通生产 client → Worker → SQLite，确认投票后禁用及重新挂载保持已投；没有 live API 时只显示未开放。
- 网站 VitePress 客户端/SSR 构建及内部链接检查通过；真实浏览器视觉、键盘、窄屏验证暂缺。
- 当前终端 Git 通过原代理连接 GitHub 失败，去除命令级代理后出现 DNS 线程初始化失败；本地 Git clone 子进程也受到 Windows 命名对象权限限制。未通过这些失败推断远端发布成功。
- GitHub 连接器只读核验账号为 AltairCardinal、main 为 `f1eec27b39c456aa2738c308e7cc793886beec93`；这不代替固定 SHA 交付和 Pages 部署结果。

参考：[GitHub Pages 静态托管](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages)、[Cloudflare 访客 IP 头](https://developers.cloudflare.com/fundamentals/reference/http-headers/)、[D1 prepared statements](https://developers.cloudflare.com/d1/worker-api/prepared-statements/)。
