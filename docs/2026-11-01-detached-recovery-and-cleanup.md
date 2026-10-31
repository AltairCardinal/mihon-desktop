# Detached 测试回收与工作树清理交付

日期：2026-11-01。依据 [取舍审查第9节](2026-11-01-local-work-retention-audit.md)，用户要求执行测试移植并清理所有 detached HEAD。实施基线 f8187e20f6d901c1c0361d41a87917d265a13376。

## 范围

只向三个现有 Desktop 测试类移植9个场景（参数展开16项），不恢复旧产品实现，不重启性能研究，不改版本/依赖。另选3个现有请求提优回归，合计预期19项。新树 `codex/detached-test-recovery-20261101` 隔离主目录其他写者。

## 工作树清理结果

39处 detached 登记已全部移除，复核 detached=0。36个可访问原目录完整迁至 [本地备份](D:/Codex/archives/detached-cleanup-20261101/README.md)，包括未跟踪和ignored内容，没有按缓存名删除。3个原目录缺失/未完成初始化，只能保存HEAD和Git元数据，未声称恢复了未知磁盘内容。

所有39个HEAD保存为 `local-detached-cleanup-20261101/*` 本地标签；[Git bundle](D:/Codex/archives/detached-cleanup-20261101/detached-commits.bundle) 包含完整历史，已通过 bundle verify，大小146376963字节，SHA256 `72b73eeab195f1e1499b566b5bcd55b9750ed30216ffca287026c452d241a981`。完整映射、原状态、二进制 staged/unstaged patch 及原 index 在 [completed.json](D:/Codex/archives/detached-cleanup-20261101/completed.json) 和各 snapshot 内。原.git链接改名 detached.git-link.txt，避免误用旧仓库指针。

这是移出活跃工作树和登记清理，不是释放全部磁盘占用；备份目录保留了原构建缓存。未触及两个不可访问但有分支的WSL工作树，不做全局prune，不清理用户终端/应用，不改变其他分支。旧性能目录首次因Windows占用迁移失败，停止该项并处理其余，稍后同一迁移成功，未结束用户进程。

| 原路径 | 结果 | HEAD |
|---|---|---|
| `/tmp/aa203-clean` | 原目录缺失；元数据保存、登记移除 | `c9c1605652` |
| `C:/Users/feeli/.codex/worktrees/f8e8/mihon` | 原目录缺失；元数据保存、登记移除 | `024212ac6d` |
| `D:/Codex/worktrees/0877/mihon` | 完整目录迁出、登记移除 | `9c7cbf5154` |
| `D:/Codex/worktrees/85be/mihon-sync-old-96db` | 完整目录迁出、登记移除 | `96dbb69d7a` |
| `D:/Codex/worktrees/9117/mihon` | 原目录缺失；元数据保存、登记移除 | `34ed3bcc91` |
| `D:/Codex/worktrees/catalog-pack-20261012` | 完整目录迁出、登记移除 | `1d26852128` |
| `D:/Codex/worktrees/e63b/mihon` | 完整目录迁出、登记移除 | `cf1ea0417f` |
| `D:/Codex/worktrees/f235/hp02-history-candidate` | 完整目录迁出、登记移除 | `05fdb81a6a` |
| `D:/Codex/worktrees/l1-r3-baseline` | 完整目录迁出、登记移除 | `db7b0e24bc` |
| `D:/Codex/worktrees/luna-v10-formal` | 完整目录迁出、登记移除 | `7b2020b77b` |
| `D:/Codex/worktrees/luna-v10-oracle` | 完整目录迁出、登记移除 | `7b2020b77b` |
| `D:/Codex/worktrees/luna-v11-candidate` | 完整目录迁出、登记移除 | `7b2020b77b` |
| `D:/Codex/worktrees/luna-v12-candidate` | 完整目录迁出、登记移除 | `0c42b4b2dd` |
| `D:/Codex/worktrees/luna-v13-candidate` | 完整目录迁出、登记移除 | `7b2020b77b` |
| `D:/Codex/worktrees/luna-v15-candidate` | 完整目录迁出、登记移除 | `7b2020b77b` |
| `D:/Codex/worktrees/luna-v20-main-control` | 完整目录迁出、登记移除 | `7b2020b77b` |
| `D:/Codex/worktrees/luna-v7-historical` | 完整目录迁出、登记移除 | `892a0061c9` |
| `D:/Codex/worktrees/luna-v7-mihon` | 完整目录迁出、登记移除 | `14e4dcdf90` |
| `D:/Codex/worktrees/luna-v9-reader-repro` | 完整目录迁出、登记移除 | `cb334ea0e4` |
| `D:/Codex/worktrees/mihon-m2-baseline-63236da` | 完整目录迁出、登记移除 | `63236da236` |
| `D:/Codex/worktrees/mihon-upstream-reader-perf-20261020/mihon` | 完整目录迁出、登记移除 | `7a917968e3` |
| `D:/Codex/worktrees/reader-ci-introduction-audit-20261013` | 完整目录迁出、登记移除 | `be9e31ec24` |
| `D:/Codex/worktrees/reader-manual-pairing-baseline/mihon` | 完整目录迁出、登记移除 | `d52fa599c9` |
| `D:/Codex/worktrees/sync-release-20260919` | 完整目录迁出、登记移除 | `f2f087688e` |
| `D:/Shell/Github/mihon/.worktrees/feat-auto-20261023-5c28ea99/.candidate/reader-pairing-r6-baseline` | 完整目录迁出、登记移除 | `03a7fbd7f1` |
| `D:/Shell/Github/mihon-aex05-old` | 完整目录迁出、登记移除 | `295107aa4d` |
| `D:/Shell/Github/mihon-android-zoom-edge` | 完整目录迁出、登记移除 | `5eda508e62` |
| `D:/Shell/Github/mihon-evidence-dbc02b` | 完整目录迁出、登记移除 | `dbc02bb9ad` |
| `D:/Shell/Github/mihon-fullscreen-white-edge-a68d287856` | 完整目录迁出、登记移除 | `a68d287856` |
| `D:/Shell/Github/mihon-main-i18n` | 完整目录迁出、登记移除 | `024212ac6d` |
| `D:/Shell/Github/mihon-pairing-preview` | 完整目录迁出、登记移除 | `5a5c54b167` |
| `D:/Shell/Github/mihon-reader-auto-release` | 完整目录迁出、登记移除 | `97c44b4411` |
| `D:/Shell/Github/mihon-reader-error-exit-build` | 完整目录迁出、登记移除 | `e0b8c6a0ae` |
| `D:/Shell/Github/mihon-rv01-evidence` | 完整目录迁出、登记移除 | `6080f7926f` |
| `D:/Shell/Github/mihon-task151-final` | 完整目录迁出、登记移除 | `056dcb79db` |
| `D:/Shell/Github/mihon-task151-windows` | 完整目录迁出、登记移除 | `8078bee94e` |
| `D:/Shell/Github/mihon-task152-windows` | 完整目录迁出、登记移除 | `1d0d7d8f41` |
| `D:/Shell/Github/mihon-window-startup` | 完整目录迁出、登记移除 | `a5057321bc` |
| `D:/Codex/worktrees/mihon-fork-reader-perf-parallel-20261020/mihon` | 完整目录迁出、登记移除 | `faf002165b` |

