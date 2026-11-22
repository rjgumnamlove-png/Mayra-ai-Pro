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
        String hit = MayraAccessService.smartClick(call.getString("label", ""), call.getString("where", ""));
        o.put("ok", hit != null);
        if (hit != null) o.put("text", hit);
        call.resolve(o);
    }

    @PluginMethod
    public void uiKeyboard(PluginCall call) {
        JSObject o = new JSObject();
        String mode = call.getString("mode", "replace");
        if ("read".equals(mode)) {
            String t = MayraAccessService.imeRead();
            o.put("ok", t != null);
            if (t != null) o.put("text", t);
        } else {
            boolean ok = MayraAccessService.imeWrite(call.getString("text", ""), mode);
            o.put("ok", ok);
            String t = ok ? MayraAccessService.imeRead() : null;
            if (t != null) o.put("text", t);
        }
        o.put("ready", MayraAccessService.imeReady());
        call.resolve(o);
    }

    @PluginMethod
    public void uiSnap(final PluginCall call) {
        MayraAccessService.snap(new MayraAccessService.SnapResult() {
            @Override
            public void done(String b64, String error) {
                JSObject o = new JSObject();
                o.put("ok", b64 != null);
                if (b64 != null) o.put("data", b64);
                if (error != null) o.put("error", error);
                call.resolve(o);
            }
        });
    }

    @PluginMethod
    public void uiEnter(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.imeEnter());
        call.resolve(o);
    }

    @PluginMethod
    public void uiMap(PluginCall call) {
        org.json.JSONObject m = MayraAccessService.screenMap();
        if (m == null) { call.reject("not enabled"); return; }
        try { call.resolve(JSObject.fromJSONObject(m)); } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void uiTapAt(PluginCall call) {
        JSObject o = new JSObject();
        Double x = call.getDouble("x", 50.0), y = call.getDouble("y", 50.0);
        o.put("ok", MayraAccessService.tapPercent(x == null ? 50f : x.floatValue(), y == null ? 50f : y.floatValue()));
        call.resolve(o);
    }

    @PluginMethod
    public void uiScroll(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.scroll(call.getString("dir", "down")));
        call.resolve(o);
    }

    @PluginMethod
    public void uiFill(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.fillField(call.getString("label", ""), call.getString("text", "")));
        call.resolve(o);
    }

    @PluginMethod
    public void uiDoubleTap(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.doubleTap());
        call.resolve(o);
    }

    @PluginMethod
    public void uiSubmit(PluginCall call) {
        JSObject o = new JSObject();
        o.put("ok", MayraAccessService.submit());
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
}
