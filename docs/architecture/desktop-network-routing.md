# Mihon Desktop 网络路由与插件代理设计

## 1. 目的

本文定义 Mihon Desktop 的最终网络路由语义，覆盖：

- Mihon 全局网络策略；
- 当前生效网络与实际连接路径的可见性；
- 已安装插件对全局策略的复写；
- 插件访问域名的展示与外部代理路由规则导出；
- 扩展自定义网络客户端带来的能力边界；
- 后续实现、测试和迁移顺序。

Mihon 只负责把自身或插件发起的请求交给直连、系统网络策略或用户指定的本地代理。
Mihon 不导入代理订阅，不管理 VMess、VLESS、Trojan 等节点，不替代 Mihomo、sing-box、
v2rayN 等代理客户端，也不尝试控制这些客户端内部的节点选择。

## 2. 核心决策

### 2.1 职责边界

常见代理客户端向其他应用提供稳定的 HTTP、SOCKS 或 Mixed 本地入口，并在代理客户端
内部完成节点选择、策略组切换、延迟测试、故障转移和域名分流。因此：

1. Mihon 不提供按插件切换外部代理节点或轮换本地端口的功能。
2. Mihon 保存的是网络入口策略，不把本地代理入口称为“节点”。
3. Mihon 不根据目标域名猜测请求属于哪个插件；插件身份在创建请求客户端时确定。
4. Mihon 不接入外部代理客户端的控制 API。即使请求进入本地代理，最终出口仍由外部
   代理客户端决定。
5. 插件仓库、应用更新及其他不属于特定插件的请求始终使用 Mihon 全局网络策略。

### 2.2 两级配置模型

网络策略分为两级：

```text
Mihon 全局网络策略
└─ 已安装插件网络策略
   ├─ 跟随 Mihon 全局设置
   ├─ 跟随系统
   ├─ 强制直连
   └─ 手动代理
```

插件策略只复写该插件通过 Mihon 托管网络客户端发出的请求。没有插件上下文的请求不会
读取插件设置。

## 3. 全局网络策略

### 3.1 三个互斥模式

“设置 → 网络”使用单选项，不再使用含义模糊的“使用网络代理”开关：

| 模式 | 语义 |
|---|---|
| 跟随系统 | 对每个目标 URI 使用操作系统当前网络代理策略；没有匹配代理时为 DIRECT |
| 强制直连 | 明确绕过 Mihon 手动代理和操作系统代理 |
| 手动代理 | 所有适用请求进入用户填写的固定 HTTP 或 SOCKS5 代理 |

首次安装默认选择“跟随系统”。默认值只代表使用系统策略，不能显示成“已使用代理”。
系统策略可能针对不同目标返回代理或 DIRECT，因此不存在一个对所有请求都正确的全局
“当前出口”。

### 3.2 跟随系统

Desktop 平台 adapter 必须读取真实操作系统代理策略。Windows 实现不得把 JVM 默认
行为等同于 Windows 系统代理，因为 JVM 的系统代理发现并非默认可靠启用。

系统模式需要区分以下结果：

- 系统为当前目标返回 HTTP/SOCKS 代理；
- 系统明确为当前目标返回 DIRECT；
- 系统没有配置代理，因此返回 DIRECT；
- 系统策略读取或 PAC 解析失败。

读取失败时不得显示“DIRECT”或“已使用系统代理”等成功状态。请求应交给仍可用的平台
selector；如果没有可用 selector，则以可操作错误失败，并提示用户改用“强制直连”或
“手动代理”。不得静默改变用户选择的策略。

第一阶段至少支持 Windows 静态系统代理及 bypass 规则。PAC 和自动发现如果尚未实现，
界面必须显示“不支持/受限”，不能假装完整跟随系统。

Windows 静态代理需要保留“规则解析”和“入口传输协议”两个概念。显式的
`http://`、`socks5://` 以及 `http=...;https=...;socks=...` 规则必须原样遵循。部分
本地代理软件会把 HTTP/SOCKS 混合入口以无协议的 `127.0.0.1:端口` 写入系统设置；
对这种仅限回环地址的歧义入口，adapter 可以执行一次有严格超时的 SOCKS5 capability
握手：确认支持时使用 SOCKS5，否则保持 HTTP。不得对远程代理地址发送探测流量。
探测结果按入口缓存；该入口连接失败时必须失效缓存，以便代理软件启动、重启或更换核心
后重新判定。

### 3.3 强制直连

强制直连必须显式使用直连路由，绕过：

- Mihon 手动代理；
- Windows 系统代理；
- JVM HTTP/HTTPS/SOCKS 代理属性；
- 默认 `ProxySelector` 返回的代理。

操作系统 TUN、VPN、WFP 或透明代理仍可能在进程外接管流量。Mihon 应说明“应用层
DIRECT”，不能声称物理网络一定没有经过 VPN 或透明代理。

### 3.4 手动代理

首版支持：

```text
http://host:port
socks5://host:port
```

输入要求：

- 必须包含协议、主机和端口；
- 不接受路径、查询参数或 fragment；
- 首版不接受 URL 内嵌用户名和密码；
- 保存前执行语法校验，但语法合法不代表代理可连接；
- 提供“测试连接”按钮，不以保存设置代替连接测试。

后续若支持认证，凭据引用应进入操作系统凭据存储；普通偏好设置中只保存非敏感标识，
不得明文保存密码。

### 3.5 保存状态、生效状态和实际路由

设置页必须区分三层信息：

| 层级 | 示例 | 含义 |
|---|---|---|
| 已保存策略 | 手动代理 | 用户最近保存的配置 |
| 当前生效策略 | SOCKS5 `127.0.0.1:7890` | 当前运行时客户端实际采用的配置 |
| 最近实际路由 | `repo.example` → SOCKS `127.0.0.1:7890` | 某次真实请求观测到的 OkHttp route |

如果保存后需要重启，界面应同时显示“重启后生效”和当前仍在运行的旧配置。优先目标是
安全地热更新网络客户端；在热更新完成前不得隐藏两者差异。

实际路由按目标主机记录，至少展示：

- 目标主机；
- 请求所属范围：Mihon 全局或具体插件；
- 应用层路由类型：DIRECT、HTTP 或 SOCKS；
- 代理地址（适用时）；
- 最近连接时间和结果；
- 信息是否来自真实连接观测。

实际路由通过 OkHttp `EventListener`/`Route` 等生产链路观测，不通过访问第三方“查询
公网 IP”服务自动推断。用户主动执行连接测试时，也只测试明确显示的目标。

### 3.6 连接测试

设置页提供可选择目标的“测试连接”：

- 插件仓库；
- GitHub；
- 用户输入的 HTTPS URL。

测试结果需要显示策略解析和连接阶段，而不是只显示“成功/失败”：

```text
目标：raw.githubusercontent.com
策略：跟随系统
解析：HTTP 127.0.0.1:7890
实际路由：HTTP 127.0.0.1:7890
结果：HTTP 200，428 ms
```

DNS、代理连接、TLS、HTTP 状态码和超时应保留可区分的错误类型。

## 4. 已安装插件网络策略

入口为“浏览 → 扩展 → 已安装 → 扩展详情 → 网络路由”。与 Android 一致，点击插件卡片
或右侧唯一的设置按钮均进入扩展详情（语言／图源列表），不直接进入某个图源的自定义设置。
设置按钮不依赖插件是否包含可配置图源；图源自定义设置仍由详情页内对应图源的齿轮进入。
网络路由、隐身模式等 Desktop 插件级能力继续保留在详情页，代理配置仍按插件包共享。

### 4.1 四个互斥选项

每个已安装插件的设置页提供：

| 选项 | 行为 |
|---|---|
| 跟随 Mihon 全局设置 | 使用第 3 节定义的当前全局策略 |
| 跟随系统 | 忽略 Mihon 全局选择，直接解析操作系统策略 |
| 强制直连 | 忽略 Mihon 全局和系统代理，使用应用层 DIRECT |
| 手动代理 | 忽略 Mihon 全局和系统代理，使用该插件填写的固定 HTTP/SOCKS5 入口 |

四个选项互斥，默认选择“跟随 Mihon 全局设置”。插件“手动代理”的格式、校验、认证边界
和连接测试与全局手动代理一致。

这里的手动代理是固定复写，不提供：

- 外部代理节点列表；
- 节点测速或自动选择；
- 节点订阅；
- 端口轮换；
- 按插件控制 Mihomo/sing-box/v2rayN 的策略组。

### 4.2 解析优先级

```text
插件请求
├─ 跟随 Mihon 全局设置 → 解析全局策略
├─ 跟随系统             → 解析系统策略
├─ 强制直连             → DIRECT
└─ 手动代理             → 插件固定代理
```

“跟随 Mihon 全局设置”解析的是当前生效的全局策略，而不是仅保存但尚未生效的值。

