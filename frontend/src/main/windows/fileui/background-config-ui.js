const { BrowserWindow } = require('electron');
const path = require('path');

const PRELOAD_PATH = path.join(__dirname, '../../../preload/index.js');
const BG_CONFIG_HTML_PATH = path.join(__dirname, '../../../renderer/pages/fileui/background-config.html');

let bgConfigWindow = null;

function createBackgroundConfigWindow(parentWindow) {
    if (bgConfigWindow && !bgConfigWindow.isDestroyed()) {
        bgConfigWindow.focus();
        return bgConfigWindow;
    }

    bgConfigWindow = new BrowserWindow({
        width: 400,
        height: 500,
        parent: parentWindow,
        modal: true,
        show: false,
        title: '背景设置',
        autoHideMenuBar: true,
        webPreferences: {
            preload: PRELOAD_PATH,
            nodeIntegration: false,
            contextIsolation: true,
        },
    });

    bgConfigWindow.loadFile(BG_CONFIG_HTML_PATH);
    bgConfigWindow.once('ready-to-show', () => {
        bgConfigWindow.show();
    });

    bgConfigWindow.on('closed', () => {
        bgConfigWindow = null;
    });

    return bgConfigWindow;
}

module.exports = {
    createBackgroundConfigWindow,
};
