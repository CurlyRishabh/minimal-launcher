package com.minimal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger

/** All settings live in SharedPreferences. Safe to use from any thread. */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val renames: SharedPreferences =
        context.getSharedPreferences("renames", Context.MODE_PRIVATE)

    private val versionCounter = AtomicInteger(0)

    /** Bumped on every write so screens can cheaply tell whether to re-apply. */
    val version: Int get() = versionCounter.get()

    var favCount: Int
        get() = sp.getInt("fav_count", 5)
        set(v) = edit { putInt("fav_count", v.coerceIn(0, MAX_FAVORITES)) }

    /** 0 = left, 1 = center, 2 = right. */
    var alignment: Int
        get() = sp.getInt("align", 0)
        set(v) = edit { putInt("align", v) }

    /** 0 = S, 1 = M, 2 = L. */
    var textSize: Int
        get() = sp.getInt("size", 1)
        set(v) = edit { putInt("size", v) }

    var darkText: Boolean
        get() = sp.getBoolean("dark_text", false)
        set(v) = edit { putBoolean("dark_text", v) }

    var autoLaunch: Boolean
        get() = sp.getBoolean("auto_launch", true)
        set(v) = edit { putBoolean("auto_launch", v) }

    var showHidden: Boolean
        get() = sp.getBoolean("show_hidden", false)
        set(v) = edit { putBoolean("show_hidden", v) }

    var doubleTapLock: Boolean
        get() = sp.getBoolean("dbl_lock", false)
        set(v) = edit { putBoolean("dbl_lock", v) }

    /** Open the keyboard automatically when swiping up to the drawer. */
    var drawerKeyboard: Boolean
        get() = sp.getBoolean("drawer_keyboard", true)
        set(v) = edit { putBoolean("drawer_keyboard", v) }

    /** Show the small "search" line at the bottom of home. */
    var homeSearchBar: Boolean
        get() = sp.getBoolean("home_search", true)
        set(v) = edit { putBoolean("home_search", v) }

    /** Swipe right on home opens the notes page. */
    var notesEnabled: Boolean
        get() = sp.getBoolean("notes", true)
        set(v) = edit { putBoolean("notes", v) }

    /** Swipe left on home opens the widgets page. */
    var widgetsEnabled: Boolean
        get() = sp.getBoolean("widgets", true)
        set(v) = edit { putBoolean("widgets", v) }

    // ---- Widgets (ordered ids + optional per-widget height) ---------------------------------

    var widgetIds: List<Int>
        get() = sp.getString("widget_ids", "")!!.split(',').mapNotNull { it.toIntOrNull() }
        set(v) = edit { putString("widget_ids", v.joinToString(",")) }

    fun widgetHeightDp(id: Int): Int = sp.getInt("wh_$id", 0)

    fun setWidgetHeightDp(id: Int, dp: Int) = edit {
        if (dp <= 0) remove("wh_$id") else putInt("wh_$id", dp)
    }

    // ---- Favorites -------------------------------------------------------------------------

    fun favoriteKey(slot: Int): String? = sp.getString(FAV_KEY[slot], null)

    /** Label snapshot so home can draw before the app list is loaded. */
    fun favoriteLabel(slot: Int): String? = sp.getString(FAV_LABEL[slot], null)

    fun setFavorite(slot: Int, key: String, label: String) = edit {
        putString(FAV_KEY[slot], key)
        putString(FAV_LABEL[slot], label)
    }

    fun clearFavorite(slot: Int) = edit {
        remove(FAV_KEY[slot])
        remove(FAV_LABEL[slot])
    }

    fun firstEmptySlot(count: Int): Int {
        for (i in 0 until count) if (favoriteKey(i) == null) return i
        return -1
    }

    /** Called when an app is uninstalled. */
    fun removeFavoritesFor(pkg: String, serial: Long) {
        val prefix = "$serial/$pkg/"
        for (i in 0 until MAX_FAVORITES) {
            if (favoriteKey(i)?.startsWith(prefix) == true) clearFavorite(i)
        }
    }

    // ---- Renames / hidden ------------------------------------------------------------------

    fun rename(key: String): String? = renames.getString(key, null)

    fun setRename(key: String, name: String?) {
        val e = renames.edit()
        if (name.isNullOrBlank()) e.remove(key) else e.putString(key, name)
        e.apply()
        versionCounter.incrementAndGet()
    }

    fun isHidden(key: String): Boolean =
        sp.getStringSet("hidden", null)?.contains(key) == true

    fun setHidden(key: String, hidden: Boolean) {
        // The returned set must not be mutated: copy it.
        val set = HashSet(sp.getStringSet("hidden", emptySet()) ?: emptySet())
        if (hidden) set.add(key) else set.remove(key)
        edit { putStringSet("hidden", set) }
    }

    // ---- App-cache bookkeeping -------------------------------------------------------------

    val bootCount: Int get() = sp.getInt("boot_count", -2)
    val packageSequence: Int get() = sp.getInt("pkg_seq", -1)

    fun setPackageState(bootCount: Int, sequence: Int) {
        // Not a user-visible setting: don't bump version.
        sp.edit().putInt("boot_count", bootCount).putInt("pkg_seq", sequence).apply()
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val e = sp.edit()
        e.block()
        e.apply()
        versionCounter.incrementAndGet()
    }

    companion object {
        const val MAX_FAVORITES = 7
        private val FAV_KEY = Array(MAX_FAVORITES) { "fav_key_$it" }
        private val FAV_LABEL = Array(MAX_FAVORITES) { "fav_label_$it" }

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
