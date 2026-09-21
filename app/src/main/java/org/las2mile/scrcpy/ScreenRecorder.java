package org.las2mile.scrcpy;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;

import org.las2mile.scrcpy.audio.AudioPlayer;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance ScreenRecorder for scrcpy-android.
 *
 * Features:
 * 1. Hardware Surface-based H.264 video encoding (MediaCodec.createInputSurface + OpenGL ES 2.0).
 *    Completely eliminates CPU-bound YUV conversion and stride/alignment mismatch (no diagonal stripes).
 * 2. Adaptive aspect-ratio letterboxing/pillarboxing: automatically centers the frame on a black canvas
 *    when remote orientation changes between portrait and landscape.
 * 3. Synchronized AAC audio capture: hooks into AudioPlayer PCM stream to mux stereo audio when available.
 * 4. Scoped storage MediaStore integration for Android 10+ (Movies/scrcpy) and legacy fallback.
 */
public class ScreenRecorder implements AudioPlayer.AudioPcmListener {
    private static final String TAG = "ScreenRecorder";
    private static final int FRAME_RATE = 30;
    private static final int IFRAME_INTERVAL = 2; // seconds
    private static final int VIDEO_BIT_RATE = 6_000_000; // 6 Mbps
    private static final long FRAME_INTERVAL_MS = 1000L / FRAME_RATE;

    private static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_BIT_RATE = 128_000; // 128 kbps AAC

    public interface RecordListener {
        void onStarted();
        void onStopped(String filePath);
        void onError(String reason);
        void onTick(long elapsedSeconds);
    }

    private final AtomicBoolean mIsRecording = new AtomicBoolean(false);

    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;

    private MediaCodec mVideoEncoder;
    private Surface mInputSurface;
    private EglSurfaceRenderer mEglRenderer;

    private MediaCodec mAudioEncoder;
    private boolean mAudioEnabled = false;

    private final Object mMuxerLock = new Object();
    private MediaMuxer mMuxer;
    private int mVideoTrackIndex = -1;
    private int mAudioTrackIndex = -1;
    private boolean mMuxerStarted = false;

    private int mCanvasWidth;
    private int mCanvasHeight;
    private long mRecordStartMs = 0;

    private SurfaceView mSurfaceView;
    private Scrcpy mScrcpy;
    private Context mContext;
    private RecordListener mListener;

    private Uri mMediaUri;
    private OutputStream mMediaOutputStream;
    private File mOutputFile;

    private final Handler mMainHandler = new Handler(android.os.Looper.getMainLooper());
    private long mFrameCount = 0;

    public boolean isRecording() {
        return mIsRecording.get();
    }

    /**
     * Backward-compatible start without Scrcpy instance.
     */
    public void start(SurfaceView surfaceView, int width, int height,
                      Context context, RecordListener listener) {
        start(surfaceView, width, height, null, context, listener);
    }

    /**
     * Start recording.
     *
     * @param surfaceView the SurfaceView displaying the mirrored device screen
     * @param width       initial requested width (0 for view width)
     * @param height      initial requested height (0 for view height)
     * @param scrcpy      scrcpy service instance for audio capture (optional)
     * @param context     context for MediaStore
     * @param listener    callbacks
     */
    public void start(SurfaceView surfaceView, int width, int height,
                      Scrcpy scrcpy, Context context, RecordListener listener) {
        if (mIsRecording.get()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (listener != null) listener.onError("录制功能需要 Android 8.0 及以上版本");
            return;
        }

        mSurfaceView = surfaceView;
        mScrcpy = scrcpy;
        mContext = context;
        mListener = listener;

        int svW = (width > 0) ? width : surfaceView.getWidth();
        int svH = (height > 0) ? height : surfaceView.getHeight();
        if (svW <= 0 || svH <= 0) {
            if (listener != null) listener.onError("SurfaceView 尺寸无效");
            return;
        }

        // Clamp maximum canvas dimension to 1920 to guarantee broad hardware AVC Level 4.1/4.2 support
        int maxDim = Math.max(svW, svH);
        int targetW = svW;
        int targetH = svH;
        if (maxDim > 1920) {
            float scale = 1920f / maxDim;
            targetW = Math.round(svW * scale);
            targetH = Math.round(svH * scale);
        }
        // Ensure dimensions are even numbers (MediaCodec requirement)
        mCanvasWidth  = (targetW / 2) * 2;
        mCanvasHeight = (targetH / 2) * 2;

        new Thread(this::initAndRecord, "RecorderInit").start();
    }

