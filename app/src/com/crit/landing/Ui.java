package com.crit.landing;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

/** Shared look: Landing's palette, the bundled Geist font, and two small vector glyphs. */
final class Ui {
    private Ui() { }

    // Palette (matches the demo video)
    static final int BG = 0xFF0E0F13;          // screen background
    static final int CARD = 0xFF1C1D22;        // list cards
    static final int CARD_SELECTED = 0xFF24283A;
    static final int ACCENT = 0xFF6E8BFF;
    static final int ACCENT_DISABLED = 0xFF2F3A65; // accent at 35% over BG
    static final int DIM = 0xFF9A9CA6;
    static final int ON_ACCENT = 0xFF0A0A0C;
    static final int SHEET = 0xFF16171C;       // app drawer sheet
    static final int SEARCH = 0xFF24252C;      // drawer search pill
    static final int GRABBER = 0xFF555760;
    static final int GLYPH = 0xFFC7C7CC;
    static final int LABEL = 0xFFE6E6EA;       // drawer app labels

    static final int REGULAR = 0, MEDIUM = 1, SEMIBOLD = 2;
    private static final Typeface[] cache = new Typeface[3];

    /** Bundled Geist (SIL OFL 1.1, see third_party/geist/OFL.txt). Falls back to the system sans. */
    static Typeface font(Context c, int weight) {
        Typeface t = cache[weight];
        if (t != null) return t;
        int id = weight == SEMIBOLD ? R.font.geist_semibold : weight == MEDIUM ? R.font.geist_medium : R.font.geist_regular;
        try { t = c.getResources().getFont(id); }
        catch (Exception e) { t = Typeface.create(weight == REGULAR ? "sans-serif" : "sans-serif-medium", Typeface.NORMAL); }
        return cache[weight] = t;
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** Settings glyph from the video: a ring with a dot in the middle. */
    static final class RingDot extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        RingDot(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(dp(c, 2.6f)); ring.setColor(GLYPH);
            dot.setColor(GLYPH);
        }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            cv.drawCircle(cx, cy, dp(getContext(), 9.5f), ring);
            cv.drawCircle(cx, cy, dp(getContext(), 3.2f), dot);
        }
    }

    /** Accent check mark drawn as a stroke, like the selected row in the video. */
    static final class Check extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Check(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(c, 3f)); p.setColor(ACCENT);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cy = h / 2f, x0 = w * 0.15f, u = w * 0.7f / 34f;
            path.reset();
            path.moveTo(x0, cy); path.lineTo(x0 + 12 * u, cy + 13 * u); path.lineTo(x0 + 34 * u, cy - 14 * u);
            cv.drawPath(path, p);
        }
    }
}
