package com.rjbilal.mayraai;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.webkit.JavascriptInterface;

// Exposed to the web page as window.AndroidBridge (the page already calls these names).
//   AndroidBridge.onWakeWordDetected()  -> the page heard "ہائے مائرہ" (wake word)
//   AndroidBridge.closeApp()            -> the page heard "بند ہو جاؤ": close the whole app
public class MayraBridge {

    private final Activity activity;

    public MayraBridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void onWakeWordDetected() {
        // The app is already awake here; bring it to the front if it was behind another app.
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (activity != null) activity.getWindow().getDecorView().requestFocus();
            } catch (Exception e) { }
        });
    }

    @JavascriptInterface
    public void closeApp() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { if (activity != null) activity.finishAndRemoveTask(); } catch (Exception e) { }
            new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 300);
        }, 300);
    }
}
