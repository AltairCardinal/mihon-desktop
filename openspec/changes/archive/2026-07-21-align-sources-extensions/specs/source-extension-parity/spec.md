## ADDED Requirements

### Requirement: Source browsing uses shared state and errors
Android 与 Desktop SHALL 共享源列表、单源浏览、全局搜索、分页、空状态和网络错误语义。

#### Scenario: Source page succeeds or is empty
- **WHEN** 源返回正常列表或空列表
- **THEN** 两端产生相同领域结果，并分别显示内容或明确空状态

#### Scenario: Source response fails
- **WHEN** 源返回 403、429、500 或畸形响应
- **THEN** 两端映射为相同 AppError，并提供与错误类型匹配的重试/登录反馈

#### Scenario: One extension repository fails
- **WHEN** 多仓库刷新中某个仓库失败而其他仓库成功
- **THEN** 系统保留成功仓库的扩展目录，并逐仓库报告失败，不得把部分失败伪装成空列表

### Requirement: Extension lifecycle is transactional and shared
系统 SHALL 共享扩展发现、版本、安全、安装、更新、失败和回滚状态，平台层只执行实际文件/包操作。

#### Scenario: Extension update succeeds
- **WHEN** 新产物通过兼容性、签名/哈希和仓库信任校验
- **THEN** 系统原子替换旧版本并发布共享 Installed 状态

#### Scenario: Extension update fails
- **WHEN** 转换、校验或加载在提交前后失败
- **THEN** 系统恢复可用旧版本并显示逐项错误，不留下半安装目录

#### Scenario: Extension trust does not continue
- **WHEN** 仓库身份、声明摘要、下载摘要或已安装来源连续性校验失败
- **THEN** 系统拒绝静默替换，保留旧版本并显示可审查的信任错误

#### Scenario: Replacement cannot be reloaded
- **WHEN** 新产物已暂存或替换但平台 loader 无法加载代表性 source
- **THEN** 事务恢复旧产物与旧 metadata，重新加载旧版本后才发布失败状态

### Requirement: Desktop keeps evidence-based extension compatibility
Desktop MUST 保留 APK→JAR 和真实扩展所需的兼容接口，但 MUST NOT 添加无调用证据的 compat stub。

#### Scenario: Real extension requires a compatibility API
- **WHEN** 受支持 fixture 在隔离加载时调用兼容 API
- **THEN** 对应 stub/adapter 有调用证据与回归测试

#### Scenario: Compatibility API has no evidence
- **WHEN** 审计找不到真实扩展调用或保护测试
- **THEN** 该 API 不得扩张，并在无调用后删除

### Requirement: Desktop web login returns authenticated session state
Desktop SHALL 提供可取消、可超时的浏览器登录与 Cookie 回传，并只把 FlareSolverr 作为显式后备。

#### Scenario: Browser login succeeds
- **WHEN** 用户完成源登录或挑战
- **THEN** Cookie 安全回传到共享网络会话并重试原请求

#### Scenario: Browser login is cancelled or times out
- **WHEN** 用户取消或流程超时
- **THEN** UI 显示可恢复状态且不写入不完整凭据

### Requirement: Source and extension UI remains usable
Desktop SHALL 提供源列表、浏览、搜索、扩展详情/设置的入口，以及加载、空、错误和权限缺失反馈。

#### Scenario: Required extension or permission is missing
- **WHEN** 用户打开依赖缺失扩展、配置或登录的源
- **THEN** 页面说明缺失项并提供可执行的安装、设置或登录入口

### Requirement: Touched source and extension UI is localized
本 change 触达的 Desktop 源、扩展与挑战登录文案 SHALL 使用共享 i18n 资源，并通过资源完整性测试。

#### Scenario: A supported locale is missing a touched key
- **WHEN** 构建或测试扫描源、扩展与挑战登录所需资源
- **THEN** 缺失 key 会使测试失败，UI 不得退回硬编码业务文案

### Requirement: Android source and extension runtime is emulator verified
涉及 Android 源、扩展加载或安装的变更 MUST 由开发流程自行部署模拟器验证当前 APK 与代表性扩展。

#### Scenario: Shared source or extension wiring changes
- **WHEN** common 状态或 Android adapter 完成测试
- **THEN** 模拟器验证扩展发现/加载、源列表、单源浏览、搜索和失败反馈的真实用户路径
