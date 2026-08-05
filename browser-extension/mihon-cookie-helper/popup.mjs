import {
    acquireClearance,
    clearancePermissionFor,
} from "./cookie-helper.mjs";

const api = globalThis.browser ?? globalThis.chrome;
const host = document.querySelector("#current-host");
const copyButton = document.querySelector("#copy-clearance");
const status = document.querySelector("#status");
let activeTab = null;

function showStatus(message, kind = "info") {
    status.textContent = message;
    status.dataset.kind = kind;
}

async function loadActiveTab() {
    [activeTab] = await api.tabs.query({ active: true, currentWindow: true });
    const target = clearancePermissionFor(activeTab?.url);
    if (!target) {
        host.textContent = "当前页面不受支持";
        copyButton.disabled = true;
        showStatus("请打开来源的 HTTP 或 HTTPS 验证页面。", "error");
        return;
    }
    host.textContent = target.host;
}

copyButton.addEventListener("click", async () => {
    copyButton.disabled = true;
    showStatus("正在请求当前域名权限并读取 Cookie……");
    try {
        const result = await acquireClearance({ api, clipboard: navigator.clipboard, tab: activeTab });
        const messages = {
            copied: [`已复制 ${result.host} 的 cf_clearance 值。`, "success"],
            "permission-denied": ["未获得当前域名权限，未读取任何 Cookie。", "error"],
            "cookie-missing": ["当前域名没有 cf_clearance；请先完成页面验证。", "error"],
            "unsupported-page": ["当前页面不受支持。", "error"],
        };
        showStatus(...messages[result.status]);
    } catch {
        showStatus("读取或复制失败，请重试或使用 F12 手动获取。", "error");
    } finally {
        copyButton.disabled = false;
    }
});

loadActiveTab().catch(() => {
    host.textContent = "无法读取当前标签页";
    copyButton.disabled = true;
    showStatus("请关闭后重新打开扩展。", "error");
});
