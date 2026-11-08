package dev.air.browser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.PanZoomController;

/**
 * Reports a downward pull that starts while the page is at its top, for pull-to-refresh. Every touch still
 * goes to the page. Gecko says at the start of each touch whether the page can scroll up, whether the page's
 * own script took the touch, and whether the page allows overscrolling; only a touch the browser would
 * overscroll at the top counts, so maps, chats and zoomed-in pages are left alone.
 */
public final class AirGeckoView extends GeckoView {
    public interface PullListener {
        /** Whether a pull may start now; asked when a finger touches the page. */
        boolean canPull();
        /** The finger is this many pixels below where it started. */
        void onPull(float distance);
        /** The finger lifted (refresh is true when it was pulled far enough) or the pull was cancelled. */
        void onPullEnd(boolean refresh);
    }

    private final int touchSlop;
    private final float refreshDistance;
    private PullListener listener;
    private int gesture;
    private boolean atTop;
    private boolean pulling;
    private float startX;
    private float startY;
    private float distance;

    public AirGeckoView(Context context) { this(context, null); }

    public AirGeckoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        refreshDistance = 120 * context.getResources().getDisplayMetrics().density;
    }

    public void setPullListener(PullListener listener) { this.listener = listener; }

    public float refreshDistance() { return refreshDistance; }

    @SuppressLint("ClickableViewAccessibility") // The page handles its own clicks, as GeckoView does.
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                endPull(false);
                atTop = false;
                final int id = ++gesture;
                startX = event.getX();
                startY = event.getY();
                if (listener == null || !listener.canPull()) return super.onTouchEvent(event);
                onTouchEventForDetailResult(event).accept(detail -> {
                    if (id == gesture && detail != null) atTop = canOverscrollTop(detail);
                });
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                atTop = false; // Two fingers zoom; they never refresh.
                endPull(false);
                break;
            case MotionEvent.ACTION_MOVE:
                if (atTop) {
                    float dy = event.getY() - startY;
                    float dx = Math.abs(event.getX() - startX);
                    if (!pulling && dy > touchSlop && dy > dx * 1.5f) pulling = true;
                    if (pulling) {
                        distance = Math.max(0f, dy);
                        listener.onPull(distance);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                endPull(distance >= refreshDistance);
                atTop = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                endPull(false);
                atTop = false;
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private void endPull(boolean refresh) {
        if (!pulling) return;
        pulling = false;
        distance = 0f;
        if (listener != null) listener.onPullEnd(refresh);
    }

    private static boolean canOverscrollTop(PanZoomController.InputResultDetail detail) {
        return detail.handledResult() != PanZoomController.INPUT_RESULT_HANDLED_CONTENT
                && (detail.scrollableDirections() & PanZoomController.SCROLLABLE_FLAG_TOP) == 0
                && (detail.overscrollDirections() & PanZoomController.OVERSCROLL_FLAG_VERTICAL) != 0;
    }
}
