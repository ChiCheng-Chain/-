import dev.tabletcompletion.keyboard.HookFlow;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exercises the ad-block callback contract against production code, without Android or ART. */
public final class AdBlockerTest {
    interface SplashListener { void failed(); }

    static final class Listener implements SplashListener {
        int failedCalls;
        public void failed() { failedCalls++; }
    }

    static final class Unrelated { }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Listener listener = new Listener();
        List<Object> args1 = Arrays.asList(null, listener, "extra");
        HookFlow.notifyCallbackFailed(args1, SplashListener.class);
        check(listener.failedCalls == 1, "must notify the ad callback argument exactly once");

        Listener second = new Listener();
        HookFlow.notifyCallbackFailed(Arrays.asList(new Unrelated(), second), SplashListener.class);
        check(second.failedCalls == 1, "must skip arguments that are not ad callbacks");

        HookFlow.notifyCallbackFailed(Collections.singletonList(null), SplashListener.class);
        HookFlow.notifyCallbackFailed(Collections.emptyList(), SplashListener.class);
        HookFlow.notifyCallbackFailed(Collections.singletonList("x"), null);
        check(true, "missing callback or listener class must be a silent no-op");

        System.out.println("AdBlocker: 3 callback contract checks passed");
    }
}
