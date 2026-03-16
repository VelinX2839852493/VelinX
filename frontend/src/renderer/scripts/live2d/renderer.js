// 引入 Node.js 内置的 path 模块，用于处理文件路径
const path = require('path');

/**
 * Live2D 互动模型核心脚本
 * =================================================================
 * 结构概览：
 * 1. 依赖与配置
 * 2. 初始化引擎
 * 3. 全局状态
 * 4. 核心算法
 * 5. 动画与物理
 * 6. 交互事件
 * 7. 模型管理
 * 8. 右键菜单
 * 9. 启动入口
 * =================================================================
 */

// ===================== 1. 依赖与配置模块 =====================
// 引入 PIXI.js 2D 渲染引擎（Live2D 渲染基础）
const PIXI = require('pixi.js');
// 引入 pixi-live2d-display 的 Cubism4 Live2DModel 类
const { Live2DModel } = require('pixi-live2d-display/cubism4');
// 引入 Electron 的 ipcRenderer 和 webFrame
const { ipcRenderer, webFrame } = require('electron');
// 引入配置读取工具
const { readConfig } = require('../../main/utils/pathUtils');

// 将 PIXI 暴露到 window，方便调试
window.PIXI = PIXI;

// --- 窗口与模型尺寸配置变量初始化 ---
// 窗口宽度（默认 300px）
let WIN_WIDTH = 300;
// 窗口高度（默认 400px）
let WIN_HEIGHT = 400;
// 模型渲染宽度（默认 300px）
let IMG_WIDTH = 300;
// 模型渲染高度（默认 400px）
let IMG_HEIGHT = 400;
// 鼠标穿透开关（默认关闭）
let allowPenetration = false;

// 1. 读取命令行参数，优先覆盖默认尺寸
if (process.argv) {
    process.argv.forEach(arg => {
        // 解析 --window-width=xxx，设置窗口宽度
        if (arg.startsWith('--window-width=')) WIN_WIDTH = parseInt(arg.split('=')[1]);
        // 解析 --window-height=xxx，设置窗口高度
        if (arg.startsWith('--window-height=')) WIN_HEIGHT = parseInt(arg.split('=')[1]);
    });
}

// 2. 读取配置文件，优先级高于命令行参数
const config = readConfig();
// 用配置文件的宽度覆盖（若无则保留原值）
WIN_WIDTH = config.width || WIN_WIDTH;
// 用配置文件的高度覆盖（若无则保留原值）
WIN_HEIGHT = config.height || WIN_HEIGHT;
// 用配置文件的模型宽度覆盖（若无则默认等于窗口高度）
IMG_WIDTH = config.img_width || WIN_HEIGHT;
// 用配置文件的模型高度覆盖（若无则默认等于窗口高度）
IMG_HEIGHT = config.img_height || IMG_HEIGHT;
// 用配置文件的鼠标穿透开关覆盖（若无则默认关闭）
allowPenetration = config.is_mouse_penetration || false;
// 读取默认模型路径（若无则为 null）
let STARTUP_MODEL_PATH = config.model_path || null;

// --- 缩放控制：强制固定页面缩放比例，防止模型显示变形 ---
// 设置网页缩放因子为 1（100%）
webFrame.setZoomFactor(1);
// 锁定缩放范围为 1~1，禁止页面缩放
webFrame.setVisualZoomLevelLimits(1, 1);

// ===================== 2. 初始化引擎模块 =====================
// DOM 加载完成后，设置 body 和 canvas 的尺寸
document.addEventListener('DOMContentLoaded', () => {
    // 设置 body 尺寸与窗口一致
    document.body.style.width = `${WIN_WIDTH}px`;
    document.body.style.height = `${WIN_HEIGHT}px`;
    // 获取页面中的 canvas 画布元素
    const canvas = document.getElementById('canvas');
    if (canvas) {
        // 设置 canvas 尺寸与窗口尺寸一致，保证渲染区域完整
        canvas.style.width = `${WIN_WIDTH}px`;
        canvas.style.height = `${WIN_HEIGHT}px`;
    }
});

