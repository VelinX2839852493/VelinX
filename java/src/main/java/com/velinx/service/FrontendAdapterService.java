package com.velinx.service;

import com.velinx.core.TTS.ApiTtsClient;
import com.velinx.core.TTS.TtsClient;
import com.velinx.core.chat.runtime.BotResponseListener;
import com.velinx.core.chat.runtime.ChatBot;
import com.velinx.core.platform.config.BotWorkspaceResolver;
import com.velinx.core.platform.config.ConfigManager;
import com.velinx.core.platform.config.PathConfig;
import com.velinx.dto.*; // 确保你建了这些 DTO
import com.velinx.dto.frontendadapter.SessionStartRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * velinx 前端适配器业务逻辑层 (Service)
 * 职责：专心处理 AI 对话的核心业务，管理底层大模型（ChatBot）的生命周期。
 * 特点：完全不碰 HTTP、JSON 转换，只处理纯 Java 对象。具有极高的复用性和线程安全性。
 */
@Service
public class FrontendAdapterService {

    private static final Logger logger = LoggerFactory.getLogger(FrontendAdapterService.class);
    private static final String SERVICE_NAME = "velinx Spring Boot Frontend Adapter";
    private static final String DEFAULT_VERSION = "1.0.0";

    // 【全局互斥锁】因为后端的接口会被多个前端请求同时调用（多线程），
    // 为了防止“正在启动会话时突然进来一条聊天消息”导致程序崩溃，必须加锁保证同一时刻只能做一件事。
    private final ReentrantLock requestLock = new ReentrantLock();

    // 【当前活跃的会话】使用 volatile 关键字：
    // 确保当一个线程（比如启动接口）修改了这个变量后，其他线程（比如聊天接口）能立刻看到最新的值，避免读到脏数据。
    private volatile FrontendSession currentSession;

    // 【配置注入】从 application.properties/yml 文件中读取工作空间目录
    @Value("${WorkBot.workspaceDir:C:/Users/28398/Desktop/text}")
    private String configuredWorkspaceDir;

    // 【超时时间】等待大模型回复的最大时间（默认 180 秒）。防止前端发个请求后，后端无限期挂起导致内存溢出。
    @Value("${velinx.frontend-adapter.reply-timeout-ms:180000}")
    private long replyTimeoutMs;

    /**
     * 获取服务健康状态信息
     */
    public Map<String, String> health() {
        return Map.of(
                "service", SERVICE_NAME,
                "version", resolveVersion()
        );
    }

    /**
     * 启动会话：负责拉起底层的大模型引擎
     *
     * @return 返回成功拉起的机器人名称
     * @throws Exception 如果创建失败，直接抛出异常，由外部 Controller 去捕获并变成 HTTP 500
     */
    public String startSession(SessionStartRequest request) throws Exception {
        requestLock.lock(); // 加锁，保证创建过程绝对排他
        try {
            // 参数校验，防止传进来 null 导致空指针异常
            SessionStartRequest normalized = normalize(request);

            // 【清理旧会话】如果当前已经有一个正在运行的模型，必须先关掉它，释放显存/内存
            FrontendSession previousSession = currentSession;
            currentSession = null;
            if (previousSession != null) {
                previousSession.close();
            }

            // 【创建新会话】解析路径并 new 一个新的模型包装对象
            String workspaceDir = resolveWorkspaceDir(normalized);
            FrontendSession nextSession = new FrontendSession(normalized, workspaceDir);

            // 赋值给全局变量，表示当前系统正在服务这个 Session
            currentSession = nextSession;

            logger.info("会话已启动: name={}, role={}", normalized.name(), normalized.role());

            return nextSession.botName(); // 只返回纯数据给 Controller
        } finally {
            requestLock.unlock(); // 必须在 finally 里解锁，防止报错后发生死锁
        }
    }

