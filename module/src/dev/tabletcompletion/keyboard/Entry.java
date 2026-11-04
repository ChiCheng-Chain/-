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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/** API 102 entry. Search keyboards require a fresh editor gesture after lifecycle changes. */
public final class Entry extends XposedModule {
    private static final String TAG = "TabletCompletion";
    private final Map<Activity, State> states = Collections.synchronizedMap(new WeakHashMap<Activity, State>());
    private final List<HookHandle> handles = new ArrayList<>();
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private String processName;
    private boolean installed;
    private boolean fallbackSearchScheduled;

    private static final class State {
        volatile boolean touched;
        final int originalMode;
        State(int mode) { originalMode = mode; }
    }

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        AppRule rule = AppRules.find(param.getPackageName());
        if (installed || rule == null || !param.isFirstPackage()
                || !rule.packageName.equals(processName)) return;
        try {
            installHooks(rule, param.getClassLoader());
            installed = true;
            log(Log.INFO, TAG, "API 102 keyboard hooks loaded: " + rule.packageName);
        } catch (Throwable error) {
            // Avoid leaving a partially installed keyboard policy after a registration failure.
            for (HookHandle handle : handles) {
                try { handle.unhook(); } catch (Throwable ignored) { }
            }
            handles.clear();
            states.clear();
            log(Log.ERROR, TAG, "Hook registration failed: " + rule.packageName, error);
        }
    }

    private void installHooks(final AppRule rule, ClassLoader loader) throws NoSuchMethodException {
        if (rule.blockAds) AdBlocker.install(loader, this, handles);
        hookExact(Activity.class, "onCreate", chain -> {
            Activity a = (Activity) chain.getThisObject();
            if (isSearch(a)) {
                State s = new State(a.getWindow().getAttributes().softInputMode);
                states.put(a, s);
                a.getWindow().setSoftInputMode(hidden(s.originalMode));
            }
            Object result = chain.proceed();
            if (rule.searchLaunchFallback && a.getClass().getName().equals(rule.homeActivity)
                    && !fallbackSearchScheduled && chain.getArg(0) == null && nativeSplit(a)) {
                fallbackSearchScheduled = true;
                a.getWindow().getDecorView().post(() -> {
                    if (a.isFinishing()) return;
                    try {
                        Intent intent = new Intent();
                        intent.setClassName(rule.packageName, rule.searchActivity);
                        a.startActivity(intent);
                    } catch (Throwable error) {
                        log(Log.ERROR, TAG, "Default search could not start", error);
                    }
                });
            }
            return result;
        }, Bundle.class);
        hookExact(Activity.class, "onResume", HookFlow.before(chain -> {
            Activity a = (Activity) chain.getThisObject();
            resumed = new WeakReference<>(a);
            disarm(a);
        }));
        hookExact(Activity.class, "onPause", HookFlow.before(chain -> disarm((Activity) chain.getThisObject())));
        hookExact(Activity.class, "onConfigurationChanged",
                HookFlow.before(chain -> disarm((Activity) chain.getThisObject())), Configuration.class);
        hookExact(Activity.class, "onWindowFocusChanged", HookFlow.before(chain -> {
            if (!(Boolean) chain.getArg(0)) disarm((Activity) chain.getThisObject());
        }), boolean.class);
        hookExact(Activity.class, "onDestroy", HookFlow.finallyAfter(chain -> states.remove(chain.getThisObject())));
        hookExact(Window.class, "setSoftInputMode", chain -> {
            Activity a = activity(((Window) chain.getThisObject()).getContext());
            return blocked(a) ? HookFlow.proceedWithArgument(chain, 0, hidden((Integer) chain.getArg(0)))
                              : chain.proceed();
        }, int.class);
        hookAll(TextView.class, "getShowSoftInputOnFocus", HookFlow.suppressKeyboardWhen(chain ->
                chain.getThisObject() instanceof EditText
                && blocked(activity(((View) chain.getThisObject()).getContext()))));
        hookAll(InputMethodManager.class, "showSoftInput", HookFlow.suppressKeyboardWhen(chain -> {
            for (Object arg : chain.getArgs()) {
                if (arg instanceof View) return blocked(activity(((View) arg).getContext()));
            }
            return false;
        }));
        hookAll(InputMethodManager.class, "toggleSoftInput", HookFlow.suppressKeyboardWhen(chain -> blocked(resumed.get())));
        hookExact(TextView.class, "onTouchEvent", HookFlow.before(chain -> {
            if (chain.getThisObject() instanceof EditText
                    && ((MotionEvent) chain.getArg(0)).getActionMasked() == MotionEvent.ACTION_DOWN) {
                unlock((View) chain.getThisObject());
            }
        }), MotionEvent.class);
        hookExact(View.class, "performAccessibilityAction", HookFlow.before(chain -> {
            int action = (Integer) chain.getArg(0);
            if (chain.getThisObject() instanceof EditText && (action == 16 || action == 64)) {
                unlock((View) chain.getThisObject());
            }
        }), int.class, Bundle.class);
    }

    private void hookExact(Class<?> type, String name, Hooker interceptor, Class<?>... parameters)
            throws NoSuchMethodException {
        register(type.getDeclaredMethod(name, parameters), interceptor);
    }

    private void hookAll(Class<?> type, String name, Hooker interceptor) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) register(method, interceptor);
        }
    }

    private void register(Method method, Hooker interceptor) {
        handles.add(hook(method).setId("tablet-completion.keyboard")
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(interceptor));
    }

    /** Registers one hook under the ad-block id; shared with AdBlocker's install path. */
    io.github.libxposed.api.XposedInterface.HookHandle registerHook(Method method, Hooker interceptor) {
        io.github.libxposed.api.XposedInterface.HookHandle handle =
                hook(method).setId("tablet-completion.adblock")
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(interceptor);
        handles.add(handle);
        return handle;
    }

    private static boolean nativeSplit(Activity a) {
        try {
            Field field = Configuration.class.getDeclaredField("windowConfiguration");
            field.setAccessible(true);
            Object windowConfig = field.get(a.getResources().getConfiguration());
            Method method = windowConfig.getClass().getDeclaredMethod("getWindowingMode");
            method.setAccessible(true);
            return ((Integer) method.invoke(windowConfig)) == 86;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
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
        return (mode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
    }

    private boolean blocked(Activity a) {
        State s = a == null ? null : states.get(a);
        return s != null && !s.touched;
    }

    private void disarm(Activity a) {
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

    private void unlock(View editor) {
        Activity a = activity(editor.getContext());
        State s = a == null ? null : states.get(a);
        if (s == null || s.touched) return;
        s.touched = true;
        a.getWindow().setSoftInputMode(s.originalMode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
    }
}