    private void initAndRecord() {
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            String fileName = "scrcpy_" + timeStamp + ".mp4";

            // ── 1. Configure Video Encoder (Surface input) ───────────────────
            MediaFormat videoFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, mCanvasWidth, mCanvasHeight);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

            mVideoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();

            // ── 2. Configure Audio Encoder (if Scrcpy has audio enabled) ─────
            mAudioEnabled = (mScrcpy != null && (mScrcpy.isAudioActive() || mScrcpy.isAudioEnabled()));
            if (mAudioEnabled) {
                try {
                    MediaFormat audioFormat = MediaFormat.createAudioFormat(
                            MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
                    audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
                    audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

                    mAudioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    mAudioEncoder.start();

                    // Pre-feed a silence frame to trigger INFO_OUTPUT_FORMAT_CHANGED immediately
                    feedAudioSilenceInitial();

                    mScrcpy.setAudioPcmListener(this);
                    Log.d(TAG, "Audio recording enabled via AAC encoder");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to initialize AAC audio encoder, continuing video-only", e);
                    mAudioEnabled = false;
                    if (mAudioEncoder != null) {
                        try { mAudioEncoder.release(); } catch (Exception ignored) {}
                        mAudioEncoder = null;
                    }
                }
            }

            // ── 3. Setup MediaMuxer ─────────────────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/scrcpy");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                mMediaUri = mContext.getContentResolver()
                        .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (mMediaUri == null) throw new Exception("MediaStore insert returned null");
                mMediaOutputStream = mContext.getContentResolver().openOutputStream(mMediaUri);

                android.os.ParcelFileDescriptor pfd = mContext.getContentResolver()
                        .openFileDescriptor(mMediaUri, "rw");
                if (pfd == null) throw new Exception("Cannot open ParcelFileDescriptor");
                mMuxer = new MediaMuxer(pfd.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_MOVIES), "scrcpy");
                if (!dir.exists()) dir.mkdirs();
                mOutputFile = new File(dir, fileName);
                mMuxer = new MediaMuxer(mOutputFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            mVideoTrackIndex = -1;
            mAudioTrackIndex = -1;
            mMuxerStarted = false;
            mFrameCount = 0;
            mRecordStartMs = System.currentTimeMillis();
            mIsRecording.set(true);

            // Drain audio initial format if ready
            if (mAudioEnabled) {
                drainAudioEncoder(false);
            }

            // ── 4. Start Capture & GL Render Thread ─────────────────────────
            mCaptureThread = new HandlerThread("ScreenRecorderGL");
            mCaptureThread.start();
            mCaptureHandler = new Handler(mCaptureThread.getLooper());

            mCaptureHandler.post(() -> {
                try {
                    mEglRenderer = new EglSurfaceRenderer(mInputSurface, mCanvasWidth, mCanvasHeight);
                    scheduleNextFrame();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to initialize EGL renderer", e);
                    cleanup();
                    mMainHandler.post(() -> {
                        if (mListener != null) mListener.onError("OpenGL 初始化失败: " + e.getMessage());
                    });
                }
            });

            mMainHandler.post(() -> {
                if (mListener != null) mListener.onStarted();
            });

        } catch (Exception e) {
            Log.e(TAG, "Recorder init failed", e);
            cleanup();
            final String msg = e.getMessage();
            mMainHandler.post(() -> {
                if (mListener != null) mListener.onError("录制初始化失败: " + msg);
            });
        }
    }

    private void feedAudioSilenceInitial() {
        if (mAudioEncoder == null) return;
        try {
            int inputIndex = mAudioEncoder.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                ByteBuffer buf = mAudioEncoder.getInputBuffer(inputIndex);
                if (buf != null) {
                    buf.clear();
                    byte[] silence = new byte[4096];
                    buf.put(silence);
                    mAudioEncoder.queueInputBuffer(inputIndex, 0, silence.length, 0, 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "feedAudioSilenceInitial exception", e);
        }
    }

    private void scheduleNextFrame() {
        if (!mIsRecording.get() || mCaptureHandler == null) return;
        mCaptureHandler.postDelayed(this::captureFrame, FRAME_INTERVAL_MS);
    }

    private void captureFrame() {
        if (!mIsRecording.get() || mEglRenderer == null) return;
        SurfaceView sv = mSurfaceView;
        if (sv == null) return;

        int curW = sv.getWidth();
        int curH = sv.getHeight();
        if (curW <= 0 || curH <= 0) {
            scheduleNextFrame();
            return;
        }

        // Capture at the SurfaceView's exact current dimensions (handles orientation dynamically)
        Bitmap frameBmp = Bitmap.createBitmap(curW, curH, Bitmap.Config.ARGB_8888);

        PixelCopy.request(sv, frameBmp, result -> {
            if (result == PixelCopy.SUCCESS && mIsRecording.get() && mEglRenderer != null) {
                long ptsNs = (System.currentTimeMillis() - mRecordStartMs) * 1_000_000L;
                mEglRenderer.drawFrame(frameBmp, ptsNs);
                drainVideoEncoder(false);
                mFrameCount++;

                long elapsed = (System.currentTimeMillis() - mRecordStartMs) / 1000;
                mMainHandler.post(() -> {
                    if (mListener != null) mListener.onTick(elapsed);
                });
            }
            frameBmp.recycle();
            scheduleNextFrame();
        }, mCaptureHandler);
    }

    private void drainVideoEncoder(boolean endOfStream) {
        if (mVideoEncoder == null) return;
        if (endOfStream) {
            try {
                mVideoEncoder.signalEndOfInputStream();
            } catch (Exception e) {
                Log.w(TAG, "signalEndOfInputStream failed", e);
            }
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (mVideoEncoder != null) {
            int outputIndex = mVideoEncoder.dequeueOutputBuffer(bufferInfo, 10_000);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (mMuxerLock) {
                    if (mMuxer != null) {
                        mVideoTrackIndex = mMuxer.addTrack(mVideoEncoder.getOutputFormat());
                        checkStartMuxerLocked();
                    }
                }
            } else if (outputIndex >= 0) {
                ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(outputIndex);
                if (encodedData != null) {
                    synchronized (mMuxerLock) {
                        if (mMuxerStarted && mVideoTrackIndex >= 0) {
                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                                encodedData.position(bufferInfo.offset);
                                encodedData.limit(bufferInfo.offset + bufferInfo.size);
                                mMuxer.writeSampleData(mVideoTrackIndex, encodedData, bufferInfo);
                            }
                        }
                    }
                }
                mVideoEncoder.releaseOutputBuffer(outputIndex, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Audio PCM callback from AudioPlayer
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onPcmData(byte[] pcmData, int offset, int length) {
        if (!mIsRecording.get() || mAudioEncoder == null || !mAudioEnabled || length <= 0) {
            return;
        }

        try {
            int inputIndex = mAudioEncoder.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = mAudioEncoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(pcmData, offset, length);
                    long ptsUs = (System.currentTimeMillis() - mRecordStartMs) * 1000L;
                    mAudioEncoder.queueInputBuffer(inputIndex, 0, length, ptsUs, 0);
                }
            }
            drainAudioEncoder(false);
        } catch (Exception e) {
            Log.w(TAG, "Audio encode error: " + e.getMessage());
        }
    }

    private void drainAudioEncoder(boolean endOfStream) {
        if (mAudioEncoder == null) return;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (mAudioEncoder != null) {
            int outputIndex = mAudioEncoder.dequeueOutputBuffer(bufferInfo, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (mMuxerLock) {
                    if (mMuxer != null) {
                        mAudioTrackIndex = mMuxer.addTrack(mAudioEncoder.getOutputFormat());
                        checkStartMuxerLocked();
                    }
                }
            } else if (outputIndex >= 0) {
                ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(outputIndex);
                if (encodedData != null) {
                    synchronized (mMuxerLock) {
                        if (mMuxerStarted && mAudioTrackIndex >= 0) {
                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                                encodedData.position(bufferInfo.offset);
                                encodedData.limit(bufferInfo.offset + bufferInfo.size);
                                mMuxer.writeSampleData(mAudioTrackIndex, encodedData, bufferInfo);
                            }
                        }
                    }
                }
                mAudioEncoder.releaseOutputBuffer(outputIndex, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private void checkStartMuxerLocked() {
        if (mMuxerStarted || mMuxer == null) return;
        boolean videoReady = (mVideoTrackIndex >= 0);
        boolean audioReady = (!mAudioEnabled || mAudioTrackIndex >= 0);
        if (videoReady && audioReady) {
            try {
                mMuxer.start();
                mMuxerStarted = true;
                Log.d(TAG, "MediaMuxer started successfully! (videoTrack=" + mVideoTrackIndex
                        + ", audioTrack=" + mAudioTrackIndex + ")");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start MediaMuxer", e);
            }
        }
    }

    /**
     * Stop recording, finalize and save the MP4 file.
     */
    public void stop() {
        if (!mIsRecording.getAndSet(false)) return;

        // Unregister audio listener
        if (mScrcpy != null) {
            mScrcpy.setAudioPcmListener(null);
        }

        // Stop capture loop
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }

        new Thread(() -> {
            try {
                // Drain video encoder
                if (mVideoEncoder != null) {
                    drainVideoEncoder(true);
                }
                // Drain audio encoder
                if (mAudioEncoder != null) {
                    try {
                        int inIdx = mAudioEncoder.dequeueInputBuffer(10_000);
                        if (inIdx >= 0) {
                            mAudioEncoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                    } catch (Exception ignored) {}
                    drainAudioEncoder(true);
                }
            } catch (Exception e) {
                Log.w(TAG, "drainEncoders on stop: " + e.getMessage());
            }

            String savedPath = null;
            try {
                synchronized (mMuxerLock) {
                    if (mMuxer != null && mMuxerStarted) {
                        mMuxer.stop();
                        mMuxer.release();
                        mMuxer = null;
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    if (mMediaUri != null) {
                        mContext.getContentResolver().update(mMediaUri, values, null, null);
                        savedPath = Environment.DIRECTORY_MOVIES + "/scrcpy (相册/Movies)";
                    }
                } else if (mOutputFile != null) {
                    savedPath = mOutputFile.getAbsolutePath();
                }
            } catch (Exception e) {
                Log.e(TAG, "Muxer stop error", e);
            }

            cleanup();

            final String path = savedPath;
            mMainHandler.post(() -> {
                if (mListener != null) mListener.onStopped(path != null ? path : "Movies/scrcpy");
            });

        }, "RecorderStop").start();
    }

    private void cleanup() {
        if (mScrcpy != null) {
            mScrcpy.setAudioPcmListener(null);
        }

        if (mCaptureHandler != null && mEglRenderer != null) {
            mCaptureHandler.post(() -> {
                if (mEglRenderer != null) {
                    mEglRenderer.release();
                    mEglRenderer = null;
                }
            });
        }

        if (mCaptureThread != null) {
            mCaptureThread.quitSafely();
            mCaptureThread = null;
        }
        mCaptureHandler = null;

        if (mVideoEncoder != null) {
            try {
                mVideoEncoder.stop();
                mVideoEncoder.release();
            } catch (Exception ignored) {}
            mVideoEncoder = null;
        }
        if (mInputSurface != null) {
            try {
                mInputSurface.release();
            } catch (Exception ignored) {}
            mInputSurface = null;
        }

        if (mAudioEncoder != null) {
            try {
                mAudioEncoder.stop();
                mAudioEncoder.release();
            } catch (Exception ignored) {}
            mAudioEncoder = null;
        }

        synchronized (mMuxerLock) {
            if (mMuxer != null) {
                try {
                    mMuxer.release();
                } catch (Exception ignored) {}
                mMuxer = null;
            }
        }

        if (mMediaOutputStream != null) {
            try {
                mMediaOutputStream.close();
            } catch (Exception ignored) {}
            mMediaOutputStream = null;
        }

        mSurfaceView = null;
        mScrcpy = null;
        mMuxerStarted = false;
        mVideoTrackIndex = -1;
        mAudioTrackIndex = -1;
    }
}
