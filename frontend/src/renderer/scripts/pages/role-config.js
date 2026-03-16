const api = window.electronAPI;
let configData = [];
let charIdx = 0;
let fieldIdx = 0;
let originalTitle = '';

function getEditor() {
    return document.getElementById('jsonEditor');
}

function showEmpty() {
    document.getElementById('charInfo').textContent = '暂无角色数据';
    document.getElementById('fieldInfo').textContent = '0 / 0';
    document.getElementById('curKeyLabel').textContent = '当前没有可编辑字段';
    getEditor().value = '';
    getEditor().disabled = true;
    document.getElementById('saveBtn').disabled = true;
    document.getElementById('deleteBtn').disabled = true;
    document.getElementById('prevChar').disabled = true;
    document.getElementById('nextChar').disabled = true;
    document.getElementById('prevField').disabled = true;
    document.getElementById('nextField').disabled = true;
}

function syncData() {
    if (configData.length === 0) {
        return;
    }

    const character = configData[charIdx];
    const keys = Object.keys(character);
    const currentKey = keys[fieldIdx];
    const editorValue = getEditor().value;
    const trimmedValue = editorValue.trim();

    try {
        if (trimmedValue.startsWith('{') || trimmedValue.startsWith('[')) {
            character[currentKey] = JSON.parse(editorValue);
        } else {
            character[currentKey] = editorValue;
        }
    } catch (_error) {
        character[currentKey] = editorValue;
    }
}

function render() {
    const character = configData[charIdx];
    const keys = Object.keys(character);

    if (fieldIdx >= keys.length) {
        fieldIdx = 0;
    }

    const currentKey = keys[fieldIdx];
    const value = character[currentKey];
    originalTitle = character.title;

    document.getElementById('charInfo').textContent = `角色: ${character.title || '未命名'}`;
    document.getElementById('fieldInfo').textContent = `${fieldIdx + 1} / ${keys.length}`;
    document.getElementById('curKeyLabel').textContent = `当前字段: ${currentKey}`;

    const editor = getEditor();
    editor.disabled = false;
    editor.value = typeof value === 'object' && value !== null
        ? JSON.stringify(value, null, 4)
        : value || '';

    document.getElementById('saveBtn').disabled = false;
    document.getElementById('deleteBtn').disabled = false;
    document.getElementById('prevChar').disabled = charIdx === 0;
    document.getElementById('nextChar').disabled = charIdx === configData.length - 1;
    document.getElementById('prevField').disabled = fieldIdx === 0;
    document.getElementById('nextField').disabled = fieldIdx === keys.length - 1;
}

async function init() {
    if (!api) {
        showEmpty();
        getEditor().value = '未检测到 electronAPI，无法读取角色配置。';
        return;
    }

    configData = await api.getAllConfigs();
    if (configData && configData.length > 0) {
        render();
    } else {
        showEmpty();
    }
}

document.getElementById('saveBtn').addEventListener('click', async () => {
    syncData();
    const character = configData[charIdx];

    if (character.title !== originalTitle) {
        const confirmed = window.confirm(
            `你修改了角色标题，原标题“${originalTitle}”将变为“${character.title}”。确认继续吗？`
        );
        if (!confirmed) {
            return;
        }

        await api.deleteConfig(originalTitle);
        await api.addConfig(character);
        originalTitle = character.title;
    } else {
        const result = await api.updateConfig(character);
        if (!result.success) {
            window.alert(`保存失败: ${result.error}`);
            return;
        }
    }

    window.alert('保存成功。');
    render();
});

document.getElementById('addBtn').addEventListener('click', async () => {
    const newRole = {
        title: `新角色_${Math.floor(Math.random() * 1000)}`,
        PERSONAL: '请在这里填写角色设定。',
        update_user: '请在这里填写用户更新策略。',
        single_summary: '请在这里填写单轮摘要提示词。',
        UPDATE_INSTRUCTION: '请在这里填写更新说明。',
        trust_PROMPT: '请在这里填写信任建立提示词。',
        SUMMARY_PROMPT: '请在这里填写总结提示词。',
        SUMMARY_ONLY_PROMPT: '请在这里填写仅总结模式提示词。',
        PROFILE: {
            name: 'Unit_01',
            age: 'Ver.1.0',
            gender: '未知',
            relationship_status: '0 | 陌生',
            health: '100% | 正常',
        },
        random_profile_prompt: '请在这里填写随机画像提示词。',
        PROFILE_mapping: {
            name: '名字',
            age: '版本',
            relationship_status: '亲密度',
        },
    };

    const result = await api.addConfig(newRole);
    if (result.success) {
        configData.push(newRole);
        charIdx = configData.length - 1;
        fieldIdx = 0;
        render();
        getEditor().focus();
    }
});

document.getElementById('deleteBtn').addEventListener('click', async () => {
    if (!window.confirm('确定要删除当前角色吗？')) {
        return;
    }

    const result = await api.deleteConfig(configData[charIdx].title);
    if (result.success) {
        configData.splice(charIdx, 1);
        charIdx = Math.max(0, charIdx - 1);
        fieldIdx = 0;

        if (configData.length === 0) {
            showEmpty();
        } else {
            render();
        }
    }
});

document.getElementById('prevChar').addEventListener('click', () => {
    syncData();
    charIdx -= 1;
    fieldIdx = 0;
    render();
});

document.getElementById('nextChar').addEventListener('click', () => {
    syncData();
    charIdx += 1;
    fieldIdx = 0;
    render();
});

document.getElementById('prevField').addEventListener('click', () => {
    syncData();
    fieldIdx -= 1;
    render();
});

document.getElementById('nextField').addEventListener('click', () => {
    syncData();
    fieldIdx += 1;
    render();
});

window.addEventListener('load', init);
