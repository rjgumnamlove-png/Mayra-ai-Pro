package com.rjbilal.mayra;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
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

    // ---------- Send / Post the text that was just typed (comment boxes, chats) ----------
    // 1) a button near the text box whose text or description means send/post
    // 2) otherwise the tappable icon on the right side of the text box (most send arrows have no label)
    // 3) otherwise the keyboard's own "send / done" key
    private static final String[] SEND_WORDS = {"send", "post", "publish", "reply", "comment", "submit", "done", "share",
            "بھیج", "ارسال", "شائع", "پوسٹ", "سینڈ", "إرسال", "نشر", "envoyer", "enviar", "gönder", "kirim"};

    private static boolean sendLike(AccessibilityNodeInfo n) {
        CharSequence t = n.getText(), d = n.getContentDescription();
        String s = ((t == null ? "" : t.toString()) + " " + (d == null ? "" : d.toString())).toLowerCase();
        if (s.trim().isEmpty()) return false;
        if (s.length() > 40) return false;                       // long texts are other comments, not a button
        for (String w : SEND_WORDS) if (s.contains(w)) return true;
        return false;
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 6 || out.size() > 200) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    static boolean submit() {
        MayraAccessService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo edit = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (edit == null || !edit.isEditable()) edit = findEditable(root);
        if (edit == null) return false;
        Rect er = new Rect();
        edit.getBoundsInScreen(er);

        // look in the text box's surroundings: its parent, grandparent and great-grandparent
        AccessibilityNodeInfo area = edit;
        for (int up = 0; up < 3 && area.getParent() != null; up++) area = area.getParent();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(area, nodes, 0);

        // 1) labelled send / post button
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || n.isEditable() || !n.isEnabled()) continue;
            if (sendLike(n) && clickNode(n)) return true;
        }
        // 2) icon button to the right of the text box, on the same line
        AccessibilityNodeInfo best = null;
        int bestLeft = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || n.isEditable() || !n.isClickable() || !n.isEnabled()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            boolean sameLine = r.centerY() >= er.top - 40 && r.centerY() <= er.bottom + 40;
            boolean right = r.left >= er.right - 30;
            boolean small = r.width() < er.width();
            if (sameLine && right && small && r.left < bestLeft) { best = n; bestLeft = r.left; }
        }
        if (best != null && best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        // 3) the keyboard's send / enter key (Android 11+)
        if (Build.VERSION.SDK_INT >= 30) {
            return edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return false;
    }

    // ---------- double tap in the middle of the screen (like a reel / TikTok / Instagram video) ----------
    static boolean doubleTap() {
        MayraAccessService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 24) return false;
        DisplayMetrics dm = s.getResources().getDisplayMetrics();
        float x = dm.widthPixels / 2f, y = dm.heightPixels * 0.45f;
        Path p1 = new Path();
        p1.moveTo(x, y);
        Path p2 = new Path();
        p2.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p1, 0, 40))
                .addStroke(new GestureDescription.StrokeDescription(p2, 140, 40)).build();
        return s.dispatchGesture(g, null, null);
    }

    // ---------- fill a named text box (upload screens: title, description, caption, tags) ----------
    private static void all(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() > 600) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) all(n.getChild(i), out);
    }

    private static String words(AccessibilityNodeInfo n) {
        StringBuilder b = new StringBuilder();
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null) b.append(t).append(' ');
        if (d != null) b.append(d).append(' ');
        if (Build.VERSION.SDK_INT >= 26) { CharSequence h = n.getHintText(); if (h != null) b.append(h); }
        return b.toString().toLowerCase();
    }

    static boolean fillField(String label, String text) {
        MayraAccessService s = instance;
        if (s == null || text == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        String l = label == null ? "" : label.trim().toLowerCase();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        all(root, nodes);
        AccessibilityNodeInfo target = null;
        if (!l.isEmpty()) {
            for (AccessibilityNodeInfo n : nodes) {                  // the box itself shows the label (hint)
                if (n != null && n.isEditable() && words(n).contains(l)) { target = n; break; }
            }
            if (target == null) {                                     // a label written next to the box
                for (AccessibilityNodeInfo n : nodes) {
                    if (n == null || n.isEditable() || !words(n).contains(l)) continue;
                    AccessibilityNodeInfo p = n.getParent();
                    for (int up = 0; up < 2 && p != null && target == null; up++) {
                        List<AccessibilityNodeInfo> near = new ArrayList<>();
                        all(p, near);
                        for (AccessibilityNodeInfo m : near) { if (m != null && m.isEditable()) { target = m; break; } }
                        p = p.getParent();
                    }
                    if (target != null) break;
                }
            }
        } else {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null || !target.isEditable()) target = findEditable(root);
        }
        if (target == null) return false;
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }
}