### 4.3 插件状态反馈

插件设置页必须展示保存值和解析后的当前状态：

```text
网络策略：跟随 Mihon 全局设置
当前生效：手动 SOCKS5 代理
代理入口：127.0.0.1:7890
最终出口：由外部代理软件决定，Mihon 无法获知
```

系统没有为最近目标返回代理时：

```text
网络策略：跟随系统
最近目标：api.example.org
当前生效：DIRECT
原因：系统策略未为该目标返回代理
```

Mihon 不得把本地入口、设置名称或外部代理进程名称显示为最终出口节点。

### 4.4 支持程度

插件页需要显示网络策略支持程度：

| 等级 | 含义 |
|---|---|
| 完整 | 插件使用 Mihon 注入的托管客户端，请求策略和域名观测均可用 |
| 部分 | 插件覆盖或派生了客户端，只有进入托管链路的请求受控 |
| 未知/不受管 | 插件自行创建网络栈、原始 Socket 或进程外通信，Mihon 无法保证复写 |

扩展在同一进程内运行时，无法从操作系统层绝对强制其所有流量遵循插件策略。若未来要求
安全意义上的强制隔离，需要把扩展移到独立进程并通过受控 IPC 提供网络能力；这不属于
本方案首版范围。

## 5. 插件访问域名

### 5.1 展示位置

“已安装插件 → 插件详情/设置 → 网络”展示该插件会访问或已经访问的域名，并提供复制到
剪贴板的入口。域名列表服务于用户在 Mihomo、sing-box、v2rayN 等外部代理客户端中编写
路由规则，不是插件权限白名单。

### 5.2 规范化格式

界面内部保存和默认展示规范化主机名：

```text
mangadex.org
api.mangadex.org
uploads.mangadex.org
```

规范化规则：

- 不包含 `http://` 或 `https://`；
- 不包含路径、查询参数、fragment、用户信息或端口；
- 转换为小写；
- 移除尾部 DNS 点；
- 国际化域名同时保留可显示 Unicode 名称和用于复制的 ASCII/Punycode 主机名；
- IP 字面量与域名分开标识；
- 去重但保留来源和最后观测时间。

不得自动删除 `www`。`www.example.org` 与 `example.org` 可能是不同主机，只有真实声明或
观测到的主机才进入精确域名列表。

### 5.3 数据来源

域名分为：

1. **插件声明**：从 source `baseUrl`、显式 API 地址等可可靠读取的元数据提取。
2. **运行时观测**：从该插件进入 Mihon 托管 HTTP 链路的真实请求、重定向和资源请求中
   记录主机名。

展示时默认合并去重，同时允许用户查看来源：

```text
api.example.org   插件声明
cdn.example.net   运行时观测
login.example.com 重定向观测
```

只允许记录：

- 主机名或 IP 字面量；
- 来源类别；
- 首次/最后观测时间；
- 可选的请求用途类别，如 API、图片或登录。

不得记录完整 URL、路径、查询参数、请求头、Cookie、请求正文或认证信息。

插件可能动态生成域名，网站也可能切换 CDN 或重定向目标。因此界面必须提示：

> 列表来自插件声明及 Mihon 托管网络链路中的历史观测。网站、CDN、重定向和插件更新
> 可能引入其他域名，列表不保证永久完整。

“清除观测记录”是可见操作；它只删除运行时观测，不删除插件声明域名。

### 5.4 复制到剪贴板

域名区域提供主按钮“复制域名”，默认复制逐行纯主机名：

```text
mangadex.org
api.mangadex.org
uploads.mangadex.org
```

复制菜单提供以下格式。

#### 纯域名

不带协议头、路径、端口或通配符，一行一个主机名。这是可供用户自行转换的中立格式。

#### Mihomo 精确规则

```yaml
- DOMAIN,mangadex.org,PROXY
- DOMAIN,api.mangadex.org,PROXY
- DOMAIN,uploads.mangadex.org,PROXY
```

`PROXY` 是用户可编辑且会记住的目标策略组名，不能假定所有配置都存在名为 `PROXY`
的策略组。

默认导出 `DOMAIN` 精确规则。另提供明确命名的“合并为 DOMAIN-SUFFIX”操作：

```yaml
- DOMAIN-SUFFIX,mangadex.org,PROXY
```

后缀合并会扩大匹配范围，必须在复制前说明它同时匹配根域及所有子域，不能静默把多个
精确域名折叠成后缀规则。

#### sing-box 规则片段

```json
{
  "domain": [
    "mangadex.org",
    "api.mangadex.org",
    "uploads.mangadex.org"
  ],
  "action": "route",
  "outbound": "proxy"
}
```

`outbound` 由用户输入或选择，并记住上次使用值。

#### v2rayN/Xray 域名项

```text
domain:mangadex.org
domain:api.mangadex.org
domain:uploads.mangadex.org
```

界面需要说明该内容是域名匹配项，不是完整 v2rayN 路由配置；最终 outbound 仍由用户在
v2rayN 路由规则中选择。

复制完成后显示包含格式和条目数的反馈，例如：

> 已复制 12 条 Mihomo DOMAIN 规则。

空列表时禁用复制并解释尚无可导出的声明或观测域名。

## 6. 目标 UI 信息架构

```text
设置
└─ 网络
   ├─ 当前网络状态
   │  ├─ 已保存策略
   │  ├─ 当前生效策略
   │  └─ 最近实际路由
   ├─ 全局网络策略
   │  ├─ 跟随系统
   │  ├─ 强制直连
   │  └─ 手动代理
   └─ 测试连接

已安装插件
└─ 插件详情/设置
   └─ 网络
      ├─ 网络策略
      │  ├─ 跟随 Mihon 全局设置
      │  ├─ 跟随系统
      │  ├─ 强制直连
      │  └─ 手动代理
      ├─ 当前生效状态
      ├─ 策略支持程度
      ├─ 测试连接
      └─ 访问域名
         ├─ 插件声明
         ├─ 运行时观测
         ├─ 复制域名/路由规则
         └─ 清除观测记录
```

## 7. 建议架构

### 7.1 策略模型

概念模型如下，实际命名应遵循项目 Kotlin 约定：

```kotlin
enum class GlobalNetworkMode {
    SYSTEM,
    DIRECT,
    MANUAL,
}

enum class PluginNetworkMode {
    INHERIT_GLOBAL,
    SYSTEM,
    DIRECT,
    MANUAL,
}
```

手动代理配置应使用共享值对象和同一解析器，避免全局与插件产生不同校验语义。

### 7.2 策略解析

`NetworkPolicyResolver` 接收：

- 可选插件身份；
- 目标 URI；
- 当前生效全局设置；
- 可选插件复写；
- Desktop 系统代理 adapter。

输出可审计的解析结果：

```text
ResolvedNetworkPolicy
├─ requestedMode
├─ effectiveMode
├─ route: DIRECT / HTTP / SOCKS
├─ proxyAddress
├─ source: GLOBAL / PLUGIN / SYSTEM
├─ limitation/error
└─ requiresRestart
```

策略解析与客户端创建分离，使 UI 状态、连接测试和真实请求使用同一 production 逻辑。

### 7.3 客户端生命周期

继续复用共享 OkHttp 的 Dispatcher、缓存、Cookie、DNS 和基础拦截器，通过 `newBuilder()`
派生不同策略客户端，不要为每次请求创建全新 OkHttpClient。每个插件使用独立连接池；这样
插件策略变化时可以只清除该插件的旧连接，避免手动代理、系统代理和 DIRECT 之间复用已经
建立的错误 route，同时不干扰仓库、更新器或其他插件。

至少维护：

- 全局当前策略客户端；
- 系统策略客户端；
- 强制直连客户端；
- 按已安装插件身份缓存的解析客户端。

插件客户端的 `ProxySelector` 必须在每次新请求时读取当前插件策略。配置变化时清除对应
连接池，允许在途请求完成，新请求使用新配置；不能要求重新加载扩展，也不能因为扩展在
构造期缓存了派生客户端而继续使用旧 route。

### 7.4 扩展注入

每个扩展 classloader 应获得带扩展身份的 `ExtensionNetworkContext`，其中的
`NetworkHelper`/OkHttpClient 由插件策略派生。不能只在全局 Injekt 注册一个无法识别
插件身份的客户端。

Desktop 当前通过 classloader 到插件包名的弱映射保存身份。扩展调用
`NetworkHelper.client` 时，getter 从调用栈中的扩展 classloader 解析包名，因此以下两类
常见写法都必须进入同一插件客户端：

```kotlin
override val client = network.client
override val client = network.client.newBuilder().addInterceptor(...).build()
```

第二种写法是 ManHuaGui 等扩展采用的构造期派生模式。派生客户端会继承动态
`ProxySelector`、策略刷新、域名观察与 route 观察链，插件以后切换四种网络策略时无需重建
source 实例。

