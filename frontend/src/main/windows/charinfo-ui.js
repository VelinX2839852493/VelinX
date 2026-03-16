const { BrowserWindow, ipcMain } = require('electron');
const fs = require('fs');
const path = require('path');

const BaseHandler = require('../bot/BaseHandler');

const CHAR_INFO_HTML_PATH = path.join(__dirname, '../../renderer/pages/char-info.html');
const PRELOAD_PATH = path.join(__dirname, '../../preload/index.js');

let win = null;
let ipcReady = false;

function loadCharInfoData() {
    const configDir = BaseHandler.readPath('aiPath') || '';
    const response = {
        configDir,
        files: [],
    };

    if (!configDir) {
        response.error = 'Character config directory is not configured.';
        return response;
    }

    if (!fs.existsSync(configDir)) {
        response.error = `Character config directory does not exist: ${configDir}`;
        return response;
    }

    try {
        const stat = fs.statSync(configDir);
        if (!stat.isDirectory()) {
            response.error = `Configured path is not a directory: ${configDir}`;
            return response;
        }

        const files = fs
            .readdirSync(configDir)
            .filter((file) => file.toLowerCase().endsWith('.md'))
            .sort((left, right) => left.localeCompare(right));

        response.files = files.map((name) => {
            const filePath = path.join(configDir, name);
            try {
                const raw = fs.readFileSync(filePath, 'utf8');
                let content = raw;

                try {
                    content = JSON.stringify(JSON.parse(raw), null, 4);
                } catch (_error) {
                    // Keep the raw content when the file is not valid JSON.
                }

                return {
                    name,
                    path: filePath,
                    content,
                };
            } catch (error) {
                return {
                    name,
                    path: filePath,
                    content: '',
                    error: error.message,
                };
            }
        });
    } catch (error) {
        response.error = error.message;
    }

    return response;
}

function ensureIpcRegistered() {
    if (ipcReady) {
        return;
    }

    ipcReady = true;
    ipcMain.removeHandler('load-char-info');
    ipcMain.handle('load-char-info', async () => loadCharInfoData());
}

function create(parentWindow) {
    ensureIpcRegistered();

    if (win && !win.isDestroyed()) {
        win.show();
        win.focus();
        return win;
    }

    win = new BrowserWindow({
        width: 800,
        height: 700,
        title: '角色信息',
        parent: parentWindow,
        autoHideMenuBar: true,
        webPreferences: {
            preload: PRELOAD_PATH,
            nodeIntegration: false,
            contextIsolation: true,
        },
    });

    win.loadFile(CHAR_INFO_HTML_PATH);
    win.on('closed', () => {
        win = null;
    });

    return win;
}

module.exports = { create };
