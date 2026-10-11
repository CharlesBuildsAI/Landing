package com.crit.landing;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Landing: an invisible Home app that opens the app you choose, usually an AI assistant.
 *
 * Fast path (a Home press): the window is translucent with no preview and no animations, no
 * views are inflated, and the chosen app's existing task is brought forward with a zero-duration
 * transition. Nothing is drawn, so nothing can flicker.
 *
 * Escape hatch: press Home twice quickly to get a spring-animated sheet over the blurred
 * wallpaper. Drag it down or press Home again to go back to the chosen app.
 */
public class HomeActivity extends Activity {

    static final String PREFS = "landing";
    static final String KEY_ONBOARDED = "onboarded";
    static final String KEY_TARGET = "target_pkg";
    static final String KEY_UNLOCK = "open_on_unlock";
    static final String KEY_MODE = "mode";          // app | fresh | shortcut | component | link
    static final String KEY_MODE_VAL = "mode_val";
    static final String KEY_MODE_LABEL = "mode_label";

    private static final long DOUBLE_PRESS_MS = 900;
    private static final float SCRIM = 0.55f;           // dim behind the drawer, as in the demo
    private static volatile String cachedTarget;          // survives across Home presses
    private static final List<App> appCache = new ArrayList<>();
    private static final ExecutorService bg = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastHomePress = 0;
    private boolean sheetOpen = false;

    // Lazily built drawer views
    private FrameLayout root;
    private View scrim;
    private SheetLayout sheet;
    private EditText search;
    private GridView grid;
    private AppAdapter adapter;

    static class App { String label; String pkg; ComponentName cn; Drawable icon; }

