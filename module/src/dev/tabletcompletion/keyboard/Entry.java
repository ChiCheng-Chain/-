package dev.tabletcompletion.keyboard;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Search-page keyboard requires a fresh editor gesture after every lifecycle transition. */
public final class Entry implements IXposedHookLoadPackage {
    private static final Map<Activity, State> states = Collections.synchronizedMap(new WeakHashMap<Activity, State>());
    private static WeakReference<Activity> resumed = new WeakReference<Activity>(null);
    private static boolean fallbackSearchScheduled;
    private static final class State {
        volatile boolean touched;
        final int originalMode;
        State(int mode) { originalMode = mode; }
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        final AppRule rule = AppRules.find(lp.packageName);
        if (rule == null) return;
        if (!lp.packageName.equals(lp.processName)) return;
        XposedBridge.log("TabletKeyboard: loaded in " + lp.packageName);
        Log.i("TabletCompletion", "Keyboard hooks loaded: " + lp.packageName);

        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity a = (Activity) p.thisObject;
                if (!isSearch(a)) return;
                State s = new State(a.getWindow().getAttributes().softInputMode);
                states.put(a, s);
                a.getWindow().setSoftInputMode(hidden(s.originalMode));
                XposedBridge.log("TabletKeyboard: gated " + a.getClass().getName());
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                final Activity a = (Activity) p.thisObject;
                if (!rule.searchLaunchFallback || !a.getClass().getName().equals(rule.homeActivity) || fallbackSearchScheduled) return;
                if (p.args[0] != null || !nativeSplit(a)) return;
                fallbackSearchScheduled = true;
                a.getWindow().getDecorView().post(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        try {
                            Intent intent = new Intent();
                            intent.setClassName(rule.packageName, rule.searchActivity);
                            a.startActivity(intent);
                        } catch (Throwable e) {
                            Log.e("TabletCompletion", "Default search could not start", e);
                        }
                    }
                });
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity a = (Activity) p.thisObject;
                resumed = new WeakReference<Activity>(a);
                disarm(a);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { disarm((Activity) p.thisObject); }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onConfigurationChanged", Configuration.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { disarm((Activity) p.thisObject); }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged", boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(Boolean) p.args[0]) disarm((Activity) p.thisObject);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { states.remove((Activity) p.thisObject); }
        });
        XposedHelpers.findAndHookMethod(Window.class, "setSoftInputMode", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Activity a = activity(((Window) p.thisObject).getContext());
                if (blocked(a)) p.args[0] = hidden((Integer) p.args[0]);
            }
        });
        XposedBridge.hookAllMethods(TextView.class, "getShowSoftInputOnFocus", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.thisObject instanceof EditText && blocked(activity(((View) p.thisObject).getContext()))) p.setResult(false);
            }
        });
        XposedBridge.hookAllMethods(InputMethodManager.class, "showSoftInput", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                for (Object arg : p.args) {
                    if (arg instanceof View) {
                        if (blocked(activity(((View) arg).getContext()))) {
                            p.setResult(((Method) p.method).getReturnType() == boolean.class ? Boolean.FALSE : null);
                        }
                        return;
                    }
                }
            }
        });
        XposedBridge.hookAllMethods(InputMethodManager.class, "toggleSoftInput", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (blocked(resumed.get())) p.setResult(null);
            }
        });
        XposedHelpers.findAndHookMethod(TextView.class, "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.thisObject instanceof EditText && ((MotionEvent) p.args[0]).getActionMasked() == MotionEvent.ACTION_DOWN) {
                    unlock((View) p.thisObject);
                }
            }
        });
        XposedHelpers.findAndHookMethod(View.class, "performAccessibilityAction", int.class, Bundle.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int action = (Integer) p.args[0];
                if (p.thisObject instanceof EditText && (action == 16 || action == 64)) unlock((View) p.thisObject);
            }
        });
    }

    private static boolean nativeSplit(Activity a) {
        try {
            Object configuration = XposedHelpers.getObjectField(a.getResources().getConfiguration(), "windowConfiguration");
            return ((Integer) XposedHelpers.callMethod(configuration, "getWindowingMode")) == 86;
        } catch (Throwable ignored) { return false; }
    }

    private static boolean isSearch(Activity a) {
        AppRule rule = AppRules.find(a.getPackageName());
        return rule != null && rule.manualKeyboard && a.getClass().getName().equals(rule.searchActivity);
    }
    private static Activity activity(Context c) {
        for (int depth = 0; c != null && depth < 16; depth++) {
            if (c instanceof Activity) return (Activity) c;
            if (!(c instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) c).getBaseContext();
            if (next == c) return null;
            c = next;
        }
        return null;
    }
    private static int hidden(int mode) {
        return (mode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
    }
    private static boolean blocked(Activity a) {
        State s = a == null ? null : states.get(a);
        return s != null && !s.touched;
    }
    private static void disarm(Activity a) {
        State s = states.get(a);
        if (s == null) return;
        s.touched = false;
        a.getWindow().setSoftInputMode(hidden(a.getWindow().getAttributes().softInputMode));
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        View decor = a.getWindow().peekDecorView();
        if (imm != null && decor != null && decor.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(decor.getWindowToken(), 0);
        }
    }
    private static void unlock(View editor) {
        Activity a = activity(editor.getContext());
        State s = a == null ? null : states.get(a);
        if (s == null || s.touched) return;
        s.touched = true;
        a.getWindow().setSoftInputMode(s.originalMode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
        XposedBridge.log("TabletKeyboard: search editor touched, keyboard allowed");
    }
}
