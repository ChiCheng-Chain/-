package dev.tabletcompletion.keyboard;

import java.lang.reflect.Method;
import java.util.List;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

/** API 102 chain operations shared by the keyboard interceptors. */
public final class HookFlow {
    private HookFlow() { }

    public interface Action { void run(Chain chain) throws Throwable; }
    public interface Condition { boolean test(Chain chain) throws Throwable; }

    public static Hooker before(Action action) {
        return chain -> {
            action.run(chain);
            return chain.proceed();
        };
    }

    public static Hooker finallyAfter(Action cleanup) {
        return chain -> {
            try { return chain.proceed(); }
            finally { cleanup.run(chain); }
        };
    }

    public static Object proceedWithArgument(Chain chain, int index, Object value) throws Throwable {
        // API 102 arguments are immutable. The downstream call receives a copy.
        Object[] args = chain.getArgs().toArray();
        args[index] = value;
        return chain.proceed(args);
    }

    public static Hooker suppressKeyboardWhen(Condition blocked) {
        return chain -> {
            if (blocked.test(chain) && chain.getExecutable() instanceof Method) {
                Class<?> resultType = ((Method) chain.getExecutable()).getReturnType();
                if (resultType == boolean.class) return Boolean.FALSE;
                if (resultType == void.class) return null;
                // An unknown future overload must keep its original return contract.
            }
            return chain.proceed();
        };
    }

    /** Runs failed() on an ad request's callback so the app takes its existing no-ad path. */
    public static void notifyCallbackFailed(List<Object> args, Class<?> listener) {
        if (listener == null) return;
        for (Object arg : args) {
            if (arg != null && listener.isInstance(arg)) {
                try {
                    Method failed = listener.getMethod("failed");
                    // Callback implementations are app-private classes; without this the
                    // cross-loader invoke would throw IllegalAccessException every time.
                    failed.setAccessible(true);
                    failed.invoke(arg);
                } catch (ReflectiveOperationException ignored) {
                    // A callback that cannot run still ends the request; nothing to recover.
                }
                return;
            }
        }
    }
}
