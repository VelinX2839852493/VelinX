package com.velinx.core.TTS;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiTtsClientTest {

    @Test
    void normalizeInputTextKeepsPlainText() {
        String normalized = ApiTtsClient.normalizeInputText("  你好，开始测试。  ");

        assertEquals("你好，开始测试。", normalized);
    }

    @Test
    void buildVoiceCandidatesPrefersRawVoiceThenFallsBackToModelScopedVoice() {
        String[] candidates = ApiTtsClient.buildVoiceCandidates("fnlp/MOSS-TTSD-v0.5", "claire");

        assertArrayEquals(new String[]{"claire", "fnlp/MOSS-TTSD-v0.5:claire"}, candidates);
    }

    @Test
    void buildVoiceCandidatesKeepsFullVoiceIdUntouched() {
        String[] candidates = ApiTtsClient.buildVoiceCandidates("fnlp/MOSS-TTSD-v0.5", "fnlp/MOSS-TTSD-v0.5:claire");

        assertArrayEquals(new String[]{"fnlp/MOSS-TTSD-v0.5:claire"}, candidates);
    }

    @Test
    void normalizeAudioResponseRepairsRiffAndDataChunkSizes() {
        byte[] wavBytes = createBrokenWav(new byte[]{1, 2, 3, 4, 5, 6});

        byte[] normalized = ApiTtsClient.normalizeAudioResponse(wavBytes);

        assertEquals(normalized.length - 8, readLittleEndianInt(normalized, 4));
        assertEquals(6, readLittleEndianInt(normalized, 40));
        assertArrayEquals(
                Arrays.copyOfRange(wavBytes, 44, wavBytes.length),
                Arrays.copyOfRange(normalized, 44, normalized.length)
        );
    }

    private byte[] createBrokenWav(byte[] audioPayload) {
        ByteBuffer buffer = ByteBuffer.allocate(44 + audioPayload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(-1);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(44100);
        buffer.putInt(88200);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(-1);
        buffer.put(audioPayload);
        return buffer.array();
    }

    private int readLittleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
