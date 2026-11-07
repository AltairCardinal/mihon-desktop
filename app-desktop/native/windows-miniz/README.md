# Windows 下载 CBZ reader 后端

此目录是 Windows x64 可选优化的完整构建输入。其他平台、非下载本地源和不符合格式的归档继续使用已有后端；不创建持久化索引，不提前打开用户章节。

## 来源与重建

- 上游：<https://github.com/richgel999/miniz/tree/174573d60290f447c13a2b1b3405de2b96e27d6c>，miniz 3.1.0，固定提交 `174573d60290f447c13a2b1b3405de2b96e27d6c`。
- `miniz/` 来自项目已保存的 `windows-reader-cbz-prototype.zip`，包 SHA-256 为 `716d54e3515b33a1dd8afe06252879556acb8837204b215365a688c9757f6733`；C 源及头文件未修改。`miniz_export.h` 是该原型生成的静态构建导出宏。
- `miniz/` 使用局部 Git 属性保留上游字节（包括原行尾和空白），避免 Windows 检出改变固定构建输入。
- 许可证为 `miniz/LICENSE`。构建同时将许可证和构建清单放入应用资源。
- `reader_miniz.cpp` 是产品 JNI 边界，不依赖实验 DLL、类替换 agent、Kotlin 默认构造器掩码或机器绝对路径。

Windows 需要本机 MSVC x64 Build Tools、Windows SDK、带 Windows JNI 头文件的 JDK，以及 Python 3.11+。不联网下载构建工具或依赖。在仓库根运行：

```powershell
python scripts/build-windows-reader-miniz.py --output app-desktop/build/generated/reader-miniz-appResources/windows/mihon-reader-miniz.dll
```

Gradle 的 `buildWindowsReaderMiniz` 为 JVM 定向测试和 `prepareAppResources` 调用同一个入口。普通 preview 自动携带生成的 DLL；macOS 资源链保持原状。使用 `/MT` 将 MSVC C 运行库静态链接，仅依赖 Windows 系统库 `bcrypt.dll`、`KERNEL32.dll`。构建清单记录源文件摘要、工具链、ABI、固定 miniz 提交和实际 DLL 摘要，打包时保留。

## 格式和身份边界

只处理本地下载 CBZ、Store/Deflate、根目录等宽纯数字小写 `.webp` 页名，可有一个 `ComicInfo.xml`。按中央目录原序计算现有指纹，以带符号 CRC、原方法名和分隔符保持旧 URL 身份；排序后的名称和 SHA 标识批量返回，真实 `ReaderPageDescriptor` 由 Kotlin 创建。

全档接受前检查中央目录和 local header 的名称、flags、方法及 extra；拒绝目录、重复条目、加密、非标准名称、ZIP64、可改变名称解释或未知的 extra。仅容许结构有效的 UT 时间戳 `0x5455` 和 ux UID/GID `0x7875`，因为目标实物含这两类不改变名称的字段。条目数最多 65535，原始页名字数最多 32，元数据最多 16 MiB、单条 extra 最多 4096 字节；不满足时整档回退。没有每页 64 MiB 解压限制。

## 加载、句柄与失败处理

真实 owner 内容首次 `drawContent` 完成后在低优先级后台线程幂等加载，只读取 `compose.application.resources.dir` 中固定 DLL 名，并校验 ABI 和 miniz 提交。等待帧开始不能代替真实内容已绘制；boot/失败界面不发通知。状态为未开始、加载中、就绪、不可用；章行点击不等待加载，未就绪直接完成本次旧后端打开，后续新开章才选择就绪后端。不从 PATH、临时目录或外置实验位置加载。

JNI 只交付单调递增的注册表 ID，不暴露裸指针所有权。每个 lease 在同一 Windows 文件句柄上读取目录和复制页，允许文件路径被替换但保持旧 handle 指向原文件。Kotlin `RefCountedArchiveLease` 保护操作、切代和释放；native 注册表的 shared ownership 为关闭与在途操作提供第二层保护。

目标页按真实条目 index 使用 miniz callback 流式写入 encoded store 分配的临时文件，检查输出偏移、写入数、完整长度、CRC、关闭结果及线程中断。读中损坏、取消或写入失败沿原 adapter/store 错误链清理临时页，不能重开档案续读旧标识。只有发布页表前的不支持格式和加载不可用允许回退。

`ARCHIVE_BACKEND_SELECT_BEGIN` 在相同选择入口标记，包含 readiness 检查、解析和回退；实际后端标为 `ARCHIVE_BACKEND_MINIZ`、`ARCHIVE_BACKEND_COMMONS` 或 `ARCHIVE_BACKEND_SEVENZIP`。这些事件服从原有默认关闭的 startup tracing，不扩展常规日志负担。

定向回归为 `DesktopReaderWindowsMinizTest`、`DesktopReaderContentAdapterTest`、`DesktopReaderCbzFastPathTest`、`DesktopReaderMaterializePortsIntegrationTest`，真实启动 draw 接线由 `DesktopReaderMinizStartupTest` 覆盖。真实 187 页身份、逐页图像 SHA 和地图 SHA 检查通过 `MIHON_CBZ_DIAGNOSTIC` 指向本地实物后运行一次，性能采样复用其正确性证据；漫画文件不纳入 Git。DLL 构建或 JVM 测试成功不代表预览包已在正常窗口命中、性能达标或用户验收通过。
