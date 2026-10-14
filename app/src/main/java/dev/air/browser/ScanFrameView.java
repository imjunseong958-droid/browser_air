package dev.air.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

public final class ScanFrameView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public ScanFrameView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    @Override protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth() * .72f, getHeight() * .38f);
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f + 16 * getResources().getDisplayMetrics().density;
        Path mask = new Path();
        mask.setFillType(Path.FillType.EVEN_ODD);
        mask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        mask.addRoundRect(left, top, left + size, top + size, 22, 22, Path.Direction.CW);
        paint.setColor(0x80223D35); paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(mask, paint);
        paint.setColor(0xFFF6F5F0); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float corner = size * .1f;
        for (int i = 0; i < 4; i++) {
            float x = i % 2 == 0 ? left : left + size;
            float y = i < 2 ? top : top + size;
            canvas.drawLine(x, y, x + (i % 2 == 0 ? corner : -corner), y, paint);
            canvas.drawLine(x, y, x, y + (i < 2 ? corner : -corner), paint);
        }
    }
}
