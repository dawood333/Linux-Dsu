package com.mcai.ubuntudsu.core.dna;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * 触觉反馈工具类
 * 基于 Linux-Dsu 的 Haptics 实现
 */
public class Haptics {
    
    private static long lastFeedbackAt = 0;
    private static final long MIN_INTERVAL_MS = 180; // 防抖间隔

    /**
     * 全局触摸事件处理：在 ACTION_UP 时对可点击 View 触发震动
     */
    public static void onTouch(View root, MotionEvent event) {
        if (root == null || event.getActionMasked() != MotionEvent.ACTION_UP) return;
        View target = findClickable(root, event.getX(), event.getY());
        if (target != null) perform(target);
    }

    /**
     * 单次点击震动（带 180ms 防抖）
     */
    public static void perform(View view) {
        long now = android.os.SystemClock.uptimeMillis();
        if (view != null && now - lastFeedbackAt > MIN_INTERVAL_MS) {
            lastFeedbackAt = now;
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /**
     * 立即触发震动（无防抖）
     */
    public static void performImmediate(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /**
     * 长按震动反馈
     */
    public static void performLongPress(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    /**
     * 递归查找触摸点下的可点击 View
     */
    private static View findClickable(View view, float x, float y) {
        if (!view.isShown() || x < 0 || y < 0 || x > view.getWidth() || y > view.getHeight()) {
            return null;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                View target = findClickable(
                    child,
                    x - child.getLeft(),
                    y - child.getTop()
                );
                if (target != null) return target;
            }
        }

        return (view.isClickable() || view.isLongClickable()) ? view : null;
    }
}
