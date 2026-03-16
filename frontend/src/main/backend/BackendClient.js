class BackendClient {
    async health() {
        throw new Error('health() must be implemented by a backend client.');
    }

    async startSession(_params) {
        throw new Error('startSession() must be implemented by a backend client.');
    }

    async sendChat(_text) {
        throw new Error('sendChat() must be implemented by a backend client.');
    }
}

module.exports = BackendClient;
