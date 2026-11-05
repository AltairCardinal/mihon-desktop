# Windows 阅读器延迟采集的已验证流程

2026-11-06在独立测试profile、与平板参考相同内容CBZ的Windows控制/实验preview上验证。桌面主机为i9-13900F、RTX 3070、Windows 11；PCE-W30仅为官方Android参考设备。主流程和采集边界沿用[Desktop验收](../agent-guides/desktop-acceptance.md)、[WGC窗口采集](../architecture/desktop-reader-render-trace.md)及[打开基准](../evidence/reader-startup-performance-baseline-2026-11-02.md)。

## 实例与数据

- 打包启动器会再启动同EXE的JVM子进程。实际窗口属于子PID，不能把Popen.pid直接交给WGC。核完整EXE、父子关系、唯一非最小化可见SunAwtFrame，然后实际收一帧才记录READY。HTTP服务ready不代表窗口或捕获ready。
- profile marker源码要求精确LF字节，Windows脚本用write_bytes生成。CRLF会触发Unrecognized test profile marker并呈现Failed to launch JVM对话框，不代表JDK缺失。保留失败现场后修模板。
- 真实CBZ原件为D:/Shell/Github/mihon/.test-tmp/slime-reader-remeasure-20261102/slime-vol04.cbz，SHA256 b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677。只复制至独立profile。合法空库种子里的source0书架作品/已下载章节能通过正常详情点击、生产进度恢复第5/187页；临时本地浏览入口固定从0开始，两种入口分别记录。
- Test Mode正常UI新建runtime同样可向当前scenario记录productionEvents，不一定需要fixture。读取GET /test/reader/state即可，点击前不要reset把正常页面清掉。chapter/generation/最新OPEN事件用于区别同进程再次打开。
- 若sky对已核身份的窗口返回旧终端残影，先读取该窗口最新accessibility树，仅点击其“最小化”，用activate_window恢复并再次观察，直到得到正确书架。此次在C3、CDon和ADon正式点书/采集前成功恢复；未操作终端、未提前进入阅读器。恢复期间截图不计性能样本，不能沿旧图坐标输入。

## 采集与输入

先观察独立窗口的书架和详情，确认正确章行/恢复页。由唯一操作者用computer-use技能node_repl/sky发送正常输入。现有capture_reader_window准备脚本及可复算原件在.test-tmp/desktop-first-open-20261106/luna/，调用既有scripts/capture-reader-compositor.py，不改产品绘制路径。

- 后台采集从创建时使用CREATE_NO_WINDOW。将正常UI动作准备好再Popen启动sidecar，即时返回PID，避免执行通道默认等10秒消耗采集窗口。
- 等ready.json记录实际首WGC帧后再点击。正常静止窗口也可能60fps回调，30秒/1800帧容易在点击前达到上限。现已验证90秒/10000帧的有界预算。脚本内部录制时间加10秒，而原生后端上限120秒，故外层上限110秒。不可直接外层传120。
- WGC的systemRelative100ns乘100为QPC纳秒。Windows HotSpot System.nanoTime同域。Node REPL未暴露process.hrtime，也禁止node:process；使用performance.now和常驻无窗口Python QueryPerformanceCounter helper，以多次往返区间交集校准。本次映射不确定度约19–38微秒。stdout必须按完整行解析，单次data回调可能只是一段数字或CRLF。
- sky.click自身前后包络约90–117ms，应把整个包络保留为输入时点的不确定范围，不能称精确EDT送达。若首匹配原帧为T1、前帧为T0、输入包络[I0,I1]，可见转换界限为[max(0,T0-I1),T1-I0]。第一张拍到画面的点值范围另列，不能漏掉相邻原帧间隙。

## 原件与裁决

实际WorldMap稳定WGC参考与ROI分别记录。本次1518×1143窗口使用地图纹理ROI(430,80,650,650)，近黑用旧详情有内容的章行ROI(676,220,823,120)，灰度均值阈值17/255。这些坐标只适用本次窗口，不复用到任意DPI/布局。

双页须先从mountedReader和稳定帧确认实际页对与左右位置，再为每侧取独立纹理ROI；避开工具栏及首次教学提示。2026-11-06关闭匹配首次/再次均为index[4,3]；开启匹配首次为[5,4]，保存进度后再次为[6,5]，A/C一致。自动匹配的首次与再次不能互作同内容对照。所谓“双页齐全”只表示两侧被测漫画纹理均出现，不表示每一像素未被工具栏遮挡。各侧近黑分别计算，与单页章行ROI也不直接作严格时长比较。

先核纹理、页码、方向和模式，再计算首图前近黑分段/总长/最长，保留前后可见原帧和间隙。首图后同样检查再黑。重复PNG按file缓存分析，逐接收帧保留独立QPC记录。采用相同ROI/阈值对照。

主动stop会使既有录制器记录native-capture-exit:1并标PARTIAL。本次received==written、missing=0、帧序连续，且输入到稳定地图已完整处于所录时间区间，因此可报告该局部转换端点并保留尾部告警；不得改写为RECORDED或完整物理扫描验证。采样间隙按实际界限报告。

区间结束后先正常退出阅读器保存进度，再关闭独立实例，核自有采集PID退出。编译/离线等待期间不继续派发输入或维持主动采集。其他用户实例与配置不操作。
