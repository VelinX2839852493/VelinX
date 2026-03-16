// 前后端连接桥梁
const {app, ipcMain} = require('electron');
const {spawn} = require('child_process');
const fs = require('fs');
const path = require('path');
const {URL} = require('url');

const {readConfig, getJavaBinaryPath, getPathMappingValue} = require('../utils/pathUtils');
const {HttpBackendClient, normalizeBackendConfig} = require('../backend/HttpBackendClient');
const HeartbeatManager = require('./HeartbeatManager');

const LOCAL_BACKEND_HOSTS = new Set(['127.0.0.1', 'localhost', '::1']);
const BACKEND_START_TIMEOUT_MS = 60000;
const HEALTH_POLL_INTERVAL_MS = 750;
const BACKEND_STOP_TIMEOUT_MS = 5000;

function normalizeSessionParams(params = {}, fallbackName = 'AI') {
    return {
        name: typeof params.name === 'string' && params.name.trim() !== '' ? params.name.trim() : fallbackName,
        role: typeof params.role === 'string' && params.role.trim() !== '' ? params.role.trim() : '1',
        hasWorld: !!params.hasWorld,
        profilePath: params.profilePath ? String(params.profilePath) : null,
        worldPath: params.worldPath ? String(params.worldPath) : null,
    };
}

function normalizeChatPayload(payload) {
    if (typeof payload === 'string') {
        return {
            message: payload,
            captureDesktop: false,
            tts: false
        };
    }

    if (!payload || typeof payload !== 'object') {
        return {
            message: '',
            captureDesktop: false,
            tts: false
        };
    }

    return {
        message: typeof payload.message === 'string' ? payload.message : String(payload.message || ''),
        captureDesktop: !!payload.captureDesktop,
        tts: !!payload.tts
    };
}

function sleep(ms) {
    return new Promise((resolve) => {
        setTimeout(resolve, ms);
    });
}

function parseBackendUrl(baseUrl) {
    try {
        return new URL(baseUrl);
    } catch (_error) {
        return null;
    }
}

function getBackendConfigKey(config) {
    return `${config.mode}:${config.baseUrl}`;
}

function isManagedLocalBackend(config) {
    if (!config || config.mode !== 'http') {
        return false;
    }

    const parsedUrl = parseBackendUrl(config.baseUrl);
    if (!parsedUrl || parsedUrl.protocol !== 'http:') {
        return false;
    }

    return LOCAL_BACKEND_HOSTS.has(parsedUrl.hostname.toLowerCase());
}

class BaseHandler {
    constructor() {
        this.currentAiName = 'AI';
        this.sessionActive = false;
        this.initialized = false;
        this.rootPath = app.isPackaged
            ? path.join(path.dirname(app.getPath('exe')), 'py_content')
            : path.join(__dirname, '../../../');

        this.backendProcess = null;
        this.backendProcessConfigKey = null;
        this.backendStartPromise = null;
        this.backendStartConfigKey = null;
        this.backendLastError = null;

        this.heartbeat = new HeartbeatManager({
            enabled: false,
            intervalMs: 30000,
            maxFailures: 3,
            task: async () => {
                if (!this.sessionActive) {
                    return {ok: true};
                }

                const result = await this.health();
                return {
                    ok: !!result.success,
                    error: result.error || 'Heartbeat health check failed.',
                };
            },
            onError: (message, count) => {
                console.warn(`[Heartbeat] failed ${count} time(s): ${message}`);
            },
            onOffline: (message) => {
                if (!this.sessionActive) {
                    return;
                }

                this.sessionActive = false;
                this.sendErrorToChat(`心跳连续失败，当前会话已断开：${message}`);
            },
        });


    }

    // ==========================================
    // 1. 公共入口 (入口点)
    // ==========================================
    init() {
        if (this.initialized) {
            return;
        }

        this.initialized = true;

        ipcMain.removeAllListeners('chat-message-to-backend');
        ipcMain.on('chat-message-to-backend', async (event, payload) => {
            const request = normalizeChatPayload(payload);

            //发送
            const result = await this.sendChat(
                request.message,
                {
                    captureDesktop: request.captureDesktop,
                    tts: request.tts
                }
            );

            event.sender.send(
                'reply-from-backend',
                result.success
                    ? {status: 'success', content: result.content}
                    : {status: 'error', message: result.error}
            );
        });
    }

