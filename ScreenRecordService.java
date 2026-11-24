package com.rjbilal.mayra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import java.io.FileOutputStream;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import java.io.File;

// Records the phone screen (picture only) into a temporary .mp4 while Mayra records the voices herself.
// MayraRecPlugin later joins the picture and the voices into one video in the gallery.
public class ScreenRecordService extends Service {
    public interface Listener {
        void onStarted(String path, long startedAt);
        void onFailed(String why);
        void onStopped(String path, long startedAt, long stoppedAt);
    }

    static volatile Listener listener;
    static volatile boolean running = false;
    // sound played by other apps (YouTube, music, games...), 16 kHz mono PCM, started together with the video
    static volatile String systemPcm = null;

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private String path;
    private long startedAt = 0;
    private boolean stopped = false;
    private AudioRecord sysRec;
    private Thread sysThread;
    private volatile boolean sysOn = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            finish();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (intent == null) { fail("no permission data"); return START_NOT_STICKY; }
        try {
            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(code, data);
            if (projection == null) { fail("screen permission was not given"); return START_NOT_STICKY; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() { finish(); }   // the user pressed "Stop" in Android's own bar
            }, new Handler(Looper.getMainLooper()));

            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels;
            float scale = Math.min(1f, 720f / Math.min(w, h));
            int vw = Math.max(16, ((int) (w * scale)) / 16 * 16);
            int vh = Math.max(16, ((int) (h * scale)) / 16 * 16);

            File dir = new File(getCacheDir(), "mayra-rec");
            dir.mkdirs();
            path = new File(dir, "screen-" + System.currentTimeMillis() + ".mp4").getAbsolutePath();

            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(vw, vh);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(5_000_000);
            recorder.setOutputFile(path);
            recorder.prepare();

            display = projection.createVirtualDisplay("mayra-rec", vw, vh, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
            startSystemSound(dir);
            recorder.start();
            startedAt = System.currentTimeMillis();
            running = true;
            Listener l = listener;
            if (l != null) l.onStarted(path, startedAt);
        } catch (Exception e) {
            fail(String.valueOf(e.getMessage()));
        }
        return START_NOT_STICKY;
    }

    private void fail(String why) {
        if (stopped) return;
        stopped = true;
        Listener l = listener;
        cleanup(false);
        try { if (path != null) new File(path).delete(); } catch (Exception ignored) {}
        if (l != null) l.onFailed(why);
        stopSelf();
    }

    private synchronized void finish() {
        if (stopped) return;
        stopped = true;
        long stoppedAt = System.currentTimeMillis();
        boolean ok = cleanup(true);
        Listener l = listener;
        if (l != null) {
            if (ok) l.onStopped(path, startedAt, stoppedAt);
            else l.onFailed("the screen video could not be finished");
        }
        stopSelf();
    }

    // Android 10+: also hear the sound other apps play (apps that forbid it simply stay silent)
    @android.annotation.TargetApi(29)
    private void startSystemSound(File dir) {
        systemPcm = null;
        if (Build.VERSION.SDK_INT < 29) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(Process.myUid())          // Mayra's own voice is recorded inside the app already
                    .build();
            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            sysRec = new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(Math.max(min, 16000) * 2)
                    .setAudioPlaybackCaptureConfig(cfg).build();
            final File out = new File(dir, "system-" + System.currentTimeMillis() + ".pcm");
            final AudioRecord rec = sysRec;
            rec.startRecording();
            sysOn = true;
            systemPcm = out.getAbsolutePath();
            sysThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] b = new byte[3200];
                    try (FileOutputStream o = new FileOutputStream(out)) {
                        while (sysOn) {
                            int n = rec.read(b, 0, b.length);
                            if (n > 0) o.write(b, 0, n);
                            else if (n < 0) break;
                        }
                    } catch (Exception ignored) {}
                }
            });
            sysThread.start();
        } catch (Exception e) {
            systemPcm = null;
            try { if (sysRec != null) sysRec.release(); } catch (Exception ignored) {}
            sysRec = null;
        }
    }

    private void stopSystemSound() {
        sysOn = false;
        try { if (sysRec != null) sysRec.stop(); } catch (Exception ignored) {}
        try { if (sysThread != null) sysThread.join(1500); } catch (Exception ignored) {}
        try { if (sysRec != null) sysRec.release(); } catch (Exception ignored) {}
        sysRec = null;
        sysThread = null;
    }

    private boolean cleanup(boolean keep) {
        stopSystemSound();
        boolean ok = recorder != null && startedAt > 0;
        running = false;
        try { if (recorder != null) { if (startedAt > 0) recorder.stop(); } } catch (Exception e) { ok = false; }
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        display = null;
        MediaProjection p = projection;
        projection = null;
        try { if (p != null) p.stop(); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
        return ok && keep;
    }

    private void startForegroundCompat() {
        String channel = "mayra_rec";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(channel, "Recording", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        Notification n = b.setContentTitle("Mayra AI").setContentText("● Recording the screen and the conversation")
                .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(4343, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(4343, n);
    }

    @Override
    public void onDestroy() {
        if (!stopped && running) finish();
        super.onDestroy();
    }
}