// PIXI 应用实例初始化（核心渲染引擎配置）
const app = new PIXI.Application({
    // 指定渲染目标 canvas
    view: document.getElementById('canvas'),
    // 渲染宽度（与窗口一致）
    width: WIN_WIDTH,
    // 渲染高度（与窗口一致）
    height: WIN_HEIGHT,
    // 背景透明度设为 0，使模型悬浮在桌面上
    backgroundAlpha: 0,
    // 自动适配设备像素密度，防止高清屏模糊
    autoDensity: true,
    // 分辨率适配设备像素比（默认 1，高分屏通常为 2）
    resolution: window.devicePixelRatio || 1,
    // 禁用自动调整大小（手动控制尺寸）
    resizeTo: null
});

// ===================== 3. 全局状态管理 =====================
/** 当前加载的 Live2D 模型实例 */
let currentModel = null;

// 鼠标状态
// 全局鼠标 X 坐标（初始为窗口中心）
let globalMouseX = WIN_WIDTH / 2;
// 全局鼠标 Y 坐标（初始为窗口中心）
let globalMouseY = WIN_HEIGHT / 2;
// 鼠标是否在窗口内（用于判断是否停止模型跟随）
let isMouseInWindow = true;
// 鼠标左键是否按下
let isMouseDown = false;

// 拖拽状态
// 是否正在拖拽窗口
let isDragging = false;
// 拖拽开始时的鼠标坐标
let startPos = { x: 0, y: 0 };
// 拖拽开始的时间戳（用于判断拖拽触发阈值）
let dragStartTime = 0;
// 上一次的穿透状态，用于避免重复发送 IPC
let lastIgnoreState = false;


// 模型头部基准坐标，用于计算鼠标跟随
let modelHeadPos = {
    x: WIN_WIDTH / 2,
    y: WIN_HEIGHT / 2 - 50
};

// ===================== 4. 核心算法 (命中检测与坐标修正) =====================

// 调试项：命中框偏移与翻转修正
const MANUAL_OFFSET_X = 0;   // X 轴偏移量
const MANUAL_OFFSET_Y = 0;   // Y 轴偏移量
const FORCE_FLIP_Y = false;  // Y 轴翻转开关

/**
 * 将 Live2D 顶点坐标转换为屏幕像素坐标
 * @param {Live2DModel} model - Live2D 模型实例
 * @param {number} vx - 模型内部顶点 X 坐标
 * @param {number} vy - 模型内部顶点 Y 坐标
 * @returns {Object} 转换后的屏幕像素坐标
 */
function transformToPixel(model, vx, vy) {
    // 获取模型内部核心实例
    const internal = model.internalModel;
    // 获取模型原始宽度
    const w = internal.canvasWidth || internal.originalWidth || internal.width;
    // 获取模型原始高度
    const h = internal.canvasHeight || internal.originalHeight || internal.height;

    // 判断是否为 Live2D 归一化坐标
    const isNormalized = Math.abs(vx) <= 2.0 && Math.abs(vy) <= 2.0;
    let px, py;

    if (isNormalized) {
        // 将归一化坐标转换为像素坐标
        px = (vx + 1) * 0.5 * w;
        if (FORCE_FLIP_Y) {
            // 强制翻转 Y 轴（适配部分模型的坐标体系）
            py = (vy + 1) * 0.5 * h;
        } else {
            // 标准情况下需要翻转 Y 轴
            py = (1 - vy) * 0.5 * h;
        }
    } else {
        // 非归一化坐标时，直接偏移到画布中心
        px = vx + w * 0.5;
        py = -vy + h * 0.5;
    }

    // 应用手动偏移量，修正检测区域位置
    return { x: px + MANUAL_OFFSET_X, y: py + MANUAL_OFFSET_Y };
}

/**
 * 计算转换后的包围盒
 * @param {Live2DModel} model - Live2D 模型实例
 * @param {Array<number>} vertices - 顶点数组，格式为 [x1, y1, x2, y2, ...]
 * @returns {Object} 包围盒信息 {minX, minY, maxX, maxY, width, height}
 */
function getTransformedBounds(model, vertices) {
    // 初始化包围盒边界
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    // 遍历顶点数组，每次读取一对 x/y
    for (let i = 0; i < vertices.length; i += 2) {
        // 将当前顶点转换为屏幕坐标
        const p = transformToPixel(model, vertices[i], vertices[i + 1]);
        // 更新包围盒边界
        if (p.x < minX) minX = p.x;
        if (p.y < minY) minY = p.y;
        if (p.x > maxX) maxX = p.x;
        if (p.y > maxY) maxY = p.y;
    }
    // 返回完整的包围盒信息（包含宽高）
    return { minX, minY, maxX, maxY, width: maxX - minX, height: maxY - minY };
}

