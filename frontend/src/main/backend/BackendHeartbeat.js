class BackendHeartbeat {
    constructor(options = {}) {
        this.intervalMs = Number(options.intervalMs) > 0 ? Number(options.intervalMs) : 30000;
        this.task = typeof options.task === 'function' ? options.task : async () => {};
        this.onError = typeof options.onError === 'function' ? options.onError : null;

        this.timer = null;
        this.running = false;
    }

    start() {
        if (this.timer) {
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
    }

    isRunning() {
        return !!this.timer;
    }

    async tick() {
        if (!this.timer || this.running) {
            return;
        }

        this.running = true;

        try {
            await this.task();
        } catch (error) {
            if (typeof this.onError === 'function') {
                this.onError(error);
            }
        } finally {
            this.running = false;
        }
    }
}

module.exports = BackendHeartbeat;
