package dev.tabletcompletion.keyboard;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Adaptive native settings UI. Preview edits are committed with explicit save actions. */
public final class SettingsActivity extends Activity {
    private SettingsApp app;
    private AppRule selected;
    private int percent;
    private boolean ads;
    private int ink, muted, background, surface, soft, accent, outline;
    private LinearLayout details;
    private TextView frameworkStatus, ratioStatus, adsStatus, ratioLabel;
    private Button ratioSave, adsSave;
    private Preview preview;
    private boolean ratioDirty, adsDirty;
    private Switch adsToggle;
    private boolean refreshing;
    private final Runnable uiObserver = this::updateStatus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (SettingsApp) getApplication();
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        ink = color(dark ? "#EEEAF7" : "#252232");
        muted = color(dark ? "#B6B0C5" : "#787383");
        background = color(dark ? "#15131B" : "#F5F4F8");
        surface = color(dark ? "#211E2A" : "#FFFFFF");
        soft = color(dark ? "#302940" : "#EEEAFC");
        accent = color(dark ? "#C2B3FF" : "#7565D8");
        outline = color(dark ? "#3D364B" : "#E8E4F0");
        String pkg = state == null ? app.local.getString("selected", "tv.danmaku.bili") : state.getString("selected");
        selected = AppRules.find(pkg);
        if (selected == null) selected = AppRules.all()[0];
        percent = state == null ? app.percent(selected) : state.getInt("percent");
        ads = state == null ? app.ads(selected) : state.getBoolean("ads");
        ratioDirty = state != null && state.getBoolean("ratioDirty");
        adsDirty = state != null && state.getBoolean("adsDirty");
        build();
        app.observer = uiObserver;
        updateStatus();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("selected", selected.packageName);
        state.putInt("percent", percent);
        state.putBoolean("ads", ads);
        state.putBoolean("ratioDirty", ratioDirty);
        state.putBoolean("adsDirty", adsDirty);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (app.observer == uiObserver) app.observer = null;
        super.onDestroy();
    }

    private void build() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout root = column();
        root.setBackgroundColor(background);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(wide ? 28 : 18), dp(18), dp(wide ? 28 : 18), 0);
        LinearLayout heading = row();
        LinearLayout brand = column();
        brand.addView(text("平板补全计划", 26, ink, true));
        TextView subtitle = text("让横屏，恰到好处。", 13, muted, false);
        subtitle.setPadding(0, dp(6), 0, 0);
        brand.addView(subtitle);
        heading.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = text("0.8  /  ALPHA", 11, accent, true);
        version.setPadding(dp(12), dp(8), dp(12), dp(8));
        version.setBackgroundDrawable(shape(soft, 20, false));
        heading.addView(version);
        root.addView(heading);
        frameworkStatus = text(app.framework, 12, muted, false);
        frameworkStatus.setPadding(0, dp(14), 0, dp(20));
        root.addView(frameworkStatus);

        LinearLayout body = wide ? row() : column();
        body.setGravity(Gravity.TOP);
        LinearLayout navigation = column();
        TextView label = text("应用适配  /  04", 11, muted, true);
        label.setPadding(dp(8), 0, 0, dp(12));
        navigation.addView(label);
        LinearLayout apps = wide ? column() : row();
        for (AppRule rule : AppRules.all()) {
            boolean active = rule.packageName.equals(selected.packageName);
            LinearLayout item = column();
            item.setPadding(dp(wide ? 16 : 4), dp(14), dp(6), dp(14));
            item.setBackgroundDrawable(shape(active ? soft : background, 18, false));
            TextView name = text(rule.name, wide ? 16 : 12, active ? accent : ink, active);
            name.setGravity(wide ? Gravity.LEFT : Gravity.CENTER);
            item.addView(name, new LinearLayout.LayoutParams(-1, -2));
            if (wide) {
                TextView capability = text(rule.blockAds ? "双栏 · 输入法 · 去广告" : "双栏 · 输入法", 11, muted, false);
                capability.setPadding(0, dp(7), 0, 0);
                item.addView(capability);
            }
            item.setContentDescription(rule.name + (active ? "，当前应用" : "，点击配置"));
            item.setOnClickListener(view -> {
                if (app.busy || active) return;
                selected = rule;
                app.local.edit().putString("selected", rule.packageName).apply();
                percent = app.percent(rule);
                ads = app.ads(rule);
                ratioDirty = false;
                adsDirty = false;
                build();
                updateStatus();
            });
            LinearLayout.LayoutParams itemParams = wide
                    ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
            itemParams.bottomMargin = dp(wide ? 8 : 0);
            apps.addView(item, itemParams);
        }
        navigation.addView(apps);
        if (wide) {
            TextView note = text("为 OriginOS 7 设计\n\n比例设置需要 root 权限\n注入功能需要启用模块", 12, muted, false);
            note.setPadding(dp(10), dp(28), dp(12), 0);
            navigation.addView(note);
        }
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(wide ? dp(218) : -1, -2);
        navParams.rightMargin = dp(wide ? 24 : 0);
        navParams.bottomMargin = dp(wide ? 0 : 18);
        body.addView(navigation, navParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(28));
        details = column();
        scroll.addView(details);
        body.addView(scroll, wide ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        buildDetails();
    }

    private void buildDetails() {
        TextView title = text(selected.name, 30, ink, true);
        title.setPadding(dp(2), 0, 0, dp(6));
        details.addView(title);
        TextView description = text("独立保存这款应用的横屏偏好", 13, muted, false);
        description.setPadding(dp(2), 0, 0, dp(22));
        details.addView(description);

        LinearLayout ratio = card();
        ratio.addView(text("双栏布局", 18, ink, true));
        TextView caption = text("左侧首页，右侧搜索。找到适合你的阅读空间。", 13, muted, false);
        caption.setPadding(0, dp(8), 0, dp(18));
        ratio.addView(caption);
        preview = new Preview();
        ratio.addView(preview, new LinearLayout.LayoutParams(-1, dp(190)));
        ratioLabel = text("", 16, accent, true);
        ratioLabel.setGravity(Gravity.CENTER);
        ratioLabel.setPadding(0, dp(18), 0, dp(4));
        ratio.addView(ratioLabel);
        SeekBar slider = new SeekBar(this);
        slider.setMax(40);
        slider.setProgress(percent - 30);
        slider.setContentDescription("左栏比例，30% 到 70%");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                percent = progress + 30;
                if (fromUser) ratioDirty = true;
                updatePreview();
                updateStatus();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        ratio.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout presets = row();
        for (int value : new int[]{40, 50, 60}) {
            Button preset = button(value + " / " + (100 - value), false);
            preset.setOnClickListener(view -> {
                if (app.busy) return;
                ratioDirty = true;
                slider.setProgress(value - 30);
                updateStatus();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
            params.setMargins(dp(3), dp(6), dp(3), 0);
            presets.addView(preset, params);
        }
        ratio.addView(presets);
        TextView hint = text("应用后固定此比例，可随时回到这里调整。系统需已开启此应用的平行视窗。", 12, muted, false);
        hint.setPadding(0, dp(16), 0, dp(10));
        ratio.addView(hint);
        ratioStatus = text("", 12, muted, false);
        ratioStatus.setPadding(0, 0, 0, dp(14));
        ratio.addView(ratioStatus);
        ratioSave = button("应用并保存比例", true);
        ratioSave.setOnClickListener(view -> {
            ratioDirty = false;
            try { app.saveRatio(selected, percent); }
            catch (RuntimeException error) { ratioStatus.setText(error.getMessage()); }
        });
        ratio.addView(ratioSave, new LinearLayout.LayoutParams(-1, dp(50)));
        addCard(ratio);

        LinearLayout advertising = card();
        LinearLayout adHeading = row();
        LinearLayout adText = column();
        adText.addView(text("去广告", 18, ink, true));
        TextView adHint = text(selected.blockAds ? "开屏、插屏、信息流及横幅" : "此应用暂未提供广告拦截规则", 12, muted, false);
        adHint.setPadding(0, dp(8), dp(8), 0);
        adText.addView(adHint);
        adHeading.addView(adText, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        adsToggle = toggle;
        toggle.setChecked(ads && selected.blockAds);
        toggle.setEnabled(selected.blockAds);
        toggle.setContentDescription("启用" + selected.name + "去广告");
        toggle.setOnCheckedChangeListener((view, checked) -> {
            if (refreshing) return;
            ads = checked;
            adsDirty = true;
            updateStatus();
        });
        adHeading.addView(toggle);
        advertising.addView(adHeading);
        adsStatus = null;
        adsSave = null;
        if (selected.blockAds) {
            adsStatus = text("", 12, muted, false);
            adsStatus.setPadding(0, dp(18), 0, dp(14));
            advertising.addView(adsStatus);
            adsSave = button("保存去广告设置", false);
            adsSave.setOnClickListener(view -> {
                adsDirty = false;
                try { app.saveAds(selected, ads); }
                catch (RuntimeException error) { adsStatus.setText(error.getMessage()); }
            });
            advertising.addView(adsSave, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        addCard(advertising);

        TextView footer = text("输入法按需唤起已包含在适配中。\n配置完成后，请结束并重新打开目标应用，让新的设置在下次启动时加载。", 12, muted, false);
        footer.setPadding(dp(4), dp(4), dp(4), dp(12));
        details.addView(footer);
        updatePreview();
    }

    private void updatePreview() {
        if (ratioLabel == null) return;
        ratioLabel.setText("左侧 " + percent + "%   /   右侧 " + (100 - percent) + "%");
        preview.setContentDescription("布局预览，首页占 " + percent + "%，搜索占 " + (100 - percent) + "%");
        preview.invalidate();
    }

    private void updateStatus() {
        if (frameworkStatus == null || ratioSave == null) return;
        frameworkStatus.setText(app.framework + (app.busy ? "   ·   正在保存…" : ""));
        ratioSave.setEnabled(!app.busy && !app.deviceTimedOut);
        ratioSave.setAlpha(app.busy || app.deviceTimedOut ? 0.5f : 1f);
        ratioStatus.setText(ratioDirty ? "比例已调整，点击下方按钮保存" : app.ratioStatus(selected));
        if (app.deviceTimedOut) ratioStatus.setText("操作超时，已停止后续系统写入，请先恢复设备响应并核对规则");
        if (adsStatus != null) {
            if (!adsDirty) {
                ads = app.ads(selected);
                refreshing = true;
                adsToggle.setChecked(ads);
                refreshing = false;
            }
            adsStatus.setText(adsDirty ? "开关已调整，点击下方按钮保存" : app.adsStatus(selected));
            adsSave.setEnabled(!app.busy);
        }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static int color(String value) { return android.graphics.Color.parseColor(value); }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private TextView text(String value, int size, int tint, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(tint);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLineSpacing(dp(3), 1f);
        return view;
    }
    private GradientDrawable shape(int tint, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(tint);
        drawable.setCornerRadius(dp(radius));
        if (border) drawable.setStroke(dp(1), outline);
        return drawable;
    }
    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(22), dp(22), dp(22), dp(22));
        view.setBackgroundDrawable(shape(surface, 24, true));
        return view;
    }
    private void addCard(LinearLayout card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        details.addView(card, params);
    }
    private Button button(String label, boolean primary) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(14);
        view.setTextColor(primary ? background : accent);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setAllCaps(false);
        view.setBackgroundDrawable(shape(primary ? accent : soft, 16, false));
        return view;
    }

    private final class Preview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Preview() { super(SettingsActivity.this); }

        private void box(Canvas canvas, float left, float top, float right, float bottom, int tint, int radius) {
            paint.setColor(tint);
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, dp(radius), dp(radius), paint);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float margin = dp(12);
            float split = margin + (width - 2 * margin) * percent / 100f;
            box(canvas, 0, 0, width, height, background, 18);
            box(canvas, margin, margin, split - dp(4), height - margin, soft, 12);
            box(canvas, split + dp(4), margin, width - margin, height - margin, surface, 12);
            paint.setTextSize(dp(12));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setColor(accent);
            canvas.drawText("首页", margin + dp(14), margin + dp(26), paint);
            paint.setColor(ink);
            canvas.drawText("搜索", split + dp(18), margin + dp(26), paint);
            float leftStart = margin + dp(14);
            float leftEnd = split - dp(18);
            float middle = (leftStart + leftEnd) / 2;
            box(canvas, leftStart, dp(58), middle - dp(3), dp(112), surface, 7);
            box(canvas, middle + dp(3), dp(58), leftEnd, dp(112), surface, 7);
            box(canvas, leftStart, dp(122), leftEnd - dp(10), dp(128), surface, 3);
            box(canvas, leftStart, dp(138), leftEnd - dp(25), dp(144), surface, 3);
            float rightStart = split + dp(18);
            float rightEnd = width - margin - dp(14);
            box(canvas, rightStart, dp(58), rightEnd, dp(86), background, 8);
            box(canvas, rightStart, dp(102), rightEnd - dp(12), dp(108), soft, 3);
            box(canvas, rightStart, dp(122), rightEnd - dp(22), dp(128), soft, 3);
            box(canvas, rightStart, dp(142), rightEnd - dp(8), dp(148), soft, 3);
            box(canvas, split - dp(1), height / 2 - dp(12), split + dp(1), height / 2 + dp(12), accent, 1);
        }
    }
}
