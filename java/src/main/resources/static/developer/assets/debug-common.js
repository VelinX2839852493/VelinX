const DebugApi = {
    async request(url, options = {}) {
        const response = await fetch(url, {
            headers: {
                "Accept": "application/json",
                ...(options.headers || {})
            },
            ...options
        });

        const contentType = response.headers.get("content-type") || "";
        const isJson = contentType.includes("application/json");
        const payload = isJson ? await response.json() : await response.text();

        if (!response.ok) {
            const error = new Error(DebugApi.extractError(payload, response.statusText));
            error.status = response.status;
            error.payload = payload;
            throw error;
        }

        if (isJson && payload && typeof payload === "object" && "ok" in payload) {
            return payload.data;
        }

        return payload;
    },

    extractError(payload, fallback) {
        if (!payload) {
            return fallback || "Request failed";
        }
        if (typeof payload === "string") {
            return payload;
        }
        if (payload.error && payload.error.message) {
            return payload.error.message;
        }
        if (payload.message) {
            return payload.message;
        }
        return fallback || "Request failed";
    },

    getStatus() {
        return this.request("/api/bot/debug/status");
    },

    getConfig() {
        return this.request("/api/bot/debug/config");
    },

    saveModelConfig(payload) {
        return this.request("/api/bot/debug/config/model", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
    },

    saveTtsConfig(payload) {
        return this.request("/api/bot/debug/config/tts", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
    },

    testTts(text) {
        return this.request("/api/bot/debug/tts", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text })
        });
    },

    sendBot(payload) {
        return this.request("/api/bot/send", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
    }
};

const DebugUi = {
    showBanner(element, tone, text) {
        if (!element) {
            return;
        }
        if (!text) {
            element.className = "banner";
            element.textContent = "";
            return;
        }
        element.className = `banner ${tone || "warn"}`;
        element.textContent = text;
    },

    applyPill(element, tone, text) {
        if (!element) {
            return;
        }
        element.className = `status-pill ${tone || ""}`.trim();
        element.textContent = text;
    },

    writeMetaRows(element, rows) {
        if (!element) {
            return;
        }
        element.innerHTML = rows.map((row) => `
            <div class="meta-row">
                <div class="meta-key">${row.key}</div>
                <div class="meta-value">${DebugUi.escapeHtml(row.value || "未设置")}</div>
            </div>
        `).join("");
    },

    appendLog(element, title, detail, tone = "warn") {
        if (!element) {
            return;
        }
        const wrapper = document.createElement("div");
        wrapper.className = `log-entry ${tone}`;
        wrapper.innerHTML = `
            <div class="log-title">${DebugUi.escapeHtml(title)}</div>
            <div class="log-detail">${DebugUi.escapeHtml(detail || "")}</div>
        `;
        element.prepend(wrapper);
    },

    formatMasked(masked, hasApiKey) {
        return hasApiKey ? (masked || "已配置") : "未配置";
    },

    formatDuration(durationMs) {
        if (durationMs === null || durationMs === undefined) {
            return "-";
        }
        return `${durationMs} ms`;
    },

    toneByReady(ready, configured) {
        if (ready) {
            return "ok";
        }
        return configured ? "warn" : "error";
    },

    summarizeTtsEvent(event) {
        const outcome = event.success ? "成功" : "失败";
        const base = `${outcome} · ${DebugUi.formatDuration(event.durationMs)} · ${event.text || "无文本"}`;
        return event.error ? `${base}\n${event.error}` : base;
    },

    escapeHtml(value) {
        return String(value)
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&#39;");
    }
};

window.DebugApi = DebugApi;
window.DebugUi = DebugUi;