由 Mihon 主进程代替扩展发出的后续请求必须显式携带 `sourceId` 并使用该源客户端，当前包括
漫画封面、章节下载、阅读器图片回退和阅读器预加载。Compose/Coil 只允许使用启动时安装的
production `ImageLoader`：普通远程图片使用全局托管客户端，漫画封面根据 `sourceId` 选择
插件客户端并合并源请求头。主进程不得在这些路径使用 Coil 默认客户端或直接读取全局
`client` 代替插件策略。

扩展允许覆盖 `client` 或打包自己的网络帮助类，因此 loader 必须检测并报告支持程度。
检测结果只用于诚实说明能力，不能把“检测到 OkHttp 类”当作全部流量已被强制接管。

### 7.5 域名观察

域名观察器挂接在真实托管 HTTP 请求链路，并接收扩展身份。它在请求和重定向发生时只
提取规范化主机名，再交给有界、去重的持久化存储。

需要限制：

- 每插件最大域名数量；
- 观测记录保留周期；
- 插件卸载后的清理策略；
- 插件 ID/签名变化后的身份迁移；
- localhost 和私网地址的显著标识。

### 7.6 统一错误契约

所有托管 source 调用把异常先转换为共享 `AppError`，再由界面统一生成用户文案，不允许
各页面直接显示异常类名或重新包装为“未知错误”。最低分类为：

- 超时、DNS 解析失败、TLS 失败、连接被拒绝、连接重置和其他网络失败；
- HTTP 401/403 鉴权失败、429 限流、5xx 服务端失败；
- 扩展返回数据无法解析；
- 取消操作（不显示为失败）。

搜索/热门/最新、详情与章节调用、阅读器页面、下载和书架更新应保留同一个结构化错误，
由共享呈现器按当前语言显示可操作原因。底层 cause 只用于诊断，不作为主要用户文案。

#### 7.6.1 扩展链接错误的下层分类与 HTTP 传输边界

共享 `inspectSourceFailure()` 按异常身份（`===`）检查最多 64 个 cause 节点，先排除
`VirtualMachineError`／`ThreadDeath`，再识别取消与链接错误。闭环在所有可达身份已读后
停止；超深链不推断深处安全，保留原错误传播。初始化、反射、Rx 或 IO 包装不能隐藏
致命／取消原因，稳定 `AppErrorException` 也须先经过同一检查。

`UncaughtExceptionInterceptor` 只保护其后的 `chain.proceed`。非致命、非取消链接错误
转换为保留原 cause 的 `SourceCompatibilityException : IOException`，使真正的 OkHttp
enqueue worker 只通知一次失败而不继续抛出链接错误；不伪造 HTTP 响应、源身份或网络成功。
普通 IO 沿用原实例，取消沿用既有 IO 传输，普通非链接 Error 与未知深链不被吞掉。
默认客户端、别名、按源客户及其 `newBuilder()` 派生客户保留保护链；扩展自行删除保护链
或另建完全独立客户端不在此保证内。

共享 `toSourceAppError()` 把可判定的链接错误归为 `ExtensionIncompatible`，不降为
Network／MalformedData／NoResults。源分页恢复动作是 None，不自动重试或跳转登录／
WebView；保留已有显式刷新入口。`StoredAppError` 仅增加类型字符串识别，不增加字段或
数据库 schema，未知旧／新 payload 仍按 Unknown 读取。用户文案只说可能需要更新应用或
扩展，不展示原始 ABI／私有请求详情。底层网络异常不猜 sourceId、包名或用户操作阶段，
归因由持有已安装记录的上层另行完成。

当前这里只完成下层分类和托管网络链隔离；加载、getter、过滤器、网络完成后的源解析、
阅读／下载业务边界及全局搜索 UI 汇合仍须各自验证，不能以 HTTP worker 通过替代。
`CatalogueCreatorDiscoverySourceAdapter.toCreatorSourceFailure()` 的通用 else 仍会把新错误
投影成 CreatorSourceFailure.Network，是后续 EC-06/07 明确待处理项，本阶段不扩 Creator 模型。
Android R8 后原 APK 和厂商设备结论仍待 EC-08。累计证据见
[扩展协程兼容性与故障隔离](../evidence/extension-coroutines-compatibility-2026-11-05.md)。

#### 7.6.2 第三方调用 guard 与 Android 加载适配

共享 `guardSourceOperation(phase) { thirdPartyCall() }` 只守卫插件实际执行部分。
`SourceOperationPhase` 是固定枚举，不承载 query、URL、凭据或推测身份；已有精确阶段
经反射／IO 包装后再次经过较宽边界时仍保留，外层异常继续作为 cause，旧单参 JVM
`SourceCompatibilityException(Throwable)` 构造保留。数据库、章节完整性 require、缓存、
存储和宿主 UI 不能整段塞入 guard。普通异常和非链接 Error 不变成兼容故障。

广域既有 catch 若需保留控制语义，使用 `rethrowSourceControlFailure()`：先传播具体
fatal／取消及未知深尾；无专用源传输标记时，连被 IOException 包裹的宿主 Error 也须传播。
已有 `SourceCompatibilityException` 标记且控制检查通过，则保持该判断；例如插件初始化
Linkage 的 cause 中普通 AssertionError 不得被误当成宿主故障重新抛出。此 helper 本身
不声称 catch 所包的全部工作来自插件，新增消费仍须对实际第三方 lambda 单独守卫。

Android `ExtensionLoader` 在真实构造、factory 和加载本来消费的 id／name／lang getter
上使用该边界。工厂任一 provider 失败则整包不发布，不能留下半个成功 source 列表；
不为“验证”而提前触发 client／baseUrl 等 lazy getter。JVM 测试的 internal classloader
factory 仅替换 OS 装载边界，默认 production ChildFirstPathClassLoader、信任及签名检查不变。
真实 APK 的 DEX／R8 装载仍需独立原生证据。

`LoadResult.Failure` 使用 PackageInfo 的可靠包／版本快照记录 phase 和 typed cause。
Manager 保留此失败及安装存在事实（inventory 的既有 LOAD_FAILED），不伪造 Installed 或
把坏包当成未安装。管理页空列表也显示失败横幅；显式 retry 经 ScreenModel 回到 Manager。
同／旧版 catalog 候选不显示成新安装，较新版候选仍用原安装管道，动作标为“更新”，
保留修复机会与原错误／进度反馈，不自动安装或自动重试。

Receiver 广播装载、手动 retry、信任后的装载各有新 identity owner；Manager 校验 owner
后才能发布结果，不能只按 versionCode 判断同版本替换。busy 状态也归属于 retry owner：
旧任务晚完成不得清掉新任务的 busy，更不能把新版健康 runtime 改成旧失败。广播自己的
pendingLoads 继续处理卸载／替换排序，owner 再覆盖与手动操作交错的代际；既有 installer
事务拥有的 reload 保留原回滚路径。

信任操作在开始保存时就持有新 owner，避免旧广播在同步保存期间返回 Untrusted 后取消
用户最新信任意图；这不允许提前执行插件，只有保存成功且该 owner 仍有效才调用 loader。
保存失败保留原 untrusted 记录；保存期间真正更新／卸载包所产生的新 owner 仍可使旧信任
失效，保存成功也不能把过期包结果写回。

Android 管理投影在真实 id／name／lang／baseUrl getter 处分别守卫，然后将宿主内存
descriptor 附在本轮 Installed copy；不改变 Source 对象、类型、id 或公开 ABI。投影错误
回报必须携带 Manager 原 Installed 引用，确认仍是当前安装实例后才移入失败记录；
反馈不是一次新的加载意图，不能推进 owner 从而取消较新的替换。移除坏 runtime 后
保留失败与安装事实，避免 flow 因重复读取同一个坏 getter 而无限重入。直接 adapter
调用没有 prepared descriptor 时也须经过相同 getter guard。

`prepareSourceFilters` 只执行真实 getFilterList，Content 保留原引用，Failure 保留原
错误分类；普通 IO 与控制异常规则不变。构造／remember 消费者直接展示 Failure，并
阻止依赖该筛选器的查询，不能调用 OrThrow 再把错误抛回 UI。空 FilterList 只可作为
伴随明确失败的内部占位。用户显式重试才重新准备；筛选器编辑的新树也在真实点击
边界获取，失败关闭编辑并反馈，不能在重组期间不断触发第三方 getter。

Desktop 装载的身份来自本次加载开始时读取的 sidecar 快照；成功 Source 和失败诊断
共用该不可变快照，之后不能因 null 或旧版本字段缺失再读取新 sidecar 补旧结果身份。
管理 projection 使用一次 runtime snapshot 选择候选与投影失败；getter 故障在同一
projection 返回 typed failure，不能在纯投影内同步触发 reload 或反写 Manager。
未知 metadata 显式显示 unknown，artifact 文件名只表示文件。失败包的同／旧版候选
不作新安装，较新版保留既有显式更新管道。该 Desktop 批的逐门验收状态见累计证据，
尚未完成的 gate 不由此维护约束代替。