    /**
     * 启动 AI 角色会话
     * @param {Object} params - 前端传来的参数（如：{name: "林黛玉", role: "1"}）
     * @returns {Promise<Object>} 返回启动结果，包含成功标志或错误信息
     */
    async start(params = {}) {
        // 1. 【参数标准化】
        // 将前端传入的原始参数进行清洗和格式化。
        // 如果参数缺失，会使用 this.currentAiName 或预设值填充。
        const payload = normalizeSessionParams(params, this.currentAiName);

        // 2. 【确保后端环境就绪】
        // 调用 ensureBackendRunning 检查 Java 后端是否在运行。
        // 如果没运行，会尝试通过 spawn 启动 JAR 包，并轮询健康检查接口直到其响应。
        const backendReady = await this.ensureBackendRunning();

        // 如果 Java 后端启动失败（如：JAR包损坏、端口占用、启动超时等）
        if (!backendReady.success) {
            this.sessionActive = false; // 标记会话未激活
            return {
                success: false,
                error: backendReady.error || 'Failed to start the local Java backend.',
                code: backendReady.code || 'BACKEND_START_FAILED',
            };
        }

        // 3. 【业务逻辑启动】
        // 后端进程已经在线了，现在通过 HTTP 请求通知 Java 后端：
        // “请按照这些参数（payload）加载 AI 模型和角色配置。”
        try {
            const result = await this.createClient().startSession(payload);

            // 如果 Java 接口返回失败（如：角色配置文件不存在、API Key 无效等）
            if (!result.success) {
                this.sessionActive = false;
                return {
                    success: false,
                    error: result.error || 'Failed to start the backend session.',
                    code: result.code || 'START_FAILED',
                };
            }

            // 4. 【确定最终的 Bot 名称】
            // 优先使用 Java 后端返回的真实名字（可能从配置文件中读取的），
            // 如果后端没返回，则使用之前准备好的 payload.name。
            const botName = result.data && result.data.botName
                ? String(result.data.botName)
                : payload.name;

            // 5. 【更新本地管理状态】
            this.currentAiName = botName; // 更新当前活跃的 AI 名字
            this.sessionActive = true;    // 全局标记：会话已激活，可以开始发消息了


            // 6. 【返回结果给前端】
            return {
                success: true,
                data: {
                    botName,
                },
            };

        } catch (error) {
            // 7. 【异常捕获】
            // 处理网络错误、代码崩溃等意外情况
            this.sessionActive = false;
            return {
                success: false,
                error: error.message,
                code: 'START_FAILED',
            };
        }
    }

    /**
     * 彻底停止会话并关闭 Java 后端进程
     */
    async stop() {

        // 1. 立即拦截所有新的业务请求
        // 防止在关闭过程中还有新的消息发往后端导致报错
        this.sessionActive = false;

        // 2. 尝试优雅地通知后端结束会话
        // 发送 HTTP 请求给 Java，让它处理收尾工作（如保存数据）
        try {
            await this.stopSession();
        } catch (e) {
            // 即便通知失败（比如网络断了），也继续执行后面的强杀流程
            console.error("通知后端停止会话失败:", e);
        }

        // 3. 杀掉 Java 外部进程
        // 调用操作系统指令，确保 java.exe 进程从任务管理器中消失
        await this.stopManagedBackendProcess();

        // 4. 反馈结果
        return {success: true};
    }

    //发送chat
    async sendChat(message, options = {}) {
        if (!this.sessionActive) {
            return {
                success: false,
                error: 'No active session. Start a role before sending chat messages.',
                code: 'SESSION_NOT_STARTED',
            };
        }

        try {
            //拉起服务端
            const result = await this.createClient().sendChat(
                message,
                {
                    captureDesktop: !!options.captureDesktop,
                    tts: !!options.tts
                }
            );
            if (!result.success) {
                return {
                    success: false,
                    error: result.error || 'Failed to send the chat message.',
                    code: result.code || 'CHAT_FAILED',
                };
            }

            return {
                success: true,
                content: result.data && result.data.content ? String(result.data.content) : '',
            };
        } catch (error) {
            return {
                success: false,
                error: error.message,
                code: 'CHAT_FAILED',
            };
        }
    }

    // ==========================================
    // 2. 状态查询
    // ==========================================
    isRunning() {
        return this.sessionActive;
    }

    getBotName() {
        return this.currentAiName;
    }

    // ==========================心跳机制======================


    // ==========================================
    // 3. 进程核心管理 (你关心的拉起逻辑)
    // ==========================================

