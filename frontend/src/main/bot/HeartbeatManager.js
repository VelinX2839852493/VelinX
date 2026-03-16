class HeartbeatManager {
    constructor(options = {}) {
        this.enabled = !!options.enabled;
        this.intervalMs = Number(options.intervalMs) > 0 ? Number(options.intervalMs) : 30000;
        this.maxFailures = Number(options.maxFailures) > 0 ? Number(options.maxFailures) : 3;
        this.task = typeof options.task === 'function' ? options.task : async () => ({ ok: true });
        this.onError = typeof options.onError === 'function' ? options.onError : null;
        this.onOffline = typeof options.onOffline === 'function' ? options.onOffline : null;

        this.timer = null;
        this.running = false;
        this.consecutiveFailures = 0;
    }

    setEnabled(enabled) {
        this.enabled = !!enabled;
        if (!this.enabled) {
            this.stop();
        }
    }

    setIntervalMs(intervalMs) {
        const nextValue = Number(intervalMs);
        if (Number.isFinite(nextValue) && nextValue > 0) {
            this.intervalMs = nextValue;
        }
    }

    setMaxFailures(maxFailures) {
        const nextValue = Number(maxFailures);
        if (Number.isFinite(nextValue) && nextValue > 0) {
            this.maxFailures = nextValue;
        }
    }

    start() {
        if (!this.enabled || this.timer) {
            return;
        }

        this.timer = setInterval(() => {
            void this.tick();
        }, this.intervalMs);

        void this.tick();
    }

    stop() {
        if (this.timer) {
            clearInterval(this.timer);
            this.timer = null;
        }

        this.running = false;
        this.consecutiveFailures = 0;
    }

    restart() {
        this.stop();
        this.start();
    }

    isEnabled() {
        return this.enabled;
    }

    getState() {
        return {
            enabled: this.enabled,
            intervalMs: this.intervalMs,
            maxFailures: this.maxFailures,
            running: !!this.timer,
            consecutiveFailures: this.consecutiveFailures,
        };
    }

    async tick() {
        if (!this.enabled || this.running) {
            return;
        }

        this.running = true;

        try {
            const result = await this.task();

            if (result && result.ok !== false) {
                this.consecutiveFailures = 0;
            } else {
                this.handleFailure(result && result.error ? result.error : 'Heartbeat failed.');
            }
        } catch (error) {
            this.handleFailure(error && error.message ? error.message : String(error));
        } finally {
            this.running = false;
        }
    }

    handleFailure(message) {
        this.consecutiveFailures += 1;

        if (typeof this.onError === 'function') {
            this.onError(message, this.consecutiveFailures);
        }

        if (this.consecutiveFailures >= this.maxFailures) {
            this.stop();

            if (typeof this.onOffline === 'function') {
                this.onOffline(message);
            }
        }
    }
}

module.exports = HeartbeatManager;
