package flutter.overlay.window.flutter_overlay_window;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

public class DismissTargetView extends View {
    private static final int NORMAL_BG = Color.parseColor("#E0101622");
    private static final int NORMAL_BORDER = Color.parseColor("#6600E5FF");
    private static final int NORMAL_ICON = Color.parseColor("#E0F7FA");

    private static final int ACTIVE_BG = Color.parseColor("#F0E53935");
    private static final int ACTIVE_BORDER = Color.parseColor("#FFFF5252");
    private static final int ACTIVE_ICON = Color.WHITE;
    private static final int ACTIVE_GLOW = Color.parseColor("#55FF1744");

    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float activeProgress = 0f;
    private boolean isActive = false;
    private ValueAnimator activeAnimator;

    public DismissTargetView(Context context) {
        super(context);
        init();
    }

    public DismissTargetView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setStyle(Paint.Style.FILL);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dpToPx(1.8f));

        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeWidth(dpToPx(2.6f));

        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(ACTIVE_GLOW);

        setVisibility(View.VISIBLE);
        setAlpha(0f);
        setTranslationY(dpToPx(60f));
        setScaleX(0.7f);
        setScaleY(0.7f);
    }

    public void setActive(boolean active) {
        if (this.isActive == active) return;
        this.isActive = active;

        if (activeAnimator != null && activeAnimator.isRunning()) {
            activeAnimator.cancel();
        }

        activeAnimator = ValueAnimator.ofFloat(activeProgress, active ? 1f : 0f);
        activeAnimator.setDuration(160);
        activeAnimator.setInterpolator(new DecelerateInterpolator());
        activeAnimator.addUpdateListener(anim -> {
            activeProgress = (float) anim.getAnimatedValue();
            invalidate();
        });
        activeAnimator.start();
    }

    public boolean isActive() {
        return isActive;
    }

    public void show() {
        if (activeAnimator != null && activeAnimator.isRunning()) {
            activeAnimator.cancel();
        }
        isActive = false;
        activeProgress = 0f;
        setVisibility(View.VISIBLE);
        setAlpha(0f);
        setTranslationY(dpToPx(60f));
        setScaleX(0.7f);
        setScaleY(0.7f);
        animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .start();
    }

    public void hide(Runnable onEnd) {
        animate()
                .alpha(0f)
                .translationY(dpToPx(60f))
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setDuration(200)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    setVisibility(View.GONE);
                    if (onEnd != null) {
                        onEnd.run();
                    }
                })
                .start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float baseRadius = dpToPx(27f);
        float currentRadius = baseRadius * (1f + 0.22f * activeProgress);

        // Draw outer glow when active
        if (activeProgress > 0.01f) {
            glowPaint.setAlpha((int) (85 * activeProgress));
            canvas.drawCircle(cx, cy, currentRadius + dpToPx(10f) * activeProgress, glowPaint);
        }

        // Background
        int currentBg = (int) argbEvaluator.evaluate(activeProgress, NORMAL_BG, ACTIVE_BG);
        bgPaint.setColor(currentBg);
        canvas.drawCircle(cx, cy, currentRadius, bgPaint);

        // Border
        int currentBorder = (int) argbEvaluator.evaluate(activeProgress, NORMAL_BORDER, ACTIVE_BORDER);
        borderPaint.setColor(currentBorder);
        borderPaint.setStrokeWidth(dpToPx(1.8f) + dpToPx(0.8f) * activeProgress);
        canvas.drawCircle(cx, cy, currentRadius, borderPaint);

        // 'X' Icon
        int currentIcon = (int) argbEvaluator.evaluate(activeProgress, NORMAL_ICON, ACTIVE_ICON);
        iconPaint.setColor(currentIcon);
        iconPaint.setStrokeWidth(dpToPx(2.6f) + dpToPx(0.4f) * activeProgress);

        float arm = dpToPx(8.5f) * (1f + 0.15f * activeProgress);
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, iconPaint);
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, iconPaint);
    }

    private float dpToPx(float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }
}