管理详情的源行、排序、启用开关与浏览／设置参数继续使用同一 prepared descriptor，
不能为展示再调用 Source 的 id／name／lang／baseUrl；Source 引用仍保留原实例与类型。
Desktop 当前 ScreenModel 只在同一个 runtime 对象和 catalog 对象下复用自己的当前
projection，偏好／语言选项变化只重新分类，不能触发 getter；新 runtime 或 catalog 帧
必须重新投影。此缓存只属于现有 model 当前帧，不是全局源缓存或动态代理。
Android GetExtensionSources 在订阅开始固定实际源行所需的 metadata，不主动探测未用的
client／baseUrl，偏好排放只改变 enabled；DetailsScreenModel 仅在 extension 变化时重新
订阅，避免 `_sources` 更新形成自我重入。typed 源失败以原安装 owner 回报 Manager 后
沿既有管理反馈收敛；控制异常和未标记宿主 Error 不进入空源列表回退。

此适配不等于所有业务边界已经完成。Desktop 剩余接线／回归、阅读／下载、Creator
及其它第三方回调仍按 EC-05/06 逐项接线；默认 parser 响应生命周期另见 7.6.3。
共享 asObservable 当前使用 execute 和同步 onNext，不能据此声称 Rx map 就在 OkHttp
enqueue 线程；其它调度链须用真实线程与资源测试证明，不改全局 Rx hooks。

#### 7.6.3 默认 HttpSource 请求与响应解析边界

默认 HttpSource 的热门、搜索、最新、详情、章节、页列表与图片 URL 链在实际执行
client getter、请求 hook 和解析 hook 时使用同一个 guard。OkHttp newCall 等宿主装配
留在 guard 外；搜索保留原有 defer 时机，其余 fetch 的即时装配时机不变，不修改公开 ABI。
旧的仅捕获 NoClassDefFoundError 并转 RuntimeException 的搜索分支由统一类型化传输替代。

解析必须在 Rx map 内转换链接错误，不能等 map 以外的协程 catch：RxJava 会把原始
LinkageError 当作 fatal，跳过订阅者 onError。默认 parser 返回值不持有 HTTP 流，
成功与失败后均由默认链关闭响应。关闭失败时先检查双方的 fatal、未知深尾及取消，
再传播宿主 Error；普通清理失败才附加到原异常，不能使用 use() 将 fatal 压成 suppressed。
OkHttp 自己对普通 close 异常的 quiet 语义保持不变。

图片请求只守卫 client 和 imageRequest；成功 getImage 返回的 Response 仍由调用者
读取并关闭，不能套用 parser 的资源规则。Range、进度、取消、旧单参重载分派保持原链路。

本节仅证明默认 HttpSource 的 hooks 与资源生命周期。扩展覆盖 fetch/get 方法、自定义
Rx operator 或额外调度时仍需实际业务调用边界及线程证据；不能用默认链通过替代这些
接线，也不修改全局 Rx hooks 或 uncaught handler 来吞错。

#### 7.6.4 阅读与下载实际调用及确定性失败

Android HttpPageLoader 的缓存 miss 只对普通缓存异常回退到源：控制异常、宿主 Error
和未知 cause 深尾先传播，不能因此发起额外源请求。页列表与 AndroidReaderPageFetchPort
的图片 URL／图片请求分别守卫实际第三方 lambda；磁盘缓存、encoded store 和调度器
不标成源执行。CanonicalReaderMaterializeExecutor 在通用错误投影前同样保留控制／
宿主错误，已标记的兼容失败仍沿既有 Failed 结果投影，不冒充空章或 Ready。

普通失败保留原阅读器重试语义；已分类的不兼容页在 holder 重新绑定时不自动排队，
现有用户显式 retryPage 仍可重新执行。正常 viewport、物理并发预算、代际失效、配对和
缓存算法不改。Android Downloader 在页列表、图片 URL、图片和实际使用的源名称／
章节链接处守卫，目录命名只守卫 source.toString，不把文件操作包进 guard。兼容失败
保留页／章节错误并显示既有本地化文案，不发布章节完成目录或 cache 完成记录；普通
图片 IO 仍是原来的三次有界重试，取消／致命／宿主 Error 不转为普通下载失败。

Desktop chapter port 对实际 getPageList 使用同样守卫；图片 URL／完整图片响应及
client／headers 的共享 adapter 覆盖直接和反射 suspend 调用，包括挂起后恢复的故障。
API 缺失的既有合法回退保持；存在但执行失败的 getter 不改用 fallback client。
Reader 与下载队列共享这些 adapter，Source 对象和类型不代理。Desktop 队列还须在
自身页列表入口接入 guard，在重试前拒绝确定性不兼容，并先识别专用传输 marker，再
沿用网络／存储分类；不能把 client／headers ABI 失败误报为磁盘错误。宿主存储异常
若包着 VM fatal，同样传播到所属任务处理器，不能降为 Storage。

两端失败源与健康源／章节有真实并行验证；正常请求 headers、POST、body、旧重载、
HTTP 错误、取消、资源释放和旧代际保持各自现有链路。本节不覆盖任意插件自行管理的
线程或独立客户端，也不能替代自定义 Rx 调度、Creator、全局搜索归因和 R8 原生验收。

#### 7.6.5 操作前冻结归属与兼容异常传输

`SourceCompatibilityException` 可携带不可变 `SourceFailureIdentity`，保留旧的一参和二参
真实 JVM 构造器。身份只来自宿主在操作开始前持有的安装记录，不能在报错时重查当前
版本、按共享域名归因或从 JAR/routing key 猜包名。未知字段保持 null，在诊断标签中明确
显示 unknown；名称/版本等标签有单行与长度限制，不接受查询、URL 或凭据字段。

共享 guard 在 IO/反射等包装中保留已有冻结身份及最精确阶段，继续保留完整 cause。
附加身份不会替换已有身份；fatal、取消及未知深尾先按原规则传播，不能借身份绑定把
宿主错误当作可恢复的扩展错误。底层网络层不会自行解析或推断扩展身份。

Android 全局搜索从同一个 installed 快照选择扩展并按 Source 对象引用匹配归属，先冻结
包名/名称/版本，再执行过滤器及查询。失败结果只附加该次冻结身份；操作中安装记录
更新不会把旧错误标成新版本，sourceId 从实际请求取值，不为诊断额外调用源 getter。
新增归属映射不依赖插件自定义 equals/hashCode；仅用对象引用匹配，避免同值对象串包。
Desktop 全局搜索的实际页面从既有 extensionPresentationService 读取一次 installed 值，
向 coordinator 传递本次快照。只按原 Source 引用匹配并消费 metadataSnapshot，不以
InstalledExtension 的 JAR/routing 名、无元数据的版本默认值或相同 sourceId 补猜归属。
child coordinator 保留该操作的冻结身份，兼容失败沿正常状态附上请求 sourceId。
旧的未传 owner 接口仍可调用，身份明确未知；单源浏览的旧构造入口保持不变。

相同查询仅在 Source 引用及冻结 owner 都未变化时复用；替换实例或版本变化后，现有
搜索按钮可重新检查该源，健康交集仍复用。失败卡复用现有本地化错误区域，以名称/版本
补充说明，未知使用既有 Unknown 资源；不新增自动重试、登录、停用或更新动作。
已有 canonical 写入、错误恢复、历史、导航及 session 保留使用原链路。

这里证明异常传输、Android 搜索绑定及 Desktop 实际页面捕获/版本反馈。源元数据 getter
的完整安全描述、Android 对应页面版本反馈和实际日志记录仍须接线，不能把异常已有
身份字段等同于双端完整诊断已经完成。

#### 7.6.6 Creator 发现与自定义 Rx 的实际边界

Creator 的 catalogue adapter 只在实际过滤器、作者搜索、普通搜索及详情调用处复用
共享 guard。确定性 ABI 失败保留 `CreatorSourceFailure.ExtensionIncompatible` 与原始
类型化 cause，不能降为 Network，也不能在作者 hook 失败后改走普通搜索假装恢复。
普通作者搜索失败仍沿原有 fallback；取消、致命异常、宿主 Error 和未知深尾先传播。
已知请求 sourceId 用请求快照传递，不为结果归属重复调用第三方 getter。

真实 CreatorDiscoveryService 以现有 checkpoint 的安全错误码记录
`EXTENSION_INCOMPATIBLE`，并将该失败的 backoffUntil/nextDueAt 置为 Long.MAX_VALUE，
阻止到期扫描自动重试。这里没有新增 schema，也不承诺自动识别扩展版本更新；用户
现有显式检查（manual force）仍会执行，成功后清错并恢复普通 due。健康源继续正常发现
与提交，普通 IO 保留原 backoff。维护时不要将 MAX 当作正常日期展示或做无界日期运算；
当前 SQL 只比较 due，失败 checkpoint 不进入成功 calendar 重算。