/**
 * 点是否在矩形内的判断函数（基础碰撞检测）
 * @param {number} pointX - 点的 X 坐标
 * @param {number} pointY - 点的 Y 坐标
 * @param {Object} rect - 矩形包围盒 {minX, minY, maxX, maxY}
 * @returns {boolean} 是否命中
 */
function isPointInRect(pointX, pointY, rect) {
    return pointX >= rect.minX && pointX <= rect.maxX &&
           pointY >= rect.minY && pointY <= rect.maxY;
}

/**
 * 自定义命中检测，替代原生 model.hitTest
 * @param {Live2DModel} model - Live2D 模型实例
 * @param {number} localX - 模型坐标系中的 X 坐标
 * @param {number} localY - 模型坐标系中的 Y 坐标
 * @returns {Array<string>} 命中的区域名称数组，例如 ["Head", "Body"]
 */
function customHitTest(model, localX, localY) {
    // 模型未加载或无命中区域配置时，返回空数组
    if (!model.internalModel || !model.internalModel.settings.hitAreas) return [];

    // 存储命中的区域名称
    const hitAreaNames = [];
    // 获取模型核心实例（包含可绘制元素、顶点数据）
    const coreModel = model.internalModel.coreModel;
    // 获取所有可绘制元素 ID
    const drawables = coreModel.getDrawableIds();
    // 获取命中区域配置
    const hitAreasSettings = model.internalModel.settings.hitAreas;

    // 遍历所有命中区域配置
    hitAreasSettings.forEach(area => {
        // 无区域名称或 ID 时跳过
        if (!area.Name || !area.Id) return;
        // 查找当前区域对应的可绘制元素索引
        const meshIndex = drawables.indexOf(area.Id);
        // 找不到对应元素时跳过
        if (meshIndex === -1) return;

        // 获取该元素的顶点坐标
        const vertices = coreModel.getDrawableVertices(meshIndex);
        // 顶点数不足时跳过（至少需要 2 个顶点）
        if (vertices.length < 2) return;

        // 计算该元素的屏幕包围盒
        const bounds = getTransformedBounds(model, vertices);

        // 增加容错 padding，防止小区域难以点中
        const paddingX = bounds.width < 10 ? 10 : 0;
        const paddingY = bounds.height < 10 ? 10 : 0;
        // 组合最终的命中矩形
        const hitRect = {
            minX: bounds.minX - paddingX,
            maxX: bounds.maxX + paddingX,
            minY: bounds.minY - paddingY,
            maxY: bounds.maxY + paddingY
        };

        // 判断鼠标是否命中
        if (isPointInRect(localX, localY, hitRect)) {
            // 命中则添加区域名称到结果数组
            hitAreaNames.push(area.Name);
        }
    });

    // 返回所有命中的区域名称
    return hitAreaNames;
}

/**
 * 解析并播放控制器动作（支持 "组名:索引" 格式）
 * @param {Live2DModel} model - Live2D 模型实例
 * @param {string} motionDef - 动作定义字符串，如 "1:1" 或 "TapHead"
 * @returns {boolean} 动作是否播放成功
 */
function playControllerMotion(model, motionDef) {
    // 无动作定义时返回失败
    if (!motionDef) return false;

    // 按冒号分割动作定义
    const parts = motionDef.split(':');
    if (parts.length === 2) {
        // 分割成功后取组名和索引
        const groupName = parts[0];
        const index = parseInt(parts[1]);

        // 打印调试信息
        console.log(`>>> 解析控制器动作: 组[${groupName}] 索引[${index}]`);
        // 获取模型动作定义
        const motions = model.internalModel.motionManager.definitions;

        // 检查该组和索引是否存在动作
        if (motions[groupName] && motions[groupName][index]) {
            // 播放指定动作（优先级 3）
            model.motion(groupName, index, 3);
            return true;
        } else {
            // 动作不存在时打印警告
            console.warn(`未找到动作: Group ${groupName} Index ${index}`);
        }
    } else {
        // 不是 "组名:索引" 格式时，直接按名称播放
        model.motion(motionDef, 0, 3);
        return true;
    }
    // 动作播放失败
    return false;
}

