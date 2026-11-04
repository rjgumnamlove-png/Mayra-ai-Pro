package com.rjbilal.mayra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    public interface FrameListener { void onFrame(String base64Jpeg); }

    static volatile FrameListener listener;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private long lastFrame = 0;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try { projection = mgr.getMediaProjection(code, data); } catch (Exception e) { projection = null; }
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() { stopCapture(); stopSelf(); }
        }, new Handler(getMainLooper()));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scale = 540f / Math.min(dm.widthPixels, dm.heightPixels);
        final int sw = Math.max(2, (int) (dm.widthPixels * scale));
        final int sh = Math.max(2, (int) (dm.heightPixels * scale));
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2);
        thread = new HandlerThread("mayra-screen");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        display = projection.createVirtualDisplay("mayra", sw, sh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    long now = System.currentTimeMillis();
                    if (now - lastFrame < 1000) return;
                    lastFrame = now;
                    Image.Plane plane = img.getPlanes()[0];
                    ByteBuffer buf = plane.getBuffer();
                    int pixelStride = plane.getPixelStride();
                    int rowStride = plane.getRowStride();
                    int pad = rowStride - pixelStride * img.getWidth();
                    Bitmap bmp = Bitmap.createBitmap(img.getWidth() + pad / pixelStride, img.getHeight(), Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(buf);
                    Bitmap crop = Bitmap.createBitmap(bmp, 0, 0, img.getWidth(), img.getHeight());
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    crop.compress(Bitmap.CompressFormat.JPEG, 60, out);
                    FrameListener l = listener;
                    if (l != null) l.onFrame(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                    bmp.recycle();
                    crop.recycle();
                } catch (Exception ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }
        }, handler);
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        String channel = "mayra_screen";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(channel, "Screen sharing", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        Notification n = b.setContentTitle("Mayra AI").setContentText("Sharing your screen with Mayra")
                .setSmallIcon(android.R.drawable.ic_menu_view).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(4242, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(4242, n);
        }
    }

    private void stopCapture() {
        try { if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; } } catch (Exception ignored) {}
        try { if (display != null) { display.release(); display = null; } } catch (Exception ignored) {}
        MediaProjection p = projection;
        projection = null;
        try { if (p != null) p.stop(); } catch (Exception ignored) {}
        if (thread != null) { thread.quitSafely(); thread = null; }
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }
}
