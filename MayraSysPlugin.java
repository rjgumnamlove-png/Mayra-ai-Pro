package com.rjbilal.mayraai;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

// Native helpers for Mayra: close the whole app, and start/stop the wake service (opt-in only).
@CapacitorPlugin(name = "MayraSys")
public class MayraSysPlugin extends Plugin {

    @Override
    public void load() {
        // Make window.AndroidBridge available to the web page (the page calls it for wake word and close).
        final Activity a = getActivity();
        if (a == null || getBridge() == null || getBridge().getWebView() == null) return;
        a.runOnUiThread(() -> getBridge().getWebView().addJavascriptInterface(new MayraBridge(a), "AndroidBridge"));
    }

    @PluginMethod
    public void closeApp(PluginCall call) {
        final Activity a = getActivity();
        call.resolve();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { if (a != null) a.finishAndRemoveTask(); } catch (Exception e) { }
            new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 300);
        }, 300);
    }

    @PluginMethod
    public void startWake(PluginCall call) {
        Intent i = new Intent(getContext(), MayraWakeService.class);
        if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i);
        else getContext().startService(i);
        call.resolve();
    }

    @PluginMethod
    public void stopWake(PluginCall call) {
        getContext().stopService(new Intent(getContext(), MayraWakeService.class));
        call.resolve();
    }
}
