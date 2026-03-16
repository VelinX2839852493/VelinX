
/**
 * 负责 Live2D 模型加载、物理计算、渲染与交互
 */

const {Live2DModel} = require('pixi-live2d-display/cubism4');
const PIXI = require('pixi.js');

class Live2dManager {
    constructor(app) {
        this.app = app;           // PIXI 应用实例
        this.model = null;        // Live2D 模型实例
        this.headPos = {x: 0, y: 0}; // 头部中心坐标

        // 调试配置
        this.debugGraphics = null;
        this.MANUAL_OFFSET_X = 0;
        this.MANUAL_OFFSET_Y = 0;
    }

    /**
     * 加载模型
     * @param {string} modelPath 模型路径
     * @param {number} winWidth 窗口宽度
     * @param {number} winHeight 窗口高度
     * @param {number} imgWidth 模型渲染宽度
     * @param {number} imgHeight 模型渲染高度
     */
    async load(modelPath, winWidth, winHeight, imgWidth, imgHeight) {
        if (!modelPath) return;

        try {
            // 清理旧模型
            if (this.model) {
                this.app.stage.removeChild(this.model);
                this.model.destroy();
                this.model = null;
            }

            console.log(`[Manager] 正在加载: ${modelPath}`);

            // 加载模型
            const model = await Live2DModel.from(modelPath);
            this.app.stage.addChild(model);
            this.model = model;

            // !!! 核心设置：关闭自动交互，接管物理控制权 !!!
            model.autoInteract = false;

            // 开启交互
            model.interactive = true;
            model.buttonMode = true;

            // ✅✅✅ 【修复点】 绑定点击事件 ✅✅✅
            // 当 PIXI 检测到点击时，调用我们封装的 handleTap 方法
            model.on('pointertap', (e) => {
                // e.data.global 包含了点击时的屏幕绝对坐标
                this.handleTap(e.data.global.x, e.data.global.y);
            });

            // ✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅
            // 调整尺寸和位置
            this.resize(winWidth, winHeight, imgWidth, imgHeight);

            console.log('[Manager] 模型加载成功');
            return true;
        } catch (e) {
            console.error('[Manager] 模型加载失败:', e);
            return false;
        }
    }

    /**
     * 调整模型尺寸与位置
     */
    resize(winWidth, winHeight, imgWidth, imgHeight) {
        if (!this.model) return;

        // 计算缩放
        const scaleX = imgWidth / this.model.width;
        const scaleY = imgHeight / this.model.height;
        this.model.scale.set(Math.min(scaleX, scaleY));

        // 居中置顶
        this.model.x = (winWidth - this.model.width) / 2;
        this.model.y = 0;

        // 更新头部基准点 (用于物理计算)
        const bounds = this.model.getBounds();
        this.headPos = {
            x: bounds.x + bounds.width / 2,
            y: bounds.y + bounds.height * 0.25
        };
    }

    /**
     * 物理更新 (每一帧调用)
     * @param {number} mouseX 全局鼠标 X
     * @param {number} mouseY 全局鼠标 Y
     */
    updatePhysics(mouseX, mouseY) {
        if (!this.model || !this.model.internalModel) return;

        try {
            const coreModel = this.model.internalModel.coreModel;

            // --- 1. 计算偏移量 ---
            const dx = mouseX - this.headPos.x;
            const dy = -(mouseY - this.headPos.y); // Y轴取反

            // --- 2. 头部转动比率 (调校后的参数) ---
            const viewScale = 500;

            let rawX = Math.max(-1, Math.min(1, dx / viewScale));
            let rawY = Math.max(-1, Math.min(1, dy / viewScale));

            // --- 3. 眼球转动比率 ---
            const eyeScale = 600;
            let eyeX = Math.max(-1, Math.min(1, dx / eyeScale));
            let eyeY = Math.max(-1, Math.min(1, dy / eyeScale));

            // --- 4. 应用参数 ---

            // 眼球
            this._setParam(coreModel, 'ParamEyeBallX', eyeX);
            this._setParam(coreModel, 'ParamEyeBallY', eyeY);
            this._setParam(coreModel, 'PARAM_EYE_BALL_Y', eyeY);
            this._setParam(coreModel, 'PARAM_EYE_BALL_TOP', eyeY > 0 ? eyeY : 0);
            this._setParam(coreModel, 'PARAM_EYE_BALL_BOTTOM', eyeY < 0 ? -eyeY : 0);

            // 头部与身体
            const angleMultiplier = 30; // 头部最大角度
            const bodyMultiplier = 20;  // 身体跟随幅度 (增强)

            // X 轴
            this._setParam(coreModel, 'ParamAngleX', rawX * angleMultiplier);
            this._setParam(coreModel, 'ParamHeadAngleX', rawX * angleMultiplier);
            this._setParam(coreModel, 'ParamBodyAngleX', rawX * bodyMultiplier);
            this._setParam(coreModel, 'ParamBodyX', rawX * 25); // 身体左右位移

            // Y 轴
            this._setParam(coreModel, 'ParamAngleY', rawY * angleMultiplier);
            this._setParam(coreModel, 'ParamHeadAngleY', rawY * angleMultiplier);
            this._setParam(coreModel, 'ParamBodyAngleY', rawY * bodyMultiplier);
            this._setParam(coreModel, 'ParamBodyY', rawY * 25); // 身体上下位移

            // Z 轴 (歪头)
            this._setParam(coreModel, 'ParamAngleZ', rawX * 15);

            coreModel.update();

        } catch (error) {
            // 避免报错刷屏
        }
    }

