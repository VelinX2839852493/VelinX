const { ipcMain, BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');

const BaseHandler = require('../bot/BaseHandler');

let win = null;
let ipcReady = false;

function getConfigPath() {
    return BaseHandler.readPath('role_all_configs');
}

function readConfig() {
    try {
        const jsonPath = getConfigPath();
        if (!jsonPath || !fs.existsSync(jsonPath)) {
            return [];
        }

        const rawData = fs.readFileSync(jsonPath, 'utf8');
        return JSON.parse(rawData);
    } catch (error) {
        console.error('Failed to read role config JSON:', error);
        return [];
    }
}

function saveConfig(data) {
    try {
        const jsonPath = getConfigPath();
        if (!jsonPath) {
            return {
                success: false,
                error: 'Role config path is not configured.',
            };
        }

        fs.writeFileSync(jsonPath, JSON.stringify(data, null, 2), 'utf8');
        return { success: true };
    } catch (error) {
        console.error('Failed to save role config JSON:', error);
        return { success: false, error: error.message };
    }
}

function ensureIpcRegistered() {
    if (ipcReady) {
        return;
    }

    ipcReady = true;

    ipcMain.removeHandler('get-all-configs');
    ipcMain.handle('get-all-configs', async () => readConfig());

    ipcMain.removeHandler('add-config');
    ipcMain.handle('add-config', async (_event, newRole) => {
        const configs = readConfig();
        configs.push(newRole);
        return saveConfig(configs);
    });

    ipcMain.removeHandler('update-config');
    ipcMain.handle('update-config', async (_event, updatedRole) => {
        const configs = readConfig();
        const index = configs.findIndex((item) => item.title === updatedRole.title);

        if (index === -1) {
            return { success: false, error: '未找到对应的角色标题。' };
        }

        configs[index] = updatedRole;
        return saveConfig(configs);
    });

    ipcMain.removeHandler('delete-config');
    ipcMain.handle('delete-config', async (_event, title) => {
        const configs = readConfig();
        const filteredConfigs = configs.filter((item) => item.title !== title);
        return saveConfig(filteredConfigs);
    });
}

const RoleConfigUi = {
    create() {
        ensureIpcRegistered();

        if (win && !win.isDestroyed()) {
            win.focus();
            return win;
        }

        win = new BrowserWindow({
            width: 900,
            height: 900,
            title: '角色配置编辑器',
            frame: true,
            webPreferences: {
                preload: path.join(__dirname, '../../preload/index.js'),
                nodeIntegration: false,
                contextIsolation: true,
            },
        });

        win.loadFile(path.join(__dirname, '../../renderer/pages/role-config-ui.html'));
        win.on('closed', () => {
            win = null;
        });

        return win;
    },
};

module.exports = RoleConfigUi;