## 定向验证

正在执行7源固定闭包编译、三文件scoped格式检查与12个精确selector（预期19项）。重型操作经现有coordinator串行执行，ignored init/guard只用于此次验证，不纳入产品。首次init应用到buildSrc时定位不到app-desktop，配置阶段失败，未执行测试；修正init复合构建guard后继续。后续定向结果见下。

### 实际发现与修复

T3暴露真实单页点击问题：SinglePagePagerViewer先以旧logicalLocation构造navigationIntent，再同步goToPage推进owner位置；旧intent令incoming ticket遭现有位置门禁拒绝，动画offset实际变化但中间像素为空，直到idle清意图后才绘制新页。仅在有binding且有targetPage的同步分支清旧navigationIntent，保留user/programmatic target、fallback和所有票据安全门禁。新增T3真实混合像素前提不放宽；不回迁旧370f467方案。

最初测试适配失败按证据关闭：T1原32×32夹具按产品规则为SPREAD，改为仅新场景的32×48竖页，再注入64×32迟到事实；T4改用非visible真实配对工作占槽，避免主干正确前台让行阻止投机；T4/T5用已有completion gate固定真实存活投机注册，再经真实native mount升级；T6固定真实旧required工作，仍证明新同页登记先于旧finally及compare-remove身份保护。没有直接写私有注册状态或伪造预算。

### 结果与基线例外

- 新增9个场景参数展开16项，最终组合全部通过。
- 另选的3项既有请求提优回归首轮通过，未受单页点击修复影响，复用其结果。
- 最终扩展组合实际56项：48通过、8失败；其中ReaderPageTurnPresentationTest整类48项（40通过、8失败），另8项为T1两参数+T4–T9六方法，均通过。不能将多轮通过数相加为独立用例数，也不能声称56项重新全绿。
- 8失败全部是既有 `live animation preference controls taps and external requests without blank instant frames` 的dual=true参数，即时帧像素为空。为排除本次变更，保持测试源不变，临时精确撤去production新增一行，单方法16参数对照：同8项dual失败仍在，另外2项single tap也失败（共10失败）。恢复修复后这2项single tap通过，因此8项dual失败已证实为基线现象，不扩大本轮修复。
- 临时production对照由try/finally完整bytes恢复并核SHA，随后另跑T3四参数确认恢复后编译及四修改源scoped格式检查；最终 `detached-recovery-restored-t3-format` PASSED：T3四参数4/4、四修改源格式检查通过；恢复后的源码指纹与输入一致。
- 首轮配置/测试编译适配失败均保留；最终production与固定7源测试闭包编译完成。所有重型命令经coordinator串行、offline、无完整模块/全仓测试，无资源故障或全局进程清理。

最终组合key `detached-recovery-final-focused`；基线对照key `detached-recovery-live-baseline`。原始XML、输入源码指纹、init和资源guard、各轮日志已保存至 [validation](D:/Codex/archives/detached-cleanup-20261101/validation/evidence/evidence-manifest.json)，不能把基线失败隐藏为PASS。

## 交付边界

清理已完成。新增场景已完成验证，确认单页点击旧导航意图导致的动画空帧问题已修复；8项既有双页即时帧基线失败明确保留。没有正式构建、发布、推送或用户应用安装。本记录是本次唯一交付报告，既有审计文档保留其取舍历史。

最终核验：新增16项与不受影响的3项关联回归，以组合证据覆盖19项全部通过，非单次最终19项重跑。四修改源格式检查与git diff --check通过；全部9个coordinator状态terminal，原身份进程退出。主干整合时保护已有AppVersion差异（SHA256 `555d42abf87335aa333182df5118c1ad9cdb43ebb93da8b5eed2e182055e6ee5`）及用户附件。
