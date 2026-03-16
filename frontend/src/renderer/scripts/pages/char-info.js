function createMessage(className, text) {
    const message = document.createElement('div');
    message.className = className;
    message.textContent = text;
    return message;
}

document.addEventListener('DOMContentLoaded', async () => {
    const api = window.electronAPI && window.electronAPI.charInfo;
    const dirNode = document.getElementById('char-info-dir');
    const listNode = document.getElementById('char-info-list');
    const statusNode = document.getElementById('char-info-status');

    if (!api || !dirNode || !listNode || !statusNode) {
        return;
    }

    try {
        const result = await api.load();
        dirNode.textContent = result.configDir || '未配置';
        listNode.replaceChildren();

        if (result.error) {
            statusNode.replaceChildren(createMessage('error-message', result.error));
        } else {
            statusNode.replaceChildren(createMessage('info-message', '配置目录读取成功，可在下方查看所有 JSON 文件内容。'));
        }

        if (!Array.isArray(result.files) || result.files.length === 0) {
            listNode.appendChild(createMessage('info-message', '当前目录下没有 JSON 文件。'));
            return;
        }

        result.files.forEach((file) => {
            const card = document.createElement('section');
            const title = document.createElement('h3');
            const filePath = document.createElement('div');
            const codeBlock = document.createElement('pre');
            const code = document.createElement('code');

            card.className = 'file-card';
            title.className = 'file-title';
            title.textContent = file.name;

            filePath.className = 'file-path';
            filePath.textContent = file.path;

            code.textContent = file.error ? `读取失败: ${file.error}` : file.content;
            codeBlock.appendChild(code);

            card.appendChild(title);
            card.appendChild(filePath);
            card.appendChild(codeBlock);
            listNode.appendChild(card);
        });
    } catch (error) {
        listNode.replaceChildren();
        statusNode.replaceChildren(createMessage('error-message', error.message || '读取角色信息失败'));
    }
});
