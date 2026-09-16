# Android 单库许可证补充

这里通过 AboutLibraries 官方 `collect.configPath` 补充
`com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533` 的元数据。
该版本 AAR 仍可从既有缓存解析，但原仓库的 POM 返回 404。其余依赖由当前构建正常采集，
包括 Tink；不要用历史完整清单替代当前依赖解析。

`libraries/flexible-adapter.json` 保留已核验发布清单中的该库条目，
`licenses/Apache-2.0.json` 保留同一清单中的 Apache 2.0 许可正文。
许可证仅省略原清单的生成内部字段 `internalHash`；正文、`hash`、名称、URL 和 SPDX 标识保持一致。
库的上游位置为 <https://github.com/arkon/FlexibleAdapter>。
来源发布清单 SHA-256 为
`0fb0f9627f9e9e136ce72dc12d94f1043f208a4bf069e2ee70df8bcb55161d3e`；
对应 AAR SHA-256 为
`41929c785c249e0395faf89fd6bb253aafd65d44d88dbeaa46ecd9658d706cc4`。
这里仅提取这一个库及其许可证，没有保存或复用其他历史依赖条目。

构建插件与运行库版本分别管理：插件使用 14.0.0 的缺失 POM 宽容采集，运行库仍为 13.2.1。
宽容采集不等于清单完整。`AndroidLicensesPluginFunctionalTest` 执行真实 Android release
生成任务，并将实际运行时工件坐标、版本与 JSON 对照；未知的无 POM 工件必须暴露为缺项。
发布验收还需对真实 app 的运行时工件执行同样的完整性核对。

在同一工作树切换 `-Pinclude-telemetry` 等依赖开关时，本次验收观察到生成任务被判为
UP-TO-DATE，仍沿用不含 Firebase 的清单。切换后须先以最终发布参数强制执行现有生成任务，
再核对当前依赖并构建 APK；不能用另一组构建参数的完整性结果代替，也不能只重打包旧清单：

```text
gradlew :app:prepareLibraryDefinitionsRelease --rerun -Pinclude-telemetry -Penable-updater
gradlew :app:assembleRelease -Pinclude-telemetry -Penable-updater
```

这是当前增量构建的维护限制，本批没有改造插件缓存。最终正常发布候选核对了 263 个实际工件模块、
293 个导出库和 95 个官方合并别名；缺失及版本不符均为零。

更新该固定库版本时，重新核验库和许可来源，更新这一个条目，并运行：

```text
gradlew -p buildSrc test --tests mihon.buildlogic.AndroidLicensesPluginFunctionalTest
gradlew :app:prepareLibraryDefinitionsRelease
```
