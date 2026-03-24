/**
 * 负责窗口管理、IPC 通信、输入监听，并协调 Live2D/Spine 管理器
 */


console.log("当前 __dirname 是:", __dirname);
console.log("当前 process.cwd() 是:", require('process').cwd());

const path = require('path');
const PIXI = require('pixi.js');
const { ipcRenderer, webFrame } = require('electron');
const { readConfig } = require('../../main/utils/pathUtils');
// 引入两个管理器
const Live2DManager = require('../scripts/live2d/live2d-manager'); // 确保路径正确
const SpineManager = require('../scripts/live2d/spine-manager');   // 确保路径正确


// --- 全局错误处理 ---
function handleGlobalError(errorMsg, url, line, col, error) {
    console.error("检测到全局错误:", errorMsg, error);

    // 强制显示背景，防止透明无法调试
    document.body.style.backgroundColor = 'rgba(255, 0, 0, 0.5)'; // 半透明红色
    document.body.style.border = '4px solid red';
    document.body.style.boxSizing = 'border-box';

    const canvas = document.getElementById('canvas');
    if (canvas) canvas.style.opacity = '0.5'; // 让画布半透明，方便看清背景

    // 在页面中心显示错误文字
    let errBanner = document.getElementById('error-banner');
    if (!errBanner) {
        errBanner = document.createElement('div');
        errBanner.id = 'error-banner';
        errBanner.style.cssText = 'position:fixed; top:50%; left:50%; transform:translate(-50%,-50%); color:white; background:black; padding:10px; z-index:10000; font-size:12px; border-radius:5px; pointer-events:none;';
        document.body.appendChild(errBanner);
    }
    errBanner.innerText = `程序运行出错: ${errorMsg}\n(请按 F12 查看控制台)`;

    return false; // 继续抛出错误，方便控制台查看
}

window.onerror = handleGlobalError;
window.addEventListener('unhandledrejection', event => {
    handleGlobalError(event.reason.message || event.reason);
});

/**
 * 清除错误显示，恢复正常 UI
 */
function clearErrorState() {
    // 1. 移除错误提示框
    const errBanner = document.getElementById('error-banner');
    if (errBanner) errBanner.remove();

    // 2. 恢复 canvas 透明度
    const canvas = document.getElementById('canvas');
    if (canvas) canvas.style.opacity = '1';

    // 3. 恢复背景设置（从当前配置读取）
    // 假设你之前已经定义了 applyBackgroundSettings 和 config
    applyBackgroundSettings(config);

    // 4. 重置边框
    document.body.style.border = 'none';

    console.log('Error state cleared, UI restored.');
}




// 暴露 PIXI (Spine 插件依赖这个)
window.PIXI = PIXI;

// --- 配置初始化 ---
let WIN_WIDTH = 300;
let WIN_HEIGHT = 400;
let IMG_WIDTH = 300;
let IMG_HEIGHT = 400;
let allowPenetration = false;
let STARTUP_MODEL_PATH = null;

// 读取参数和配置
if (process.argv) {
    process.argv.forEach(arg => {
        if (arg.startsWith('--window-width=')) WIN_WIDTH = parseInt(arg.split('=')[1]);
        if (arg.startsWith('--window-height=')) WIN_HEIGHT = parseInt(arg.split('=')[1]);
    });
}
const config = readConfig();
WIN_WIDTH = config.width || WIN_WIDTH;
WIN_HEIGHT = config.height || WIN_HEIGHT;
IMG_WIDTH = config.img_width || WIN_HEIGHT;
IMG_HEIGHT = config.img_height || IMG_HEIGHT;
allowPenetration = config.is_mouse_penetration || false;
STARTUP_MODEL_PATH = config.model_path || null;

// 禁止缩放
webFrame.setZoomFactor(1);
webFrame.setVisualZoomLevelLimits(1, 1);

// --- 初始化引擎 ---
document.addEventListener('DOMContentLoaded', () => {
    document.body.style.width = `${WIN_WIDTH}px`;
    document.body.style.height = `${WIN_HEIGHT}px`;
    const canvas = document.getElementById('canvas');
    if (canvas) {
        canvas.style.width = `${WIN_WIDTH}px`;
        canvas.style.height = `${WIN_HEIGHT}px`;
    }
});

