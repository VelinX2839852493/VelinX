package com.velinx.controller;

import com.velinx.dto.ChatOutcome;
import com.velinx.dto.ChatRequest;
import com.velinx.dto.SessionStartRequest;
import com.velinx.service.FrontendAdapterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * velinx 前端适配器控制器 (Controller层)
 * 职责：作为 Electron 前端与 Java 后端之间的网络路由器。
 * 核心机制：只负责接收 HTTP 请求、校验参数、调用 Service 层处理业务，最后把结果包装成标准的 JSON 返回给前端。
 * 绝对不在这里写任何底层的 AI 逻辑。
 */
@RestController // @RestController = @Controller + @ResponseBody，告诉 Spring 这个类下的所有接口都返回 JSON 格式的数据
public class FrontendAdapterController {

    // 注入业务逻辑层（Service）。使用 final 保证它不可变，线程安全。
    private final FrontendAdapterService frontendAdapterService;

    // 构造函数注入（Spring 推荐的依赖注入方式），Spring 启动时会自动把创建好的 Service 实例塞进来
    public FrontendAdapterController(FrontendAdapterService frontendAdapterService) {
        this.frontendAdapterService = frontendAdapterService;
    }

    /**
     * 接口：健康检查
     * 路由：GET http://localhost:端口/health
     * 作用：前端（Electron）启动时，可以通过不断轮询这个接口，来确认 Java 引擎是否已经完全启动并准备好接客了。
     */
    @GetMapping("/health")
    public ResponseEntity<SuccessResponse<Map<String, String>>> health() {
        // 调用 service 获取状态，然后用 success() 包一层标准信封，最后用 ResponseEntity.ok() 设置 HTTP 状态码为 200
        return ResponseEntity.ok(success(frontendAdapterService.health()));
    }

    /**
     * 接口：启动会话
     * 路由：POST /session/start
     * 作用：接收前端传来的角色信息，通知 Service 层去创建大模型实例。
     */
    @PostMapping(path = "/session/start", consumes = MediaType.APPLICATION_JSON_VALUE) // consumes 限制前端必须传 JSON 过来
    public ResponseEntity<?> startSession(@RequestBody(required = false) SessionStartRequest request) {
        try {
            // 1. 调用 Service 干活，拿到返回的机器人名字
            String botName = frontendAdapterService.startSession(request);
            // 2. 拼装成功的数据：{"botName": "AI助手"}，包上信封并返回 200 OK
            return ResponseEntity.ok(success(Map.of("botName", botName)));
        } catch (Exception e) {
            // 3. 如果 Service 抛出异常（比如路径找不到、显存爆了），在这里统一捕获，并返回 HTTP 500 错误
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.getMessage());
        }
    }

    /**
     * 接口：停止会话
     * 路由：POST /session/stop
     * 作用：前端关闭窗口时调用，销毁大模型实例，释放内存/显存。
     */
    @PostMapping(path = "/session/stop")
    public ResponseEntity<?> stopSession() {
        try {
            // 告诉 Service 去停止
            frontendAdapterService.stopSession();
            // 返回成功标识：{"stopped": true}
            return ResponseEntity.ok(success(Map.of("stopped", true)));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "STOP_FAILED", e.getMessage());
        }
    }

    /**
     * 接口：聊天对话
     * 路由：POST /chat
     * 作用：接收前端的提问，调用模型生成回答。这是一个同步阻塞接口，会一直等到 AI 回复完毕才返回给前端。
     */
    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chat(@RequestBody(required = false) ChatRequest request) {

        // 1. 把前端传来的参数直接扔给 Service 处理，拿到业务处理结果（ChatOutcome）
        ChatOutcome outcome = frontendAdapterService.chat(request);

        // 2. 如果业务处理失败（比如超时、传了空消息）
        if (!outcome.success()) {
            // 根据 Service 返回的错误码（code），精准映射到对应的 HTTP 协议状态码
            HttpStatus status = switch (outcome.code()) {
                case "CHAT_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;     // 504 网关超时
                case "EMPTY_TEXT", "SESSION_NOT_STARTED" -> HttpStatus.BAD_REQUEST; // 400 客户端参数错误
                default -> HttpStatus.INTERNAL_SERVER_ERROR;             // 500 服务器内部兜底错误
            };
            // 包装成标准错误 JSON 返回
            return error(status, outcome.code(), outcome.message());
        }

        // 3. 如果业务处理成功，拿到 AI 的回答并包装返回 200 OK
        return ResponseEntity.ok(success(Map.of("content", outcome.message())));
    }

    // ==========================================
    // 以下是 Controller 专用的“响应格式统一包装”工具
    // ==========================================

    /**
     * 成功数据的“包装信封”
     * 把单纯的数据装进 {"ok": true, "data": {...}} 的格式中
     */
    private <T> SuccessResponse<T> success(T data) {
        return new SuccessResponse<>(true, data);
    }

    /**
     * 错误数据的“包装信封”
     * 指定 HTTP 状态码，并封装成 {"ok": false, "error": {"code": "...", "message": "..."}} 的格式
     */
    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String msg) {
        return ResponseEntity.status(status).body(new ErrorResponse(false, new ErrorPayload(code, msg)));
    }

    // ==========================================
    // 内部 Record：用来定义返回给前端的 JSON 结构
    // Record 是 JDK 14 引入的语法糖，相当于自动生成 getter/setter 的轻量级 class
    // ==========================================

    // 成功时的 JSON 结构
    private record SuccessResponse<T>(boolean ok, T data) {
    }

    // 失败时的 JSON 结构
    private record ErrorResponse(boolean ok, ErrorPayload error) {
    }

    // 错误详情的数据结构
    private record ErrorPayload(String code, String message) {
    }
}