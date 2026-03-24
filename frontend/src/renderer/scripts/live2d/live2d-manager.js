const { Live2DModel } = require('pixi-live2d-display/cubism4');
const PIXI = require('pixi.js');

const Live2dMotionController = require('./motion/live2d-motion-controller');
const Live2dParamApplier = require('./params/live2d-param-applier');
const { resolveLive2dProfile } = require('./profile/live2d-profile-resolver');

class Live2dManager {
    constructor(app) {
        this.app = app;
        this.model = null;
        this.headPos = { x: 0, y: 0 };

        this.debugGraphics = null;
        this.MANUAL_OFFSET_X = 0;
        this.MANUAL_OFFSET_Y = 0;

        this.activeProfile = resolveLive2dProfile(null);
        this.motionController = new Live2dMotionController(this.activeProfile.motion);
        this.paramApplier = new Live2dParamApplier(this.activeProfile.params);
    }

    async load(modelPath, winWidth, winHeight, imgWidth, imgHeight) {
        if (!modelPath) return;

        try {
            this._disposeCurrentModel();
            this._applyProfile(modelPath);

            console.log(`[Live2DManager] loading model: ${modelPath}`);

            const model = await Live2DModel.from(modelPath);
            this.app.stage.addChild(model);
            this.model = model;

            model.autoInteract = false;
            model.interactive = true;
            model.buttonMode = true;
            model.on('pointertap', (event) => {
                this.handleTap(event.data.global.x, event.data.global.y);
            });

            this.resize(winWidth, winHeight, imgWidth, imgHeight);
            this._prepareRuntime();

            console.log('[Live2DManager] model loaded');
            return true;
        } catch (error) {
            console.error('[Live2DManager] model load failed:', error);
            return false;
        }
    }

    resize(winWidth, winHeight, imgWidth, imgHeight) {
        if (!this.model) return;

        const scaleX = imgWidth / this.model.width;
        const scaleY = imgHeight / this.model.height;
        this.model.scale.set(Math.min(scaleX, scaleY));

        this.model.x = (winWidth - this.model.width) / 2;
        this.model.y = 0;

        const bounds = this.model.getBounds();
        this.headPos = {
            x: bounds.x + bounds.width / 2,
            y: bounds.y + bounds.height * 0.25
        };
    }

    updatePhysics(mouseX, mouseY) {
        if (!this.model || !this.model.internalModel) return;

        try {
            const coreModel = this.model.internalModel.coreModel;
            const motion = this.motionController.update({
                mouseX,
                mouseY,
                headPos: this.headPos
            });

            this.paramApplier.apply(coreModel, motion);
            coreModel.update();
        } catch (error) {
            // Keep the desktop widget resilient even when a model misses some params.
        }
    }

    setFocusOverride(payload) {
        return this.motionController.setFocusOverride(payload);
    }

    triggerBlink(payload) {
        return this.motionController.triggerBlink(payload);
    }

    triggerAttentionPose(payload) {
        return this.motionController.triggerAttentionPose(payload);
    }

    clearOverrides() {
        this.motionController.clearOverrides();
    }

    handleTap(globalX, globalY) {
        if (!this.model) return;

        const localPoint = this.model.toLocal(new PIXI.Point(globalX, globalY));
        console.log(
            `[Live2DManager] tap local point: (${localPoint.x.toFixed(0)}, ${localPoint.y.toFixed(0)})`
        );

        const hitAreaNames = this._customHitTest(localPoint.x, localPoint.y);

        if (hitAreaNames.length > 0) {
            console.log('[Live2DManager] hit areas:', hitAreaNames);
            let motionPlayed = false;

            hitAreaNames.forEach((name) => {
                if (motionPlayed) return;
                const group = `Tap${name}`;
                if (this.model.internalModel.motionManager.definitions[group]) {
                    this.model.motion(group, 0, 3);
                    motionPlayed = true;
                }
            });

            if (!motionPlayed) {
                const controllers = this.model.internalModel.settings?.json?.Controllers;
                if (controllers && controllers.ParamHit) {
                    const hitItems = controllers.ParamHit.Items || [];
                    const matchedItem = hitItems.find((item) => hitAreaNames.includes(item.HitArea));
                    if (matchedItem?.BeginMtn) {
                        motionPlayed = this._playControllerMotion(matchedItem.BeginMtn);
                    }
                }
            }

            if (!motionPlayed) {
                this.model.motion('Tap', 0, 3);
            }
        } else {
            this.model.motion('Tap');
        }
    }

    getBounds() {
        return this.model ? this.model.getBounds() : { x: 0, y: 0, width: 0, height: 0 };
    }

    toggleDebug() {
        if (!this.model) return;

        const oldGraphics = this.model.children.find((child) => child.name === 'debug_hitarea');
        if (oldGraphics) {
            this.model.removeChild(oldGraphics);
        } else {
            this._visualizeHitAreas();
        }
    }

    destroy() {
        this.motionController.reset();
        this.paramApplier.reset();
        this._disposeCurrentModel();
    }

