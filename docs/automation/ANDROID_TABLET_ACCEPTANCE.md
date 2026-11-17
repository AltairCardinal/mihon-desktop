# Android 平板操作：无线优先、安装与收尾

适用于已授权的真机操作；构建与签名沿用[Android 构建指南](../agent-guides/android-build.md)。本页单独读取；输出截断时补读缺失段落，再执行依赖操作。历史实测与性能诊断按末尾索引读取，不随每次连接一起加载。

## 连接顺序：无线优先

**不得仅因 `adb devices -l` 为空或 offline 就要求用户接 USB。** 默认先检查无线入口；用户明确指定 USB，或本轮已核验目标 USB 在线时可直接复用。

1. 使用本机 `D:/Android/Sdk/platform-tools/adb.exe`，先查本次任务及项目记录，再运行 `adb devices -l`。已有目标在线则核验身份后复用；列表为空只表示当前没有已连接设备，不证明无线不可达。
2. 无目标在线时，先对已记录的无线地址执行一次 `adb connect <IP:端口>`，通过有超时控制的执行入口限制为最多 12 秒。既有候选地址为 `192.168.50.51:5555`，只作尝试入口，不作当前身份依据。不得跳过连接尝试，把历史失败当本轮失败。
3. 旧地址失败或已变更时，可用 `adb mdns services` 查找目标的新入口（同样最多等待 12 秒），仅对有依据属于目标的地址尝试一次；无发现不等于设备关机或锁定。不扫描网段、不无限重连。
4. 连接后重新枚举，并显式指定 `-s <本次确认的serial>` 读取型号和 API，与目标记录核对；历史目标是 PCE-W30 / Android 12 / API31。`connected` 或命令 exit0 单独不足以证明目标正确。USB 与无线别名可能指向同一设备，不默认选取首项。
5. 仅在无线尝试失败、缺少可确认的无线入口，或确需用户完成授权/配对时再请求协助；说明尝试的地址与实际结果，可请用户提供当前无线入口或改接 USB。`unauthorized` 由用户正常授权，不绕过认证。用户接管后停止输入。

```powershell
$adb = 'D:/Android/Sdk/platform-tools/adb.exe'
& $adb devices -l
# 无目标在线时，按上文超时限制执行 connect；失败后再按需查询 mdns。
& $adb connect '<本次要核对的IP:端口>'
& $adb devices -l
$serial = '<本次在线且待核验的无线或USB serial>'
& $adb -s $serial shell getprop ro.product.model
& $adb -s $serial shell getprop ro.build.version.sdk
```

所有后续安装、shell、pull、日志及采集命令均显式指定已核验 serial；不能把历史 USB serial 写成所有任务的固定要求。

## 安装与操作边界

- 安装前核对 APK、包名、版本、证书及目标设备；使用 `scripts/build-android.py verify/install --artifact ...`，安装必须带 `--serial ...`。完整规则见[安装与升级责任](../architecture/android-build-and-acceptance.md#9-安装升级与设备责任)。
- 保留用户数据和非目标安装，不用卸载、清数据或降级绕过问题；性能实验使用已确认的隔离包，不能把旧 benchmark 包覆盖到正式安装。
- 构建不隐式安装，安装不隐式启动、点击或强停应用。用户要求“安装到平板，我来验收”时，完成安装及身份核验后交还设备，不代操作验收。

## 正常唤醒与无密码锁屏

安装、日志读取和离线分析通常无需解锁；仅需前台输入或画面证据时亮屏。先核电源、锁屏显示状态和 `secure`；`showing=true` 不等于需要密码。

```powershell
& $adb -s $serial shell input keyevent KEYCODE_WAKEUP
& $adb -s $serial shell dumpsys power
& $adb -s $serial shell dumpsys window policy
# 仅确认 secure=false 且仍 showing=true 时，执行正常解除：
& $adb -s $serial shell wm dismiss-keyguard
& $adb -s $serial shell dumpsys window policy
& $adb -s $serial shell dumpsys window windows
```

读回 `Awake`、`showing=false`、`secure=false`，并核对目标应用焦点后才做前台操作。状态异步变化时先做短时间、有界的只读复核，不反复发送输入；不使用会切换状态的 POWER 代替 WAKEUP/SLEEP。已有认证锁、字段不明或正常解除失败时保留状态并请用户处理，不猜密码、不改安全或超时设置。

## 亮屏范围与收尾

- 前台步骤逐项观察实际结果；双击等连续手势之间不插入慢速查询。构建、长分析及等待前先保存证据、停止输入并熄屏。
- 采样结束先正常离开 Reader，等待完整探针 JSON，再关闭探针并停止本任务隔离包和持有的录制、trace、logcat 进程；不强停正式安装。导出失败保留缺口，不补造样本。
- 前台操作结束、失败或取消时熄屏并读回 `mWakefulness=Asleep`、`mWakefulnessChanging=false`；过渡状态只做有界只读等待。连接已断则报告未确认，不宣称收尾成功。

```powershell
& $adb -s $serial shell input keyevent KEYCODE_SLEEP
& $adb -s $serial shell dumpsys power
```

## 按需参考

仅执行对应任务时读[实测经验与证据](ANDROID_TABLET_EXPERIENCE.md)的相关章节：

| 任务 | 参考 |
| --- | --- |
| 复核历史设备、USB及锁屏结果 | [环境与依据](ANDROID_TABLET_EXPERIENCE.md#已验证环境与依据) |
| 核对实际 ART/OAT 编译状态 | [安装与编译经验](ANDROID_TABLET_EXPERIENCE.md#安装与编译条件的实测经验) |
| 视频、双图及帧边界分析 | [本机工具与采样经验](ANDROID_TABLET_EXPERIENCE.md#已找到并实际使用的本机工具) |
| 下载章首开性能复测 | [低成本复测](ANDROID_TABLET_EXPERIENCE.md#下载章首开低成本复测) |
| 重建临时 snapshot 诊断包 | [内部状态查询](ANDROID_TABLET_EXPERIENCE.md#已验证的临时内部状态查询) |
| 双击未被识别 | [已验证输入时序](ANDROID_TABLET_EXPERIENCE.md#r4h正常双击输入的成功时序) |
