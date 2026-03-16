/**
 * @file modelWindow.js
 */

const { BrowserWindow, screen, ipcMain } = require('electron');
const path = require('path');

// 读取和保存配置
const { readConfig, writeConfig } = require('../utils/pathUtils');

let win = null;
let mouseTrackingInterval = null;
let isAlwaysOnTop = true;

// 用于防抖保存窗口位置的定时器
let saveMoveTimer = null;

function stopGlobalMouseTracking() {
    if (mouseTrackingInterval) {
        clearInterval(mouseTrackingInterval);
        mouseTrackingInterval = null;
        console.log('停止全局鼠标追踪');
    }
}

function setupGlobalMouseTracking() {
    if (!win || win.isDestroyed() || mouseTrackingInterval) return;

    console.log('启动全局鼠标追踪...');
    let lastMousePos = { x: -1, y: -1 };
    let frameCount = 0;

    mouseTrackingInterval = setInterval(() => {
        if (!win || win.isDestroyed()) {
            stopGlobalMouseTracking();
            return;
        }
        try {
            const point = screen.getCursorScreenPoint();
            const bounds = win.getBounds();
            const windowX = point.x - bounds.x;
            const windowY = point.y - bounds.y;
            const inWindow = windowX >= 0 && windowX <= bounds.width &&
                            windowY >= 0 && windowY <= bounds.height;
            const dx = Math.abs(windowX - lastMousePos.x);
            const dy = Math.abs(windowY - lastMousePos.y);

            if (dx > 0.5 || dy > 0.5 || frameCount % 10 === 0) {
                lastMousePos = { x: windowX, y: windowY };
                win.webContents.send('global-mouse-move', {
                    x: windowX, y: windowY, inWindow: inWindow, screenX: point.x, screenY: point.y
                });
            }
            frameCount++;
        } catch (error) {
            console.error('鼠标追踪错误:', error);
        }
    }, 16);
}

/**
 * 创建应用主窗口
 */
function create() {
    if (win) return win;

    // 读取配置
    const config = readConfig();

    // 获取屏幕尺寸用于计算默认位置
    const primaryDisplay = screen.getPrimaryDisplay();
    const { width: screenW, height: screenH } = primaryDisplay.workAreaSize;

    // 确定初始坐标
    // 如果配置里有 initX 和 initY，就直接使用；否则回退到右下角默认位置
    let initX = config.initX;
    let initY = config.initY;

    // 如果坐标缺失，则重置为默认值
    if (initX === undefined || initY === undefined) {
        initX = screenW - 350;
        initY = screenH - 450;
    }

    win = new BrowserWindow({
        width: config.width,
        height: config.height,
        x: initX,    // 应用初始 X
        y: initY,    // 应用初始 Y
        transparent: true,
        backgroundColor: '#00000000',
        frame: false,
        resizable: false,
        alwaysOnTop: true,
        skipTaskbar: true,
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false,
            backgroundThrottling: false,
            additionalArguments: [
                `--window-width=${config.width}`,
                `--window-height=${config.height}`
            ]
        }
    });
    win.isMainApp = true;

    // win.setIgnoreMouseEvents(false, { forward: false });

    win.loadFile(path.join(__dirname, '../../renderer/pages/index.html'));

    // 初始化置顶状态
    if (isAlwaysOnTop) {
        win.setAlwaysOnTop(true, 'screen-saver');
    } else {
        win.setAlwaysOnTop(false);
    }

    // ============================================================
    // 监听窗口移动事件，并在停止拖动后保存位置（防抖）
    // ============================================================
    win.on('move', () => {
        // 如果已有待执行的保存任务，先取消
        if (saveMoveTimer) {
            clearTimeout(saveMoveTimer);
        }

        // 延迟 500 毫秒后写入配置
        // 只有在用户停止拖动 0.5 秒后才真正落盘
        saveMoveTimer = setTimeout(() => {
            if (!win || win.isDestroyed()) return;

            // 获取当前窗口坐标
            const [newX, newY] = win.getPosition();

            // 为了保证不覆盖掉 width/height/model_path，我们需要先读一遍最新的
            const currentConfig = readConfig();

            // 仅在坐标变化时保存，避免频繁写入
            if (currentConfig.initX !== newX || currentConfig.initY !== newY) {
                const newConfig = {
                    ...currentConfig,
                    initX: newX,
                    initY: newY
                };

                writeConfig(newConfig);
                // console.log(`窗口位置已保存 [${newX}, ${newY}]`);
            }
        }, 500);
    });
    // ============================================================

    win.webContents.on('did-finish-load', () => {
        setupGlobalMouseTracking();
    });

    win.on('closed', () => {
        stopGlobalMouseTracking();
        // 清理定时器
        if (saveMoveTimer) clearTimeout(saveMoveTimer);
        win = null;
    });

    win.on('hide', stopGlobalMouseTracking);
    win.on('show', setupGlobalMouseTracking);

    return win;
}

function get() {
    return win;
}

function setTopMost(flag) {
    if (!win || win.isDestroyed()) return;
    isAlwaysOnTop = flag;
    if (flag) {
        win.setAlwaysOnTop(true, 'screen-saver');
    } else {
        win.setAlwaysOnTop(false);
    }
}

function getIsAlwaysOnTop() {
    return isAlwaysOnTop;
}

module.exports = {
    create,
    get,
    setupGlobalMouseTracking,
    stopGlobalMouseTracking,
    setTopMost,
    getIsAlwaysOnTop
};
