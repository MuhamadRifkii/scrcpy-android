package org.las2mile.scrcpy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

public class FloatService extends Service implements Scrcpy.ServiceCallbacks {
    private static final String TAG = "FloatService";
    private static final String CHANNEL_ID = "scrcpy_floating_service";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STOP = "org.las2mile.scrcpy.action.STOP";
    public static final String ACTION_VOL_DOWN = "org.las2mile.scrcpy.action.VOL_DOWN";
    public static final String ACTION_VOL_UP = "org.las2mile.scrcpy.action.VOL_UP";

    private DisplayWindow displayWindow;
    private WindowManager windowManager;
    private WindowManager.LayoutParams lp;

    private volatile Scrcpy scrcpy;
    private volatile boolean serviceBound = false;
    private Surface surface;

    private String serverAdr;
    private int serverPort = 7007;
    private int screenWidth = 1080;
    private int screenHeight = 1920;
    private boolean audioEnabled = false;
    private boolean audioOnly = false;
    private boolean captureButtons = true;
    private boolean noControl = false;
    private boolean screenOff = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Screenshot & recording
    private final ScreenRecorder screenRecorder = new ScreenRecorder();
    private Runnable recordTimerRunnable;
    private long recordStartMs = 0;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            scrcpy = ((Scrcpy.MyServiceBinder) binder).getService();
            if (scrcpy == null) return;
            scrcpy.setServiceCallbacks(FloatService.this);
            serviceBound = true;

            if (audioOnly || (surface != null && surface.isValid())) {
                scrcpy.start(surface, serverAdr, serverPort, screenHeight, screenWidth, audioEnabled, !audioOnly);

                new Thread(() -> {
                    int count = 60;
                    while (count > 0 && serviceBound && scrcpy != null) {
                        if (scrcpy.check_socket_connection()) break;
                        count--;
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    final int finalCount = count;
                    mainHandler.post(() -> {
                        if (finalCount == 0) {
                            Toast.makeText(FloatService.this, R.string.msg_connection_timeout, Toast.LENGTH_SHORT).show();
                            stopSelf();
                        } else {
                            if (screenOff && scrcpy != null) {
                                scrcpy.setDisplayPower(false);
                            }
                            if (audioOnly) {
                                String dev = (scrcpy != null) ? scrcpy.getDeviceName() : null;
                                String title = (dev != null && !dev.isEmpty()) ? dev : getString(R.string.title_remote_speaker);
                                updateNotification(title, "Remote Speaker active");
                            } else if (displayWindow != null) {
                                displayWindow.hideHintTip();
                                if (scrcpy != null) {
                                    int[] res = scrcpy.get_remote_device_resolution();
                                    if (res != null && res.length >= 2 && res[0] > 0 && res[1] > 0) {
                                        displayWindow.setRemote(res[0], res[1]);
                                    }
                                    String dev = scrcpy.getDeviceName();
                                    if (dev != null && !dev.isEmpty()) {
                                        TextView tvTitle = displayWindow.findViewById(R.id.tv_title);
                                        if (tvTitle != null) {
                                            tvTitle.setText(dev);
                                        }
                                    }
                                }
                            }
                        }
                    });
                }, "FloatWaitConnect").start();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            scrcpy = null;
        }
    };

    private final BroadcastReceiver usbDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                Log.d(TAG, "USB device detached");
                onDisconnected();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        promoteToForeground();