    /**
     * 处理点击事件
     * @param {number} globalX 全局点击 X
     * @param {number} globalY 全局点击 Y
     */
    handleTap(globalX, globalY) {
        if (!this.model) return;

        // 转为本地坐标
        const localPoint = this.model.toLocal(new PIXI.Point(globalX, globalY));

        console.log(`[Manager] 点击本地坐标: (${localPoint.x.toFixed(0)}, ${localPoint.y.toFixed(0)})`);

        // 执行命中检测
        const hitAreaNames = this._customHitTest(localPoint.x, localPoint.y);

        if (hitAreaNames.length > 0) {
            console.log('[Manager] 命中区域:', hitAreaNames);
            let motionPlayed = false;

            // 1. 尝试 Tap + 区域名
            hitAreaNames.forEach(name => {
                if (motionPlayed) return;
                const group = `Tap${name}`;
                if (this.model.internalModel.motionManager.definitions[group]) {
                    console.log(`>>> 播放动作: ${group}`);
                    this.model.motion(group, 0, 3);
                    motionPlayed = true;
                }
            });

            // 2. 尝试控制器配置
            if (!motionPlayed) {
                const controllers = this.model.internalModel.settings.json.Controllers;
                if (controllers && controllers.ParamHit) {
                    const hitItems = controllers.ParamHit.Items || [];
                    const matchedItem = hitItems.find(item => hitAreaNames.includes(item.HitArea));
                    if (matchedItem) {
                        if (matchedItem.BeginMtn) {
                            motionPlayed = this._playControllerMotion(matchedItem.BeginMtn);
                        }
                    }
                }
            }

            // 3. 通用动作
            if (!motionPlayed) {
                this.model.motion('Tap', 0, 3);
            }
        } else {
            console.log("[Manager] 未命中，播放通用 Tap");
            this.model.motion('Tap');
        }
    }

    /** 获取模型包围盒 (用于穿透检测) */
    getBounds() {
        return this.model ? this.model.getBounds() : {x: 0, y: 0, width: 0, height: 0};
    }

    /** 切换调试红框 */
    toggleDebug() {
        if (!this.model) return;

        // 简单实现：这里可以把之前的 visualizeHitAreas 逻辑放进来
        // 为了代码简洁，这里仅做开关逻辑演示，具体绘图代码可复用之前的
        const oldGraphics = this.model.children.find(c => c.name === 'debug_hitarea');
        if (oldGraphics) {
            this.model.removeChild(oldGraphics);
        } else {
            this._visualizeHitAreas(); // 调用内部绘图方法
        }
    }

    // ================= 私有辅助方法 =================

    _setParam(core, name, val) {
        try {
            const idx = core.getParameterIndex(name);
            if (idx !== -1) core.setParameterValueByIndex(idx, val);
        } catch (e) {
        }
    }

    _playControllerMotion(motionDef) {
        if (!motionDef) return false;
        const parts = motionDef.split(':');
        if (parts.length === 2) {
            const groupName = parts[0];
            const index = parseInt(parts[1]);
            const motions = this.model.internalModel.motionManager.definitions;
            if (motions[groupName] && motions[groupName][index]) {
                this.model.motion(groupName, index, 3);
                return true;
            }
        } else {
            this.model.motion(motionDef, 0, 3);
            return true;
        }
        return false;
    }

