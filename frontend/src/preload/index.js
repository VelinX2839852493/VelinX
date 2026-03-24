const {contextBridge, ipcRenderer, webUtils} = require('electron');

function subscribe(channel, callback) {
    if (typeof callback !== 'function') {
        return () => {
        };
    }

    const listener = (_event, ...args) => {
        callback(...args);
    };

    ipcRenderer.on(channel, listener);

    return () => {
        ipcRenderer.removeListener(channel, listener);
    };
}

contextBridge.exposeInMainWorld('electronAPI', {
    getTemplate: () => ipcRenderer.invoke('get-template'),
    getPresetRoles: () => ipcRenderer.invoke('get-preset-roles'),
    executeRoleStart: (params) => ipcRenderer.invoke('execute-role-start', params),
    openFileDialog: () => ipcRenderer.invoke('open-file-dialog'),
    getAllConfigs: () => ipcRenderer.invoke('get-all-configs'),
    addConfig: (newRole) => ipcRenderer.invoke('add-config', newRole),
    updateConfig: (updatedRole) => ipcRenderer.invoke('update-config', updatedRole),
    deleteConfig: (title) => ipcRenderer.invoke('delete-config', title),

    chat: {
        sendMessage: (textOrPayload, options = {}) => {
            const payload = typeof textOrPayload === 'object' && textOrPayload !== null
                ? textOrPayload
                : {message: textOrPayload, ...options};
            ipcRenderer.send('chat-message-to-backend', payload);
        },
        openWindow: (type) => ipcRenderer.send('open-window', type),
        resize: (width) => ipcRenderer.send('resize-chat-window', {width}),
        onReply: (callback) => subscribe('reply-from-backend', callback),
    },

    developerTest: {
        run: (payload) => ipcRenderer.invoke('developer-test:run', payload),
    },

    apiConfig: {
        load: () => ipcRenderer.invoke('api-config:load'),
        save: (config) => ipcRenderer.invoke('api-config:save', config),
        testConnection: (config) => ipcRenderer.invoke('api-config:test', config),
    },

    openaiConfig: {
        load: () => ipcRenderer.invoke('openai-config:load'),
        save: (config) => ipcRenderer.invoke('openai-config:save', config),
    },

    settings: {
        getCurrentConfig: () => ipcRenderer.invoke('get-current-config'),
        selectModelFolder: () => ipcRenderer.invoke('select-model-folder'),
        selectBackgroundImage: () => ipcRenderer.invoke('select-bg-image'),
        save: (payload) => ipcRenderer.invoke('save-settings', payload),
        toggleDebug: () => ipcRenderer.invoke('toggle-debug-mode'),
    },

    fileUi: {
        getFolderList: () => ipcRenderer.invoke('get-folder-list'),
        getPathForFile: (file) => webUtils.getPathForFile(file),
        saveFolderPath: (folderPath) => ipcRenderer.invoke('save-folder-path', folderPath),
        openFolder: (folderPath) => ipcRenderer.send('open-folder', folderPath),
        showItemContextMenu: (folderPath) => ipcRenderer.send('show-item-context-menu', folderPath),
        getBackgroundConfig: () => ipcRenderer.invoke('get-file-background-config'),
        selectBackgroundImage: () => ipcRenderer.invoke('select-file-bg-image'),
        saveBackgroundConfig: (config) => ipcRenderer.send('save-file-background-config', config),
        onFolderListUpdate: (callback) => subscribe('update-folder-list', callback),
        onBackgroundUpdate: (callback) => subscribe('update-file-background', callback),
    },

    charInfo: {
        load: () => ipcRenderer.invoke('load-char-info'),
    },

    invoke: (channel, ...args) => ipcRenderer.invoke(channel, ...args),
});
