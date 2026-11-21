package os.nyro.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

final class Visuals {
    static final int BG = 0xff11131a;
    static final int CARD = 0xff1a1d28;
    static final int PANEL = 0xff171923;
    static final int LINE = 0xff323047;
    static final int ACCENT = 0xffb29aff;
    static final int TEXT = 0xffefedf6;
    static final int MUTED = 0xffaea9c0;
    static final int SUCCESS = 0xff9ed7bd;

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable surface(Context context, int fill, int radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        if (border != Color.TRANSPARENT) shape.setStroke(dp(context, 1), border);
        return shape;
    }

    static Drawable pressable(Context context, int fill, int radius, int border) {
        return new RippleDrawable(ColorStateList.valueOf(0x33b29aff),
                surface(context, fill, radius, border), surface(context, Color.WHITE, radius, Color.TRANSPARENT));
    }

    static final class Wallpaper extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private Shader glow;
        private boolean home;

        Wallpaper(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void showHome(boolean enabled) {
            home = enabled;
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            if (width > 0) {
                glow = new RadialGradient(width * .92f, height * .24f, width * .86f,
                        new int[]{0xff31264c, 0xff1d192e, BG}, new float[]{0f, .52f, 1f},
                        Shader.TileMode.CLAMP);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(BG);
            if (!home || glow == null) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(glow);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            canvas.save();
            canvas.rotate(-22, getWidth() * .92f, getHeight() * .24f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(getContext(), 1));
            for (int i = 0; i < 4; i++) {
                float x = getWidth() * (.42f + .15f * i);
                float y = getWidth() * (.53f + .13f * i);
                float cx = getWidth() * .92f;
                float cy = getHeight() * .24f;
                paint.setColor(Color.argb(31 - i * 5, 178, 154, 255));
                canvas.drawOval(cx - x, cy - y, cx + x, cy + y, paint);
            }
            canvas.restore();
        }
    }

    static final class Glyph extends View {
        private final String type;
        private final int color;
        private final float size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(Context context, String type, int color, float size) {
            super(context);
            this.type = type;
            this.color = color;
            this.size = size;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            float pixels = dp(getContext(), size);
            canvas.save();
            canvas.translate((getWidth() - pixels) / 2, (getHeight() - pixels) / 2);
            canvas.scale(pixels / 24f, pixels / 24f);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.65f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            switch (type) {
                case "search" -> { canvas.drawCircle(10, 10, 5.6f, paint); line(canvas, 14.3f,14.3f,19.4f,19.4f); }
                case "left" -> line(canvas, 14, 5, 7, 12, 14, 19);
                case "right" -> line(canvas, 9, 5, 16, 12, 9, 19);
                case "up" -> line(canvas, 5, 15, 12, 8, 19, 15);
                case "down" -> line(canvas, 5, 9, 12, 16, 19, 9);
                case "close" -> { line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18); }
                case "settings" -> {
                    line(canvas, 4, 6, 20, 6);
                    line(canvas, 4, 12, 20, 12);
                    line(canvas, 4, 18, 20, 18);
                    canvas.drawCircle(9, 6, 2, paint);
                    canvas.drawCircle(16, 12, 2, paint);
                    canvas.drawCircle(9, 18, 2, paint);
                }
                case "star" -> line(canvas, 12,3, 14.7f,8.6f, 21,9.4f, 16.4f,13.9f,
                        17.5f,20, 12,17, 6.5f,20, 7.6f,13.9f, 3,9.4f, 9.3f,8.6f, 12,3);
                case "eye", "eye-off" -> {
                    canvas.drawOval(3, 7, 21, 17, paint);
                    canvas.drawCircle(12, 12, 2.8f, paint);
                    if (type.equals("eye-off")) line(canvas, 5, 4, 19, 20);
                }
                case "info" -> {
                    canvas.drawCircle(12, 12, 8.5f, paint);
                    line(canvas, 12, 11, 12, 16);
                    canvas.drawPoint(12, 8, paint);
                }
                case "trash" -> {
                    line(canvas, 4, 6, 20, 6);
                    line(canvas, 6, 6, 7.5f, 20, 16.5f, 20, 18, 6);
                    line(canvas, 9, 6, 9, 3.5f, 15, 3.5f, 15, 6);
                    line(canvas, 10.5f, 9.5f, 10.5f, 16.5f);
                    line(canvas, 13.5f, 9.5f, 13.5f, 16.5f);
                }
                case "more" -> {
                    paint.setStyle(Paint.Style.FILL);
                    for (int x = 5; x <= 19; x += 7) canvas.drawCircle(x, 12, 1.6f, paint);
                }
                case "battery" -> {
                    canvas.drawRoundRect(3, 7, 19, 17, 2, 2, paint);
                    line(canvas, 22, 10.5f, 22, 13.5f);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(5.7f, 9.7f, 15.5f, 14.3f, .8f, .8f, paint);
                }
                case "grid" -> {
                    for (int y = 4; y <= 13; y += 9)
                        for (int x = 4; x <= 13; x += 9) canvas.drawRoundRect(x, y, x+6, y+6, 1.5f, 1.5f, paint);
                }
                case "chip" -> {
                    canvas.drawRoundRect(6, 6, 18, 18, 2, 2, paint);
                    canvas.drawRoundRect(9, 9, 15, 15, 1, 1, paint);
                    for (int y = 9; y <= 15; y += 6) {
                        line(canvas, 3, y, 6, y); line(canvas, 18, y, 21, y);
                        line(canvas, y, 3, y, 6); line(canvas, y, 18, y, 21);
                    }
                }
                case "disk" -> {
                    canvas.drawRoundRect(3.5f, 5, 20.5f, 19, 3, 3, paint);
                    line(canvas, 4, 14, 20, 14); line(canvas, 15.5f, 16.7f, 17, 16.7f);
                }
                case "network" -> {
                    canvas.drawArc(2, 6, 22, 24, 227, 86, false, paint);
                    canvas.drawArc(6, 10, 18, 22, 225, 90, false, paint);
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 17, 1.55f, paint);
                }
                case "globe" -> { canvas.drawCircle(12,12,8,paint); canvas.drawOval(8.5f,4,15.5f,20,paint); line(canvas,4,12,20,12); }
                case "refresh" -> { canvas.drawArc(4,4,20,20,35,300,false,paint); line(canvas,20,5,20,10,15,10); }
                case "link" -> { line(canvas,7,17,17,7); line(canvas,8,7,17,7,17,16); }
                case "code" -> { line(canvas,8,7,3,12,8,17); line(canvas,16,7,21,12,16,17); line(canvas,14,6,10,18); }
                default -> canvas.drawCircle(12, 12, 7, paint);
            }
            canvas.restore();
        }

        private void line(Canvas canvas, float... points) {
            Path path = new Path();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i+1]);
            canvas.drawPath(path, paint);
        }
    }

    private Visuals() {}
}