    // 复用之前的 HitTest 逻辑
    _customHitTest(localX, localY) {
        if (!this.model || !this.model.internalModel || !this.model.internalModel.settings.hitAreas) return [];
        const hitAreaNames = [];
        const coreModel = this.model.internalModel.coreModel;
        const drawables = coreModel.getDrawableIds();
        const hitAreasSettings = this.model.internalModel.settings.hitAreas;

        hitAreasSettings.forEach(area => {
            if (!area.Name || !area.Id) return;
            const meshIndex = drawables.indexOf(area.Id);
            if (meshIndex === -1) return;
            const vertices = coreModel.getDrawableVertices(meshIndex);
            if (vertices.length < 2) return;

            const bounds = this._getTransformedBounds(vertices);
            const paddingX = bounds.width < 10 ? 10 : 0;
            const paddingY = bounds.height < 10 ? 10 : 0;
            const hitRect = {
                minX: bounds.minX - paddingX, maxX: bounds.maxX + paddingX,
                minY: bounds.minY - paddingY, maxY: bounds.maxY + paddingY
            };

            if (localX >= hitRect.minX && localX <= hitRect.maxX &&
                localY >= hitRect.minY && localY <= hitRect.maxY) {
                hitAreaNames.push(area.Name);
            }
        });
        return hitAreaNames;
    }

    _getTransformedBounds(vertices) {
        let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        for (let i = 0; i < vertices.length; i += 2) {
            const p = this._transformToPixel(vertices[i], vertices[i + 1]);
            if (p.x < minX) minX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.x > maxX) maxX = p.x;
            if (p.y > maxY) maxY = p.y;
        }
        return {minX, minY, maxX, maxY, width: maxX - minX, height: maxY - minY};
    }

    _transformToPixel(vx, vy) {
        const internal = this.model.internalModel;
        const w = internal.canvasWidth || internal.originalWidth || internal.width;
        const h = internal.canvasHeight || internal.originalHeight || internal.height;
        const isNormalized = Math.abs(vx) <= 2.0 && Math.abs(vy) <= 2.0;
        let px, py;
        if (isNormalized) {
            px = (vx + 1) * 0.5 * w;
            py = (1 - vy) * 0.5 * h; // 默认不翻转
        } else {
            px = vx + w * 0.5;
            py = -vy + h * 0.5;
        }
        return {x: px + this.MANUAL_OFFSET_X, y: py + this.MANUAL_OFFSET_Y};
    }

    _visualizeHitAreas() {
        // 简化的调试绘图逻辑
        const graphics = new PIXI.Graphics();
        graphics.name = 'debug_hitarea';
        this.model.addChild(graphics);
        const internal = this.model.internalModel;
        const w = internal.canvasWidth || internal.originalWidth || internal.width;
        const h = internal.canvasHeight || internal.originalHeight || internal.height;
        graphics.lineStyle(2, 0x0000FF, 0.5);
        graphics.drawRect(0, 0, w, h);

        const coreModel = internal.coreModel;
        const drawables = coreModel.getDrawableIds();
        const hitAreas = internal.settings.hitAreas || [];

        hitAreas.forEach((area) => {
            if (!area.Id) return;
            const meshIndex = drawables.indexOf(area.Id);
            if (meshIndex !== -1) {
                const vertices = coreModel.getDrawableVertices(meshIndex);
                if (vertices.length > 0) {
                    const b = this._getTransformedBounds(vertices);
                    graphics.lineStyle(2, 0xff0000, 1);
                    graphics.beginFill(0xff0000, 0.2);
                    graphics.drawRect(b.minX, b.minY, b.width, b.height);
                    graphics.endFill();
                }
            }
        });
    }

    /**
     * 彻底销毁模型并从舞台移除
     */
    destroy() {
        if (this.model) {
            console.log("[Live2DManager] 正在从舞台移除模型");
            this.app.stage.removeChild(this.model);
            this.model.destroy(); // 释放资源
            this.model = null;
        }
        // 如果有调试框，也一并移除
        const debug = this.app.stage.getChildByName('debug_hitarea');
        if (debug) this.app.stage.removeChild(debug);
    }
}

module.exports = Live2dManager;