/**
 * 可视化调试命中区域：在模型上绘制红框显示命中检测区域，蓝框显示模型边界
 * @param {Live2DModel} model - Live2D 模型实例
 */
function visualizeHitAreas(model) {
    // 模型未加载时跳过
    if (!model.internalModel) return;

    // 移除已存在的调试图形（避免重复绘制）
    const oldGraphics = model.children.find(c => c.name === 'debug_hitarea');
    if (oldGraphics) model.removeChild(oldGraphics);

    // 创建调试图形
    const graphics = new PIXI.Graphics();
    // 标记图形名称，方便后续查找和移除
    graphics.name = 'debug_hitarea';
    // 将调试图形添加到模型节点（跟随模型渲染）
    model.addChild(graphics);

    // 获取模型内部实例
    const internal = model.internalModel;
    // 获取模型原始尺寸
    const w = internal.canvasWidth || internal.originalWidth || internal.width;
    const h = internal.canvasHeight || internal.originalHeight || internal.height;

    // 绘制蓝色画布边界（2px 半透明）
    graphics.lineStyle(2, 0x0000FF, 0.5);
    graphics.drawRect(0, 0, w, h);

    // 获取模型核心实例和可绘制元素 ID
    const coreModel = internal.coreModel;
    const drawables = coreModel.getDrawableIds();
    // 获取命中区域配置
    const hitAreas = internal.settings.hitAreas || [];

    // 遍历命中区域并绘制红框
    hitAreas.forEach((area) => {
        // 无元素 ID 时跳过
        if (!area.Id) return;
        // 查找区域对应的可绘制元素索引
        const meshIndex = drawables.indexOf(area.Id);
        if (meshIndex !== -1) {
            // 获取该元素的顶点坐标
            const vertices = coreModel.getDrawableVertices(meshIndex);
            // 无顶点数据时跳过
            if (vertices.length <= 0) return;

            // 计算转换后的包围盒
            const b = getTransformedBounds(model, vertices);

            // 绘制红色命中区域框
            graphics.lineStyle(2, 0xff0000, 1);
            graphics.beginFill(0xff0000, 0.2);
            graphics.drawRect(b.minX, b.minY, b.width, b.height);
            graphics.endFill();
        }
    });
}

// ===================== 5. 动画与物理计算模块（无延迟版） =====================

/**
 * 安全设置模型参数：参数不存在时直接跳过
 */
function setParameterIfExists(coreModel, paramName, value) {
    try {
        const paramIndex = coreModel.getParameterIndex(paramName);
        if (paramIndex !== -1) {
            coreModel.setParameterValueByIndex(paramIndex, value);
            return true;
        }
    } catch (e) { }
    return false;
}

/**
 * 更新 Live2D 模型参数：直接根据鼠标位置计算并应用
 * @param {number} mouseX - 鼠标相对于窗口左上角的 X 坐标
 * @param {number} mouseY - 鼠标相对于窗口左上角的 Y 坐标
 */
