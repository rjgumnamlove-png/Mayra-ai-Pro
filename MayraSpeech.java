package com.rjbilal.mayraai;

import android.content.Intent;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

// Turns the background wake service on/off from the web page, and tells the page about a wake.
@CapacitorPlugin(name = "MayraSpeech")
public class MayraSpeech extends Plugin {

    @Override
    public void load() {
        MayraWakeService.listener = () -> {
            JSObject o = new JSObject();
            notifyListeners("wake", o);
        };
    }

    @Override
    protected void handleOnResume() {
        MayraWakeService.appVisible = true;
    }

    @Override
    protected void handleOnPause() {
        MayraWakeService.appVisible = false;
    }

    @PluginMethod
    public void start(PluginCall call) {
        Intent i = new Intent(getContext(), MayraWakeService.class);
        if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i);
        else getContext().startService(i);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), MayraWakeService.class));
        call.resolve();
    }

    // True once if the app was opened by a wake (so the page can start the call straight away)
    @PluginMethod
    public void consumeWake(PluginCall call) {
        JSObject o = new JSObject();
        o.put("wake", MayraWakeService.wakePending);
        MayraWakeService.wakePending = false;
        call.resolve(o);
    }
}
