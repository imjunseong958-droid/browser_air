package dev.air.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

/** A quiet, static illustration. No animation, rewards, or attention prompts. */
public final class AirArtworkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public AirArtworkView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getHeight() < 48) return;
        float scale = Math.min(getWidth() / 320f, getHeight() / 240f);
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getContext().getColor(R.color.sage));
        canvas.drawOval(-72, -84, 72, 76, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.15f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(getContext().getColor(R.color.muted));
        Path arch = new Path();
        arch.moveTo(-46, 58); arch.lineTo(-46, -18);
        arch.cubicTo(-46, -78, 46, -78, 46, -18); arch.lineTo(46, 58);
        canvas.drawPath(arch, paint);
        canvas.drawLine(-79, 58, 82, 58, paint);
        paint.setColor(getContext().getColor(R.color.line));
        Path breeze = new Path();
        breeze.moveTo(-136, 14); breeze.cubicTo(-88, -17, -86, 28, -62, 9);
        canvas.drawPath(breeze, paint);
        breeze.reset(); breeze.moveTo(59, -27); breeze.cubicTo(100, -46, 104, -6, 139, -24);
        canvas.drawPath(breeze, paint);
        paint.setColor(getContext().getColor(R.color.ink));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0, 19, 2.6f, paint);
        canvas.restore();
    }
}
