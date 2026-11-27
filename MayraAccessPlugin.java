package com.rjbilal.mayra;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Settings;
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
        name = "MayraAccess",
        permissions = {@Permission(strings = {Manifest.permission.READ_CONTACTS}, alias = "contacts")}
)
public class MayraAccessPlugin extends Plugin {

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject o = new JSObject();
        o.put("enabled", MayraAccessService.instance != null);
        call.resolve(o);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void arm(PluginCall call) {
        String t = call.getString("task", "send");
        MayraAccessService.arm(t);
        call.resolve();
    }

    @PluginMethod
    public void uiRead(PluginCall call) {
        org.json.JSONArray a = MayraAccessService.readScreen();
        if (a == null) { call.reject("not enabled"); return; }
        JSObject o = new JSObject();
        o.put("items", a);
        call.resolve(o);
    }

    @PluginMethod
    public void uiClick(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.clickLabel(call.getString("label", "")));
        call.resolve(o);
    }

    @PluginMethod
    public void uiSwipe(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.swipe(call.getString("dir", "up")));
        call.resolve(o);
    }

    @PluginMethod
    public void uiType(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.typeText(call.getString("text", "")));
        call.resolve(o);
    }

    @PluginMethod
    public void uiGlobal(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.global(call.getString("action", "back")));
        call.resolve(o);
    }

    @PluginMethod
    public void disarm(PluginCall call) {
        MayraAccessService.task = null;
        call.resolve();
    }

    @PluginMethod
    public void torch(PluginCall call) {
        JSObject o = new JSObject();
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) getContext().getSystemService(android.content.Context.CAMERA_SERVICE);
            cm.setTorchMode(cm.getCameraIdList()[0], Boolean.TRUE.equals(call.getBoolean("on", true)));
            o.put("ok", true);
        } catch (Exception e) { o.put("ok", false); }
        call.resolve(o);
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        JSObject o = new JSObject();
        try {
            android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            Integer lv = call.getInt("level", 50);
            int l = lv == null ? 50 : lv;
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, Math.round(max * l / 100f))), 0);
            o.put("ok", true);
        } catch (Exception e) { o.put("ok", false); }
        call.resolve(o);
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

    @PluginMethod
    public void openUri(PluginCall call) {
        String uri = call.getString("uri", "");
        String pkg = call.getString("pkg", "");
        JSObject o = new JSObject();
        String[] pkgs = pkg.isEmpty() ? new String[]{""} : pkg.split(",");
        for (String p : pkgs) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            if (!p.isEmpty()) i.setPackage(p);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (startAny(i)) { o.put("ok", true); call.resolve(o); return; }
        }
        o.put("ok", false);
        call.resolve(o);
    }

    @PluginMethod
    public void openApp(PluginCall call) {
        JSObject o = new JSObject();
        for (String p : call.getString("pkg", "").split(",")) {
            if (p.isEmpty()) continue;
            Intent i = getContext().getPackageManager().getLaunchIntentForPackage(p);
            if (i == null) continue;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (startAny(i)) { o.put("ok", true); call.resolve(o); return; }
        }
        o.put("ok", false);
        call.resolve(o);
    }

    private boolean startAny(Intent i) {
        MayraAccessService s = MayraAccessService.instance;
        if (s != null) { try { s.startActivity(i); return true; } catch (Exception ignored) {} }
        try { getContext().startActivity(i); return true; } catch (Exception e) { return false; }
    }

    @PluginMethod
    public void whatsappCall(PluginCall call) {
        String number = call.getString("number", "");
        boolean video = Boolean.TRUE.equals(call.getBoolean("video", false));
        if (getContext().checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            call.reject("no contacts permission");
            return;
        }
        String digits = number.replaceAll("\\D", "");
        if (digits.length() > 10) digits = digits.substring(digits.length() - 10);
        String mime = video
                ? "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
                : "vnd.android.cursor.item/vnd.com.whatsapp.voip.call";
        long id = -1;
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(
                    ContactsContract.Data.CONTENT_URI,
                    new String[]{ContactsContract.Data._ID, ContactsContract.Data.DATA1},
                    ContactsContract.Data.MIMETYPE + "=?",
                    new String[]{mime}, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String d = c.getString(1);
                    if (d != null && d.contains(digits)) { id = c.getLong(0); break; }
                }
            }
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return;
        } finally {
            if (c != null) c.close();
        }
        if (id < 0) { call.reject("contact not found in WhatsApp"); return; }
        if (MayraAccessService.instance != null) MayraAccessService.arm("confirm");
        String lastErr = "WhatsApp not installed";
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("content://com.android.contacts/data/" + id), mime);
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(i);
                call.resolve();
                return;
            } catch (Exception e) {
                lastErr = String.valueOf(e.getMessage());
            }
        }
        call.reject(lastErr);
    }

    // ---------- (2026-11-27) WhatsApp voice note ----------
    @PluginMethod
    public void waVoiceFind(PluginCall call) {
        JSObject o = new JSObject();
        float[] b = MayraAccessService.findWaVoiceButton();
        o.put("ok", b != null);
        if (b != null) { o.put("x", b[0]); o.put("y", b[1]); o.put("byId", b[2] > 0); }
        call.resolve(o);
    }

    @PluginMethod
    public void waVoiceStart(PluginCall call) {
        JSObject o = new JSObject();
        float[] b = MayraAccessService.findWaVoiceButton();
        if (b == null) { o.put("ok", false); o.put("error", "WhatsApp mic button not found"); call.resolve(o); return; }
        o.put("ok", MayraAccessService.voiceHoldStart(b[0], b[1]));
        call.resolve(o);
    }

    @PluginMethod
    public void waVoiceStop(PluginCall call) {
        MayraAccessService.voiceHoldStop();
        JSObject o = new JSObject();
        o.put("ok", true);
        call.resolve(o);
    }

    @PluginMethod
    public void waVoiceState(PluginCall call) {
        JSObject o = new JSObject();
        o.put("holding", MayraAccessService.holding);
        call.resolve(o);
    }

    @PluginMethod
    public void hideKeyboard(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.hideKeyboard());
        call.resolve(o);
    }

    @PluginMethod
    public void waInstalled(PluginCall call) {
        JSObject o = new JSObject();
        PackageManager pm = getContext().getPackageManager();
        o.put("normal", pm.getLaunchIntentForPackage("com.whatsapp") != null);
        o.put("business", pm.getLaunchIntentForPackage("com.whatsapp.w4b") != null);
        call.resolve(o);
    }
}
