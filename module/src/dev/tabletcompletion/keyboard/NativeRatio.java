package dev.tabletcompletion.keyboard;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs once under su/app_process. Writes only the selected app's PAD ratio and drag policy. */
public final class NativeRatio {
    private static final String BASE = "content://com.vivo.smartmultiwindow.multiwindow/";

    public static void main(String[] args) {
        try {
            if (args.length != 3 || AppRules.find(args[0]) == null) {
                throw new IllegalArgumentException("不支持的应用");
            }
            int percent = Integer.parseInt(args[1]);
            if (percent < 30 || percent > 70) throw new IllegalArgumentException("比例超出范围");
            Class<?> manager = Class.forName("android.app.ActivityManager");
            if (((Integer) manager.getMethod("getCurrentUser").invoke(null)) != 0) {
                throw new IllegalStateException("仅支持 Android 主用户 0");
            }
            Looper.prepareMainLooper();
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object instance = thread.getMethod("systemMain").invoke(null);
            Context context = (Context) thread.getMethod("getSystemContext").invoke(instance);
            File directory = new File(args[2]);
            if (!directory.isDirectory()) throw new IllegalStateException("备份目录不存在");
            try (RandomAccessFile file = new RandomAccessFile(new File(directory, ".apply.lock"), "rw");
                    FileLock lock = file.getChannel().tryLock()) {
                if (lock == null) throw new IllegalStateException("上一次规则应用尚未结束，请稍后核对");
                apply(context, args[0], percent, directory);
            }
            System.out.println("TC_RESULT=OK");
            System.exit(0);
        } catch (Throwable error) {
            System.err.println("TC_ERROR=" + error.toString());
            System.exit(1);
        }
    }

    private static String read(Context context, String pkg) {
        Cursor cursor = context.getContentResolver().query(Uri.parse(BASE + "p_client"),
                new String[]{"config"}, "name=? AND fun=?", new String[]{pkg, "multi-landscape"}, null);
        if (cursor == null) throw new IllegalStateException("无法读取系统规则");
        try {
            if (cursor.getCount() != 1 || !cursor.moveToFirst()) {
                throw new IllegalStateException("系统中缺少此应用的双栏规则，请先完成原生适配");
            }
            return cursor.getString(0);
        } finally { cursor.close(); }
    }

    private static void checkSwitch(Context context, String pkg) {
        Cursor cursor = context.getContentResolver().query(Uri.parse(BASE + "app_switch"),
                new String[]{"enable"}, "name=?", new String[]{pkg}, null);
        if (cursor == null) throw new IllegalStateException("无法读取平行视窗开关");
        try {
            if (cursor.getCount() != 1 || !cursor.moveToFirst() || cursor.getInt(0) != 1) {
                throw new IllegalStateException("请先在系统设置中开启此应用的平行视窗");
            }
        } finally { cursor.close(); }
    }

    static JSONObject patch(String original, int percent) throws Exception {
        if (percent < 30 || percent > 70) throw new IllegalArgumentException("比例超出范围");
        JSONObject result = new JSONObject(original);
        JSONObject extra = result.getJSONObject("EX");
        JSONArray gravity = extra.optJSONArray("windowsGravity");
        if (extra.has("windowsGravity") && gravity == null) {
            throw new IllegalStateException("系统比例规则格式不兼容");
        }
        JSONArray updated = new JSONArray();
        if (gravity != null) {
            for (int i = 0; i < gravity.length(); i++) {
                JSONObject item = gravity.getJSONObject(i);
                if (!"PAD".equals(item.optString("deviceType"))) updated.put(item);
            }
        }
        int divisor = gcd(percent, 100 - percent);
        updated.put(new JSONObject().put("deviceType", "PAD")
                .put("gravity", (percent / divisor) + "|" + ((100 - percent) / divisor)));
        extra.put("windowsGravity", updated);
        // A saved drag position can override a default ratio. The settings page owns this ratio.
        extra.put("draggable", "false");
        return result;
    }

    private static int gcd(int left, int right) {
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }

    private static void write(Context context, String pkg, String value, String expected) throws Exception {
        ContentValues values = new ContentValues();
        values.put("config", value);
        int count = context.getContentResolver().update(Uri.parse(BASE + "p_client"), values,
                "name=? AND fun=? AND config=?", new String[]{pkg, "multi-landscape", expected});
        if (count != 1 || !same(value, read(context, pkg))) {
            throw new IllegalStateException("系统规则读回不一致，备份已保留");
        }
    }

    static boolean same(String left, String right) throws Exception {
        return sameValue(new JSONObject(left), new JSONObject(right));
    }

    private static boolean sameValue(Object left, Object right) throws Exception {
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a = (JSONObject) left;
            JSONObject b = (JSONObject) right;
            if (a.length() != b.length()) return false;
            Iterator<String> keys = a.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!b.has(key) || !sameValue(a.get(key), b.get(key))) return false;
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a = (JSONArray) left;
            JSONArray b = (JSONArray) right;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) {
                if (!sameValue(a.get(i), b.get(i))) return false;
            }
            return true;
        }
        return left.equals(right);
    }

    private static void reload(Context context, String pkg) throws Exception {
        Bundle params = new Bundle();
        params.putString("client_names", pkg);
        params.putInt("mode", 1);
        Intent intent = new Intent("com.vivo.smartmultiwindow.multilandscape.update_client_config");
        intent.setPackage("android");
        intent.putExtra("params", params);
        Class<?> user = Class.forName("android.os.UserHandle");
        Context.class.getMethod("sendBroadcastAsUser", Intent.class, user)
                .invoke(context, intent, user.getField("CURRENT").get(null));
    }

    private static void apply(Context context, String pkg, int percent, File directory) throws Exception {
        checkSwitch(context, pkg);
        String original = read(context, pkg);
        JSONObject config = new JSONObject(original);
        AppRule rule = AppRules.find(pkg);
        boolean attached = false;
        JSONArray actions = config.getJSONArray("action");
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.getJSONObject(i);
            if (rule.homeActivity.equals(action.optString("pages"))
                    && rule.searchActivity.equals(action.optString("attachedPage"))) attached = true;
        }
        if (!attached) throw new IllegalStateException("请先使用原生规则工具完成此应用的双栏适配");
        String changed = patch(original, percent).toString();
        if (!directory.isDirectory()) throw new IllegalStateException("备份目录不存在");
        File backup = File.createTempFile("ratio-" + pkg + "-", ".json", directory);
        JSONObject entry = new JSONObject().put("original", config).put("applied", new JSONObject(changed));
        JSONObject data = new JSONObject().put("schemaVersion", 1)
                .put("apps", new JSONObject().put(pkg, entry));
        try (FileOutputStream output = new FileOutputStream(backup)) {
            output.write(data.toString(2).getBytes("UTF-8"));
            output.getFD().sync();
        }
        if (!same(original, read(context, pkg))) throw new IllegalStateException("规则已被外部修改，请重新应用");
        try {
            write(context, pkg, changed, original);
            reload(context, pkg);
        } catch (Exception failure) {
            try {
                String current = read(context, pkg);
                if (same(current, changed)) {
                    write(context, pkg, original, current);
                    reload(context, pkg);
                } else if (!same(current, original)) {
                    throw new IllegalStateException("规则已被外部修改，保留现场和备份");
                }
            } catch (Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        if (!same(changed, read(context, pkg))) throw new IllegalStateException("重载后规则校验失败，备份已保留");
    }
}
