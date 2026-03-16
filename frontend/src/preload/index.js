// 主线程通信工具
const {contextBridge, ipcRenderer, webUtils} = require('electron');

/**
 * 通用的订阅函数封装
 * 用于监听主进程发往渲染进程的消息
 * @param {string} channel - 监听的 IPC 频道名称
 * @param {function} callback - 收到消息后的回调函数
 * @returns {function} - 返回一个取消订阅的函数，用于清理监听器防止内存泄漏
 */
function subscribe(channel, callback) {
    if (typeof callback !== 'function') {
        return () => {
        };
    }

    // 创建监听器包装函数
    const listener = (_event, ...args) => {
        callback(...args);
    };

    // 开启监听
    ipcRenderer.on(channel, listener);

    // 返回销毁函数
    return () => {
        ipcRenderer.removeListener(channel, listener);
    };
}

/**
 * 使用 contextBridge 将 API 注入到渲染进程的全局 window 对象中
 * 前端可以通过 window.electronAPI 访问这里定义的方法
 */
contextBridge.exposeInMainWorld('electronAPI', {
    // --- 基础配置与角色管理 ---
    getTemplate: () => ipcRenderer.invoke('get-template'), // 获取模板
    getPresetRoles: () => ipcRenderer.invoke('get-preset-roles'), // 获取预设角色
    executeRoleStart: (params) => ipcRenderer.invoke('execute-role-start', params), // 执行角色启动逻辑
    openFileDialog: () => ipcRenderer.invoke('open-file-dialog'), // 打开文件选择对话框
    getAllConfigs: () => ipcRenderer.invoke('get-all-configs'), // 获取所有配置
    addConfig: (newRole) => ipcRenderer.invoke('add-config', newRole), // 添加新配置
    updateConfig: (updatedRole) => ipcRenderer.invoke('update-config', updatedRole), // 更新配置
    deleteConfig: (title) => ipcRenderer.invoke('delete-config', title), // 根据标题删除配置

    // --- 聊天相关功能 ---
    chat: {
        // 发送消息到后端（主进程）
        sendMessage: (textOrPayload, options = {}) => {
            const payload = typeof textOrPayload === 'object' && textOrPayload !== null
                ? textOrPayload
                : {message: textOrPayload, ...options};
            ipcRenderer.send('chat-message-to-backend', payload);
        },
        openWindow: (type) => ipcRenderer.send('open-window', type), // 打开指定类型的窗口
        resize: (width) => ipcRenderer.send('resize-chat-window', {width}), // 调整聊天窗口宽度
        onReply: (callback) => subscribe('reply-from-backend', callback), // 监听后端的回复消息
    },

    // --- API 配置管理 (如 LLM API Key) ---
    apiConfig: {
        load: () => ipcRenderer.invoke('api-config:load'), // 加载配置
        save: (config) => ipcRenderer.invoke('api-config:save', config), // 保存配置
        testConnection: (config) => ipcRenderer.invoke('api-config:test', config), // 测试 API 连接是否通畅
    },

    // --- OpenAI API 文件配置 ---
    openaiConfig: {
        load: () => ipcRenderer.invoke('openai-config:load'),
        save: (config) => ipcRenderer.invoke('openai-config:save', config),
    },

    // --- 通用设置 ---
    settings: {
        getCurrentConfig: () => ipcRenderer.invoke('get-current-config'), // 获取当前生效的配置
        selectModelFolder: () => ipcRenderer.invoke('select-model-folder'), // 选择模型存放文件夹
        selectBackgroundImage: () => ipcRenderer.invoke('select-bg-image'), // 选择背景图
        save: (payload) => ipcRenderer.invoke('save-settings', payload), // 保存设置
        toggleDebug: () => ipcRenderer.invoke('toggle-debug-mode'), // 切换调试模式
    },

    // --- 文件管理界面相关 ---
    fileUi: {
        getFolderList: () => ipcRenderer.invoke('get-folder-list'), // 获取文件夹列表
        // 安全地获取 File 对象的完整磁盘路径 (Electron 特有)
        getPathForFile: (file) => webUtils.getPathForFile(file),
        saveFolderPath: (folderPath) => ipcRenderer.invoke('save-folder-path', folderPath), // 保存选定的文件夹路径
        openFolder: (folderPath) => ipcRenderer.send('open-folder', folderPath), // 在操作系统的文件管理器中打开文件夹
        showItemContextMenu: (folderPath) => ipcRenderer.send('show-item-context-menu', folderPath), // 显示文件右键菜单
        getBackgroundConfig: () => ipcRenderer.invoke('get-file-background-config'), // 获取文件界面的背景配置
        selectBackgroundImage: () => ipcRenderer.invoke('select-file-bg-image'), // 为文件界面选择背景图
        saveBackgroundConfig: (config) => ipcRenderer.send('save-file-background-config', config), // 保存背景配置
        onFolderListUpdate: (callback) => subscribe('update-folder-list', callback), // 监听文件夹列表更新
        onBackgroundUpdate: (callback) => subscribe('update-file-background', callback), // 监听背景图更新
    },

    // --- 角色信息 ---
    charInfo: {
        load: () => ipcRenderer.invoke('load-char-info'), // 加载角色详细信息
    },

    // --- 通用万能调用接口 ---
    // 允许前端直接调用任意 IPC 频道，增加灵活性
    invoke: (channel, ...args) => ipcRenderer.invoke(channel, ...args),
});
