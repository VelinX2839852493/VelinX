class Live2dMotionController {
    constructor(config = {}) {
        this.animationConfig = {
            breathCycleMs: 4200,
            breathAngleX: 2.8,
            breathAngleY: 1.9,
            breathAngleZ: 1.6,
            breathBodyAngleX: 3.8,
            breathBodyAngleY: 1.4,
            breathBodyY: 6.0,
            blinkCloseMs: 95,
            blinkHoldMs: 55,
            blinkOpenMs: 165,
            blinkIntervalMinMs: 1600,
            blinkIntervalMaxMs: 3600,
            blinkDoubleChance: 0.25,
            blinkDoubleGapMinMs: 120,
            blinkDoubleGapMaxMs: 220,
            pointerViewScale: 460,
            eyeFollowSpring: 190,
            eyeFollowDamping: 24,
            headFollowSpring: 108,
            headFollowDamping: 17,
            bodyFollowSpring: 64,
            bodyFollowDamping: 13,
            idleEnterDelayMs: 2500,
            idleMovementThresholdPx: 3,
            idleWanderIntervalMinMs: 2200,
            idleWanderIntervalMaxMs: 3800,
            idleWanderRangeX: 0.32,
            idleWanderRangeY: 0.24,
            idleWanderTravelSpring: 40,
            idleWanderTravelDamping: 12,
            idleMicroSaccadeDelayMinMs: 320,
            idleMicroSaccadeDelayMaxMs: 920,
            idleMicroSaccadeHoldMinMs: 110,
            idleMicroSaccadeHoldMaxMs: 260,
            idleMicroSaccadeRangeX: 0.06,
            idleMicroSaccadeRangeY: 0.045,
            idleMicroSaccadeSpring: 210,
            idleMicroSaccadeDamping: 28,
            attentionRisePortion: 0.38,
            attentionAngleY: 18,
            attentionBodyAngleY: 7.5,
            attentionBodyY: 12,
            attentionEyeOpenBoost: 0.08,
            postureDriftPrimaryCycleMs: 9800,
            postureDriftSecondaryCycleMs: 14600,
            postureDriftAngleX: 1.1,
            postureDriftAngleY: 0.75,
            postureDriftAngleZ: 1.9,
            postureDriftBodyAngleX: 1.0,
            postureDriftBodyAngleY: 0.75,
            postureDriftBodyY: 2.8,
            ...config
        };
        this.animationState = this._createAnimationState();
    }

    reset() {
        this.animationState = this._createAnimationState();
    }

    update({ mouseX = 0, mouseY = 0, headPos = { x: 0, y: 0 } } = {}) {
        const now = this._now();
        const deltaMs = this._getDeltaMs(now);
        const pointerTarget = this._normalizePointerFocus(mouseX, mouseY, headPos);

        return this._updateAnimationTracks({
            now,
            deltaMs,
            mouseX,
            mouseY,
            pointerTarget
        });
    }

    setFocusOverride({ x, y, durationMs = 1400, blendMs = 220 } = {}) {
        const focusX = Number(x);
        const focusY = Number(y);
        const resolvedDurationMs = Number(durationMs);
        const resolvedBlendMs = Number(blendMs);

        if (!Number.isFinite(focusX) || !Number.isFinite(focusY)) {
            return false;
        }

        this.animationState.focusOverride = {
            active: true,
            x: this._clamp(focusX, -1, 1),
            y: this._clamp(focusY, -1, 1),
            startedAt: this._now(),
            durationMs: Math.max(1, Number.isFinite(resolvedDurationMs) ? resolvedDurationMs : 1400),
            blendMs: Math.max(0, Number.isFinite(resolvedBlendMs) ? resolvedBlendMs : 220)
        };

        return true;
    }

    triggerBlink({ doubleBlink = false } = {}) {
        this._queueBlinkRequest({
            source: 'manual',
            executeAt: this._now(),
            doubleBlink: !!doubleBlink
        });
        return true;
    }

    triggerAttentionPose({ intensity = 1, durationMs = 1100 } = {}) {
        const nextIntensity = Math.max(0, Math.min(1.5, Number(intensity) || 0));
        const resolvedDurationMs = Number(durationMs);

        if (nextIntensity <= 0) {
            return false;
        }

        this.animationState.attentionPose = {
            active: true,
            intensity: nextIntensity,
            startedAt: this._now(),
            durationMs: Math.max(1, Number.isFinite(resolvedDurationMs) ? resolvedDurationMs : 1100)
        };

        return true;
    }

    clearOverrides() {
        const blinkQueue = this.animationState.blinkQueue;

        this.animationState.focusOverride = {
            active: false,
            x: 0,
            y: 0,
            startedAt: 0,
            durationMs: 0,
            blendMs: 0
        };
        this.animationState.attentionPose = {
            active: false,
            intensity: 0,
            startedAt: 0,
            durationMs: 0
        };

        blinkQueue.queue = blinkQueue.queue.filter((request) => request.source === 'natural');
        if (blinkQueue.pendingFollowUp && blinkQueue.pendingFollowUp.source === 'followup-manual') {
            blinkQueue.pendingFollowUp = null;
        }
        if (blinkQueue.activeRequest && blinkQueue.activeRequest.source === 'manual') {
            blinkQueue.activeRequest.doubleBlink = false;
        }
    }

    _createAnimationState() {
        const now = this._now();
        return {
            startedAt: now,
            lastUpdatedAt: now,
            pointerFollow: {
                lastMouse: null,
                lastMoveAt: now,
                eye: this._createMotionPoint(),
                head: this._createMotionPoint(),
                body: this._createMotionPoint(),
                target: { x: 0, y: 0 }
            },
            idleWander: {
                active: false,
                current: this._createMotionPoint(),
                target: { x: 0, y: 0 },
                microOffset: this._createMotionPoint(),
                microTarget: { x: 0, y: 0 },
                nextMicroAt: now + this._randomBetween(
                    this.animationConfig.idleMicroSaccadeDelayMinMs,
                    this.animationConfig.idleMicroSaccadeDelayMaxMs
                ),
                microHoldUntil: 0,
                nextSampleAt: now + this._randomBetween(
                    this.animationConfig.idleWanderIntervalMinMs,
                    this.animationConfig.idleWanderIntervalMaxMs
                )
            },
            blinkQueue: {
                phase: 'idle',
                phaseStartedAt: now,
                nextNaturalBlinkAt: now + this._randomBetween(
                    this.animationConfig.blinkIntervalMinMs,
                    this.animationConfig.blinkIntervalMaxMs
                ),
                queue: [],
                activeRequest: null,
                pendingFollowUp: null,
                naturalSeriesActive: false
            },
            focusOverride: {
                active: false,
                x: 0,
                y: 0,
                startedAt: 0,
                durationMs: 0,
                blendMs: 0
            },
            attentionPose: {
                active: false,
                intensity: 0,
                startedAt: 0,
                durationMs: 0
            }
        };
    }

    _updateAnimationTracks({ now, deltaMs, mouseX, mouseY, pointerTarget }) {
        const pointerFollow = this.animationState.pointerFollow;
        const idleMotion = this._getBreathMotion(now);

        pointerFollow.target = pointerTarget;
        this._updatePointerActivity(now, mouseX, mouseY, pointerTarget);

        const wanderTarget = this._updateIdleWander(now, deltaMs);
        const baseTarget = wanderTarget || pointerTarget;
        const blendedTarget = this._applyFocusOverride(now, baseTarget);

        pointerFollow.eye = this._approachPoint(
            pointerFollow.eye,
            blendedTarget,
            this.animationConfig.eyeFollowSpring,
            this.animationConfig.eyeFollowDamping,
            deltaMs
        );
        pointerFollow.head = this._approachPoint(
            pointerFollow.head,
            blendedTarget,
            this.animationConfig.headFollowSpring,
            this.animationConfig.headFollowDamping,
            deltaMs
        );
        pointerFollow.body = this._approachPoint(
            pointerFollow.body,
            blendedTarget,
            this.animationConfig.bodyFollowSpring,
            this.animationConfig.bodyFollowDamping,
            deltaMs
        );

        return {
            eye: pointerFollow.eye,
            head: pointerFollow.head,
            body: pointerFollow.body,
            idleMotion,
            blinkEyeOpen: this._updateBlinkQueue(now),
            attentionPose: this._getAttentionPose(now)
        };
    }

    _updatePointerActivity(now, mouseX, mouseY, pointerTarget) {
        const pointerFollow = this.animationState.pointerFollow;
        const lastMouse = pointerFollow.lastMouse;

        if (!lastMouse) {
            pointerFollow.lastMouse = { x: mouseX, y: mouseY };
            pointerFollow.lastMoveAt = now;
            return;
        }

        const dx = mouseX - lastMouse.x;
        const dy = mouseY - lastMouse.y;
        const moved = (dx * dx + dy * dy) >= (this.animationConfig.idleMovementThresholdPx ** 2);

        if (moved) {
            pointerFollow.lastMoveAt = now;
            this._stopIdleWander(pointerTarget, now);
        }

        pointerFollow.lastMouse = { x: mouseX, y: mouseY };
    }

    _updateIdleWander(now, deltaMs) {
        const pointerFollow = this.animationState.pointerFollow;
        const idleWander = this.animationState.idleWander;

        if (!idleWander.active) {
            if (now - pointerFollow.lastMoveAt < this.animationConfig.idleEnterDelayMs) {
                return null;
            }

            idleWander.active = true;
            this._resetMotionPoint(idleWander.current, pointerFollow.head.x, pointerFollow.head.y);
            idleWander.target = this._sampleIdleWanderTarget();
            this._resetMotionPoint(idleWander.microOffset, 0, 0);
            idleWander.microTarget = { x: 0, y: 0 };
            idleWander.microHoldUntil = 0;
            idleWander.nextMicroAt = now + this._randomBetween(
                this.animationConfig.idleMicroSaccadeDelayMinMs,
                this.animationConfig.idleMicroSaccadeDelayMaxMs
            );
            idleWander.nextSampleAt = now + this._randomBetween(
                this.animationConfig.idleWanderIntervalMinMs,
                this.animationConfig.idleWanderIntervalMaxMs
            );
        }

        if (now >= idleWander.nextSampleAt) {
            idleWander.target = this._sampleIdleWanderTarget();
            idleWander.microTarget = { x: 0, y: 0 };
            idleWander.microHoldUntil = 0;
            idleWander.nextMicroAt = now + this._randomBetween(
                this.animationConfig.idleMicroSaccadeDelayMinMs,
                this.animationConfig.idleMicroSaccadeDelayMaxMs
            );
            idleWander.nextSampleAt = now + this._randomBetween(
                this.animationConfig.idleWanderIntervalMinMs,
                this.animationConfig.idleWanderIntervalMaxMs
            );
        }

        this._updateIdleMicroSaccade(now, deltaMs);
        idleWander.current = this._approachPoint(
            idleWander.current,
            idleWander.target,
            this.animationConfig.idleWanderTravelSpring,
            this.animationConfig.idleWanderTravelDamping,
            deltaMs
        );

        return {
            x: idleWander.current.x + idleWander.microOffset.x,
            y: idleWander.current.y + idleWander.microOffset.y
        };
    }

    _updateIdleMicroSaccade(now, deltaMs) {
        const idleWander = this.animationState.idleWander;

        if (!idleWander.active) {
            return;
        }

        if (idleWander.microHoldUntil > 0 && now >= idleWander.microHoldUntil) {
            idleWander.microTarget = { x: 0, y: 0 };
            idleWander.microHoldUntil = 0;
            idleWander.nextMicroAt = now + this._randomBetween(
                this.animationConfig.idleMicroSaccadeDelayMinMs,
                this.animationConfig.idleMicroSaccadeDelayMaxMs
            );
        }

        if (idleWander.microHoldUntil === 0 && now >= idleWander.nextMicroAt) {
            const deltaX = idleWander.target.x - idleWander.current.x;
            const deltaY = idleWander.target.y - idleWander.current.y;
            const isFixating = (deltaX * deltaX + deltaY * deltaY) <= 0.0016;

            if (!isFixating) {
                idleWander.nextMicroAt = now + 120;
            } else {
                idleWander.microTarget = {
                    x: this._randomBetween(
                        -this.animationConfig.idleMicroSaccadeRangeX,
                        this.animationConfig.idleMicroSaccadeRangeX
                    ),
                    y: this._randomBetween(
                        -this.animationConfig.idleMicroSaccadeRangeY,
                        this.animationConfig.idleMicroSaccadeRangeY
                    )
                };
                idleWander.microHoldUntil = now + this._randomBetween(
                    this.animationConfig.idleMicroSaccadeHoldMinMs,
                    this.animationConfig.idleMicroSaccadeHoldMaxMs
                );
                idleWander.nextMicroAt = Number.POSITIVE_INFINITY;
            }
        }

        idleWander.microOffset = this._approachPoint(
            idleWander.microOffset,
            idleWander.microTarget,
            this.animationConfig.idleMicroSaccadeSpring,
            this.animationConfig.idleMicroSaccadeDamping,
            deltaMs
        );
    }

    _stopIdleWander(pointerTarget, now) {
        const idleWander = this.animationState.idleWander;
        if (!idleWander.active) {
            return;
        }

        idleWander.active = false;
        this._resetMotionPoint(idleWander.current, pointerTarget.x, pointerTarget.y);
        this._resetMotionPoint(idleWander.microOffset, 0, 0);
        idleWander.target = { ...pointerTarget };
        idleWander.microTarget = { x: 0, y: 0 };
        idleWander.microHoldUntil = 0;
        idleWander.nextMicroAt = now + this._randomBetween(
            this.animationConfig.idleMicroSaccadeDelayMinMs,
            this.animationConfig.idleMicroSaccadeDelayMaxMs
        );
        idleWander.nextSampleAt = now + this._randomBetween(
            this.animationConfig.idleWanderIntervalMinMs,
            this.animationConfig.idleWanderIntervalMaxMs
        );
    }

    _applyFocusOverride(now, baseTarget) {
        const focusOverride = this.animationState.focusOverride;

        if (!focusOverride.active) {
            return baseTarget;
        }

        const elapsed = now - focusOverride.startedAt;
        if (elapsed >= focusOverride.durationMs) {
            focusOverride.active = false;
            return baseTarget;
        }

        const remaining = focusOverride.durationMs - elapsed;
        let weight = 1;

        if (focusOverride.blendMs > 0) {
            weight = Math.min(
                1,
                elapsed / focusOverride.blendMs,
                remaining / focusOverride.blendMs
            );
        }

        return {
            x: baseTarget.x + (focusOverride.x - baseTarget.x) * this._clamp(weight, 0, 1),
            y: baseTarget.y + (focusOverride.y - baseTarget.y) * this._clamp(weight, 0, 1)
        };
    }

    _queueBlinkRequest({ source = 'manual', executeAt = this._now(), doubleBlink = false } = {}) {
        const blinkQueue = this.animationState.blinkQueue;

        blinkQueue.queue.push({
            source,
            executeAt,
            doubleBlink: !!doubleBlink
        });
        blinkQueue.queue.sort((left, right) => left.executeAt - right.executeAt);
    }

    _updateBlinkQueue(now) {
        const blinkQueue = this.animationState.blinkQueue;
        const {
            blinkCloseMs,
            blinkHoldMs,
            blinkOpenMs
        } = this.animationConfig;

        if (!blinkQueue.naturalSeriesActive && now >= blinkQueue.nextNaturalBlinkAt) {
            blinkQueue.naturalSeriesActive = true;
            blinkQueue.nextNaturalBlinkAt = Number.POSITIVE_INFINITY;
            this._queueBlinkRequest({
                source: 'natural',
                executeAt: now,
                doubleBlink: Math.random() < this.animationConfig.blinkDoubleChance
            });
        }

        if (blinkQueue.phase === 'idle') {
            if (blinkQueue.pendingFollowUp && now >= blinkQueue.pendingFollowUp.executeAt) {
                blinkQueue.activeRequest = blinkQueue.pendingFollowUp;
                blinkQueue.pendingFollowUp = null;
                blinkQueue.phase = 'closing';
                blinkQueue.phaseStartedAt = now;
            } else if (blinkQueue.queue.length > 0 && blinkQueue.queue[0].executeAt <= now) {
                blinkQueue.activeRequest = blinkQueue.queue.shift();
                blinkQueue.phase = 'closing';
                blinkQueue.phaseStartedAt = now;
            }
        }

        if (blinkQueue.phase === 'closing') {
            const progress = (now - blinkQueue.phaseStartedAt) / blinkCloseMs;
            if (progress >= 1) {
                blinkQueue.phase = 'closed';
                blinkQueue.phaseStartedAt = now;
                return 0;
            }
            return 1 - this._ease(progress);
        }

        if (blinkQueue.phase === 'closed') {
            if (now - blinkQueue.phaseStartedAt >= blinkHoldMs) {
                blinkQueue.phase = 'opening';
                blinkQueue.phaseStartedAt = now;
            }
            return 0;
        }

        if (blinkQueue.phase === 'opening') {
            const progress = (now - blinkQueue.phaseStartedAt) / blinkOpenMs;
            if (progress >= 1) {
                this._finishBlink(now);
                return 1;
            }
            return this._ease(progress);
        }

        return 1;
    }

    _finishBlink(now) {
        const blinkQueue = this.animationState.blinkQueue;
        const completedRequest = blinkQueue.activeRequest;

        blinkQueue.phase = 'idle';
        blinkQueue.phaseStartedAt = now;
        blinkQueue.activeRequest = null;

        if (!completedRequest) {
            return;
        }

        if (completedRequest.doubleBlink) {
            blinkQueue.pendingFollowUp = {
                source: completedRequest.source === 'natural' ? 'followup-natural' : 'followup-manual',
                executeAt: now + this._randomBetween(
                    this.animationConfig.blinkDoubleGapMinMs,
                    this.animationConfig.blinkDoubleGapMaxMs
                ),
                doubleBlink: false
            };
            return;
        }

        if (completedRequest.source === 'natural' || completedRequest.source === 'followup-natural') {
            blinkQueue.naturalSeriesActive = false;
            blinkQueue.nextNaturalBlinkAt = now + this._randomBetween(
                this.animationConfig.blinkIntervalMinMs,
                this.animationConfig.blinkIntervalMaxMs
            );
        }
    }

    _getBreathMotion(now) {
        const elapsed = now - this.animationState.startedAt;
        const primaryPhase = (elapsed / this.animationConfig.breathCycleMs) * Math.PI * 2;
        const secondaryPhase = primaryPhase * 0.55 + Math.PI / 3;
        const driftPrimaryPhase = (elapsed / this.animationConfig.postureDriftPrimaryCycleMs) * Math.PI * 2;
        const driftSecondaryPhase = (elapsed / this.animationConfig.postureDriftSecondaryCycleMs) * Math.PI * 2 + Math.PI / 5;
        const primaryWave = Math.sin(primaryPhase);
        const secondaryWave = Math.sin(secondaryPhase);
        const driftPrimaryWave = Math.sin(driftPrimaryPhase);
        const driftSecondaryWave = Math.sin(driftSecondaryPhase);

        return {
            angleX: secondaryWave * this.animationConfig.breathAngleX + driftSecondaryWave * this.animationConfig.postureDriftAngleX,
            angleY: primaryWave * this.animationConfig.breathAngleY + driftPrimaryWave * this.animationConfig.postureDriftAngleY,
            angleZ: secondaryWave * this.animationConfig.breathAngleZ + driftSecondaryWave * this.animationConfig.postureDriftAngleZ,
            bodyAngleX: secondaryWave * this.animationConfig.breathBodyAngleX + driftSecondaryWave * this.animationConfig.postureDriftBodyAngleX,
            bodyAngleY: primaryWave * this.animationConfig.breathBodyAngleY + driftPrimaryWave * this.animationConfig.postureDriftBodyAngleY,
            bodyY: primaryWave * this.animationConfig.breathBodyY + driftSecondaryWave * this.animationConfig.postureDriftBodyY,
            breath: 0.5 + primaryWave * 0.42
        };
    }

    _getAttentionPose(now) {
        const attentionPose = this.animationState.attentionPose;

        if (!attentionPose.active) {
            return {
                angleY: 0,
                bodyAngleY: 0,
                bodyY: 0,
                eyeOpenBoost: 0
            };
        }

        const progress = (now - attentionPose.startedAt) / attentionPose.durationMs;
        if (progress >= 1) {
            attentionPose.active = false;
            return {
                angleY: 0,
                bodyAngleY: 0,
                bodyY: 0,
                eyeOpenBoost: 0
            };
        }

        const clampedProgress = this._clamp(progress, 0, 1);
        const risePortion = this._clamp(this.animationConfig.attentionRisePortion, 0.1, 0.9);
        let envelope;

        if (clampedProgress <= risePortion) {
            envelope = this._easeOut(clampedProgress / risePortion);
        } else {
            const settleProgress = (clampedProgress - risePortion) / (1 - risePortion);
            envelope = this._easeBackReturn(settleProgress);
        }

        const amplitude = attentionPose.intensity * envelope;
        return {
            angleY: this.animationConfig.attentionAngleY * amplitude,
            bodyAngleY: this.animationConfig.attentionBodyAngleY * amplitude,
            bodyY: this.animationConfig.attentionBodyY * amplitude,
            eyeOpenBoost: this.animationConfig.attentionEyeOpenBoost * amplitude
        };
    }

    _normalizePointerFocus(mouseX, mouseY, headPos = { x: 0, y: 0 }) {
        const dx = mouseX - headPos.x;
        const dy = -(mouseY - headPos.y);
        const scale = this.animationConfig.pointerViewScale;

        return {
            x: this._clamp(dx / scale, -1, 1),
            y: this._clamp(dy / scale, -1, 1)
        };
    }

    _sampleIdleWanderTarget() {
        return {
            x: this._randomBetween(
                -this.animationConfig.idleWanderRangeX,
                this.animationConfig.idleWanderRangeX
            ),
            y: this._randomBetween(
                -this.animationConfig.idleWanderRangeY,
                this.animationConfig.idleWanderRangeY
            )
        };
    }

    _createMotionPoint(x = 0, y = 0) {
        return { x, y, vx: 0, vy: 0 };
    }

    _resetMotionPoint(point, x = 0, y = 0) {
        point.x = x;
        point.y = y;
        point.vx = 0;
        point.vy = 0;
        return point;
    }

    _approachPoint(current, target, stiffness, damping, deltaMs) {
        if (!current) {
            return this._createMotionPoint(target.x, target.y);
        }

        if (!Number.isFinite(current.vx)) current.vx = 0;
        if (!Number.isFinite(current.vy)) current.vy = 0;

        const dt = Math.min(Math.max(deltaMs, 0), 48) / 1000;
        const safeStiffness = Math.max(0.001, stiffness);
        const safeDamping = Math.max(0, damping);

        const ax = (target.x - current.x) * safeStiffness - current.vx * safeDamping;
        const ay = (target.y - current.y) * safeStiffness - current.vy * safeDamping;

        current.vx += ax * dt;
        current.vy += ay * dt;
        current.x += current.vx * dt;
        current.y += current.vy * dt;

        return current;
    }

    _getDeltaMs(now) {
        const lastUpdatedAt = this.animationState.lastUpdatedAt || now;
        let deltaMs = now - lastUpdatedAt;

        if (!Number.isFinite(deltaMs) || deltaMs < 0) {
            deltaMs = 16;
        }

        this.animationState.lastUpdatedAt = now;
        return Math.min(deltaMs, 64);
    }

    _clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    _ease(value) {
        const clamped = this._clamp(value, 0, 1);
        return clamped * clamped * (3 - 2 * clamped);
    }

    _easeOut(value) {
        const clamped = this._clamp(value, 0, 1);
        return 1 - ((1 - clamped) ** 3);
    }

    _easeBackReturn(value) {
        const clamped = this._clamp(value, 0, 1);
        const eased = 1 - this._ease(clamped);
        return eased + Math.sin(clamped * Math.PI) * 0.04 * (1 - clamped);
    }

    _randomBetween(min, max) {
        return min + Math.random() * (max - min);
    }

    _now() {
        return typeof performance !== 'undefined' ? performance.now() : Date.now();
    }
}

module.exports = Live2dMotionController;
