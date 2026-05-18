# Mihon Desktop 开发指南

## 构建与部署

### 必须使用构建脚本（不直接调用 gradle）
```bash
./scripts/build-desktop.sh           # 默认：仅更新 git hash
./scripts/build-desktop.sh feature   # 递增功能批次号（7.0 → 7.1）
./scripts/build-desktop.sh stage     # 递增阶段号，重置功能批次（7.x → 8.0）
```

### 手动构建命令
```bash
# 格式检查（必须通过 CI）
./gradlew spotlessCheck

# 自动修复格式
./gradlew spotlessApply

# 运行单元测试
./gradlew :app-desktop:jvmTest

# 构建分发包
./gradlew :app-desktop:createDistributable
```

### 部署步骤
```bash
rm -rf "/Applications/Mihon Desktop.app"
cp -a "/private/tmp/mihon-dist/main/app/Mihon Desktop.app" "/Applications/Mihon Desktop.app"
```

⚠️ **必须先 `rm -rf` 再 `cp`**：Compose Desktop 的 JAR 文件名包含内容哈希，直接覆盖会导致旧 JAR 残留。

## 核心算法

### 双页分组（DualPageState）
- `spreadPages`（宽>高）+ `forcedSinglePages` → 单独显示
- `matchedPairs`（边缘匹配）→ 强制配对
- 默认顺序配对：[0], [1,2], [3,4], ...

### 跨页边缘匹配（EdgePixelMatcher）
- 色差匹配：左右页内侧边缘像素 RGB 差异均值 < 30
- 三重守卫：亮边守卫、暗边守卫、低方差守卫
- 白边装订检测：内侧 20 列 80% 像素 minChannel > 220

## 测试覆盖
```
app-desktop/src/test/kotlin/mihon/desktop/reader/
├── EdgePixelMatcherTest.kt   （色差匹配、白边检测）
└── PhaseEReaderTest.kt       （DualPageState 分组）
```

## UI 参考
有疑问先读 `app/src/main/java/eu/kanade/tachiyomi/ui/` 对应 Android 文件。
