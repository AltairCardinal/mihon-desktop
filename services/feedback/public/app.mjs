import { MAX_LOG_BYTES, MAX_PREVIEW_BYTES, crashLogBytes, normalizeCrashLog, validCrashLogText } from "./crash-log.mjs";

const strings = {
  zh: {
    heading: "问题反馈",
    intro: "无需注册或登录。请勿填写密码、账号、书库内容或其他个人信息。",
    privacy: "标题、描述和可选安全摘要在你同意后公开转交 GitHub。崩溃日志仅在独立授权后交给项目维护者，不发送 GitHub。服务数据库中的报告、私密日志及用于限流的 IP 不可逆摘要通常保留最多 30 天；已公开的 Issue 不受此期限限制。不收集邮箱。Cloudflare 处理网络请求及安全验证。",
    title: "问题标题（5–120 字符）",
    titleHelp: "简要说明遇到的问题。",
    description: "问题描述（20–6000 字符）",
    descriptionHelp: "你做了什么？原本预期什么？实际发生什么？请使用虚构示例。",
    summary: "应用安全摘要（可选，最多 1000 字符）",
    summaryHelp: "可手动粘贴应用“问题上报”中复制的五行摘要。仅接受 Application、Version、OS、Architecture、Runtime；不会自动读取剪贴板。",
    logHeading: "私密崩溃日志（可选）",
    logHelp: "选择 UTF-8 文本 .txt、.log 或 .log.1/.2/.3，在本地预览并删除敏感信息。最多预览 4 MiB；提交内容最多 256 KiB。日志仅供项目维护者查看，不发送 GitHub。",
    logFile: "选择日志文件",
    logPreview: "可编辑的本地预览",
    logRemove: "移除日志",
    logConsent: "我已检查日志，仅授权项目维护者查看；日志通常保留最多 30 天，不发送 GitHub。修改、换文件或移除后需重新授权。",
    logEmpty: "未附加日志；你可以不上传日志直接反馈。",
    logReading: "正在本地读取，尚未上传…",
    logLoaded: "仅在本地预览，尚未上传。请检查内容后单独授权。",
    logReceived: "日志已接收，仅项目维护者可见；不会发送 GitHub。",
    logEdited: "预览已修改，请重新检查并授权。尚未上传。",
    logRemoved: "日志已移除，不会随反馈提交。",
    logUnsupported: "只支持 .txt、.log、.log.1/.2/.3 文本文件，不接受 ZIP。原预览已保留。",
    logFileLarge: "文件超过 4 MiB，未读取。请先在本地精简；原预览已保留。",
    logUtf8: "文件不是有效 UTF-8 文本，未附加。原预览已保留。",
    logBinary: "日志包含二进制控制字符或无效文本，未附加。原预览已保留。",
    logLimit: "日志超过 256 KiB，请编辑精简或移除；不会静默截断，输入内容已保留。",
    logInvalid: "日志包含无效文本或二进制控制字符，请编辑精简或移除。输入内容已保留。",
    logNeedConsent: "请先检查日志，并单独勾选仅授权项目维护者查看。输入内容已保留。",
    logBytes: "UTF-8 字节数",
    consent: "我已检查内容，并同意标题、描述和可选摘要公开发布为 GitHub Issue。提交后无法通过此页面撤回。",
    submit: "提交反馈",
    footer: "无需 GitHub 账号。草稿只保留在当前页面内存中，关闭或刷新页面会丢失。公开的 GitHub Issue 由维护者按仓库规则管理。",
    loading: "正在连接反馈服务…",
    ready: "请填写问题，并完成安全验证。",
    unavailable: "反馈服务暂不可用，无法提交。输入内容保留在当前页面；请稍后重试。",
    needConsent: "请先检查内容并明确同意公开发布。输入内容已保留。",
    invalid: "请检查标题、描述的长度及安全摘要格式。输入内容已保留。",
    challenge: "请完成安全验证；验证过期后请重新验证。输入内容已保留。",
    sending: "正在提交，请勿重复操作…",
    received: "已接收，待处理。这不表示 GitHub Issue 已创建。请保存回执：",
    rate: "提交过于频繁或今日额度已用完，请稍后重新验证并重试。输入内容已保留。",
    failed: "暂时无法确认接收，请重新验证后重试；重试将使用同一提交编号避免重复。输入内容已保留。",
    conflict: "此提交编号已接收不同内容，不能覆盖原报告。输入内容已保留；请手动复制草稿后刷新页面，以发起另一份反馈。",
  },
  en: {
    heading: "Report a problem",
    intro: "No registration or sign-in required. Do not include passwords, account details, library contents or other personal information.",
    privacy: "The title, description and optional safe summary are forwarded to public GitHub Issues with your consent. Crash logs require separate permission and are accessible only to project maintainers, never sent to GitHub. Database reports, private logs and keyed IP hashes are normally retained for up to 30 days; public Issues are outside this period. No email collection. Cloudflare processes network requests and security checks.",
    title: "Problem title (5–120 characters)",
    titleHelp: "Briefly describe the problem.",
    description: "Description (20–6000 characters)",
    descriptionHelp: "What did you do, what did you expect, and what happened? Use fictional examples.",
    summary: "Safe application summary (optional, up to 1000 characters)",
    summaryHelp: "Manually paste the five-line summary copied from the app's problem report dialog. Only Application, Version, OS, Architecture and Runtime are accepted. The clipboard is never read automatically.",
    logHeading: "Private crash log (optional)",
    logHelp: "Select a UTF-8 .txt, .log or .log.1/.2/.3 file, preview it locally and remove sensitive details. Preview limit: 4 MiB; submission limit: 256 KiB. Logs are only for project maintainers and never sent to GitHub.",
    logFile: "Choose log file",
    logPreview: "Editable local preview",
    logRemove: "Remove log",
    logConsent: "I have checked this log and authorize only project maintainers to view it. It is normally retained for up to 30 days and never sent to GitHub. Editing, replacing or removing it resets this permission.",
    logEmpty: "No log attached. You can send feedback without a log.",
    logReading: "Reading locally; nothing has been uploaded…",
    logLoaded: "Local preview only; not uploaded. Review the contents and grant separate permission.",
    logReceived: "Received. This log is accessible to project maintainers only and will not be sent to GitHub.",
    logEdited: "Preview changed. Review it and grant permission again; not uploaded.",
    logRemoved: "Log removed. It will not be submitted with the report.",
    logUnsupported: "Only .txt, .log and .log.1/.2/.3 text files are supported; ZIP is not accepted. Previous preview preserved.",
    logFileLarge: "File exceeds 4 MiB and was not read. Shorten it locally first; previous preview preserved.",
    logUtf8: "Not valid UTF-8 text; file not attached. Previous preview preserved.",
    logBinary: "The file contains binary controls or invalid text and was not attached. Previous preview preserved.",
    logLimit: "Log exceeds 256 KiB. Edit it shorter or remove it; it will not be silently truncated. Your input is preserved.",
    logInvalid: "Log contains invalid text or binary controls. Edit or remove it. Your input is preserved.",
    logNeedConsent: "Review the log and separately grant permission for project maintainers only. Your input is preserved.",
    logBytes: "UTF-8 bytes",
    consent: "I have reviewed the contents and consent to publishing the title, description and optional summary as a public GitHub Issue. This page cannot withdraw a submitted report.",
    submit: "Send feedback",
    footer: "No GitHub account required. Your draft stays only in this page's memory and is lost when you close or reload the page. Public GitHub Issues are managed by the maintainer under repository rules.",
    loading: "Connecting to the feedback service…",
    ready: "Describe the problem and complete the security check.",
    unavailable: "The feedback service is temporarily unavailable. Your input is preserved on this page; please try again later.",
    needConsent: "Please review the contents and explicitly consent to public publication. Your input is preserved.",
    invalid: "Check the title and description lengths and the safe summary format. Your input is preserved.",
    challenge: "Complete the security check, or repeat it if it has expired. Your input is preserved.",
    sending: "Sending. Please do not submit again…",
    received: "Received, pending processing. This does not mean a GitHub Issue has been created. Save your receipt: ",
    rate: "The request limit has been reached. Repeat the security check and try later. Your input is preserved.",
    failed: "Receipt could not be confirmed. Repeat the security check and retry; the same submission ID prevents duplicates. Your input is preserved.",
    conflict: "This submission ID already received different contents and cannot overwrite the report. Your input is preserved; manually copy your draft and reload to start another report.",
  },
};

