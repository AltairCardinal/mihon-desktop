const CLEARANCE_COOKIE_NAME = "cf_clearance";

export function clearancePermissionFor(rawUrl) {
    try {
        const url = new URL(rawUrl);
        if ((url.protocol !== "http:" && url.protocol !== "https:") || !url.hostname) return null;
        return {
            host: url.hostname,
            origin: `${url.protocol}//${url.hostname}/*`,
            url: url.href,
        };
    } catch {
        return null;
    }
}

export async function acquireClearance({ api, clipboard, tab }) {
    const target = clearancePermissionFor(tab?.url);
    if (!target) return { status: "unsupported-page", host: "" };

    const currentSitePermission = { origins: [target.origin] };
    const granted = await api.permissions.request(currentSitePermission);
    if (!granted) return { status: "permission-denied", host: target.host };

    try {
        const stores = await api.cookies.getAllCookieStores();
        const storeId = stores.find((store) => store.tabIds.includes(tab.id))?.id;
        const details = {
            url: target.url,
            name: CLEARANCE_COOKIE_NAME,
            ...(storeId === undefined ? {} : { storeId }),
        };
        const cookie = await api.cookies.get(details);
        if (!cookie?.value) return { status: "cookie-missing", host: target.host };

        await clipboard.writeText(cookie.value);
        return { status: "copied", host: target.host };
    } finally {
        await api.permissions.remove(currentSitePermission).catch(() => false);
    }
}
