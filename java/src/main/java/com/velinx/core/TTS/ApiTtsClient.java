package com.velinx.core.TTS;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

public class ApiTtsClient implements TtsClient {

    private static final Logger logger = LoggerFactory.getLogger(ApiTtsClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RESPONSE_FORMAT_WAV = "wav";
    private static final byte[] RIFF_HEADER = {'R', 'I', 'F', 'F'};
    private static final byte[] WAVE_HEADER = {'W', 'A', 'V', 'E'};

    private final HttpClient httpClient;
    private final URI apiUri;
    private final String apiKey;
    private final String model;
    private final String voice;

    public ApiTtsClient(HttpClient httpClient, URI apiUri, String apiKey, String model, String voice) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.apiUri = Objects.requireNonNull(apiUri, "apiUri must not be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.voice = Objects.requireNonNull(voice, "voice must not be null");
    }

    @Override
    public byte[] synthesize(String text) throws IOException, InterruptedException {
        return synthesize(text, voice);
    }

    public byte[] synthesize(String text, String voiceName) throws IOException, InterruptedException {
        String normalizedText = normalizeInputText(text);
        if (normalizedText.isEmpty()) {
            throw new IllegalArgumentException("text must not be blank");
        }

        IOException lastError = null;
        for (String voiceCandidate : buildVoiceCandidates(model, voiceName == null || voiceName.isBlank() ? voice : voiceName)) {
            try {
                return sendSynthesisRequest(normalizedText, voiceCandidate);
            } catch (IOException error) {
                lastError = error;
                logger.warn("TTS request failed with voice '{}': {}", voiceCandidate, error.getMessage());
            }
        }

        throw lastError != null ? lastError : new IOException("TTS request failed without a response.");
    }

    public void speak(String text, String voiceName) throws Exception {
        play(synthesize(text, voiceName));
    }

    @Override
    public void play(byte[] audioBytes) throws Exception {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("audioBytes must not be empty");
        }

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(audioBytes);
             AudioInputStream sourceStream = AudioSystem.getAudioInputStream(inputStream);
             AudioInputStream playableStream = toPlayableStream(sourceStream)) {

            AudioFormat format = playableStream.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);

            try {
                line.open(format);
                line.start();

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = playableStream.read(buffer, 0, buffer.length)) != -1) {
                    line.write(buffer, 0, bytesRead);
                }

                line.drain();
            } finally {
                line.stop();
                line.close();
            }
        }
    }

    private byte[] sendSynthesisRequest(String text, String voiceCandidate) throws IOException, InterruptedException {
        String requestJson = OBJECT_MAPPER.writeValueAsString(Map.of(
                "model", model,
                "input", text,
                "voice", voiceCandidate,
                "response_format", RESPONSE_FORMAT_WAV
        ));

        HttpRequest request = HttpRequest.newBuilder(apiUri)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(
                    "TTS request failed: HTTP " + response.statusCode() + ", detail=" + new String(response.body(), StandardCharsets.UTF_8)
            );
        }

        return normalizeAudioResponse(response.body());
    }

    private AudioInputStream toPlayableStream(AudioInputStream sourceStream) {
        AudioFormat sourceFormat = sourceStream.getFormat();
        DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, sourceFormat);

        if (AudioSystem.isLineSupported(sourceInfo)) {
            return sourceStream;
        }

        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.getSampleRate(),
                16,
                sourceFormat.getChannels(),
                sourceFormat.getChannels() * 2,
                sourceFormat.getSampleRate(),
                false
        );

        if (!AudioSystem.isConversionSupported(targetFormat, sourceFormat)) {
            throw new IllegalStateException("Unsupported audio format and no conversion path: " + sourceFormat);
        }

        return AudioSystem.getAudioInputStream(targetFormat, sourceStream);
    }

    static String normalizeInputText(String text) {
        return text == null ? "" : text.trim();
    }

    static String[] buildVoiceCandidates(String model, String voiceName) {
        if (voiceName == null || voiceName.isBlank()) {
            throw new IllegalArgumentException("voiceName must not be blank");
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String normalizedVoice = voiceName.trim();
        candidates.add(normalizedVoice);

        if (!normalizedVoice.contains(":") && model != null && !model.isBlank()) {
            candidates.add(model.trim() + ":" + normalizedVoice);
        }

        return candidates.toArray(new String[0]);
    }

    static byte[] normalizeAudioResponse(byte[] audioBytes) {
        if (!looksLikeWav(audioBytes)) {
            return audioBytes;
        }

        byte[] normalized = Arrays.copyOf(audioBytes, audioBytes.length);
        writeLittleEndianInt(normalized, 4, Math.max(0, normalized.length - 8));

        int dataChunkOffset = findChunkOffset(normalized, "data");
        if (dataChunkOffset >= 0 && dataChunkOffset + 8 <= normalized.length) {
            int dataSize = normalized.length - (dataChunkOffset + 8);
            writeLittleEndianInt(normalized, dataChunkOffset + 4, Math.max(0, dataSize));
        } else {
            logger.debug("WAV response does not contain a data chunk.");
        }

        return normalized;
    }

    private static boolean looksLikeWav(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length < 12) {
            return false;
        }

        return matchesAt(audioBytes, 0, RIFF_HEADER) && matchesAt(audioBytes, 8, WAVE_HEADER);
    }

    private static boolean matchesAt(byte[] data, int offset, byte[] expected) {
        if (data.length < offset + expected.length) {
            return false;
        }

        for (int index = 0; index < expected.length; index += 1) {
            if (data[offset + index] != expected[index]) {
                return false;
            }
        }

        return true;
    }

    private static int findChunkOffset(byte[] audioBytes, String chunkName) {
        byte[] marker = chunkName.getBytes(StandardCharsets.US_ASCII);
        for (int index = 12; index <= audioBytes.length - marker.length; index += 1) {
            boolean match = true;
            for (int markerIndex = 0; markerIndex < marker.length; markerIndex += 1) {
                if (audioBytes[index + markerIndex] != marker[markerIndex]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return index;
            }
        }

        return -1;
    }

    private static void writeLittleEndianInt(byte[] audioBytes, int offset, int value) {
        if (audioBytes.length < offset + 4) {
            return;
        }

        audioBytes[offset] = (byte) (value & 0xFF);
        audioBytes[offset + 1] = (byte) ((value >> 8) & 0xFF);
        audioBytes[offset + 2] = (byte) ((value >> 16) & 0xFF);
        audioBytes[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
