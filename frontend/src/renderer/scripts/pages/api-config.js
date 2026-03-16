function setStatus(message, isError = false) {
    const status = document.getElementById('status-msg');
    if (!status) {
        return;
    }

    status.textContent = message || '';
    status.style.color = isError ? '#d64545' : '#1c9b63';
}

function readForm() {
    const mode = document.getElementById('backend-mode');
    const baseUrl = document.getElementById('backend-base-url');
    const timeoutMs = document.getElementById('backend-timeout');

    return {
        mode: mode ? mode.value : 'http',
        baseUrl: baseUrl ? baseUrl.value.trim() : '',
        timeoutMs: timeoutMs ? timeoutMs.value.trim() : '',
    };
}

function writeForm(config = {}) {
    const mode = document.getElementById('backend-mode');
    const baseUrl = document.getElementById('backend-base-url');
    const timeoutMs = document.getElementById('backend-timeout');

    if (mode) {
        mode.value = config.mode || 'http';
    }

    if (baseUrl) {
        baseUrl.value = config.baseUrl || '';
    }

    if (timeoutMs) {
        timeoutMs.value = config.timeoutMs || 30000;
    }
}

function setBusy(isBusy) {
    ['save-btn', 'test-btn'].forEach((id) => {
        const button = document.getElementById(id);
        if (button) {
            button.disabled = isBusy;
        }
    });
}

document.addEventListener('DOMContentLoaded', async () => {
    const api = window.electronAPI && window.electronAPI.apiConfig;
    const saveButton = document.getElementById('save-btn');
    const testButton = document.getElementById('test-btn');

    if (!api || !saveButton || !testButton) {
        return;
    }

    async function load() {
        try {
            const config = await api.load();
            writeForm(config || {});
        } catch (error) {
            console.error('Failed to load backend config:', error);
            setStatus('读取配置失败。', true);
        }
    }

    testButton.addEventListener('click', async () => {
        setBusy(true);
        setStatus('正在测试连接...');

        try {
            const result = await api.testConnection(readForm());
            if (result && result.success) {
                const data = result.data || {};
                const service = data.service ? `服务: ${data.service}` : '服务可达';
                const version = data.version ? `，版本: ${data.version}` : '';
                setStatus(`连接成功。${service}${version}`);
                return;
            }

            setStatus(
                result && result.error ? result.error : '连接失败，请检查后端服务。',
                true
            );
        } catch (error) {
            console.error('Failed to test backend connection:', error);
            setStatus(error.message || '连接失败，请检查后端服务。', true);
        } finally {
            setBusy(false);
        }
    });

    saveButton.addEventListener('click', async () => {
        setBusy(true);
        setStatus('正在保存配置...');

        try {
            const result = await api.save(readForm());
            if (result && result.success) {
                writeForm(result.config || {});
                setStatus('后端接入配置已保存。');
                window.setTimeout(() => setStatus(''), 3000);
                return;
            }

            setStatus(
                result && result.error ? result.error : '保存失败，请检查填写内容。',
                true
            );
        } catch (error) {
            console.error('Failed to save backend config:', error);
            setStatus(error.message || '保存失败，请检查填写内容。', true);
        } finally {
            setBusy(false);
        }
    });

    await load();
});
