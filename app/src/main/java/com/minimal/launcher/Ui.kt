package com.minimal.launcher

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController

/** Window helpers shared by both activities. */
object Ui {

    /** Dark Material dialogs read well over any wallpaper. */
    val DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert

    @Suppress("DEPRECATION")
    private const val LEGACY_LAYOUT_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

    /** Draw behind the (transparent) status and navigation bars. */
    fun layoutBehindSystemBars(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = LEGACY_LAYOUT_FLAGS
        }
    }

    /** Dark status/nav icons (for dark text on a light wallpaper) or light icons. */
    fun setDarkSystemIcons(window: Window, dark: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = LEGACY_LAYOUT_FLAGS or (if (dark) light else 0)
        }
    }
}
