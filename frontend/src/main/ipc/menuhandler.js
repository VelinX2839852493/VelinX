//菜单
const { ipcMain, Menu, app, BrowserWindow, dialog } = require('electron');

const ChatWindow = require('../windows/chat-ui');
const FileUi = require('../windows/fileui/file-ui');
const ChangeRoleUi = require('../windows/change-live2d-role-ui');
const ModelWindow = require('../windows/model-ui');
const BaseHandler = require('../bot/BaseHandler');
// const heartbeatEnabled = BaseHandler.isHeartbeatEnabled();


function init() {
    ipcMain.on('show-context-menu', async (event) => {
        const isTop = ModelWindow.getIsAlwaysOnTop();
        const isSessionActive = BaseHandler.isRunning();
        const isFileOpened = FileUi.isOpen();

        const template = [
            {
                label: '打开对话面板',
                click: () => {
                    ChatWindow.create();
                },
            },
            { type: 'separator' },
            {
                label: isFileOpened ? '关闭文件管理器' : '打开文件管理器',
                click: () => {
                    if (isFileOpened) {
                        FileUi.close();
                        return;
                    }

                    FileUi.create();
                },
            },
            { type: 'separator' },
            {
                label: isSessionActive ? '结束当前会话' : '启动默认会话',
                click: async () => {
                    if (isSessionActive) {
                        await BaseHandler.stop();
                        return;
                    }

                    const result = await BaseHandler.start();
                    if (!result.success) {
                        dialog.showErrorBox('会话启动失败', result.error || '无法连接到后端服务。');
                    }
                },
            },
            // { type: 'separator' },
            // {
            //     label: heartbeatEnabled ? '关闭心跳' : '开启心跳',
            //     // click: () => {
            //     //     const result = BaseHandler.setHeartbeatEnabled(!heartbeatEnabled);
            //     //     if (!result.success) {
            //     //         dialog.showErrorBox('心跳设置失败', result.error || '无法切换心跳状态。');
            //     //     }
            //     // },
            // },
            // { type: 'separator' },

            { type: 'separator' },
            {
                label: '切换 Live2D 模型',
                click: () => {
                    ChangeRoleUi.create();
                },
            },
            {
                label: isTop ? '切换为桌面模式' : '切换为置顶模式',
                click: () => {
                    ModelWindow.setTopMost(!isTop);
                },
            },
            { type: 'separator' },
            {
                label: '退出程序',
                click: () => {
                    app.quit();
                },
            },
        ];

        // 把你的 JS 数组转换成系统能识别的菜单对象。
        const menu = Menu.buildFromTemplate(template);
        // 指令下达，系统就在你鼠标点击的位置弹出了这个菜单。
        menu.popup({ window: BrowserWindow.fromWebContents(event.sender) });
    });
}

module.exports = { init };
