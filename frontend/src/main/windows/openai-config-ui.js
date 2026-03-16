const { BrowserWindow, ipcMain } = require('electron');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const { getOpenAiConfigPath } = require('../utils/pathUtils');

const OPENAI_CONFIG_HTML_PATH = path.join(__dirname, '../../renderer/pages/openai-config.html');
const PRELOAD_PATH = path.join(__dirname, '../../preload/index.js');
const FORM_KEYS = [
    'API_KEY_q',
    'BASE_URL_q',
    'MODEL_NAME_q',
    'API_KEY_b',
    'BASE_URL_b',
    'MODEL_NAME_b',
];

let win = null;
let ipcReady = false;

function isPlainObject(value) {
    return !!value && typeof value === 'object' && !Array.isArray(value);
}

function createEmptyFormConfig() {
    return FORM_KEYS.reduce((accumulator, key) => {
        accumulator[key] = '';
        return accumulator;
    }, {});
}

function pickFormConfig(config = {}) {
    const nextConfig = createEmptyFormConfig();
    FORM_KEYS.forEach((key) => {
        if (config[key] !== undefined && config[key] !== null) {
            nextConfig[key] = String(config[key]);
        }
    });
    return nextConfig;
}

function validateHttpUrl(value, fieldLabel) {
    const normalizedValue = typeof value === 'string' ? value.trim() : String(value || '').trim();
    if (!normalizedValue) {
        return '';
    }

    let parsedUrl;
    try {
        parsedUrl = new URL(normalizedValue);
    } catch (_error) {
        throw new Error(`${fieldLabel} 必须是合法的 http/https 地址。`);
    }

    if (parsedUrl.protocol !== 'http:' && parsedUrl.protocol !== 'https:') {
        throw new Error(`${fieldLabel} 必须使用 http 或 https 协议。`);
    }

    return normalizedValue;
}

function normalizeFormConfig(config = {}) {
    return {
        API_KEY_q: typeof config.API_KEY_q === 'string' ? config.API_KEY_q.trim() : String(config.API_KEY_q || '').trim(),
        BASE_URL_q: validateHttpUrl(config.BASE_URL_q, 'Q 通道 Base URL'),
        MODEL_NAME_q: typeof config.MODEL_NAME_q === 'string' ? config.MODEL_NAME_q.trim() : String(config.MODEL_NAME_q || '').trim(),
        API_KEY_b: typeof config.API_KEY_b === 'string' ? config.API_KEY_b.trim() : String(config.API_KEY_b || '').trim(),
        BASE_URL_b: validateHttpUrl(config.BASE_URL_b, 'B 通道 Base URL'),
        MODEL_NAME_b: typeof config.MODEL_NAME_b === 'string' ? config.MODEL_NAME_b.trim() : String(config.MODEL_NAME_b || '').trim(),
    };
}

function readConfigFile(filePath) {
    if (!fs.existsSync(filePath)) {
        return {};
    }

    const raw = fs.readFileSync(filePath, 'utf8');
    if (raw.trim() === '') {
        return {};
    }

    let parsed;
    try {
        parsed = JSON.parse(raw);
    } catch (_error) {
        throw new Error('读取 API 配置文件失败：JSON 格式无效。');
    }

    if (!isPlainObject(parsed)) {
        throw new Error('读取 API 配置文件失败：文件内容必须是 JSON 对象。');
    }

    return parsed;
}

function loadOpenAiConfig() {
    const filePath = getOpenAiConfigPath();

    try {
        return {
            success: true,
            filePath,
            config: pickFormConfig(readConfigFile(filePath)),
        };
    } catch (error) {
        return {
            success: false,
            filePath,
            error: error.message,
        };
    }
}

function saveOpenAiConfig(config) {
    const filePath = getOpenAiConfigPath();

    let existingConfig;
    let nextFormConfig;
    try {
        existingConfig = readConfigFile(filePath);
        nextFormConfig = normalizeFormConfig(config || {});
    } catch (error) {
        return {
            success: false,
            filePath,
            error: error.message,
        };
    }

    try {
        fs.mkdirSync(path.dirname(filePath), { recursive: true });
        fs.writeFileSync(
            filePath,
            JSON.stringify({ ...existingConfig, ...nextFormConfig }, null, 2),
            'utf8'
        );

        return {
            success: true,
            filePath,
            config: nextFormConfig,
        };
    } catch (error) {
        return {
            success: false,
            filePath,
            error: `写入 API 配置文件失败：${error.message}`,
        };
    }
}

function ensureIpcRegistered() {
    if (ipcReady) {
        return;
    }

    ipcReady = true;

    ipcMain.removeHandler('openai-config:load');
    ipcMain.handle('openai-config:load', async () => loadOpenAiConfig());

    ipcMain.removeHandler('openai-config:save');
    ipcMain.handle('openai-config:save', async (_event, config) => saveOpenAiConfig(config));
}

function create(parentWindow) {
    ensureIpcRegistered();

    if (win && !win.isDestroyed()) {
        win.show();
        win.focus();
        return win;
    }

    win = new BrowserWindow({
        width: 760,
        height: 660,
        title: 'API 配置',
        parent: parentWindow,
        modal: true,
        autoHideMenuBar: true,
        webPreferences: {
            preload: PRELOAD_PATH,
            nodeIntegration: false,
            contextIsolation: true,
        },
    });

    win.loadFile(OPENAI_CONFIG_HTML_PATH);
    win.on('closed', () => {
        win = null;
    });

    return win;
}

module.exports = { create };