function updateLive2DParams(mouseX, mouseY) {
    if (!currentModel || !currentModel.internalModel) return;

    try {
        const coreModel = currentModel.internalModel.coreModel;

        // --- 1. 计算基础偏移（相对于头部中心） ---
        const dx = mouseX - modelHeadPos.x;
        const dy = -(mouseY - modelHeadPos.y); // 反转 Y 轴方向

        // --- [调试] 可按需打开下面的日志 ---
        // 每帧打印太多会卡，建议只在需要时开启
        // console.log(`榧犳爣X:${mouseX.toFixed(0)} 澶撮儴X:${modelHeadPos.x.toFixed(0)} 宸€糄X:${dx.toFixed(0)}`);

        // --- 2. 计算头部和身体旋转比例 ---
        // 将 viewScale 从 1500 调整为 500，提高跟随灵敏度
        // 鼠标距离头部约 500px 时接近最大角度
        const viewScale = 500;

        let rawX = dx / viewScale;
        let rawY = dy / viewScale;

        // 将值限制在 -1 ~ 1
        rawX = Math.max(-1, Math.min(1, rawX));
        rawY = Math.max(-1, Math.min(1, rawY));

        // --- 3. 计算眼球转动比例 ---
        const eyeScale = 300; // 旧值为 1000
        let eyeX = Math.max(-1, Math.min(1, dx / eyeScale));
        let eyeY = Math.max(-1, Math.min(1, dy / eyeScale));

        // --- 4. 应用参数 ---

        // 眼球参数
        setParameterIfExists(coreModel, 'ParamEyeBallX', eyeX);
        setParameterIfExists(coreModel, 'ParamEyeBallY', eyeY);
        // 部分模型使用额外的眼球参数
        setParameterIfExists(coreModel, 'PARAM_EYE_BALL_Y', eyeY);
        setParameterIfExists(coreModel, 'PARAM_EYE_BALL_TOP', eyeY > 0 ? eyeY : 0);
        setParameterIfExists(coreModel, 'PARAM_EYE_BALL_BOTTOM', eyeY < 0 ? -eyeY : 0);

        // 头部与身体参数
        // 角度倍率从 10 提升到 30
        const angleMultiplier = 30;
        const bodyMultiplier = 10; // 身体转动幅度更小

        // X 轴（左右转头）
        setParameterIfExists(coreModel, 'ParamAngleX', rawX * angleMultiplier);
        setParameterIfExists(coreModel, 'ParamHeadAngleX', rawX * angleMultiplier);
        // 身体跟着头转一点点
        setParameterIfExists(coreModel, 'ParamBodyAngleX', rawX * bodyMultiplier);
        // 身体左右平移
        setParameterIfExists(coreModel, 'ParamBodyX', rawX * 10);

        // Y 轴（抬头低头）
        setParameterIfExists(coreModel, 'ParamAngleY', rawY * angleMultiplier);
        setParameterIfExists(coreModel, 'ParamHeadAngleY', rawY * angleMultiplier);
        setParameterIfExists(coreModel, 'ParamBodyAngleY', rawY * bodyMultiplier);
        setParameterIfExists(coreModel, 'ParamBodyY', rawY * 10);

        // Z 轴（歪头）
        setParameterIfExists(coreModel, 'ParamAngleZ', rawX * 10);

        // 提交更新
        coreModel.update();

        // [调试] 如有需要，可打印最终角度
        console.log(`目标角度 X: ${(rawX * angleMultiplier).toFixed(1)}`);

    } catch (error) {
       console.warn('模型参数更新失败:', error);
    }
}

/**
 * 动画循环函数
 * 去掉 smoothX/Y 和 currentAngle 的缓动，直接响应 globalMouse
 */
function smoothUpdate() {
    // 仅在非拖拽状态下跟随鼠标
    if (!isDragging) {
        // 直接传入全局鼠标坐标，实现零延迟跟随
        updateLive2DParams(globalMouseX, globalMouseY);
    }

    // 保持循环
    requestAnimationFrame(smoothUpdate);
}

// ===================== 6. 交互事件处理模块 =====================

/** 结束拖拽：重置状态并通知主进程 */
const endDrag = () => {
    // 重置鼠标按下状态
    isMouseDown = false;
    // 正在拖拽时，重置拖拽状态并发送 IPC 消息
    if (isDragging) {
        isDragging = false;
        ipcRenderer.send('window-drag-end'); // 通知主进程：窗口拖拽结束
    }
};

// 监听鼠标按下事件（左键按下时初始化拖拽状态）
document.addEventListener('mousedown', (e) => {
    // 仅处理左键（e.button === 0 为左键）
    if (e.button === 0) {
        isMouseDown = true; // 标记鼠标按下
        isDragging = false; // 初始为未拖拽
        startPos = { x: e.clientX, y: e.clientY }; // 记录按下时的鼠标坐标
        dragStartTime = Date.now(); // 记录按下时间
        // 更新全局鼠标坐标
        globalMouseX = e.clientX;
        globalMouseY = e.clientY;
    }
});

// 监听鼠标松开事件：结束拖拽
document.addEventListener('mouseup', endDrag);
// 监听窗口失焦事件：结束拖拽，防止鼠标移出后无法松开
window.addEventListener('blur', endDrag);

