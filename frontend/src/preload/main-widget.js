const { contextBridge, ipcRenderer, webFrame } = require('electron');

function subscribe(channel, callback) {
    if (typeof callback !== 'function') {
        return () => {};
    }

    const listener = (_event, ...args) => {
        callback(...args);
    };

    ipcRenderer.on(channel, listener);
    return () => {
        ipcRenderer.removeListener(channel, listener);
    };
}

webFrame.setZoomFactor(1);
webFrame.setVisualZoomLevelLimits(1, 1);

contextBridge.exposeInMainWorld('electronAPI', {
    mainWidget: {
        getCurrentConfig: () => ipcRenderer.invoke('get-current-config'),
        setIgnoreMouse: (ignore, options) => ipcRenderer.send('set-ignore-mouse', ignore, options),
        startWindowDrag: () => ipcRenderer.send('window-drag-start'),
        endWindowDrag: () => ipcRenderer.send('window-drag-end'),
        showContextMenu: () => ipcRenderer.send('show-context-menu'),
        onConfigUpdated: (callback) => subscribe('config-updated', callback),
        onToggleHitArea: (callback) => subscribe('toggle-hit-area', callback),
        onGlobalMouseMove: (callback) => subscribe('global-mouse-move', callback),
    },
});