    /**
     * 停止会话：手动销毁当前模型实例，并最终退出程序
     */
    public void stopSession() {
        requestLock.lock();
        try {
            FrontendSession session = currentSession;
            currentSession = null;
            if (session != null) {
                session.close();
            }
            // 为什么要开新线程去 System.exit？
            // 如果直接在当前线程 exit，会立刻终止程序，导致外面的 Controller 还没来得及把 HTTP 200 返回给前端。
            // 开新线程睡 200 毫秒，是为了给 Spring 留点时间把“已停止”的响应发给前端，然后安静自尽。
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                logger.info("会话关闭成功");
                System.exit(0);
            }).start();
        } catch (Exception e) {
            logger.error("关闭会话失败", e);
            throw new RuntimeException("STOP_FAILED: " + e.getMessage());
        } finally {
            requestLock.unlock();
        }
    }

    /**
     * 聊天核心逻辑：把文字发给大模型，并等待它的回复
     *
     * @return ChatOutcome (自定义的结果对象，包含成功/失败状态和内容)
     */
    public ChatOutcome chat(ChatRequest request) {
        requestLock.lock();
        try {
            String text = request == null || request.message() == null ? "" : request.message().trim();
            boolean captureDesktop = request != null && request.captureDesktop();
            boolean ttsOpen = request != null && request.tts();


            logger.info("信息是！！！ {}", request);

            if (text.isEmpty()) {
                // 不抛出异常，而是返回带有自定义错误码的结果对象，Controller 看到后会转成 HTTP 400
                return ChatOutcome.failure("EMPTY_TEXT", "消息不能为空");
            }

            FrontendSession session = currentSession;
            if (session == null) {
                return ChatOutcome.failure("SESSION_NOT_STARTED", "请先启动会话");
            }

            // 【核心】调用内部类的 sendChat，这一步会阻塞当前线程，直到大模型给出结果
            return session.sendChat(text, captureDesktop, ttsOpen,replyTimeoutMs);

        } catch (TimeoutException e) {
            logger.warn("聊天请求超时");
            return ChatOutcome.failure("CHAT_TIMEOUT", "机器人响应超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复线程的中断标志位
            return ChatOutcome.failure("CHAT_FAILED", "请求被中断");
        } catch (Exception e) {
            logger.error("聊天请求异常", e);
            return ChatOutcome.failure("CHAT_FAILED", e.getMessage());
        } finally {
            requestLock.unlock();
        }
    }


    /**
     * 生命周期钩子：当 Spring Boot 进程被关闭（比如按下 Ctrl+C）时，自动触发。
     * 用于兜底清理资源，防止大模型进程变成孤儿进程在后台偷偷吃显存。
     */
    @PreDestroy
    public void destroy() {
        FrontendSession session = currentSession;
        currentSession = null;
        if (session != null) {
            session.close();
        }
    }

    /**
     * 作用：包装底层的 ChatBot 引擎，并巧妙地【将异步的回调机制转换为同步的阻塞等待】。
     */
    private static final class FrontendSession implements AutoCloseable {

        private final SessionStartRequest request;

        // 【精华所在】：AtomicReference 保证原子性。
        // CompletableFuture 是一个“未来结果”的占位符。当发送消息后，主线程会在这里等待(get)。
        // 等到 AI 引擎通过监听器回调时，把结果填入这个 Future，主线程就被唤醒了。
        private final AtomicReference<CompletableFuture<ChatOutcome>> pendingReply = new AtomicReference<>();
        private final ChatBot chatBot;
        private final TtsClient ttsClient ;
        private boolean ttsOpen = false;

        private FrontendSession(SessionStartRequest request, String workspaceDir) {
            this.request = request;
            // 初始化底层 AI 引擎，并把自己写的监听器传进去
            ConfigManager configManager = new ConfigManager(request.name(),request.role());

            this.chatBot = new ChatBot(
                    request.name(),
                    request.role(),
                    PathConfig.SKILLS_DIR,
                    workspaceDir,
                    configManager,
                    new AdapterBotResponseListener() // 关键：注入回调监听器
            );


            this.ttsClient = new ApiTtsClient(
                    HttpClient.newHttpClient(),
                    URI.create(configManager.get_tts("apiUri")),
                    configManager.get_tts("apiKey"),
                    configManager.get_tts("model"),
                    configManager.get_tts("voice")
            );
        }

        private String botName() {
            return request.name();
        }

        /**
         * 发送消息并等待回复。
         */
        private ChatOutcome sendChat(String text, boolean captureDesktop, boolean ttsOpen, long timeoutMs)
                throws InterruptedException, TimeoutException {

            CompletableFuture<ChatOutcome> future = new CompletableFuture<>();
            this.ttsOpen = ttsOpen;

            // compareAndSet 意思是：如果 pendingReply 当前是 null（代表空闲），就把它设为 future。
            // 如果它不是 null（说明上一句话 AI 还在思考，还没填入结果），就直接拒绝本次请求，防止前端疯狂连点发消息。
            if (!pendingReply.compareAndSet(null, future)) {
                return ChatOutcome.failure("CHAT_FAILED", "上一个请求还在处理中");
            }

            try {
                // 告诉底层引擎开始干活（这个方法通常是瞬间返回的，底层在异步算力线程中计算）
                chatBot.sendMessage(text, captureDesktop);

                // 【挂起等待】：当前线程在这里停住，最多等 timeoutMs 毫秒。
                // 只有当 AdapterBotResponseListener 里的 onResponse 被触发并填入 future，这里才会往下走。
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | InterruptedException e) {
                pendingReply.compareAndSet(future, null); // 超时或报错了，赶紧把坑位腾出来
                throw e;
            } catch (Exception e) {
                pendingReply.compareAndSet(future, null);
                return ChatOutcome.failure("CHAT_FAILED", e.getMessage());
            } finally {
                pendingReply.compareAndSet(future, null); // 不管成功失败，最后一定要清空占位符，迎接下一句对话
            }
        }

        @Override
        public void close() {
            // 如果正在等待回答的时候突然关掉窗口，立即给 future 塞一个失败结果，让外面被挂起的线程立刻解脱
            CompletableFuture<ChatOutcome> future = pendingReply.getAndSet(null);
            if (future != null) {
                future.complete(ChatOutcome.failure("CHAT_FAILED", "会话已关闭"));
            }
            chatBot.stop(); // 停止底层 AI 引擎
        }

        private void speakAsync(String text) {
            CompletableFuture.runAsync(() -> {
                try {
                    ttsClient.speak(text);
                } catch (Exception e) {
                    logger.warn("TTS failed: {}", e.getMessage(), e);
                }
            });
        }


        // 把 AI 的结果塞进坑位里，唤醒在 future.get() 处等待的线程
        private void complete(ChatOutcome outcome) {
            CompletableFuture<ChatOutcome> future = pendingReply.getAndSet(null);
            if (future != null) {
                future.complete(outcome);
            }
        }

        /**
         * 监听器实现：接收底层大模型异步回调的各种事件。
         */
        private final class AdapterBotResponseListener implements BotResponseListener {
            @Override
            public void onResponse(String text) {
                // AI 计算完成，给出了完整回复，包装成 success 丢进坑位唤醒主线程
                if(ttsOpen){
                    speakAsync(text);
                }
                complete(ChatOutcome.success(text));
            }

            @Override
            public void onError(String msg) {
                // AI 计算出错了，包装成 failure 丢进坑位唤醒主线程
                complete(ChatOutcome.failure("CHAT_FAILED", msg));
            }

            // --- 其他事件回调（比如流式输出、工具调用），目前没用到，所以空着 ---
            @Override
            public void onStreamToken(String token) {
            }

            @Override
            public void onAction(String actionMsg) {
            }

            @Override
            public void onTaskUpdate(String taskMsg) {
            }

            @Override
            public void onTokenUpdate(String tokenMsg) {
            }

            @Override
            public boolean onCodeReview(String fileName, String oldCode, String newCode) {
                return false;
            }

            @Override
            public void onWorkflowComplete() {
            }
        }
    }

    // --- 以下为内部逻辑辅助方法（数据清洗） ---

    SessionStartRequest normalize(SessionStartRequest request) {
        if (request == null) return new SessionStartRequest(
                "AI",
                "1",
                false,
                null,
                null,
                null
        );
        return new SessionStartRequest(
                normalizeText(request.name(), "AI"),
                normalizeText(request.role(), "1"),
                request.hasWorld(),
                normalizeNullablePath(request.profilePath()),
                normalizeNullablePath(request.worldPath()),
                normalizeNullablePath(request.workPath())
        );
    }

    String resolveWorkspaceDir(SessionStartRequest request) {
        String requestedWorkspaceDir = request == null ? null : normalizeNullablePath(request.workPath());
        String effectiveWorkspaceDir = requestedWorkspaceDir != null
                ? requestedWorkspaceDir
                : normalizeNullablePath(configuredWorkspaceDir);
        return BotWorkspaceResolver.resolve(effectiveWorkspaceDir);
    }

    private String normalizeText(String v, String fallback) {
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    private String normalizeNullablePath(String v) {
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }

    private String resolveVersion() {
        Package pkg = getClass().getPackage();
        return (pkg != null && pkg.getImplementationVersion() != null) ? pkg.getImplementationVersion() : DEFAULT_VERSION;
    }

}
