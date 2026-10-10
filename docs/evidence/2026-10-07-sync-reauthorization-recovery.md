# 重新授权后的同步空间恢复入口

## 现象与原因

用户在删除远端 mihon-sync 仓库后重新连接 GitHub，授权成功，界面依次进入查找、准备和通用失败，仅提供查看详情与重试。

本轮用真实设备授权客户端、MockWebServer、同步控制器及持久化数据库复现其中一条路径：保留 CONNECTED 阶段的首次同步记录，令原凭据缺失、远端仓库返回 404，然后完成设备授权。旧代码没有在接收新凭据后刷新 `canChangeSpace`，导致恢复入口和自动空间检查被旧状态阻止；仓库校验的 `SyncRequiredResourceUnavailable(REPOSITORY)` 还会降为 `RETRYABLE`，丢失准确的问题分类。红测在“授权后恢复入口仍不可用”断言失败。

这证明该链路存在代码缺陷；本轮没有读取用户平板日志，不将模拟中的凭据缺失认定为用户设备上唯一的触发原因。

## 实现与边界

- `acceptAuthorization` 完成身份验证和凭据持久化后，先刷新真实连接/凭据事实，再分派恢复或设置流程。刷新继续沿用原有格式、存储和连接有效性保护，不直接将恢复权限设为 true。
- 设置失败时保留明确的仓库不可用分类，使现有错误页提供更换空间和重新检查入口。只有仓库资源异常使用该分类，空间数据异常、网络错误和限流不冒充仓库缺失。
- GitHub 404 也可能意味着没有访问权，因此使用“空间不可用”的恢复语义。用户可进一步检查授权或选择创建空间，不自动创建或删除仓库，不丢弃旧连接、待同步记录和本地书库。
- 复用现有恢复页面、创建确认和手动创建仓库引导，不新增导航或平台专用同步实现。

## 验证

- 红：`sync-reauthorization-red2`，新回归用例按预期失败，恢复入口为 false。
- 绿：`sync-reauthorization-green`，相同用例通过；覆盖重新授权、仓库不可用分类、打开恢复、确认创建并到达 PREPARE_REPOSITORY，以及原连接、未完成同步记录和远端写入数不变。
- 补验 `sync-reauthorization-focused`：JVM/Android 共用恢复契约各 24 项、Compose 设置页 17 项、真实恢复页面集成 2 项，共 67 项通过。首次格式检查要求换行，修正后 `sync-reauthorization-format` 通过；生产实现未再变化。没有执行全量模块/仓库测试。
- 构建前核对可访问工作树的正式版本，另一任务已占用 code 53 / aex.35，本轮使用 code 54 / aex.36。

## 正式候选与手动验收

`python scripts/build-android.py candidate --offline` 和独立 `verify` 均通过。沿用 fork 包名及原证书，R8/资源压缩启用，v2/v3 签名验证通过。

- APK：`app/artifacts/android/0.19.4-aex.36-vc54-2d97762bd9-release/Mihon-Fork-0.19.4-aex.36-vc54-release-universal.apk`。
- SHA-256：`00cb47cdc79eaa99368c1353c39cbbe1f16c2948b85a97709c8691331cdebbd4`。
- productionInputsSha256：`ccff77adde8299eb2c2c5170b7151ce267c6bf8f1ec3f4e0be369f4900758d91`。候选绑定构建时 HEAD 与冻结生产 diff；之后只补充本文记录。

验收：覆盖安装本包 → 在原仓库已删除的环境下重新连接 GitHub 并完成设备授权 → 原空间不可用时可见“更换同步空间” → 选择“创建新空间”并确认 → 显示仓库创建引导。返回原书库，数据仍保留。

未安装或操作用户实体设备；自动化契约和签名验证不代表平板上的真实 GitHub 账号、网络、升级及运行验收。此轮不发布站点。
