# 同步完整活动进度修复

基线：`fce64f71dee8350706beb703992b31e52c6f0a1e`。用户验收发现条目 1/1 后满格等待、随后再走一次进度。原因是旧展示把条目比例等同总进度，且明确允许运行中 100%，没有纳入最终核验、持久收尾和协调器排队段。此轮未操作用户数据库。

## 实现与边界

沿用 Runtime、Exchange、协调器和现有面板接线，添加易失 operation 身份、segment token 及真实工作阶段。检查 2%、授权 4%、基线准备 8%、快照发现 4%、冻结计划 2%、条目流程 65%、最终远端复核 5%、完整性检查 4%、持久收尾 4%、活动完成 2%。下载保存/确认分别贡献条目份额的 55%/45%；上传准备/传输/确认分别为 15%/60%/25%。暂停、回滚及确认账本沿用原权威，未改变 schema、协议、发布权限或恢复许可。

同 flight 的后续 segment 从已达比例分配剩余份额，拒绝过期 token；实际持久 run 成功且活动结束才释放最后份额。local-only 恢复沿原执行路径发布独立活动事实，不读取远端。历史和当前 run 隔离，同 run 使用最新活动序列。总进度不按时间伪增，不为展示增加网络请求；高频进度不重新读取完整凭据与诊断。无可靠全流程速率时主 ETA 保持未知。进程重启重建当前事实，不把易失的乐观进度当作持久确认。

## 红绿与审查

- `sync-operation-red` 两条真实展示行为按预期失败：全部条目确认仍在运行、run 已成功但活动仍忙，均提前 fraction=1。
- `sync-operation-green` 首次被一行 ktlint 长度阻塞，未执行产品测试；修正后 `green2` 执行 domain 和 data 定向范围。新 Runtime 测试末尾把 fixture 的既有完成次数误作零，改为相对基线断言；其延迟核验/持久收尾/排队阶段断言此前已通过。
- `sync-operation-green3` 两条真实 Runtime 集成通过，包含原有最终 HEAD 请求挂起、history 保存挂起、同活动后续零条目请求、阶段顺序、单身份与不回退；真实 SQLite trigger 令持久结果降为 PARTIAL，验证不能借返回 SUCCESS 完成。presentation 27 项中 26 过，旧 UI 一项仍断言条目比例而非完整活动份额。
- `sync-operation-final` 修正该旧预期，补验真实 Compose 全活动关闭/排队/完成和失败重试、主 ETA 未知，以及 local-only 恢复，全部通过。组合证据不冒充最终代码重跑全部测试。
- 组合 focused 共 52 个唯一用例：domain 8、data 15、presentation 29；最后补验为 6/6，不声称最终源码整套重新执行。修改的 Kotlin 文件定向 Spotless Apply/Check 及 `git diff --check` 通过。主代理独立审查完成边界、旧活动隔离、恢复权限和遥测接线；审查发现的额外 HEAD 请求已移除，保留原请求与结果语义。未执行全量测试、Android 验收或用户真实账户同步。

## Windows 候选

`scripts/build-desktop.sh preview` 经 `sync-operation-preview` 串行完成，exit 0；build/sourceIntegrity/productionRuntime PASS，nativeInteraction NOT_RUN。productionRuntime 仅指生产扩展安装和加载检查，不代表真实同步或原生视觉通过。

- EXE：`app-desktop/artifacts/preview/windows/20261117T132843Z-abfd2098/Mihon-Desktop-0.11.24.10.fce64f7-unpacked/Mihon Desktop.exe`
- manifest：同目录 `preview-manifest.json`，如实记录基线 HEAD + dirty 输入，不冒充主干或最终提交标签。
- 生产源码摘要：`78e0af06c5b5076245673a2d0a5a4b52830d4e27b0aede18a7950b270404cf96`，2486 文件；构建后重新采集一致。
- 完整产物树摘要：`11e4174bc388744cd8499478caaff73b753ffd61f73a7d8822a44cc087d04f38`。

用户验收：完全退出旧版后启动本候选，打开书架同步面板；条目到 1/1 后总进度继续呈现真实核验与收尾，整次活动结束时显示完成；内部批次和排队段不重新归零。暂停、等待、失败、待决定及部分完成不显示成功。公共功能说明等待用户验收后更新。本地提交不代表推送或用户验收。

## 活动结束检查文案补充

基线 `d11febde52bd0f97aae1774057b30cac060cbb0c`。用户要求最后的活动结束检查显示“正在结束同步”。实际结果持久保存成功且活动仍 running 时，阶段文字与面板状态统一使用此文案；保存期间仍显示“正在保存同步结果”，后续 segment 清除 durableSuccess 后恢复对应检查文案，最终结束显示同步完成。不增加阶段等待、请求或修改完成权限。

`sync-finishing-red` 新增行为测试按预期失败；首绿遇到跨模块 public 属性 smart cast 编译错误，改为安全读取后，`sync-finishing-green2` 的展示 22 项及真实 Compose 活动流程 1 项全部通过，定向格式检查通过。`sync-finishing-preview` 成功，候选位于 `app-desktop/artifacts/preview/windows/20261117T152554Z-c5499df7/Mihon-Desktop-0.11.24.10.d11febd-unpacked/Mihon Desktop.exe`；如实为基线加 dirty 输入。生产扩展运行时与源码完整性通过，原生交互未执行，未操作真实数据库。
