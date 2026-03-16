const { BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');

const selectModelFolder = require('../ipc/changerole');
const { readConfig, writeConfig } = require('../utils/pathUtils');

const PRELOAD_PATH = path.join(__dirname, '../../preload/index.js');
const SETTINGS_HTML_PATH = path.join(__dirname, '../../renderer/pages/change-live2drole.html');
const MIN_SIZE = 30;

let settingsWindow = null;

function getMainWindow() {
    return BrowserWindow.getAllWindows().find((window) => window.isMainApp === true);
}

function saveSettings(data = {}) {
    const { config = {}, modelPath } = data;
    const currentConfig = readConfig();

    const newConfig = {
        ...currentConfig,
        width: Math.max(Number.parseInt(config.width, 10) || MIN_SIZE, MIN_SIZE),
        height: Math.max(Number.parseInt(config.height, 10) || MIN_SIZE, MIN_SIZE),
        img_width: Math.max(Number.parseInt(config.img_width, 10) || MIN_SIZE, MIN_SIZE),
        img_height: Math.max(Number.parseInt(config.img_height, 10) || MIN_SIZE, MIN_SIZE),
        background_color: config.background_color !== undefined ? config.background_color : '',
        background_image: config.background_image !== undefined ? config.background_image : '',
        is_mouse_penetration: !!config.is_mouse_penetration,
        model_path: modelPath || currentConfig.model_path || '',
    };

    const success = writeConfig(newConfig);
    if (!success) {
        return {
            success: false,
            error: 'Unable to write the config file.',
        };
    }

    const mainWindow = getMainWindow();
    if (mainWindow) {
        mainWindow.setSize(newConfig.width, newConfig.height);
        mainWindow.webContents.send('config-updated', newConfig);
    }

    if (settingsWindow && !settingsWindow.isDestroyed()) {
        settingsWindow.close();
    }

    return { success: true };
}

function toggleDebugMode() {
    const mainWindow = BrowserWindow.getAllWindows().find(
        (window) => window !== settingsWindow && !window.isDestroyed() && window.isVisible()
    );

    if (mainWindow) {
        mainWindow.webContents.send('toggle-hit-area');
    }

    return { success: true };
}

module.exports = {
    initIPC() {
        ipcMain.removeHandler('get-current-config');
        ipcMain.handle('get-current-config', async () => readConfig());

        ipcMain.removeHandler('select-model-folder');
        ipcMain.handle('select-model-folder', selectModelFolder);

        ipcMain.removeHandler('select-bg-image');
        ipcMain.handle('select-bg-image', async () => {
            const result = await dialog.showOpenDialog({
                properties: ['openFile'],
                filters: [{ name: 'Images', extensions: ['jpg', 'png', 'gif', 'jpeg', 'webp'] }],
            });

            if (!result.canceled && result.filePaths.length > 0) {
                return result.filePaths[0];
            }

            return null;
        });

        ipcMain.removeHandler('save-settings');
        ipcMain.handle('save-settings', async (_event, data) => saveSettings(data));

        ipcMain.removeHandler('toggle-debug-mode');
        ipcMain.handle('toggle-debug-mode', async () => toggleDebugMode());
    },

    create() {
        if (settingsWindow && !settingsWindow.isDestroyed()) {
            settingsWindow.focus();
            return settingsWindow;
        }

        settingsWindow = new BrowserWindow({
            width: 400,
            height: 550,
            title: '模型与窗口设置',
            autoHideMenuBar: true,
            webPreferences: {
                preload: PRELOAD_PATH,
                nodeIntegration: false,
                contextIsolation: true,
            },
        });

        settingsWindow.loadFile(SETTINGS_HTML_PATH);
        settingsWindow.on('closed', () => {
            settingsWindow = null;
        });

        return settingsWindow;
    },
};
