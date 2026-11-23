package com.rjbilal.mayra;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Calendar;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import com.getcapacitor.JSArray;

@CapacitorPlugin(
        name = "MayraMedia",
        permissions = {@Permission(strings = {Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS}, alias = "phone")}
)
public class MayraMediaPlugin extends Plugin {

    // ---------- screen sharing ----------
    @PluginMethod
    public void startScreen(PluginCall call) {
        MediaProjectionManager m = (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(call, m.createScreenCaptureIntent(), "screenResult");
    }

    @ActivityCallback
    private void screenResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("denied");
            return;
        }
        Intent i = new Intent(getContext(), ScreenCaptureService.class);
        i.putExtra("code", result.getResultCode());
        i.putExtra("data", result.getData());
        ScreenCaptureService.listener = new ScreenCaptureService.FrameListener() {
            @Override
            public void onFrame(String base64Jpeg) {
                JSObject o = new JSObject();
                o.put("data", base64Jpeg);
                notifyListeners("screenFrame", o);
            }
        };
        if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i);
        else getContext().startService(i);
        call.resolve();
    }

    @PluginMethod
    public void screenStatus(PluginCall call) {
        JSObject o = new JSObject();
        o.put("status", ScreenCaptureService.status);
        o.put("images", ScreenCaptureService.images);
        o.put("frames", ScreenCaptureService.frames);
        o.put("listener", ScreenCaptureService.listener != null);
        call.resolve(o);
    }

    @PluginMethod
    public void stopScreen(PluginCall call) {
        ScreenCaptureService.listener = null;
        Intent i = new Intent(getContext(), ScreenCaptureService.class);
        i.setAction("stop");
        try { getContext().startService(i); } catch (Exception e) { try { getContext().stopService(i); } catch (Exception ignored) {} }
        call.resolve();
    }

    // ---------- save a picture to the gallery ----------
    @PluginMethod
    public void saveImage(PluginCall call) {
        String b64 = call.getString("data", "");
        String name = call.getString("name", "Mayra_" + System.currentTimeMillis() + ".png");
        if (Build.VERSION.SDK_INT < 29) { call.reject("Saving to gallery needs Android 10 or newer"); return; }
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mayra");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) { call.reject("insert failed"); return; }
            OutputStream os = getContext().getContentResolver().openOutputStream(uri);
            if (os == null) { call.reject("open failed"); return; }
            os.write(bytes);
            os.close();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues d = new ContentValues();
                d.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContext().getContentResolver().update(uri, d, null, null);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // ---------- phone: SIM count, call, SMS ----------
    private boolean needPhonePermission(PluginCall call) {
        if (getPermissionState("phone") == PermissionState.GRANTED) return false;
        requestPermissionForAlias("phone", call, "phoneResult");
        return true;
    }

    @PermissionCallback
    private void phoneResult(PluginCall call) {
        if (getPermissionState("phone") != PermissionState.GRANTED) { call.reject("Phone permission denied"); return; }
        String m = call.getMethodName();
        if ("callNumber".equals(m)) doCall(call);
        else if ("sendSms".equals(m)) doSms(call);
        else doSimCount(call);
    }

    @PluginMethod
    public void simCount(PluginCall call) {
        if (needPhonePermission(call)) return;
        doSimCount(call);
    }

    private void doSimCount(PluginCall call) {
        int n = 0;
        try {
            SubscriptionManager sm = (SubscriptionManager) getContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            n = sm.getActiveSubscriptionInfoCount();
        } catch (Exception ignored) {}
        JSObject o = new JSObject();
        o.put("count", n);
        call.resolve(o);
    }

    @PluginMethod
    public void callNumber(PluginCall call) {
        if (needPhonePermission(call)) return;
        doCall(call);
    }

    private void doCall(PluginCall call) {
        String number = call.getString("number", "");
        Integer simObj = call.getInt("sim", 0);
        int sim = simObj == null ? 0 : simObj;
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
            if (sim > 0) {
                TelecomManager tm = (TelecomManager) getContext().getSystemService(Context.TELECOM_SERVICE);
                List<PhoneAccountHandle> hs = tm.getCallCapablePhoneAccounts();
                if (hs != null && hs.size() >= sim) i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, hs.get(sim - 1));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void sendSms(PluginCall call) {
        if (needPhonePermission(call)) return;
        doSms(call);
    }

    private void doSms(PluginCall call) {
        String number = call.getString("number", "");
        String text = call.getString("text", "");
        Integer simObj = call.getInt("sim", 0);
        int sim = simObj == null ? 0 : simObj;
        try {
            SmsManager mgr = null;
            if (sim > 0) {
                SubscriptionManager sm = (SubscriptionManager) getContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                List<SubscriptionInfo> l = sm.getActiveSubscriptionInfoList();
                if (l != null && l.size() >= sim) mgr = SmsManager.getSmsManagerForSubscriptionId(l.get(sim - 1).getSubscriptionId());
            }
            if (mgr == null) {
                mgr = Build.VERSION.SDK_INT >= 31 ? getContext().getSystemService(SmsManager.class) : SmsManager.getDefault();
            }
            ArrayList<String> parts = mgr.divideMessage(text);
            mgr.sendMultipartTextMessage(number, null, parts, null, null);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // ---------- which apps the user spends time on: only with Android's "Usage access", which the user turns on himself ----------
    private boolean usageAllowed() {
        try {
            AppOpsManager ao = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
            int uid = android.os.Process.myUid();
            String me = getContext().getPackageName();
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, me)
                    : ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, me);
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    @PluginMethod
    public void usageStatus(PluginCall call) {
        JSObject o = new JSObject();
        o.put("allowed", usageAllowed());
        call.resolve(o);
    }

    @PluginMethod
    public void openUsageSettings(PluginCall call) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // the apps with the most screen time from midnight "days" days ago until now (Mayra itself, the home screen and system UI left out)
    @PluginMethod
    public void appUsage(PluginCall call) {
        if (!usageAllowed()) { call.reject("not allowed"); return; }
        try {
            Integer dObj = call.getInt("days", 1);
            int days = Math.max(1, Math.min(30, dObj == null ? 1 : dObj));
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            c.add(Calendar.DAY_OF_YEAR, -(days - 1));
            long start = c.getTimeInMillis(), end = System.currentTimeMillis();
            UsageStatsManager um = (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);
            Map<String, UsageStats> m = um.queryAndAggregateUsageStats(start, end);
            PackageManager pm = getContext().getPackageManager();
            String me = getContext().getPackageName(), home = "";
            try {
                ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
                if (ri != null && ri.activityInfo != null) home = ri.activityInfo.packageName;
            } catch (Exception ignored) { }
            List<UsageStats> list = new ArrayList<>(m.values());
            Collections.sort(list, (a, b) -> Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));
            JSArray arr = new JSArray();
            for (UsageStats u : list) {
                if (arr.length() >= 10) break;
                String pkg = u.getPackageName();
                long ms = u.getTotalTimeInForeground();
                if (ms < 60000 || pkg == null || pkg.equals(me) || pkg.equals(home) || pkg.contains("launcher") || pkg.contains("systemui")) continue;
                String label = "";
                try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Exception ignored) { }
                JSObject o = new JSObject();
                o.put("pkg", pkg);
                o.put("label", label);
                o.put("minutes", Math.round(ms / 60000.0));
                arr.put(o);
            }
            JSObject r = new JSObject();
            r.put("apps", arr);
            r.put("days", days);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }
}
