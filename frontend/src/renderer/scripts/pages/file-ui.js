function isBackgroundContextTarget(eventTarget, folderList, emptyHint) {
    if (!eventTarget) {
        return false;
    }

    if (eventTarget === document.body || eventTarget === folderList || eventTarget === emptyHint) {
        return true;
    }

    if (eventTarget instanceof Element) {
        return !eventTarget.closest('.folder-item');
    }

    return false;
}

function normalizeBackgroundConfig(config) {
    return {
        color: config && config.color ? config.color : '#ffffff',
        opacity: config && config.opacity !== undefined ? config.opacity : 1,
        image: config && config.image ? config.image : '',
        useImage: !!(config && config.useImage),
    };
}

function resolveDroppedPath(api, file) {
    if (!file) {
        return '';
    }

    if (typeof api.getPathForFile === 'function') {
        const resolvedPath = api.getPathForFile(file);
        if (resolvedPath) {
            return resolvedPath;
        }
    }

    return typeof file.path === 'string' ? file.path : '';
}

document.addEventListener('DOMContentLoaded', async () => {
    const api = window.electronAPI && window.electronAPI.fileUi;
    if (!api) {
        return;
    }

    const folderListDiv = document.getElementById('folder-list');
    const overlay = document.getElementById('drop-overlay');
    const emptyHint = document.getElementById('empty-hint');
    const backgroundLayer = document.getElementById('background-layer');

    let dragCounter = 0;
    const removeFolderUpdate = api.onFolderListUpdate((newList) => renderList(newList));
    const removeBackgroundUpdate = api.onBackgroundUpdate((config) => applyBackgroundConfig(config));

    function renderList(list) {
        folderListDiv.replaceChildren();

        if (!Array.isArray(list) || list.length === 0) {
            emptyHint.style.display = 'block';
            return;
        }

        emptyHint.style.display = 'none';
        list.forEach((itemData) => {
            const item = document.createElement('div');
            const icon = document.createElement('img');
            const label = document.createElement('span');

            item.className = 'folder-item';
            if (itemData.icon) {
                icon.src = itemData.icon;
            } else {
                icon.alt = '';
            }

            label.textContent = itemData.name || itemData.path || '';

            item.appendChild(icon);
            item.appendChild(label);
            item.addEventListener('click', () => {
                api.openFolder(itemData.path);
            });

            item.addEventListener('contextmenu', (event) => {
                event.preventDefault();
                event.stopPropagation();
                api.showItemContextMenu(itemData.path);
            });

            folderListDiv.appendChild(item);
        });
    }

    function applyBackgroundConfig(bgConfig) {
        const nextConfig = normalizeBackgroundConfig(bgConfig);
        if (nextConfig.useImage && nextConfig.image) {
            backgroundLayer.style.backgroundImage = `url("file://${nextConfig.image.replace(/\\/g, '/')}")`;
            backgroundLayer.style.backgroundColor = 'transparent';
            backgroundLayer.style.opacity = nextConfig.opacity;
            return;
        }

        backgroundLayer.style.backgroundImage = 'none';
        const color = nextConfig.color;
        const r = Number.parseInt(color.slice(1, 3), 16);
        const g = Number.parseInt(color.slice(3, 5), 16);
        const b = Number.parseInt(color.slice(5, 7), 16);
        backgroundLayer.style.backgroundColor = `rgba(${r}, ${g}, ${b}, ${nextConfig.opacity})`;
        backgroundLayer.style.opacity = 1;
    }

    window.addEventListener('contextmenu', (event) => {
        event.preventDefault();
        if (isBackgroundContextTarget(event.target, folderListDiv, emptyHint)) {
            api.showItemContextMenu(null);
        }
    });

    window.addEventListener('dragenter', (event) => {
        event.preventDefault();
        dragCounter += 1;
        overlay.classList.add('active');
    });

    window.addEventListener('dragleave', (event) => {
        event.preventDefault();
        dragCounter = Math.max(0, dragCounter - 1);
        if (dragCounter === 0) {
            overlay.classList.remove('active');
        }
    });

    window.addEventListener('dragover', (event) => {
        event.preventDefault();
    });

    window.addEventListener('drop', async (event) => {
        event.preventDefault();
        dragCounter = 0;
        overlay.classList.remove('active');

        const files = Array.from(event.dataTransfer.files || []);
        for (const file of files) {
            const folderPath = resolveDroppedPath(api, file);
            if (!folderPath) {
                continue;
            }

            const newList = await api.saveFolderPath(folderPath);
            renderList(newList);
        }
    });

    window.addEventListener('beforeunload', () => {
        if (typeof removeFolderUpdate === 'function') {
            removeFolderUpdate();
        }
        if (typeof removeBackgroundUpdate === 'function') {
            removeBackgroundUpdate();
        }
    });

    renderList(await api.getFolderList());
    applyBackgroundConfig(await api.getBackgroundConfig());
});
