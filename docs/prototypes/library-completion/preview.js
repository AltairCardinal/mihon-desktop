(() => {
  const scenes = {
    baseline: "基础书架 A–E",
    large: "千本书架",
    "single-default": "唯一默认分类",
    "single-custom": "唯一自定义分类",
    "single-tracker": "单个追踪服务",
    "multi-tracker": "多服务评分",
    "custom-cover": "自定义封面",
    "detail-content": "详情长简介与作品资料",
    "detail-missing-source": "详情缺失图源与外部章节",
    "detail-auth-source": "详情图源登录验证",
    "detail-duplicate": "详情重复作品",
    "detail-tracking": "详情追踪与未收藏作品",
    "detail-tracking-error": "详情远端追踪失败",
    "detail-migration": "详情图源迁移",
    "detail-batch-failure": "详情批量下载失败与重试",
    "detail-uncollected-tracking": "未收藏作品自动追踪",
    "detail-long-chapters": "详情二百章长列表",
    "detail-sync-progress": "同步阅读进度与页码",
    "partial-failure": "部分更新失败",
    "chapter-change": "目录增删改与换链",
    "empty-source": "空目录响应",
    "malformed-source": "畸形目录响应",
    "save-failure": "下次保存失败",
    "sort-interrupted": "分类排序关闭中断",
    "device-wait": "自动更新等待",
    "legacy-task": "旧更新待确认",
    "broken-store": "更新记录损坏",
  };
  Object.assign(scenes, {
    "empty-library": "空书架",
    loading: "书架载入中",
    "load-error": "书架载入失败",
    "download-only": "全局仅下载",
    "smart-samples": "零章节与本地作品",
    "multi-category": "跨分类作品",
    "legacy-no-scope": "旧更新没有范围",
    "transaction-failure": "目录保存失败",
    "file-failure": "下载文件关联失败",
    "checkpoint-gap": "目录提交后中断",
    "custom-period": "自定义周期筛选已开放",
  });
  const select = document.querySelector("#scenario");
  Object.entries(scenes).forEach(([id, name]) =>
    select.add(new Option(name, id)),
  );
  const send = (message, all = false) =>
    document
      .querySelectorAll(
        all ? "iframe" : `#preview-${document.querySelector("#target").value}`,
      )
      .forEach((f) =>
        f.contentWindow.postMessage({ libraryDemo: true, ...message }, "*"),
      );
  document.querySelector("#target").onchange = () =>
    send({ requestDeviceState: true });
  window.addEventListener("message", (event) => {
    const target = document.querySelector(
      `#preview-${document.querySelector("#target").value}`,
    );
    if (event.source !== target?.contentWindow || !event.data?.libraryDemoState)
      return;
    const device = event.data.device;
    if (!device) return;
    document.querySelectorAll("[data-device]").forEach((control) => {
      const value = device[control.dataset.device];
      if (value === true || value === false || value === null)
        control.value = JSON.stringify(value);
    });
  });
  let themeSelection = MihonThemes.apply(document);
  let big = false;
  const themeSelect = document.querySelector("#theme-select");
  MihonThemes.catalog.forEach(({ id, name }) => themeSelect.add(new Option(name, id)));
  function updateTheme() {
    themeSelection = MihonThemes.apply(document, themeSelection);
    document.querySelector("#theme-note").hidden = themeSelection.theme !== "MONET";
    document.querySelector("#theme-toggle").textContent = themeSelection.mode === "light" ? "切换深色" : "切换浅色";
    send({ themeSelection }, true);
  }
  themeSelect.onchange = () => {
    themeSelection.theme = themeSelect.value;
    updateTheme();
  };
  document.querySelector("#amoled-toggle").onchange = (e) => {
    themeSelection.amoled = e.target.checked;
    updateTheme();
  };
  document.querySelector("#theme-toggle").onclick = () => {
    themeSelection.mode = themeSelection.mode === "light" ? "dark" : "light";
    updateTheme();
  };
  document.querySelectorAll("iframe").forEach((frame) => {
    frame.addEventListener("load", () => {
      frame.contentWindow.postMessage({ libraryDemo: true, themeSelection, font: big ? 2 : 1 }, "*");
      send({ requestDeviceState: true });
    });
  });
  document.querySelector("#apply-scenario").onclick = () => {
    send({ scenario: select.value });
    document.querySelector("#tool-status").textContent = "已载入：" + scenes[select.value];
  };
  updateTheme();
  document.querySelector("#font-toggle").onclick = (e) => {
    big = !big;
    send({ font: big ? 2 : 1 }, true);
    e.target.textContent = big ? "字号 100%" : "字号 200%";
  };
  document.querySelectorAll("[data-command]").forEach(
    (b) =>
      (b.onclick = () => {
        send({ command: b.dataset.command });
        document.querySelector("#tool-status").textContent =
          b.textContent + "：已作用于所选设备";
      }),
  );
  document
    .querySelectorAll("[data-device]")
    .forEach(
      (el) =>
        (el.onchange = () =>
          send({ device: { [el.dataset.device]: JSON.parse(el.value) } })),
    );
})();
