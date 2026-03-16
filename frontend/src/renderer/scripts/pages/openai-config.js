function setStatus(message, isError = false) {
    const status = document.getElementById('status-msg');
    if (!status) {
        return;
    }

    status.textContent = message || '';
    status.style.color = isError ? '#d64545' : '#1c9b63';
}

function setFilePath(filePath) {
    const pathNode = document.getElementById('openai-config-path');
    if (!pathNode) {
        return;
    }

    pathNode.textContent = filePath || '未找到配置文件路径。';
}

function readForm() {
    return {
        API_KEY_q: document.getElementById('api-key-q')?.value.trim() || '',
        BASE_URL_q: document.getElementById('base-url-q')?.value.trim() || '',
        MODEL_NAME_q: document.getElementById('model-name-q')?.value.trim() || '',
        API_KEY_b: document.getElementById('api-key-b')?.value.trim() || '',
        BASE_URL_b: document.getElementById('base-url-b')?.value.trim() || '',
        MODEL_NAME_b: document.getElementById('model-name-b')?.value.trim() || '',
    };
}

function writeForm(config = {}) {
    const mappings = {
        'api-key-q': config.API_KEY_q || '',
        'base-url-q': config.BASE_URL_q || '',
        'model-name-q': config.MODEL_NAME_q || '',
        'api-key-b': config.API_KEY_b || '',
        'base-url-b': config.BASE_URL_b || '',
        'model-name-b': config.MODEL_NAME_b || '',
    };

    Object.entries(mappings).forEach(([id, value]) => {
        const element = document.getElementById(id);
        if (element) {
            element.value = value;
        }
    });
}

function validateOptionalUrl(value, label) {
    if (!value) {
        return;
    }

    let parsedUrl;
    try {
        parsedUrl = new URL(value);
    } catch (_error) {
        throw new Error(`${label} 必须是合法的 http/https 地址。`);
    }

    if (parsedUrl.protocol !== 'http:' && parsedUrl.protocol !== 'https:') {
        throw new Error(`${label} 必须使用 http 或 https 协议。`);
    }
}

function validateForm(payload) {
    validateOptionalUrl(payload.BASE_URL_q, 'Q 通道 Base URL');
    validateOptionalUrl(payload.BASE_URL_b, 'B 通道 Base URL');
    return payload;
}

function setBusy(isBusy) {
    ['reload-btn', 'save-btn'].forEach((id) => {
        const button = document.getElementById(id);
        if (button) {
            button.disabled = isBusy;
        }
    });
}

document.addEventListener('DOMContentLoaded', async () => {
    const api = window.electronAPI && window.electronAPI.openaiConfig;
    const reloadButton = document.getElementById('reload-btn');
    const saveButton = document.getElementById('save-btn');

    if (!api || !reloadButton || !saveButton) {
        return;
    }

    async function loadConfig() {
        setBusy(true);
        setStatus('正在读取配置...');

        try {
            const result = await api.load();
            setFilePath(result && result.filePath ? result.filePath : '');

            if (!result || !result.success) {
                setStatus(
                    result && result.error ? result.error : '读取配置失败。',
                    true
                );
                return;
            }

            writeForm(result.config || {});
            setStatus('配置已读取。');
            window.setTimeout(() => setStatus(''), 2500);
        } catch (error) {
            console.error('Failed to load OpenAI config file:', error);
            setStatus(error.message || '读取配置失败。', true);
        } finally {
            setBusy(false);
        }
    }

    reloadButton.addEventListener('click', async () => {
        await loadConfig();
    });

    saveButton.addEventListener('click', async () => {
        let payload;
        try {
            payload = validateForm(readForm());
        } catch (error) {
            setStatus(error.message || '表单校验失败。', true);
            return;
        }

        setBusy(true);
        setStatus('正在保存配置...');

        try {
            const result = await api.save(payload);
            setFilePath(result && result.filePath ? result.filePath : '');

            if (!result || !result.success) {
                setStatus(
                    result && result.error ? result.error : '保存失败，请检查填写内容。',
                    true
                );
                return;
            }

            writeForm(result.config || payload);
            setStatus('API 配置文件已保存。');
            window.setTimeout(() => setStatus(''), 3000);
        } catch (error) {
            console.error('Failed to save OpenAI config file:', error);
            setStatus(error.message || '保存失败，请检查填写内容。', true);
        } finally {
            setBusy(false);
        }
    });

    await loadConfig();
});
