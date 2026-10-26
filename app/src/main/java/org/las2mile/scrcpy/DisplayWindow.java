package org.las2mile.scrcpy;

import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public class DisplayWindow extends FrameLayout {
    private static final String TAG = "DisplayWindow";

    public static final int ACTION_BACK = 0;
    public static final int ACTION_HOME = 1;
    public static final int ACTION_MENU = 2;
    public static final int ACTION_ROTATE = 3;
    public static final int ACTION_POWER = 4;
    public static final int ACTION_VOLUME_UP = 5;
    public static final int ACTION_VOLUME_DOWN = 6;
    public static final int ACTION_SCREENSHOT = 7;
    public static final int ACTION_RECORD_TOGGLE = 8;

    public interface OnWindowResizeListener {
        void onWindowResize(int width, int height);
    }
    private OnWindowResizeListener onWindowResizeListener;

    public void setOnWindowResizeListener(OnWindowResizeListener listener) {
        this.onWindowResizeListener = listener;
    }

    private OnClickListener closeListener;
    private OnMoveCallback moveCallback;
    private OnActionCallback actionCallback;
    private OnTouchListener onDisplayTouchListener;
    private SurfaceReadyCallback surfaceReadyCallback;

    private float oldX;
    private float oldY;
    private float touchDownX;
    private float touchDownY;
    private boolean isDraggingHandle = false;

    private ViewGroup container;
    private SurfaceView surfaceView;
    private View handleDots;
    private View topPillScroll;
    private View bottomPillScroll;
    private ImageView actionPin;
    private View resizeHandle;
    private View hintLayout;
    private TextView tvRecordTimer;

    private int remoteW = 1080;
    private int remoteH = 1920;
    private Surface surface;
    private boolean isAudioOnly = false;
    private boolean captureButtonsEnabled = true;
    private boolean isPinned = false;
    private boolean isMinimized = false;

    // Preserved scale factor across rotations
    private float userScaleRatio = 0.52f;
    private boolean hasCustomScale = false;

    private final Runnable autoCollapseRunnable = this::collapseControls;

    public DisplayWindow(Context context) {
        super(context);
        init();
    }

    public DisplayWindow(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DisplayWindow(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClipChildren(false);
        setClipToPadding(false);
        LayoutInflater.from(getContext()).inflate(R.layout.window_display, this, true);

        container = findViewById(R.id.container);
        surfaceView = findViewById(R.id.surface);
        hintLayout = findViewById(R.id.hint_layout);
        handleDots = findViewById(R.id.handle_dots);
        topPillScroll = findViewById(R.id.top_pill_scroll);
        bottomPillScroll = findViewById(R.id.bottom_pill_scroll);
        actionPin = findViewById(R.id.action_pin);
        resizeHandle = findViewById(R.id.iv_resize);
        tvRecordTimer = findViewById(R.id.tv_record_timer);

        // Clip container to rounded corners (Xiaomi style rounded floating window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpToPx(14));
                }
            });
            setClipToOutline(true);
        }

        final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        // ────────────────────────────────────────────────────────────────────────
        // Top 3-Dots Handle: Drag to move, Tap to toggle Xiaomi-style controls
        // ────────────────────────────────────────────────────────────────────────
        if (handleDots != null) {
            handleDots.setOnTouchListener((view, motionEvent) -> {
                final int action = motionEvent.getActionMasked();
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        oldX = motionEvent.getRawX();
                        oldY = motionEvent.getRawY();
                        touchDownX = oldX;
                        touchDownY = oldY;
                        isDraggingHandle = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = motionEvent.getRawX() - oldX;
                        float dy = motionEvent.getRawY() - oldY;
                        float totalDist = (float) Math.hypot(motionEvent.getRawX() - touchDownX, motionEvent.getRawY() - touchDownY);
                        if (totalDist > touchSlop) {
                            isDraggingHandle = true;
                            if (moveCallback != null) {
                                moveCallback.onMove(dx, dy);
                            }
                        }
                        oldX = motionEvent.getRawX();
                        oldY = motionEvent.getRawY();
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDraggingHandle) {
                            toggleControls();
                        }
                        isDraggingHandle = false;
                        return true;
                }
                return false;
            });
        }

        // ────────────────────────────────────────────────────────────────────────
        // Corner resize handle (clean semi-transparent icon, drag to scale, tap passes through)
        // ────────────────────────────────────────────────────────────────────────
        if (resizeHandle != null) {
            resizeHandle.setOnTouchListener(new OnTouchListener() {
                private float startRawX;
                private float startRawY;
                private int startW;
                private boolean isDragging = false;
                private long downTime = 0;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startRawX = event.getRawX();
                            startRawY = event.getRawY();
                            startW = (container != null && container.getWidth() > 0) ? container.getWidth() : getWidth();
                            isDragging = false;
                            downTime = event.getEventTime();
                            v.setAlpha(1.0f);
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - startRawX;
                            float dy = event.getRawY() - startRawY;
                            float totalDist = (float) Math.hypot(dx, dy);
                            if (totalDist > touchSlop) {
                                isDragging = true;
                            }
                            if (isDragging) {
                                float delta = Math.abs(dx) > Math.abs(dy) ? dx : dy;
                                int targetW = startW + (int) delta;
                                applyConstrainedSize(targetW);

                                // Remember custom scale ratio relative to local screen width
                                int screenW = getScreenWidth();
                                if (screenW > 0 && container != null && container.getWidth() > 0) {
                                    userScaleRatio = (float) container.getWidth() / (float) screenW;
                                    hasCustomScale = true;
                                }
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            v.setAlpha(0.55f);
                            if (!isDragging) {
                                // Tap pass-through to game/video underneath
                                if (onDisplayTouchListener != null && surfaceView != null) {
                                    int[] loc = new int[2];
                                    surfaceView.getLocationOnScreen(loc);
                                    float surfaceX = event.getRawX() - loc[0];
                                    float surfaceY = event.getRawY() - loc[1];

                                    MotionEvent downEv = MotionEvent.obtain(
                                            downTime, downTime,
                                            MotionEvent.ACTION_DOWN, surfaceX, surfaceY, 0);
                                    MotionEvent upEv = MotionEvent.obtain(
                                            downTime, event.getEventTime(),
                                            MotionEvent.ACTION_UP, surfaceX, surfaceY, 0);

                                    onDisplayTouchListener.onTouch(surfaceView, downEv);
                                    onDisplayTouchListener.onTouch(surfaceView, upEv);

                                    downEv.recycle();
                                    upEv.recycle();
                                }
                            }
                            isDragging = false;
                            return true;

                        case MotionEvent.ACTION_CANCEL:
                            v.setAlpha(0.55f);
                            isDragging = false;
                            return true;
                    }
                    return false;
                }
            });
        }

        // Screenshot button
        View screenshotBtn = findViewById(R.id.iv_screenshot);
        if (screenshotBtn != null) {
            screenshotBtn.setOnClickListener(v -> {
                if (actionCallback != null) {
                    actionCallback.onAction(ACTION_SCREENSHOT);
                }
                resetAutoCollapseTimer();
            });
        }

        // Record button / timer
        View recordBtn = findViewById(R.id.iv_record);
        if (recordBtn != null) {
            recordBtn.setOnClickListener(v -> {
                if (actionCallback != null) {
                    actionCallback.onAction(ACTION_RECORD_TOGGLE);
                }
                resetAutoCollapseTimer();
            });
        }
        if (tvRecordTimer != null) {
            tvRecordTimer.setOnClickListener(v -> {
                if (actionCallback != null) {
                    actionCallback.onAction(ACTION_RECORD_TOGGLE);
                }
                resetAutoCollapseTimer();
            });
        }

        // Quick rotate remote device
        View rotateBtn = findViewById(R.id.iv_rotate);
        if (rotateBtn != null) {
            rotateBtn.setOnClickListener(v -> {
                if (actionCallback != null) {
                    actionCallback.onAction(ACTION_ROTATE);
                }
                resetAutoCollapseTimer();
            });
        }

        // Minimize / collapse toggle
        View miniBtn = findViewById(R.id.iv_mini);
        if (miniBtn != null) {
            miniBtn.setOnClickListener(v -> {
                if (container.getVisibility() == View.VISIBLE) {
                    container.setVisibility(View.GONE);
                    collapseControls();
                    if (resizeHandle != null) resizeHandle.setVisibility(View.GONE);
                    if (bottomPillScroll != null) bottomPillScroll.setVisibility(View.GONE);
                    isMinimized = true;
                } else {
                    container.setVisibility(View.VISIBLE);
                    if (resizeHandle != null && !isAudioOnly) resizeHandle.setVisibility(View.VISIBLE);
                    if (isPinned && bottomPillScroll != null) bottomPillScroll.setVisibility(View.VISIBLE);
                    isMinimized = false;
                }
            });
        }

        // Close button
        View closeBtn = findViewById(R.id.iv_close);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> {
                if (closeListener != null) {
                    closeListener.onClick(v);
                }
            });
        }

        // Navigation buttons
        View powerBtn = findViewById(R.id.action_power);
        if (powerBtn != null) {
            powerBtn.setOnClickListener(v -> {
                if (actionCallback != null) actionCallback.onAction(ACTION_POWER);
                resetAutoCollapseTimer();
            });
        }

        View backBtn = findViewById(R.id.action_back);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> {
                if (actionCallback != null) actionCallback.onAction(ACTION_BACK);
                resetAutoCollapseTimer();
            });
        }

        View homeBtn = findViewById(R.id.action_home);
        if (homeBtn != null) {
            homeBtn.setOnClickListener(v -> {
                if (actionCallback != null) actionCallback.onAction(ACTION_HOME);
                resetAutoCollapseTimer();
            });
        }

        View menuBtn = findViewById(R.id.action_menu);
        if (menuBtn != null) {
            menuBtn.setOnClickListener(v -> {
                if (actionCallback != null) actionCallback.onAction(ACTION_MENU);
                resetAutoCollapseTimer();
            });
        }

        View volDownBtn = findViewById(R.id.action_vol_down);
        if (volDownBtn != null) {
            volDownBtn.setOnClickListener(v -> {
                if (actionCallback != null) actionCallback.onAction(ACTION_VOLUME_DOWN);
                resetAutoCollapseTimer();
            });
        }

        View volUpBtn = findViewById(R.id.action_vol_up);
        if (volUpBtn != null) {
            volUpBtn.setOnClickListener(v -> {
                if (actionCallback != null) actionCallback.onAction(ACTION_VOLUME_UP);
                resetAutoCollapseTimer();
            });
        }

        // Pin navigation bar button
        if (actionPin != null) {
            actionPin.setOnClickListener(v -> {
                isPinned = !isPinned;
                actionPin.setAlpha(isPinned ? 1.0f : 0.45f);
                actionPin.setColorFilter(isPinned ? 0xFF00E5FF : 0xFFFFFFFF);
                if (isPinned) {
                    removeCallbacks(autoCollapseRunnable);
                    if (bottomPillScroll != null) bottomPillScroll.setVisibility(View.VISIBLE);
                } else {
                    resetAutoCollapseTimer();
                }
            });
        }

        // SurfaceView callbacks and touch forwarding
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                Log.d(TAG, "surfaceCreated: " + surface);
                if (surfaceReadyCallback != null) {
                    surfaceReadyCallback.onSurfaceReady(surface);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surface = holder.getSurface();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surface = null;
                if (surfaceReadyCallback != null) {
                    surfaceReadyCallback.onSurfaceDestroyed();
                }
            }
        });

        // Touching the video: auto-collapses top pill if not pinned, and forwards touch to remote device
        surfaceView.setOnTouchListener((view, motionEvent) -> {
            if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (topPillScroll != null && topPillScroll.getVisibility() == View.VISIBLE) {
                    topPillScroll.setVisibility(View.GONE);
                    if (!isPinned && bottomPillScroll != null) {
                        bottomPillScroll.setVisibility(View.GONE);
                    }
                }
            }
            if (onDisplayTouchListener != null) {
                return onDisplayTouchListener.onTouch(view, motionEvent);
            }
            return false;
        });
    }

    public void toggleControls() {
        if (topPillScroll == null) return;
        if (topPillScroll.getVisibility() == View.VISIBLE) {
            collapseControls();
        } else {
            expandControls();
        }
    }

    public void expandControls() {
        if (isMinimized) {
            if (container != null) container.setVisibility(View.VISIBLE);
            if (resizeHandle != null && !isAudioOnly) resizeHandle.setVisibility(View.VISIBLE);
            isMinimized = false;
        }
        if (topPillScroll != null) topPillScroll.setVisibility(View.VISIBLE);
        if (bottomPillScroll != null) bottomPillScroll.setVisibility(View.VISIBLE);
        resetAutoCollapseTimer();
    }

    public void collapseControls() {
        removeCallbacks(autoCollapseRunnable);
        if (topPillScroll != null) topPillScroll.setVisibility(View.GONE);
        if (!isPinned && bottomPillScroll != null) {
            bottomPillScroll.setVisibility(View.GONE);
        }
    }

    private void resetAutoCollapseTimer() {
        removeCallbacks(autoCollapseRunnable);
        if (!isPinned) {
            postDelayed(autoCollapseRunnable, 4000);
        }
    }

    public void setCloseListener(OnClickListener closeListener) {
        this.closeListener = closeListener;
    }

    public void setMoveCallback(OnMoveCallback moveCallback) {
        this.moveCallback = moveCallback;
    }

    public void setActionCallback(OnActionCallback actionCallback) {
        this.actionCallback = actionCallback;
    }

    public void setOnDisplayTouchListener(OnTouchListener onDisplayTouchListener) {
        this.onDisplayTouchListener = onDisplayTouchListener;
    }

    public void setSurfaceReadyCallback(SurfaceReadyCallback callback) {
        this.surfaceReadyCallback = callback;
        if (surface != null && surface.isValid() && callback != null) {
            callback.onSurfaceReady(surface);
        }
    }

    public SurfaceView getSurfaceView() {
        return surfaceView;
    }

    /**
     * Remote device resolution changed (e.g. remote rotated portrait <-> landscape)
     */
    public void setRemote(int w, int h) {
        if (w <= 0 || h <= 0) return;
        this.remoteW = w;
        this.remoteH = h;

        Runnable action = () -> {
            int screenW = getScreenWidth();
            int screenH = getScreenHeight();
            int minW = dpToPx(180);
            int targetW;

            if (hasCustomScale) {
                // Preserve user-adjusted scale ratio
                float aspect = (float) remoteW / (float) remoteH;
                targetW = (int) (screenW * userScaleRatio);
                int testH = (int) (targetW / aspect);
                if (testH > (int) (screenH * 0.85f)) {
                    targetW = (int) ((screenH * 0.85f) * aspect);
                }
                targetW = Math.max(minW, targetW);
            } else {
                if (w < h) {
                    userScaleRatio = 0.52f;
                    targetW = Math.max(minW, (int) (screenW * 0.52f));
                } else {
                    userScaleRatio = 0.68f;
                    targetW = Math.max(minW, (int) (screenW * 0.68f));
                }
            }
            applyConstrainedSize(targetW);
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
        } else {
            post(action);
        }
    }

    /**
     * Local device orientation changed (e.g. user rotated their own phone)
     */
    public void onLocalConfigurationChanged(int w, int h) {
        if (w > 0 && h > 0) {
            this.remoteW = w;
            this.remoteH = h;
        }
        Runnable action = () -> {
            int screenW = getScreenWidth();
            int screenH = getScreenHeight();
            int minW = dpToPx(180);
            float aspect = (remoteW > 0 && remoteH > 0) ? ((float) remoteW / (float) remoteH) : (9f / 16f);
            int targetW = (int) (screenW * userScaleRatio);
            int testH = (int) (targetW / aspect);
            if (testH > (int) (screenH * 0.85f)) {
                targetW = (int) ((screenH * 0.85f) * aspect);
            }
            targetW = Math.max(minW, targetW);
            applyConstrainedSize(targetW);
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
        } else {
            post(action);
        }
    }

    public void applyConstrainedSize(int targetWidth) {
        int screenW = getScreenWidth();
        int screenH = getScreenHeight();
        float aspect = (remoteW > 0 && remoteH > 0) ? ((float) remoteW / (float) remoteH) : (9f / 16f);

        int minW = dpToPx(180);
        int maxW = (int) (screenW * 0.95f);
        int clampedW = Math.max(minW, Math.min(maxW, targetWidth));
        int clampedH = (int) (clampedW / aspect);

        int maxH = (int) (screenH * 0.85f);
        if (clampedH > maxH) {
            clampedH = maxH;
            clampedW = Math.max(minW, (int) (clampedH * aspect));
        }

        ViewGroup.LayoutParams lp = container.getLayoutParams();
        if (lp != null) {
            lp.width = clampedW;
            lp.height = clampedH;
            container.setLayoutParams(lp);
        }

        ViewGroup.LayoutParams rlp = getLayoutParams();
        if (rlp != null) {
            rlp.width = clampedW;
            rlp.height = clampedH;
            setLayoutParams(rlp);
        }

        requestLayout();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            invalidateOutline();
        }

        if (onWindowResizeListener != null) {
            onWindowResizeListener.onWindowResize(clampedW, clampedH);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private int getScreenWidth() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return 1080;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int getScreenHeight() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return 1920;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    public void hideHintTip() {
        if (hintLayout != null) {
            hintLayout.setVisibility(GONE);
        }
    }

    public void showHintTip() {
        if (hintLayout != null) {
            hintLayout.setVisibility(VISIBLE);
        }
    }

    /**
     * Update the recording timer display.
     * @param timerText e.g. "00:42"; pass null or empty to stop recording UI mode.
     */
    public void updateRecordTimer(String timerText) {
        View recordBtn = findViewById(R.id.iv_record);
        boolean recording = (timerText != null && !timerText.isEmpty());
        if (recordBtn != null) {
            recordBtn.setVisibility((recording || !captureButtonsEnabled || isAudioOnly) ? GONE : VISIBLE);
        }
        if (tvRecordTimer != null) {
            tvRecordTimer.setVisibility((recording && captureButtonsEnabled && !isAudioOnly) ? VISIBLE : GONE);
            if (recording) tvRecordTimer.setText(timerText);
        }
    }

    public Surface getDisplaySurface() {
        return surface != null ? surface : surfaceView.getHolder().getSurface();
    }

    public int getSurfaceWidth() {
        return surfaceView != null && surfaceView.getWidth() > 0 ? surfaceView.getWidth() : container.getWidth();
    }

    public int getSurfaceHeight() {
        return surfaceView != null && surfaceView.getHeight() > 0 ? surfaceView.getHeight() : container.getHeight();
    }

    public void setAudioOnlyMode(boolean audioOnly) {
        this.isAudioOnly = audioOnly;
        if (container != null) {
            container.setVisibility(audioOnly ? GONE : VISIBLE);
        }
        if (resizeHandle != null) {
            resizeHandle.setVisibility(audioOnly ? GONE : VISIBLE);
        }
        applyCaptureButtonsVisibility();
    }

    public void setCaptureButtonsEnabled(boolean enabled) {
        this.captureButtonsEnabled = enabled;
        applyCaptureButtonsVisibility();
    }

    public void applyCaptureButtonsVisibility() {
        View screenshot = findViewById(R.id.iv_screenshot);
        View record = findViewById(R.id.iv_record);
        View recordTimer = findViewById(R.id.tv_record_timer);
        boolean show = captureButtonsEnabled && !isAudioOnly;
        if (screenshot != null) screenshot.setVisibility(show ? VISIBLE : GONE);
        if (record != null) {
            record.setVisibility(show && (recordTimer == null || recordTimer.getVisibility() != VISIBLE) ? VISIBLE : GONE);
        }
        if (recordTimer != null && !show) {
            recordTimer.setVisibility(GONE);
        }
    }

    public interface OnMoveCallback {
        void onMove(float x, float y);
    }

    public interface OnActionCallback {
        void onAction(int actionType);
    }

    public interface SurfaceReadyCallback {
        void onSurfaceReady(Surface surface);
        void onSurfaceDestroyed();
    }
}