// 监听鼠标移动事件：处理拖拽和鼠标穿透
document.addEventListener('mousemove', (e) => {
    // 获取窗口内的本地鼠标坐标
    const localX = e.clientX;
    const localY = e.clientY;

    // --- 1. 拖拽逻辑（最高优先级） ---
    // 正在拖拽但鼠标按键已松开时，强制结束拖拽
    if (isDragging && e.buttons === 0) {
        endDrag();
        return;
    }
    // 鼠标按下但尚未拖拽时，移动超过 5px 视为拖拽
    if (isMouseDown && !isDragging) {
        // 计算鼠标移动距离
        const dx = Math.abs(localX - startPos.x);
        const dy = Math.abs(localY - startPos.y);
        // 移动距离超过阈值，触发拖拽
        if (dx > 5 || dy > 5) {
            isDragging = true;
            ipcRenderer.send('window-drag-start'); // 通知主进程：开始拖拽窗口
        }
    }

    // 拖拽时强制关闭鼠标穿透，避免窗口失焦
    if (isDragging) {
        if (lastIgnoreState === true) {
            ipcRenderer.send('set-ignore-mouse', false); // 关闭穿透
            lastIgnoreState = false; // 更新穿透状态
        }
        return;
    }

    // --- 2. 穿透检测逻辑（背景穿透，模型拦截） ---

    // 未开启穿透或模型未加载时，强制关闭穿透
    if (!allowPenetration || !currentModel) {
        if (lastIgnoreState === true) {
            ipcRenderer.send('set-ignore-mouse', false); // 关闭穿透
            lastIgnoreState = false; // 更新穿透状态
        }
        return;
    }

    // ================== 核心穿透逻辑 ==================

    // 1. 获取模型显示边界
    const bounds = currentModel.getBounds();

    // 2. 判断鼠标是否在模型边界内
    const isInsideModelBounds = (
        localX >= bounds.x &&
        localX <= bounds.x + bounds.width &&
        localY >= bounds.y &&
        localY <= bounds.y + bounds.height
    );

    // 3. 核心逻辑：鼠标不在模型范围内时开启穿透，否则关闭
    const shouldIgnore = !isInsideModelBounds;

    // ================== 核心穿透逻辑结束 ==================

    // 仅在穿透状态变化时发送 IPC，避免重复发送
    if (shouldIgnore !== lastIgnoreState) {
        if (shouldIgnore) {
            // 开启穿透：鼠标事件穿透窗口，传给底层窗口/桌面
            // forward: true 保留 mousemove 监听，便于鼠标回到模型时关闭穿透
            ipcRenderer.send('set-ignore-mouse', true, { forward: true });
        } else {
            // 关闭穿透：拦截鼠标事件，允许点击和拖拽模型
            ipcRenderer.send('set-ignore-mouse', false);
        }
        // 更新上一次的穿透状态
        lastIgnoreState = shouldIgnore;
    }
});



// ===================== 7. 模型管理模块 =====================
// ===================== 辅助函数：应用背景配置 =====================
/**
 * 应用背景配置：设置窗口背景颜色和背景图片
 * @param {Object} cfg - 配置对象，包含 background_color 和 background_image
 */
function applyBackgroundSettings(cfg) {
    // 获取 body 样式对象
    const bodyStyle = document.body.style;

    // 1. 设置背景颜色
    if (cfg.background_color) {
        // 有背景色时应用配置
        bodyStyle.backgroundColor = cfg.background_color;
    } else {
        // 无配置时恢复透明背景
        bodyStyle.backgroundColor = '';
    }

    // 2. 设置背景图片
    if (cfg.background_image) {
        // 修复 Windows 反斜杠路径，便于 CSS 使用
        const cleanPath = cfg.background_image.replace(/\\/g, '/');
        // 设置背景图片
        bodyStyle.backgroundImage = `url("${cleanPath}")`;
        bodyStyle.backgroundSize = 'cover'; // 铺满窗口
        bodyStyle.backgroundPosition = 'center'; // 图片居中
        bodyStyle.backgroundRepeat = 'no-repeat'; // 禁止重复
    } else {
        // 无配置时清除背景图片
        bodyStyle.backgroundImage = '';
    }
}

/**
 * 加载 Live2D 模型：异步设置尺寸、交互和动作
 * @param {string|null} modelPath - 模型路径，默认为 null 时使用默认模型
 */