    /**
     * 确保后端服务正在运行
     * @param {Object} overrideConfig - 可选的覆盖配置
     * @returns {Promise<Object>} 返回运行状态
     */
    async ensureBackendRunning(overrideConfig = null) {
        // 1. 【获取最终配置】
        // 合并应用默认配置和传入的覆盖配置（如 URL、端口、模式等）
        const backendConfig = this.getBackendConfig(overrideConfig);

        // 2. 【判断是否需要管理本地进程】
        // 如果配置不是 "localhost/127.0.0.1" 或者模式不是 "http"，说明是远程后端。
        // 远程后端不需要 Electron 去启动进程，所以直接返回成功。
        if (!isManagedLocalBackend(backendConfig)) {
            return {
                success: true,
                managed: false,
            };
        }

        // 3. 【初步健康检查】
        // 尝试向后端发一个轻量请求，看看它是不是已经在运行了（比如上次没关掉）。
        const healthResult = await this.requestHealth(backendConfig);
        if (healthResult.success) {
            return {
                success: true,
                managed: false,
                reused: true, // 标记：这是复用了现有的进程
            };
        }

        // --- 以下是启动逻辑，涉及并发控制 ---

        // 4. 【并发锁检查：防止重复启动】
        // 检查是否已经有一个启动任务正在进行中 (backendStartPromise)。
        // 如果有，并且配置相同（configKey），则直接返回现有的 Promise，大家排队等这一个结果。
        const configKey = getBackendConfigKey(backendConfig);
        if (this.backendStartPromise && this.backendStartConfigKey === configKey) {
            return this.backendStartPromise;
        }

        // 5. 【清理旧任务】
        // 如果之前有一个启动任务，但配置变了，先等待之前的任务结束（无论成功失败）。
        if (this.backendStartPromise) {
            try {
                await this.backendStartPromise;
            } catch (_error) {
                // 忽略旧错误，接下来的新启动尝试会产生新的错误信息。
            }
        }

        // 6. 【正式执行启动】
        // 记录当前启动的配置 Key，并创建启动进程的 Promise。
        this.backendStartConfigKey = configKey;
        const startPromise = this.startManagedBackendProcess(backendConfig);
        this.backendStartPromise = startPromise;

        try {
            // 等待 Java 启动完毕（包括 spawn 进程和 waitForBackendReady 轮询成功）
            return await startPromise;
        } finally {
            // 7. 【清理状态：无论成功还是失败】
            // 只要当前的 startPromise 任务结束了，就清空单例锁。
            // 这样下次调用 start 时，可以重新触发 ensureBackendRunning。
            if (this.backendStartPromise === startPromise) {
                this.backendStartPromise = null;
                this.backendStartConfigKey = null;
            }
        }
    }

    /**
     * 拉起程序
     * @param backendConfig
     * @returns {Promise<{success: boolean}|{success: boolean, error, code: string}|{success: boolean, error, code: string}|{success: boolean, error: string, code: string}>}
     */
    async startManagedBackendProcess(backendConfig) {
        const configKey = getBackendConfigKey(backendConfig);

        if (this.isManagedBackendProcessAlive()) {
            if (this.backendProcessConfigKey !== configKey) {
                await this.stopManagedBackendProcess();
            } else {
                return this.waitForBackendReady(backendConfig);
            }
        }

        //得到java启动路径
        const processConfig = getJavaBinaryPath({baseUrl: backendConfig.baseUrl});

        if (!fs.existsSync(processConfig.jarPath)) {
            return {
                success: false,
                error: `Local Java backend jar not found: ${processConfig.jarPath}`,
                code: 'JAR_NOT_FOUND',
            };
        }

        this.backendLastError = null;

        // 【关键点 B】：这里是真正的“拉起”动作
        // spawn 是 Node.js 的核心方法，用来启动一个外部进程（即 Java 进程）
        const childProcess = spawn(processConfig.command, processConfig.args, {
            ...processConfig.options,
            stdio: ['ignore', 'pipe', 'pipe'],// 建立管道，为了后面获取日志
        });

        this.backendProcess = childProcess;
        this.backendProcessConfigKey = configKey;
        this.attachBackendProcessListeners(childProcess);

        const readyResult = await this.waitForBackendReady(backendConfig);
        if (!readyResult.success) {
            await this.stopManagedBackendProcess();
        }

        return readyResult;
    }

    //发送 HTTP 请求给 Java，让它处理收尾工作（如保存数据）
    async stopSession(overrideConfig = null) {
        try {
            return await this.createClient(overrideConfig).stopSession();
        } catch (error) {
            return {
                success: false,
                error: error.message,
                code: 'STOP_FAILED',
            };
        }
    }

