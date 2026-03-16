const { ipcMain, screen, BrowserWindow } = require('electron');
const ModelWindow = require('../windows/model-ui'); // 引用窗口管理器

const {readConfig} = require('../utils/pathUtils'); // 引入工具
const config = readConfig();

let moveInterval = null;

function init() {
    // 鼠标穿透
    ipcMain.on('set-ignore-mouse', (event, ignore, options) => {
        const win = BrowserWindow.fromWebContents(event.sender);
        if (win) win.setIgnoreMouseEvents(ignore, options);
    });

    // 拖拽开始
    ipcMain.on('window-drag-start', () => {
        const win = ModelWindow.get(); // 获取主窗口实例
        if (!win) return;

        clearInterval(moveInterval);
        const winBounds = win.getBounds();
        const startCursor = screen.getCursorScreenPoint();
        const offset = { x: startCursor.x - winBounds.x, y: startCursor.y - winBounds.y };

        moveInterval = setInterval(() => {
            try {
                if (!win || win.isDestroyed()) {
                    clearInterval(moveInterval);
                    return;
                }
                const cursor = screen.getCursorScreenPoint();
                win.setBounds({
                    x: Math.floor(cursor.x - offset.x),
                    y: Math.floor(cursor.y - offset.y),
                    width: config.width,
                    height: config.height
                });
            } catch (err) {
                console.error(err);
                clearInterval(moveInterval);
            }
        }, 16);
    });

    // 拖拽结束
    ipcMain.on('window-drag-end', () => {
        clearInterval(moveInterval);
    });
}

module.exports = { init };