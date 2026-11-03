package dev.tabletcompletion.tools;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import dev.tabletcompletion.keyboard.AppRules;

/** One-shot app_process entrypoint for the observed OriginOS 7 reload protocol. */
public final class ReloadRules {
    public static void main(String[] packages) throws Exception {
        if (packages.length == 0) throw new IllegalArgumentException("Specify supported packages");
        StringBuilder names = new StringBuilder();
        for (String pkg : packages) {
            if (AppRules.find(pkg) == null) throw new IllegalArgumentException("Unsupported package: " + pkg);
            if (names.length() != 0) names.append(';');
            names.append(pkg);
        }
        Looper.prepareMainLooper();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        Context context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        Bundle params = new Bundle();
        params.putString("client_names", names.toString());
        params.putInt("mode", 1);
        Intent intent = new Intent("com.vivo.smartmultiwindow.multilandscape.update_client_config");
        intent.setPackage("android");
        intent.putExtra("params", params);
        Class<?> userClass = Class.forName("android.os.UserHandle");
        Object currentUser = userClass.getField("CURRENT").get(null);
        Context.class.getMethod("sendBroadcastAsUser", Intent.class, userClass).invoke(context, intent, currentUser);
        System.out.println("Reload requested for " + names + "; verify UI separately.");
        System.exit(0);
    }
}
