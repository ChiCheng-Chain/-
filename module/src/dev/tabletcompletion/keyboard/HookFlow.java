package dev.tabletcompletion.keyboard;

import java.lang.reflect.Method;
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
}
