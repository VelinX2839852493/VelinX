const { BrowserWindow, ipcMain } = require('electron');
const path = require('path');

const BaseHandler = require('../bot/BaseHandler');

const HTML_PATH = path.join(__dirname, '../../renderer/pages/developer-test.html');
const PRELOAD_PATH = path.join(__dirname, '../../preload/index.js');

let win = null;
let ipcReady = false;

function ensureIpcRegistered() {
    if (ipcReady) {
        return;
    }

    ipcReady = true;

    ipcMain.removeHandler('developer-test:run');
    ipcMain.handle('developer-test:run', async (_event, payload) => {
        return BaseHandler.runDeveloperStartupTest(payload);
    });
}

function create(parentWindow) {
    ensureIpcRegistered();

    if (win && !win.isDestroyed()) {
        win.show();
        win.focus();
        return win;
    }

    win = new BrowserWindow({
        width: 680,
        height: 520,
        title: '开发者测试',
        parent: parentWindow,
        modal: true,
        autoHideMenuBar: true,
        webPreferences: {
            preload: PRELOAD_PATH,
            nodeIntegration: false,
            contextIsolation: true,
        },
    });

    win.loadFile(HTML_PATH);
    win.on('closed', () => {
        win = null;
    });

    return win;
}

module.exports = { create };
