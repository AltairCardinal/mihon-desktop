# Desktop 扩展制品策略

## 目的

Mihon Desktop 优先消费扩展仓库发布的 JVM JAR，避免把 Android DEX 反向转换为 JVM
字节码。APK→JAR 只用于没有 JVM 制品的旧仓库或手动旧制品，不再作为 Keiyoushi 的
默认兼容路径。

Android consumer 的扩展安装仍使用 APK；本策略只约束 Desktop 平台 adapter，不改变
原版 Mihon 的 Android PackageManager、签名和安装流程。

## 选择顺序

1. Desktop 先读取仓库 `repo.json`。
2. 仓库声明 `index_v2` 时，读取 gzip-compressed protobuf 目录。
3. 每个 v2 条目优先选择 `resources.jarUrl`。
4. 只有条目没有 JAR 时，才以 `resources.apkUrl` 进入 APK→JAR Legacy 路径。
5. 未声明 `index_v2` 的旧仓库继续读取 `index.min.json` 和 APK，不影响既有安装。

Keiyoushi 当前同时发布 APK 与签名 JAR；Desktop 的全量兼容调查必须直接测试官方 JAR，
不得再以 Dex2Jar 调查结果代表当前 Keiyoushi 支持率。

## 信任边界

- 已保存的 repository fingerprint 是本地信任根。
- `repo.json` 的 fingerprint 和 v2 index 的 `signingKey` 必须分别与本地信任根一致；
  任一不一致都按 malformed/untrusted catalog 失败关闭。
- 原生 JAR 的每个 payload entry 必须由同一 repository certificate 签名；未签名、部分
  签名或混用签名的 JAR 必须拒绝。
- 签名验证必须先在下载的原始 JAR 上完成。若 Desktop 为平台 ABI 兼容而生成本地运行时
  派生 JAR，只允许执行文档化的窄范围字节码适配，并移除已经失效的原始签名元数据；
  派生 JAR 不得被当作仓库签名制品再次传播或建立新信任。
- APK Legacy 路径继续使用 APK signer 验证。声明 SHA-256 只能提供完整性，不能代替制品
  签名。
- 不允许为了兼容第三方仓库而关闭或弱化上述验证；第三方若只提供未签名 JAR，应先修复
  发布协议。

## 兼容范围与失败处理

- 共享 catalog 当前接受 extension-lib 1.4–1.6；更旧或更新的 ABI 仍显示为不兼容。
- JAR 下载、签名、package、source discovery 或 runtime reload 任一失败时，现有事务必须
  回滚到旧 JAR、sidecar 和 runtime snapshot。
- APK→JAR 保留为 Legacy capability，但不承诺任意历史 APK 都能无损转换；不得继续为
  Keiyoushi 当前 JAR 已解决的问题增加 DEX 类型猜测或扩展特例。
- APK→JAR 的失败必须保留“检查输入、准备工作区、DEX 转换、字节码重写、复制资源、发布
  输出、清理”阶段以及原始异常。仅文件被临时占用等可恢复的文件系统失败重试一次；确定性
  的格式、DEX 或字节码失败不得重试。转换失败与随后发生的 source discovery/runtime 加载
  失败必须分别报告，不能统一伪装成“仓库应提供 JAR”。
- 部分 Android 扩展在源构造阶段读取 JVM `http.agent` 并假定它非空。Desktop 统一网络层
  初始化时只在该属性缺失时提供 `Mihon Desktop/1.0`，显式 JVM 配置保持不变。此属性只用于
  Android ABI 与请求头兼容，不创建旁路 HTTP 客户端，也不改变代理选择；扩展请求仍必须通过
  production `NetworkHelper` 与按源分派的统一出口。
- 全量网络调查只在显式启用 integration、live-network 和 network-survey 标签时运行，并
  持久化逐制品报告以便附着恢复。

## 验证

- v2 catalog 集成测试必须从真实 protobuf wire shape 经过 production HTTP 和 decoder，
  证明 Desktop 选择 `jarUrl`，并能在 production mapping 损坏时失败。
- Legacy catalog 测试继续证明没有 `index_v2` 时仍选择 APK。
- artifact authenticity 测试分别覆盖错误 APK signer、未签名 JAR、正确签名 JAR 和摘要
  失败顺序。
- Legacy 安装事务测试必须至少用一个可再分发的真实 APK 覆盖 prepare、验证、转换、source
  discovery、commit 与 reload；无再分发许可证的制品只允许由显式集成测试从不可变地址获取，
  并在使用前核对大小和 SHA-256，不得提交进仓库。
- Keiyoushi Windows survey 对当前 v2 index 中每个 JAR 执行 repository signer 验证和
  production loader 加载，不执行 APK→JAR。
