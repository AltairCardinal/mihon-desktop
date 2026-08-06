# 阅读器本地图像恢复设计

## 状态与目的

本文记录低质量漫画扫描页的本地画质恢复方案、候选开源算法、产品边界和后续实现约束。当前状态为
`DESIGN_ONLY / NOT_IMPLEMENTED`：本文不是实现计划的完成声明，仓库当前也没有内置图像恢复模型或推理
runtime。

该能力的目标是提高低质量漫画的可读性，同时把改变作者线条、文字、网点和页面布局的风险控制在用户可见、
可关闭、可回退的边界内。所有分析和推理都必须在用户设备本地完成：

- 不调用在线图片、多模态或大语言模型 API；
- 不上传漫画页、缩略图、OCR 文本、模型输入、模型输出或质量诊断；
- 网络只允许沿现有漫画源链路获取原始页面，以及按明确的模型分发策略下载可验证的本地模型；
- 离线时已经安装的模型和确定性处理仍可工作；没有模型时必须回退原图，而不是伪装为增强成功。

本文描述长期维护规则，因此实现时必须同步更新本文、阅读器架构文档、用户设置说明和对应验证证据。

## 问题定义

目标样本代表的是混合退化，而不是单一的“分辨率不足”：

- 页面带有紫褐或黄褐色偏色；
- 纸张、拍摄或扫描造成低频灰雾和不均匀照明；
- 黑白点没有充分展开，明暗动态范围被压缩；
- 印刷半色调、扫描采样和显示缩放叠加，产生网点、摩尔纹或伪纹理；
- 文字、照片格和线稿存在不同程度的模糊及压缩损伤；
- 跨页装订缝可能带有阴影、高光和几何变化；
- 同一跨页中可能同时存在亮底线稿和作者有意设计的暗底页面。

因此“最佳画面”必须先区分两个目标：

1. **保真恢复**：提高可读性，但不主动创造原图无法支持的文字、线条或网点；这是默认目标。
2. **强力增强**：允许模型推断缺失的细节，结果可能偏离原稿；只能由用户显式开启并看到风险提示。

任何自动选择都不能把暗底设计误判为需要整体漂白的扫描缺陷。没有原稿或高质量参照时，不存在能够证明唯一
“正确还原”的客观答案。

## 当前阅读器能力与立即可用设置

当前共享 `ReaderColorFilterParams` 只声明色调、亮度、灰度和反相。Desktop 的亮度与色调通过半透明纯色
overlay 实现；灰度和反相通过 `ColorMatrix` 实现：

