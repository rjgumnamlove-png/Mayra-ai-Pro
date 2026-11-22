package com.rjbilal.mayra;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.InputMethod;
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
        while (i < queue.size() && out.length() < 90) {
            AccessibilityNodeInfo n = queue.get(i++);
            if (n == null) continue;
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            boolean hasT = t != null && t.length() > 0;
            boolean hasD = d != null && d.length() > 0;
            if ((hasT || hasD) && n.isVisibleToUser()) {
                try {
                    JSONObject o = new JSONObject();
                    if (hasT) o.put("text", cut(t));
                    if (hasD) o.put("desc", cut(d));
                    o.put("clickable", n.isClickable());
                    o.put("editable", n.isEditable());
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    int hh = Math.max(1, realMetrics(s).heightPixels);
                    float cy = r.centerY() / (float) hh;
                    o.put("at", cy < 0.15f ? "top" : (cy > 0.85f ? "bottom" : "middle"));
                    out.put(o);
                } catch (Exception ignored) {}
            }
            for (int k = 0; k < n.getChildCount(); k++) queue.add(n.getChild(k));
        }
        return out;
    }

    static boolean clickLabel(String label) { return smartClick(label, "") != null; }

    // ---------- smart tap: the best match wins, not the first one ----------
    // exact text > starts with > whole word > part of a word; long texts (other people's names,
    // captions) lose points; "where" = bottom / top prefers the bottom navigation bar or the top bar.
    private static final String[] NAV_WORDS = {"profile", "home", "you", "shorts", "subscriptions", "library",
            "inbox", "friends", "reels", "create", "me", "search", "discover", "notifications", "menu", "video", "watch"};

    private static int score(String s, String l) {
        if (s == null || s.isEmpty()) return 0;
        if (s.equals(l)) return 100;
        int extra = Math.max(0, s.length() - l.length());
        if (s.startsWith(l)) {
            boolean wordEnd = l.length() >= s.length() || !Character.isLetterOrDigit(s.charAt(l.length()));
            return (wordEnd ? 80 : 50) - Math.min(30, extra);
        }
        int i = s.indexOf(l);
        if (i < 0) return 0;
        boolean wordStart = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
        boolean wordEnd = i + l.length() >= s.length() || !Character.isLetterOrDigit(s.charAt(i + l.length()));
        int base = (wordStart && wordEnd) ? 55 : 25;
        return Math.max(4, base - Math.min(40, extra));
    }

    static String smartClick(String label, String where) {
        MayraAccessService s = instance;
        if (s == null || label == null) return null;
        String l = label.trim().toLowerCase();
        if (l.isEmpty()) return null;
        int h = Math.max(1, realMetrics(s).heightPixels);
        String w = where == null ? "" : where.trim().toLowerCase();
        boolean nav = false;
        for (String x : NAV_WORDS) if (x.equals(l)) { nav = true; break; }
        List<AccessibilityNodeInfo> nodes = screenNodes();
        if (nodes.isEmpty()) return null;
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        String bestText = null;
        Rect bestR = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || !n.isVisibleToUser()) continue;
            String t = n.getText() == null ? "" : n.getText().toString().trim();
            String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
            int st = score(t.toLowerCase(), l), sd = score(d.toLowerCase(), l);
            int sc = Math.max(st, sd);
            if (sc <= 0) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) continue;
            float cy = r.centerY() / (float) h;
            if ("bottom".equals(w)) sc += cy > 0.8f ? 45 : -25;
            else if ("top".equals(w)) sc += cy < 0.2f ? 45 : -25;
            else if (nav && cy > 0.85f) sc += 25;
            if (n.isClickable()) sc += 5;
            if (sc > bestScore) { bestScore = sc; best = n; bestR = r; bestText = st >= sd ? t : d; }
        }
        if (best == null) return null;
        if (clickNode(best)) return bestText;
        if (bestR != null && tapAt(bestR.exactCenterX(), bestR.exactCenterY())) return bestText;
        return null;
    }

    static boolean tapAt(float x, float y) {
        MayraAccessService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return s.dispatchGesture(g, null, null);
    }

    // ---------- scroll the main list (forms, settings, upload screens) without changing a video ----------
    static boolean scroll(String dir) {
        MayraAccessService s = instance;
        if (s == null) return false;
        List<AccessibilityNodeInfo> nodes = screenNodes();
        if (nodes.isEmpty()) return false;
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || !n.isScrollable() || !n.isVisibleToUser()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long a = (long) r.width() * r.height();
            if (a > bestArea) { bestArea = a; best = n; }
        }
        boolean up = "up".equals(dir);
        if (best != null && best.performAction(up ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        if (Build.VERSION.SDK_INT < 24) return false;
        DisplayMetrics dm = s.getResources().getDisplayMetrics();
        float x = dm.widthPixels / 2f, h = dm.heightPixels;
        Path p = new Path();
        p.moveTo(x, up ? h * 0.35f : h * 0.7f);
        p.lineTo(x, up ? h * 0.7f : h * 0.35f);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 400)).build();
        return s.dispatchGesture(g, null, null);
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

    // a search bar is never used as a fallback box (typing a title there and pressing Enter would search)
    static boolean isSearchBox(AccessibilityNodeInfo n) {
        if (n == null) return false;
        String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase();
        String w = words(n);
        return id.contains("search") || id.contains("query") || w.contains("search") || w.contains("تلاش") || w.contains("سرچ") || w.contains("खोज");
    }

    static AccessibilityNodeInfo focusedBox() {
        for (AccessibilityNodeInfo r : roots()) {
            AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        return null;
    }

    private static AccessibilityNodeInfo findEditableNotSearch(AccessibilityNodeInfo n) {
        List<AccessibilityNodeInfo> nodes = screenNodes();
        for (AccessibilityNodeInfo x : nodes) if (x != null && x.isEditable() && x.isVisibleToUser() && !isSearchBox(x)) return x;
        return null;
    }

    static boolean typeText(String text) {
        MayraAccessService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo f = focusedBox();
        if (f == null) f = findEditableNotSearch(root);
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
    // walks the screen; hidden parts (old pages an app keeps in memory) are skipped, so the visible page is always reached
    private static void all(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() > 3000) return;
        if (!out.isEmpty() && !n.isVisibleToUser()) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) all(n.getChild(i), out);
    }

    // the screen = the active window, plus the other app/system windows on top (dialogs, bottom sheets)
    static List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> r = new ArrayList<>();
        MayraAccessService s = instance;
        if (s == null) return r;
        AccessibilityNodeInfo a = s.getRootInActiveWindow();
        if (a != null) r.add(a);
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> ws = s.getWindows();
            if (ws != null) for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                int t = w.getType();
                if (t != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                        && t != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                AccessibilityNodeInfo x = w.getRoot();
                if (x == null) continue;
                CharSequence pk = x.getPackageName();
                if (pk != null && pk.toString().startsWith("com.rjbilal") && a != null && a.getPackageName() != null
                        && !a.getPackageName().toString().startsWith("com.rjbilal")) continue;   // Mayra's own window behind another app
                boolean dup = false;
                for (AccessibilityNodeInfo y : r) if (y.equals(x)) { dup = true; break; }
                if (!dup) r.add(x);
            }
        } catch (Exception ignored) {}
        return r;
    }

    private static List<AccessibilityNodeInfo> screenNodes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo r : roots()) {
            List<AccessibilityNodeInfo> part = new ArrayList<>();
            all(r, part);
            out.addAll(part);
        }
        return out;
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
        List<AccessibilityNodeInfo> nodes = screenNodes();
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
            target = focusedBox();
            if (target == null) target = findEditableNotSearch(root);
        }
        if (target == null) return false;
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    // ---------- full screen map: every visible box, button and list with its hint, id and place ----------
    // lets Mayra reason about any phone (any language, layout, browser or app version) instead of fixed labels
    static JSONObject screenMap() {
        MayraAccessService s = instance;
        if (s == null) return null;
        JSONObject res = new JSONObject();
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return res;
        DisplayMetrics dm = realMetrics(s);
        float W = Math.max(1, dm.widthPixels), H = Math.max(1, dm.heightPixels);
        JSONArray out = new JSONArray();
        List<AccessibilityNodeInfo> nodes = screenNodes();
        try {
            res.put("app", root.getPackageName() == null ? "" : root.getPackageName().toString());
            for (AccessibilityNodeInfo n : nodes) {
                if (n == null || !n.isVisibleToUser() || out.length() >= 220) continue;
                CharSequence t = n.getText(), d = n.getContentDescription();
                CharSequence h = Build.VERSION.SDK_INT >= 26 ? n.getHintText() : null;
                String id = n.getViewIdResourceName();
                boolean edit = n.isEditable(), click = n.isClickable(), scr = n.isScrollable();
                boolean words = (t != null && t.length() > 0) || (d != null && d.length() > 0) || (h != null && h.length() > 0);
                if (!words && !edit && !scr) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.width() <= 0 || r.height() <= 0) continue;
                JSONObject o = new JSONObject();
                if (t != null && t.length() > 0) o.put("text", cut(t));
                if (d != null && d.length() > 0) o.put("desc", cut(d));
                if (h != null && h.length() > 0) o.put("hint", cut(h));
                if (id != null) o.put("id", id.substring(id.indexOf('/') + 1));
                if (edit) o.put("box", true);
                if (edit && n.isFocused()) o.put("focused", true);
                if (edit && isSearchBox(n)) o.put("search", true);
                if (click) o.put("tap", true);
                if (scr) o.put("scroll", true);
                if (n.isChecked()) o.put("on", true);
                o.put("x", Math.round(r.centerX() * 100 / W));
                o.put("y", Math.round(r.centerY() * 100 / H));
                out.put(o);
            }
            res.put("items", out);
        } catch (Exception ignored) {}
        return res;
    }

    // tap a point given in percent of the screen (for icons that have no text at all)
    static boolean tapPercent(float px, float py) {
        MayraAccessService s = instance;
        if (s == null) return false;
        DisplayMetrics dm = realMetrics(s);
        return tapAt(dm.widthPixels * px / 100f, dm.heightPixels * py / 100f);
    }

    // the keyboard's Enter on the box that has focus (turns a typed tag into a tag chip); never taps any button
    static boolean imeEnter() {
        MayraAccessService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 30) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo f = focusedBox();
        if (f == null) return false;
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    // ---------- see the screen without MediaProjection (Android 11+, needs canTakeScreenshot in the config) ----------
    interface SnapResult { void done(String base64Jpeg, String error); }

    static void snap(final SnapResult cb) {
        final MayraAccessService s = instance;
        if (s == null) { cb.done(null, "accessibility off"); return; }
        if (Build.VERSION.SDK_INT < 30) { cb.done(null, "android older than 11"); return; }
        try {
            s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, s.getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    try {
                        android.hardware.HardwareBuffer hb = r.getHardwareBuffer();
                        android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw == null) { hb.close(); cb.done(null, "no bitmap"); return; }
                        float scale = 540f / Math.min(hw.getWidth(), hw.getHeight());
                        int w = Math.max(2, Math.round(hw.getWidth() * scale)), h = Math.max(2, Math.round(hw.getHeight() * scale));
                        android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                        hw.recycle();
                        hb.close();
                        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(sw, w, h, true);
                        if (small != sw) sw.recycle();
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, out);
                        small.recycle();
                        cb.done(android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP), null);
                    } catch (Throwable e) { cb.done(null, "convert: " + e); }
                }
                @Override
                public void onFailure(int code) { cb.done(null, "screenshot error " + code); }
            });
        } catch (Throwable e) { cb.done(null, String.valueOf(e)); }
    }

    // ---------- real screen size (with status and navigation bars), the same size as the screenshot ----------
    static DisplayMetrics realMetrics(MayraAccessService s) {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            android.view.WindowManager wm = (android.view.WindowManager) s.getSystemService(android.content.Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) return dm;
        } catch (Throwable ignored) {}
        return s.getResources().getDisplayMetrics();
    }

    // ---------- Mayra as a keyboard (Android 13+): types into the box that has the cursor, even when the app
    // hides its boxes from Accessibility (like YouTube's Edit video page), and reads back what the box holds ----------
    private static InputMethod.AccessibilityInputConnection ic() {
        MayraAccessService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 33) return null;
        try {
            InputMethod im = s.getInputMethod();
            return im == null ? null : im.getCurrentInputConnection();
        } catch (Throwable e) { return null; }
    }

    static boolean imeReady() { return ic() != null; }

    // mode: replace (select all, then write), add (write at the cursor), clear
    static boolean imeWrite(String text, String mode) {
        InputMethod.AccessibilityInputConnection c = ic();
        if (c == null) return false;
        try {
            if (!"add".equals(mode)) c.performContextMenuAction(android.R.id.selectAll);
            if ("clear".equals(mode)) { c.commitText("", 1, null); return true; }
            c.commitText(text == null ? "" : text, 1, null);
            return true;
        } catch (Throwable e) { return false; }
    }

    static String imeRead() {
        InputMethod.AccessibilityInputConnection c = ic();
        if (c == null) return null;
        try {
            android.view.inputmethod.SurroundingText t = c.getSurroundingText(5000, 5000, 0);
            return t == null || t.getText() == null ? "" : t.getText().toString();
        } catch (Throwable e) { return null; }
    }
}