async function loadModel(modelPath = null) {
    // 未传入模型路径时，回退到默认模型
    if (!modelPath) modelPath = '../resources/aierdeliqi_4/aierdeliqi_4.model3.json';

    try {
        // 已有模型时，先销毁旧模型释放资源
        if (currentModel) {
            // 从 PIXI 舞台移除旧模型
            app.stage.removeChild(currentModel);
            // 销毁模型实例，释放内存
            currentModel.destroy();
            // 重置当前模型变量
            currentModel = null;
        }

        // 打印加载日志
        console.log(`正在加载模型: ${modelPath}`);
        // 调用核心 API 异步加载 Live2D 模型
        const model = await Live2DModel.from(modelPath);
        // 将模型添加到 PIXI 舞台（开始渲染）
        app.stage.addChild(model);
        // 更新当前模型实例
        currentModel = model;

        // ================== 必须保留这一项 ==================
        // 禁用库自带的鼠标跟随，避免与自定义逻辑冲突
        model.autoInteract = false;
        // ===================================================


        // 计算模型缩放比例，保持宽高比
        const scaleX = IMG_WIDTH / model.width;
        const scaleY = IMG_HEIGHT / model.height;
        // 取最小缩放比例，避免超出窗口
        model.scale.set(Math.min(scaleX, scaleY));
        // 设置模型位置：水平居中、垂直置顶
        model.x = (WIN_WIDTH - model.width) / 2;
        model.y = 0;

        // 更新模型头部基准坐标（用于鼠标跟随计算）
        const bounds = model.getBounds();
        modelHeadPos = { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height * 0.25 };

        // 开启模型交互
        model.interactive = true;
        // 设置鼠标样式为手型（hover 时显示）
        model.buttonMode = true;


        // 绑定模型点击事件
        model.on('pointertap', (e) => {
            // 拖拽状态下忽略点击
            if (isDragging) return;

            // 将全局坐标转换为模型本地坐标
            const localPoint = model.toLocal(e.data.global);
            // 打印点击坐标（调试用）
            console.log(`点击: (${localPoint.x.toFixed(0)}, ${localPoint.y.toFixed(0)})`);

            // 自定义命中检测，获取点击到的区域
            const hitAreaNames = customHitTest(model, localPoint.x, localPoint.y);

            if (hitAreaNames.length > 0) {
                // 打印命中结果
                console.log('命中:', hitAreaNames);
                // 标记是否已经播放动作
                let motionPlayed = false;

                // 1. 优先尝试播放 Tap + 区域名 动作
                hitAreaNames.forEach(name => {
                    // 已播放动作时跳过
                    if (motionPlayed) return;
                    // 组合动作组名，例如 Head -> TapHead
                    const group = `Tap${name}`;
                    // 检查动作组是否存在
                    if (model.internalModel.motionManager.definitions[group]) {
                        console.log(`>>> 播放动作: ${group}`);
                        // 播放动作（优先级 3）
                        model.motion(group, 0, 3);
                        // 标记动作已播放
                        motionPlayed = true;
                    }
                });

                // 2. 未播放到动作时，检查控制器配置
                if (!motionPlayed) {
                    // 获取模型控制器配置
                    const controllers = model.internalModel.settings.json.Controllers;
                    if (controllers && controllers.ParamHit) {
                        // 获取命中动作配置
                        const hitItems = controllers.ParamHit.Items || [];
                        // 查找匹配的命中动作
                        const matchedItem = hitItems.find(item => hitAreaNames.includes(item.HitArea));

                        if (matchedItem) {
                            console.log(`>>> 命中控制器: ${matchedItem.Name}`);
                            // 播放控制器配置的动作
                            if (matchedItem.BeginMtn) {
                                motionPlayed = playControllerMotion(model, matchedItem.BeginMtn);
                            }
                            // 控制器无动作时，播放通用 Tap 动作
                            if (!motionPlayed) {
                                console.log("控制器无特定动作，播放通用 Tap");
                                model.motion('Tap', 0, 3);
                            }
                        }
                    }
                }
            } else {
                // 未命中任何区域时，播放通用 Tap 动作
                console.log('No hit area matched.');
                model.motion('Tap');
            }
        });

        // 打印加载成功日志
        console.log('模型加载成功');
        // 启动平滑动画循环
        requestAnimationFrame(smoothUpdate);

    } catch (e) {
        // 加载失败时打印错误日志
        console.error('加载失败:', e);
    }
}

