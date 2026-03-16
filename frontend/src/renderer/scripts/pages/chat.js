const CHAT_WIDTH_CLOSED = 800;
const CHAT_WIDTH_OPEN = 1020;

function appendMessage(container, sender, text) {
    const message = document.createElement('div');
    if (sender === 'User') {
        message.className = 'message user';
    } else if (sender === 'AI') {
        message.className = 'message ai';
    } else {
        message.className = 'message system';
    }

    message.textContent = text;
    container.appendChild(message);
    container.scrollTo({
        top: container.scrollHeight,
        behavior: 'smooth',
    });
}

document.addEventListener('DOMContentLoaded', () => {
    const api = window.electronAPI && window.electronAPI.chat;
    const sendBtn = document.getElementById('sendBtn');
    const input = document.getElementById('msgInput');
    const multimodalToggle = document.getElementById('multimodalToggle');
    const ttsOpen = document.getElementById('tts')
    const container = document.getElementById('chat-container');
    const toggleBtn = document.getElementById('togglePanelBtn');
    const sidePanel = document.getElementById('side-panel');
    const clearBtn = document.getElementById('clearBtn');
    const modalOverlay = document.getElementById('modal-overlay');
    const cancelClear = document.getElementById('cancelClear');
    const confirmClear = document.getElementById('confirmClear');

    if (!api || !sendBtn || !input || !multimodalToggle || !tts || !container || !toggleBtn || !sidePanel || !clearBtn) {
        return;
    }

    let isPanelOpen = false;
    const removeReplyListener = api.onReply((res = {}) => {
        if (res.status === 'success') {
            appendMessage(container, 'AI', String(res.content || ''));
            return;
        }

        appendMessage(container, 'System', `发生错误: ${String(res.message || '未知错误')}`);
    });

    function setPanelOpen(open) {
        isPanelOpen = open;
        sidePanel.classList.toggle('open', open);
        toggleBtn.classList.toggle('active', open);
        toggleBtn.textContent = open ? '收起功能' : '展开功能';
        api.resize(open ? CHAT_WIDTH_OPEN : CHAT_WIDTH_CLOSED);
    }

    function sendMessage() {
        const message = input.value.trim();
        if (!message) {
            return;
        }

        const captureDesktop = !!multimodalToggle.checked;
        const tts = !!ttsOpen.checked;
        appendMessage(
            container,
            'User',
            captureDesktop ? `${message}\n[已开启多模态：附带当前桌面截图]` : message,
        );
        api.sendMessage(message, {captureDesktop, tts});
        input.value = '';
    }

    input.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            sendMessage();
        }
    });

    sendBtn.addEventListener('click', sendMessage);
    toggleBtn.addEventListener('click', () => {
        setPanelOpen(!isPanelOpen);
    });

    clearBtn.addEventListener('click', () => {
        modalOverlay.style.display = 'flex';
    });

    cancelClear.addEventListener('click', () => {
        modalOverlay.style.display = 'none';
    });

    confirmClear.addEventListener('click', () => {
        container.replaceChildren();
        appendMessage(container, 'System', '已重置当前会话。');
        modalOverlay.style.display = 'none';
    });

    modalOverlay.addEventListener('click', (event) => {
        if (event.target === modalOverlay) {
            modalOverlay.style.display = 'none';
        }
    });

    document.querySelectorAll('.side-btn').forEach((button) => {
        button.addEventListener('click', () => {
            api.openWindow(button.getAttribute('data-type'));
        });
    });

    setPanelOpen(false);

    window.addEventListener('beforeunload', () => {
        if (typeof removeReplyListener === 'function') {
            removeReplyListener();
        }
    });
});
