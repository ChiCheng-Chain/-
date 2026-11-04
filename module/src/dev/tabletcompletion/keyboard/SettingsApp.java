package dev.tabletcompletion.keyboard;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Owns serialized writes independently of Activity recreation. */
public final class SettingsApp extends Application implements XposedServiceHelper.OnServiceListener {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile XposedService service;
    private final Object adsLock = new Object();
    volatile boolean busy;
    volatile boolean deviceTimedOut;
    volatile String framework = "等待框架连接";
    Runnable observer;
    SharedPreferences local;

    @Override public void onCreate() {
        super.onCreate();
        local = getSharedPreferences("ui-settings", MODE_PRIVATE);
        for (AppRule rule : AppRules.all()) {
            String key = rule.packageName + ".ratioStatus";
            if (local.getString(key, "").contains("正在备份")) {
                local.edit().putString(key, "待核对 · 上次应用结果未确认，请重新应用并核对").commit();
            }
        }
        XposedServiceHelper.registerListener(this);
    }

    @Override public void onServiceBind(XposedService connected) {
        service = connected;
        worker.execute(() -> {
            try {
                framework = connected.getFrameworkName() + " · API " + connected.getApiVersion();
                for (AppRule rule : AppRules.all()) {
                    if (rule.blockAds) syncAds(rule, connected);
                }
            } catch (RuntimeException error) {
                framework = "框架连接失败，请检查模块启用状态";
                for (AppRule rule : AppRules.all()) {
                    if (rule.blockAds) local.edit().putString(rule.packageName + ".adsStatus",
                            "同步失败，设置已保留 · 请检查框架后重新保存").commit();
                }
            }
            changed();
        });
    }

    @Override public void onServiceDied(XposedService disconnected) {
        if (service == disconnected) {
            service = null;
            framework = "框架已断开，广告设置等待同步";
            changed();
        }
    }

    void changed() {
        main.post(() -> { if (observer != null) observer.run(); });
    }

    int percent(AppRule rule) {
        String[] pair = rule.defaultRatio.split("\\|");
        int left = Integer.parseInt(pair[0]);
        int right = Integer.parseInt(pair[1]);
        return local.getInt(rule.packageName + ".ratio", Math.round(100f * left / (left + right)));
    }

    boolean ads(AppRule rule) {
        return local.getBoolean(rule.packageName + ".ads", rule.blockAds);
    }

    String ratioStatus(AppRule rule) {
        return local.getString(rule.packageName + ".ratioStatus", "尚未从管理页应用 · 默认比例仅供预览");
    }

    String adsStatus(AppRule rule) {
        if (service == null) return "等待框架连接，保存后将保留待同步设置";
        return local.getString(rule.packageName + ".adsStatus", "正在读取框架配置");
    }

    void saveAds(AppRule rule, boolean enabled) {
        if (busy) return;
        String key = rule.packageName + ".ads";
        synchronized (adsLock) {
            if (!local.edit().putBoolean(key, enabled).putString(key + "Status", "已保存在本机，等待框架同步").commit()) {
                throw new IllegalStateException("无法保存广告设置");
            }
        }
        busy = true;
        changed();
        worker.execute(() -> {
            try {
                XposedService current = service;
                if (current != null) syncAds(rule, current);
            } catch (RuntimeException error) {
                local.edit().putString(key + "Status", "同步失败，设置已保留 · 请检查框架后重新保存").commit();
            } finally {
                busy = false;
                changed();
            }
        });
    }

    private void syncAds(AppRule rule, XposedService connected) {
        String key = rule.packageName + ".ads";
        SharedPreferences remote = connected.getRemotePreferences("settings");
        boolean desired;
        synchronized (adsLock) {
            desired = local.getBoolean(key, remote.getBoolean(key, rule.blockAds));
            // Initialize the local mirror once. Never overwrite a newer UI choice after Binder I/O.
            if (!local.contains(key) && !local.edit().putBoolean(key, desired).commit()) {
                throw new IllegalStateException("本机保存失败");
            }
        }
        if (!remote.edit().putBoolean(key, desired).commit()) {
            throw new IllegalStateException("框架未确认保存");
        }
        boolean scoped = connected.getScope().contains(rule.packageName);
        String status = scoped ? "已同步到框架 · 结束并重开目标应用后生效"
                : "已同步 · 请在框架作用域勾选此应用后重开";
        synchronized (adsLock) {
            if (local.getBoolean(key, desired) != desired) status = "已保存在本机，等待框架同步";
            if (!local.edit().putString(key + "Status", status).commit()) {
                throw new IllegalStateException("本机保存失败");
            }
        }
    }

    void saveRatio(AppRule rule, int percent) {
        if (busy || deviceTimedOut) return;
        String key = rule.packageName + ".ratio";
        if (!local.edit().putInt(key, percent).putString(key + "Status", "待应用 · 正在备份和写入系统").commit()) {
            throw new IllegalStateException("无法保存比例设置");
        }
        busy = true;
        changed();
        worker.execute(() -> {
            String status;
            try {
                runRatio(rule, percent);
                status = "已写入系统并读回核对 · 重开目标应用后验证";
            } catch (Exception error) {
                status = "待应用 · " + error.getMessage();
            }
            local.edit().putString(key + "Status", status).commit();
            busy = false;
            changed();
        });
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void runRatio(AppRule rule, int percent) throws Exception {
        File backups = new File(getFilesDir(), "native-backups");
        if (!backups.isDirectory() && !backups.mkdirs()) throw new IllegalStateException("无法创建备份目录");
        String command = "CLASSPATH=" + quote(getApplicationInfo().sourceDir)
                + " app_process /system/bin dev.tabletcompletion.keyboard.NativeRatio "
                + quote(rule.packageName) + " " + percent + " " + quote(backups.getAbsolutePath());
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] bytes = new byte[1024];
                int count;
                while ((count = input.read(bytes)) != -1) {
                    if (output.size() < 65536) output.write(bytes, 0, count);
                }
            } catch (Exception ignored) { }
        }, "ratio-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
        int exit;
        while (true) {
            try {
                exit = process.exitValue();
                break;
            } catch (IllegalThreadStateException running) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    deviceTimedOut = true;
                    process.destroy();
                    throw new IllegalStateException("操作超时，结果未确认，请先恢复设备响应再核对规则");
                }
                Thread.sleep(100);
            }
        }
        reader.join(2000);
        if (reader.isAlive()) throw new IllegalStateException("结果读取未完成，请核对系统规则");
        String response = output.toString("UTF-8");
        if (exit != 0 || !response.contains("TC_RESULT=OK")) {
            int marker = response.indexOf("TC_ERROR=");
            String detail = marker >= 0 ? response.substring(marker + 9).trim() : "请授予本应用 root 权限并检查平行视窗开关";
            throw new IllegalStateException(detail.length() > 220 ? detail.substring(0, 220) : detail);
        }
    }
}
