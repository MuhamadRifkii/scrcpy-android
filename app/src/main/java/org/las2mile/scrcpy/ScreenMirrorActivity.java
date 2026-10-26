package org.las2mile.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.android.material.button.MaterialButton;

/**
 * Dedicated full-screen activity for screen mirroring.
 * Receives connection parameters via Intent extras and manages the Scrcpy service lifecycle.
 */
public class ScreenMirrorActivity extends Activity
        implements Scrcpy.ServiceCallbacks, SensorEventListener, SurfaceHolder.Callback {

    private static final String TAG = "ScreenMirrorActivity";

    public static final String EXTRA_SERVER_ADR = "server_adr";
    public static final String EXTRA_SERVER_PORT = "server_port";
    public static final String EXTRA_SCREEN_WIDTH = "screen_width";
    public static final String EXTRA_SCREEN_HEIGHT = "screen_height";
    public static final String EXTRA_AUDIO_ENABLED = "audio_enabled";
    public static final String EXTRA_AUDIO_ONLY = "audio_only";
    public static final String EXTRA_CAPTURE_BUTTONS = "capture_buttons";
    public static final String EXTRA_NAV = "nav";
    public static final String EXTRA_NO_CONTROL = "no_control";
    public static final String EXTRA_SCREEN_OFF = "screen_off";
    public static final String EXTRA_SYNC_ROTATION = "sync_rotation";

    private String serverAdr;
    private int serverPort = 7007;
    private int screenWidth;
    private int screenHeight;
    private boolean audioEnabled;
    private boolean audioOnly = false;
    private boolean captureButtons = true;
    private boolean nav;
    private boolean noControl;
    private boolean screenOff;
    private boolean syncRotation = true;

    private SurfaceView surfaceView;
    private Surface surface;
    private ConstraintLayout containerLayout;
    private volatile Scrcpy scrcpy;
    private volatile boolean serviceBound = false;
    private boolean isFirstBind = true;
    private long backTimestamp = 0;

    private float remoteDeviceWidth;
    private float remoteDeviceHeight;
    private volatile boolean landscape = false;

    private SensorManager sensorManager;
    private Sensor proximitySensor;

    // Screenshot & Recording
    private final ScreenRecorder screenRecorder = new ScreenRecorder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable recordTimerRunnable;
    private long recordStartMs = 0;
    private boolean recordTabDragged = false;
    private boolean screenshotTabDragged = false;

    // Pull-tab drag threshold in pixels (set in onCreate)
    private float pullTabThresholdPx = 200f;

    // ────────────────────────────────────────────────────────────────────────
    // Service connection
    // ────────────────────────────────────────────────────────────────────────

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            scrcpy = ((Scrcpy.MyServiceBinder) binder).getService();
            if (scrcpy == null) return;
            scrcpy.setServiceCallbacks(ScreenMirrorActivity.this);
            serviceBound = true;

            if (isFirstBind) {
                // Kick off connection
                scrcpy.start(surface, serverAdr, serverPort, screenHeight, screenWidth, audioEnabled, !audioOnly);

                // Background thread waits for socket, then handles result on UI thread
                new Thread(() -> {
                    int count = 60;
                    while (count > 0) {
                        Scrcpy s = scrcpy;
                        if (s == null || !serviceBound) break;
                        if (s.check_socket_connection()) break;
                        count--;
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    }
                    final int finalCount = count;
                    runOnUiThread(() -> {
                        if (finalCount == 0) {
                            // Timed out
                            stopScrcpyService();
                            Toast.makeText(ScreenMirrorActivity.this,
                                    getString(R.string.msg_connection_timeout), Toast.LENGTH_SHORT).show();
                            finish();
                        } else {
                            if (scrcpy != null) {
                                int[] res = scrcpy.get_remote_device_resolution();
                                if (res != null && res.length >= 2) {
                                    remoteDeviceWidth = res[0];
                                    remoteDeviceHeight = res[1];
                                }
                                isFirstBind = false;
                                if (screenOff) {
                                    scrcpy.setDisplayPower(false);
                                }
                                if (audioOnly) {
                                    TextView tvDev = findViewById(R.id.tv_speaker_device);
                                    if (tvDev != null && scrcpy.getDeviceName() != null && !scrcpy.getDeviceName().isEmpty()) {
                                        tvDev.setText(scrcpy.getDeviceName());
                                    }
                                }
                            }
                            if (!audioOnly) {
                                adjustSurfaceLayout();
                            }
                        }
                    });
                }, "WaitSocketConnect").start();
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                if (!audioOnly) {
                    adjustSurfaceLayout();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            scrcpy = null;
        }
    };

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Read params from Intent
        Intent i = getIntent();
        serverAdr      = i.getStringExtra(EXTRA_SERVER_ADR);
        serverPort     = i.getIntExtra(EXTRA_SERVER_PORT, 7007);
        screenWidth    = i.getIntExtra(EXTRA_SCREEN_WIDTH, 1280);
        screenHeight   = i.getIntExtra(EXTRA_SCREEN_HEIGHT, 720);
        audioEnabled   = i.getBooleanExtra(EXTRA_AUDIO_ENABLED, false);
        audioOnly      = i.getBooleanExtra(EXTRA_AUDIO_ONLY, false);
        captureButtons = i.getBooleanExtra(EXTRA_CAPTURE_BUTTONS, true);
        if (audioOnly) {
            audioEnabled = true;
        }
        nav            = i.getBooleanExtra(EXTRA_NAV, true);
        noControl      = i.getBooleanExtra(EXTRA_NO_CONTROL, false);
        screenOff      = i.getBooleanExtra(EXTRA_SCREEN_OFF, false);
        syncRotation   = i.getBooleanExtra(EXTRA_SYNC_ROTATION, true);

        setContentView(R.layout.surface);
        applyFullscreen();

        surfaceView    = findViewById(R.id.decoder_surface);
        containerLayout = findViewById(R.id.container1);

        // Pull-tab threshold: 80dp
        pullTabThresholdPx = 80 * getResources().getDisplayMetrics().density;

        // Nav bar visibility
        LinearLayout navBar = findViewById(R.id.nav_button_bar);
        if (navBar != null) {
            if (nav && !noControl) {
                navBar.setVisibility(LinearLayout.VISIBLE);
                navBar.bringToFront();
            } else {
                navBar.setVisibility(LinearLayout.GONE);
            }
        }

        if (audioOnly) {
            if (surfaceView != null) {
                surfaceView.setVisibility(View.GONE);
            }
            View speakerLayout = findViewById(R.id.layout_audio_only_speaker);
            if (speakerLayout != null) {
                speakerLayout.setVisibility(View.VISIBLE);
            }
            View tabScreenshot = findViewById(R.id.tab_screenshot);
            if (tabScreenshot != null) tabScreenshot.setVisibility(View.GONE);
            View tabRecord = findViewById(R.id.tab_record);
            if (tabRecord != null) tabRecord.setVisibility(View.GONE);
            View rotateBtn = findViewById(R.id.rotate_button);
            if (rotateBtn != null) rotateBtn.setVisibility(View.GONE);

            // Remote speaker minimize button
            View btnMinimize = findViewById(R.id.btn_speaker_minimize);
            if (btnMinimize != null) {
                btnMinimize.setOnClickListener(v -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                        Intent floatIntent = new Intent(this, FloatService.class);
                        floatIntent.putExtra("ip", serverAdr);
                        floatIntent.putExtra("port", serverPort);
                        floatIntent.putExtra("audio", true);
                        floatIntent.putExtra("audio_only", true);
                        floatIntent.putExtra("no_control", noControl);
                        floatIntent.putExtra("capture_buttons", captureButtons);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(floatIntent);
                        } else {
                            startService(floatIntent);
                        }
                        finish();
                    } else {
                        moveTaskToBack(true);
                    }
                });
            }

            // Directly start and bind Scrcpy service for audio-only mode without waiting for Surface
            Intent intent = new Intent(this, Scrcpy.class);
            startService(intent);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } else {
            // Use SurfaceHolder callback so we get surface ready event
            surfaceView.getHolder().addCallback(this);
        }

        // Proximity sensor
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }

        // Physical orientation sensor listener
        if (syncRotation) {
            orientationEventListener = new android.view.OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == ORIENTATION_UNKNOWN) return;
                    boolean isLandscape = (orientation >= 60 && orientation <= 120) || (orientation >= 240 && orientation <= 300);
                    boolean isPortrait = (orientation >= 330 || orientation <= 30) || (orientation >= 150 && orientation <= 210);

                    if (isLandscape && (lastPhysicalLandscape == null || !lastPhysicalLandscape)) {
                        lastPhysicalLandscape = true;
                        checkAndSyncRotation(true);
                    } else if (isPortrait && (lastPhysicalLandscape == null || lastPhysicalLandscape)) {
                        lastPhysicalLandscape = false;
                        checkAndSyncRotation(false);
                    }
                }
            };
            if (orientationEventListener.canDetectOrientation()) {
                orientationEventListener.enable();
            }
        }

        // Register USB detach listener to exit when USB cable is disconnected
        IntentFilter usbFilter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, usbFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbDetachReceiver, usbFilter);
        }
    }

    private final BroadcastReceiver usbDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                Log.d(TAG, "USB device detached");
                onDisconnected();
            }
        }
    };

    private android.view.OrientationEventListener orientationEventListener;
    private Boolean lastPhysicalLandscape = null;
    private long lastRotationSyncTime = 0;

    private synchronized void checkAndSyncRotation(boolean targetLandscape) {
        if (!syncRotation || scrcpy == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastRotationSyncTime < 1000) {
            return;
        }
        if (remoteDeviceWidth <= 0 || remoteDeviceHeight <= 0) return;
        boolean remoteLandscape = (remoteDeviceWidth > remoteDeviceHeight);
        if (targetLandscape != remoteLandscape) {
            lastRotationSyncTime = now;
            Log.d(TAG, "Syncing remote rotation: targetLandscape=" + targetLandscape + ", remoteLandscape=" + remoteLandscape);
            scrcpy.rotateDevice();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFullscreen();
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
        if (!isFirstBind && serviceBound && scrcpy != null) {
            if (!audioOnly) {
                scrcpy.resume();
            }
            if (screenOff) {
                // Re-assert Screen Off Mirror after a pause/resume cycle
                scrcpy.setDisplayPower(false);
            } else {
                registerProximity();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (serviceBound && scrcpy != null && !audioOnly) {
            scrcpy.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (orientationEventListener != null) {
            orientationEventListener.disable();
            orientationEventListener = null;
        }
        if (sensorManager != null) sensorManager.unregisterListener(this);
        try {
            unregisterReceiver(usbDetachReceiver);
        } catch (Exception ignored) {}
        stopScrcpyService();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean currentLandscape = (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
        checkAndSyncRotation(currentLandscape);
        if (containerLayout != null) {
            containerLayout.post(this::adjustSurfaceLayout);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // SurfaceHolder.Callback
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        // Bind service only once surface is ready
        Intent intent = new Intent(this, Scrcpy.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surface = holder.getSurface();
        if (scrcpy != null && surface != null && surface.isValid()) {
            scrcpy.setSurface(surface);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scrcpy.ServiceCallbacks
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void loadNewRotation() {
        if (scrcpy == null) return;
        int[] res = scrcpy.get_remote_device_resolution();
        if (res == null || res.length < 2) return;
        remoteDeviceWidth  = res[0];
        remoteDeviceHeight = res[1];
        landscape = (remoteDeviceWidth > remoteDeviceHeight);

        // Aspect ratio is dynamically applied to SurfaceView by ConstraintSet in adjustSurfaceLayout()
        if (containerLayout != null) {
            containerLayout.post(this::adjustSurfaceLayout);
        }
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                Toast.makeText(ScreenMirrorActivity.this, R.string.msg_device_disconnected, Toast.LENGTH_SHORT).show();
                stopScrcpyService();
                finish();
            }
        });
    }

    @Override
    public void onDeviceNameResolved(String name) {
        if (name != null && !name.isEmpty()) {
            runOnUiThread(() -> {
                TextView tvDev = findViewById(R.id.tv_speaker_device);
                if (tvDev != null) {
                    tvDev.setText(name);
                }
            });
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Touch & Key events
    // ────────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchAndNav() {
        if (!noControl && surfaceView != null) {
            surfaceView.setOnTouchListener((v, event) ->
                    scrcpy != null && scrcpy.touchevent(event, surfaceView.getWidth(), surfaceView.getHeight()));
        }

        if (nav && !noControl) {
            View powerBtn   = findViewById(R.id.power_button);
            View backBtn    = findViewById(R.id.back_button);
            View homeBtn    = findViewById(R.id.home_button);
            View switchBtn  = findViewById(R.id.appswitch_button);
            View volDownBtn = findViewById(R.id.vol_down_button);
            View volUpBtn   = findViewById(R.id.vol_up_button);
            View rotateBtn  = findViewById(R.id.rotate_button);

            if (powerBtn   != null) powerBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_POWER); });
            if (backBtn    != null) backBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_BACK); });
            if (homeBtn    != null) homeBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_HOME); });
            if (switchBtn  != null) switchBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_APP_SWITCH); });
            if (volDownBtn != null) volDownBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_VOLUME_DOWN); });
            if (volUpBtn   != null) volUpBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_VOLUME_UP); });
            if (rotateBtn  != null) rotateBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.rotateDevice(); });
        }

        setupPullTabs();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPullTabs() {
        View screenshotTab = findViewById(R.id.tab_screenshot);
        View recordTab = findViewById(R.id.tab_record);

        if (!captureButtons || audioOnly) {
            if (screenshotTab != null) screenshotTab.setVisibility(View.GONE);
            if (recordTab != null) recordTab.setVisibility(View.GONE);
            return;
        } else {
            if (screenshotTab != null) screenshotTab.setVisibility(View.VISIBLE);
            if (recordTab != null) recordTab.setVisibility(View.VISIBLE);
        }

        // ── Screenshot pull-tab (top-left) ────────────────────────────────
        if (screenshotTab != null) {
            screenshotTab.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        screenshotTabDragged = false;
                        // Animate: slightly expand on touch down
                        v.animate().scaleX(1.08f).scaleY(1.08f).alpha(1.0f).setDuration(120).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getX();
                        float dy = event.getY();
                        // Progress-based scale: grow as user drags toward threshold
                        float progress = Math.min(1.0f,
                                Math.max(dx, 0) / pullTabThresholdPx);
                        v.animate()
                                .scaleX(1.08f + 0.15f * progress)
                                .scaleY(1.08f + 0.15f * progress)
                                .alpha(0.82f + 0.18f * progress)
                                .setDuration(0).start();
                        // Trigger when dragged diagonally right-down
                        if (dx > pullTabThresholdPx && dy > pullTabThresholdPx / 2) {
                            if (!screenshotTabDragged) {
                                screenshotTabDragged = true;
                                captureFullscreenScreenshot();
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        screenshotTabDragged = false;
                        // Animate back to resting state
                        v.animate().scaleX(1.0f).scaleY(1.0f).alpha(0.82f).setDuration(200).start();
                        return true;
                }
                return false;
            });
        }

        // ── Record pull-tab (top-right) ───────────────────────────────────
        if (recordTab != null) {
            recordTab.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        recordTabDragged = false;
                        v.animate().scaleX(1.08f).scaleY(1.08f).alpha(1.0f).setDuration(120).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getX();
                        float dy = event.getY();
                        // dx is negative when dragging left from the right-anchored tab
                        float prog = Math.min(1.0f, Math.max(-dx, 0) / pullTabThresholdPx);
                        v.animate()
                                .scaleX(1.08f + 0.15f * prog)
                                .scaleY(1.08f + 0.15f * prog)
                                .alpha(0.82f + 0.18f * prog)
                                .setDuration(0).start();
                        if (-dx > pullTabThresholdPx && dy > pullTabThresholdPx / 2) {
                            if (!recordTabDragged) {
                                recordTabDragged = true;
                                handleRecordPullTab();
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        recordTabDragged = false;
                        v.animate().scaleX(1.0f).scaleY(1.0f).alpha(0.82f).setDuration(200).start();
                        return true;
                }
                return false;
            });
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Forward D-pad / media keys to the remote device.
        // Note: Volume keys are intentionally NOT forwarded here — use the on-screen
        // volume buttons in the navigation bar instead, so physical keys adjust local volume.
        if (scrcpy != null && serviceBound) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    scrcpy.sendKeyevent(keyCode);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        long now = SystemClock.uptimeMillis();
        if (backTimestamp == 0 || now > backTimestamp + 2000) {
            backTimestamp = now;
            Toast.makeText(this, getString(R.string.msg_press_again_exit), Toast.LENGTH_SHORT).show();
        } else {
            backTimestamp = 0;
            stopScrcpyService();
            finish();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Sensor
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Screen Off Mirror takes precedence: while it is enabled, the controlled
        // device's display must stay off, so proximity events must not power it on
        if (screenOff) return;
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            Scrcpy s = scrcpy;
            if (serviceBound && s != null) {
                try {
                    s.setDisplayPower(event.values[0] != 0);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private void applyFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Updates the SurfaceView's dimension ratio via ConstraintSet so ConstraintLayout
     * automatically letterboxes/pillarboxes the video — no manual padding needed.
     * Works correctly in both portrait and landscape.
     */
    private void adjustSurfaceLayout() {
        setupTouchAndNav();

        View navBar = findViewById(R.id.nav_button_bar);
        if (navBar != null) {
            navBar.setVisibility(nav && !noControl ? View.VISIBLE : View.GONE);
            navBar.bringToFront();
        }

        if (!(containerLayout instanceof ConstraintLayout)) return;
        if (remoteDeviceWidth <= 0 || remoteDeviceHeight <= 0) return;

        if (containerLayout.getWidth() <= 0 || containerLayout.getHeight() <= 0) {
            containerLayout.post(this::adjustSurfaceLayout);
            return;
        }

        float availableW = containerLayout.getWidth();
        float navH = (navBar != null && navBar.getVisibility() == View.VISIBLE) ? navBar.getHeight() : 0;
        if (navH <= 0 && nav && !noControl) {
            navH = 52 * getResources().getDisplayMetrics().density;
        }
        float availableH = Math.max(1f, containerLayout.getHeight() - navH);

        float containerRatio = availableW / availableH;
        float remoteRatio = remoteDeviceWidth / remoteDeviceHeight;

        // When remote screen is wider than available container space, constrain height by width ("H,W:H")
        // When remote screen is taller than available container space, constrain width by height ("W,W:H")
        String ratio;
        if (remoteRatio > containerRatio) {
            ratio = "H," + (int) remoteDeviceWidth + ":" + (int) remoteDeviceHeight;
        } else {
            ratio = "W," + (int) remoteDeviceWidth + ":" + (int) remoteDeviceHeight;
        }

        ConstraintLayout cl = (ConstraintLayout) containerLayout;
        androidx.constraintlayout.widget.ConstraintSet cs = new androidx.constraintlayout.widget.ConstraintSet();
        cs.clone(cl);
        cs.setDimensionRatio(R.id.decoder_surface, ratio);
        cs.constrainedWidth(R.id.decoder_surface, true);
        cs.constrainedHeight(R.id.decoder_surface, true);
        cs.applyTo(cl);

        if (navBar != null) {
            navBar.bringToFront();
        }
    }

    private void registerProximity() {
        if (sensorManager != null && proximitySensor != null) {
            sensorManager.unregisterListener(this);
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void stopScrcpyService() {
        // Stop any ongoing recording
        stopFullscreenRecordTimer();
        if (screenRecorder.isRecording()) {
            screenRecorder.stop();
        }

        if (serviceBound && scrcpy != null) {
            scrcpy.StopService();
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceBound = false;
            scrcpy = null;
        }
        SendCommands.closeActiveSession();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Screenshot & Recording (Fullscreen)
    // ────────────────────────────────────────────────────────────────────────

    private void captureFullscreenScreenshot() {
        if (surfaceView == null) return;
        ScreenCaptureUtil.captureFrame(surfaceView, this, new ScreenCaptureUtil.CaptureCallback() {
            @Override
            public void onSuccess(String filePath) {
                Toast.makeText(ScreenMirrorActivity.this,
                        R.string.msg_screenshot_saved, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(String reason) {
                Toast.makeText(ScreenMirrorActivity.this,
                        getString(R.string.msg_screenshot_failed, reason), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleRecordPullTab() {
        if (isFinishing() || isDestroyed()) return;
        if (screenRecorder.isRecording()) {
            // Confirm stop
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_record_stop_title)
                    .setMessage(R.string.dialog_record_stop_msg)
                    .setPositiveButton(R.string.dialog_btn_stop_record, (d, w) -> stopFullscreenRecording())
                    .setNegativeButton(R.string.dialog_btn_continue_record, null)
                    .show();
        } else {
            // Confirm start
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_record_start_title)
                    .setMessage(R.string.dialog_record_start_msg)
                    .setPositiveButton(R.string.dialog_btn_start_record, (d, w) -> startFullscreenRecording())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void startFullscreenRecording() {
        if (surfaceView == null) return;
        screenRecorder.start(surfaceView, surfaceView.getWidth(), surfaceView.getHeight(),
                scrcpy, this, new ScreenRecorder.RecordListener() {
                    @Override
                    public void onStarted() {
                        recordStartMs = System.currentTimeMillis();
                        startFullscreenRecordTimer();
                        Toast.makeText(ScreenMirrorActivity.this, R.string.msg_record_started, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onStopped(String filePath) {
                        stopFullscreenRecordTimer();
                        Toast.makeText(ScreenMirrorActivity.this, R.string.msg_record_saved, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String reason) {
                        stopFullscreenRecordTimer();
                        Toast.makeText(ScreenMirrorActivity.this,
                                getString(R.string.msg_record_failed, reason), Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onTick(long elapsedSeconds) {
                        // handled by timer runnable
                    }
                });
    }

    private void stopFullscreenRecording() {
        screenRecorder.stop();
        stopFullscreenRecordTimer();
    }

    private void startFullscreenRecordTimer() {
        if (recordTimerRunnable != null) mainHandler.removeCallbacks(recordTimerRunnable);
        recordTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!screenRecorder.isRecording()) return;
                long elapsed = (System.currentTimeMillis() - recordStartMs) / 1000;
                long mm = elapsed / 60;
                long ss = elapsed % 60;
                String text = String.format(java.util.Locale.getDefault(), "%02d:%02d", mm, ss);
                updateRecordTabUI(text);
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(recordTimerRunnable);
    }

    private void stopFullscreenRecordTimer() {
        if (recordTimerRunnable != null) {
            mainHandler.removeCallbacks(recordTimerRunnable);
            recordTimerRunnable = null;
        }
        updateRecordTabUI(null);
    }

    private void updateRecordTabUI(String timerText) {
        ImageView ivRecord = findViewById(R.id.iv_tab_record);
        TextView tvTimer   = findViewById(R.id.tv_tab_record_timer);
        TextView tvHint    = findViewById(R.id.tv_tab_record_hint);
        boolean recording  = (timerText != null && !timerText.isEmpty());

        if (ivRecord != null) ivRecord.setVisibility(recording ? View.GONE : View.VISIBLE);
        if (tvTimer  != null) {
            tvTimer.setVisibility(recording ? View.VISIBLE : View.GONE);
            if (recording) tvTimer.setText(timerText);
        }
        // Keep the hint arrow visible always (it shows ⇙ during idle, still present during recording)
        if (tvHint != null) tvHint.setVisibility(View.VISIBLE);
    }
}
