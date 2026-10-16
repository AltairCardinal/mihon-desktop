const copy = {
  zh: {
    heading: "提交安全验证", explanation: "问题内容和日志保留在应用中，本页只进行安全验证，不会接收这些内容。",
    button: "完成验证", loading: "正在连接验证服务…", ready: "请完成安全验证，再点击“完成验证”。",
    invalid: "验证链接无效，请返回应用重新提交。", unavailable: "验证服务暂不可用，请返回应用重试。",
    sending: "正在验证…", failed: "验证失败或会话已过期，请重新验证，或返回应用重试。",
    done: "验证完成。请返回应用，程序将继续提交。",
  },
  en: {
    heading: "Submission verification", explanation: "Your report and log stay in the app. This page only verifies the submission and does not receive those contents.",
    button: "Complete verification", loading: "Connecting to verification…", ready: "Complete the security challenge, then select Complete verification.",
    invalid: "Invalid verification link. Return to the app and try again.", unavailable: "Verification is unavailable. Return to the app and try again.",
    sending: "Verifying…", failed: "Verification failed or the session expired. Complete a fresh challenge or return to the app to retry.",
    done: "Verified. Please return to the app; it will continue submitting your report.",
  },
};

export async function initializeNativeVerification(window = globalThis.window, document = window.document) {
  const id = window.location.hash.slice(1);
  const valid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id);
  const button = document.getElementById("verify");
  const status = document.getElementById("status");
  const languageButton = document.getElementById("language");
  let language = "zh"; let state = valid ? "loading" : "invalid";
  let token = ""; let busy = false; let done = false; let widget;
  function render() {
    document.documentElement.lang = language === "zh" ? "zh-CN" : "en";
    for (const field of ["heading", "explanation"]) document.getElementById(field).textContent = copy[language][field];
    languageButton.textContent = language === "zh" ? "English" : "中文";
    button.textContent = copy[language].button;
    button.disabled = !token || busy || done;
    status.textContent = copy[language][state];
  }
  languageButton.addEventListener("click", () => { language = language === "zh" ? "en" : "zh"; render(); });
  button.addEventListener("click", async () => {
    if (!valid || !token || busy || done) return;
    busy = true; state = "sending"; render();
    try {
      const response = await window.fetch("/api/native-verify", {
        method: "POST", credentials: "omit", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sessionId: id.toLowerCase(), turnstileToken: token }),
        signal: window.AbortSignal.timeout(20000),
      });
      const body = await response.json();
      if (response.ok && body.verified === true) { done = true; state = "done"; }
      else state = "failed";
    } catch { state = "failed"; }
    finally {
      busy = false; token = "";
      if (!done && widget !== undefined) window.turnstile.reset(widget);
      render(); status.focus();
    }
  });
  render();
  if (!valid) return;
  try {
    const response = await window.fetch("/api/config", { cache: "no-store", credentials: "omit", signal: window.AbortSignal.timeout(10000) });
    const config = await response.json();
    if (!response.ok || config.accepting !== true || typeof config.siteKey !== "string") throw new Error();
    const mount = () => {
      try {
        const expire = () => { token = ""; if (!done && !busy) { state = "failed"; render(); } };
        widget = window.turnstile.render(document.getElementById("challenge"), {
          sitekey: config.siteKey, action: "feedback", theme: "auto",
          callback(value) { if (!done && !busy) { token = value; state = "ready"; render(); } },
          "expired-callback": expire, "error-callback": expire,
        });
        state = "ready";
      } catch { state = "unavailable"; }
      render();
    };
    if (window.turnstile) mount();
    else {
      const script = document.createElement("script");
      script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
      script.async = true;
      script.addEventListener("load", mount, { once: true });
      script.addEventListener("error", () => { state = "unavailable"; render(); }, { once: true });
      document.head.append(script);
    }
  } catch { state = "unavailable"; render(); }
}
