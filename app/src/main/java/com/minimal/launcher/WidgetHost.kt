package com.minimal.launcher

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/** AppWidgetHost that creates [WidgetHostView]s and can drop all its views to free memory. */
class WidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView = WidgetHostView(context)

    /** Forget every created view (the host otherwise keeps references to them). */
    fun releaseViews() = clearViews()
}

/**
 * Widgets put their own clickable views inside the host view, which swallow long-presses.
 * We track the gesture in dispatchTouchEvent (sees every event, whoever handles it) with a
 * single timer, so a long-press anywhere on a widget opens our menu exactly once, and the
 * widget's own button then gets CANCEL instead of a click.
 */
class WidgetHostView(context: Context) : AppWidgetHostView(context) {

    /** Called on long-press. Not using setOnLongClickListener avoids a second, built-in timer. */
    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false

    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                removeCallbacks(longPress)
                postDelayed(longPress, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return super.dispatchTouchEvent(ev)
    }

    // After the long-press fired, take over the gesture: the widget's child gets CANCEL.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = longPressed

    // Consume touches on the widget's empty areas too, so we always see UP/CANCEL.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