        // Register USB detach listener
        IntentFilter usbFilter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, usbFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbDetachReceiver, usbFilter);
        }
    }

    private void promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "scrcpy Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Running screen mirroring or audio playback in background");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
        updateNotification(getString(R.string.app_name), getString(R.string.btn_start_float));
    }

    private void updateNotification(String title, String content) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title != null ? title : getString(R.string.app_name))
                .setContentText(content != null ? content : getString(R.string.btn_start_float))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // Stop / Disconnect action
        Intent stopIntent = new Intent(this, FloatService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stopIntent, pendingFlags);
        builder.addAction(new NotificationCompat.Action.Builder(
                R.drawable.ic_close_small,
                "Disconnect",
                pStop
        ).build());

        if (audioOnly) {
            // Volume Down action
            Intent vDownIntent = new Intent(this, FloatService.class);
            vDownIntent.setAction(ACTION_VOL_DOWN);
            PendingIntent pVDown = PendingIntent.getService(this, 2, vDownIntent, pendingFlags);
            builder.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_nav_vol_down,
                    "Vol -",
                    pVDown
            ).build());

            // Volume Up action
            Intent vUpIntent = new Intent(this, FloatService.class);
            vUpIntent.setAction(ACTION_VOL_UP);
            PendingIntent pVUp = PendingIntent.getService(this, 3, vUpIntent, pendingFlags);
            builder.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_nav_vol_up,
                    "Vol +",
                    pVUp
            ).build());
        }

        Notification notification = builder.build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Exception fatal) {
                if (nm != null) nm.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return super.onStartCommand(intent, flags, startId);
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "Notification Disconnect action received");
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACTION_VOL_DOWN.equals(action)) {
            if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_VOLUME_DOWN);
            return START_NOT_STICKY;
        } else if (ACTION_VOL_UP.equals(action)) {
            if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_VOLUME_UP);
            return START_NOT_STICKY;
        }

        serverAdr = intent.getStringExtra("ip");
        serverPort = intent.getIntExtra("port", 7007);
        screenWidth = intent.getIntExtra("width", intent.getIntExtra("w", 1080));
        screenHeight = intent.getIntExtra("height", intent.getIntExtra("h", 1920));
        audioOnly = intent.getBooleanExtra("audio_only", false);
        captureButtons = intent.getBooleanExtra("capture_buttons", true);
        if (audioOnly) {
            audioEnabled = true;
        } else {
            audioEnabled = intent.getBooleanExtra("audio", false);
        }
        noControl = intent.getBooleanExtra("no_control", false);
        screenOff = intent.getBooleanExtra("screen_off", false);

        Log.d(TAG, "onStartCommand: server=" + serverAdr + ":" + serverPort + ", audioOnly=" + audioOnly);

        if (!audioOnly) {
            if (displayWindow == null) {
                setupDisplay();
            }
            displayWindow.setCaptureButtonsEnabled(captureButtons);
            displayWindow.setRemote(screenWidth, screenHeight);
            updateNotification(getString(R.string.app_name), getString(R.string.btn_start_float));
        } else {
            // Audio-only: do not add or display floating window
            if (displayWindow != null && windowManager != null) {
                try {
                    windowManager.removeView(displayWindow);
                } catch (Exception ignored) {}
                displayWindow = null;
            }
            updateNotification(getString(R.string.title_remote_speaker), "Remote Speaker active");
        }

        boolean canStart = (audioOnly || (surface != null && surface.isValid())) && serverAdr != null && !serverAdr.isEmpty();
        if (!serviceBound && canStart) {
            Intent scrcpyIntent = new Intent(FloatService.this, Scrcpy.class);
            startService(scrcpyIntent);
            bindService(scrcpyIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        }

        return START_NOT_STICKY;
    }

    private void setupDisplay() {
        Context themedContext = new androidx.appcompat.view.ContextThemeWrapper(this, R.style.AppTheme);
        displayWindow = new DisplayWindow(themedContext);
        displayWindow.setCaptureButtonsEnabled(captureButtons);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        lp = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            lp.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.x = 40;
        lp.y = 120;

        displayWindow.setOnWindowResizeListener((width, height) -> {
            if (lp != null) {
                lp.width = width;
                lp.height = height;
                clampWindowPositionToScreen();
            }
        });

        displayWindow.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (lp != null && lp.width > 0 && lp.height > 0) {
                    clampWindowPositionToScreen();
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
        });

        windowManager.addView(displayWindow, lp);

        displayWindow.setCloseListener(v -> {
            Log.d(TAG, "Floating window close clicked");
            stopSelf();
        });

        displayWindow.setMoveCallback((dx, dy) -> {
            lp.x += (int) dx;
            lp.y += (int) dy;
            clampWindowPositionToScreen();
        });

        displayWindow.setActionCallback(actionType -> {
            switch (actionType) {
                case DisplayWindow.ACTION_POWER:
                    if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_POWER);
                    break;
                case DisplayWindow.ACTION_BACK:
                    if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_BACK);
                    break;
                case DisplayWindow.ACTION_HOME:
                    if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_HOME);
                    break;
                case DisplayWindow.ACTION_MENU:
                    if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_APP_SWITCH);
                    break;
                case DisplayWindow.ACTION_VOLUME_DOWN:
                    if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_VOLUME_DOWN);
                    break;
                case DisplayWindow.ACTION_VOLUME_UP:
                    if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_VOLUME_UP);
                    break;
                case DisplayWindow.ACTION_ROTATE:
                    if (scrcpy != null) scrcpy.rotateDevice();
                    break;
                case DisplayWindow.ACTION_SCREENSHOT:
                    captureFloatScreenshot();
                    break;
                case DisplayWindow.ACTION_RECORD_TOGGLE:
                    toggleFloatRecording();
                    break;
            }
        });

        displayWindow.setOnDisplayTouchListener((v, event) -> {
            if (noControl || scrcpy == null) return false;
            return scrcpy.touchevent(event, displayWindow.getSurfaceWidth(), displayWindow.getSurfaceHeight());
        });

        displayWindow.setSurfaceReadyCallback(new DisplayWindow.SurfaceReadyCallback() {
            @Override
            public void onSurfaceReady(Surface readySurface) {
                if (audioOnly) return;
                surface = readySurface;
                if (!serviceBound && serverAdr != null && !serverAdr.isEmpty()) {
                    Intent scrcpyIntent = new Intent(FloatService.this, Scrcpy.class);
                    startService(scrcpyIntent);
                    bindService(scrcpyIntent, serviceConnection, Context.BIND_AUTO_CREATE);
                } else if (serviceBound && scrcpy != null) {
                    scrcpy.setSurface(readySurface);
                }
            }

            @Override
            public void onSurfaceDestroyed() {
                surface = null;
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged");
        if (displayWindow != null) {
            int[] res = (scrcpy != null) ? scrcpy.get_remote_device_resolution() : null;
            if (res != null && res.length >= 2 && res[0] > 0 && res[1] > 0) {
                displayWindow.onLocalConfigurationChanged(res[0], res[1]);
            } else {
                displayWindow.onLocalConfigurationChanged(0, 0);
            }
        }
    }

    private void clampWindowPositionToScreen() {
        if (windowManager == null || displayWindow == null || lp == null) return;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        int winW = (lp.width > 0) ? lp.width : displayWindow.getWidth();
        int winH = (lp.height > 0) ? lp.height : displayWindow.getHeight();
        if (winW <= 0) winW = (int) (screenW * 0.52f);
        if (winH <= 0) winH = (int) (screenH * 0.52f);

        lp.x = Math.max(0, Math.min(screenW - winW, lp.x));
        lp.y = Math.max(0, Math.min(screenH - winH, lp.y));
        try {
            if (displayWindow.isAttachedToWindow()) {
                windowManager.updateViewLayout(displayWindow, lp);
            }
        } catch (Exception ignored) {}
    }

    // ────────────────────────────────────────────────────────────────────────
    // Screenshot & Recording
    // ────────────────────────────────────────────────────────────────────────

    private void captureFloatScreenshot() {
        if (displayWindow == null) return;
        SurfaceView sv = displayWindow.getSurfaceView();
        if (sv == null) {
            Toast.makeText(this, R.string.msg_cannot_get_frame, Toast.LENGTH_SHORT).show();
            return;
        }
        ScreenCaptureUtil.captureFrame(sv, this, new ScreenCaptureUtil.CaptureCallback() {
            @Override
            public void onSuccess(String filePath) {
                Toast.makeText(FloatService.this, R.string.msg_screenshot_saved, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(String reason) {
                Toast.makeText(FloatService.this, getString(R.string.msg_screenshot_failed, reason), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void toggleFloatRecording() {
        if (screenRecorder.isRecording()) {
            screenRecorder.stop();
            stopRecordTimer();
        } else {
            if (displayWindow == null) return;
            SurfaceView sv = displayWindow.getSurfaceView();
            if (sv == null) {
                Toast.makeText(this, R.string.msg_cannot_get_frame, Toast.LENGTH_SHORT).show();
                return;
            }
            screenRecorder.start(sv, sv.getWidth(), sv.getHeight(), scrcpy, this,
                    new ScreenRecorder.RecordListener() {
                        @Override
                        public void onStarted() {
                            recordStartMs = System.currentTimeMillis();
                            startRecordTimer();
                            Toast.makeText(FloatService.this, R.string.msg_record_started, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onStopped(String filePath) {
                            stopRecordTimer();
                            Toast.makeText(FloatService.this, R.string.msg_record_saved, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String reason) {
                            stopRecordTimer();
                            Toast.makeText(FloatService.this, getString(R.string.msg_record_failed, reason), Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onTick(long elapsedSeconds) {
                            // handled by timer runnable
                        }
                    });
        }
    }

    private void startRecordTimer() {
        if (recordTimerRunnable != null) mainHandler.removeCallbacks(recordTimerRunnable);
        recordTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!screenRecorder.isRecording() || displayWindow == null) return;
                long elapsed = (System.currentTimeMillis() - recordStartMs) / 1000;
                long mm = elapsed / 60;
                long ss = elapsed % 60;
                String text = String.format(java.util.Locale.getDefault(), "%02d:%02d", mm, ss);
                displayWindow.updateRecordTimer(text);
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(recordTimerRunnable);
    }

    private void stopRecordTimer() {
        if (recordTimerRunnable != null) {
            mainHandler.removeCallbacks(recordTimerRunnable);
            recordTimerRunnable = null;
        }
        if (displayWindow != null) {
            displayWindow.updateRecordTimer(null);
        }
    }

    @Override
    public void loadNewRotation() {
        if (scrcpy == null) return;
        int[] res = scrcpy.get_remote_device_resolution();
        if (res != null && res.length >= 2 && res[0] > 0 && res[1] > 0) {
            Runnable r = () -> {
                if (displayWindow != null) {
                    displayWindow.setRemote(res[0], res[1]);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
            } else {
                mainHandler.post(r);
            }
        }
    }

    @Override
    public void onDisconnected() {
        mainHandler.post(() -> {
            Toast.makeText(FloatService.this, R.string.msg_device_disconnected, Toast.LENGTH_SHORT).show();
            stopSelf();
        });
    }

    @Override
    public void onDeviceNameResolved(String name) {
        if (name != null && !name.isEmpty()) {
            mainHandler.post(() -> {
                if (displayWindow != null) {
                    TextView tvTitle = displayWindow.findViewById(R.id.tv_title);
                    if (tvTitle != null) {
                        tvTitle.setText(name);
                    }
                }
                if (audioOnly) {
                    updateNotification(name, "Remote Speaker active");
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "FloatService onDestroy");

        // Stop any ongoing recording
        stopRecordTimer();
        if (screenRecorder.isRecording()) {
            screenRecorder.stop();
        }

        try {
            unregisterReceiver(usbDetachReceiver);
        } catch (Exception ignored) {
        }

        if (serviceBound && scrcpy != null) {
            scrcpy.StopService();
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {
            }
            serviceBound = false;
            scrcpy = null;
        }

        SendCommands.closeActiveSession();

        if (windowManager != null && displayWindow != null) {
            try {
                windowManager.removeView(displayWindow);
            } catch (Exception ignored) {
            }
            displayWindow = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
