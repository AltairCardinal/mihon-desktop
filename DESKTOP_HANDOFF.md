# Mihon Desktop — 项目交接文档

本文档记录 Mihon Desktop 桌面版的完整开发背景，供任何人接手工作时快速了解现状。

---

## 一、Git 仓库结构

### 远程仓库
```
origin  https://github.com/mihonapp/mihon.git
```
本地分支是在 Mihon 官方仓库基础上开发，**不向 origin push**，所有桌面端代码在本地维护。

### 分支
| 分支 | 路径 | 说明 |
|---|---|---|
| `main` | `/Volumes/File/OpenClaw/workspace/mihon` | 跟踪 upstream，保持与官方同步，不做修改 |
| `claude/pensive-vaughan` | `/Volumes/File/OpenClaw/workspace/mihon/.claude/worktrees/pensive-vaughan` | 所有桌面端开发工作在此分支 |

### Git Worktree
桌面端开发使用 git worktree 在独立目录进行，避免污染主工作区：

```bash
# 查看当前 worktree 列表
git worktree list

# 进入开发 worktree
cd /Volumes/File/OpenClaw/workspace/mihon/.claude/worktrees/pensive-vaughan
```

---

## 二、提交历史（桌面端相关）

基准提交（桌面端起点）：
```
bef51fc69  2026-03-18  Add Compose Multiplatform desktop client with KMP module refactoring
```

后续提交（时间顺序）：
```
e21dce871  Decouple desktop reader viewer composables into separate files
369a7f600  feat(desktop-reader): 实现调整跨页匹配与自动判断跨页匹配
ce3bd095d  fix(desktop-reader): 补全白边装订检测，改善自动跨页匹配效果
c66abf7d0  fix(desktop-reader): 修复 matchedPair 被默认顺序配对抢占的 bug
402cd1125  fix(edge-matcher): 修复 isWhiteGutterPair 误判对称白边为扫描跨页
456160522  fix(edge-matcher): 修复 bestEdgeScore 白边距色差=0 导致的第二条误判路径
145ce360f  feat(desktop-reader): 双页匹配算法重写 + 单页智能定位
d7b179d09  docs(changelog): 记录 desktop-0.3.0 版本特性与修复
b70a33726  docs(changelog): 补全 desktop-0.1.0 和 desktop-0.2.0 历史记录
```

完整功能列表见 `CHANGELOG.md`（desktop-0.1.0 起）。

---

## 三、构建与部署

### 构建命令
```bash
cd /Volumes/File/OpenClaw/workspace/mihon/.claude/worktrees/pensive-vaughan

# 格式检查
./gradlew spotlessCheck

# 自动修复格式
./gradlew spotlessApply

# 运行单元测试
./gradlew :app-desktop:jvmTest

# 构建分发包
./gradlew :app-desktop:createDistributable
# 产物：/private/tmp/mihon-dist/main/app/Mihon Desktop.app
```

### 部署（重要：必须先删除旧版本）
```bash
rm -rf "/Applications/Mihon Desktop.app"
cp -a "/private/tmp/mihon-dist/main/app/Mihon Desktop.app" "/Applications/Mihon Desktop.app"
```

> ⚠️ **必须先 `rm -rf` 再 `cp`**，不能直接 `cp -a` 覆盖。
> 原因：Compose Desktop 的 JAR 文件名包含内容哈希，每次构建产生的 JAR 文件名不同。
> 直接 `cp -a` 只会添加新文件而不删除旧文件，导致旧 JAR 残留并被启动器加载，
> 新代码完全不生效。

### 验证部署成功
修改 `Main.kt` 中的窗口标题加版本号，构建后确认标题已更新。

---

## 四、项目架构

### 模块结构
| 模块 | 说明 |
|---|---|
| `app-desktop/` | 桌面端主模块（Compose Multiplatform JVM 目标） |
| `domain/` | 业务逻辑（KMP，commonMain + jvmMain） |
| `data/` | 数据层，SQLDelight（KMP） |
| `core/common/` | 共享工具（KMP） |
| `source-api/` | 源抽象接口（KMP） |
| `app/` | Android 端（保持原样，不修改） |

### 桌面端包结构
```
mihon.desktop
├── di/                    依赖注入（Injekt）
├── domain/                桌面专属用例
│   ├── AddMangaToLibrary
│   ├── DesktopCategoryManager
│   ├── LibrarySearchFilter
│   ├── LibraryUpdateChecker
│   └── ReaderProgressTracker
├── download/              下载管理器
├── extension/             扩展加载
├── platform/              平台实现（网络等）
├── reader/                阅读器核心逻辑（无 UI）
│   ├── DualPageState      页面分组算法
│   ├── EdgePixelMatcher   跨页边缘匹配
│   ├── ReaderKeyboardAction
│   ├── ReaderNavigator
│   └── ReaderPreferences
├── source/                MangaDex 内置源
└── ui/                    所有 Compose UI
    ├── home/              主屏幕 + 五个标签页
    ├── reader/            阅读器界面
    │   ├── DesktopReaderScreen.kt
    │   ├── DualPagePagerViewer.kt
    │   ├── SinglePagePagerViewer.kt
    │   ├── WebtoonViewer.kt
    │   ├── ZoomablePageBox.kt
    │   └── ReaderBottomBar.kt
    ├── library/
    ├── browse/
    ├── downloads/
    └── settings/
```

