package com.minimal.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private val rows = ArrayList<Row>()

    private class Row(val value: TextView, val valueText: (() -> String)?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Ui.layoutBehindSystemBars(window)
        prefs = Prefs.get(this)
        list = findViewById(R.id.settings_list)

        row(R.string.s_fav_count, { prefs.favCount.toString() }) {
            prefs.favCount = (prefs.favCount + 1) % (Prefs.MAX_FAVORITES + 1)
        }
        row(R.string.s_alignment, { getString(ALIGN[prefs.alignment]) }) {
            prefs.alignment = (prefs.alignment + 1) % 3
        }
        row(R.string.s_text_size, { getString(SIZE[prefs.textSize]) }) {
            prefs.textSize = (prefs.textSize + 1) % 3
        }
        row(R.string.s_text_color, {
            getString(if (prefs.darkText) R.string.color_dark else R.string.color_light)
        }) { prefs.darkText = !prefs.darkText }
        row(R.string.s_auto_launch, { onOff(prefs.autoLaunch) }) {
            prefs.autoLaunch = !prefs.autoLaunch
        }
        row(R.string.s_show_hidden, { onOff(prefs.showHidden) }) {
            prefs.showHidden = !prefs.showHidden
        }
        row(R.string.s_drawer_keyboard, { onOff(prefs.drawerKeyboard) }) {
            prefs.drawerKeyboard = !prefs.drawerKeyboard
        }
        row(R.string.s_home_search, { onOff(prefs.homeSearchBar) }) {
            prefs.homeSearchBar = !prefs.homeSearchBar
        }
        row(R.string.s_notes, { onOff(prefs.notesEnabled) }) {
            prefs.notesEnabled = !prefs.notesEnabled
        }
        row(R.string.s_widgets, { onOff(prefs.widgetsEnabled) }) {
            prefs.widgetsEnabled = !prefs.widgetsEnabled
        }
        row(R.string.s_double_tap, { lockStatus() }) { onDoubleTapRow() }
        row(R.string.s_wallpaper, null) {
            // The chooser lists every wallpaper picker: static (gallery/system) and live.
            val pick = Intent(Intent.ACTION_SET_WALLPAPER)
            safeStart(Intent.createChooser(pick, getString(R.string.s_wallpaper)))
        }
        row(R.string.s_default, {
            getString(if (isDefaultLauncher()) R.string.yes_default else R.string.not_default)
        }) { openHomeSettings() }
        row(R.string.s_refresh, null) {
            AppRepository.get(this).refreshAll()
            Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshValues() // Service state / default-launcher state may have changed elsewhere.
    }

    private fun row(title: Int, valueText: (() -> String)?, onClick: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_setting, list, false)
        v.findViewById<TextView>(R.id.title).setText(title)
        val value = v.findViewById<TextView>(R.id.value)
        if (valueText != null) value.visibility = View.VISIBLE
        v.setOnClickListener {
            onClick()
            refreshValues()
        }
        list.addView(v)
        rows.add(Row(value, valueText))
    }

    private fun refreshValues() {
        for (r in rows) r.valueText?.let { r.value.text = it() }
    }

    private fun onOff(b: Boolean) = getString(if (b) R.string.on else R.string.off)

    private fun lockStatus(): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> getString(R.string.lock_unsupported)
        !prefs.doubleTapLock -> getString(R.string.off)
        LockService.isRunning -> getString(R.string.on)
        else -> getString(R.string.lock_on_service_off)
    }

    private fun onDoubleTapRow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, R.string.lock_needs_p, Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.doubleTapLock && LockService.isRunning) {
            // Turning off: also switch the service off so nothing stays enabled needlessly.
            prefs.doubleTapLock = false
            LockService.disable()
            return
        }
        // Explain first, then send the user to Accessibility settings.
        AlertDialog.Builder(this, Ui.DIALOG_THEME)
            .setTitle(R.string.lock_explain_title)
            .setMessage(R.string.lock_explain_body)
            .setPositiveButton(R.string.lock_explain_go) { _, _ ->
                prefs.doubleTapLock = true
                safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                prefs.doubleTapLock = false
                refreshValues()
            }
            .show()
    }

    private fun isDefaultLauncher(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    private fun openHomeSettings() {
        if (safeStart(Intent(Settings.ACTION_HOME_SETTINGS))) return
        if (safeStart(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) return
        safeStart(Intent(Settings.ACTION_SETTINGS))
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        val ALIGN = intArrayOf(R.string.align_left, R.string.align_center, R.string.align_right)
        val SIZE = intArrayOf(R.string.size_s, R.string.size_m, R.string.size_l)
    }
}