DiscoverCreatorWorks 将类型化不兼容传到既有 Android 作者详情错误区；Desktop scheduler
保留 task 的 typed error，并在原有 failureMessage 区域使用共享本地化兼容文案。
不展示内部 ABI 信息，不提供登录/WebView，不增加自动重试或新页面。Creator 初始
源快照的元数据 getter 仍需后续安全描述接线，本节不代表所有 getter 已被冻结。

自定义 Rx 必须按真实执行/错误交接位置判断：同步自定义 map 的 raw Linkage 可以由
外层实际 Source ABI/service guard 捕获；扩展在独立 executor 把错误交回 onError
（如 IOException 包装 Linkage）时，同样可以类型化。若扩展独立 executor 上的 raw
Linkage 被 Rx 当作 fatal、只逃到该 worker 而未交回订阅者，宿主 await 不会收到终态；
调用者取消仍负责 unsubscribe。这是已实测的扩展自行管理线程边界，不是对全部自定义
operator 或异步调用的豁免。不得安装全局 Rx hooks/uncaught handler 吞错，也不能用
默认 HttpSource parser 通过替代该边界证据。

#### 7.6.7 深链接、迁移和章节准备的补充调用边界

Android DeepLinkScreenModel 在实际 ResolvableSource 的 URI 判断、漫画解析、章节解析、
章节列表及随后的源 ID getter 处守卫。只有专用兼容传输进入新 Failure 状态，并先检查
控制异常；现有 Screen 复用 EmptyScreen 与共享兼容文案，不导航到全局搜索伪装成
NoResults。正常不可解析链接仍走原 fallback，正常章节仍调用原本地查找/目录同步链。
networkToLocalManga、数据库查找和目录同步不在此 source guard 内，也不将其普通错误
误标为插件不兼容。阶段使用既有 METADATA/DETAILS/CHAPTERS，不新增插件 ABI。

Android SyncChaptersWithSource 仅在 ChapterDirectoryPlan.prepare 的真实 HttpSource
prepareNewChapter 回调和实际 ID/toString 读取处守卫。每章回调在 guard 外从宿主 Manga
转换一个独立的 SManga，保留原有可变输入隔离；不能为减少转换而跨章节共享该对象。
ID 在提交前读取并复用于 Local 判断和目录 effects；主机漫画转换、计划、校验、事务、文件处理及 fetch interval 更新
保持在边界之外。恢复已有 phase 时读取的实际 ID 也受守卫。兼容失败不能提交空的正常
目录，取消/致命/宿主 Error 保持控制传播；不重做整个目录模型或改变已提交 phase 恢复。

Desktop 旧详情迁移 searchMigration 的真实搜索、MangaDetailScreenModelFactory 和
DesktopAppModule batch 的实际 getChapterList 都在原调用位置守卫。factory 保留 typed
cause；保留的旧 picker/确认分支通过既有 Snackbar 反馈并清除 busy，不能 runCatching
后假装无结果。当前 More 菜单实际进入 MigrationSearchScreen，该普通导航不变；旧
picker 测试只证明保留分支，不将它描述为当前默认入口。

现有 batch migration 事件只携带字符串错误，不能据此新增协议：仅在 AppModule 的
兼容 marker 出口经控制检查后以本地化 IOException 包装，保留完整 cause，再交既有
ERROR 文本；不把整个迁移/SQL 包成 source 调用。下游共享 BatchMigrationOrchestrator
及 Desktop controller 的通用 catch 同样先执行 rethrowSourceControlFailure，避免上游
传播的取消/致命/宿主 Error 或未知 cause 深尾再降为普通 Failed。普通 IO 与已标记兼容
仍走原逐项失败/健康项继续、暂停/取消/重试和 checkpoint 协议。

本节不等于所有源元数据读取已冻结。Creator 初始 snapshot 和保留 legacy picker 的
id/name/lang 仍需后续登记键/安全描述接口；不得伪造 sourceId，也不代理 Source 对象。

#### 7.6.8 全局搜索逐源状态与作品订阅

Android 全局搜索的单源结果必须在同一个 MutableStateFlow.update 内读取当前 items、
修改并发布。不能先在状态外计算整张 map 再写入，否则线程池内健康/失败/空结果会互相
覆盖，使已结束源重新变成 Loading。发布中的 State.generation 是原子代际屏障：旧代际
结果不能覆盖已经发布的新搜索，同时保留当前输入、筛选和其他状态字段。

作品观察按领域来源 ID 和作品 URL 分组，以独立 Compose key 持有 produceState 初值与
数据库订阅。仅替换 initialValue 或在旧组内等待新数据库值不够；新结果没有数据库发射
时也必须立即使用自己的初值，旧 URL 不能继续占据当前卡片。

Android 错误组件复用已有文案、恢复动作和可选小字说明，展示已冻结的扩展名/版本；
未知显式使用 Unknown。确定性兼容失败无登录或自动重试。双端真实三源用例验证失败源、
健康源和挂起源独立；后续查询/版本重新检查取消旧挂起任务，拒收旧结果并完成进度。
这些规则不代表第三方元数据 getter 或日志 sink 已全部接线，相关欠项继续按 roadmap。

#### 7.6.9 搜索兼容失败的实际诊断通道

全局搜索绑定冻结归属后，通过共享 `recordSourceFailureDiagnostics` 输出单次兼容失败。
Android 直接使用 ERROR 级 `android.util.Log` 和固定 `MihonExtension` tag；不能依赖只在
Debug 初始化的通用 logger。它沿用现有 logcat 读取通道。Desktop 默认写既有 stderr，
不新增日志文件、遥测、诊断页面或自动上传。搜索页面和 child coordinator 可注入写入端
验证失败处理；生产默认通道须由真实搜索入口测试覆盖，不能仅以 formatter 单元测试验收。

记录只包含冻结 sourceId、已安装包名/名称/版本、固定操作阶段、固定错误类型，以及原始
链接错误的脱敏栈。未知字段明确写 unknown，不在报错时补读当前安装记录或第三方 getter。
沿用 `safeCrashReport` 的消息剔除和栈标识过滤，栈文本最多 2048 字符；身份字段每项最多
160 UTF-16 单元且不拆开有效代理对，移除控制和换行字符。包含 tag 的单条 UTF-8 记录小于
4096 字节。搜索词、请求 URL、凭据、异常消息及源码文件名不进入新增记录；原 cause 仍
保存在类型化失败中，不以脱敏字符串替换异常链。

写入在状态 CAS 重试及 coordinator 锁之外完成，不随重组或 CAS 重试重复。普通诊断写入
Exception 不覆盖原失败，也不取消健康源；写入端包装的取消、VM 致命错误、未知深尾及
未标记的宿主 Error 仍传播。普通网络错误和取消不产生兼容日志。日志写入不构成搜索成功，
也不触发作品持久化。

此接线不修改现有 SafeCrashReportCodec 或结构化反馈协议：独立的安全崩溃文件/反馈预览
不会因此自动附带这些 logcat 或 stderr 记录。导出崩溃日志本身的故障仍须独立定位；R8 后
真实设备上的日志行为须按 EC-08 原生门验证，JVM/Robolectric 通过不能替代它。

#### 7.6.10 宿主登记 ID 与失败时的只读识别

共享 `SourceManager.getRegisteredSourceId(source)` 只查询宿主已有登记，按 `Source ===`
匹配；默认实现和未知对象返回 null，不为补身份调用第三方 `id` getter。新登记 DTO
`SourceRegistration` 的相等和 hash 只依赖引用身份及冻结 Long，不受插件自定义相等性影响。
这不改变公开扩展 Source ABI、ID 算法或既有 InstalledExtension 整体相等协议，也不包装
Source；不能据此宣称所有旧容器都不执行插件 equals。

Android 复用当前 sourcesMap 的键，并用同一帧的键排除在线 stub。Desktop loader 在
METADATA 守卫内保存首次实际 ID，并随 Loaded/Installed runtime 记录发布；按 ID 查源、
版本、重载验证和候选去重使用该登记值。替换/卸载后旧引用不重新绑定给新实例；没有
登记信息的测试/旧宿主记录保持未知，不能加失败时读取 getter 的回退。

Desktop builtin 在构造时登记，两个内置测试桥提供宿主常量的 typed registration 快照。
typed provider 的当前替换/清空即时可见。保留的 raw additional callback 只在原有 get/query
读取链观察新引用并登记一次；已登记 alias 复用键。失败时的只读识别不刷新 raw callback，
未曾登记的 raw 对象仍未知；raw 移除须由原有 get/query 观察，不另建事件系统。刷新先
剔除已观察移除的旧记录，即使后续新源登记失败也不能继续认旧实例。原 builtin/首命中
短路顺序保持，不能为查询前一源而触发后面坏源的 ID getter。

