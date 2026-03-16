const { BrowserWindow, ipcMain } = require('electron');
const path = require('path');

const charInfoWindow = require('./charinfo-ui');
const apiConfigWindow = require('./api-config-ui');
const openAiConfigWindow = require('./openai-config-ui');
const switchCharWindow = require('./switch-char-ui');
const roleConfigWindow = require('./role-config-ui');

const CHAT_HTML_PATH = path.join(__dirname, '../../renderer/pages/chat.html');
const PRELOAD_PATH = path.join(__dirname, '../../preload/index.js');
const DEFAULT_CHAT_WIDTH = 800;
const MIN_CHAT_WIDTH = 480;

let chatWin = null;
let ipcReady = false;

function openChildWindow(type) {
    switch (type) {
        case 'char-info':
            charInfoWindow.create(chatWin);
            break;
        case 'api-config':
            apiConfigWindow.create(chatWin);
            break;
        case 'openai-config':
            openAiConfigWindow.create(chatWin);
            break;
        case 'switch-char':
            switchCharWindow.create(chatWin);
            break;
        case 'RoleConfigWindow':
            roleConfigWindow.create(chatWin);
            break;
        case 'heart':
            roleConfigWindow.create(chatWin);
            break;
        default:
            console.log('Unknown chat sub window:', type);
    }
}

function handleResize(_event, payload) {
    if (!chatWin || chatWin.isDestroyed()) {
        return;
    }

    const requestedWidth = typeof payload === 'number' ? payload : payload && payload.width;
    const width = Number.parseInt(requestedWidth, 10);
    if (!Number.isFinite(width) || width <= 0) {
        return;
    }

    const [, currentHeight] = chatWin.getSize();
    chatWin.setSize(Math.max(width, MIN_CHAT_WIDTH), currentHeight, true);
}

function ensureIpcRegistered() {
    if (ipcReady) {
        return;
    }

    ipcReady = true;
    ipcMain.removeAllListeners('resize-chat-window');
    ipcMain.on('resize-chat-window', handleResize);

    ipcMain.removeAllListeners('open-window');
    ipcMain.on('open-window', (_event, type) => {
        openChildWindow(type);
    });
}

function create() {
    ensureIpcRegistered();

    if (chatWin && !chatWin.isDestroyed()) {
        chatWin.show();
        chatWin.focus();
        return chatWin;
    }

    chatWin = new BrowserWindow({
        width: DEFAULT_CHAT_WIDTH,
        height: 600,
        title: '聊天面板',
        autoHideMenuBar: true,
        webPreferences: {
            preload: PRELOAD_PATH,
            nodeIntegration: false,
            contextIsolation: true,
        },
    });

    chatWin.loadFile(CHAT_HTML_PATH);
    chatWin.on('closed', () => {
        chatWin = null;
    });

    return chatWin;
}

function get() {
    return chatWin;
}

module.exports = { create, get };
