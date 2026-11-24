package com.rjbilal.mayra;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

// Screen + voice recording of a Mayra conversation.
// The page calls (Capacitor.Plugins.MayraRec):
//   start()                         -> asks Android for screen recording, resolves { startedAt } when the video has started
//   pcmAppend({ data })             -> adds a piece of the voices (16 kHz, 16-bit mono PCM, base64)
//   stop()                          -> stops the screen video, resolves { startedAt, stoppedAt }
//   finish({ audioStartedAt, name }) -> joins the picture and the voices into Movies/Mayra/<name>.mp4, resolves { path }
// Event "recStopped" is sent when Android's own "Stop" button ends the recording.
@CapacitorPlugin(name = "MayraRec")
public class MayraRecPlugin extends Plugin {
    private static final int RATE = 16000;

    private PluginCall startCall, stopCall;
    private String video;
    private long vStart = 0, vStop = 0;
    private File pcm;
    private FileOutputStream pcmOut;

    @PluginMethod
    public void isRecording(PluginCall call) {
        JSObject o = new JSObject();
        o.put("recording", ScreenRecordService.running);
        call.resolve(o);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (ScreenRecordService.running) { call.reject("already recording"); return; }
        try { openPcm(); } catch (Exception e) { call.reject("cannot prepare the sound file"); return; }
        MediaProjectionManager m = (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(call, m.createScreenCaptureIntent(), "recPermission");
    }

    @ActivityCallback
    private void recPermission(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("denied");
            return;
        }
        startCall = call;
        video = null; vStart = 0; vStop = 0;
        ScreenRecordService.listener = new ScreenRecordService.Listener() {
            @Override
            public void onStarted(String path, long startedAt) {
                video = path; vStart = startedAt;
                PluginCall c = startCall; startCall = null;
                if (c != null) { JSObject o = new JSObject(); o.put("startedAt", startedAt); c.resolve(o); }
            }
            @Override
            public void onFailed(String why) {
                PluginCall c = startCall; startCall = null;
                if (c != null) c.reject(why);
                PluginCall s = stopCall; stopCall = null;
                if (s != null) s.reject(why);
                JSObject o = new JSObject(); o.put("error", why);
                notifyListeners("recStopped", o);
            }
            @Override
            public void onStopped(String path, long startedAt, long stoppedAt) {
                video = path; vStart = startedAt; vStop = stoppedAt;
                JSObject o = new JSObject();
                o.put("startedAt", startedAt);
                o.put("stoppedAt", stoppedAt);
                PluginCall s = stopCall; stopCall = null;
                if (s != null) s.resolve(o);
                else notifyListeners("recStopped", o);
            }
        };
        Intent i = new Intent(getContext(), ScreenRecordService.class);
        i.putExtra("code", result.getResultCode());
        i.putExtra("data", result.getData());
        if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i);
        else getContext().startService(i);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (!ScreenRecordService.running) {
            JSObject o = new JSObject();
            o.put("startedAt", vStart);
            o.put("stoppedAt", vStop > 0 ? vStop : System.currentTimeMillis());
            call.resolve(o);
            return;
        }
        stopCall = call;
        Intent i = new Intent(getContext(), ScreenRecordService.class);
        i.setAction("stop");
        try { getContext().startService(i); } catch (Exception e) { stopCall = null; call.reject(String.valueOf(e.getMessage())); }
    }

    private void openPcm() throws Exception {
        closePcm();
        File dir = new File(getContext().getCacheDir(), "mayra-rec");
        dir.mkdirs();
        pcm = new File(dir, "voice-" + System.currentTimeMillis() + ".pcm");
        pcmOut = new FileOutputStream(pcm);
    }

    private void closePcm() {
        try { if (pcmOut != null) pcmOut.close(); } catch (Exception ignored) {}
        pcmOut = null;
    }

    @PluginMethod
    public void pcmAppend(PluginCall call) {
        try {
            if (pcmOut == null) openPcm();
            byte[] b = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            pcmOut.write(b);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void finish(final PluginCall call) {
        closePcm();
        final String name = call.getString("name", "Mayra-" + System.currentTimeMillis() + ".mp4");
        Double a = null;
        try { a = call.getDouble("audioStartedAt"); } catch (Exception ignored) {}
        final long audioStartedAt = a != null ? a.longValue() : vStart;
        final String vid = video;
        final File voice = pcm;
        final File system = ScreenRecordService.systemPcm != null ? new File(ScreenRecordService.systemPcm) : null;
        ScreenRecordService.systemPcm = null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(new File(getContext().getCacheDir(), "mayra-rec"), "joined-" + System.currentTimeMillis() + ".mp4");
                boolean joined = false;
                String note = "";
                try {
                    if (vid == null || !new File(vid).exists()) throw new Exception("no screen video");
                    boolean hasVoice = voice != null && voice.length() > RATE;
                    boolean hasSystem = system != null && system.length() > RATE;
                    if (hasVoice || hasSystem) {
                        join(vid, hasVoice ? voice : null, vStart - audioStartedAt, hasSystem ? system : null, tmp);
                        joined = true;
                    } else {
                        note = "no voice was recorded";
                    }
                } catch (Exception e) {
                    note = String.valueOf(e.getMessage());
                    try { tmp.delete(); } catch (Exception ignored) {}
                }
                try {
                    File src = joined ? tmp : (vid != null ? new File(vid) : null);
                    if (src == null || !src.exists()) throw new Exception(note.isEmpty() ? "nothing was recorded" : note);
                    String where = toGallery(src, name);
                    JSObject o = new JSObject();
                    o.put("path", where);
                    o.put("withVoice", joined);
                    if (!note.isEmpty()) o.put("note", note);
                    call.resolve(o);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                } finally {
                    try { tmp.delete(); } catch (Exception ignored) {}
                    try { if (vid != null) new File(vid).delete(); } catch (Exception ignored) {}
                    try { if (voice != null) voice.delete(); } catch (Exception ignored) {}
                    try { if (system != null) system.delete(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    // Puts the screen picture, the recorded voices and the other apps' sound (mixed, AAC) into one .mp4.
    // shiftMs = how much later the video started than the voices (positive: skip that much of the voices).
    // The other apps' sound started together with the video.
    private void join(String videoPath, File voice, long shiftMs, File system, File out) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(videoPath);
        int vIndex = -1;
        MediaFormat vf = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) { vIndex = i; vf = f; break; }
        }
        if (vIndex < 0) { ex.release(); throw new Exception("the screen video has no picture"); }
        ex.selectTrack(vIndex);
        long videoUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : Long.MAX_VALUE;

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int vTrack = mux.addTrack(vf);
        int aTrack = -1;
        boolean started = false;

        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        InputStream vIn = voice != null ? new FileInputStream(voice) : null;
        InputStream sIn = system != null ? new FileInputStream(system) : null;
        if (vIn != null && shiftMs > 0) {
            long skip = (shiftMs * RATE / 1000) * 2;
            while (skip > 0) { long k = vIn.skip(skip); if (k <= 0) break; skip -= k; }
        }
        long leadSamples = (vIn != null && shiftMs < 0) ? -shiftMs * RATE / 1000 : 0;   // the voices began a little after the video
        boolean vEnd = vIn == null, sEnd = sIn == null;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final int N = 2048;                       // samples per piece
        byte[] vb2 = new byte[N * 2], sb2 = new byte[N * 2], mixed = new byte[N * 2];
        long fedSamples = 0;
        boolean inputDone = false, outputDone = false;
        while (!outputDone) {
            if (!inputDone) {
                int ii = enc.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer buf = enc.getInputBuffer(ii);
                    buf.clear();
                    long pts = fedSamples * 1_000_000L / RATE;
                    int want = Math.min(N, buf.remaining() / 2);
                    if (pts >= videoUs || (vEnd && sEnd)) {
                        enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        int vn = 0, sn = 0;
                        if (!vEnd) {
                            int from = 0;
                            if (leadSamples > 0) { int z = (int) Math.min(leadSamples, want); java.util.Arrays.fill(vb2, 0, z * 2, (byte) 0); from = z; leadSamples -= z; }
                            int got = from < want ? readFully(vIn, vb2, from * 2, (want - from) * 2) : 0;
                            if (from == 0 && got <= 0) vEnd = true;
                            vn = from + Math.max(0, got) / 2;
                        }
                        if (!sEnd) {
                            int got = readFully(sIn, sb2, 0, want * 2);
                            if (got <= 0) sEnd = true;
                            sn = Math.max(0, got) / 2;
                        }
                        int n = Math.max(vn, sn);
                        if (n <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            for (int i = 0; i < n; i++) {
                                int a = i < vn ? (short) ((vb2[2 * i] & 0xff) | (vb2[2 * i + 1] << 8)) : 0;
                                int c = i < sn ? (short) ((sb2[2 * i] & 0xff) | (sb2[2 * i + 1] << 8)) : 0;
                                int m = a + (c * 7) / 10;        // other apps a little lower than the conversation
                                if (m > 32767) m = 32767; else if (m < -32768) m = -32768;
                                mixed[2 * i] = (byte) m;
                                mixed[2 * i + 1] = (byte) (m >> 8);
                            }
                            buf.put(mixed, 0, n * 2);
                            enc.queueInputBuffer(ii, 0, n * 2, pts, 0);
                            fedSamples += n;
                        }
                    }
                }
            }
            int oi = enc.dequeueOutputBuffer(info, 10000);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                aTrack = mux.addTrack(enc.getOutputFormat());
                mux.start();
                started = true;
            } else if (oi >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(oi);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && started) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    mux.writeSampleData(aTrack, ob, info);
                }
                enc.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }
        }
        if (vIn != null) vIn.close();
        if (sIn != null) sIn.close();
        enc.stop();
        enc.release();
        if (!started) { mux.release(); ex.release(); throw new Exception("the voices could not be encoded"); }

        ByteBuffer vb = ByteBuffer.allocate(4 * 1024 * 1024);
        MediaCodec.BufferInfo vi = new MediaCodec.BufferInfo();
        while (true) {
            vb.clear();
            int n = ex.readSampleData(vb, 0);
            if (n < 0) break;
            vi.offset = 0;
            vi.size = n;
            vi.presentationTimeUs = ex.getSampleTime();
            vi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            mux.writeSampleData(vTrack, vb, vi);
            ex.advance();
        }
        ex.release();
        mux.stop();
        mux.release();
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws Exception {
        int total = 0;
        while (total < len) {
            int n = in.read(b, off + total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total == 0 && len > 0 ? -1 : total;
    }

    private String toGallery(File src, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = getContext().getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Mayra");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("cannot create the video in the gallery");
            try (OutputStream o = r.openOutputStream(uri); InputStream i = new FileInputStream(src)) {
                if (o == null) throw new Exception("cannot open the video in the gallery");
                byte[] b = new byte[65536];
                int n;
                while ((n = i.read(b)) > 0) o.write(b, 0, n);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            r.update(uri, done, null, null);
            return "Movies/Mayra/" + name;
        }
        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        File dst = new File(dir, name);
        try (OutputStream o = new FileOutputStream(dst); InputStream i = new FileInputStream(src)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
        }
        return dst.getAbsolutePath();
    }
}
