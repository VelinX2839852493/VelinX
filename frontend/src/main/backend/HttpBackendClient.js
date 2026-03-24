const http = require('http');
const https = require('https');
const {URL} = require('url');

const BackendClient = require('./BackendClient');
const defaultConfig = require('../../shared/default-config');

const DEFAULT_BACKEND_CONFIG = {
    mode: 'http',
    baseUrl: 'http://127.0.0.1:38080',
    timeoutMs: 180000,
    ...(defaultConfig.backend || {}),
};

function normalizeTimeoutMs(value) {
    const timeout = Number.parseInt(value, 10);
    if (!Number.isFinite(timeout) || timeout <= 0) {
        return DEFAULT_BACKEND_CONFIG.timeoutMs;
    }

    return timeout;
}

function normalizeMode(value) {
    if (typeof value !== 'string' || value.trim() === '') {
        return DEFAULT_BACKEND_CONFIG.mode;
    }

    return value.trim().toLowerCase();
}

function normalizeBaseUrl(value) {
    const rawValue = typeof value === 'string' && value.trim() !== ''
        ? value.trim()
        : DEFAULT_BACKEND_CONFIG.baseUrl;

    let parsed;
    try {
        parsed = new URL(rawValue);
    } catch (_error) {
        throw new Error('Base URL must be a valid http or https URL.');
    }

    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        throw new Error('Base URL must use the http or https protocol.');
    }

    return parsed.toString().replace(/\/$/, '');
}

function normalizeBackendConfig(config = {}) {
    return {
        mode: normalizeMode(config.mode),
        baseUrl: normalizeBaseUrl(config.baseUrl),
        timeoutMs: normalizeTimeoutMs(config.timeoutMs),
    };
}

function normalizeSessionPayload(params = {}) {
    return {
        name: typeof params.name === 'string' && params.name.trim() !== '' ? params.name.trim() : 'AI',
        role: typeof params.role === 'string' && params.role.trim() !== '' ? params.role.trim() : '1',
        hasWorld: !!params.hasWorld,
        profilePath: params.profilePath ? String(params.profilePath) : null,
        worldPath: params.worldPath ? String(params.worldPath) : null,
    };
}

function parseJson(rawBody) {
    if (!rawBody || rawBody.trim() === '') {
        return null;
    }

    try {
        return JSON.parse(rawBody);
    } catch (_error) {
        return null;
    }
}

function getBackendError(parsedBody, statusCode, rawBody) {
    if (parsedBody && typeof parsedBody === 'object' && parsedBody.ok === false) {
        const errorMessage = parsedBody.error && parsedBody.error.message
            ? String(parsedBody.error.message)
            : `Backend request failed with status ${statusCode}.`;

        return {
            code: parsedBody.error && parsedBody.error.code ? String(parsedBody.error.code) : 'BACKEND_ERROR',
            message: errorMessage,
        };
    }

    if (statusCode < 200 || statusCode >= 300) {
        const compactBody = rawBody && rawBody.trim() !== ''
            ? ` ${rawBody.trim().slice(0, 180)}`
            : '';

        return {
            code: 'HTTP_ERROR',
            message: `Backend request failed with status ${statusCode}.${compactBody}`.trim(),
        };
    }

    return {
        code: 'INVALID_RESPONSE',
        message: 'Backend response format is invalid.',
    };
}

function buildTargetUrl(baseUrl, pathName) {
    const normalizedBase = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
    const normalizedPath = String(pathName || '').replace(/^\/+/, '');
    return new URL(normalizedPath, normalizedBase);
}

class HttpBackendClient extends BackendClient {
    constructor(config = {}) {
        super();
        this.config = normalizeBackendConfig(config);
    }

    async health() {
        return this._request('GET', 'health');
    }

    async startSession(params = {}) {
        return this._request('POST', 'session/start', normalizeSessionPayload(params));
    }

    async stopSession() {
        return this._request('POST', 'session/stop');
    }

    async sendChat(message, options = {}) {
        const captureDesktop = !!(options && options.captureDesktop);
        const tts = !!(options && options.tts);

        return this._request('POST', 'chat', {
            message: typeof message === 'string' ? message : String(message || ''),
            ...(captureDesktop ? {captureDesktop: true} : {}),
            ...(tts ? {tts: true} : {}),
        });
    }

    //测试信号请求
    async sendHeartbeat(message) {
        return this._request('POST', 'heartbeat', {
            message: typeof message === 'string' ? message : String(message || ''),
        });
    }

    async sendDeveloperStartupTest(payload = {}) {
        return this._request('POST', 'developer/startup-test', {
            message: typeof payload.message === 'string' ? payload.message : String(payload.message || ''),
        });
    }

    _request(method, pathName, payload) {
        const targetUrl = buildTargetUrl(this.config.baseUrl, pathName);
        const transport = targetUrl.protocol === 'https:' ? https : http;
        const rawBody = payload === undefined ? null : JSON.stringify(payload);

        return new Promise((resolve) => {
            let settled = false;
            let timedOut = false;

            const request = transport.request(
                {
                    method,
                    hostname: targetUrl.hostname,
                    port: targetUrl.port || undefined,
                    path: `${targetUrl.pathname}${targetUrl.search}`,
                    headers: {
                        Accept: 'application/json',
                        ...(rawBody
                            ? {
                                'Content-Type': 'application/json',
                                'Content-Length': Buffer.byteLength(rawBody),
                            }
                            : {}),
                    },
                },
                (response) => {
                    let responseBody = '';
                    response.setEncoding('utf8');

                    response.on('data', (chunk) => {
                        responseBody += chunk;
                    });

                    response.on('end', () => {
                        if (settled) {
                            return;
                        }

                        settled = true;
                        const parsedBody = parseJson(responseBody);

                        if (parsedBody && typeof parsedBody === 'object' && parsedBody.ok === true) {
                            resolve({
                                success: true,
                                data: parsedBody.data || {},
                            });
                            return;
                        }

                        const backendError = getBackendError(
                            parsedBody,
                            response.statusCode || 500,
                            responseBody
                        );

                        resolve({
                            success: false,
                            error: backendError.message,
                            code: backendError.code,
                            statusCode: response.statusCode || 500,
                        });
                    });
                }
            );

            request.setTimeout(this.config.timeoutMs, () => {
                timedOut = true;
                request.destroy(new Error(`Request timed out after ${this.config.timeoutMs}ms.`));
            });

            request.on('error', (error) => {
                if (settled) {
                    return;
                }

                settled = true;
                resolve({
                    success: false,
                    error: timedOut
                        ? error.message
                        : `Unable to reach backend: ${error.message}`,
                    code: timedOut ? 'TIMEOUT' : 'REQUEST_ERROR',
                });
            });

            if (rawBody) {
                request.write(rawBody);
            }

            request.end();
        });
    }
}

module.exports = {
    HttpBackendClient,
    normalizeBackendConfig,
};