const app = new PIXI.Application({
    view: document.getElementById('canvas'),
    width: WIN_WIDTH,
    height: WIN_HEIGHT,
    backgroundAlpha: 0,
    autoDensity: true,
    resolution: window.devicePixelRatio || 1,
    resizeTo: null
});

// ===================== 核心管理器逻辑 =====================

/** 当前活跃的管理器（可以是 Live2D 或 Spine） */
let avatarManager = null;

/**
 * 核心工厂函数：根据文件后缀动态加载管理器
 */
async function loadAvatar(modelPath) {
    if (!modelPath) return;

    // 1. 判断是否需要切换管理器类型
    let newManagerType = null;
    const isSpine = modelPath.endsWith('.skel') || modelPath.endsWith('.json') && !modelPath.endsWith('model3.json');

    if (isSpine) {
        console.log(">>> 检测到 Spine 模型");
        if (!(avatarManager instanceof SpineManager)) {
            newManagerType = SpineManager;
        }
    } else {
        console.log(">>> 检测到 Live2D 模型");
        if (!(avatarManager instanceof Live2DManager)) {
            newManagerType = Live2DManager;
        }
    }


        // --- 核心修复点：销毁旧管理器及其模型 ---
    if (newManagerType && avatarManager) {
        console.log(">>> 切换管理器，正在清理旧模型...");
        if (typeof avatarManager.destroy === 'function') {
            avatarManager.destroy();
        }
        // 清理完成后再赋值新类型
        avatarManager = new newManagerType(app);
    }


    // 2. 如果类型变了，销毁旧的，创建新的
    if (newManagerType) {
        if (avatarManager) {
            // 如果旧管理器有 destroy 方法，可以在这里调用清理
            // 这里依赖 PIXI stage 或 manager 内部 load 时的清理
        }
        avatarManager = new newManagerType(app);
    }

    // 3. 如果还没有管理器（第一次启动），默认创建 Live2D 管理器
    if (!avatarManager) {
        avatarManager = new Live2DManager(app);
    }

    // 4. 调用统一接口加载
    await avatarManager.load(modelPath, WIN_WIDTH, WIN_HEIGHT, IMG_WIDTH, IMG_HEIGHT);

    clearErrorState()
}


// --- 全局状态 ---
let globalMouseX = WIN_WIDTH / 2;
let globalMouseY = WIN_HEIGHT / 2;
let isMouseDown = false;
let isDragging = false;
let startPos = { x: 0, y: 0 };
let lastIgnoreState = false;

// ===================== 辅助函数 =====================

function applyBackgroundSettings(cfg) {
    const bodyStyle = document.body.style;
    if (cfg.background_color) bodyStyle.backgroundColor = cfg.background_color;
    else bodyStyle.backgroundColor = '';

    if (cfg.background_image) {
        const cleanPath = cfg.background_image.replace(/\\/g, '/');
        bodyStyle.backgroundImage = `url("${cleanPath}")`;
        bodyStyle.backgroundSize = 'cover';
        bodyStyle.backgroundPosition = 'center';
        bodyStyle.backgroundRepeat = 'no-repeat';
    } else {
        bodyStyle.backgroundImage = '';
    }
}

// ===================== 循环与逻辑 =====================

/** 动画循环 */
function smoothUpdate() {
    // 只有非拖拽且管理器存在时才更新物理
    if (!isDragging && avatarManager) {
        avatarManager.updatePhysics(globalMouseX, globalMouseY);
    }
    requestAnimationFrame(smoothUpdate);
}

const endDrag = () => {
    isMouseDown = false;
    if (isDragging) {
        isDragging = false;
        ipcRenderer.send('window-drag-end');
    }
};

// ===================== 交互事件监听 =====================

document.addEventListener('mousedown', (e) => {
    if (e.button === 0) {
        isMouseDown = true;
        isDragging = false;
        startPos = { x: e.clientX, y: e.clientY };
        globalMouseX = e.clientX;
        globalMouseY = e.clientY;
    }
});

document.addEventListener('mouseup', endDrag);
window.addEventListener('blur', endDrag);

