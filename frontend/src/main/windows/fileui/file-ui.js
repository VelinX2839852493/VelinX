const { BrowserWindow, ipcMain, dialog, shell, app, Menu } = require('electron');
const path = require('path');
const fs = require('fs');

const { readFileConfig, writeFileConfig } = require('../../utils/pathUtils');
const { createBackgroundConfigWindow } = require('./background-config-ui');

const PRELOAD_PATH = path.join(__dirname, '../../../preload/index.js');
const FILE_UI_HTML_PATH = path.join(__dirname, '../../../renderer/pages/fileui/fileui.html');

let fileWindow = null;
let ipcReady = false;

async function getFoldersWithIcons(pathList) {
    const results = [];

    for (const folderPath of pathList) {
        if (!fs.existsSync(folderPath)) {
            continue;
        }

        try {
            const icon = await app.getFileIcon(folderPath, { size: 'normal' });
            results.push({
                path: folderPath,
                name: path.basename(folderPath),
                icon: icon.toDataURL(),
            });
        } catch (_error) {
            results.push({
                path: folderPath,
                name: path.basename(folderPath),
                icon: '',
            });
        }
    }

    return results;
}

function broadcastBackgroundUpdate(bgConfig) {
    BrowserWindow.getAllWindows().forEach((window) => {
        if (!window.isDestroyed()) {
            window.webContents.send('update-file-background', bgConfig);
        }
    });
}

function ensureIpcRegistered() {
    if (ipcReady) {
        return;
    }

    ipcReady = true;

    ipcMain.removeHandler('get-folder-list');
    ipcMain.handle('get-folder-list', async () => {
        const config = readFileConfig();
        return getFoldersWithIcons(config.folderList || []);
    });

    ipcMain.removeHandler('save-folder-path');
    ipcMain.handle('save-folder-path', async (_event, folderPath) => {
        const currentConfig = readFileConfig();
        const folderList = Array.isArray(currentConfig.folderList) ? [...currentConfig.folderList] : [];

        let isDirectory = false;
        if (typeof folderPath === 'string' && folderPath && fs.existsSync(folderPath)) {
            try {
                isDirectory = fs.statSync(folderPath).isDirectory();
            } catch (_error) {
                isDirectory = false;
            }
        }

        if (isDirectory && !folderList.includes(folderPath)) {
            folderList.push(folderPath);
            writeFileConfig({ ...currentConfig, folderList });
        }

        return getFoldersWithIcons(folderList);
    });

    ipcMain.removeHandler('get-file-background-config');
    ipcMain.handle('get-file-background-config', async () => {
        const config = readFileConfig();
        return config.background || {
            color: '#f5f5f7',
            opacity: 1,
            image: '',
            useImage: false,
        };
    });

    ipcMain.removeHandler('select-file-bg-image');
    ipcMain.handle('select-file-bg-image', async () => {
        const result = await dialog.showOpenDialog({
            properties: ['openFile'],
            filters: [
                { name: 'Images', extensions: ['jpg', 'png', 'gif', 'jpeg', 'webp', 'bmp'] },
                { name: 'All Files', extensions: ['*'] },
            ],
        });

        if (!result.canceled && result.filePaths.length > 0) {
            return result.filePaths[0];
        }

        return null;
    });

    ipcMain.removeAllListeners('open-folder');
    ipcMain.on('open-folder', (_event, folderPath) => {
        if (folderPath && fs.existsSync(folderPath)) {
            shell.openPath(folderPath);
        }
    });

    ipcMain.removeAllListeners('show-item-context-menu');
    ipcMain.on('show-item-context-menu', async (event, folderPath) => {
        const template = folderPath
            ? [
                { label: '打开', click: () => shell.openPath(folderPath) },
                { type: 'separator' },
                {
                    label: '从列表移除',
                    click: async () => {
                        const currentConfig = readFileConfig();
                        const folderList = (currentConfig.folderList || []).filter((item) => item !== folderPath);
                        writeFileConfig({ ...currentConfig, folderList });

                        const newList = await getFoldersWithIcons(folderList);
                        event.sender.send('update-folder-list', newList);
                    },
                },
                { type: 'separator' },
                {
                    label: '更换背景',
                    click: () => {
                        const parentWindow = BrowserWindow.fromWebContents(event.sender);
                        if (parentWindow) {
                            createBackgroundConfigWindow(parentWindow);
                        }
                    },
                },
            ]
            : [
                {
                    label: '更换背景',
                    click: () => {
                        const parentWindow = BrowserWindow.fromWebContents(event.sender);
                        if (parentWindow) {
                            createBackgroundConfigWindow(parentWindow);
                        }
                    },
                },
            ];

        const menu = Menu.buildFromTemplate(template);
        menu.popup({ window: BrowserWindow.fromWebContents(event.sender) });
    });

    ipcMain.removeAllListeners('save-file-background-config');
    ipcMain.on('save-file-background-config', (_event, bgConfig) => {
        const currentConfig = readFileConfig();
        writeFileConfig({ ...currentConfig, background: bgConfig });
        broadcastBackgroundUpdate(bgConfig);
    });

    ipcMain.removeAllListeners('open-background-config');
    ipcMain.on('open-background-config', (event) => {
        const parentWindow = fileWindow && !fileWindow.isDestroyed()
            ? fileWindow
            : BrowserWindow.fromWebContents(event.sender);

        if (parentWindow) {
            createBackgroundConfigWindow(parentWindow);
        }
    });
}

module.exports = {
    create() {
        ensureIpcRegistered();

        if (fileWindow && !fileWindow.isDestroyed()) {
            fileWindow.focus();
            return fileWindow;
        }

        const savedState = readFileConfig();
        fileWindow = new BrowserWindow({
            x: savedState.fileWinX,
            y: savedState.fileWinY,
            width: savedState.fileWinWidth || 400,
            height: savedState.fileWinHeight || 550,
            minWidth: 50,
            minHeight: 50,
            title: '文件窗口',
            autoHideMenuBar: true,
            webPreferences: {
                preload: PRELOAD_PATH,
                nodeIntegration: false,
                contextIsolation: true,
            },
        });

        fileWindow.loadFile(FILE_UI_HTML_PATH);
        fileWindow.on('close', () => {
            const bounds = fileWindow.getBounds();
            const current = readFileConfig();

            writeFileConfig({
                ...current,
                fileWinX: bounds.x,
                fileWinY: bounds.y,
                fileWinWidth: bounds.width,
                fileWinHeight: bounds.height,
            });
        });

        fileWindow.on('closed', () => {
            fileWindow = null;
        });

        return fileWindow;
    },

    close() {
        if (fileWindow && !fileWindow.isDestroyed()) {
            fileWindow.close();
        }
    },

    isOpen() {
        return !!(fileWindow && !fileWindow.isDestroyed());
    },
};
