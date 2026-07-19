# Desktop 扩展诊断基线

日期：2026-07-20

## 目标

Windows 扩展加载已输出 `LOAD_OK`、`STUB_MISSING`、`DI_FAILURE`、`LOAD_ERROR`，失败诊断保留 `(jarName, category, errorType, message)`；兼容证据只证明 ABI/runtime 边界，不等于业务语义 parity。

## 失败分类

| 分类 | 含义 | 用户反馈 | 开发者日志 |
| --- | --- | --- | --- |
| `LOAD_OK` | throwable 为 null，Source 已成功实例化 | 无失败反馈 | `jarName`、`category`，`error=null` |
| `STUB_MISSING` | cause chain 存在缺失的 `android.*`/`androidx.*` 类 | 由调用方映射兼容错误 | `jarName`、`category`、`errorType`、`message` |
| `DI_FAILURE` | Injekt/injectLazy 绑定或相关方法解析失败 | 由调用方映射加载错误 | `jarName`、`category`、`errorType`、`message` |
| `LOAD_ERROR` | 其余未归类 throwable，需人工分析 | 由调用方映射加载错误 | `jarName`、`category`、`errorType`、`message` |

## 当前验证入口

```bash
./gradlew :app-desktop:jvmTest --tests "*Extension*"
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionLoaderTest"
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.extension.ExtensionCompatibilityTest"
```

## 持续门禁

- per-JAR 外层失败已由 `recordDiagnostic` 记录完整四元组；ledger 为 45 types＝44 required＋1 unsupported＋0 unverified，覆盖 6 fixtures、9 real test classes/11 tests。`WebView` engine 明确 unsupported，`loadByClassName`/scan 的 class-level `catch-and-skip` 仍是 remaining limitation，在逐类诊断落地前不得宣称闭合。