function validInput(title, description, summary) {
  if ([...title].length < 5 || [...title].length > 120 || /[\p{Cc}\p{Cf}]/u.test(title) ||
      [...description].length < 20 || [...description].length > 6000 ||
      /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(description) || [...summary].length > 1000) return false;
  const seen = new Set();
  for (const line of summary ? summary.split("\n") : []) {
    const match = /^(Application|Version|OS|Architecture|Runtime): (.{1,200})$/u.exec(line);
    if (!match || seen.has(match[1])) return false;
    const sentinel = (match[1] === "Version" && match[2] === "[redacted]") ||
      (match[1] === "OS" && /^(?:Android|Windows|macOS|Linux|Unknown) \[redacted\]$/.test(match[2])) ||
      (match[1] === "Runtime" && /^(?:Android SDK|Java) \[redacted\]$/.test(match[2]));
    if (!/^[\p{L}\p{N} ._+()/:\-]+$/u.test(match[2]) && !sentinel) return false;
    seen.add(match[1]);
  }
  return true;
}

export async function initializeFeedback(window = globalThis.window, document = window.document) {
  const form = document.getElementById("feedback");
  const button = document.getElementById("submit");
  const status = document.getElementById("status");
  const languageButton = document.getElementById("language");
  const fields = Object.fromEntries(["title", "description", "summary", "consent"].map((key) => [key, document.getElementById(key)]));
  const logFile = document.getElementById("crash-log-file");
  const logPreview = document.getElementById("crash-log");
  const logConsent = document.getElementById("crash-log-consent");
  const logRemove = document.getElementById("crash-log-remove");
  const logStatus = document.getElementById("crash-log-status");
  let language = "zh";
  let message = "loading";
  let accepting = false;
  let busy = false;
  let received = false;
  let receipt = "";
  let token = "";
  let widget;
  let logState = "logEmpty";
  let logReading = false;
  let logGeneration = 0;
  const submissionId = window.crypto.randomUUID();

  function render() {
    document.documentElement.lang = language === "zh" ? "zh-CN" : "en";
    for (const element of document.querySelectorAll("[data-text]")) element.textContent = strings[language][element.dataset.text];
    languageButton.textContent = language === "zh" ? "English" : "中文";
    languageButton.lang = language === "zh" ? "en" : "zh-CN";
    status.textContent = strings[language][message] + (message === "received" ? receipt : "");
    button.disabled = !accepting || busy || received || logReading;
    for (const field of Object.values(fields)) field.disabled = busy || received;
    logFile.disabled = busy || received;
    logPreview.disabled = busy || received;
    logConsent.disabled = busy || received || logPreview.value === "" || logReading;
    logRemove.disabled = busy || received || (logPreview.value === "" && !logReading);
    const bytes = crashLogBytes(logPreview.value);
    document.getElementById("crash-log-bytes").textContent = `${strings[language].logBytes}: ${bytes} / ${MAX_LOG_BYTES}`;
    logStatus.textContent = strings[language][received && logPreview.value !== "" ? "logReceived" : logState] + (bytes > MAX_LOG_BYTES ? ` ${strings[language].logLimit}` : "");
    logStatus.dataset.state = logReading ? "reading" : logState;
    form.setAttribute("aria-busy", String(busy));
  }

  languageButton.addEventListener("click", () => { language = language === "zh" ? "en" : "zh"; render(); });
  logPreview.addEventListener("input", () => {
    if (busy || received) return;
    logGeneration++;
    logReading = false;
    logConsent.checked = false;
    logState = logPreview.value === "" ? "logEmpty" : "logEdited";
    render();
  });
  logRemove.addEventListener("click", () => {
    if (busy || received) return;
    logGeneration++;
    logReading = false;
    logFile.value = "";
    logPreview.value = "";
    logConsent.checked = false;
    logState = "logRemoved";
    render();
    logPreview.focus();
  });
  logFile.addEventListener("change", () => {
    if (busy || received) return;
    const generation = ++logGeneration;
    logConsent.checked = false;
    logReading = false;
    const file = logFile.files?.[0];
    if (!file) { render(); return; }
    if (!/(?:\.txt|\.log(?:\.[123])?)$/i.test(file.name)) {
      logState = "logUnsupported";
      logFile.value = "";
      render();
      return;
    }
    if (file.size > MAX_PREVIEW_BYTES) {
      logState = "logFileLarge";
      logFile.value = "";
      render();
      return;
    }
    logReading = true;
    logState = "logReading";
    render();
    const reader = new window.FileReader();
    reader.addEventListener("load", () => {
      if (generation !== logGeneration) return;
      try {
        const text = normalizeCrashLog(new TextDecoder("utf-8", { fatal: true }).decode(reader.result));
        if (!validCrashLogText(text)) logState = "logBinary";
        else { logPreview.value = text; logState = "logLoaded"; }
      } catch { logState = "logUtf8"; }
      logReading = false;
      logFile.value = "";
      render();
    }, { once: true });
    reader.addEventListener("error", () => {
      if (generation !== logGeneration) return;
      logReading = false;
      logState = "logUtf8";
      logFile.value = "";
      render();
    }, { once: true });
    reader.readAsArrayBuffer(file);
  });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!accepting || busy || received || logReading) return;
    if (!fields.consent.checked) { message = "needConsent"; render(); return; }
    const title = fields.title.value.trim();
    const description = fields.description.value.trim();
    const summary = fields.summary.value.replace(/\r\n/g, "\n").trim();
    const crashLog = normalizeCrashLog(logPreview.value);
    if (!validInput(title, description, summary)) { message = "invalid"; render(); return; }
    if (!validCrashLogText(crashLog)) { message = "logInvalid"; render(); return; }
    if (crashLogBytes(crashLog) > MAX_LOG_BYTES) { message = "logLimit"; render(); return; }
    if (crashLog !== "" && !logConsent.checked) { message = "logNeedConsent"; render(); return; }
    if (!token) { message = "challenge"; render(); return; }
    busy = true;
    message = "sending";
    render();
    try {
      const response = await window.fetch("/api/feedback", {
        method: "POST",
        credentials: "omit",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ submissionId, title, description, summary, consent: true, turnstileToken: token,
          ...(crashLog !== "" ? { crashLog, crashLogConsent: true } : {}),
        }),
        signal: window.AbortSignal.timeout(20000),
      });
      const result = await response.json();
      if (response.status === 202 && result.status === "received_pending" && typeof result.receipt === "string" && result.receipt.length <= 128) {
        receipt = result.receipt;
        received = true;
        message = "received";
      } else if (response.status === 429) message = "rate";
      else if (response.status === 409) message = "conflict";
      else if (response.status === 400 && result.error === "verification_failed") message = "challenge";
      else if (response.status === 400) message = "invalid";
      else message = "failed";
    } catch { message = "failed"; }
    finally {
      busy = false;
      token = "";
      if (!received && widget !== undefined) window.turnstile.reset(widget);
      render();
      status.focus();
    }
  });
  render();
  try {
    const response = await window.fetch("/api/config", { cache: "no-store", credentials: "omit", signal: window.AbortSignal.timeout(10000) });
    if (!response.ok) throw new Error("unavailable");
    const config = await response.json();
    if (config.accepting !== true || typeof config.siteKey !== "string") throw new Error("unavailable");
    const mount = () => {
      try {
        widget = window.turnstile.render(document.getElementById("challenge"), {
          sitekey: config.siteKey,
          action: "feedback",
          theme: "auto",
          callback(value) { token = value; },
          "expired-callback"() { token = ""; if (!received && !busy) { message = "challenge"; render(); } },
          "error-callback"() { token = ""; if (!received && !busy) { message = "challenge"; render(); } },
        });
        accepting = true;
        message = "ready";
      } catch { message = "unavailable"; }
      render();
    };
    if (window.turnstile) mount();
    else {
      const script = document.createElement("script");
      script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
      script.async = true;
      script.addEventListener("load", mount, { once: true });
      script.addEventListener("error", () => { message = "unavailable"; render(); }, { once: true });
      document.head.append(script);
    }
  } catch { message = "unavailable"; render(); }
}
