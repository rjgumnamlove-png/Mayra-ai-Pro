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

// Saves a text file (for example a generated web page) into the phone's Downloads folder.
// The page calls: Capacitor.Plugins.MayraSave.saveText({ name, text, mime })
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
}