// 监听主进程发送的配置更新事件
ipcRenderer.on('config-updated', (event, newConfig) => {
    // 打印新配置（调试用）
    console.log('接收到完整配置', newConfig);
    // 解构新配置参数
    const { width, height, img_width, img_height, model_path, is_mouse_penetration } = newConfig;

    // 更新窗口/模型尺寸
    WIN_WIDTH = width;
    WIN_HEIGHT = height;
    IMG_WIDTH = img_width;
    IMG_HEIGHT = img_height;

    // 应用新的背景配置
    applyBackgroundSettings(newConfig);
    // 更新鼠标穿透开关
    allowPenetration = !!is_mouse_penetration;
    console.log("穿透模式更新为:", allowPenetration);

    // ================== 更新穿透状态 ==================
    if (!allowPenetration) {
        // 关闭穿透时，强制关闭鼠标忽略
        ipcRenderer.send('set-ignore-mouse', false);
        lastIgnoreState = false;
    } else {
        // 开启穿透时，重置状态标记，交给 mousemove 重新计算
        lastIgnoreState = null;
    }
    // ================== 更新穿透状态结束 ==================

    // 调整 PIXI 渲染器尺寸
    app.renderer.resize(WIN_WIDTH, WIN_HEIGHT);
    // 更新 body 尺寸
    document.body.style.width = `${WIN_WIDTH}px`;
    document.body.style.height = `${WIN_HEIGHT}px`;

    // 更新 canvas 画布尺寸
    const canvas = document.getElementById('canvas');
    if (canvas) {
        canvas.style.width = `${WIN_WIDTH}px`;
        canvas.style.height = `${WIN_HEIGHT}px`;
    }

    // 模型路径变化时，重新加载模型
    if (model_path) {
        console.log("检测到模型路径配置，正在重新加载模型...");
        loadModel(model_path);
    } else if (currentModel) {
        // 无新模型路径时，调整现有模型尺寸（适配新窗口尺寸）
        currentModel.scale.set(1); // 重置缩放
        // 重新计算缩放比例
        const scale = Math.min(IMG_WIDTH / currentModel.width, IMG_HEIGHT / currentModel.height);
        currentModel.scale.set(scale);
        // 重新设置模型位置（居中）
        currentModel.x = (WIN_WIDTH - currentModel.width) / 2;
        currentModel.y = 0;

        // 更新模型头部基准坐标
        const bounds = currentModel.getBounds();
        modelHeadPos.x = bounds.x + bounds.width / 2;
        modelHeadPos.y = bounds.y + bounds.height * 0.25;
    }
});

// 监听命中区域调试开关
ipcRenderer.on('toggle-hit-area', () => {
    // 模型未加载时跳过
    if (!currentModel) return;

    // 查找现有调试图形
    const oldGraphics = currentModel.children.find(c => c.name === 'debug_hitarea');

    if (oldGraphics) {
        // 存在调试图形时，移除（关闭红框）
        console.log(">>> 关闭调试红框");
        currentModel.removeChild(oldGraphics);
    } else {
        // 无调试图形时，创建（开启红框）
        console.log('Debug hit area overlay enabled.');
        visualizeHitAreas(currentModel);
    }
});



// 监听主进程发送的全局鼠标移动事件（即使鼠标在窗口外也能获取坐标）
ipcRenderer.on('global-mouse-move', (event, data) => {
    // 解构主进程传递的鼠标数据
    const { x, y, inWindow } = data;
    // 更新全局鼠标坐标
    globalMouseX = x;
    globalMouseY = y;
    // 更新鼠标是否在窗口内
    isMouseInWindow = inWindow;

});

// ===================== 8. 右键菜单与启动 =====================

// 监听右键菜单事件（替换默认右键菜单）
window.addEventListener('contextmenu', (e) => {
    // 阻止浏览器默认右键菜单
    e.preventDefault();
    // 鼠标未穿透时，通知主进程显示自定义右键菜单
    if (lastIgnoreState === false) {
        ipcRenderer.send('show-context-menu');
    }
});

// 应用初始背景配置
applyBackgroundSettings(config);
// 加载默认模型（启动入口）
loadModel(typeof STARTUP_MODEL_PATH !== 'undefined' ? STARTUP_MODEL_PATH : null);

