import dev.tabletcompletion.keyboard.HookFlow;
import io.github.libxposed.api.XposedInterface.Chain;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Runs against the real API 102 interfaces and production HookFlow, without Android or ART. */
public final class HookFlowTest {
    public static boolean booleanCall() { return true; }
    public static void voidCall() { }
    public static int futureCall() { return 42; }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Throwable {
        FakeChain before = new FakeChain("booleanCall", Boolean.TRUE);
        boolean[] called = {false};
        Object result = HookFlow.before(chain -> {
            check(before.calls == 0, "lifecycle disarm must run before downstream");
            called[0] = true;
        }).intercept(before);
        check(called[0] && result == Boolean.TRUE && before.calls == 1, "before must preserve result and proceed once");

        FakeChain blocked = new FakeChain("booleanCall", Boolean.TRUE);
        check(HookFlow.suppressKeyboardWhen(chain -> true).intercept(blocked) == Boolean.FALSE
                && blocked.calls == 0, "blocked boolean input request must short circuit");
        FakeChain blockedVoid = new FakeChain("voidCall", null);
        check(HookFlow.suppressKeyboardWhen(chain -> true).intercept(blockedVoid) == null
                && blockedVoid.calls == 0, "blocked void input request must short circuit");
        FakeChain allowed = new FakeChain("booleanCall", Boolean.TRUE);
        check(HookFlow.suppressKeyboardWhen(chain -> false).intercept(allowed) == Boolean.TRUE
                && allowed.calls == 1, "editor gesture must allow downstream input request");
        FakeChain future = new FakeChain("futureCall", 42);
        check(HookFlow.suppressKeyboardWhen(chain -> true).intercept(future).equals(42)
                && future.calls == 1, "unknown return types must keep original contract");

        FakeChain changed = new FakeChain("voidCall", null);
        HookFlow.proceedWithArgument(changed, 0, 3);
        check(changed.calls == 1 && changed.getArg(0).equals(7) && changed.forwarded[0].equals(3)
                && changed.forwarded[1].equals("untouched"), "must copy immutable arguments and preserve other arguments");

        RuntimeException expected = new RuntimeException("original app failure");
        FakeChain failing = new FakeChain("booleanCall", Boolean.TRUE);
        failing.failure = expected;
        try {
            HookFlow.before(chain -> {}).intercept(failing);
            throw new AssertionError("original exception was swallowed");
        } catch (RuntimeException actual) {
            check(actual == expected && failing.calls == 1, "preserve original exception identity");
        }

        boolean[] cleaned = {false};
        FakeChain destroy = new FakeChain("voidCall", null);
        destroy.failure = expected;
        try {
            HookFlow.finallyAfter(chain -> cleaned[0] = true).intercept(destroy);
            throw new AssertionError("destroy exception was swallowed");
        } catch (RuntimeException actual) {
            check(actual == expected && cleaned[0] && destroy.calls == 1, "cleanup must run even on lifecycle failure");
        }
        FakeChain successfulDestroy = new FakeChain("booleanCall", Boolean.TRUE);
        check(HookFlow.finallyAfter(chain -> check(successfulDestroy.calls == 1, "cleanup order"))
                .intercept(successfulDestroy) == Boolean.TRUE, "cleanup must preserve downstream result");
        System.out.println("API 102 HookFlow: 9 contract checks passed");
    }

    private static final class FakeChain implements Chain {
        final Method method;
        final Object result;
        final List<Object> args = Collections.unmodifiableList(Arrays.asList(7, "untouched"));
        int calls;
        Object[] forwarded;
        Throwable failure;
        FakeChain(String name, Object result) throws NoSuchMethodException {
            this.method = HookFlowTest.class.getDeclaredMethod(name);
            this.result = result;
        }
        public Executable getExecutable() { return method; }
        public Object getThisObject() { return this; }
        public List<Object> getArgs() { return args; }
        public Object getArg(int index) { return args.get(index); }
        public Object proceed() throws Throwable { return proceed(args.toArray()); }
        public Object proceed(Object[] args) throws Throwable {
            calls++;
            forwarded = args;
            if (failure != null) throw failure;
            return result;
        }
        public Object proceedWith(Object receiver) { throw new AssertionError("receiver must not change"); }
        public Object proceedWith(Object receiver, Object[] args) { throw new AssertionError("receiver must not change"); }
    }
}
