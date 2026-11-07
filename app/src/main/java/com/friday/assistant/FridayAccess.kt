package com.friday.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FridayAccess : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FridayAccess? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun roots(): List<AccessibilityNodeInfo> {
        val l = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root
                if (r != null) l.add(r)
            }
        } catch (e: Exception) {
        }
        if (l.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null) l.add(r)
        }
        return l
    }

    private fun dfs(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val f = dfs(c, pred)
            if (f != null) return f
        }
        return null
    }

    fun find(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (r in roots()) {
            val f = dfs(r, pred)
            if (f != null) return f
        }
        return null
    }

    fun click(n: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = n
        while (p != null) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            p = p.parent
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    private fun gesture(path: Path, duration: Long) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        Handler(Looper.getMainLooper()).post { dispatchGesture(g, null, null) }
    }

    fun tap(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        gesture(p, 60)
        return true
    }

    fun swipeUp() {
        val dm = resources.displayMetrics
        val p = Path()
        p.moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.85f)
        p.lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.25f)
        gesture(p, 350)
    }

    // Pattern: grid 1 2 3 / 4 5 6 / 7 8 9, ek hi ungli-swipe me draw hota hai
    private fun drawPattern(seq: String): Boolean {
        val pts = ArrayList<Pair<Float, Float>>()
        // Pehle har cell ka node dhoondho (agar phone dikhata hai)
        for (ch in seq) {
            val want = "cell $ch"
            var cell: AccessibilityNodeInfo? = null
            repeat(4) {
                if (cell == null) {
                    cell = find { (it.contentDescription?.toString() ?: "").lowercase().trim() == want }
                    if (cell == null) Thread.sleep(250)
                }
            }
            val c = cell ?: break
            val b = Rect()
            c.getBoundsInScreen(b)
            pts.add(Pair(b.exactCenterX(), b.exactCenterY()))
        }
        // Nahi mila to pattern area ke 3x3 grid se nikalo
        if (pts.size != seq.length) {
            pts.clear()
            var area: AccessibilityNodeInfo? = null
            repeat(6) {
                if (area == null) {
                    area = find {
                        val id = it.viewIdResourceName ?: ""
                        val d = (it.contentDescription?.toString() ?: "").lowercase()
                        id.contains("lockPatternView", true) || d.contains("pattern area")
                    }
                    if (area == null) Thread.sleep(300)
                }
            }
            val a = area ?: return false
            val r = Rect()
            a.getBoundsInScreen(r)
            for (ch in seq) {
                val n = ch - '1'
                if (n < 0 || n > 8) continue
                val x = r.left + r.width() * (n % 3 * 2 + 1) / 6f
                val y = r.top + r.height() * (n / 3 * 2 + 1) / 6f
                pts.add(Pair(x, y))
            }
        }
        if (pts.size < 2) return false
        val path = Path()
        path.moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
        gesture(path, 900)
        return true
    }

    // Lock screen: swipe up, phir pattern ya PIN
    fun unlock(pin: String, pattern: String = "") {
        swipeUp()
        Thread.sleep(1000)
        if (pattern.isNotEmpty()) {
            drawPattern(pattern)
            return
        }
        if (pin.isEmpty()) return
        for (ch in pin) {
            val s = ch.toString()
            var node: AccessibilityNodeInfo? = null
            repeat(6) {
                if (node == null) {
                    node = find { it.text?.toString() == s || it.contentDescription?.toString() == s }
                    if (node == null) Thread.sleep(300)
                }
            }
            node?.let { click(it) }
            Thread.sleep(250)
        }
        Thread.sleep(300)
        find {
            val d = (it.contentDescription?.toString() ?: "").lowercase()
            d == "enter" || d == "ok" || d == "confirm"
        }?.let { click(it) }
    }
}
