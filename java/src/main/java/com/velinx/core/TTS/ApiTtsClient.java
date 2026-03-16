package com.velinx.core.TTS;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * ApiTtsClient 是一个通过 SiliconFlow 提供的 API 实现文本转语音 (TTS) 功能的客户端。
 * 该类负责调用远程接口获取音频数据，并利用 Java Sound API 进行播放。
 */
public class ApiTtsClient implements TtsClient {

    // JSON 序列化工具
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // 默认使用的 TTS 模型
    private static final String DEFAULT_MODEL = "FunAudioLLM/CosyVoice2-0.5B";
    // 默认使用的音色
    private static final String DEFAULT_VOICE_NAME = "claire";

    private final HttpClient httpClient;
    private final URI apiUri;
    private final String apiKey;
    private final String model;
    private final String voice;


    /**
     * 全参数构造函数，允许自定义配置。
     */
    public ApiTtsClient(HttpClient httpClient, URI apiUri, String apiKey, String model, String voice) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient 不能为空");
        this.apiUri = Objects.requireNonNull(apiUri, "apiUri 不能为空");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey 不能为空");
        this.model = Objects.requireNonNull(model, "model 不能为空");
        this.voice = Objects.requireNonNull(voice, "voice 不能为空");
    }

    /**
     * 合成语音（使用默认音色）。
     *
     * @param text 待合成的文本
     * @return 语音文件的字节数组
     */
    @Override
    public byte[] synthesize(String text) throws IOException, InterruptedException {
        return synthesize(text, voice);
    }

    /**
     * 合成语音（指定音色名称）。
     *
     * @param text      待合成的文本
     * @param voiceName 指定音色名称（如 "claire" 或完整的 "model:claire"）
     * @return 语音文件的字节数组
     */
    public byte[] synthesize(String text, String voiceName) throws IOException, InterruptedException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("待合成文本不能为空");
        }

        // 构建请求体 JSON
        String requestJson = OBJECT_MAPPER.writeValueAsString(Map.of(
                "model", model,
                "input", text,
                "voice", resolveVoice(voiceName),
                "response_format", "wav" // 强制要求返回 wav 格式以便 Java 处理
        ));

        // 构建 HTTP 请求
        HttpRequest request = HttpRequest.newBuilder(apiUri)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        // 发送请求并获取响应内容
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        // 校验响应状态码
        if (response.statusCode() != 200) {
            throw new IOException("TTS 请求失败: HTTP 状态码 " + response.statusCode() + ", 详情=" + new String(response.body()));
        }

        return response.body();
    }

    /**
     * 合成语音并直接播放。
     */
    public void speak(String text, String voiceName) throws Exception {
        play(synthesize(text, voiceName));
    }

    /**
     * 播放音频字节流。
     *
     * @param audioBytes 音频原始字节（通常为 WAV 格式）
     */
    @Override
    public void play(byte[] audioBytes) throws Exception {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("音频数据不能为空");
        }

        // 使用 ByteArrayInputStream 包装字节数组，再通过 AudioSystem 获取音频输入流
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(audioBytes);
             AudioInputStream sourceStream = AudioSystem.getAudioInputStream(inputStream);
             // 将原始音频流转换为系统可播放的 PCM 格式
             AudioInputStream playableStream = toPlayableStream(sourceStream)) {

            AudioFormat format = playableStream.getFormat();
            // 定义音频输出行（扬声器）
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);

            try {
                // 打开并开始播放
                line.open(format);
                line.start();

                byte[] buffer = new byte[4096];
                int bytesRead;
                // 循环读取流并写入到音频行中
                while ((bytesRead = playableStream.read(buffer, 0, buffer.length)) != -1) {
                    line.write(buffer, 0, bytesRead);
                }

                // 阻塞直到所有数据播放完毕
                line.drain();
            } finally {
                line.stop();
                line.close();
            }
        }
    }

    /**
     * 辅助方法：确保音频格式是系统支持的 PCM 格式。
     * 如果系统不支持当前的编码（如压缩格式），则尝试转换为 16-bit PCM。
     */
    private AudioInputStream toPlayableStream(AudioInputStream sourceStream) {
        AudioFormat sourceFormat = sourceStream.getFormat();
        DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, sourceFormat);

        // 如果当前格式已经支持播放，则直接返回
        if (AudioSystem.isLineSupported(sourceInfo)) {
            return sourceStream;
        }

        // 定义通用的 PCM 目标格式 (16位, 符号化, 小端模式)
        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.getSampleRate(),
                16,
                sourceFormat.getChannels(),
                sourceFormat.getChannels() * 2,
                sourceFormat.getSampleRate(),
                false
        );

        // 检查系统是否能完成此格式转换
        if (!AudioSystem.isConversionSupported(targetFormat, sourceFormat)) {
            throw new IllegalStateException("不支持的音频格式且无法转换: " + sourceFormat);
        }

        return AudioSystem.getAudioInputStream(targetFormat, sourceStream);
    }

    /**
     * 处理音色名称逻辑：如果传入为空则使用默认音色，否则根据模型名进行包装。
     */
    private String resolveVoice(String voiceName) {
        if (voiceName == null || voiceName.isBlank()) {
            return voice;
        }
        return buildVoice(model, voiceName);
    }

    /**
     * 静态方法：根据模型名和音色简名构建完整的音色标识。
     * 格式示例: "FunAudioLLM/CosyVoice2-0.5B:claire"
     */
    private static String buildVoice(String model, String voiceName) {
        if (voiceName == null || voiceName.isBlank()) {
            throw new IllegalArgumentException("音色名称 (voiceName) 不能为空");
        }
        // 如果音色名已经包含了冒号，则认为已经是完整标识
        if (voiceName.contains(":")) {
            return voiceName;
        }
        return model + ":" + voiceName;
    }

//    /**
//     * 测试入口。
//     * 命令行参数：[音色名] [测试文本]
//     */
//    public static void main(String[] args) throws Exception {
//        ApiTtsClient client = new ApiTtsClient();
//
//        // 解析参数：第一个参数为音色名，后续参数为待合成文本
//        String voiceName = args.length >= 2 ? args[0] : DEFAULT_VOICE_NAME;
//        String testText = args.length == 0
//                ? "你好，这是默认音色测试。"
//                : args.length == 1
//                ? args[0]
//                : String.join(" ", Arrays.copyOfRange(args, 1, args.length));
//
//        System.out.println("正在合成语音 [模型: " + DEFAULT_MODEL + ", 音色: " + voiceName + "]...");
//        byte[] audio = client.synthesize(testText, voiceName);
//
//        System.out.println("正在播放音频...");
//        client.play(audio);
//
//        System.out.println("播放完成。");
//    }
}