    //调用操作系统指令，确保 java.exe 进程从任务管理器中消失
    /**
     * 停止并清理受管理的后端进程（Java 进程）
     * 采用“先礼后兵”策略：先尝试优雅关闭，超时则强制杀死。
     */
    async stopManagedBackendProcess() {
        // 1. 获取当前运行中的进程引用
        const childProcess = this.backendProcess;

        // 如果当前没有进程记录，直接返回
        if (!childProcess) {
            return;
        }

        // 2. 立即清除类内部对该进程的引用
        // 这样做是为了防止在关闭过程中，其他逻辑（如启动逻辑）误认为进程仍然可用
        this.backendProcess = null;
        this.backendProcessConfigKey = null;

        // 3. 将异步的进程关闭过程封装在 Promise 中，以便使用 await 等待其彻底结束
        await new Promise((resolve) => {
            let settled = false; // 标记位：确保 resolve 只被执行一次

            // 内部完成函数：清理状态并结束 Promise
            const finish = () => {
                if (settled) {
                    return;
                }
                settled = true;
                resolve();
            };

            // 4. 监听操作系统层面的进程关闭事件
            // 一旦进程通过任何方式关闭，执行 finish
            childProcess.once('close', finish);

            // 5. 状态检查：如果进程已经由于某些原因退出了（或已被杀掉），直接结束
            if (childProcess.exitCode !== null || childProcess.killed) {
                finish();
                return;
            }

            // 6. 执行“优雅关闭”（先礼）
            try {
                // 默认发送 SIGTERM 信号。
                // 这会通知 Java 程序执行关闭钩子（Shutdown Hook），如保存数据、释放内存等。
                childProcess.kill();
            } catch (error) {
                // 如果发送关闭信号失败，记录错误并强制结束 Promise 流程
                this.backendLastError = `Failed to stop the local Java backend: ${error.message}`;
                finish();
                return;
            }

            // 7. 设置超时保护机制（后兵）
            // 理由：Java 进程可能因为死锁或长时间写文件而无视 SIGTERM 信号
            setTimeout(() => {
                // 如果在超时时间内进程已经正常关闭（settled 为 true），则什么都不做
                if (settled) {
                    return;
                }

                try {
                    // 如果时间到了还没关掉，发送 SIGKILL 信号（强制杀掉进程）
                    // 这相当于在任务管理器中直接“结束任务”，不会给 Java 任何处理余地
                    childProcess.kill('SIGKILL');
                } catch (_error) {
                    // 忽略强杀时的错误（通常是因为进程恰好在这一刻消失了）
                }

                // 强杀后，无论结果如何都必须结束 Promise，防止主程序永久挂起
                finish();
            }, BACKEND_STOP_TIMEOUT_MS); // 默认为 5000 毫秒（5秒）
        });
    }

    async waitForBackendReady(backendConfig) {
        const deadline = Date.now() + Math.max(BACKEND_START_TIMEOUT_MS, backendConfig.timeoutMs || 0);

        while (Date.now() < deadline) {
            const healthResult = await this.requestHealth(backendConfig);
            if (healthResult.success) {
                return {
                    success: true,
                };
            }

            if (!this.isManagedBackendProcessAlive()) {
                return {
                    success: false,
                    error: this.backendLastError || 'The local Java backend exited before it became ready.',
                    code: 'BACKEND_START_FAILED',
                };
            }

            await sleep(HEALTH_POLL_INTERVAL_MS);
        }

        return {
            success: false,
            error: this.backendLastError || `Timed out waiting for the local Java backend at ${backendConfig.baseUrl}.`,
            code: 'BACKEND_START_TIMEOUT',
        };
    }

    // ==========================================
    // 4. 辅助通讯与监控
    // ==========================================
    attachBackendProcessListeners(childProcess) {
        if (childProcess.stdout) {
            childProcess.stdout.on('data', (chunk) => {
                this.logBackendOutput('stdout', chunk);
            });
        }

        if (childProcess.stderr) {
            childProcess.stderr.on('data', (chunk) => {
                const message = Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk || '');
                if (message.trim() !== '') {
                    this.backendLastError = message.trim();
                }
                this.logBackendOutput('stderr', chunk);
            });
        }

        childProcess.on('error', (error) => {
            this.backendLastError = `Unable to start the local Java backend: ${error.message}`;
            console.error(`[JavaBackend] ${this.backendLastError}`);
        });

