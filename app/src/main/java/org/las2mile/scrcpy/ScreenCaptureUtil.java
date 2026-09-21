package org.las2mile.scrcpy;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Utility class for capturing a single frame from a SurfaceView and saving it to Downloads.
 */
public class ScreenCaptureUtil {
    private static final String TAG = "ScreenCaptureUtil";

    public interface CaptureCallback {
        /** Called on the main thread when save is complete. */
        void onSuccess(String filePath);
        /** Called on the main thread on failure. */
        void onFailure(String reason);
    }

    /**
     * Capture the current frame of {@code surfaceView} and save it as a PNG to
     * the public Downloads directory.
     * Works on API 26+ via PixelCopy; falls back to drawingCache on older versions.
     */
    public static void captureFrame(SurfaceView surfaceView, Context context, CaptureCallback callback) {
        if (surfaceView == null || callback == null) return;

        Handler mainHandler = new Handler(android.os.Looper.getMainLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use PixelCopy for accurate GPU-rendered frame capture
            int w = surfaceView.getWidth();
            int h = surfaceView.getHeight();
            if (w <= 0 || h <= 0) {
                callback.onFailure("SurfaceView size is 0");
                return;
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

            HandlerThread handlerThread = new HandlerThread("PixelCopyThread");
            handlerThread.start();
            Handler copyHandler = new Handler(handlerThread.getLooper());

            PixelCopy.request(surfaceView, bitmap, copyResult -> {
                handlerThread.quitSafely();
                if (copyResult == PixelCopy.SUCCESS) {
                    saveBitmap(bitmap, context, mainHandler, callback);
                } else {
                    Log.e(TAG, "PixelCopy failed: " + copyResult);
                    mainHandler.post(() -> callback.onFailure("PixelCopy error: " + copyResult));
                }
            }, copyHandler);
        } else {
            // Fallback: drawingCache (may not capture GPU content accurately)
            surfaceView.setDrawingCacheEnabled(true);
            Bitmap bitmap = Bitmap.createBitmap(surfaceView.getDrawingCache());
            surfaceView.setDrawingCacheEnabled(false);
            if (bitmap == null) {
                mainHandler.post(() -> callback.onFailure("DrawingCache returned null"));
                return;
            }
            saveBitmap(bitmap, context, mainHandler, callback);
        }
    }

    private static void saveBitmap(Bitmap bitmap, Context context,
                                   Handler mainHandler, CaptureCallback callback) {
        new Thread(() -> {
            try {
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(new Date());
                String fileName = "scrcpy_" + timeStamp + ".png";
                String savedPath;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Use MediaStore for Android 10+
                    // NOTE: MediaStore.Images only allows DCIM or Pictures, NOT Downloads
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/scrcpy");
                    android.net.Uri uri = context.getContentResolver()
                            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new Exception("MediaStore insert returned null URI");
                    try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os == null) throw new Exception("Cannot open output stream");
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    savedPath = Environment.DIRECTORY_PICTURES + "/scrcpy/" + fileName;
                } else {
                    // Direct file write for older Android (API < 29)
                    File dir = new File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_PICTURES), "scrcpy");
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                    savedPath = file.getAbsolutePath();
                }

                bitmap.recycle();
                final String path = savedPath;
                mainHandler.post(() -> callback.onSuccess(path));
            } catch (Exception e) {
                Log.e(TAG, "Failed to save screenshot", e);
                mainHandler.post(() -> callback.onFailure(e.getMessage()));
            }
        }, "SaveScreenshot").start();
    }
}
