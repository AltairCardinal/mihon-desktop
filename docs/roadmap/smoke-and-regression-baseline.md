# Smoke 与回归基线

日期：2026-06-30

## Desktop smoke 基线

后续重构不得破坏以下用户路径：

| 路径 | 预期 |
| --- | --- |
| 启动应用 | Home 可显示，后台服务启动受控 |
| Library | 可查看库、切换分类、搜索、打开详情 |
| Manga Detail | 可查看元信息、章节列表、过滤排序、阅读章节 |
| Reader | 可翻页、记录进度、退出返回 |
| Browse / Extension | 可查看源、扩展列表、进入源浏览 |
| History | 可搜索、删除单条、清空 |
| Migration | 可从源列表进入迁移搜索流程 |
| Backup/Restore | 可创建备份、选择备份文件、显示成功或错误 |
| Downloads | 可查看下载队列和状态 |

推荐验证：

```bash
./gradlew :app-desktop:jvmTest
./gradlew :test-desktop:test
./scripts/desktop-smoke-test.sh
```

## Android 回归基线

从 `app-desktop` 回流功能到 Android 前，必须确认原版 Mihon 下列路径不退化：

| 路径 | 预期 |
| --- | --- |
| Library | 原有库入口和分类行为不变 |
| Browse / Extension | 原有扩展安装、源浏览行为不变 |
| Manga Detail | 原有详情页和章节操作不变 |
| Reader | 原有阅读器设置、进度记录、返回行为不变 |
| Download | 原有下载队列和通知行为不变 |
| Backup/Restore | 原有备份恢复兼容性不退化 |

推荐验证：

```bash
./gradlew testReleaseUnitTest
./gradlew assembleDebug
```

## 记录要求

每次重构完成后，必须在相关任务的进度记录中写明：

- 已运行的 smoke/回归命令。
- 未运行的命令及原因。
- 用户路径是否有可见变化。