        childProcess.on('close', (code, signal) => {
            const wasManagedProcess = this.backendProcess === childProcess;
            if (wasManagedProcess) {
                this.backendProcess = null;
                this.backendProcessConfigKey = null;
            }

            const closeReason = signal ? `signal ${signal}` : `exit code ${code}`;
            console.log(`[JavaBackend] Process closed (${closeReason}).`);

            if (wasManagedProcess && code !== 0 && this.sessionActive) {
                this.sessionActive = false;
                this.sendErrorToChat('The local Java backend stopped unexpectedly.');
            }
        });
    }

    logBackendOutput(stream, chunk) {
        const logger = stream === 'stderr' ? console.error : console.log;
        const message = Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk || '');

        message.split(/\r?\n/).forEach((line) => {
            const trimmed = line.trim();
            if (trimmed !== '') {
                logger(`[JavaBackend:${stream}] ${trimmed}`);
            }
        });
    }

    async requestHealth(backendConfig) {
        try {
            return await new HttpBackendClient(backendConfig).health();
        } catch (error) {
            return {
                success: false,
                error: error.message,
                code: 'CONFIG_ERROR',
            };
        }
    }

    // ==========================================
    // 5. 配置与工具 (最不常看的)
    // ==========================================

    getBackendConfig(overrideConfig = null) {
        const appConfig = readConfig();
        const savedConfig = appConfig && appConfig.backend ? appConfig.backend : {};
        return normalizeBackendConfig({
            ...savedConfig,
            ...(overrideConfig || {}),
        });
    }

    createClient(overrideConfig = null) {
        // 1. 【确定配置参数】
        // 调用 getBackendConfig 获取最终的后端地址、端口、超时时间等配置。
        // 如果传入了 overrideConfig，则会覆盖默认配置。
        const backendConfig = this.getBackendConfig(overrideConfig);

        // 2. 【协议合法性检查】
        // 检查模式是不是 'http'。
        // 因为目前的 HttpBackendClient 类只支持 HTTP 协议，
        // 如果配置里写的是 'websocket' 或其他，程序会报错。
        if (backendConfig.mode !== 'http') {
            throw new Error(`Unsupported backend mode: ${backendConfig.mode}`);
        }

        // 3. 【实例化并返回通讯客户端】
        // 创建一个新的 HttpBackendClient 实例。
        // 这个实例就像是一个封装好的“电话机”，里面存好了 Java 后端的 IP 和端口。
        // 以后你只需要调用 client.sendChat()，它就会自动帮你发 HTTP 请求。
        return new HttpBackendClient(backendConfig);
    }

    async health(overrideConfig = null) {
        const backendConfig = this.getBackendConfig(overrideConfig);
        return this.requestHealth(backendConfig);
    }

    isManagedBackendProcessAlive() {
        return !!this.backendProcess && this.backendProcess.exitCode === null && !this.backendProcess.killed;
    }

    getPaths(name = null) {
        const targetName = name || this.currentAiName;
        const dataDir = getPathMappingValue('DATA_DIR') || path.join(this.rootPath, 'data');
        const aiDir = path.join(dataDir, targetName);

        return {
            root: getPathMappingValue('ROOT_DIR') || this.rootPath,
            dataDir,
            aiDir,
            role_all_configs: getPathMappingValue('CONFIG_FILE_PATH') || path.join(dataDir, 'role_all_configs.json'),
            openai_config: getPathMappingValue('OPENAI_CONFIG_PATH') || path.join(dataDir, 'openai_config.json'),
        };
    }

    readPath(configType) {
        const paths = this.getPaths();

        if (configType === 'role_all_configs') return paths.role_all_configs;
        if (configType === 'openai_config') return paths.openai_config;
        if (configType === 'ai-private') return path.join(paths.aiDir, `ai_profile_${this.currentAiName}.json`);
        if (configType === 'dataPath') return paths.dataDir;
        if (configType === 'aiPath') return paths.aiDir;

        return null;
    }

    sendErrorToChat(message) {
        const ChatWindow = require('../windows/chat-ui');
        const chatWindow = ChatWindow.get();

        if (chatWindow && !chatWindow.isDestroyed()) {
            chatWindow.webContents.send('reply-from-backend', {
                status: 'error',
                message,
            });
        }
    }

    destroy() {
        this.sessionActive = false;
        void this.stopManagedBackendProcess();
    }

    async shutdownForAppQuit() {
        const backendConfig = this.getBackendConfig();
        const shouldNotifyBackend = isManagedLocalBackend(backendConfig);

        this.sessionActive = false;

        if (shouldNotifyBackend) {
            try {
                await this.stopSession(backendConfig);
            } catch (error) {
                console.error('Failed to notify the backend before app quit:', error);
            }
        }

        await this.stopManagedBackendProcess();
    }
}

module.exports = new BaseHandler();
