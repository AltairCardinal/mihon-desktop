# 测试策略（必读）

## TDD 强制要求
**所有功能变化必须严格执行红绿 TDD 流程，无例外。**

1. **Red**：先写失败的测试，运行确认它因正确原因失败
2. **Green**：写最小实现让测试通过，运行确认全绿
3. **Refactor**：清理代码，重新运行确认仍全绿

**没有对应测试的功能代码 = 不允许提交。**

## 必须有集成测试的场景

| 变更类型 | 所需测试 |
|---|---|
| 新增/修改 Screen 或 Tab | Screen 实例化测试 + 导航类型测试 |
| 新增 `navigator.push(X)` | X 与 navigator 上下文类型兼容性测试 |
| 新增 `Injekt.get<T>()` | DI 绑定解析测试 |
| 修改 HTTP/API 代码 | MockWebServer 测试（成功+失败用例） |
| 新增 domain use case | 单元测试（已有实践） |

## 常见陷阱
`LocalNavigator.currentOrThrow` 在 Tab 的 Content() 中会解析到父 Navigator，而不是 TabNavigator。向 TabNavigator push 非 Tab 的 Screen 会导致 `ClassCastException`。

## 运行测试
```bash
# 单元测试
./gradlew testReleaseUnitTest

# 单个测试类
./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.SomeTest"

# Desktop 测试
./gradlew :app-desktop:jvmTest
```
