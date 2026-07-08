# Brainstorm Summary

- Change: sync-desktop-missing-chapter-indicators
- Date: 2026-07-05
- Status: 已确认

## 确认的技术方案

完整同步原版 Mihon 的缺话提示行为。Mihon Desktop 书籍详情页在章节过滤与排序之后，将真实章节列表转换为混合显示列表：真实章节行 + 缺话提示行。缺话计算复用 `tachiyomi.domain.chapter.service.calculateChapterGap`，不新增 desktop 专用算法。

desktop 新增全局偏好 `pref_hide_missing_chapter_indicators`，默认不隐藏。Library 设置页提供复选项，开启后书籍详情页不显示缺话提示行。

## 关键取舍与风险

- 复用 domain 算法，避免 Android/Desktop 行为漂移。
- 缺话提示按当前可见章节列表计算，因此过滤后的列表也会产生对应缺口；这与原版 Android 的后过滤插入策略保持一致。
- 缺话提示行仅用于展示，不参与选择、下载、标记已读、收藏、删除或阅读器跳转。
- 当前工作区仍包含已归档的 reader 改动；本 change 只触碰 desktop library/settings 和相关测试，避免混入旧 reader 范围。

## 测试策略

- 先写失败 JVM 测试验证缺话行插入：中间缺口、开头缺口、未知/负数章节号、升降序位置。
- 先写失败偏好测试验证默认显示、设置更新、设置页 wiring。
- 先写失败详情页显示模型测试验证隐藏偏好生效，并确认批量章节操作只使用真实章节。
- 通过 targeted JVM tests、`spotlessCheck` 和 `./scripts/build-desktop.sh` 验证。

## Spec Patch

无。
