package com.crit.landing;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** First-run setup: welcome, pick the app, become Home, open-on-unlock (optional), drawer demo. */
public class OnboardingActivity extends Activity {
    static final String KEY_STEP = "onboarding_step";
    static final int STEPS = 5;
    private static final int REQ_HOME = 7;
    private static final int BG = Ui.BG, CARD = Ui.CARD, ACCENT = Ui.ACCENT, DIM = Ui.DIM;

    private FrameLayout body;
    private LinearLayout dots;
    private ValueAnimator demo;
    private int step;
    // Picker state: the row the user tapped, and the rows so selection can restyle in place.
    private String pickSel;
    private final List<View> pickRows = new ArrayList<>();
    private final List<String> pickPkgs = new ArrayList<>();
    private TextView pickButton;

    static void open(Context c) {
        c.startActivity(new Intent(c, OnboardingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    static void openBatterySettings(Context c) {
        try { c.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private SharedPreferences prefs() { return getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(18), 0, dp(6));
        root.addView(dots, new LinearLayout.LayoutParams(-1, -2));
        body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        step = Math.max(0, Math.min(STEPS - 1, prefs().getInt(KEY_STEP, 0)));
    }

    @Override protected void onResume() { super.onResume(); render(); }   // re-check permissions on return

    @Override protected void onPause() { super.onPause(); if (demo != null) demo.cancel(); }

    @Override public void onBackPressed() { if (step > 0) go(step - 1); }

    private void go(int s) { step = s; prefs().edit().putInt(KEY_STEP, s).apply(); render(); }

    private void render() {
        if (demo != null) { demo.cancel(); demo = null; }
        body.removeAllViews();
        dots.removeAllViews();
        // The picker is a full-height list with its own button, like the demo: no step dots there.
        dots.setVisibility(step == 1 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < STEPS; i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(4));
            g.setColor(i == step ? Color.WHITE : 0x44FFFFFF); d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == step ? 22 : 8), dp(8));
            lp.setMargins(dp(4), 0, dp(4), 0); dots.addView(d, lp);
        }
        switch (step) {
            case 0: welcome(); break;
            case 1: pickApp(); break;
            case 2: becomeHome(); break;
            case 3: unlockStep(); break;
            default: drawerDemo(); break;
        }
    }

    // ---------------------------------------------------------------- pages

    private void welcome() {
        LinearLayout p = page();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(88), dp(88));
        ilp.gravity = Gravity.CENTER_HORIZONTAL; ilp.bottomMargin = dp(28);
        p.addView(icon, ilp);
        p.addView(title("Make any AI\nyour home screen"));
        p.addView(text("Pick the assistant you talk to most (ChatGPT, Claude, Gemini, Perplexity, or any other app). Press Home and you're in it, with no grid of icons in the way. "
                + "Your other apps are always one double-press away."));
        spacer(p);
        p.addView(primary("Get started", v -> go(1)));
        body.addView(p);
    }