---

## 五、核心算法设计说明

### 5.1 双页分组算法（DualPageState）

**位置**：`app-desktop/src/main/kotlin/mihon/desktop/reader/DualPageState.kt`

**三级优先级**：
1. `spreadPages`（图片宽>高的横版跨页）+ `forcedSinglePages`（用户手动强制单页）→ 单独显示
2. `matchedPairs`（EdgePixelMatcher 识别的匹配对）→ 强制配对
3. 默认顺序配对：[0], [1,2], [3,4], ...

**智能奇偶重置**：
横版跨页在实体书中占两页物理位置，数字化后被压缩为一页，导致后续页面配对偏移。
解决方案：跨页后统计连续普通页数量（`countRunAfter`），奇数才插入落单页，偶数直接配对。

**单页定位（SinglePageSide）**：
- `TRAILING`：页面后面有内容 → 放在阅读方向末尾侧（RTL=物理左，LTR=物理右）
- `LEADING`：页面后面无内容（末尾孤页）→ 放在阅读方向首侧
- `CENTER`：横版跨页 → 全幅居中

**UI 实现**：使用 `Box(contentAlignment = Alignment.CenterEnd/CenterStart)`，
`CenterEnd/CenterStart` 是 Compose 方向感知 API，RTL/LTR 下自动映射到正确物理位置，
代码中无任何 `isRtl` 判断。

### 5.2 跨页边缘匹配算法（EdgePixelMatcher）

**位置**：`app-desktop/src/main/kotlin/mihon/desktop/reader/EdgePixelMatcher.kt`

判定两页是否为同一物理跨页的扫描结果，满足任一条件即判定为跨页：

**条件 A — 色差匹配**：
取左页右侧边缘列与右页左侧边缘列的像素，计算逐像素 RGB 绝对差的均值。
均值 < `threshold`（默认 30）即认为颜色连续。

三重守卫（任一命中则拒绝）：
1. **亮边守卫**：双侧 80% 以上像素亮度 ≥ 240（纯白边框）→ 拒绝
2. **暗边守卫**：双侧 80% 以上像素 max(R,G,B) < 50（纯黑边框）→ 拒绝
3. **低方差守卫**：双侧像素亮度方差均 < 100（纯色均匀区域）→ 拒绝

**条件 B — 白边装订检测**：
扫描版经过清理后，两页内侧装订处往往为纯白区域。
对内侧取 20 列多行采样，80% 以上像素 minChannel > 220 即判定为白边装订。
双侧均为白边时仍拒绝（避免两页各有白色页边误判）。

**横版跨页判定**：图片 `width > height`（严格大于，方形图不算）。

### 5.3 RTL/LTR 方向处理原则

- `HorizontalPager` 通过外层 `CompositionLocalProvider(LocalLayoutDirection)` 切换方向，不反转页面 URL 顺序
- `Alignment.CenterEnd/CenterStart` 在 Compose 中是方向感知 API，RTL/LTR 下自动映射，无需 `isRtl` 条件判断
- `DualPageState` 和 `EdgePixelMatcher` 是纯 Kotlin，零方向依赖

---

## 六、未来规划（尚未实现）

以下功能在 Android 版 Mihon 中存在，桌面版尚未实现，按优先级排列：

### 高优先级
- [ ] **页面操作菜单**：右键/长按页面，弹出「保存图片」「设为封面」「分享」等选项
- [ ] **白边裁剪（Crop Borders）**：自动裁去扫描版四周多余白边

### 中优先级
- [ ] **更多阅读器设置**：缩放起始位置、双击缩放速度、阅读器背景色、网络漫画侧边留白
- [ ] **压缩文件/目录本地源**：直接读取设备上的 zip/cbz/目录（无需下载）
- [ ] **颜色滤镜**：护眼模式、亮度调节

### 低优先级
- [ ] **页面加载指示器**：图片加载中时显示占位动画
- [ ] **阅读统计**：记录每本漫画的阅读时长
- [ ] **书签系统**：收藏特定页面

---

## 七、测试

单元测试覆盖核心算法：
```
app-desktop/src/test/kotlin/mihon/desktop/reader/
├── EdgePixelMatcherTest.kt   （色差匹配、白边检测、三重守卫）
└── PhaseEReaderTest.kt       （DualPageState 分组、奇偶重置、单页定位）
```

运行：
```bash
./gradlew :app-desktop:jvmTest
```

测试数据：Chainsaw Man Vol.1 Ch.1（53页，含横版跨页和方形封面）
路径：`/Users/altair/.mihon/downloads/2499283573021220255/Chainsaw Man/Vol.1 Ch.1_ A Dog and a Chainsaw/`

---

## 八、开发规范

1. **UI 必须复刻 Android 版**：有疑问先读 `app/src/main/java/eu/kanade/tachiyomi/ui/` 对应文件
2. **每次完成开发后自行构建部署**：无需等待用户指令，见第三节
3. **完成报告格式**：见 `CLAUDE.md`，须包含功能特性、BUG 修复、验收清单三部分
4. **测试策略**：见 `CLAUDE.md` Test Policy 章节，导航/DI/HTTP 变更须有集成测试
