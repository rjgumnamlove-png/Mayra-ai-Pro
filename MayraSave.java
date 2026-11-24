package com.rjbilal.mayraai;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

// Saves a text file (for example a generated web page) into the phone's Downloads folder.
// The page calls: Capacitor.Plugins.MayraSave.saveText({ name, text, mime })
// Binary files (a recorded conversation) are written in pieces with saveBase64:
//   first piece  { name, data, mime }            -> returns { uri, path }
//   next pieces  { uri, data }                   -> added at the end
//   a fix-up     { uri, data, offset }           -> written at that byte position (the WAV header)
@CapacitorPlugin(name = "MayraSave")
public class MayraSave extends Plugin {

    @PluginMethod
    public void saveText(PluginCall call) {
        String name = call.getString("name", "mayra-file.html");
        String text = call.getString("text", "");
        String mime = call.getString("mime", "text/html");
        try {
            String where;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver resolver = getContext().getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    call.reject("cannot create the file");
                    return;
                }
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        call.reject("cannot open the file");
                        return;
                    }
                    out.write(text.getBytes("UTF-8"));
                }
                where = "Downloads/" + name;
            } else {
                File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(text.getBytes("UTF-8"));
                }
                where = file.getAbsolutePath();
            }
            JSObject result = new JSObject();
            result.put("path", where);
            call.resolve(result);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void saveBase64(PluginCall call) {
        String name = call.getString("name", "mayra-recording.wav");
        String data = call.getString("data", "");
        String mime = call.getString("mime", "audio/wav");
        String target = call.getString("uri", "");
        long offset = -1;
        try { Double o = call.getDouble("offset"); if (o != null) offset = o.longValue(); } catch (Exception ignored) {}
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            String where;
            if (target == null || target.isEmpty()) {
                // a new file
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver resolver = getContext().getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) { call.reject("cannot create the file"); return; }
                    try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                        if (out == null) { call.reject("cannot open the file"); return; }
                        out.write(bytes);
                    }
                    target = uri.toString();
                    where = "Downloads/" + name;
                } else {
                    File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File file = new File(dir, name);
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                    target = file.getAbsolutePath();
                    where = target;
                }
            } else if (target.startsWith("content://")) {
                Uri uri = Uri.parse(target);
                ContentResolver resolver = getContext().getContentResolver();
                if (offset >= 0) {
                    try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw")) {
                        if (pfd == null) { call.reject("cannot open the file"); return; }
                        try (FileOutputStream fo = new FileOutputStream(pfd.getFileDescriptor())) {
                            FileChannel ch = fo.getChannel();
                            ch.position(offset);
                            ch.write(ByteBuffer.wrap(bytes));
                        }
                    }
                } else {
                    try (OutputStream out = resolver.openOutputStream(uri, "wa")) {
                        if (out == null) { call.reject("cannot open the file"); return; }
                        out.write(bytes);
                    }
                }
                where = "Downloads/" + name;
            } else {
                try (RandomAccessFile f = new RandomAccessFile(new File(target), "rw")) {
                    f.seek(offset >= 0 ? offset : f.length());
                    f.write(bytes);
                }
                where = target;
            }
            JSObject result = new JSObject();
            result.put("uri", target);
            result.put("path", where);
            result.put("bytes", bytes.length);
            call.resolve(result);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }
}
