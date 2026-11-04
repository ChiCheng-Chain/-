package dev.tabletcompletion.keyboard;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Blocks iPlayMTG advertising by short-circuiting its three supply paths. Every surface was
 * verified against APK 9.2.7 to degrade safely: splash callbacks route failed() into the
 * normal no-ad navigation, feed adapters skip empty data, and the mine-page banner renderer
 * skips items with empty icon or url. Targets are resolved by name so one missing symbol
 * after an app update only drops that surface.
 */
final class AdBlocker {
    private AdBlocker() { }

    private static final String AD_MANAGER = "g.i.a.u.q2.b";
    // Splash Z/R, feed load T/V/b0, interstitial N, reward U, seeds a0. All void with a
    // callback argument; proceed(null) would leave the app waiting for an ad that never comes.
    private static final String[] AD_MANAGER_METHODS =
            {"Z", "R", "T", "N", "U", "V", "a0", "b0"};
    private static final String AD_CACHE = "g.i.a.l.b";
    private static final String MINE_AD_BEAN = "com.gonlan.iplaymtg.common.bean.MineADBean";

    static void install(ClassLoader loader, Entry entry, List<HookHandle> handles) {
        for (String name : AD_MANAGER_METHODS) {
            hookVoid(entry, handles, findMethod(loader, AD_MANAGER, name), loader);
        }
        Class<?> cache = findClass(loader, AD_CACHE);
        if (cache != null) {
            Hooker emptyList = chain -> Collections.emptyList();
            for (Method method : cache.getDeclaredMethods()) {
                if (method.getName().equals("d") && method.getReturnType() == List.class) {
                    handles.add(entry.registerHook(method, emptyList));
                }
            }
        }
        hookBeanTextGetters(entry, handles, findClass(loader, MINE_AD_BEAN));
    }

    /** Void ad entries answer failed() so callers take their existing no-ad path, then stop. */
    private static void hookVoid(Entry entry, List<HookHandle> handles,
                                 Method method, ClassLoader loader) {
        if (method == null || method.getReturnType() != void.class) return;
        handles.add(entry.registerHook(method, chain -> {
            HookFlow.notifyCallbackFailed(chain.getArgs(),
                    findClass(loader, AD_MANAGER + "$w"));
            return null;
        }));
    }

    /** Text getters drive the mine-page banner; empty strings fail the adapter's checks. */
    private static void hookBeanTextGetters(Entry entry, List<HookHandle> handles,
                                            Class<?> bean) {
        if (bean == null) return;
        for (Method method : bean.getDeclaredMethods()) {
            Class<?> type = method.getReturnType();
            if (method.getParameterCount() == 0 && (type == String.class || type == int.class)) {
                handles.add(entry.registerHook(method, chain ->
                        type == String.class ? "" : 0));
            }
        }
    }

    private static Class<?> findClass(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Method findMethod(ClassLoader loader, String className, String methodName) {
        Class<?> type = findClass(loader, className);
        if (type == null) return null;
        List<Method> matches = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) matches.add(method);
        }
        // The app builds one overload per name today; extra overloads stay unhooked so a
        // signature change cannot make this module call the wrong entry.
        return matches.size() == 1 ? matches.get(0) : null;
    }
}
