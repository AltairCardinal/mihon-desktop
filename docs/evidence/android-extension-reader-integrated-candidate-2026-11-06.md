# Android 扩展兼容与阅读启动优化整合候选

2026-11-06，用户要求将扩展兼容修复与最新 Android 阅读启动优化整合后提供新 APK。
本批不推送/发布，不操作用户平板，不重测性能或运行完整模块/全量。

## 固定输入与整合

- 主干 `35203467e3b8703e515405dcba057c44abb923a9`，保留已验收的 B9/O2/O4/O8/O9
  及正式 candidate 的 noReaderProbe 物理源码选择。
- 扩展分支 `3dfc17441fec204266efa547c01f2ad4e79b4aea`，保留协程 1.11.0、新旧 ABI、
  链接错误隔离/来源诊断和普通 production 共用的精确成员 keep。
- 独立工作区 `D:/Shell/Github/mihon-ec-reader-integrated` 从主干合并扩展分支。
  唯一双方同路径修改为 app/build.gradle.kts，自动合并无冲突；没有重写任一业务实现。
  原工作区的未提交 Reader 草稿保持原样，不纳入此包。
- 原正式候选 aex.118/code136 已交付；本批分配 **aex.119/code137**，正式证书仍为
  `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`。

## 聚焦验证

`ec-reader-integrated-focused` **PASSED/exit0/3分54秒**；有效 XML 为 source-api ABI2、
Android9，共11项，零失败/错误/跳过。覆盖独编旧新二进制、页列表/图片不兼容与健康页/
显式重试、真实 canonical executor/retry、后台目录保留活动章节窗口、O2 原图头变化、
新建/已加载章节不提前扫描图片头、正方形原图零额外区域工作、O9 亮度独立更新。
定向 app/build.gradle.kts 正常严格格式通过；无新增产品逻辑，不虚构整合红测。
命令和原始输出位于该工作区 `.gradle-coordinator/ec-reader-integrated-focused.{json,log}`，
精确 XML 汇总在 `.test-tmp/integration-focused-summary.json`。

签名预检通过；专用 API36/x86_64/ranchu AVD ec-coroutines-api36/serial emulator-5580
安装原正式 code136，实际 hash 与旧 artifact.json 一致，冷启动成功；这是任务虚构环境，
没有用户书库。首次只读“无安装”检查遇到 pm path 缺包时的非零退出码，修正为检查
pm list 后继续，不计产品失败。最终新候选的制品核验、升级/启动与收尾记录待下段补充。

候选使用 scripts/build-android.py candidate --offline，既有正式签名/校验链路；交付文件
必须从本次 artifact.json 指定路径读取。不把此前 benchmark 包或历史 app/build/outputs
当作本批正式候选，也不以本次 APK 宣称 Android17 联想实机或新的性能测量通过。


## 最终签名候选与隔离升级

构建源快照 `97e70f33e8d9b8e0159fe6cc353b2d92dd7cb42f`，两个父提交分别是上述
main35203467 与扩展3dfc174；构建时工作树干净，diff/untracked hash 均为空输入摘要。
最终提交只补本记录和旧roadmap的交付指引，产品/构建内容与快照相同，无须为文档重建。

- `python scripts/build-android.py candidate --offline`：完整入口 exit0、170秒；其
  `android-candidate` 协调器 **PASSED/exit0/2分41秒**。实际命令包含
  `-Pandroid.candidateVariant=release`，采用 noReaderProbe 源目录；R8/资源压缩开启，
  非debuggable、min26/target36，本项目 updater 保留。未修改公共功能承诺或执行外部发布。
- [正式签名候选 APK](D:/Shell/Github/mihon-ec-reader-integrated/app/artifacts/android/0.19.4-aex.119-vc137-97e70f33e8-release/Mihon-Fork-0.19.4-aex.119-vc137-release-universal.apk)
  为通用包，70,191,290字节；包名 `app.mihon.desktop.fork`、**0.19.4-aex.119/code137**，
  四ABI arm64-v8a/armeabi-v7a/x86/x86_64。
- SHA256：`f74571c949d91a3c57e35f8894b1b6a9114a2d43f881c56c2e9ec4e724259f27`。
  既有正式证书 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`，
  实际 v2/v3 验签与统一 `verify --artifact` 复验通过。
- [artifact.json](D:/Shell/Github/mihon-ec-reader-integrated/app/artifacts/android/0.19.4-aex.119-vc137-97e70f33e8-release/artifact.json)
  的 sourceRevision 绑定上述快照，productionInputsSha256 为
  `d07931e14d3ef91447658c7e6621bef8c4c548dd6719210c545ada41a860c9db`；
  未混入原目录Reader草稿、第三方测试APK或薄DEX。入口保留本次mapping及清单。
- 未实施者独立审查实际候选 SHA、manifest/cert、provenance 和 DEX：24950类，
  BuildersKt旧/新及两default均有正确原型、static和可执行code；无EC测试资产。
  无 reader-open-real 配置串、Probe Session/TargetBinding、record/loadConfiguration等
  记录器实现。允许同名no-op适配器的最小Object构造及仅return-void的空clinit；
  审查脚本最初把任何非构造方法都拒绝，核对该空初始化后修正判读，非产品失败。
- 已签名候选使用统一 `install --artifact ... --serial emulator-5580` 在任务专用
  API36/x86_64/ranchu AVD 原位升级，入口再次核实际安装SHA与正式身份。未卸载/清数据。
  首次系统字段检查按旧userId名称未找到，实际API36输出appId；随后读取前后原件，
  appId均10218、firstInstallTime均2026-11-06 03:03:24，code136→137。该装置字段
  适配没有重新安装或重建APK，不计产品故障。
- 明确force-stop后真实MainActivity冷启动Status ok/LaunchState COLD；PID3988两次
  读回存活，实际topResumedActivity属于正式包，crash buffer无该PID对应崩溃。
  仅为安装/升级/启动smoke，不以模拟器启动耗时比较或替代阅读首开性能测量。
  此环境为虚构空书库，不宣称已验用户真实书库迁移或Android17联想设备。
- Root保存安装/前后包状态/启动/崩溃缓冲原件于 `.test-tmp/integration-upgrade-*.json`
  及同名前后package文本、integration-startup-*；候选脚本实际退出码保存在
  `.test-tmp/integrated-candidate-result.json`，原始日志为同目录integrated-candidate.log。
  只关闭任务AVD，serial emulator-5580已消失、启动PID56732已退出；未触及用户平板、
  终端或其他Java进程。`integration-cleanup.json` 保存收尾结果。

## 交付边界

11项focused/精确格式、正式签名/制品独审、旧正式136→新137的隔离安装/冷启动通过。
未重复此前完整EC R8/ART方法或阅读器真机性能测量，复用未受本次自动整合影响的有效
结果；不把静态DEX或启动smoke扩大描述为搜索/阅读全路径实机验收。原EC范围、
未管理插件线程/JNI和日志导出独立故障边界保持原状。用户自行用本APK验收搜索及阅读
启动体验；Android17厂商设备仍待实际反馈。

本批可快进回本地main，但不推送、不打tag、不对外发布。原扩展工作区及未提交用户
草稿继续保留，未stash/回滚/纳入候选。正式候选文件不会因文档补录被重新编译替换。
