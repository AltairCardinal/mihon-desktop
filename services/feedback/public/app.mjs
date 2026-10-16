const strings = {
  zh: {
    heading: "问题反馈",
    intro: "无需注册或登录。请勿填写密码、账号、书库内容或其他个人信息。",
    privacy: "本服务接收匿名反馈，并在你同意后公开转交 GitHub。服务数据库中的报告及用于限流的 IP 不可逆摘要通常保留最多 30 天；已公开的 Issue 不受此期限限制。不收集邮箱，不支持附件。Cloudflare 处理网络请求及安全验证。",
    title: "问题标题（5–120 字符）",
    titleHelp: "简要说明遇到的问题。",
    description: "问题描述（20–6000 字符）",
    descriptionHelp: "你做了什么？原本预期什么？实际发生什么？请使用虚构示例。",
    summary: "应用安全摘要（可选，最多 1000 字符）",
    summaryHelp: "可手动粘贴应用“问题上报”中复制的五行摘要。仅接受 Application、Version、OS、Architecture、Runtime；不会自动读取剪贴板。",
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
    privacy: "This service receives anonymous reports and forwards them to public GitHub Issues with your consent. Database reports and keyed IP hashes are normally retained for up to 30 days; public Issues are outside this retention period. No email collection or attachments. Cloudflare processes network requests and security checks.",
    title: "Problem title (5–120 characters)",
    titleHelp: "Briefly describe the problem.",
    description: "Description (20–6000 characters)",
    descriptionHelp: "What did you do, what did you expect, and what happened? Use fictional examples.",
    summary: "Safe application summary (optional, up to 1000 characters)",
    summaryHelp: "Manually paste the five-line summary copied from the app's problem report dialog. Only Application, Version, OS, Architecture and Runtime are accepted. The clipboard is never read automatically.",
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
  let language = "zh";
  let message = "loading";
  let accepting = false;
  let busy = false;
  let received = false;
  let receipt = "";
  let token = "";
  let widget;
  const submissionId = window.crypto.randomUUID();

  function render() {
    document.documentElement.lang = language === "zh" ? "zh-CN" : "en";
    for (const element of document.querySelectorAll("[data-text]")) element.textContent = strings[language][element.dataset.text];
    languageButton.textContent = language === "zh" ? "English" : "中文";
    languageButton.lang = language === "zh" ? "en" : "zh-CN";
    status.textContent = strings[language][message] + (message === "received" ? receipt : "");
    button.disabled = !accepting || busy || received;
    form.setAttribute("aria-busy", String(busy));
  }

  languageButton.addEventListener("click", () => { language = language === "zh" ? "en" : "zh"; render(); });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!accepting || busy || received) return;
    if (!fields.consent.checked) { message = "needConsent"; render(); return; }
    const title = fields.title.value.trim();
    const description = fields.description.value.trim();
    const summary = fields.summary.value.replace(/\r\n/g, "\n").trim();
    if (!validInput(title, description, summary)) { message = "invalid"; render(); return; }
    if (!token) { message = "challenge"; render(); return; }
    busy = true;
    message = "sending";
    render();
    try {
      const response = await window.fetch("/api/feedback", {
        method: "POST",
        credentials: "omit",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ submissionId, title, description, summary, consent: true, turnstileToken: token }),
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