源登记锁内不再获取 extension runtimeLock：候选读取消费锁外取得的已发布登记快照，避免
runtime 发布同步唤醒源候选观察者时出现反向锁依赖。此设施为后续安全 metadata descriptor
提供可靠 ID，不代表 Creator、旧迁移 picker 或全局搜索所有 id/name/lang 消费已保护。

#### 7.6.11 查询前的安全元数据快照

共享 `SourceMetadataSnapshot` 保存原 Source 引用、可空登记 ID、已读取名称/语言及类型化
错误。它是普通只读 class，不依赖插件 equals/hash，不提供未知 ID 的 getter 回退，也不
改变 Source ABI、ID 算法或持久化 schema。已知普通元数据失败不阻止读取另一项以保留
部分描述；兼容错误优先于普通失败，取消/致命/未知深尾及宿主 Error 保留传播。

无选择策略的二参数 `prepareSourceMetadata` 保留名称→语言顺序。带纯宿主语言策略的
三参数入口先读取受守卫的语言；已知不匹配时不执行名称 getter。策略本身在 source guard
之外，其错误不能被归因给扩展。隐藏/未置顶等可由宿主登记 ID 决定的选择，应在准备前
完成，避免为了随后丢弃的源触发第三方元数据。未知语言保留失败反馈，不能假装正常空源。

Android SearchScreenModel 在调用前冻结已安装归属与登记键，查询、排序、作品来源 ID、
卡片及浏览导航共同消费本轮快照。相同显示名称以登记 ID、再以本轮行位置区分，不因
TreeMap 比较结果相同而合并。同关键词但运行对象/归属版本/登记变化会重新检查，健康
交集保留；元数据失败在 filter/API 之前形成逐源结果。未知登记不派发 API、不构造假的
SourcePageRequest ID，浏览入口禁用，其他源仍可完成。

已知行使用原登记 Long 作为 Compose key。未知行只使用页面代际与快照行位置生成可放入
Android Bundle 的字符串 key；该值仅用于 UI，不能回填 sourceId 或导航参数。直接拿
snapshot 对象作 Android LazyColumn key 会在未知行出现时失败。迁移搜索保留显式来源及
其专用顺序，不套全局隐藏/语言选择；其父 model 必须使用同一个显式注入 SourceManager。

这不是所有后续第三方数据读取的总保护声明：Desktop 全局搜索接线及已验证的返回字段
边界见7.6.13，其他调用端仍须分别验证。SQL/宿主 reducer 的整体实现不属于 source
guard，不能为了分类方便包住整段持久化。

#### 7.6.12 Creator 与保留迁移入口的元数据消费

Creator adapter 每次来源快照先通过 factory 捕获当时的宿主安装记录，再按 Source 引用
冻结归属；factory 不是 DI 初始化时永久保存的一帧。生产 ID lookup 返回 null 时不再
尝试 getter。已知 ID 的元数据失败保留在来源条目并缓存类型化失败，搜索/详情在实际 API
之前返回既有 CreatorSourceFailure；健康源继续。已知被隐藏的 ID 完全不读元数据，语言
不匹配不读名称；已知语言已被排除的未知登记不应再中止所选健康源。

真正被选但没有登记 ID 的来源无法进入现有 Long ID 结果协议，沿既有 AppErrorException
给出操作级失败：普通缺失为 MalformedData，实际链接错误仍为 ExtensionIncompatible。
不得造 ID、静默成功或扩展数据库/反馈协议。类型化已知失败与未知登记是不同边界。

原显式来源的 Creator Service 入口保留 permissive 语义。配置了 Catalogue adapter 时，
派生本轮 adapter 继承宿主 lookup/身份 factory，但保留传入的确切来源和全新临时 cache，
不让配置端 resolver 替换显式来源，也不套平台默认选择策略。独立旧显式入口没有宿主
lookup 时，仅在首次登记阶段守卫读取 ID；注入 lookup 后返回 null 不能触发该兼容模式。
实际 AuthorsTab 仍走原显式调用，由真实 DI/service 接线证明，不能仅验证派生函数存在。

Desktop 保留的旧迁移 picker 同样消费准备后的描述，错误/未知项禁用，目标 ID 来自快照，
不在渲染或点击时重新读取 getter。实际 API 前再次确认该引用仍登记，失效走既有错误
Snackbar，不伪装空结果。该旧分支不是当前 More 菜单的默认迁移入口，不据此改变默认导航。

#### 7.6.13 Desktop 全局搜索快照与返回对象字段边界

Desktop 的生产搜索先捕获已安装帧，再由宿主登记键执行隐藏/置顶选择和安全语言选择。
`DesktopGlobalSearchState.sourceMetadata` 是本轮展示描述的唯一权威，不再另存一套
session sourcesByGeneration。已知来源的 child、查询、排序、标题、列表 key、源导航及
重试均使用冻结 ID；未知来源保留错误行和进度，不制造 Long 请求或进入浏览。独立旧
coordinator 入口没有 registry 时仅首次守卫登记 ID，不能作为生产 lookup=null 的回退。

child 首次消费已准备描述，元数据 IO 的显式重试重新准备描述和筛选器；取消时仅在本次
started 帧仍有效的条件下，一并恢复原错误及原描述。相同关键词只在前后元数据都健康、
对象引用与冻结归属相同的交集中复用，不因原显示名相同而合并来源。

登录地址不在结果投影/渲染期间读取。用户点击既有登录动作后，child 校验所显示的确切
request，再守卫读取 HttpSource.baseUrl；失败转为该请求的错误并记录冻结归属，链接
不兼容不保留登录动作。不使用假 URL 或猜测的 ID。正常401继续原 Cookie/登录重试链。

共享 SourceQueryReducer 对去重所需的每个 SManga.url 单独守卫；其余集合处理不包在
guard 中。字段失败转换为相应查询阶段的失败；后续页仍保留先前内容。Desktop child 在
锁外执行这段读取，只有原 state 实例仍为当前帧才提交、冻结归属和记录诊断。

`SManga.toDomainManga(Long)` 保留原 JVM 入口，另有显式 phase 重载，逐一保护实际
读取的字段/getGenres。Creator 的 URL、快照、缓存 copy 与详情字段分别守卫；URL 只
对原本允许的未初始化属性回退。Desktop Save 在进入 SQL 前把本链实际消费的字段转为
宿主值；章节准备回调仍收到原 SManga，全量元数据在该回调后捕获，保留原修改语义。
存储错误仅透传已标记的扩展兼容异常，普通 IO 保持原存储分类；控制/宿主 Error 不降级。

后续 materializer、Android 全局/分页及深链接的验证边界见7.6.14。尚余 Browse UI 的
标题/WebView/assist、Smart 迁移实际反馈和 SyncRecovery 等已盘点入口，不能把 mapper
或 reducer 的通过视为所有消费端完成。原 APK/ART/R8 结论由 EC-08 独立提供。

#### 7.6.14 转换结果保真及 Android 消费端

Desktop 全局状态保存本次已确认的 `sourceIdentities`（Long→纯宿主 DTO），materializer
接收此帧，而不在失败时查询当前安装记录。它在任务内逐一守卫 URL、冻结 URL→原 SManga
的表，然后调用原持久化。缓存比较只使用原对象引用，不调用插件 equals；不因持久化后
补建导航索引而再次读 URL。存储本身不放入 source guard：只有已标记的兼容异常保留
ExtensionIncompatible/冻结归属，其余普通 IO 仍为原 Unknown/Retry；控制与宿主 Error
先传播。兼容失败不自动重试或显示登录，旧代际/attempt/已关闭任务不发布完成结果。
未知 ID 的 prepared 兼容错误只在已接受的搜索操作中记录一次安全诊断，不造 child/request。

Android Global Content 在实际字段转换范围捕获标记兼容异常，沿原逐源错误、操作前
归属与代际发布；NetworkToLocal/SQL仍在外。登录恢复只消费展示帧的 prepared metadata，
在用户点击时守卫 baseUrl，前后校验 generation 与旧 pageError 引用；失败通过 CAS
反馈给仍有效的同一错误帧，接受后才诊断，不打开 WebView。旧二参数 factory 只用于
无宿主描述的独立调用，所需 ID/name 守卫读取，不能作为生产未知登记的回退。

Android Browse Model 在筛选器调用前捕获归属并放入操作状态，Pager 同时消费 listing、
filterError、identity，不能在源失败后才读取已更新的安装记录。可信导航 ID 用于初始化
偏好及 Paging 请求。分页先守卫/冻结 URL，仅为未缓存且首次出现的作品转换其他字段；
重复页保持原分页边界，坏重复项不额外读取 title/memo 等字段。链接错误走既有类型化
分页错误和恢复语义，API/字段失败不写成功作品；真实默认 AppModule 的新依赖接线有验证。