- [`PageTransform.kt`](../../domain/src/commonMain/kotlin/mihon/domain/reader/PageTransform.kt)；
- [`ReaderVisualComponents.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderVisualComponents.kt)；
- [`ReaderSettingsPanel.kt`](../../app-desktop/src/main/kotlin/mihon/desktop/ui/reader/ReaderSettingsPanel.kt)。

亮度 overlay 的效果可近似写为：

```text
调亮：output = input * (1 - amount) + white * amount
调暗：output = input * (1 - abs(amount))
```

两者都会缩小像素之间的明暗差，不能替代黑白点、曲线、伽马、白平衡、去网纹、去噪、去模糊或超分辨率。
RGBA 色调 overlay 同样不能展开动态范围。

在新能力实现前，对上述混合退化页面建议：

| 设置 | 建议 | 边界 |
| --- | --- | --- |
| 灰度 | 开启 | 去除扫描偏色，但不恢复层次 |
| 亮度 | 关闭，数值保持 `0%` | 非零值会进一步压低对比度 |
| 色彩滤镜 | 关闭 | 只会叠加颜色，不做白平衡 |
| 反相 | 默认关闭 | 可临时帮助阅读单独的暗底页，但会破坏亮底线稿 |
| 拆分宽页 | 开启 | 原图为跨页时增加单页有效显示面积 |
| 双页并排 | 关闭 | 原始位图已经是跨页时避免再次缩小 |
| 缩放方式 | 适应宽度 | 优先保证小字和细线的显示尺寸 |
| 裁剪边框 | 视页面而定 | 当前 `CropBorderScanner` 只裁近白边，不能裁黑色外框 |

恢复算法必须处理阅读器拿到的原始 encoded page，而不是处理 Compose 渲染后的屏幕截图。截图已经混入窗口背景、
显示缩放、色彩管理和可能的二次压缩，不能作为 production 恢复输入。

## 产品入口与反馈

这是用户可见 capability，不能只增加后台模型或隐藏偏好。

### 建议入口

在阅读器设置的“滤镜/画面”区域增加“本地画质恢复”，提供：

- `关闭`：直接显示原图；
- `保真清理`：默认推荐，只启用低幻觉处理和置信度回退；
- `强力增强`：允许重建网点与推断细节，首次开启显示明确说明；
- `对比原图`：按住或切换查看相同 viewport 的原始页面；
- `仅本漫画` / `全局默认`：沿用现有阅读器偏好分层，不把一次选择隐式应用到全部作品；
- `清除增强缓存`：只删除派生结果，不删除下载页或阅读进度。

若模型不是随应用分发，设置页必须显示模型大小、许可证摘要、校验状态和删除入口。安装模型属于可恢复操作，
无需危险确认；删除派生缓存也不影响原始漫画。若未来允许删除模型文件，应说明重新使用时需要再次下载。

### 运行反馈

页面必须区分以下状态：

- `原图`；
- `等待恢复`；
- `正在本地处理`，可取消；
- `已增强`，可立即切回原图；
- `本页已回退原图`，并给出简短原因，例如模型不可用、显存不足或质量门禁拒绝；
- `模型校验失败`，不得继续加载该模型。

恢复任务不能阻塞当前页首次显示。当前页原图 Ready 后立即可读，增强结果晚到时只替换稳定 display unit 内的
内容，不替换 pager、Lazy item、zoom container 或页身份。

## 架构边界

本能力追加到现有 canonical reader 链路，不建立第二套 source、下载、session、scheduler 或进度系统。

```text
ReaderSessionCore Ready(encodedRef)
              │
              ▼
     platform decoded-page adapter
              │
              ├──────────────► 原图立即显示
              │
              ▼
 LocalPageRestorationPort
  normalize / segment / restore / quality gate
              │
              ▼
  derived restored-page cache
              │
              ▼
 stable DisplayUnit 内切换位图
```

共享层只拥有可序列化的策略、状态和诊断，不引用 Bitmap、Skia、OpenCV、CUDA、Vulkan、文件路径或具体模型：

- `ReaderRestorationMode`：`OFF / FAITHFUL / STRONG`；
- `ReaderRestorationRequest`：稳定 `PageId`、encoded 内容版本、模式和模型版本；
- `ReaderRestorationResult`：成功、用户取消、资源不足、模型不可用、质量拒绝、解码失败；
- 缓存 key 和失效规则；
- 当前页与附近页的调度优先级及 generation 迟到拒绝。

平台 adapter 负责：

- decoded bitmap 与推理 tensor 的转换；
- 本地模型加载、GPU/CPU backend 和分块推理；
- 派生缓存文件；
- 平台资源监控、取消和诊断；
- 在 stable display unit 中切换原图与增强图。

恢复不得改变：

- `ReaderPageId`、章节页序、宽页切片身份或双页配对；
- encoded page store 中的源文件；
- 阅读进度、历史、下载状态或 source 请求；
- 右键“保存原图”的语义。若增加“保存增强图”，必须是明确的第二个动作。

## 本地图像恢复算法

### 总体原则

默认算法采用“确定性低频校正 + 内容分区 + 专用恢复分支 + 不确定度融合 + 结构质量门禁”。生成式模型不能
直接接管整页，也不能修改文字区域。

### 阶段 0：输入与几何预处理

1. 从 encoded page 解码到保留原始位深和色彩 profile 的位图。
2. 检测外部纯色/近纯色边框，但不要复用只识别近白色的现有裁边规则作为唯一依据。
3. 使用宽高比、中心装订缝证据和现有 spread 信息判断是否跨页；优先消费 reader 已确认的宽页拆分边界。
4. 保存原始坐标到处理坐标的映射，确保 viewport、保存和调试可以追溯。
5. 装订缝只生成低置信度 mask；除非用户选择强力增强，不做内容补画。

### 阶段 1：色彩与照明归一化

1. 转换到线性亮度与色度分离的工作空间。
2. 从低色度、高亮度、低局部梯度区域估计纸张颜色；排除纯黑外框、暗底页面和高光。
3. 用大尺度引导滤波、形态学 opening 或低阶曲面估计 illumination field。
4. 对亮度做除法/对数域归一化，对色度做受限白平衡；校正量必须有上限。
5. 以稳健分位数估计黑白点，不允许极少量高光或黑框控制整页曲线。
6. 对局部对比度使用受限 CLAHE 或等价算法；tile 边界必须平滑，clip limit 由噪声估计约束。

本阶段决定页面整体观感，必须完全确定性，不能依赖生成式模型。

### 阶段 2：内容与退化分区

为每个像素或 patch 估计以下 soft mask：

- 文字和对白框；
- 主轮廓/细线稿；
- 规则网点；
- 照片或连续灰阶区域；
- 平坦亮底；
- 平坦暗底；
- 装订缝、阴影和高光；
- 水印或来源标识，默认保留，不作为清理目标。

特征至少包含局部梯度、连通结构、FFT 周期能量、色度、局部方差和多尺度亮度。首版可由确定性特征与轻量
segmentation CNN 组合；不能仅依赖一个整页分类标签。

### 阶段 3：区域专用恢复

#### 文字与线稿

- 使用保边去噪、受限反卷积或以 L1/Charbonnier + edge loss 训练的非 GAN 网络；
- 保持字符连通分量数量、主要骨架和端点关系；
- 禁止 diffusion、GAN 细节生成和自由 inpainting；
- 恢复前后不能出现新的文字形状；质量门禁不通过即回退确定性结果。

#### 规则网点

- 在频域估计网点主频率、方向和 aliasing 置信度；
- 参考 MangaRestoration 的 SE-Net/MR-Net 思路，先估计目标尺度，再按区域退化程度恢复；
- 只有 pattern-identifiable 区域允许恢复离散网点；
- pattern-agnostic 区域在保真模式转为稳定连续灰阶，不合成一种未经输入支持的网点；
- 强力增强模式可以合成网点，但 UI 和输出元数据必须标记为推断结果。

#### 照片与连续灰阶格

- 先做 inverse halftoning/descreen，避免超分模型把印刷点当作真实纹理；
- 使用非 GAN 或降低 denoise strength 的盲超分网络；
- 人脸增强模型不适用于漫画角色，禁止自动启用；
- 高频输出受原图低频亮度和结构边缘约束。

#### 暗底页面

- 单独估计黑白点并保持极性；
- 只增强文字与背景之间的局部可读性，不把整块暗底映射成白纸；
- 与相邻亮底页的处理参数解耦，避免跨页全局直方图造成一侧失真。

### 阶段 4：不确定度融合

维护两条输出：

1. `conservative`：确定性色彩、照明、曲线和保边处理；
2. `learned`：网点、去模糊、逆半色调和超分模型输出。

融合权重来自区域类型、退化程度和模型不确定度，而不是固定 alpha。文字区域默认使用 conservative；规则网点只在
频率和结构置信度高时采用 learned；装订缝和无法分类区域回退原图或 conservative。

tile 必须带 overlap，并使用平滑权重拼接。模型不能看到不同 tile 时生成互相矛盾的网点频率；网点频率估计应在
整页或足够大的共享上下文上完成，再作为每个 tile 的条件。

### 阶段 5：质量门禁

增强结果在进入显示缓存前必须满足：

- 页面尺寸、方向、alpha 和坐标映射有效；
- 低频明暗结构没有超过模式允许的变化；
- 文字连通分量、骨架和局部边缘拓扑没有异常新增或消失；
- 直线和 panel 边界没有明显波浪、断裂或重复；
- 相邻 tile 的亮度、梯度和网点频率连续；
- 输出不存在 NaN、全黑、全白、异常饱和或明显棋盘格；
- 处理时间、内存和缓存大小未超过预算。

门禁失败是正常的可恢复结果：记录本地诊断，显示原图，不重试同一输入/模型/参数组合。不能为了提高“增强成功率”
降低文字和结构门禁。

## 候选开源算法与采用策略

### MangaRestoration / Exploiting Aliasing for Manga Restoration

- 资料：
  - [论文项目页](https://ttwong12.github.io/papers/mangarestore/mangarestore.html)
  - [官方实现](https://github.com/msxie92/MangaRestoration)
  - [CVPR 2021 论文](https://openaccess.thecvf.com/content/CVPR2021/papers/Xie_Exploiting_Aliasing_for_Manga_Restoration_CVPR_2021_paper.pdf)
- 价值：直接针对错误缩放导致的二值漫画网点 aliasing；SE-Net 估计目标尺度，MR-Net 区分可识别和不可识别网点。
- 风险：不可识别区域会合成视觉合理网点，不等同于恢复原稿；旧 PyTorch 环境和模型转换需要工程验证。
- 采用：吸收尺度估计、区域置信度和网点恢复思想；保真模式禁止其 pattern-agnostic 合成路径。
- 许可证：仓库声明学术和商业用途均获得许可，但集成前仍需独立核对 LICENSE、权重来源及传递依赖。

### MangaJaNai

- 资料：
  - [模型仓库](https://github.com/the-database/MangaJaNai)
  - [许可证](https://github.com/the-database/MangaJaNai/blob/main/LICENSE)
- 价值：模型专门面向约 1200～2048 像素高的黑白数字漫画，并针对 JPEG、摩尔纹和退化网点。
- 风险：项目明确说明模型可能重新生成输入中已经不可见的半色调点；权重采用 CC BY-NC 4.0，限制商业用途。
- 采用：仅作为离线研究基准和训练方向参考，不随 Mihon 默认分发，也不作为 production 自动下载源。

### DocRes

- 资料：
  - [官方实现](https://github.com/ZZZHANG-jx/DocRes)
  - [CVPR 2024 论文](https://openaccess.thecvf.com/content/CVPR2024/papers/Zhang_DocRes_A_Generalist_Model_Toward_Unifying_Document_Image_Restoration_Tasks_CVPR_2024_paper.pdf)
- 价值：统一支持 appearance enhancement、deshadowing、deblurring、binarization 和 dewarping，MIT 许可。
- 风险：训练目标是文档，不理解漫画网点、照片格和作者有意设计的暗底页面。
- 采用：作为照明/外观恢复的对比基线；不能直接把 end-to-end 输出作为默认漫画结果。

### Real-ESRGAN / RealESRNet

- 资料：
  - [官方实现](https://github.com/xinntao/Real-ESRGAN)
  - [ncnn Vulkan 实现](https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan)
- 价值：BSD-3-Clause；支持灰度图、FP16、tile、便携 NCNN/Vulkan 推理和可调 denoise strength。
- 风险：通用 GAN 输出可能过度锐化、改变细线或制造纹理；动画小模型也不等于扫描漫画模型。
- 采用：优先评估非 GAN `RealESRNet` 或低强度恢复作为照片格分支；禁止自动启用 face enhancement。

### APISR

- 资料：[官方实现](https://github.com/Kiteretsu77/APISR)
- 价值：面向真实退化动画图像的超分研究，可参考 degradation synthesis。
- 风险：不是扫描漫画专用；GPL-3.0 且仓库另有学术用途免责声明，会增加分发和合规边界。
- 采用：只作为研究对照，不列入首版 production 候选。

### 传统图像处理

- [OpenCV CLAHE 文档](https://docs.opencv.org/4.x/d5/daf/tutorial_py_histogram_equalization.html)
- [OpenCV CLAHE API](https://docs.opencv.org/4.x/d6/db6/classcv_1_1CLAHE.html)

CLAHE、稳健黑白点、引导滤波、形态学背景估计、FFT 网点检测、Wiener/受限反卷积和保边去噪应构成默认保真
管线的基础。传统算法可解释、可做像素级回归测试且不会语义重画，但必须防止放大纸张噪声和网点。

## 本地推理与 RTX 3070 8GB 预算

### Runtime 选择

优先评估 [ncnn](https://github.com/Tencent/ncnn)：

- BSD-3-Clause；
- 支持 Windows、Android、CPU 和 Vulkan GPU；
- 支持 FP16 storage/arithmetic、显式内存 allocator 和 PyTorch/ONNX 转换工具；
- 可将平台差异限制在 native adapter，而不让共享 reader core 依赖 CUDA 或 Windows API。

Desktop 可以先以受控 native sidecar 验证模型，再决定稳定 ABI/JNI 形态；实验 sidecar 不能成为绕过应用生命周期、
缓存、取消和诊断的长期第二套服务。Android adapter 必须消费相同策略和结果 contract，但可根据设备能力只提供
确定性处理或 CPU/Vulkan 小模型。

不选择 CUDA-only 作为共享 production 契约。RTX 3070 可以使用 Vulkan FP16；其他厂商 GPU、无 Vulkan 或驱动
失败时必须有 CPU/原图回退。

### 初始资源预算

以下是待实机基准校准的安全起点，不是性能承诺：

- batch size：`1`；
- 精度：优先 FP16，质量门禁失败时针对相关模型回到 FP32 验证；
- 输入 tile：`256～384 px`；
- overlap：`32～64 px`，至少覆盖模型有效感受野和拼接羽化区；
- 同时驻留模型：最多一个主恢复模型，分支模型按需串行；
- GPU 显存硬上限：不得占满 8GB，需给渲染、驱动和其他应用预留空间；
- 当前页优先，下一 display unit 低优先预处理；不批量预处理整章 decoded bitmap；
- 用户翻页、切章、关闭阅读器或切换模式后取消旧 generation，迟到结果不得覆盖新页。

遇到 OOM 时依次：减小 tile → 关闭 learned 分支并保留确定性结果 → 回退原图。禁止无限自动重试或让 GPU OOM
终止阅读器进程。

## 缓存与模型生命周期

恢复结果是可丢弃派生数据，不能写回原始下载文件。建议缓存 key：

```text
SHA-256(encoded page bytes)
+ restoration mode
+ deterministic pipeline version
+ model id and model digest
+ normalized parameters
+ output scale/profile
```

缓存必须：

- 有独立配额和 LRU；
- 与 encoded page store 分开统计和清理；
- 模型、算法、参数或色彩 profile 改变时自然失效；
- 校验读取结果的尺寸、格式和 digest；
- 只缓存通过质量门禁的结果；
- session 结束可释放 decoded bitmap，但不要求删除磁盘派生结果。

模型包必须包含固定 model id、版本、SHA-256、许可证和适用输入说明。校验失败、许可证信息缺失或 runtime 不支持
时不得加载。模型升级失败时保留旧的已验证版本；没有可用版本则回退原图。

## 数据与训练约束

若现有权重不能满足保真门禁，应训练 Mihon 专用的轻量模型，但数据来源必须先解决：

- 训练数据和高质量目标必须有明确的训练、再分发和商业使用授权；
- Manga109 等学术数据集不能仅因公开下载就假定可随产品模型自由使用；
- 合成退化应覆盖 JPEG/WebP、错误缩放、扫描 blur、纸张纹理、偏色、低频照明、半色调 aliasing 和装订缝；
- 训练时保留文字/线稿/网点区域标签或伪标签，不能只优化整页感知分数；
- 保真模型以 L1/Charbonnier、结构/edge、频域网点一致性和文字拓扑损失为主；
- GAN 或 diffusion loss 只允许用于显式强力增强模型，且模型、缓存和 UI 状态与保真模型分开。

不能用 OCR 文本正确率作为唯一目标，因为 OCR 可能偏好被模型改写得更像常见字形的错误结果。

## 验证策略

实现必须严格按红→绿→重构推进，并覆盖真实 production wiring。

### 算法单元与契约测试

- 照明归一化不改变纯色暗底的极性；
- 黑框、白边、跨页和装订缝 mask 使用固定 fixture 验证坐标；
- 不同 tile/overlap 产生无可见接缝的确定性输出；
- 同一输入、模型和参数生成稳定 cache key；
- 模型版本改变后旧缓存不被误用；
- 取消、OOM、坏模型、坏输出和质量拒绝均返回明确 result；
- 文字/线稿 fixture 的连通结构和边缘拓扑不越过阈值；
- pattern-agnostic fixture 在保真模式不生成离散网点。

### 平台集成测试

- 初始化相关 DI 后可以解析恢复 port、模型 registry、缓存和 resource policy；
- 从真实 encoded page ref 解码、处理、质量门禁、缓存，再进入 mounted viewer；
- 断开 production restoration wiring 后测试必须失败，不能只扫描源码符号；
- 原图先显示，增强结果在同一 `DisplayUnitId` 和 zoom container 内替换；
- 快速翻页后旧 generation 结果不会覆盖当前页；
- 清除派生缓存不删除 encoded page、下载记录或阅读进度；
- 保存原图仍保存源页面，保存增强图只由独立动作触发；
- 无 Vulkan、驱动初始化失败、显存不足和模型校验失败均显示原图及可理解反馈。

### 质量评估集

建立经授权的固定 fixture 集，至少包含：

- 清晰数字黑白漫画，验证不应处理时接近 identity；
- 低对比偏色扫描；
- 规则网点和已 alias 网点；
- 照片格与线稿混排；
- 亮底/暗底跨页；
- 小字号中日韩文字；
- 装订缝阴影、高光和黑外框；
- JPEG/WebP 压缩、不同源尺寸和已有超分页面。

不能仅用 PSNR、SSIM、NIQE 或感知指标宣称质量最佳。必须同时评估低频保真、边缘/文字结构、网点频率、
tile 连续性、人工 A/B 偏好、处理延迟、峰值显存和回退率。

## 分阶段落地建议

### 阶段 A：确定性保真清理

- 增加本地恢复 contract、UI 状态、派生缓存和原图对比入口；
- 实现色彩/照明归一化、稳健黑白点、受限局部对比度和保边去噪；
- 不引入神经网络；
- 以低风险能力验证稳定身份、取消、缓存和回退链路。

### 阶段 B：内容分区与本地专用模型

- 增加文字/线稿/网点/照片/暗底 soft mask；
- 接入 ncnn Vulkan/CPU adapter；
- 先评估网点恢复和非 GAN 照片超分分支；
- 完成 RTX 3070 8GB、无独显和 Android 代表设备的资源基准。

### 阶段 C：强力增强

- 只在授权数据、独立模型、明确 UI 风险说明和更严格质量门禁就绪后开放；
- 允许 pattern-agnostic 网点推断，但必须可辨识、可关闭、可清缓存；
- 不得替换默认“保真清理”。

每个阶段都是独立可交付批次。阶段 A 没有 production UI、真实 encoded-page wiring 和集成测试时，不能因算法
函数已存在而声明 capability 完成；阶段 B 没有本地 runtime 的失败回退和模型许可证证据时，不能分发模型。

## 明确不做

- 不使用任何在线图片、多模态或 LLM API；
- 不把漫画页或其派生信息发送到第三方服务；
- 不用生成式模型重画整页；
- 不自动删除水印或来源标识；
- 不覆盖原始下载文件；
- 不让恢复任务写阅读进度或触发额外 source 请求；
- 不以“处理完成”代替质量门禁通过；
- 不因某模型能在 RTX 3070 上运行就假定它在所有 Desktop 或 Android 设备可用；
- 不把非商业、学术用途或来源不明的模型权重直接打包进发布产物。

## 资料索引

1. Xie, Xia, Wong, *Exploiting Aliasing for Manga Restoration*, CVPR 2021：
   [项目页](https://ttwong12.github.io/papers/mangarestore/mangarestore.html)、
   [代码](https://github.com/msxie92/MangaRestoration)。
2. Zhang et al., *DocRes: A Generalist Model Toward Unifying Document Image Restoration Tasks*, CVPR 2024：
   [代码](https://github.com/ZZZHANG-jx/DocRes)。
3. Wang et al., *Real-ESRGAN: Training Real-World Blind Super-Resolution with Pure Synthetic Data*：
   [代码](https://github.com/xinntao/Real-ESRGAN)。
4. MangaJaNai manga upscaling models：
   [代码与模型说明](https://github.com/the-database/MangaJaNai)。
5. Wang et al., *APISR: Anime Production Inspired Real-World Anime Super-Resolution*, CVPR 2024：
   [代码](https://github.com/Kiteretsu77/APISR)。
6. Tencent ncnn 本地推理框架：
   [代码与平台说明](https://github.com/Tencent/ncnn)。
7. OpenCV histogram equalization / CLAHE：
   [教程](https://docs.opencv.org/4.x/d5/daf/tutorial_py_histogram_equalization.html)。