document.addEventListener('mousemove', (e) => {
    const localX = e.clientX;
    const localY = e.clientY;
    globalMouseX = localX;
    globalMouseY = localY;

    if (isDragging && e.buttons === 0) { endDrag(); return; }

    if (isMouseDown && !isDragging) {
        const dx = Math.abs(localX - startPos.x);
        const dy = Math.abs(localY - startPos.y);
        if (dx > 5 || dy > 5) {
            isDragging = true;
            ipcRenderer.send('window-drag-start');
        }
    }

    if (isDragging) {
        if (lastIgnoreState === true) {
            ipcRenderer.send('set-ignore-mouse', false);
            lastIgnoreState = false;
        }
        return;
    }

    if (!allowPenetration || !avatarManager) {
        if (lastIgnoreState === true) {
            ipcRenderer.send('set-ignore-mouse', false);
            lastIgnoreState = false;
        }
        return;
    }

    // 通用接口：getBounds()
    const bounds = avatarManager.getBounds();
    const isInside = (
        localX >= bounds.x &&
        localX <= bounds.x + bounds.width &&
        localY >= bounds.y &&
        localY <= bounds.y + bounds.height
    );

    const shouldIgnore = !isInside;
    if (shouldIgnore !== lastIgnoreState) {
        ipcRenderer.send('set-ignore-mouse', shouldIgnore, { forward: true });
        lastIgnoreState = shouldIgnore;
    }
});

// ===================== IPC 通信 =====================

function dispatchAvatarSignal(signal) {
    if (!avatarManager || !signal || typeof signal !== 'object') {
        return;
    }

    try {
        switch (signal.type) {
        case 'focus':
            if (typeof avatarManager.setFocusOverride === 'function') {
                avatarManager.setFocusOverride({
                    x: signal.x,
                    y: signal.y,
                    durationMs: signal.durationMs
                });
            }
            break;
        case 'blink':
            if (typeof avatarManager.triggerBlink === 'function') {
                avatarManager.triggerBlink({
                    doubleBlink: !!signal.doubleBlink
                });
            }
            break;
        case 'attention':
            if (typeof avatarManager.triggerAttentionPose === 'function') {
                avatarManager.triggerAttentionPose({
                    intensity: signal.intensity,
                    durationMs: signal.durationMs
                });
            }
            break;
        case 'reset':
            if (typeof avatarManager.clearOverrides === 'function') {
                avatarManager.clearOverrides();
            }
            break;
        default:
            break;
        }
    } catch (error) {
        console.error('avatar-signal dispatch failed:', error);
    }
}

ipcRenderer.on('config-updated', (event, newConfig) => {
    console.log('接收到新配置:', newConfig);
        // 【修复 1】更新全局 config，避免 clearErrorState 恢复旧背景
    Object.assign(config, newConfig);
    const { width, height, img_width, img_height, model_path, is_mouse_penetration } = newConfig;

    WIN_WIDTH = width;
    WIN_HEIGHT = height;
    IMG_WIDTH = img_width;
    IMG_HEIGHT = img_height;
    allowPenetration = !!is_mouse_penetration;

    applyBackgroundSettings(newConfig);

    if (!allowPenetration) {
        ipcRenderer.send('set-ignore-mouse', false);
        lastIgnoreState = false;
    } else {
        lastIgnoreState = null;
    }

    app.renderer.resize(WIN_WIDTH, WIN_HEIGHT);
    document.body.style.width = `${WIN_WIDTH}px`;
    document.body.style.height = `${WIN_HEIGHT}px`;
    const canvas = document.getElementById('canvas');
    if(canvas) {
        canvas.style.width = `${WIN_WIDTH}px`;
        canvas.style.height = `${WIN_HEIGHT}px`;
    }

    if (model_path) {
        // 使用新封装的 loadAvatar 函数
        loadAvatar(model_path);
    } else if (avatarManager) {
        avatarManager.resize(WIN_WIDTH, WIN_HEIGHT, IMG_WIDTH, IMG_HEIGHT);
    }
});

ipcRenderer.on('toggle-hit-area', () => {
    if (avatarManager) avatarManager.toggleDebug();
});

ipcRenderer.on('global-mouse-move', (event, data) => {
    globalMouseX = data.x;
    globalMouseY = data.y;
});

ipcRenderer.on('avatar-signal', (event, signal) => {
    dispatchAvatarSignal(signal);
});

window.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    if (lastIgnoreState === false) {
        ipcRenderer.send('show-context-menu');
    }
});

// ===================== 启动 =====================

applyBackgroundSettings(config);

const initialModelPath = typeof STARTUP_MODEL_PATH !== 'undefined' ? STARTUP_MODEL_PATH : '../resources/aierdeliqi_4/aierdeliqi_4.model3.json';

// 启动入口
loadAvatar(initialModelPath);

requestAnimationFrame(smoothUpdate);