遗留 AndroidSourceRepository 的 search/popular/latest 将已请求的宿主 ID 传入 Paging，
分别使用 SEARCH/POPULAR/LATEST 守卫 API和字段。独立旧构造入口保留，只在确实没有
宿主 ID 时守卫读取一次本次 load 所需的 ID；不能在每个结果项重新探测。已有 catch
先恢复取消/致命/宿主 Error，普通持久化 IO 仍是原 LoadResult.Error，不改 schema。

DeepLink 在选定可解析来源后使用宿主登记 ID；缺登记显示既有未知错误，不读取 ID 或
请求作品。详情字段保留 DETAILS 阶段；所选 SChapter.url 在 CHAPTERS 内读取一次，
之后本地查询/同步匹配复用该宿主字符串，不能把查询、事务或整个同步过程包为插件调用。
URI 选择与原健康章节同步链保持不变。上述边界均有真实 UI/API/Paging 或持久化接线门，
不把结果类型或源码符号检查当验收，不扩为任意插件线程/JNI的进程级保证。

#### 7.6.15 Android Browse 展示与同步恢复

Browse 在初始化及显式重置筛选器时，先按 Source 引用冻结安装归属，再只准备当前页面
需要的 name/supportsLatest；失败复用已有 filterError 阻止查询，不预读 lang/client。
toolbar、listing 和 WebView 标题消费准备值及宿主导航 ID。assist 与显式 WebView/login
动作只守卫实际需要的 baseUrl，Navigator 不在守卫内。错误只回写仍有效的 State/
pageError 引用，接受后记录冻结归属的安全诊断；反馈使用原页面错误区域，保留健康内容。
显式重试走原 resetFilters，新 listing/search 清除展示错误，不在重绘时自动重试。
保留旧组件/factory 的独立入口，但不能用其 getter 回退替代生产已准备的宿主描述。

同步恢复的 READER 仅在本地作品缺失时，沿原恢复入口获取详情。宿主登记 ID 不存在时
不探测 source.id 或发送详情请求；调用前按 Source 引用冻结安装归属。只守卫源 API、
实际返回字段及原 URL setter，保留原请求 URL、30 秒超时与 IO 调度，映射完成后才沿
既有 NetworkToLocal 持久化。SQL/导航不放进 source guard；控制及未标记宿主 Error
先传播。接受当前 recoveryPlatformRequest 后才显示不兼容提示、冻结扩展名/版本并
记录诊断，返回仍使用原 TARGET_UNAVAILABLE 协议，不新增同步状态或恢复协议。

这两条入口的接线测试不能替代 Smart 迁移反馈或 EC-08 原 APK/ART/R8 验收；迁移
实际消费边界见7.6.16，宿主管理之外的插件线程/JNI 边界仍遵循7.6原有说明。

#### 7.6.16 Smart 迁移候选与失败反馈

Smart 引擎保留旧入口，守卫一次 legacy ID 读取；生产入口传偏好中已由宿主 lookup
解析的 ID，逐一守卫 FILTERS/SEARCH 及实际返回标题/字段。取消及未标记宿主 Error
先传播，不将整个持久化或同步事务当作扩展调用。

自动迁移在开轮时冻结安装帧，并为候选按 Source 引用捕获归属。普通模式仍按原顺序
取首个健康候选，按章节模式仍使用5并发并按最新章节选择。已标记的不兼容使该候选
失败，详情 enrichment 失败时继续原剩余候选；不提前请求未被选中候选的详情。章节/
详情失败不能伪装为成功或普通 NotFound；无可用候选时，沿原反馈位显示 Failure，
hideUnmatched 不移除错误行，批量动作的跳过统计包含 Failure。

传给扩展的原详情输入不变。返回详情只冻结原 UpdateManga 消费的字段，再交宿主更新
链；不新增 remote.url/initialized 读取。标题未初始化保留原回退，getGenres 的列表
保留逗号元素语义，不以 join/split 重新解释；缓存/SQL 不在 source guard 内。
手动选择复用同一章节/详情和标签边界，不把兼容失败转成缺章节提示。

初始标签、候选失败及选中后失败均保留冻结归属；健康候选最终成功也保留此前坏候选
的诊断。错误随本行结果返回，只在当前 context、item 引用和 migrationScope 仍有效时
发布与记录；移除/取消后的迟到结果不得覆盖或追加诊断。不新增迁移协议、数据库字段
或页面；以上定向接线证据不替代 EC-08 原生验收。

#### 7.6.17 常规详情与书库更新消费

共享 sourceMangaMetadata 保留旧4参及默认参数入口，新增显式 phase 入口；仅逐一守卫
原更新策略实际消费的字段。标题未初始化保留原回退，genre列表不做join/split重解释，
不新增remote.url/initialized读取。Android UpdateManga先取得纯宿主MangaUpdate，再
计算封面版本、访问缓存或写SQL；章节效果使用已存Manga.source，所需source.toString
单独METADATA守卫，fetchDetails=false仅保护实际memo读取。

作品页及LibraryUpdateJob在更新API前按Source引用冻结安装归属。catch先恢复取消、
致命和未标记宿主Error；只有显式兼容marker沿既有本地化提示/安全诊断反馈。作品页
接受仍属同一作品/来源的结果；书库保留WorkManager顶层已有取消完成约定，不将内层
取消当普通failedUpdates。错误文件保存本次源引用与冻结身份，兼容失败不重读坏标签；
普通失败需要标签时仅守卫toString，失败使用安全宿主标签，文件IO不属于source guard。

Desktop Checker在实际API前捕获failure identity；Module按Source===匹配已安装的
metadataSnapshot，不用文件名或sourceDate的builtin回退猜测诊断归属。实际返回字段/
memo、所需标签及prepareNewChapter分别保护，宿主目录plan/commit在外；兼容失败沿
原UpdateResult.sourceError反馈，存储IO/控制与宿主Error保持原传播。sourceDate已有
登记键查询语义不改。上述链路有真实SQL/缓存、实际Model/Worker和Module接线门，
不代表其他后台来源投影或EC-08原生门已通过。

## 8. 安全与隐私

- 手动代理地址属于敏感配置，不写入普通运行日志。
- 代理认证凭据不得明文存储。
- UI 和诊断导出默认隐藏凭据。
- 域名观测不保存完整 URL 或用户请求数据。
- 复制到剪贴板前不自动加入未知第三方域名。
- 插件设置不能影响应用更新、插件仓库或其他插件。
- “强制直连”只承诺 Mihon 应用层不选择代理，不能绕过操作系统 VPN/TUN。
- “实际路由”只报告 Mihon 能从生产网络栈观测的 route，不推断外部代理最终出口。

## 9. 当前实现状态与迁移

Desktop 已实现本文首版产品链路：

- 全局网络使用“跟随系统 / 强制直连 / 手动代理”三个互斥模式；
- Windows adapter 直接读取当前用户的静态 `ProxyEnable`、`ProxyServer` 与
  `ProxyOverride`，按目标应用代理及 bypass；读取失败时再回退 JVM `ProxySelector`；
- Windows 无协议回环入口使用 500 ms 上限的 SOCKS5 capability 握手识别
  v2rayN/Xray 等 mixed 入口；显式协议和远程入口不探测，连接失败会使缓存失效；
- 连接测试、插件仓库、应用更新和托管插件请求共用同一全局 OkHttp 策略与 Windows
  adapter，不维护仓库专用的旁路客户端；
- 插件仓库配置流会在 Desktop 启动后异步预取目录；“可用”页在首次目录完成前显示加载态，
  无仓库、请求失败和成功但为空使用不同反馈，不再把“尚未完成”显示成“无可用”；
- 最近一次完整目录在进程内保留 5 分钟；短时间返回页面直接复用快照，过期刷新时继续展示
  快照并明确提示后台检查。仓库增删或替换时按目录结果携带的仓库身份失效旧快照；如果
  数据库通知与刷新并发，先核对该刷新实际读取的仓库，避免取消一份已经使用新配置的有效
  结果。部分失败和整体失败不会延长新鲜期，避免把失败结果长期缓存成成功；
- 仓库元数据和索引请求继续复用生产 OkHttp 的磁盘 HTTP cache，并遵守服务端缓存语义；
  应用层目录快照不绕过代理、TLS、签名校验或仓库解析；多个仓库仍由共享目录服务并发拉取；
- 设置页区分已保存策略、当前生效策略和最近实际 route，并提供真实连接测试；
- 连接测试遇到瞬时 timeout 时会使用独立 Dispatcher 与 ConnectionPool 重试一次，
  避免应用既有 TLS/代理连接状态污染诊断结果；非 timeout 错误不会自动重试；
