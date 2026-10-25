package org.las2mile.scrcpy.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.PlaybackParams;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioPlayer {
    private static final String TAG = "AudioPlayer";

    public static final int CODEC_RAW = 0x00726177;
    public static final int CODEC_OPUS = 0x6F707573;
    public static final int CODEC_AAC = 0x00616163;
    public static final int CODEC_FLAC = 0x666C6163;

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioTrack audioTrack;
    private MediaCodec audioDecoder;
    private int codecId;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private long totalWrittenFrames = 0;
    private long lastHeadPosition = 0;
    private long totalPlayedFrames = 0;
    private float currentSpeed = 1.0f;
    private long playbackStartTimeMs = 0;

    public interface AudioPcmListener {
        void onPcmData(byte[] pcm, int offset, int length);
    }

    private volatile AudioPcmListener pcmListener;

    public void setPcmListener(AudioPcmListener listener) {
        this.pcmListener = listener;
    }

    public AudioPlayer(int codecId) {
        this.codecId = codecId;
    }

    public void start() {
        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize <= 0) {
            minBufferSize = 4096;
        }
        // Allocate comfortable buffer capacity (~85ms to 120ms) so OS/thread jitter never causes underrun crackles
        int bufferSize = Math.max(minBufferSize * 2, 16384);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            audioTrack = builder.build();
        } else {
            audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
            );
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                int frameSize = 2 * 2;
                int targetFrames = (SAMPLE_RATE * 50) / 1000; // 50ms buffer target (scrcpy standard)
                int minFrames = minBufferSize / frameSize;
                int optimalFrames = Math.max(targetFrames, minFrames);
                audioTrack.setBufferSizeInFrames(optimalFrames);
            } catch (Exception ignored) {}
        }

        totalWrittenFrames = 0;
        totalPlayedFrames = 0;
        lastHeadPosition = 0;
        currentSpeed = 1.0f;
        playbackStartTimeMs = System.currentTimeMillis();

        audioTrack.play();
        isRunning.set(true);
    }

    private void initDecoderWithCsd(byte[] csd0Bytes) {
        if (audioDecoder != null) {
            try {
                audioDecoder.stop();
                audioDecoder.release();
            } catch (Exception ignored) {}
            audioDecoder = null;
        }

        String mime = (codecId == CODEC_OPUS) ? "audio/opus" : ((codecId == CODEC_AAC) ? "audio/mp4a-latm" : "audio/flac");
        try {
            int channels = (csd0Bytes != null && csd0Bytes.length > 9) ? (csd0Bytes[9] & 0xFF) : 2;
            if (channels <= 0 || channels > 8) channels = 2;

            MediaFormat format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, channels);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0Bytes));

            if (codecId == CODEC_OPUS) {
                // csd-1: codec delay in nanoseconds (default 6500000L = 6.5ms)
                ByteBuffer csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
                csd1.putLong(6500000L);
                csd1.flip();
                format.setByteBuffer("csd-1", csd1);

                // csd-2: seek preroll in nanoseconds (0 for real-time live streaming)
                ByteBuffer csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
                csd2.putLong(0L);
                csd2.flip();
                format.setByteBuffer("csd-2", csd2);
            }

            audioDecoder = MediaCodec.createDecoderByType(mime);
            audioDecoder.configure(format, null, null, 0);
            audioDecoder.start();
            Log.d(TAG, mime + " decoder initialized with CSD successfully! (channels=" + channels + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize " + mime + " decoder with CSD", e);
            if (audioDecoder != null) {
                try {
                    audioDecoder.release();
                } catch (Exception ignored) {}
                audioDecoder = null;
            }
        }
    }

    private void writePcm(byte[] pcm, int offset, int length) {
        if (audioTrack == null || !isRunning.get() || length <= 0) {
            return;
        }

        long currentHead = audioTrack.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long deltaHead = (currentHead - lastHeadPosition) & 0xFFFFFFFFL;
        totalPlayedFrames += deltaHead;
        lastHeadPosition = currentHead;

        long queuedFrames = totalWrittenFrames - totalPlayedFrames;
        if (queuedFrames < 0) {
            queuedFrames = 0;
            totalWrittenFrames = totalPlayedFrames;
        }

        long queuedMs = (queuedFrames * 1000L) / SAMPLE_RATE;

        // Handle massive stalls / initial connection backlog (> 250ms)
        long elapsedSinceStart = System.currentTimeMillis() - playbackStartTimeMs;
        if (queuedMs > 250 || (elapsedSinceStart < 1200 && queuedMs > 80 && totalPlayedFrames == 0)) {
            Log.w(TAG, "Audio buffer backlog excessive (" + queuedMs + " ms), flushing to resync to real-time");
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.play();
            } catch (Exception e) {
                Log.w(TAG, "Failed to flush audio track: " + e.getMessage());
            }
            lastHeadPosition = audioTrack.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            totalPlayedFrames = 0;
            totalWrittenFrames = 0;
            queuedFrames = 0;
            queuedMs = 0;
            currentSpeed = 1.0f;
        }

        // Smooth clock drift regulation using native pitch-preserved time-stretching (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            float targetSpeed;
            if (queuedMs > 100) {
                targetSpeed = 1.08f; // 8% faster to quickly recover from network jitter
            } else if (queuedMs > 60) {
                targetSpeed = 1.03f; // 3% faster to gently eliminate hardware clock drift
            } else if (queuedMs < 20) {
                targetSpeed = 0.97f; // 3% slower to prevent buffer underrun crackles
            } else {
                targetSpeed = 1.0f;  // Normal bit-perfect 1.0x playback
            }

            if (Math.abs(targetSpeed - currentSpeed) > 0.005f) {
                try {
                    PlaybackParams params = audioTrack.getPlaybackParams();
                    params.setSpeed(targetSpeed);
                    audioTrack.setPlaybackParams(params);
                    currentSpeed = targetSpeed;
                } catch (Exception ignored) {}
            }
        }

        // Always write complete frames without slicing samples mid-wave (prevents crackling)
        try {
            int written = 0;
            while (written < length && isRunning.get()) {
                int w = audioTrack.write(pcm, offset + written, length - written);
                if (w <= 0) break;
                written += w;
            }

            if (written > 0) {
                totalWrittenFrames += (written / 4);
                AudioPcmListener listener = pcmListener;
                if (listener != null) {
                    listener.onPcmData(pcm, offset, written);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing to AudioTrack", e);
        }
    }

    public synchronized void playSample(byte[] data, long pts, boolean isConfig) {
        if (!isRunning.get() || audioTrack == null) {
            return;
        }

        if (codecId == CODEC_RAW) {
            writePcm(data, 0, data.length);
            return;
        }

        if (isConfig) {
            initDecoderWithCsd(data);
            return;
        }

        if (audioDecoder == null) {
            // Wait for configuration packet before decoding
            return;
        }

        try {
            int inputIndex = audioDecoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    inputBuffer = audioDecoder.getInputBuffer(inputIndex);
                } else {
                    inputBuffer = audioDecoder.getInputBuffers()[inputIndex];
                }
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data);
                    audioDecoder.queueInputBuffer(inputIndex, 0, data.length, pts, 0);
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, 0);
            while (outputIndex >= 0) {
                ByteBuffer outputBuffer;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    outputBuffer = audioDecoder.getOutputBuffer(outputIndex);
                } else {
                    outputBuffer = audioDecoder.getOutputBuffers()[outputIndex];
                }
                if (outputBuffer != null && bufferInfo.size > 0 && audioTrack != null) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    byte[] pcm = new byte[bufferInfo.size];
                    outputBuffer.get(pcm);
                    writePcm(pcm, 0, pcm.length);
                }
                audioDecoder.releaseOutputBuffer(outputIndex, false);
                outputIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "MediaCodec invalid state, disposing decoder: " + e.getMessage());
            if (audioDecoder != null) {
                try {
                    audioDecoder.release();
                } catch (Exception ignored) {}
                audioDecoder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding audio sample", e);
        }
    }

    public synchronized void stop() {
        isRunning.set(false);
        totalWrittenFrames = 0;
        totalPlayedFrames = 0;
        lastHeadPosition = 0;
        currentSpeed = 1.0f;
        if (audioDecoder != null) {
            try {
                audioDecoder.stop();
            } catch (Exception ignored) {
            }
            try {
                audioDecoder.release();
            } catch (Exception ignored) {
            }
            audioDecoder = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Exception ignored) {
            }
            try {
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }
}
