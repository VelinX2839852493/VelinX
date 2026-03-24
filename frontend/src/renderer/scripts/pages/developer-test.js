const DEFAULT_MESSAGE = '开发者测试：请回复 index 已启动';

function setStatus(message, isError = false) {
    const status = document.getElementById('developer-test-status');
    if (!status) {
        return;
    }

    status.textContent = message || '';
    status.style.color = isError ? '#d64545' : 'var(--vx-ink-soft)';
}

function setBusy(isBusy) {
    const button = document.getElementById('developer-test-run');
    if (button) {
        button.disabled = isBusy;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const api = window.electronAPI && window.electronAPI.developerTest;
    const button = document.getElementById('developer-test-run');
    const messageInput = document.getElementById('developer-test-message');

    if (!api || !button || !messageInput) {
        return;
    }

    if (!messageInput.value.trim()) {
        messageInput.value = DEFAULT_MESSAGE;
    }

    button.addEventListener('click', async () => {
        const message = messageInput.value.trim() || DEFAULT_MESSAGE;

        setBusy(true);
        setStatus('正在启动后端并发送测试消息...');

        try {
            const result = await api.run({message});
            if (!result || !result.success) {
                setStatus(
                    result && result.error ? result.error : '启动测试失败，请检查后端服务。',
                    true
                );
                return;
            }

            const data = result.data || {};
            const lines = [
                data.messageQueued ? '测试消息已投递到后端。' : '测试消息未成功投递。',
                `Index 地址: ${data.indexUrl || 'http://localhost:38080'}`,
                data.indexOpened ? 'Index 页面已请求打开。' : 'Index 页面未能自动打开。',
            ];

            if (data.warning) {
                lines.push(`提示: ${data.warning}`);
            }

            setStatus(lines.join('\n'));
        } catch (error) {
            console.error('Failed to run developer startup test:', error);
            setStatus(error.message || '启动测试失败，请检查后端服务。', true);
        } finally {
            setBusy(false);
        }
    });
});
