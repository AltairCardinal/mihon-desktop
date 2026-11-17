# Desktop 普通已读下载清理

本次仅对齐普通已读删除设置。延迟删除、持久队列、启动清理、24 小时触发、数据库 schema 与同步/备份格式不在实现范围内。

## 设置与迁移

现有“设置 → 下载 → 删除章节”按 Android 顺序消费共享 `DownloadPreferences`：手动标记已读、读完自动删除（-1 关闭，0 当前章，1–4 向前保留对应数量）、允许删除书签、排除分类。分类选择使用草稿，确认才持久化；取消和 Escape 不保存。各项进入现有设置搜索和锚点链路。

`DesktopDownloadPreferences` 构造时，只有共享 `remove_after_read_slots` 从未显式保存才读取旧 `delete_after_read`：true 写入 0，false/未设写入 -1。之后共享设置为唯一权威；重启不会覆盖已有共享值。旧布尔句柄仅为兼容保留，不被生产删除入口消费。

## 候选、时机与保护

Android 与 Desktop 共同消费 `ReadDownloadDeletionPolicy` 的阅读目录过滤、位置候选和书签/分类策略。候选按阅读顺序的位置选择，不按章名或章号重排，也不会跨过未读候选继续寻找更旧章节。Desktop 阅读目录由真实 `ReaderScreenModel` 提供；平台适配只负责将其倒序映射为共享阅读顺序。

阅读进度成功提交后，当前 runtime 捕获有限候选及既有文件。关闭时既要等待已接受的进度队列，也要等待该 runtime 的图片/内容资源释放，才消费这份内存集合。清理前只精确读取候选章节与所属作品分类，新增书签、标回未读或当前分类排除会保护对应文件。不回溯扫描历史已读章节或下载树。

详情、书架及更新页使用唯一 DI `SetChapterReadStatus` 实例。仅配置 Desktop 清理时，该实例串行化手动命令：读取接受时的实际未读子集，写入前捕获有限文件与代次，读态写入成功后执行预备清理。重复显式已读仍保留原有同步写语义，但不重复删除；写入失败不执行文件副作用。这是命令级串行与预捕获边界，不是数据库事务返回转换 ID。Android 未配置此 hook，保留既有读态路径。

书签默认受保护。排除分类使用共享的“任意分类命中即排除”规则，无显式分类按 ID 0 处理，重复分类不增加操作。手动删除文件的原确认入口不变。

## 文件安全与失败

先固定下载 generation，再进行可能挂起的资格/文件查询。复用下载 manager 的有限别名、原 producer 退休与 artifact reservation；后续接受的同章新下载令旧 generation 失效。文件删除只使用预先捕获路径，不以新标题重新定位文件。

同一下载图的 provider 持有 `DesktopDownloadArtifactUsage`。完成目录/CBZ 的真实 materialization 注册跨 runtime 读租约，章节释放、取消或 runtime 关闭会退租；实际文件读取/归档复制另持短租约。清理原子取得删除 reservation 后禁止新读，已有读者则跳过本次清理。不得依赖 Windows 文件锁或操作系统删除失败充当保护。

单个候选失败不阻止本 runtime 其他候选；既有异常链报告实际失败，下载可用性由真实文件反映。内存任务在本次关闭消费，不持久化、无启动重试；占用、保护或新代次导致的跳过不补删后来的文件。应用崩溃可能留下下载，符合本次普通模式的有限生命周期。

## 定向验证

`ReadDownloadDeletionIntegrationTest` 覆盖真实偏好迁移/重建、production DI 手动入口、失败写入、真实 reader 进度/close 的 0–4 选项与候选保护、重下载和跨 runtime 目录/CBZ 占用。`DownloadSettingsDirectoryWiringTest` 挂载真实设置页，点击保留选项、分类确认/取消并核对持久化和搜索。共享策略回归位于 `ReadDownloadDeletionPolicyTest`；Android 的真实 `ReaderViewModel.completionPlan` 接线由 `ReaderProgressProductionWiringTest` 覆盖。

本批涉及偏好、设置、手动命令、阅读器生命周期和文件 adapter，文件数超出一般局部修改估算；这些属于同一用户可见清理行为的必要生产接线与安全验证，不新增独立功能体系。focused 红绿日志位于本工作树 `.gradle-coordinator/read-delete-*.log`，实际结果由本轮完成回执报告。
