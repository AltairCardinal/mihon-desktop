# Mihon 项目概览

## 项目背景
Mihon 是一个开源漫画阅读器 fork，从 Tachiyomi 发展而来。项目包含 Android 端和 Desktop 端（Compose Multiplatform）。

## 关键文档
- `CLAUDE.md` - 开发规范、TDD 流程、测试策略（必读）
- `DESKTOP_HANDOFF.md` - 桌面端项目交接文档
- `ROADMAP.md` - 桌面版功能追赶路线图
- `extensions_desktop_api_migration.md` - 扩展 JVM 构建迁移计划

## 语言要求
与用户的所有交流使用**中文**。

## 架构概览
| 模块 | 说明 |
|---|---|
| `app/` | Android 端（保持原样，不修改） |
| `app-desktop/` | 桌面端（Compose Multiplatform JVM） |
| `domain/` | 业务逻辑（KMP，commonMain + jvmMain） |
| `data/` | 数据层，SQLDelight（KMP） |
| `source-api/` | 源抽象接口（KMP） |

## 桌面端开发位置
```
/Volumes/File/OpenClaw/workspace/mihon/.claude/worktrees/pensive-vaughan
```

## Git 结构
- `main` 分支：跟踪 upstream，保持同步
- `claude/pensive-vaughan` 分支：所有桌面端开发工作
