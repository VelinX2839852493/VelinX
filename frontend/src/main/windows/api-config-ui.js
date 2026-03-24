const { BrowserWindow, ipcMain } = require('electron');
const path = require('path');

const BaseHandler = require('../bot/BaseHandler');
const { readConfig, writeConfig } = require('../utils/pathUtils');
const { normalizeBackendConfig } = require('../backend/HttpBackendClient');

const API_HTML_PATH = path.join(__dirname, '../../renderer/pages/api-config.html');
const PRELOAD_PATH = path.join(__dirname, '../../preload/index.js');

let win = null;
let ipcReady = false;

function loadBackendConfig() {
    const appConfig = readConfig();
    return normalizeBackendConfig(appConfig && appConfig.backend ? appConfig.backend : {});
}

function saveBackendConfig(config) {
    let nextConfig;
    try {
        nextConfig = normalizeBackendConfig(config || {});
    } catch (error) {
        return {
            success: false,
            error: error.message,
        };
    }

    const success = writeConfig({
        backend: nextConfig,
    });

    if (!success) {
        return {
            success: false,
            error: 'Unable to save the backend config file.',
        };
    }

    try {
        BaseHandler.refreshBackendClient();
    } catch (error) {
        return {
            success: false,
            error: error.message,
        };
    }

    return {
        success: true,
        config: nextConfig,
    };
}

async function testBackendConfig(config) {
    let nextConfig;
    try {
        nextConfig = normalizeBackendConfig(config || {});
    } catch (error) {
        return {
            success: false,
            error: error.message,
        };
    }

    const result = await BaseHandler.health(nextConfig);
    if (!result.success) {
        return {
            success: false,
            error: result.error || 'Unable to connect to the backend service.',
        };
    }

    return {
        success: true,
        data: result.data || {},
    };
}

function ensureIpcRegistered() {
    if (ipcReady) {
        return;
    }

    ipcReady = true;

    ipcMain.removeHandler('api-config:load');
    ipcMain.handle('api-config:load', async () => loadBackendConfig());

    ipcMain.removeHandler('api-config:save');
    ipcMain.handle('api-config:save', async (_event, config) => saveBackendConfig(config));

    ipcMain.removeHandler('api-config:test');
    ipcMain.handle('api-config:test', async (_event, config) => testBackendConfig(config));
}

function create(parentWindow) {
    ensureIpcRegistered();

    if (win && !win.isDestroyed()) {
        win.show();
        win.focus();
        return win;
    }

    win = new BrowserWindow({
        width: 620,
        height: 520,
        title: '后端接入配置',
        parent: parentWindow,
        modal: true,
        autoHideMenuBar: true,
        webPreferences: {
            preload: PRELOAD_PATH,
            nodeIntegration: false,
            contextIsolation: true,
        },
    });

    win.loadFile(API_HTML_PATH);
    win.on('closed', () => {
        win = null;
    });

    return win;
}

module.exports = { create };
