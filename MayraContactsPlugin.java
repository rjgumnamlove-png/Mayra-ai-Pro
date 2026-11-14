package com.rjbilal.mayra;

import android.Manifest;
import android.database.Cursor;
import android.provider.ContactsContract;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
        name = "MayraContacts",
        permissions = {@Permission(strings = {Manifest.permission.READ_CONTACTS}, alias = "contacts")}
)
public class MayraContactsPlugin extends Plugin {

    // Counts audio players that are active on the phone (other apps' videos/music included).
    // Mayra keeps exactly one of its own open, so a count of 2 or more means another app is playing.
    @PluginMethod
    public void mediaState(PluginCall call) {
        JSObject r = new JSObject();
        int players = -1;
        boolean music = false;
        try {
            android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am != null) {
                music = am.isMusicActive();
                if (android.os.Build.VERSION.SDK_INT >= 26) players = am.getActivePlaybackConfigurations().size();
            }
        } catch (Exception ignored) {}
        r.put("players", players);
        r.put("music", music);
        call.resolve(r);
    }

    // Fully closes the app (task + process) so the next launch starts fresh.
    @PluginMethod
    public void exitApp(PluginCall call) {
        call.resolve();
        final android.app.Activity a = getActivity();
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (a != null) {
                        if (android.os.Build.VERSION.SDK_INT >= 21) a.finishAndRemoveTask();
                        else a.finishAffinity();
                    }
                } catch (Exception ignored) {}
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
                }, 400);
            }
        }, 300);
    }

    @PluginMethod
    public void getContacts(PluginCall call) {
        if (getPermissionState("contacts") != PermissionState.GRANTED) {
            requestPermissionForAlias("contacts", call, "contactsResult");
            return;
        }
        doContacts(call);
    }

    @PermissionCallback
    private void contactsResult(PluginCall call) {
        if (getPermissionState("contacts") != PermissionState.GRANTED) {
            call.reject("Contacts permission denied");
            return;
        }
        doContacts(call);
    }

    private void doContacts(PluginCall call) {
        JSArray arr = new JSArray();
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (c != null) {
                while (c.moveToNext()) {
                    String n = c.getString(0);
                    String p = c.getString(1);
                    if (n == null || p == null) continue;
                    JSObject o = new JSObject();
                    o.put("n", n);
                    o.put("p", p);
                    arr.put(o);
                }
            }
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return;
        } finally {
            if (c != null) c.close();
        }
        JSObject res = new JSObject();
        res.put("contacts", arr);
        call.resolve(res);
    }
}
