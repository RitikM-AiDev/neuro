package flutter.overlay.window.flutter_overlay_window;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

public class OverlayService extends Service implements View.OnTouchListener {
    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;
    private static final int LONG_PRESS_TIMEOUT_MS = 320;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

    private static OverlayService instance;
    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel = new MethodChannel(
            FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
            OverlayConstants.OVERLAY_TAG
    );
    private BasicMessageChannel<Object> overlayMessageChannel = new BasicMessageChannel<>(
            FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(),
            OverlayConstants.MESSENGER_TAG,
            JSONMessageCodec.INSTANCE
    );
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private Point szWindow = new Point();
    private WindowManager.LayoutParams bubbleParams;
    private DismissTargetView dismissTargetView;
    private WindowManager.LayoutParams dismissParams;

    private final Handler mHandler = new Handler();
    private boolean isLongPressed = false;
    private boolean isDragging = false;
    private boolean isHoveringDismiss = false;
    private float initialTouchRawX, initialTouchRawY;
    private int initialParamsX, initialParamsY;
    private ValueAnimator snapAnimator;

    private final Runnable mLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning || flutterView == null) return;
            isLongPressed = true;
            isDragging = true;
            triggerHapticFeedback();

            // Bubble lift animation
            flutterView.animate().scaleX(1.08f).scaleY(1.08f).setDuration(160).start();

            // Cancel any pending tap/long-press gestures in Flutter
            long now = SystemClock.uptimeMillis();
            MotionEvent cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            flutterView.onTouchEvent(cancelEvent);
            cancelEvent.recycle();

            // Display circular Close (X) drop target at bottom-center
            if (dismissTargetView != null) {
                dismissTargetView.show();
            }
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverLay", "Destroying the overlay window service");
        if (snapAnimator != null && snapAnimator.isRunning()) {
            snapAnimator.cancel();
        }
        mHandler.removeCallbacks(mLongPressRunnable);
        if (windowManager != null) {
            if (dismissTargetView != null) {
                try {
                    windowManager.removeView(dismissTargetView);
                } catch (Exception ignored) {}
                dismissTargetView = null;
            }
            if (flutterView != null) {
                try {
                    windowManager.removeView(flutterView);
                } catch (Exception ignored) {}
                flutterView.detachFromFlutterEngine();
                flutterView = null;
            }
            windowManager = null;
        }
        isRunning = false;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
        }
        instance = null;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mResources = getApplicationContext().getResources();
        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);

        if (isCloseWindow) {
            cleanupAndDismiss();
            return START_STICKY;
        }

        if (windowManager != null) {
            cleanupAndDismiss();
        }

        isRunning = true;
        Log.d("onStartCommand", "Service started");
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        engine.getLifecycleChannel().appIsResumed();
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG));
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);

        flutterChannel.setMethodCallHandler((call, result) -> {
            if (call.method.equals("updateFlag")) {
                String flag = call.argument("flag").toString();
                updateOverlayFlag(result, flag);
            } else if (call.method.equals("updateOverlayPosition")) {
                int x = call.<Integer>argument("x");
                int y = call.<Integer>argument("y");
                moveOverlay(x, y, result);
            } else if (call.method.equals("resizeOverlay")) {
                int width = call.argument("width");
                int height = call.argument("height");
                boolean enableDrag = call.argument("enableDrag");
                resizeOverlay(width, height, enableDrag, result);
            } else if (call.method.equals("closeOverlay")) {
                cleanupAndDismiss();
                result.success(true);
            }
        });

        overlayMessageChannel.setMessageHandler((message, reply) -> {
            WindowSetup.messenger.send(message);
        });

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        updateScreenDimensions();

        int bubbleWidth = (WindowSetup.width == -1999 || WindowSetup.width == -1) ? szWindow.x : dpToPx(WindowSetup.width);
        int bubbleHeight = (WindowSetup.height == -1999 || WindowSetup.height == -1) ? screenHeight() : dpToPx(WindowSetup.height);

        bubbleParams = new WindowManager.LayoutParams(
                bubbleWidth,
                bubbleHeight,
                0,
                0,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            bubbleParams.alpha = 0.8f;
        }

        bubbleParams.gravity = Gravity.TOP | Gravity.LEFT;

        // Position bubble: start always at bottom-center of screen
        bubbleParams.x = (szWindow.x - bubbleWidth) / 2;
        bubbleParams.y = szWindow.y - navigationBarHeightPx() - bubbleHeight - dpToPx(35);

        flutterView.setOnTouchListener(this);
        windowManager.addView(flutterView, bubbleParams);

        // Add circular Dismiss (X) Drop Target View at bottom center
        int targetSize = dpToPx(130);
        dismissParams = new WindowManager.LayoutParams(
                targetSize,
                targetSize,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        dismissParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dismissParams.y = dpToPx(35);

        dismissTargetView = new DismissTargetView(getApplicationContext());
        windowManager.addView(dismissTargetView, dismissParams);

        return START_STICKY;
    }

    private void updateScreenDimensions() {
        if (windowManager != null) {
            DisplayMetrics displaymetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(displaymetrics);
            szWindow.set(displaymetrics.widthPixels, displaymetrics.heightPixels);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenDimensions();
        if (bubbleParams != null && windowManager != null && flutterView != null) {
            int bubbleWidth = (WindowSetup.width == -1999 || WindowSetup.width == -1) ? szWindow.x : dpToPx(WindowSetup.width);
            int bubbleHeight = (WindowSetup.height == -1999 || WindowSetup.height == -1) ? szWindow.y : dpToPx(WindowSetup.height);
            int minX = 0;
            int maxX = szWindow.x - bubbleWidth;
            int minY = statusBarHeightPx();
            int maxY = szWindow.y - navigationBarHeightPx() - bubbleHeight;
            bubbleParams.x = Math.max(minX, Math.min(maxX, bubbleParams.x));
            bubbleParams.y = Math.max(minY, Math.min(maxY, bubbleParams.y));
            windowManager.updateViewLayout(flutterView, bubbleParams);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        return inPortrait() ?
                dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
                :
                dm.heightPixels + statusBarHeightPx();
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");
            if (statusBarHeightId > 0) {
                mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
            } else {
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
        }
        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");
            if (navBarHeightId > 0) {
                mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
            } else {
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
        }
        return mNavigationBarHeight;
    }

    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.flags = WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = 0.8f;
            } else {
                params.alpha = 1;
            }
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
            params.height = (height != 1999 || height != -1) ? dpToPx(height) : height;
            WindowSetup.enableDrag = enableDrag;
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        if (windowManager != null && bubbleParams != null) {
            bubbleParams.x = (x == -1999 || x == -1) ? -1 : dpToPx(x);
            bubbleParams.y = dpToPx(y);
            windowManager.updateViewLayout(flutterView, bubbleParams);
            if (result != null) result.success(true);
        } else {
            if (result != null) result.success(false);
        }
    }

    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null && instance.bubbleParams != null) {
            Map<String, Double> position = new HashMap<>();
            position.put("x", instance.pxToDp(instance.bubbleParams.x));
            position.put("y", instance.pxToDp(instance.bubbleParams.y));
            return position;
        }
        return null;
    }

    public static boolean moveOverlay(int x, int y) {
        if (instance != null && instance.flutterView != null && instance.windowManager != null && instance.bubbleParams != null) {
            instance.bubbleParams.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
            instance.bubbleParams.y = instance.dpToPx(y);
            instance.windowManager.updateViewLayout(instance.flutterView, instance.bubbleParams);
            return true;
        }
        return false;
    }

    @Override
    public void onCreate() {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
        int pendingFlags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingFlags = PendingIntent.FLAG_IMMUTABLE;
        } else {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, pendingFlags);
        final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(OverlayConstants.NOTIFICATION_ID, notification, serviceType);
        } else {
            startForeground(OverlayConstants.NOTIFICATION_ID, notification);
        }
        instance = this;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType, getApplicationContext().getPackageName());
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                mResources.getDisplayMetrics()
        );
    }

    private double pxToDp(int px) {
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private void triggerHapticFeedback() {
        try {
            if (flutterView != null) {
                flutterView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(35);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager == null || flutterView == null || !WindowSetup.enableDrag || bubbleParams == null) {
            return false;
        }

        int bubbleWidth = (WindowSetup.width == -1999 || WindowSetup.width == -1) ? szWindow.x : dpToPx(WindowSetup.width);
        int bubbleHeight = (WindowSetup.height == -1999 || WindowSetup.height == -1) ? szWindow.y : dpToPx(WindowSetup.height);
        int touchSlop = ViewConfiguration.get(getApplicationContext()).getScaledTouchSlop();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (snapAnimator != null && snapAnimator.isRunning()) {
                    snapAnimator.cancel();
                }
                isLongPressed = false;
                isDragging = false;
                isHoveringDismiss = false;
                initialTouchRawX = event.getRawX();
                initialTouchRawY = event.getRawY();
                initialParamsX = bubbleParams.x;
                initialParamsY = bubbleParams.y;

                mHandler.postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
                flutterView.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchRawX;
                float dy = event.getRawY() - initialTouchRawY;

                if (!isDragging) {
                    // Start dragging immediately once finger moves beyond touch slop!
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        isDragging = true;
                        mHandler.removeCallbacks(mLongPressRunnable);
                        triggerHapticFeedback();
                        flutterView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();

                        // Cancel touch in Flutter so buttons inside don't trigger onTap
                        long now = SystemClock.uptimeMillis();
                        MotionEvent cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                        flutterView.onTouchEvent(cancelEvent);
                        cancelEvent.recycle();

                        if (dismissTargetView != null) {
                            dismissTargetView.show();
                        }
                    } else {
                        // Tiny movement within touch slop (jitter)
                        return true;
                    }
                }

                // In drag mode: smoothly follow finger across screen
                int targetX = (int) (initialParamsX + dx);
                int targetY = (int) (initialParamsY + dy);

                // Screen boundary clamping
                int minX = 0;
                int maxX = szWindow.x - bubbleWidth;
                int minY = statusBarHeightPx();
                int maxY = szWindow.y - navigationBarHeightPx() - bubbleHeight;

                bubbleParams.x = Math.max(minX, Math.min(maxX, targetX));
                bubbleParams.y = Math.max(minY, Math.min(maxY, targetY));

                // Check distance to close drop target at bottom center
                int bubbleCenterX = bubbleParams.x + bubbleWidth / 2;
                int bubbleCenterY = bubbleParams.y + bubbleHeight / 2;
                int targetCenterX = szWindow.x / 2;
                int targetCenterY = szWindow.y - navigationBarHeightPx() - dpToPx(35) - dpToPx(65);

                double dist = Math.hypot(bubbleCenterX - targetCenterX, bubbleCenterY - targetCenterY);
                boolean near = dist < dpToPx(85);

                if (near && !isHoveringDismiss) {
                    isHoveringDismiss = true;
                    if (dismissTargetView != null) {
                        dismissTargetView.setActive(true);
                    }
                    triggerHapticFeedback();
                } else if (!near && isHoveringDismiss) {
                    isHoveringDismiss = false;
                    if (dismissTargetView != null) {
                        dismissTargetView.setActive(false);
                    }
                }

                // Magnetic pull when hovering close target
                if (isHoveringDismiss) {
                    bubbleParams.x = (int) (bubbleParams.x + (targetCenterX - bubbleCenterX) * 0.25f);
                    bubbleParams.y = (int) (bubbleParams.y + (targetCenterY - bubbleCenterY) * 0.25f);
                }

                windowManager.updateViewLayout(flutterView, bubbleParams);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mLongPressRunnable);
                flutterView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).start();

                if (isDragging) {
                    if (isHoveringDismiss) {
                        // Released inside close target: DISMISS!
                        triggerHapticFeedback();
                        flutterView.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(180).start();
                        if (dismissTargetView != null) {
                            dismissTargetView.hide(this::cleanupAndDismiss);
                        } else {
                            cleanupAndDismiss();
                        }
                    } else {
                        // Released outside close target: hide target & snap bubble to nearest screen edge
                        if (dismissTargetView != null) {
                            dismissTargetView.hide(null);
                        }
                        snapBubbleToEdge(bubbleWidth, bubbleHeight);
                    }
                    isDragging = false;
                    isLongPressed = false;
                    isHoveringDismiss = false;
                    return true;
                } else {
                    // Normal tap / interaction
                    flutterView.onTouchEvent(event);
                    return true;
                }
        }
        return false;
    }

    private void snapBubbleToEdge(int bubbleWidth, int bubbleHeight) {
        if (windowManager == null || flutterView == null || bubbleParams == null) return;
        int bubbleCenterX = bubbleParams.x + bubbleWidth / 2;
        int edgeMargin = dpToPx(8);
        int destX = (bubbleCenterX < szWindow.x / 2) ? edgeMargin : (szWindow.x - bubbleWidth - edgeMargin);

        if (snapAnimator != null && snapAnimator.isRunning()) {
            snapAnimator.cancel();
        }
        snapAnimator = ValueAnimator.ofInt(bubbleParams.x, destX);
        snapAnimator.setDuration(350);
        snapAnimator.setInterpolator(new OvershootInterpolator(1.15f));
        snapAnimator.addUpdateListener(anim -> {
            if (windowManager != null && flutterView != null && bubbleParams != null) {
                bubbleParams.x = (int) anim.getAnimatedValue();
                windowManager.updateViewLayout(flutterView, bubbleParams);
            }
        });
        snapAnimator.start();
    }

    private void cleanupAndDismiss() {
        if (WindowSetup.messenger != null) {
            WindowSetup.messenger.send("close");
        }
        if (windowManager != null) {
            if (dismissTargetView != null) {
                try {
                    windowManager.removeView(dismissTargetView);
                } catch (Exception ignored) {}
                dismissTargetView = null;
            }
            if (flutterView != null) {
                try {
                    windowManager.removeView(flutterView);
                } catch (Exception ignored) {}
                flutterView.detachFromFlutterEngine();
                flutterView = null;
            }
            windowManager = null;
        }
        isRunning = false;
        stopSelf();
    }
}