- `HttpSource` 的默认托管客户端按插件包名应用四种插件复写策略；
- 扩展 classloader 在 source 实例化前绑定插件身份；`NetworkHelper.client` 及其
  `newBuilder()` 派生客户端均继承插件策略、域名观察和 route 观察；
- 插件客户端每次请求动态读取当前复写策略，策略变化会清除该插件连接池；构造期缓存的
  客户端无需重建即可在后续请求使用新 route；
- 下载、阅读器图片回退和阅读器后台预加载等主进程代发请求均按 `sourceId` 获取插件客户端；
- Compose/Coil 的远程请求统一使用 production `ImageLoader`；漫画封面携带 `sourceId`，按插件
  网络复写策略和源请求头加载，仓库图标等普通图片使用全局托管客户端；
- source 异常统一保留为 `AppError`，用户反馈区分超时、DNS、TLS、拒绝连接、连接重置、
  鉴权、限流、服务端和响应解析错误；
- 搜索结果进入详情后的详情/章节刷新通过有界状态流公开加载与失败状态；失败时保留结构化
  `AppError` 并显示重试入口，不能吞掉异常后把未加载完成伪装成“章节 0”；
- 插件详情页显示支持程度、当前生效 route、连接测试、声明域名和运行时观测域名；
- 运行时观测覆盖托管 HTTP 请求及重定向，只持久化规范化主机名，单插件上限 256 条；
- 支持纯域名、Mihomo `DOMAIN`、显式 Mihomo `DOMAIN-SUFFIX`、sing-box 与
  v2rayN/Xray 域名项复制，并按插件记住策略组/outbound 名称。

当前边界：

- 全局策略和 DoH 在下次启动时生效；插件复写对现有托管客户端的下一次新请求立即生效；
- Windows PAC URL 和 WPAD 自动发现尚未由 Mihon 自行解析，只能使用 JVM fallback；
- mixed capability 探测只适用于无协议的本机回环入口，不用于远程代理，也不尝试识别
  代理软件、节点或最终出口；
- 插件自建 `OkHttpClient`、原始 Socket 或进程外通信无法被保证接管，界面会显示完整、部分或未知；
- “强制直连”不能绕过系统 VPN/TUN；route 观测也不能推断外部代理的最终出口；
- 观测记录当前保存域名集合，不保存完整 URL、请求时间线或请求内容；
- 目录快照只在当前进程内保留；重启后的加速依赖符合 HTTP 缓存语义的磁盘响应，不持久化
  一个可能过期且未经重新校验的“成功目录”；
- 真实 Keiyoushi 插件兼容性统计属于阶段四的持续调查，不影响上述托管链路的可用性。

迁移时必须保留已有用户配置：

| 旧设置 | 迁移结果 |
|---|---|
| 旧代理开关开启且 URL 合法 | 全局“手动代理”，沿用 URL |
| 旧代理开关关闭 | 全局“跟随系统” |
| 旧代理开关开启但 URL 非法 | 全局“手动代理”但标记配置错误，不静默直连 |

迁移实现只读取旧布尔开关作为一次性输入；`network_mode` 是唯一行为权威，避免两套状态并存。

## 10. 实施阶段

### 阶段一：全局语义与可见性

- [x] 将旧开关替换为系统、直连、手动三个模式；
- [x] 实现 Windows 系统代理 adapter；
- [x] 展示保存策略和当前生效策略；
- [x] 增加连接测试；
- [x] 记录并展示按目标区分的实际 route；
- [x] 完成旧配置迁移。

### 阶段二：插件复写

- [x] 建立插件身份到网络上下文的 production wiring；
- [x] 实现四个互斥插件策略；
- [x] 提供插件当前生效状态和连接测试；
- [x] 检测并展示完整、部分、未知/不受管支持等级；
- [x] 确保仓库、更新和其他插件不受复写影响。

### 阶段三：域名声明、观测与导出

- [x] 提取插件声明域名；
- [x] 在托管网络链路记录规范化域名；
- [x] 实现持久化上限和清除操作；
- [x] 实现纯域名、Mihomo、sing-box、v2rayN/Xray 复制格式；
- [x] 增加剪贴板反馈和范围说明。

### 阶段四：兼容性调查

- 使用当前 Keiyoushi JVM JAR 验证搜索、详情、章节、图片和登录/重定向链路；
- 统计完整、部分和不受管插件；
- 根据真实插件行为决定是否需要扩展 API 约束或独立进程方案。

## 11. 验证要求

所有产品行为变更按红、绿、重构执行。测试必须经过 production 实现和 wiring。

### 11.1 策略单元测试

- 全局三个模式的解析；
- 插件四个模式与全局三个模式的组合矩阵；
- 系统为不同 URI 返回不同路由；
- DIRECT 明确绕过 selector；
- 手动 HTTP/SOCKS 格式校验；
- 旧配置迁移；
- 保存状态与生效状态不同步。

### 11.2 HTTP 集成测试

使用 MockWebServer 或测试代理服务器覆盖：

- DIRECT 成功及代理不可达时仍不使用代理；
- HTTP 代理和 SOCKS 代理真实连接；
- Windows 无协议 mixed 入口经 capability 探测后，生产 `DesktopNetworkHelper` 与
  `DesktopExtensionApi` 能通过同一 SOCKS route 获取仓库 manifest 和索引；
- 系统 bypass；
- 插件复写不影响全局仓库请求；
- 两个插件选择不同策略时客户端不串用；
- DNS、代理连接、TLS、403、429、500、超时和重定向；
- route 观察结果来自真实 production 请求。

### 11.3 域名测试

- scheme、端口、路径、查询参数和 fragment 被移除；
- `www` 与根域保持独立；
- Unicode/Punycode、IPv4、IPv6、尾部点和大小写；
- 声明与观测合并去重；
- 重定向和图片域名进入正确插件；
- 不保存完整 URL、Cookie 或查询参数；
- 各复制格式的转义、策略组/outbound 名称和条目数；
- 后缀合并必须由显式操作触发。

### 11.4 Wiring 与真实扩展测试

- DI 能解析全局 resolver、系统 adapter、插件网络上下文和域名观察器；
- loader 为不同 extension ID 注入不同上下文；
- 破坏 production wiring 时测试必须失败；
- 使用真实 Keiyoushi JVM 扩展验证列表、章节和图片请求；
- 自定义 client 插件显示正确的受限等级。

### 11.5 UI 验收

- 用户始终能看到已保存和当前生效策略；
- 系统模式针对最近目标显示代理或 DIRECT；
- 手动代理保存错误和连接错误可区分；
- 插件四个选项互斥且状态说明准确；
- 复制按钮生成选定格式并显示条目数；
- 空域名列表、系统策略受限和插件不受管均有可见反馈。

## 12. 非目标

本方案不包含：

- 代理订阅导入；
- 机场节点管理、测速、自动选择或负载均衡；
- 外部代理客户端控制 API；
- 按插件切换或轮换本地端口；
- 自动查询公网出口 IP；
- 通过域名反推插件身份；
- 对插件任意 Socket 的进程内强制拦截；
- 绕过操作系统 VPN、TUN 或透明代理。

以上边界变化需要新的架构决策，不能作为本方案实现时的隐式扩展。

## 13. 设计参考

- [Firefox Connection Settings](https://support.mozilla.org/en-US/kb/connection-settings-firefox)：
  系统、直连、手动代理和 PAC 等模式的互斥表达。
- [JetBrains HTTP Proxy](https://www.jetbrains.com/help/idea/settings-http-proxy.html)：
  自动检测、手动 HTTP/SOCKS 和连接测试的交互。
- [Java ProxySelector](https://docs.oracle.com/en/java/javase/13/docs/api/java.base/java/net/ProxySelector.html)：
  代理可能按目标 URI 解析，不能用单一静态状态代表全部请求。
- [OkHttp Route](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-route/index.html)：
  从真实连接获得代理与 socket route。
- [Mihomo Proxy Ports](https://wiki.metacubex.one/en/config/inbound/port/)：
  外部应用通过固定 HTTP、SOCKS 或 Mixed 入口接入。
- [Mihomo Select](https://wiki.metacubex.one/en/config/proxy-groups/select/)：
  节点和策略组选择由代理客户端内部完成。
- [Mihomo Route Rules](https://wiki.metacubex.one/en/config/rules/)：
  `DOMAIN`、`DOMAIN-SUFFIX`、`IN-PORT`、`IN-NAME` 等路由规则语义。
- [sing-box Selector](https://sing-box.sagernet.org/configuration/outbound/selector/) 和
  [sing-box Route Rule](https://sing-box.sagernet.org/configuration/route/rule/)：
  固定入站、内部 selector 和域名路由结构。
- [v2rayN 系统代理与路由](https://github.com/2dust/v2rayN/wiki/Description-of-system-proxy-routing)：
  系统代理负责导入流量，核心路由负责选择出口。
