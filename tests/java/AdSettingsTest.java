package dev.tabletcompletion.keyboard;

import android.content.SharedPreferences;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Tests the production entry's configuration read with a new module instance on every launch. */
public final class AdSettingsTest {
    public static void main(String[] args) throws Exception {
        AppRule rule = AppRules.find("com.gonlan.iplaymtg");
        Map<String, Boolean> saved = new HashMap<>();
        check(readOnLaunch(rule, saved, false), "Existing installations retain enabled default");
        saved.put(rule.packageName + ".ads", false);
        check(!readOnLaunch(rule, saved, false), "Next launch respects disabled setting");
        check(!readOnLaunch(rule, saved, false), "Another module instance retains disabled setting");
        saved.put(rule.packageName + ".ads", true);
        check(readOnLaunch(rule, saved, false), "Next launch respects re-enabled setting");
        check(!readOnLaunch(rule, saved, true), "Unavailable preferences leave ad requests intact");
        System.out.println("Ad settings: 5 startup preference checks passed");
    }

    private static boolean readOnLaunch(AppRule rule, Map<String, Boolean> saved, boolean fail) throws Exception {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBoolean")) return saved.getOrDefault(args[0], (Boolean) args[1]);
                    throw new AssertionError("Unexpected preferences method " + method.getName());
                });
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("log")) return null;
                    if (method.getName().equals("getRemotePreferences")) {
                        check(args[0].equals("settings"), "UI and injected entry use the same group");
                        if (fail) throw new IllegalStateException("Framework disconnected");
                        return preferences;
                    }
                    throw new AssertionError("Unexpected framework method " + method.getName());
                });
        Entry entry = new Entry();
        entry.attachFramework(framework, () -> { });
        Method method = Entry.class.getDeclaredMethod("adsEnabled", AppRule.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(entry, rule);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
