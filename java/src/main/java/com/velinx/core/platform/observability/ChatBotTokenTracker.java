package com.velinx.core.platform.observability;


import com.velinx.core.chat.runtime.BotResponseListener;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * token 计数器
 */
public class ChatBotTokenTracker implements ChatModelListener {

    private final AtomicInteger totalInputTokens = new AtomicInteger(0);
    private final AtomicInteger totalOutputTokens = new AtomicInteger(0);

    private final double inputPricePer1k;
    private final double outputPricePer1k;
    private final BotResponseListener listener;

    /**
     * @param inputPricePer1k  每 1000 Token 的输入价格 (单位：元)
     * @param outputPricePer1k 每 1000 Token 的输出价格 (单位：元)
     * @param listener         UI 回调监听器
     */
    public ChatBotTokenTracker(double inputPricePer1k, double outputPricePer1k, BotResponseListener listener) {
        this.inputPricePer1k = inputPricePer1k;
        this.outputPricePer1k = outputPricePer1k;
        this.listener = listener;
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        if (context.response() != null && context.response().tokenUsage() != null) {
            int currentInput = context.response().tokenUsage().inputTokenCount();
            int currentOutput = context.response().tokenUsage().outputTokenCount();
            int currentTotal = context.response().tokenUsage().totalTokenCount();

            // 原子更新累加值
            int cumulativeInput = totalInputTokens.addAndGet(currentInput);
            int cumulativeOutput = totalOutputTokens.addAndGet(currentOutput);
            int cumulativeTotal = cumulativeInput + cumulativeOutput;

            // 计算费用
            double cost = (cumulativeInput / 1000.0 * inputPricePer1k) + (cumulativeOutput / 1000.0 * outputPricePer1k);

            String tokenMsg = String.format(
                    "{\"inputTokens\":%d,\"outputTokens\":%d,\"totalTokens\":%d,\"cost\":%.4f}",
                    cumulativeInput,
                    cumulativeOutput,
                    cumulativeTotal,
                    cost
            );

            if (listener != null) {
                listener.onTokenUpdate(tokenMsg);
            }
        }
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        // 请求发起时的逻辑（可选）
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        // 错误处理逻辑（可选）
    }

    // 提供获取当前统计的方法
    public int getTotalInputTokens() { return totalInputTokens.get(); }
    public int getTotalOutputTokens() { return totalOutputTokens.get(); }
}
