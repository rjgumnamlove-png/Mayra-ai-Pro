package com.rjbilal.mayra;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class MayraAccessService extends AccessibilityService {
    static volatile MayraAccessService instance;
    static volatile String task = null;
    static volatile long until = 0;
    static volatile long lastClick = 0;

    static void arm(String t) {
        task = t;
        until = System.currentTimeMillis() + 25000;
    }

    @Override
    protected void onServiceConnected() { instance = this; }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // ---------- armed WhatsApp tasks (auto Send / auto Call) ----------
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        String t = task;
        if (t == null) return;
        long now = System.currentTimeMillis();
        if (now > until) { task = null; return; }
        CharSequence pk = event.getPackageName();
        if (pk == null || !pk.toString().startsWith("com.whatsapp")) return;
        if (now - lastClick < 700) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        if ("send".equals(t)) {
            boolean ok = clickById(root, new String[]{"com.whatsapp:id/send", "com.whatsapp.w4b:id/send"})
                    || clickByLabel(root, new String[]{"Send", "بھیجیں"});
            if (ok) { lastClick = now; task = null; }
        } else if ("confirm".equals(t)) {
            if (clickByLabel(root, new String[]{"Call", "CALL", "کال"})) { lastClick = now; task = null; }
        } else if ("call_voice".equals(t) || "call_video".equals(t)) {
            if (clickByLabel(root, new String[]{"Call", "CALL"})) { lastClick = now; task = null; return; }
            String[] labels = "call_video".equals(t)
                    ? new String[]{"Video call", "ویڈیو کال"}
                    : new String[]{"Voice call", "Audio call", "آڈیو کال", "وائس کال"};
            if (clickByLabel(root, labels)) { lastClick = now; task = "confirm"; until = now + 8000; }
        }
    }

    private boolean clickById(AccessibilityNodeInfo root, String[] ids) {
        for (String id : ids) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
            if (list == null) continue;
            for (AccessibilityNodeInfo n : list) { if (clickNode(n)) return true; }
        }
        return false;
    }

    private boolean clickByLabel(AccessibilityNodeInfo root, String[] labels) {
        for (String l : labels) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(l);
            if (list == null) continue;
            for (AccessibilityNodeInfo n : list) {
                CharSequence d = n.getContentDescription();
                CharSequence tx = n.getText();
                if ((d != null && d.toString().equalsIgnoreCase(l)) || (tx != null && tx.toString().equalsIgnoreCase(l))) {
                    if (clickNode(n)) return true;
                }
            }
        }
        return false;
    }

    // ---------- general phone control (used by Mayra's ui_* tools) ----------
    static boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo c = node;
        while (c != null) {
            if (c.isClickable() && c.isEnabled()) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            c = c.getParent();
        }
        return false;
    }

    private static String cut(CharSequence s) {
        String x = s.toString().trim();
        return x.length() > 80 ? x.substring(0, 80) : x;
    }

    static JSONArray readScreen() {
        MayraAccessService s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        JSONArray out = new JSONArray();
        if (root == null) return out;
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);
        int i = 0;
        while (i < queue.size() && out.length() < 70) {
            AccessibilityNodeInfo n = queue.get(i++);
            if (n == null) continue;
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            boolean hasT = t != null && t.length() > 0;
            boolean hasD = d != null && d.length() > 0;
            if (hasT || hasD) {
                try {
                    JSONObject o = new JSONObject();
                    if (hasT) o.put("text", cut(t));
                    if (hasD) o.put("desc", cut(d));
                    o.put("clickable", n.isClickable());
                    o.put("editable", n.isEditable());
                    out.put(o);
                } catch (Exception ignored) {}
            }
            for (int k = 0; k < n.getChildCount(); k++) queue.add(n.getChild(k));
        }
        return out;
    }

    static boolean clickLabel(String label) {
        MayraAccessService s = instance;
        if (s == null || label == null || label.isEmpty()) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(label);
        if (list == null) return false;
        for (AccessibilityNodeInfo n : list) { if (clickNode(n)) return true; }
        return false;
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    static boolean typeText(String text) {
        MayraAccessService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || !f.isEditable()) f = findEditable(root);
        if (f == null) return false;
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    static boolean swipe(String dir) {
        MayraAccessService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 24) return false;
        DisplayMetrics dm = s.getResources().getDisplayMetrics();
        float w = dm.widthPixels, h = dm.heightPixels;
        float x1 = w / 2, y1 = h / 2, x2 = x1, y2 = y1;
        if ("left".equals(dir)) { x1 = w * 0.8f; x2 = w * 0.2f; }
        else if ("right".equals(dir)) { x1 = w * 0.2f; x2 = w * 0.8f; }
        else if ("up".equals(dir)) { y1 = h * 0.75f; y2 = h * 0.25f; }
        else if ("down".equals(dir)) { y1 = h * 0.25f; y2 = h * 0.75f; }
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build();
        return s.dispatchGesture(g, null, null);
    }

    static boolean global(String action) {
        MayraAccessService s = instance;
        if (s == null) return false;
        int a;
        if ("home".equals(action)) a = GLOBAL_ACTION_HOME;
        else if ("recents".equals(action)) a = GLOBAL_ACTION_RECENTS;
        else if ("notifications".equals(action)) a = GLOBAL_ACTION_NOTIFICATIONS;
        else if ("quick_settings".equals(action)) a = GLOBAL_ACTION_QUICK_SETTINGS;
        else if ("back".equals(action)) a = GLOBAL_ACTION_BACK;
        else return false;
        return s.performGlobalAction(a);
    }
}
