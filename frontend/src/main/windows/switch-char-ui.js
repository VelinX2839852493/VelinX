const { BrowserWindow, ipcMain, dialog } = require('electron');
const fs = require('fs');
const path = require('path');

const BaseHandler = require('../bot/BaseHandler');

let win = null;
let ipcReady = false;

function ensureIpcRegistered() {
    if (ipcReady) {
        return;
    }

    ipcReady = true;

    ipcMain.removeHandler('get-preset-roles');
    ipcMain.handle('get-preset-roles', async () => {
        try {
            const basePath = BaseHandler.readPath('dataPath');
            if (!basePath || !fs.existsSync(basePath)) {
                return [];
            }

            const currentAiName = BaseHandler.getBotName();
            const files = fs.readdirSync(basePath, { withFileTypes: true });
            let roleNames = files
                .filter((dirent) => dirent.isDirectory())
                .map((dirent) => dirent.name);

            if (currentAiName && roleNames.includes(currentAiName)) {
                roleNames = [
                    currentAiName,
                    ...roleNames.filter((name) => name !== currentAiName),
                ];
            }

            return roleNames;
        } catch (error) {
            console.error('Failed to read preset roles:', error);
            return [];
        }
    });

    ipcMain.removeHandler('get-template');
    ipcMain.handle('get-template', async () => {
        try {
            const filePath = BaseHandler.readPath('role_all_configs');
            if (!filePath || !fs.existsSync(filePath)) {
                return [];
            }

            const rawData = fs.readFileSync(filePath, 'utf8');
            const jsonData = JSON.parse(rawData);
            return jsonData.map((item) => item.title);
        } catch (error) {
            console.error('Failed to read role templates:', error);
            return [];
        }
    });

    ipcMain.removeHandler('execute-role-start');
    ipcMain.handle('execute-role-start', async (_event, params) => BaseHandler.start(params));

    ipcMain.removeHandler('open-file-dialog');
    ipcMain.handle('open-file-dialog', async () => {
        const { canceled, filePaths } = await dialog.showOpenDialog({
            properties: ['openFile'],
            filters: [{ name: 'JSON Files', extensions: ['json'] }],
        });

        if (!canceled) {
            return filePaths[0];
        }

        return null;
    });
}

const ChangeRoleUi = {
    create() {
        ensureIpcRegistered();

        if (win && !win.isDestroyed()) {
            win.focus();
            return win;
        }

        win = new BrowserWindow({
            width: 500,
            height: 700,
            title: '选择 / 创建 AI 角色',
            resizable: false,
            frame: true,
            webPreferences: {
                preload: path.join(__dirname, '../../preload/index.js'),
                nodeIntegration: false,
                contextIsolation: true,
            },
        });

        win.loadFile(path.join(__dirname, '../../renderer/pages/changerole.html'));
        win.on('closed', () => {
            win = null;
        });
        win.setMenu(null);

        return win;
    },
};

module.exports = ChangeRoleUi;
