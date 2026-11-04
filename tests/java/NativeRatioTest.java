package dev.tabletcompletion.keyboard;

import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the production ratio patcher without an Android device or vendor data. */
public final class NativeRatioTest {
    public static void main(String[] args) throws Exception {
        JSONObject original = new JSONObject()
                .put("action", new JSONArray().put(new JSONObject().put("pages", "example.Home")))
                .put("vendor", new JSONObject().put("keep", true))
                .put("EX", new JSONObject().put("unknown", 17).put("draggable", "true")
                        .put("windowsGravity", new JSONArray()
                                .put(new JSONObject().put("deviceType", "FOLD").put("gravity", "1|1"))
                                .put(new JSONObject().put("deviceType", "PAD").put("gravity", "2|3"))));
        String source = original.toString();
        for (int percent = 30; percent <= 70; percent++) {
            JSONObject changed = NativeRatio.patch(source, percent);
            JSONObject extra = changed.getJSONObject("EX");
            check(extra.getString("draggable").equals("false"), "Saved drag must not override selection");
            check(extra.getInt("unknown") == 17, "Unknown settings survive");
            check(changed.getJSONObject("vendor").getBoolean("keep"), "Vendor fields survive");
            check(changed.getJSONArray("action").toString().equals(original.getJSONArray("action").toString()),
                    "Navigation stays intact");
            JSONArray gravity = extra.getJSONArray("windowsGravity");
            check(gravity.length() == 2, "Exactly one PAD entry");
            check(gravity.getJSONObject(0).getString("deviceType").equals("FOLD"), "FOLD setting survives");
            String[] pair = gravity.getJSONObject(1).getString("gravity").split("\\|");
            int left = Integer.parseInt(pair[0]);
            int right = Integer.parseInt(pair[1]);
            check(left * 100 == percent * (left + right), "Every percentage is exact");
            check(NativeRatio.same(changed.toString(), NativeRatio.patch(changed.toString(), percent).toString()),
                    "Repeated saves are idempotent");
        }
        check(source.equals(original.toString()), "Source remains intact");
        check(NativeRatio.patch("{\"EX\":{}}", 50).getJSONObject("EX")
                .getJSONArray("windowsGravity").length() == 1, "Missing ratio is initialized");
        for (String malformed : new String[]{"{}", "{\"EX\":[]}", "{\"EX\":{\"windowsGravity\":{}}}",
                "{\"EX\":{\"windowsGravity\":[1]}}"}) {
            boolean failed = false;
            try { NativeRatio.patch(malformed, 40); } catch (Exception expected) { failed = true; }
            check(failed, "Unknown vendor schema is rejected");
        }
        for (int invalid : new int[]{-1, 29, 71, 101}) {
            boolean failed = false;
            try { NativeRatio.patch(source, invalid); } catch (IllegalArgumentException expected) { failed = true; }
            check(failed, "Invalid percentages are rejected");
        }
        check(NativeRatio.same("{\"b\":[1,null],\"a\":{\"x\":true}}",
                "{\"a\":{\"x\":true},\"b\":[1,null]}"), "Read-back ignores object key order");
        check(!NativeRatio.same("{\"a\":[1,2]}", "{\"a\":[2,1]}"), "Read-back preserves array order");
        check(!NativeRatio.same("{\"a\":1}", "{\"b\":1}"), "Different keys are rejected");
        System.out.println("Native ratio checks passed: 41 percentages, preservation, idempotence, validation, read-back");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
