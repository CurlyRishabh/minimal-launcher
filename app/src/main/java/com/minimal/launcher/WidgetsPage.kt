package com.minimal.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.SizeF
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

/**
 * The widgets page (swipe left on home).
 *
 * Memory rule: widgets cost nothing unless this page is on screen.
 * - The AppWidgetHost only listens for updates while the page is visible ([show]/[hide]).
 * - When the page closes, every widget view is released ([release]); they are re-created
 *   from the system's cached RemoteViews the next time the page opens.
 *
 * Adding a widget follows the standard host flow:
 * allocate id → bind (asks the user once per provider app) → optional configure screen → show.
 */
class WidgetsPage(private val activity: Activity, val root: View) {

    private val prefs = Prefs.get(activity)
    private val awm: AppWidgetManager = AppWidgetManager.getInstance(activity)
    private val host = WidgetHost(activity.applicationContext, HOST_ID)
    private val list: LinearLayout = root.findViewById(R.id.widgets_list)
    private val empty: View = root.findViewById(R.id.widgets_empty)
    private val hint: View = root.findViewById(R.id.widgets_hint)
    private val density = activity.resources.displayMetrics.density
    private val gap = activity.resources.getDimensionPixelSize(R.dimen.widget_gap)
    private val pagePadding = activity.resources.getDimensionPixelSize(R.dimen.widget_page_padding)

    private var listening = false
    private var built = false
    private var pendingId = INVALID
    private var dialog: Dialog? = null

    init {
        root.findViewById<View>(R.id.widgets_add).setOnClickListener { pickProvider() }
    }

    /** Page became visible (opened, or activity restarted while open). */
    fun show() {
        if (!built) build()
        if (!listening) {
            try {
                host.startListening()
                listening = true
            } catch (_: Exception) {
                // Some devices throw while the user is still locked; widgets just won't update.
            }
        }
    }

    /** Page hidden or activity stopped: stop receiving widget updates. */
    fun hide() {
        if (!listening) return
        try {
            host.stopListening()
        } catch (_: Exception) {
        }
        listening = false
    }

    /** Page closed: drop every widget view. */
    fun release() {
        list.removeAllViews()
        host.releaseViews()
        built = false
    }

    fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    // ---- Building views --------------------------------------------------------------------

    private fun build() {
        built = true
        list.removeAllViews()
        val kept = ArrayList<Int>()
        for (id in prefs.widgetIds) {
            val info = awm.getAppWidgetInfo(id)
            if (info == null) {
                host.deleteAppWidgetId(id) // Provider app was uninstalled.
                continue
            }
            kept.add(id)
            list.addView(createView(id, info))
        }
        if (kept != prefs.widgetIds) prefs.widgetIds = kept
        cleanUpOrphans(kept)
        updateEmptyState()
    }

    /** Ids allocated but never saved (e.g. the process died mid-add) are released. */
    private fun cleanUpOrphans(kept: List<Int>) {
        if (pendingId != INVALID) return
        try {
            for (id in host.appWidgetIds) if (id !in kept) host.deleteAppWidgetId(id)
        } catch (_: Exception) {
        }
    }

