# 测试覆盖率提升 Roadmap

## 目标
将 Mihon 项目测试覆盖率从 ~13% 提升至 30%+

## 严格遵循 TDD 流程
1. **Red** - 先写失败的测试
2. **Green** - 编写最小实现通过测试
3. **Refactor** - 清理代码，重新运行确保通过

## 当前阶段任务

### 第一阶段：基础设施 (必须首先完成)

**1.1 添加 Kover 覆盖率插件**
- 修改 app/build.gradle.kts 添加 Kover 插件
- 版本：org.jetbrains.kotlinx.kover version "0.9.1"
- 注意：mihon 使用自定义 Gradle 插件，需找到正确的添加位置

**1.2 配置 Kover 过滤规则**
- 排除生成的代码 (build/generated)
- 排除 data binding / view binding
- 排除第三方库

**1.3 生成基线覆盖率报告**
- 运行 ./gradlew koverXmlReport
- 确认报告生成成功
- 记录当前覆盖率数值

**1.4 添加测试依赖**
- compose-ui-test-junit4
- activity-compose
- 确保 testInstrumentationRunner 配置正确

---

## 完成标准
每个任务完成后必须：
1. 测试文件存在且有意义的测试用例
2. ./gradlew testDebugUnitTest 通过
3. 更新 roadmap 进度
4. commit 并 push

## 提示
- 先从简单的 ViewModel 测试开始建立信心
- 使用 mockk 进行依赖模拟
- 查看现有 Desktop 测试作为参考