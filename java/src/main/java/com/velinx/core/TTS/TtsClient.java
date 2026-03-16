package com.velinx.core.TTS;

/**
 * TtsClient 接口定义了文本转语音（Text-To-Speech）客户端的核心功能。
 * 该接口提供了将文本转换为音频数据以及将音频数据输出到硬件设备的能力。
 */
public interface TtsClient {

    /**
     * 将指定的文本合成为语音音频数据。
     *
     * @param text 待转换的文本内容。
     * @return 包含音频原始数据的字节数组（通常为 WAV 或 MP3 格式，取决于具体实现）。
     * @throws Exception 如果在合成过程中发生网络请求失败、API 鉴权错误或参数无效等情况。
     */
    byte[] synthesize(String text) throws Exception;

    /**
     * 播放给定的音频字节流。
     *
     * @param audioBytes 要播放的音频原始字节数组（通常是从 synthesize 方法获取的结果）。
     * @throws Exception 如果音频格式不受支持、音频硬件被占用或播放过程中发生 IO 错误。
     */
    void play(byte[] audioBytes) throws Exception;

    /**
     * 便捷方法：直接将文本转换为语音并立即通过播放设备朗读出来。
     * 这是一个默认实现，它按顺序调用 {@link #synthesize(String)} 获取音频，
     * 然后调用 {@link #play(byte[])} 进行播放。
     *
     * @param text 待朗读的文本内容。
     * @throws Exception 如果合成或播放过程中的任何环节发生错误。
     */
    default void speak(String text) throws Exception {
        play(synthesize(text));
    }
}