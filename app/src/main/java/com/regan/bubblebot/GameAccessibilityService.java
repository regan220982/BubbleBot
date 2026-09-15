package com.regan.bubblebot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public class GameAccessibilityService extends AccessibilityService {
    private static volatile GameAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    public static boolean isReady() { return instance != null; }

    public static boolean tap(float x, float y) {
        GameAccessibilityService s = instance;
        if (s == null) return false;
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 35);
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        return s.dispatchGesture(g, null, s.main);
    }

    public static boolean swipe(float x1, float y1, float x2, float y2, long duration) {
        GameAccessibilityService s = instance;
        if (s == null) return false;
        Path p = new Path(); p.moveTo(x1,y1); p.lineTo(x2,y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, duration);
        return s.dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, s.main);
    }
}