    private void pickApp() {
        LinearLayout p = page();
        p.setPadding(dp(24), dp(36), dp(24), dp(24));
        TextView t = title("What should Home open?");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        t.setPadding(0, 0, 0, dp(6));
        p.addView(t);
        TextView sub = text("Pick the app every Home press lands in.");
        sub.setTextSize(14);
        sub.setPadding(0, 0, 0, dp(14));
        p.addView(sub);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> ris = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        List<ResolveInfo> apps = new ArrayList<>();
        List<ResolveInfo> ai = new ArrayList<>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (AiApps.isAi(pkg, ri.loadLabel(pm))) ai.add(ri); else apps.add(ri);
        }
        java.util.Comparator<ResolveInfo> byLabel = (x, y) ->
                String.valueOf(x.loadLabel(pm)).compareToIgnoreCase(String.valueOf(y.loadLabel(pm)));
        Collections.sort(ai, byLabel);
        Collections.sort(apps, byLabel);
        String current = prefs().getString(HomeActivity.KEY_TARGET, null);
        pickSel = (current != null && pm.getLaunchIntentForPackage(current) != null) ? current : null;
        pickRows.clear(); pickPkgs.clear();
        if (!ai.isEmpty()) {
            list.addView(section("Featured", true));
            for (ResolveInfo ri : ai) list.addView(appRow(ri, "Your AI agent, a press away"));
            list.addView(section("All apps", false));
        }
        for (ResolveInfo ri : apps) list.addView(appRow(ri, null));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.leftMargin = -dp(8); slp.rightMargin = -dp(8);   // cards sit a little wider than the text, as in the demo
        p.addView(sv, slp);
        pickButton = primary("Set as Home app", v -> confirmPick());
        p.addView(pickButton);
        restylePick();
        body.addView(p);
    }

    /** "Set as Home app" on the picker: save the app, then ask Android to make Landing the Home app. */
    private void confirmPick() {
        if (pickSel == null) return;
        HomeActivity.setTarget(this, pickSel);
        if (isHome()) { go(3); return; }
        step = 2; prefs().edit().putInt(KEY_STEP, 2).apply();   // if the prompt is dismissed, come back to the Home step
        requestHome();
    }

    private void restylePick() {
        for (int i = 0; i < pickRows.size(); i++) {
            View row = pickRows.get(i);
            boolean sel = pickPkgs.get(i).equals(pickSel);
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(14));
            g.setColor(sel ? Ui.CARD_SELECTED : CARD);
            if (sel) g.setStroke(dp(2), ACCENT);
            row.setBackground(g);
            View check = row.findViewWithTag("check");
            if (check != null) check.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        }
        if (pickButton != null) {
            boolean on = pickSel != null;
            pickButton.setEnabled(on);
            ((GradientDrawable) pickButton.getBackground()).setColor(on ? ACCENT : Ui.ACCENT_DISABLED);
        }
    }

    private View appRow(ResolveInfo ri, String subtitle) {
        PackageManager pm = getPackageManager();
        final String pkg = ri.activityInfo.packageName;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), 0, dp(12), 0);
        ImageView iv = new ImageView(this);
        Drawable d = ri.loadIcon(pm); iv.setImageDrawable(d);
        row.addView(iv, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(13), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(ri.loadLabel(pm)); name.setTextColor(Color.WHITE); name.setTextSize(16);
        name.setTypeface(Ui.font(this, subtitle != null ? Ui.SEMIBOLD : Ui.MEDIUM));
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(name);
        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle); sub.setTextColor(DIM); sub.setTextSize(12.5f);
            sub.setTypeface(Ui.font(this, Ui.REGULAR));
            sub.setPadding(0, dp(1), 0, 0);
            labels.addView(sub);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        Ui.Check check = new Ui.Check(this);
        check.setTag("check");
        row.addView(check, new LinearLayout.LayoutParams(dp(24), dp(24)));
        row.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            pickSel = pkg;
            restylePick();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(subtitle != null ? 58 : 50));
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        pickRows.add(row); pickPkgs.add(pkg);
        return row;
    }

    private boolean isHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME);
        }
        ResolveInfo ri = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void becomeHome() {
        LinearLayout p = page();
        String app = HomeActivity.appLabel(this, HomeActivity.findTargetPackage(this));
        boolean home = isHome();
        p.addView(title(home ? "Landing is your Home app" : "Make Landing your Home app"));
        p.addView(text(home ? "Every Home press now opens " + app + "."
                : "Android will ask which Home app to use. Choose Landing so the Home button opens " + app + ". "
                + "You can switch back any time in Settings."));
        spacer(p);
        if (home) p.addView(primary("Continue", v -> go(3)));
        else {
            p.addView(primary("Set as Home app", v -> requestHome()));
            p.addView(secondary("Not now", v -> go(3)));
        }
        body.addView(p);
    }

    private void requestHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
                    return;
                }
            }
        } catch (Exception ignored) { }
        try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_HOME && isHome()) go(3);
        else if (req == REQ_HOME) {
            // Some phones ignore the role prompt for launchers; fall back to the system picker.
            try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    private void unlockStep() {
        LinearLayout p = page();
        String app = HomeActivity.appLabel(this, HomeActivity.findTargetPackage(this));
        boolean overlay = Settings.canDrawOverlays(this);
        if (overlay && !prefs().contains(HomeActivity.KEY_UNLOCK)) prefs().edit().putBoolean(HomeActivity.KEY_UNLOCK, true).apply();
        boolean on = overlay && prefs().getBoolean(HomeActivity.KEY_UNLOCK, false);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());

        p.addView(title("Open " + app + " when you unlock"));
        p.addView(text("Optional. Unlock your phone and land in " + app + " instead of whatever was open last. "
                + "Android calls this \u201cdisplay over other apps.\u201d Landing only uses it to open " + app + ", and it never draws anything over your screen."));
        if (on) p.addView(status("\u2713  Opens on unlock"));
        else {
            p.addView(primary("Allow", v -> {
                prefs().edit().putBoolean(HomeActivity.KEY_UNLOCK, true).apply();
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
            }));
            if (Build.VERSION.SDK_INT >= 33) {
                TextView help = text("Toggle greyed out? Android limits this for apps installed outside a store. "
                        + "Open App info, tap \u22ee in the top corner, choose \u201cAllow restricted settings,\u201d then come back and tap Allow.");
                help.setTextSize(13);
                p.addView(help);
                p.addView(secondary("Open App info", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))));
            }
        }
        TextView bh = text(battery ? "Battery: unrestricted, so Android won't stop it."
                : "Some phones stop background apps to save battery. If unlock stops working, set Landing to \u201cUnrestricted\u201d here.");
        bh.setTextSize(13);
        p.addView(bh);
        if (!battery) p.addView(secondary("Battery settings", v -> openBatterySettings(this)));
        spacer(p);
        p.addView(primary(on ? "Continue" : "Skip for now", v -> {
            if (!Settings.canDrawOverlays(this)) prefs().edit().putBoolean(HomeActivity.KEY_UNLOCK, false).apply();
            go(4);
        }));
        if (on) UnlockService.start(this);
        body.addView(p);
    }

    private void drawerDemo() {
        LinearLayout p = page();
        String app = HomeActivity.appLabel(this, HomeActivity.findTargetPackage(this));
        p.addView(title("Your other apps"));
        p.addView(text("Press Home twice, quickly, to pull up all your apps. Swipe the sheet down or press Home again to go back to " + app + ". "
                + "Long-press any app to make it your Home app, or tap the settings button next to search."));
        p.addView(demoPhone(app), demoLp());
        spacer(p);
        p.addView(primary("Done, take me to " + app, v -> finishSetup()));
        body.addView(p);
    }

    private LinearLayout.LayoutParams demoLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(170), dp(300));
        lp.gravity = Gravity.CENTER_HORIZONTAL; lp.topMargin = dp(20);
        return lp;
    }

    /** A tiny looping animation: two Home taps, then the app sheet springs up and back down. */
    private View demoPhone(String app) {
        FrameLayout phone = new FrameLayout(this);
        GradientDrawable frame = new GradientDrawable(); frame.setColor(0xFF0B0B0B); frame.setCornerRadius(dp(26));
        frame.setStroke(dp(2), 0xFF555552);
        phone.setBackground(frame);
        phone.setPadding(dp(6), dp(6), dp(6), dp(6));
        phone.setClipToOutline(true);

        TextView screen = new TextView(this);
        screen.setText(app); screen.setTextColor(Color.WHITE); screen.setTextSize(15);
        screen.setTypeface(Ui.font(this, Ui.SEMIBOLD));
        screen.setGravity(Gravity.CENTER);
        GradientDrawable sg = new GradientDrawable(); sg.setColor(BG);
        sg.setCornerRadius(dp(21));
        screen.setBackground(sg);
        phone.addView(screen, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(8), dp(6), dp(8), dp(10));
        GradientDrawable shg = new GradientDrawable(); shg.setColor(Ui.SHEET);
        float r = dp(14); shg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(shg);
        View grab = new View(this);
        GradientDrawable gg = new GradientDrawable(); gg.setColor(Ui.GRABBER); gg.setCornerRadius(dp(2)); grab.setBackground(gg);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(22), dp(3)); glp.gravity = Gravity.CENTER_HORIZONTAL; glp.bottomMargin = dp(8);
        sheet.addView(grab, glp);
        View pill = new View(this);
        GradientDrawable pg = new GradientDrawable(); pg.setColor(Ui.SEARCH); pg.setCornerRadius(dp(8)); pill.setBackground(pg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(16)); plp.setMargins(dp(4), 0, dp(4), dp(6));
        sheet.addView(pill, plp);
        // Same stand-in icon colours as the demo video's drawer.
        int[] colors = {0xFF3DB86B, 0xFF4C8DF6, 0xFF5A5D66, 0xFFE89A3C, 0xFF2FA89A, 0xFFD94F70, 0xFF3F6FD8, 0xFF44464E,
                0xFFD8B43A, 0xFF4AA3E0, 0xFF6B6E78, 0xFF5C7CC9};
        for (int rI = 0; rI < 3; rI++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setGravity(Gravity.CENTER);
            for (int c = 0; c < 4; c++) {
                View ic = new View(this);
                GradientDrawable ig = new GradientDrawable(); ig.setColor(colors[rI * 4 + c]); ig.setCornerRadius(dp(6.5f)); ic.setBackground(ig);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24)); lp.setMargins(dp(5), dp(6), dp(5), dp(6));
                rowL.addView(ic, lp);
            }
            sheet.addView(rowL);
        }
        FrameLayout.LayoutParams shlp = new FrameLayout.LayoutParams(-1, dp(200)); shlp.gravity = Gravity.BOTTOM;
        phone.addView(sheet, shlp);

        final View tap = new View(this);
        GradientDrawable tg = new GradientDrawable(); tg.setShape(GradientDrawable.OVAL); tg.setColor(Color.WHITE); tap.setBackground(tg);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(26), dp(26));
        tlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; tlp.bottomMargin = dp(10);
        phone.addView(tap, tlp);

        final float hidden = dp(220);
        sheet.setTranslationY(hidden);
        tap.setAlpha(0f);
        final PathInterpolator ease = new PathInterpolator(0.65f, 0f, 0.35f, 1f);   // ease-in-out, as in the video
        demo = ValueAnimator.ofFloat(0f, 1f);
        demo.setDuration(3600);
        demo.setRepeatCount(ValueAnimator.INFINITE);
        demo.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // 0.00-0.12 tap one, 0.14-0.26 tap two
            float tapA = pulse(t, 0.0f, 0.12f) + pulse(t, 0.14f, 0.26f);
            tap.setAlpha(0.85f * tapA);
            tap.setScaleX(0.6f + 0.6f * tapA); tap.setScaleY(0.6f + 0.6f * tapA);
            float up;
            if (t < 0.28f) up = 0f;
            else if (t < 0.45f) up = ease.getInterpolation((t - 0.28f) / 0.17f);
            else if (t < 0.75f) up = 1f;
            else if (t < 0.9f) up = 1f - ease.getInterpolation((t - 0.75f) / 0.15f);
            else up = 0f;
            sheet.setTranslationY(hidden * (1f - up));
        });
        demo.start();
        return phone;
    }

    private static float pulse(float t, float a, float b) {
        if (t < a || t > b) return 0f;
        float x = (t - a) / (b - a);
        return (float) Math.sin(Math.PI * x);
    }

    private void finishSetup() {
        prefs().edit().putBoolean(HomeActivity.KEY_ONBOARDED, true).putInt(KEY_STEP, 0).apply();
        UnlockService.start(this);
        HomeActivity.launchTarget(this);
        finishAndRemoveTask();
    }

    // ---------------------------------------------------------------- views

    private LinearLayout page() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(24), dp(24), dp(24), dp(24));
        p.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return p;
    }

    private void spacer(LinearLayout p) { p.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f)); }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Ui.font(this, Ui.SEMIBOLD));
        t.setLineSpacing(0, 1.1f);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(DIM); t.setTextSize(15);
        t.setTypeface(Ui.font(this, Ui.REGULAR));
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, 0, 0, dp(12));
        return t;
    }

    private TextView section(String s, boolean first) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(Locale.US)); t.setTextColor(DIM); t.setTextSize(11);
        t.setTypeface(Ui.font(this, Ui.SEMIBOLD));
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(8), dp(first ? 6 : 12), 0, dp(10));
        return t;
    }

    private TextView status(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(0xFF5BD68A); t.setTextSize(15);
        t.setTypeface(Ui.font(this, Ui.MEDIUM));
        t.setPadding(0, dp(4), 0, dp(14));
        return t;
    }

    /** Accent pill with near-black label, as in the demo's "Set as Home app". */
    private TextView primary(String s, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(s); b.setTextColor(Ui.ON_ACCENT); b.setTextSize(16); b.setGravity(Gravity.CENTER);
        b.setTypeface(Ui.font(this, Ui.SEMIBOLD));
        GradientDrawable g = new GradientDrawable(); g.setColor(ACCENT); g.setCornerRadius(dp(25));
        b.setBackground(g);
        b.setOnClickListener(v -> {
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()).start();
            l.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50)); lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView secondary(String s, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(s); b.setTextColor(ACCENT); b.setTextSize(15); b.setGravity(Gravity.CENTER);
        b.setTypeface(Ui.font(this, Ui.MEDIUM));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.bottomMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
