class Live2dParamApplier {
    constructor(mapping = {}) {
        this.mapping = {
            angleX: 36,
            headAngleX: 33,
            bodyAngleX: 16,
            bodyX: 22,
            angleY: 36,
            headAngleY: 33,
            bodyAngleY: 16,
            bodyY: 20,
            angleZ: 18,
            ...mapping
        };
        this.paramIndexCache = new Map();
        this.eyeOpenParams = [];
        this.breathParams = [];
    }

    prepare({ coreModel, settings } = {}) {
        this.paramIndexCache.clear();

        if (!coreModel) {
            this.eyeOpenParams = [];
            this.breathParams = [];
            return;
        }

        const configuredEyeBlinkParams = typeof settings?.getEyeBlinkParameters === 'function'
            ? settings.getEyeBlinkParameters() || []
            : [];

        this.eyeOpenParams = this._resolveExistingParams(coreModel, [
            ...configuredEyeBlinkParams,
            'ParamEyeOpen',
            'ParamEyeLOpen',
            'ParamEyeROpen',
            'PARAM_EYE_L_OPEN',
            'PARAM_EYE_R_OPEN'
        ]);
        this.breathParams = this._resolveExistingParams(coreModel, [
            'ParamBreath',
            'PARAM_BREATH'
        ]);
    }

    reset() {
        this.paramIndexCache.clear();
        this.eyeOpenParams = [];
        this.breathParams = [];
    }

    apply(coreModel, motion) {
        if (!coreModel || !motion) {
            return;
        }

        const { eye, head, body, idleMotion, blinkEyeOpen, attentionPose } = motion;
        const eyeX = this._clamp(eye.x, -1, 1);
        const eyeY = this._clamp(eye.y, -1, 1);
        const headX = this._clamp(head.x, -1, 1);
        const headY = this._clamp(head.y, -1, 1);
        const bodyX = this._clamp(body.x, -1, 1);
        const bodyY = this._clamp(body.y, -1, 1);

        this._setParam(coreModel, 'ParamEyeBallX', eyeX);
        this._setParam(coreModel, 'ParamEyeBallY', eyeY);
        this._setParam(coreModel, 'PARAM_EYE_BALL_Y', eyeY);
        this._setParam(coreModel, 'PARAM_EYE_BALL_TOP', eyeY > 0 ? eyeY : 0);
        this._setParam(coreModel, 'PARAM_EYE_BALL_BOTTOM', eyeY < 0 ? -eyeY : 0);

        this._setParam(coreModel, 'ParamAngleX', headX * this.mapping.angleX + idleMotion.angleX);
        this._setParam(coreModel, 'ParamHeadAngleX', headX * this.mapping.headAngleX + idleMotion.angleX * 0.8);
        this._setParam(coreModel, 'ParamBodyAngleX', bodyX * this.mapping.bodyAngleX + idleMotion.bodyAngleX);
        this._setParam(coreModel, 'ParamBodyX', bodyX * this.mapping.bodyX);

        this._setParam(coreModel, 'ParamAngleY', headY * this.mapping.angleY + idleMotion.angleY + attentionPose.angleY);
        this._setParam(coreModel, 'ParamHeadAngleY', headY * this.mapping.headAngleY + idleMotion.angleY * 0.8 + attentionPose.angleY * 0.85);
        this._setParam(coreModel, 'ParamBodyAngleY', bodyY * this.mapping.bodyAngleY + idleMotion.bodyAngleY + attentionPose.bodyAngleY);
        this._setParam(coreModel, 'ParamBodyY', bodyY * this.mapping.bodyY + idleMotion.bodyY + attentionPose.bodyY);

        this._setParam(coreModel, 'ParamAngleZ', headX * this.mapping.angleZ + idleMotion.angleZ);
        this._setParams(coreModel, this.eyeOpenParams, Math.min(1, blinkEyeOpen + attentionPose.eyeOpenBoost));
        this._setParams(coreModel, this.breathParams, idleMotion.breath);
    }

    _resolveExistingParams(coreModel, paramNames) {
        const resolved = [];
        paramNames.forEach((name) => {
            if (typeof name !== 'string' || !name || resolved.includes(name)) {
                return;
            }
            if (this._getParamIndex(coreModel, name) !== -1) {
                resolved.push(name);
            }
        });
        return resolved;
    }

    _setParams(coreModel, paramNames, value) {
        paramNames.forEach((name) => this._setParam(coreModel, name, value));
    }

    _getParamIndex(coreModel, name) {
        if (!this.paramIndexCache.has(name)) {
            let index = -1;
            try {
                index = coreModel.getParameterIndex(name);
            } catch (error) {
                index = -1;
            }
            this.paramIndexCache.set(name, index);
        }
        return this.paramIndexCache.get(name);
    }

    _setParam(coreModel, name, value) {
        const index = this._getParamIndex(coreModel, name);
        if (index !== -1) {
            coreModel.setParameterValueByIndex(index, value);
        }
    }

    _clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }
}

module.exports = Live2dParamApplier;
