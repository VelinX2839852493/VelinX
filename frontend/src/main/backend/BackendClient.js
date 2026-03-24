const BackendHeartbeat = require('./BackendHeartbeat');

const HEARTBEAT_MESSAGE = '__heartbeat__';
const HEARTBEAT_INTERVAL_MS = 30000;

class BackendClient {
    constructor() {
        this.heartbeat = new BackendHeartbeat({
            intervalMs: HEARTBEAT_INTERVAL_MS,
            task: async () => {
                const result = await this.sendHeartbeat(HEARTBEAT_MESSAGE);
                if (!result || result.success === false) {
                    throw new Error(result && result.error ? result.error : 'Heartbeat request failed.');
                }
            },
            onError: (error) => {
                const message = error && error.message ? error.message : String(error);
                console.warn(`[BackendHeartbeat] ${message}`);
            },
        });
    }

    async health() {
        throw new Error('health() must be implemented by a backend client.');
    }

    async startSession(_params) {
        throw new Error('startSession() must be implemented by a backend client.');
    }

    async sendChat(_text) {
        throw new Error('sendChat() must be implemented by a backend client.');
    }

    async sendHeartbeat(_text) {
        throw new Error('sendHeartbeat() must be implemented by a backend client.');
    }

    async sendDeveloperStartupTest(_payload) {
        throw new Error('sendDeveloperStartupTest() must be implemented by a backend client.');
    }

    startHeartbeat() {
        this.heartbeat.start();
    }

    stopHeartbeat() {
        this.heartbeat.stop();
    }

    isHeartbeatRunning() {
        return this.heartbeat.isRunning();
    }
}

module.exports = BackendClient;
