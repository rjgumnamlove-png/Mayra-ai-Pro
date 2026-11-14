package com.rjbilal.mayraai;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

// Background wake-word service (opt-in). Runs only while the user keeps it on.
// It uses the free Vosk offline model that the build adds (see add_speech.sh).
// The Vosk classes are loaded by reflection, so a missing model never breaks the build.
public class MayraWakeService extends Service {

    static final String CH_LISTEN = "mayra_listen";
    static final String CH_ALERT = "mayra_alert";
    static final int NOTE_LISTEN = 4711;
    static final int NOTE_ALERT = 4712;
    static final String EXTRA_WAKE = "mayra_wake";
    static final String GRAMMAR = "[\"hello mayra\",\"hi mayra\",\"hey mayra\",\"listen mayra\",\"hello mera\",\"hi mera\",\"hey mera\",\"listen mera\",\"baat suno mera\",\"[unk]\"]";

    // set by the plugin while the app is on screen
    static volatile boolean appVisible = false;
    static volatile WakeListener listener = null;
    static volatile boolean wakePending = false;

    public interface WakeListener { void onWake(); }

    private volatile boolean running = false;
    private AudioRecord record;
    private Thread worker;
    private Object recognizer;      // org.vosk.Recognizer (via reflection)
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH_LISTEN, "Mayra listening", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Mayra wake", NotificationManager.IMPORTANCE_HIGH));
        }
        Notification n = new NotificationCompat.Builder(this, CH_LISTEN)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("مائرہ · Mayra")
                .setContentText("ہیلو میرا کہیں · Say \"Hello Mayra\"")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_LISTEN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTE_LISTEN, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) startListening();
        return START_STICKY;
    }

    private void startListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        running = true;
        worker = new Thread(this::loop, "mayra-wake");
        worker.start();
        handler.postDelayed(this::batteryCheck, 60000);
    }

    private void batteryCheck() {
        if (!running) return;
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        int level = bm == null ? 100 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = bm != null && bm.isCharging();
        if (!charging && level <= 15) {   // stop to protect the battery
            stopSelf();
            return;
        }
        handler.postDelayed(this::batteryCheck, 60000);
    }

    private void loop() {
        try {
            Object model = loadModel();
            if (model == null) { running = false; stopSelf(); return; }
            Class<?> recCls = Class.forName("org.vosk.Recognizer");
            recognizer = recCls.getConstructor(Class.forName("org.vosk.Model"), float.class, String.class)
                    .newInstance(model, 16000f, GRAMMAR);
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 3200));
            record.startRecording();
            byte[] buf = new byte[3200];
            java.lang.reflect.Method accept = recCls.getMethod("acceptWaveForm", byte[].class, int.class);
            java.lang.reflect.Method result = recCls.getMethod("getResult");
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n <= 0) continue;
                // Save battery: only hand audio to the recognizer when there is some sound
                if (rms(buf, n) < 250) continue;
                boolean done = (Boolean) accept.invoke(recognizer, buf, n);
                if (done) {
                    String text = textOf((String) result.invoke(recognizer));
                    if (containsWake(text)) onWakeWord();
                }
            }
        } catch (Throwable t) {
            running = false;
        } finally {
            try { if (record != null) { record.stop(); record.release(); } } catch (Exception e) { }
            record = null;
        }
    }

    static double rms(byte[] b, int n) {
        double sum = 0; int count = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            short s = (short) ((b[i + 1] << 8) | (b[i] & 0xff));
            sum += (double) s * s; count++;
        }
        return count == 0 ? 0 : Math.sqrt(sum / count);
    }

    static String textOf(String json) {
        if (json == null) return "";
        int i = json.indexOf("\"text\"");
        if (i < 0) return "";
        int a = json.indexOf('"', json.indexOf(':', i) + 1);
        int b = json.indexOf('"', a + 1);
        return (a >= 0 && b > a) ? json.substring(a + 1, b).toLowerCase() : "";
    }

    static boolean containsWake(String t) {
        return t.contains("hello mayra") || t.contains("hi mayra") || t.contains("hey mayra") || t.contains("listen mayra")
            || t.contains("hello mera") || t.contains("hi mera") || t.contains("hey mera") || t.contains("listen mera")
            || t.contains("baat suno mera");
    }

    private void onWakeWord() {
        if (appVisible && listener != null) {
            wakePending = false;
            handler.post(() -> { if (listener != null) listener.onWake(); });
            return;
        }
        // The app is closed: tell the user, and opening it starts the call
        wakePending = true;
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) launch.putExtra(EXTRA_WAKE, true);
        PendingIntent pi = PendingIntent.getActivity(this, 1, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification n = new NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("مائرہ آپ کو بلا رہی ہے")
                .setContentText("ایپ کھولیں تاکہ بات شروع ہو · Tap to talk")
                .setContentIntent(pi).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH).build();
        getSystemService(NotificationManager.class).notify(NOTE_ALERT, n);
        try { startActivity(launch); } catch (Exception e) { }   // some devices allow it; the notification is the fallback
    }

    // Copies the model from the app assets into a folder the recognizer can read.
    private Object loadModel() {
        try {
            File dir = new File(getFilesDir(), "model-en-us");
            if (!new File(dir, "conf").exists()) copyAssets("model-en-us", dir);
            Class<?> modelCls = Class.forName("org.vosk.Model");
            return modelCls.getConstructor(String.class).newInstance(dir.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    private void copyAssets(String path, File dest) throws Exception {
        AssetManager am = getAssets();
        String[] list = am.list(path);
        if (list == null || list.length == 0) {
            dest.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            return;
        }
        dest.mkdirs();
        for (String f : list) copyAssets(path + "/" + f, new File(dest, f));
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (worker != null) worker.interrupt(); } catch (Exception e) { }
        try { handler.removeCallbacksAndMessages(null); } catch (Exception e) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