    private fun createView(id: Int, info: AppWidgetProviderInfo): View {
        val view = host.createView(activity, id, info)
        val heightDp = heightDpFor(id, info)
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(heightDp))
            .apply { bottomMargin = gap }
        reportSize(view, heightDp)
        (view as? WidgetHostView)?.onLongPress = { showMenu(id) }
        return view
    }

    private fun heightDpFor(id: Int, info: AppWidgetProviderInfo): Int {
        val saved = prefs.widgetHeightDp(id)
        if (saved > 0) return saved
        // minHeight is in px; fall back to a sensible size for tiny declarations.
        return (info.minHeight / density).toInt().coerceAtLeast(MIN_HEIGHT_DP)
    }

    /** Tell the widget how much space it really has, so it picks the right layout. */
    private fun reportSize(view: AppWidgetHostView, heightDp: Int) {
        val widthDp = (activity.resources.displayMetrics.widthPixels - 2 * pagePadding) / density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp.toFloat())))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(Bundle(), widthDp.toInt(), heightDp, widthDp.toInt(), heightDp)
        }
    }

    private fun updateEmptyState() {
        val none = list.childCount == 0
        empty.visibility = if (none) View.VISIBLE else View.GONE
        hint.visibility = if (none) View.GONE else View.VISIBLE
    }

    private fun px(dp: Int) = (dp * density + 0.5f).toInt()

    // ---- Long-press menu -------------------------------------------------------------------

    private fun showMenu(id: Int) {
        val items = arrayOf(
            activity.getString(R.string.widget_taller),
            activity.getString(R.string.widget_shorter),
            activity.getString(R.string.widget_up),
            activity.getString(R.string.widget_down),
            activity.getString(R.string.widget_remove),
        )
        val title = awm.getAppWidgetInfo(id)?.loadLabel(activity.packageManager)
        showDialog(
            AlertDialog.Builder(activity, Ui.DIALOG_THEME)
                .setTitle(title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> resize(id, +STEP_DP)
                        1 -> resize(id, -STEP_DP)
                        2 -> move(id, -1)
                        3 -> move(id, +1)
                        4 -> remove(id)
                    }
                }
        )
    }

    private fun resize(id: Int, deltaDp: Int) {
        val index = prefs.widgetIds.indexOf(id)
        val info = awm.getAppWidgetInfo(id) ?: return
        val view = list.getChildAt(index) as? AppWidgetHostView ?: return
        val maxDp = (activity.resources.displayMetrics.heightPixels / density * 0.8f).toInt()
        val newDp = (heightDpFor(id, info) + deltaDp).coerceIn(MIN_HEIGHT_DP, maxDp)
        prefs.setWidgetHeightDp(id, newDp)
        view.layoutParams = (view.layoutParams as LinearLayout.LayoutParams).apply { height = px(newDp) }
        reportSize(view, newDp)
    }

    private fun move(id: Int, by: Int) {
        val ids = prefs.widgetIds.toMutableList()
        val from = ids.indexOf(id)
        val to = from + by
        if (from < 0 || to < 0 || to >= ids.size) return
        ids.removeAt(from)
        ids.add(to, id)
        prefs.widgetIds = ids
        val view = list.getChildAt(from)
        list.removeViewAt(from)
        list.addView(view, to)
    }

    private fun remove(id: Int) {
        val index = prefs.widgetIds.indexOf(id)
        host.deleteAppWidgetId(id)
        prefs.widgetIds = prefs.widgetIds - id
        prefs.setWidgetHeightDp(id, 0)
        if (index >= 0 && index < list.childCount) list.removeViewAt(index)
        updateEmptyState()
    }

    // ---- Adding a widget -------------------------------------------------------------------

    /** Text-only picker: first the app, then (if it has several) the widget. */
    private fun pickProvider() {
        val app = activity.applicationContext
        Thread({
            // Loading labels touches every widget app's resources: keep it off the UI thread.
            val pm = app.packageManager
            val me = Process.myUserHandle()
            val groups = HashMap<String, ArrayList<Pair<String, AppWidgetProviderInfo>>>()
            val launcherApps = app.getSystemService(LauncherApps::class.java)
            val appLabels = HashMap<String, String>()
            for (user in launcherApps.profiles) {
                for (info in awm.getInstalledProvidersForProfile(user)) {
                    val pkg = info.provider.packageName
                    var appLabel = appLabels.getOrPut("$pkg|$user") {
                        try {
                            pm.getApplicationLabel(launcherApps.getApplicationInfo(pkg, 0, user)).toString()
                        } catch (_: Exception) {
                            pkg
                        }
                    }
                    if (user != me) appLabel = app.getString(R.string.work_suffix, appLabel)
                    val label = info.loadLabel(pm) + sizeSuffix(info)
                    groups.getOrPut(appLabel) { ArrayList() }.add(label to info)
                }
            }
            val apps = groups.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
            activity.runOnUiThread {
                if (!activity.isFinishing) showAppPicker(apps, groups)
            }
        }, "widget-list").start()
    }

    private fun sizeSuffix(info: AppWidgetProviderInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            "  ·  ${info.targetCellWidth}×${info.targetCellHeight}"
        } else {
            ""
        }

    private fun showAppPicker(apps: List<String>, groups: Map<String, List<Pair<String, AppWidgetProviderInfo>>>) {
        if (apps.isEmpty()) {
            Toast.makeText(activity, R.string.widgets_none, Toast.LENGTH_SHORT).show()
            return
        }
        showDialog(
            AlertDialog.Builder(activity, Ui.DIALOG_THEME)
                .setTitle(R.string.widgets_add)
                .setItems(apps.toTypedArray()) { _, which ->
                    val app = apps[which]
                    val widgets = groups[app].orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
                    if (widgets.size == 1) {
                        onProviderChosen(widgets[0].second)
                    } else {
                        showDialog(
                            AlertDialog.Builder(activity, Ui.DIALOG_THEME)
                                .setTitle(activity.getString(R.string.widget_choose_app) + " " + app)
                                .setItems(widgets.map { it.first }.toTypedArray()) { _, w ->
                                    onProviderChosen(widgets[w].second)
                                }
                        )
                    }
                }
        )
    }

    private fun onProviderChosen(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        val allowed = try {
            awm.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (_: Exception) {
            false
        }
        if (allowed) {
            configureOrAdd(id)
            return
        }
        // First widget from this app: Android asks the user to allow it.
        pendingId = id
        val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(bind, REQUEST_BIND)
        } catch (_: Exception) {
            fail(id)
        }
    }

    private fun configureOrAdd(id: Int) {
        val info = awm.getAppWidgetInfo(id) ?: return fail(id)
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure == null || optional) {
            add(id)
            return
        }
        pendingId = id
        try {
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
        } catch (_: Exception) {
            fail(id)
        }
    }

    /** @return true if the result belonged to the widget flow. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return false
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId) ?: pendingId
        pendingId = INVALID
        if (id == INVALID) return true
        when {
            resultCode != Activity.RESULT_OK -> host.deleteAppWidgetId(id) // User cancelled.
            requestCode == REQUEST_BIND -> configureOrAdd(id)
            else -> add(id)
        }
        return true
    }

    private fun add(id: Int) {
        pendingId = INVALID
        val info = awm.getAppWidgetInfo(id) ?: return fail(id)
        prefs.widgetIds = prefs.widgetIds + id
        if (!built) return // Page closed meanwhile: it is built in full on next open.
        list.addView(createView(id, info))
        updateEmptyState()
    }

    private fun fail(id: Int) {
        pendingId = INVALID
        host.deleteAppWidgetId(id)
        prefs.widgetIds = prefs.widgetIds - id
        Toast.makeText(activity, R.string.widget_failed, Toast.LENGTH_SHORT).show()
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        dismissDialog()
        dialog = builder.show()
    }

    companion object {
        private const val HOST_ID = 1024
        private const val INVALID = AppWidgetManager.INVALID_APPWIDGET_ID
        private const val MIN_HEIGHT_DP = 72
        private const val STEP_DP = 48
        const val REQUEST_BIND = 2
        const val REQUEST_CONFIGURE = 3
    }
}
