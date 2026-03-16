async function loadOptions(selectId, loader, emptyLabel) {
    const selectElement = document.getElementById(selectId);
    if (!selectElement || typeof loader !== 'function') {
        return;
    }

    try {
        const items = await loader();
        selectElement.replaceChildren();

        if (!Array.isArray(items) || items.length === 0) {
            const option = document.createElement('option');
            option.value = '';
            option.textContent = emptyLabel;
            selectElement.appendChild(option);
            return;
        }

        items.forEach((item) => {
            const option = document.createElement('option');
            option.value = item;
            option.textContent = item;
            selectElement.appendChild(option);
        });
    } catch (error) {
        console.error(`Failed to load ${selectId}:`, error);
    }
}

function setStatus(message, isError = false) {
    const status = document.getElementById('status-msg');
    if (!status) {
        return;
    }

    status.textContent = message || '';
    status.style.color = isError ? '#d64545' : '#1c9b63';
}

async function selectFile(targetId) {
    const api = window.electronAPI;
    if (!api || typeof api.openFileDialog !== 'function') {
        return;
    }

    try {
        const filePath = await api.openFileDialog();
        if (filePath) {
            const input = document.getElementById(targetId);
            if (input) {
                input.value = filePath;
            }
        }
    } catch (error) {
        console.error('Failed to select file:', error);
    }
}

document.addEventListener('DOMContentLoaded', async () => {
    const api = window.electronAPI;
    const confirmButton = document.getElementById('confirm-btn');

    if (!api || !confirmButton) {
        return;
    }

    await Promise.all([
        loadOptions('preset-select', api.getPresetRoles, '未找到预设角色'),
        loadOptions('template-select', api.getTemplate, '未找到模板'),
    ]);

    document.getElementById('browse-world-btn').addEventListener('click', async () => {
        await selectFile('world-book-path');
    });

    document.getElementById('browse-profile-btn').addEventListener('click', async () => {
        await selectFile('profile-path');
    });

    confirmButton.addEventListener('click', async () => {
        const presetName = document.getElementById('preset-select').value;
        const newName = document.getElementById('new-role-name').value.trim();
        const worldPath = document.getElementById('world-book-path').value;
        const templateId = document.getElementById('template-select').value;
        const profilePath = document.getElementById('profile-path').value;

        const finalName = newName !== '' ? newName : presetName;
        if (!finalName) {
            setStatus('请先选择一个现有角色，或输入一个新角色名称。', true);
            return;
        }

        const params = {
            name: finalName,
            role: templateId,
            worldPath,
            profilePath,
            hasWorld: worldPath !== '',
            hasProfile: profilePath !== '',
        };

        confirmButton.disabled = true;
        setStatus('正在连接后端并启动会话...');

        try {
            const result = await api.executeRoleStart(params);
            if (result && result.success) {
                window.close();
                return;
            }

            const message = result && result.error
                ? result.error
                : '启动失败，请检查后端服务是否已运行。';
            setStatus(message, true);
        } catch (error) {
            console.error('Failed to start the selected role:', error);
            setStatus(error.message || '启动失败，请检查后端服务是否已运行。', true);
        } finally {
            confirmButton.disabled = false;
        }
    });

    document.getElementById('cancel-btn').addEventListener('click', () => {
        window.close();
    });
});
