package org.las2mile.scrcpy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

public class FloatService extends Service implements Scrcpy.ServiceCallbacks {
    private static final String TAG = "FloatService";
    private static final String CHANNEL_ID = "scrcpy_floating_service";
    private static final int NOTIFICATION_ID = 1001;

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
                                // Screen Off Mirror: turn off the controlled device's
                                // display now that the mirror connection is up
                                scrcpy.setDisplayPower(false);
                            }
                            if (displayWindow != null) {
                                displayWindow.hideHintTip();
                                if (!audioOnly && scrcpy != null) {
                                    int[] res = scrcpy.get_remote_device_resolution();
                                    if (res != null && res.length >= 2 && res[0] > 0 && res[1] > 0) {
                                        displayWindow.setRemote(res[0], res[1]);
                                    }
                                } else if (audioOnly && scrcpy != null) {
                                    String dev = scrcpy.getDeviceName();
                                    if (dev != null && !dev.isEmpty()) {
                                        android.widget.TextView tvTitle = displayWindow.findViewById(R.id.tv_title);
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
        setupDisplay();

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
                    "scrcpy Floating Window",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Running screen mirroring in floating window");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.btn_start_float))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground with connectedDevice failed, retrying without type: " + e.getMessage());
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Exception fatal) {
                Log.e(TAG, "Failed to start foreground service", fatal);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return super.onStartCommand(intent, flags, startId);
        }

        serverAdr = intent.getStringExtra("ip");
        serverPort = intent.getIntExtra("port", 7007);
        screenWidth = intent.getIntExtra("width", intent.getIntExtra("w", 1080));
        screenHeight = intent.getIntExtra("height", intent.getIntExtra("h", 1920));
        audioOnly = intent.getBooleanExtra("audio_only", false);
        if (audioOnly) {
            audioEnabled = true;
        } else {
            audioEnabled = intent.getBooleanExtra("audio", false);
        }
        noControl = intent.getBooleanExtra("no_control", false);
        screenOff = intent.getBooleanExtra("screen_off", false);

        Log.d(TAG, "onStartCommand: server=" + serverAdr + ":" + serverPort + ", audioOnly=" + audioOnly + ", size=" + screenWidth + "x" + screenHeight);
        if (displayWindow != null) {
            if (audioOnly) {
                displayWindow.setAudioOnlyMode(true);
                if (lp != null && windowManager != null) {
                    int speakerWidth = (int) (220 * getResources().getDisplayMetrics().density);
                    lp.width = speakerWidth;
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                    try {
                        windowManager.updateViewLayout(displayWindow, lp);
                    } catch (Exception ignored) {}
                }
            } else {
                displayWindow.setRemote(screenWidth, screenHeight);
            }
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

        windowManager.addView(displayWindow, lp);

        displayWindow.setCloseListener(v -> {
            Log.d(TAG, "Floating window close clicked");
            stopSelf();
        });

        displayWindow.setMoveCallback((dx, dy) -> {
            lp.x += (int) dx;
            lp.y += (int) dy;
            try {
                if (windowManager != null && displayWindow != null) {
                    windowManager.updateViewLayout(displayWindow, lp);
                }
            } catch (Exception ignored) {
            }
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
        if (displayWindow != null && scrcpy != null) {
            int[] res = scrcpy.get_remote_device_resolution();
            if (res != null && res.length >= 2 && res[0] > 0 && res[1] > 0) {
                displayWindow.setRemote(res[0], res[1]);
            }
        }
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
        if (scrcpy == null || displayWindow == null) return;
        int[] res = scrcpy.get_remote_device_resolution();
        if (res != null && res.length >= 2 && res[0] > 0 && res[1] > 0) {
            mainHandler.post(() -> {
                if (displayWindow != null) {
                    displayWindow.setRemote(res[0], res[1]);
                }
            });
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