    _prepareRuntime() {
        this.motionController.reset();

        if (!this.model || !this.model.internalModel) {
            this.paramApplier.reset();
            return;
        }

        this.paramApplier.prepare({
            coreModel: this.model.internalModel.coreModel,
            settings: this.model.internalModel.settings
        });
    }

    _applyProfile(modelPath) {
        this.activeProfile = resolveLive2dProfile(modelPath);
        this.motionController = new Live2dMotionController(this.activeProfile.motion);
        this.paramApplier = new Live2dParamApplier(this.activeProfile.params);

        const sourceLabel = this.activeProfile.sources.join(', ');
        console.log(`[Live2DManager] applied profile: ${this.activeProfile.id} (${sourceLabel})`);
    }

    _disposeCurrentModel() {
        if (this.model) {
            console.log('[Live2DManager] removing model from stage');
            this.app.stage.removeChild(this.model);
            this.model.destroy();
            this.model = null;
        }

        const debug = this.app.stage.getChildByName('debug_hitarea');
        if (debug) {
            this.app.stage.removeChild(debug);
        }
    }

    _playControllerMotion(motionDef) {
        if (!motionDef) return false;

        const parts = motionDef.split(':');
        if (parts.length === 2) {
            const groupName = parts[0];
            const index = parseInt(parts[1], 10);
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

    _customHitTest(localX, localY) {
        if (!this.model || !this.model.internalModel || !this.model.internalModel.settings.hitAreas) {
            return [];
        }

        const hitAreaNames = [];
        const coreModel = this.model.internalModel.coreModel;
        const drawables = coreModel.getDrawableIds();
        const hitAreasSettings = this.model.internalModel.settings.hitAreas;

        hitAreasSettings.forEach((area) => {
            if (!area.Name || !area.Id) return;

            const meshIndex = drawables.indexOf(area.Id);
            if (meshIndex === -1) return;

            const vertices = coreModel.getDrawableVertices(meshIndex);
            if (vertices.length < 2) return;

            const bounds = this._getTransformedBounds(vertices);
            const paddingX = bounds.width < 10 ? 10 : 0;
            const paddingY = bounds.height < 10 ? 10 : 0;
            const hitRect = {
                minX: bounds.minX - paddingX,
                maxX: bounds.maxX + paddingX,
                minY: bounds.minY - paddingY,
                maxY: bounds.maxY + paddingY
            };

            if (
                localX >= hitRect.minX &&
                localX <= hitRect.maxX &&
                localY >= hitRect.minY &&
                localY <= hitRect.maxY
            ) {
                hitAreaNames.push(area.Name);
            }
        });

        return hitAreaNames;
    }

    _getTransformedBounds(vertices) {
        let minX = Infinity;
        let minY = Infinity;
        let maxX = -Infinity;
        let maxY = -Infinity;

        for (let index = 0; index < vertices.length; index += 2) {
            const point = this._transformToPixel(vertices[index], vertices[index + 1]);
            if (point.x < minX) minX = point.x;
            if (point.y < minY) minY = point.y;
            if (point.x > maxX) maxX = point.x;
            if (point.y > maxY) maxY = point.y;
        }

        return { minX, minY, maxX, maxY, width: maxX - minX, height: maxY - minY };
    }

    _transformToPixel(vx, vy) {
        const internal = this.model.internalModel;
        const width = internal.canvasWidth || internal.originalWidth || internal.width;
        const height = internal.canvasHeight || internal.originalHeight || internal.height;
        const isNormalized = Math.abs(vx) <= 2.0 && Math.abs(vy) <= 2.0;

        let px;
        let py;

        if (isNormalized) {
            px = (vx + 1) * 0.5 * width;
            py = (1 - vy) * 0.5 * height;
        } else {
            px = vx + width * 0.5;
            py = -vy + height * 0.5;
        }

        return { x: px + this.MANUAL_OFFSET_X, y: py + this.MANUAL_OFFSET_Y };
    }

    _visualizeHitAreas() {
        const graphics = new PIXI.Graphics();
        graphics.name = 'debug_hitarea';
        this.model.addChild(graphics);

        const internal = this.model.internalModel;
        const width = internal.canvasWidth || internal.originalWidth || internal.width;
        const height = internal.canvasHeight || internal.originalHeight || internal.height;
        graphics.lineStyle(2, 0x0000FF, 0.5);
        graphics.drawRect(0, 0, width, height);

        const coreModel = internal.coreModel;
        const drawables = coreModel.getDrawableIds();
        const hitAreas = internal.settings.hitAreas || [];

        hitAreas.forEach((area) => {
            if (!area.Id) return;

            const meshIndex = drawables.indexOf(area.Id);
            if (meshIndex === -1) return;

            const vertices = coreModel.getDrawableVertices(meshIndex);
            if (vertices.length <= 0) return;

            const bounds = this._getTransformedBounds(vertices);
            graphics.lineStyle(2, 0xff0000, 1);
            graphics.beginFill(0xff0000, 0.2);
            graphics.drawRect(bounds.minX, bounds.minY, bounds.width, bounds.height);
            graphics.endFill();
        });
    }
}

module.exports = Live2dManager;