    // ======================================================================
    // Lifecycle: keep the fast path empty
    // ======================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        UnlockService.start(this);
        handle(getIntent());
        bg.execute(this::refreshApps);             // warm the drawer off the main thread
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Never come back showing a stale sheet: reset it while invisible.
        if (sheetOpen) hideSheetInstant();
    }

    @Override
    public void onBackPressed() {
        if (sheetOpen) dismissSheet(true);
    }

    private void handle(Intent i) {
        if (!prefs().getBoolean(KEY_ONBOARDED, false)) { OnboardingActivity.open(this); return; }
        boolean home = i != null && i.hasCategory(Intent.CATEGORY_HOME);
        if (!home) { showSheet(); return; }       // opened from an app list: show the drawer
        if (sheetOpen) { dismissSheet(true); return; }
        long now = SystemClock.uptimeMillis();
        boolean dbl = now - lastHomePress < DOUBLE_PRESS_MS;
        lastHomePress = now;
        if (dbl) { showSheet(); return; }
        if (!launchTarget(this)) {
            showSheet();
            if (findTargetPackage(this) == null) toast("Pick an app for Home: long-press it, or tap settings (top right)");
        }
    }

    // ======================================================================
    // Target resolution and the invisible hand-off
    // ======================================================================

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    static String findTargetPackage(Context c) {
        String t = cachedTarget;
        if (t != null) return t;
        PackageManager pm = c.getPackageManager();
        String saved = c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TARGET, null);
        if (saved != null && pm.getLaunchIntentForPackage(saved) != null) return cachedTarget = saved;
        return null;
    }

    static void setTarget(Context c, String pkg) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TARGET, pkg).putString(KEY_MODE, "app")
                .remove(KEY_MODE_VAL).remove(KEY_MODE_LABEL).apply();
        cachedTarget = pkg;
    }

    static String appLabel(Context c, String pkg) {
        if (pkg == null) return "your app";
        try { PackageManager pm = c.getPackageManager(); return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return "your app"; }
    }

    /** Zero-duration transition: the hand-off has no animation to see. */
    static Bundle noAnim(Context c) {
        return ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle();
    }

    static boolean launchTarget(Context c) {
        String pkg = findTargetPackage(c);
        if (pkg == null) return false;
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = p.getString(KEY_MODE, "app");
        String val = p.getString(KEY_MODE_VAL, null);
        Bundle opts = noAnim(c);
        try {
            if ("shortcut".equals(mode) && val != null) {
                c.getSystemService(LauncherApps.class).startShortcut(pkg, val, null, opts, Process.myUserHandle());
                return true;
            }
            if ("component".equals(mode) && val != null) {
                c.startActivity(new Intent(Intent.ACTION_MAIN)
                        .setComponent(ComponentName.unflattenFromString(val))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts);
                return true;
            }
            if ("link".equals(mode) && val != null) {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(val)).setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts);
                return true;
            }
        } catch (Exception ignored) { }
        Intent li = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) { cachedTarget = null; return false; }
        // Same intent Android's own launcher sends: an already-running task is brought
        // forward as-is (no restart). "fresh" deliberately clears it back to the app's main screen.
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | ("fresh".equals(mode)
                ? Intent.FLAG_ACTIVITY_CLEAR_TASK : Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        try {
            c.startActivity(li, opts);
            if (c instanceof Activity) ((Activity) c).overridePendingTransition(0, 0);
            return true;
        } catch (Exception e) { return false; }
    }

    // ======================================================================
    // App list (background)
    // ======================================================================

    private void refreshApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            App a = new App();
            a.label = String.valueOf(ri.loadLabel(pm));
            a.pkg = ri.activityInfo.packageName;
            a.cn = new ComponentName(a.pkg, ri.activityInfo.name);
            a.icon = ri.loadIcon(pm);
            out.add(a);
        }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        main.post(() -> {
            synchronized (appCache) { appCache.clear(); appCache.addAll(out); }
            if (adapter != null) adapter.filter(search.getText().toString());
        });
    }

    // ======================================================================
    // Drawer sheet
    // ======================================================================

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void haptic(View v, int constant) {
        try { v.performHapticFeedback(constant); } catch (Exception ignored) { }
    }

    private static int confirmHaptic() {
        return Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
    }

    private static int thresholdHaptic() {
        return Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                : HapticFeedbackConstants.CLOCK_TICK;
    }

    private void ensureUi() {
        if (root != null) return;
        root = new FrameLayout(this);

        scrim = new View(this);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> dismissSheet(true));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        sheet = new SheetLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(Ui.SHEET);
        float r = dp(24);
        bgd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bgd);
        sheet.setClipToOutline(true);
        sheet.setElevation(dp(16));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        slp.gravity = Gravity.BOTTOM;
        root.addView(sheet, slp);

        // Grabber
        View grab = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Ui.GRABBER);
        gd.setCornerRadius(dp(2));
        grab.setBackground(gd);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(44), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.setMargins(0, dp(9), 0, dp(17));
        sheet.addView(grab, glp);

        // Header: search pill + settings
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(17), 0, dp(12), 0);

        search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTypeface(Ui.font(this, Ui.REGULAR));
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Ui.DIM);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(24), 0, dp(16), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Ui.SEARCH);
        pill.setCornerRadius(dp(22));
        search.setBackground(pill);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { if (adapter != null) adapter.filter(s.toString()); }
            public void afterTextChanged(Editable s) {}
        });
        header.addView(search, new LinearLayout.LayoutParams(0, dp(43), 1f));

        View gear = new Ui.RingDot(this);
        gear.setContentDescription("Settings");
        gear.setOnClickListener(v -> { haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); showSettings(); });
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        gearLp.setMargins(dp(4), 0, 0, 0);
        header.addView(gear, gearLp);
        sheet.addView(header);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(24));
        grid.setPadding(dp(8), dp(22), dp(8), dp(24));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> openApp(v, adapter.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            App a = adapter.get(pos);
            haptic(v, HapticFeedbackConstants.LONG_PRESS);
            new AlertDialog.Builder(this)
                    .setTitle(a.label)
                    .setItems(new String[]{"Make Home open " + a.label, "App info"}, (d, w) -> {
                        if (w == 0) {
                            setTarget(this, a.pkg);
                            toast("Home now opens " + a.label);
                        } else {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + a.pkg)));
                        }
                    }).show();
            return true;
        });
        sheet.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top = in.getSystemWindowInsetTop();
            ((FrameLayout.LayoutParams) sheet.getLayoutParams()).topMargin = top + dp(32);
            grid.setPadding(dp(8), dp(22), dp(8), dp(24) + in.getSystemWindowInsetBottom());
            return in;
        });

        root.setVisibility(View.GONE);
        setContentView(root);
    }

    private void showSheet() {
        ensureUi();
        if (sheetOpen) return;
        sheetOpen = true;
        synchronized (appCache) { if (appCache.isEmpty()) bg.execute(this::refreshApps); }
        adapter.filter(search.getText().toString());
        sheet.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        root.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setBackgroundBlurRadius(dp(24));
        root.post(() -> {
            float h = sheet.getHeight() > 0 ? sheet.getHeight() : root.getHeight();
            sheet.setTranslationY(h);
            animateSheet(0f, 0f, null);
            haptic(sheet, confirmHaptic());
        });
    }

    /** Dismiss the sheet. toTarget = go back to the chosen app afterwards. */
    private void dismissSheet(boolean toTarget) {
        if (!sheetOpen) return;
        hideKeyboard();
        float h = sheet.getHeight();
        float v = sheet.lastVelocity;
        sheet.lastVelocity = 0;
        animateSheet(h, v, () -> {
            hideSheetInstant();
            if (toTarget) launchTarget(this);
        });
    }

    private void hideSheetInstant() {
        sheetOpen = false;
        if (root == null) return;
        root.setVisibility(View.GONE);
        scrim.setAlpha(0f);
        search.setText("");
        hideKeyboard();
        if (Build.VERSION.SDK_INT >= 31) getWindow().setBackgroundBlurRadius(0);
    }

    private void hideKeyboard() {
        if (search == null) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    private ValueAnimator sheetAnim;

    /** Damped-spring animation of the sheet's translationY, carrying the fling velocity in. */
    private void animateSheet(float to, float velocityPxPerS, Runnable end) {
        if (sheetAnim != null) sheetAnim.cancel();
        final float from = sheet.getTranslationY();
        final float h = Math.max(1f, sheet.getHeight());
        final boolean closing = to > 0;
        // Critically damped both ways (no overshoot), about 0.4 s: the ease-in-out slide in the demo.
        Spring spring = new Spring(1.0f, closing ? 200f : 140f,
                (from - to) == 0 ? 0 : -velocityPxPerS / (from - to));
        sheetAnim = ValueAnimator.ofFloat(0f, 1f);
        sheetAnim.setDuration(spring.durationMs());
        sheetAnim.setInterpolator(spring);
        sheetAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float y = from + (to - from) * f;
            sheet.setTranslationY(y);
            scrim.setAlpha(SCRIM * (1f - Math.max(0f, Math.min(1f, y / h))));
        });
        sheetAnim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) { if (!cancelled && end != null) end.run(); }
        });
        sheetAnim.start();
    }

    /** Analytic damped harmonic oscillator, normalised 0 -> 1. */
    static class Spring implements TimeInterpolator {
        final float zeta, omega, v0; final long dur;
        Spring(float dampingRatio, float stiffness, float normVelocity) {
            zeta = dampingRatio; omega = (float) Math.sqrt(stiffness); v0 = normVelocity;
            dur = (long) Math.min(700, Math.max(220, 1000 * 5.0 / (zeta * omega)));
        }
        long durationMs() { return dur; }
        @Override public float getInterpolation(float input) {
            double t = input * dur / 1000.0;
            double x;
            if (zeta < 1) {
                double wd = omega * Math.sqrt(1 - zeta * zeta);
                x = Math.exp(-zeta * omega * t) * (Math.cos(wd * t) + ((zeta * omega - v0) / wd) * Math.sin(wd * t));
            } else {
                x = Math.exp(-omega * t) * (1 + (omega - v0) * t);
            }
            return input >= 1f ? 1f : (float) (1 - x);
        }
    }

    /** Sheet that can be dragged down from the grabber, or from the list when it's scrolled to top. */
    class SheetLayout extends LinearLayout {
        private float downY, startTy; private boolean dragging, crossed;
        private VelocityTracker vt; float lastVelocity;
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        SheetLayout(Context c) { super(c); }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getRawY(); dragging = false; crossed = false;
                    if (vt != null) vt.recycle(); vt = VelocityTracker.obtain(); vt.addMovement(e);
                    if (sheetAnim != null) sheetAnim.cancel();
                    startTy = getTranslationY();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(e);
                    float dy = e.getRawY() - downY;
                    boolean atTop = grid == null || !grid.canScrollVertically(-1);
                    boolean inHeader = e.getY() < dp(80);
                    if (dy > slop && (atTop || inHeader)) { dragging = true; hideKeyboard(); return true; }
                    return false;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            float h = Math.max(1, getHeight());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getRawY(); startTy = getTranslationY();
                    if (sheetAnim != null) sheetAnim.cancel();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - downY;
                    float ty = startTy + dy;
                    if (ty < 0) ty = ty / 4f;            // rubber-band upward
                    setTranslationY(ty);
                    scrim.setAlpha(SCRIM * (1f - Math.max(0f, Math.min(1f, ty / h))));
                    boolean past = ty > h * 0.25f;
                    if (past != crossed) { crossed = past; haptic(this, thresholdHaptic()); }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    vt.computeCurrentVelocity(1000);
                    lastVelocity = vt.getYVelocity();
                    vt.recycle(); vt = null;
                    float ty = getTranslationY();
                    if (ty > h * 0.25f || lastVelocity > dp(900)) dismissSheet(true);
                    else animateSheet(0f, lastVelocity, null);
                    dragging = false;
                    return true;
                }
            }
            return super.onTouchEvent(e);
        }
    }

    private void openApp(View cell, App a) {
        haptic(cell, HapticFeedbackConstants.VIRTUAL_KEY);
        cell.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction(() ->
                cell.animate().scaleX(1f).scaleY(1f).setDuration(160).start()).start();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(a.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Bundle opts = ActivityOptions.makeScaleUpAnimation(cell, 0, 0, cell.getWidth(), cell.getHeight()).toBundle();
        try { startActivity(i, opts); } catch (Exception e) { toast("Can't open " + a.label); }
    }

    class AppAdapter extends BaseAdapter {
        private final List<App> shown = new ArrayList<>();
        void filter(String q) {
            shown.clear();
            String l = q.toLowerCase(Locale.US).trim();
            synchronized (appCache) {
                for (App a : appCache) if (l.isEmpty() || a.label.toLowerCase(Locale.US).contains(l)) shown.add(a);
            }
            notifyDataSetChanged();
        }
        App get(int i) { return shown.get(i); }
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View cv, ViewGroup parent) {
            LinearLayout cell; ImageView iv; TextView tv;
            if (cv == null) {
                cell = new LinearLayout(HomeActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                iv = new ImageView(HomeActivity.this);
                cell.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
                tv = new TextView(HomeActivity.this);
                tv.setTextColor(Ui.LABEL);
                tv.setTextSize(11);
                tv.setTypeface(Ui.font(HomeActivity.this, Ui.MEDIUM));
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(1);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(dp(2), dp(5), dp(2), 0);
                cell.addView(tv);
            } else {
                cell = (LinearLayout) cv;
                iv = (ImageView) cell.getChildAt(0);
                tv = (TextView) cell.getChildAt(1);
            }
            App a = shown.get(i);
            iv.setImageDrawable(a.icon);
            tv.setText(a.label);
            return cell;
        }
    }

    // ======================================================================
    // Settings (gear)
    // ======================================================================

    private void showSettings() {
        String pkg = findTargetPackage(this);
        boolean unlock = prefs().getBoolean(KEY_UNLOCK, false);
        boolean overlay = Settings.canDrawOverlays(this);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        String name = appLabel(this, pkg);
        String status = "Home opens: " + (pkg == null ? "not set" : prefs().getString(KEY_MODE_LABEL, name))
                + "\nUnlock \u2192 " + name + ": " + (!unlock ? "off" : overlay ? "on" : "needs permission")
                + "\nBattery: " + (battery ? "unrestricted" : "optimized");
        String[] items = {
                "Choose what Home opens\u2026",
                "Open on unlock: turn " + (unlock ? "off" : "on"),
                "Allow opening on unlock (permission)",
                "Battery settings",
                "Home app settings",
                "Run setup again"
        };
        new AlertDialog.Builder(this).setCustomTitle(dialogTitle("Landing", status)).setItems(items, (d, w) -> {
            switch (w) {
                case 0: showScreenPicker(); break;
                case 1:
                    prefs().edit().putBoolean(KEY_UNLOCK, !unlock).apply();
                    if (!unlock) UnlockService.start(this); else UnlockService.stop(this);
                    toast(!unlock ? "Unlock opens " + name : "Unlock opens your last app");
                    break;
                case 2:
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
                    break;
                case 3:
                    OnboardingActivity.openBatterySettings(this);
                    break;
                case 4:
                    try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                    break;
                case 5:
                    prefs().edit().putBoolean(KEY_ONBOARDED, false).putInt(OnboardingActivity.KEY_STEP, 0).apply();
                    OnboardingActivity.open(this);
                    break;
            }
        }).show();
    }

    private void choose(String mode, String val, String label) {
        prefs().edit().putString(KEY_MODE, mode).putString(KEY_MODE_VAL, val).putString(KEY_MODE_LABEL, label).apply();
        toast("Home and unlock now open: " + label);
    }

    private void showScreenPicker() {
        final String pkg = findTargetPackage(this);
        if (pkg == null) { toast("Long-press an app in the grid to pick it first"); return; }
        final String name = appLabel(this, pkg);
        final List<String> labels = new ArrayList<>();
        final List<String[]> opts = new ArrayList<>();
        labels.add(name + ", normal (where you left off)"); opts.add(new String[]{"app", null, name + " (normal)"});
        labels.add(name + ", fresh start (main screen every time)"); opts.add(new String[]{"fresh", null, name + " (fresh start)"});
        try {
            LauncherApps la = getSystemService(LauncherApps.class);
            if (la.hasShortcutHostPermission()) {
                LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
                q.setPackage(pkg);
                q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                        | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                List<ShortcutInfo> sc = la.getShortcuts(q, Process.myUserHandle());
                if (sc != null) for (ShortcutInfo si : sc) {
                    CharSequence l = si.getLongLabel() != null ? si.getLongLabel() : si.getShortLabel();
                    labels.add("Shortcut: " + l); opts.add(new String[]{"shortcut", si.getId(), "Shortcut: " + l});
                }
            }
        } catch (Exception ignored) { }
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null) for (ActivityInfo ai : pi.activities) {
                if (!ai.exported) continue;
                String shortName = ai.name.substring(ai.name.lastIndexOf('.') + 1);
                labels.add("Screen: " + shortName);
                opts.add(new String[]{"component", new ComponentName(pkg, ai.name).flattenToString(), "Screen: " + shortName});
            }
        } catch (Exception ignored) { }
        labels.add("Type a link to try\u2026"); opts.add(new String[]{"custom", null, null});
        new AlertDialog.Builder(this)
                .setTitle("What should Home and unlock open?")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String[] o = opts.get(which);
                    if ("custom".equals(o[0])) { askForLink(pkg); return; }
                    choose(o[0], o[1], o[2]);
                    dismissSheet(true);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void askForLink(String pkg) {
        final EditText et = new EditText(this);
        et.setHint("e.g. myapp://home");
        et.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Link to open in " + appLabel(this, pkg)).setView(et)
                .setPositiveButton("Try it", (d, w) -> {
                    String u = et.getText().toString().trim();
                    if (u.isEmpty()) return;
                    Intent t = new Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(pkg);
                    if (getPackageManager().queryIntentActivities(t, 0).isEmpty()) { toast("That app doesn't answer to that link"); return; }
                    choose("link", u, "Link: " + u);
                    dismissSheet(true);
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Multi-line dialog header (setTitle truncates after two lines on many phones). */
    private View dialogTitle(String head, String body) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(20), dp(24), dp(8));
        TextView h = new TextView(this);
        h.setText(head); h.setTextSize(20);
        h.setTypeface(Ui.font(this, Ui.SEMIBOLD));
        l.addView(h);
        TextView b = new TextView(this);
        b.setText(body); b.setTextSize(14); b.setAlpha(0.7f); b.setPadding(0, dp(6), 0, 0);
        l.addView(b);
        return l;
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }
}
