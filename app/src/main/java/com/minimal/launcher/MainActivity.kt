package com.minimal.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.AlarmClock
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewStub
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), AppAdapter.Listener, AppRepository.Listener {

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var launcherApps: LauncherApps
    private lateinit var imm: InputMethodManager

    // Home
    private lateinit var scrim: View
    private lateinit var content: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var favContainer: LinearLayout
    private val favViews = arrayOfNulls<TextView>(Prefs.MAX_FAVORITES)
    private lateinit var homeSearch: LinearLayout
    private lateinit var homeSearchText: TextView
    private lateinit var homeSearchLine: View

    // Drawer (inflated lazily from a ViewStub)
    private var drawer: View? = null
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private var adapter: AppAdapter? = null
    private val results = ArrayList<AppEntry>()
    private val spare = ArrayList<AppEntry>()

    // Notes page (inflated lazily on first open)
    private var notes: View? = null
    private lateinit var notesText: EditText
    private var notesOpen = false
    private var notesLoaded = false
    private var notesDirty = false
    private var notesSettingText = false

    // Widgets page (inflated lazily on first open)
    private var widgetsPage: WidgetsPage? = null
    private var widgetsOpen = false

    // State
    private var drawerOpen = false
    private var pickSlot = -1
    private var launching = false
    private var lastQueryLength = 0
    private var dialog: Dialog? = null
    private var appliedVersion = -1
    private var appliedLook = -1
    private var autoLaunch = true
    private var showHidden = false
    private var darkText = false
    private var drawerKeyboard = true
    private var notesEnabled = true
    private var widgetsEnabled = true
    private var swipeMin = 0f
    private var slideDistance = 0f
    private var flingConsumed = false

    // Clock
    private val calendar: Calendar = Calendar.getInstance()
    private var timePattern = "HH:mm"
    private lateinit var datePattern: String
    private var clockReceiverRegistered = false

    private lateinit var flingDetector: GestureDetector
    private lateinit var emptySpaceDetector: GestureDetector

    // Reused runnables: no allocation when opening/closing the drawer.
    @SuppressLint("NotifyDataSetChanged")
    private val hideDrawerViews = Runnable {
        drawer?.visibility = View.GONE
        if (!anyPanelOpen()) scrim.visibility = View.GONE
        results.clear()
        adapter?.showFooter = false
        adapter?.notifyDataSetChanged()
    }
    private val hideNotesViews = Runnable {
        notes?.visibility = View.GONE
        if (!anyPanelOpen()) scrim.visibility = View.GONE
    }
    private val hideWidgetsViews = Runnable {
        widgetsPage?.let {
            it.root.visibility = View.GONE
            it.release() // Widgets hold no views (and no memory) while the page is closed.
        }
        if (!anyPanelOpen()) scrim.visibility = View.GONE
    }

    private fun anyPanelOpen() = drawerOpen || notesOpen || widgetsOpen
    private val showKeyboard = Runnable {
        if (drawerOpen) imm.showSoftInput(search, 0)
    }

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                calendar.timeZone = java.util.TimeZone.getDefault()
            }
            updateClock()
        }
    }

    // ---- Lifecycle -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Ui.layoutBehindSystemBars(window)

        prefs = Prefs.get(this)
        repo = AppRepository.get(this)
        launcherApps = getSystemService(LauncherApps::class.java)
        imm = getSystemService(InputMethodManager::class.java)

        scrim = findViewById(R.id.scrim)
        content = findViewById(R.id.content)
        home = findViewById(R.id.home)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        favContainer = findViewById(R.id.favorites)
        homeSearch = findViewById(R.id.home_search)
        homeSearchText = findViewById(R.id.home_search_text)
        homeSearchLine = findViewById(R.id.home_search_line)

        swipeMin = resources.getDimension(R.dimen.swipe_min_distance)
        slideDistance = resources.getDimension(R.dimen.drawer_slide)
        datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")

        buildFavoriteViews()
        setupGestures()
        setupBack()
        clock.setOnClickListener { openAlarms() }
        // Tapping the search line is an explicit "I want to type": always open the keyboard.
        homeSearch.setOnClickListener { openDrawer(pickSlot = -1, forceKeyboard = true) }

        // 1) Draw clock + favorites from SharedPreferences only (no app list needed).
        applyLook()
        renderFavorites()

        // 2) App list loads from the file cache on a background thread.
        repo.listener = this
        repo.load()

        // 3) Pre-inflate the drawer once the UI thread is idle, so the first swipe is instant
        //    but cold start never pays for RecyclerView inflation.
        Looper.myQueue().addIdleHandler {
            ensureDrawer()
            false
        }
    }

    override fun onStart() {
        super.onStart()
        timePattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        registerClock()
        updateClock()
        if (widgetsOpen) widgetsPage?.show()
        if (prefs.version != appliedVersion) {
            applyLook()
            renderFavorites()
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterClock()
        dismissDialog()
        saveNotesIfDirty()
        widgetsPage?.hide() // Stop widget updates while we're not visible.
        widgetsPage?.dismissDialog()
        // Coming back always shows home (notes stay open until Home/back/swipe).
        closeDrawer(animate = false)
    }

    override fun onDestroy() {
        if (repo.listener === this) repo.listener = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Home button pressed while we're in front: back to the home view, keyboard closed.
        dismissDialog()
        closeDrawer(animate = hasWindowFocus())
        closeNotes(animate = hasWindowFocus())
        widgetsPage?.dismissDialog()
        closeWidgets(animate = hasWindowFocus())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !drawerOpen) {
            // Drop search results and drawer adapter data; rebuilt lazily on next open.
            results.clear()
            results.trimToSize()
            spare.trimToSize()
            dropAdapter()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !notesOpen && notesLoaded && !notesDirty) {
            // Notes are safely on disk: drop the text, reload on next open.
            setNotesText("")
            notesLoaded = false
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // Also drop the in-memory app list; it reloads from the small file cache.
            repo.trim()
        }
    }

    // ---- Back ------------------------------------------------------------------------------

    private fun setupBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Always consume back: on home it does nothing; in the drawer it closes the drawer.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }
    }

    // Only reached below API 33; on 33+ the OnBackInvokedCallback above handles back.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Used below API 33 only; newer versions use OnBackInvokedCallback")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBack() // Deliberately no super: back on home does nothing.
    }

    private fun handleBack() {
        if (drawerOpen) closeDrawer(animate = true)
        else if (notesOpen) closeNotes(animate = true)
        else if (widgetsOpen) closeWidgets(animate = true)
    }

    // ---- Clock -----------------------------------------------------------------------------

    private fun registerClock() {
        if (clockReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(clockReceiver, filter)
        }
        clockReceiverRegistered = true
    }

    private fun unregisterClock() {
        if (!clockReceiverRegistered) return
        unregisterReceiver(clockReceiver)
        clockReceiverRegistered = false
    }

    private fun updateClock() {
        calendar.timeInMillis = System.currentTimeMillis()
        clock.text = DateFormat.format(timePattern, calendar)
        date.text = DateFormat.format(datePattern, calendar)
    }

    private fun openAlarms() {
        try {
            // NEW_TASK: otherwise the app joins the launcher's (hidden, cleared-on-Home) task.
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    // ---- Look ------------------------------------------------------------------------------

    private fun applyLook() {
        appliedVersion = prefs.version
        autoLaunch = prefs.autoLaunch
        darkText = prefs.darkText
        drawerKeyboard = prefs.drawerKeyboard
        notesEnabled = prefs.notesEnabled
        if (!notesEnabled) closeNotes(animate = false)
        widgetsEnabled = prefs.widgetsEnabled
        if (!widgetsEnabled) closeWidgets(animate = false)
        val newShowHidden = prefs.showHidden
        val hiddenChanged = newShowHidden != showHidden
        showHidden = newShowHidden

        val align = prefs.alignment.coerceIn(0, 2)
        val size = prefs.textSize.coerceIn(0, 2)
        val gravity = GRAVITY[align]

        home.gravity = gravity
        favContainer.gravity = gravity
        homeSearch.gravity = gravity
        homeSearch.visibility = if (prefs.homeSearchBar) View.VISIBLE else View.GONE

        val color = getColor(if (darkText) R.color.text_dark else R.color.text_light)
        val shadow = getColor(if (darkText) R.color.shadow_on_dark_text else R.color.shadow_on_light_text)
        val radius = resources.getDimension(R.dimen.shadow_radius)
        val dy = resources.getDimension(R.dimen.shadow_dy)

        clock.textSize = CLOCK_SP[size]
        date.textSize = DATE_SP[size]
        styleHomeText(clock, color, shadow, radius, dy)
        styleHomeText(date, color, shadow, radius, dy)
        styleHomeText(homeSearchText, color, shadow, radius, dy)
        homeSearchLine.setBackgroundColor(color)
        for (tv in favViews) {
            if (tv == null) continue
            tv.textSize = FAV_SP[size]
            tv.gravity = gravity
            styleHomeText(tv, color, shadow, radius, dy)
        }
        if (!anyPanelOpen()) Ui.setDarkSystemIcons(window, darkText)

        // Drawer rows follow alignment/size. Only rebuild the adapter if those changed.
        val look = align * 10 + size
        if (look != appliedLook) {
            appliedLook = look
            drawer?.let { search.gravity = gravity or Gravity.CENTER_VERTICAL }
            dropAdapter()
        }
        if (hiddenChanged && drawerOpen) runFilter(fromTyping = false)
    }

    private fun styleHomeText(tv: TextView, color: Int, shadow: Int, radius: Float, dy: Float) {
        tv.setTextColor(color)
        tv.setShadowLayer(radius, 0f, dy, shadow)
    }

    // ---- Favorites -------------------------------------------------------------------------

    private fun buildFavoriteViews() {
        val inflater = layoutInflater
        for (i in 0 until Prefs.MAX_FAVORITES) {
            val tv = inflater.inflate(R.layout.item_favorite, favContainer, false) as TextView
            tv.setOnClickListener { onFavoriteClick(i) }
            tv.setOnLongClickListener {
                onFavoriteLongClick(i)
                true
            }
            favContainer.addView(tv)
            favViews[i] = tv
        }
    }

    private fun renderFavorites() {
        val count = prefs.favCount
        for (i in 0 until Prefs.MAX_FAVORITES) {
            val tv = favViews[i] ?: continue
            if (i >= count) {
                tv.visibility = View.GONE
                continue
            }
            tv.visibility = View.VISIBLE
            val key = prefs.favoriteKey(i)
            if (key == null) {
                tv.setText(R.string.empty_slot)
                tv.alpha = 0.4f
            } else {
                tv.text = prefs.rename(key) ?: prefs.favoriteLabel(i) ?: AppEntry.pkgOf(key)
                tv.alpha = 1f
            }
        }
    }

    private fun onFavoriteClick(slot: Int) {
        val key = prefs.favoriteKey(slot)
        if (key == null) openDrawer(pickSlot = slot) else launch(key, favViews[slot])
    }

    private fun onFavoriteLongClick(slot: Int) {
        favViews[slot]?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (prefs.favoriteKey(slot) == null) {
            openDrawer(pickSlot = slot)
            return
        }
        val items = arrayOf(getString(R.string.reassign), getString(R.string.remove))
        showDialog(
            AlertDialog.Builder(this, Ui.DIALOG_THEME)
                .setTitle(favViews[slot]?.text)
                .setItems(items) { _, which ->
                    if (which == 0) {
                        openDrawer(pickSlot = slot)
                    } else {
                        prefs.clearFavorite(slot)
                        renderFavorites()
                    }
                }
        )
    }

    private fun addToHome(e: AppEntry) {
        var count = prefs.favCount
        var slot = prefs.firstEmptySlot(count)
        if (slot < 0 && count < Prefs.MAX_FAVORITES) {
            slot = count
            count++
            prefs.favCount = count
        }
        if (slot >= 0) {
            prefs.setFavorite(slot, e.key, e.display)
            renderFavorites()
            Toast.makeText(this, R.string.added_to_home, Toast.LENGTH_SHORT).show()
            return
        }
        // Home is full: ask which one to replace.
        val names = Array(count) { i -> favViews[i]?.text ?: "" }
        showDialog(
            AlertDialog.Builder(this, Ui.DIALOG_THEME)
                .setTitle(R.string.replace_which)
                .setItems(names) { _, which ->
                    prefs.setFavorite(which, e.key, e.display)
                    renderFavorites()
                }
        )
    }

    // ---- Gestures --------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // Swipes are detected for the whole window (even when starting on an app name).
        flingDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dx) > abs(dy) * 1.5f) {
                    // Horizontal: right opens notes, left closes them. Needs a longer swipe than
                    // vertical so text selection drags inside notes don't trigger it.
                    if (abs(dx) < swipeMin * 1.5f) return false
                    // Pages sit left (notes) and right (widgets) of home; swiping moves between them.
                    val handled = when {
                        dx > 0 && widgetsOpen -> { closeWidgets(animate = true); true }
                        dx < 0 && notesOpen && !notesText.hasSelection() -> { closeNotes(animate = true); true }
                        anyPanelOpen() -> false
                        dx > 0 && notesEnabled -> { openNotes(); true }
                        dx < 0 && widgetsEnabled -> { openWidgets(); true }
                        else -> false
                    }
                    if (handled) flingConsumed = true
                    return handled
                }
                if (abs(dy) < swipeMin || abs(dy) < abs(dx) * 1.5f) return false
                if (notesOpen || widgetsOpen) return false // Vertical swipes scroll the page.
                if (!drawerOpen) {
                    if (dy < 0) openDrawer(pickSlot = -1) else expandNotifications()
                    flingConsumed = true
                    return true
                }
                if (dy > 0 && !list.canScrollVertically(-1)) {
                    closeDrawer(animate = true)
                    flingConsumed = true
                    return true
                }
                return false
            }
        }).apply { setIsLongpressEnabled(false) }

        // Double-tap / long-press only on *empty* home space: app names consume their own touches.
        emptySpaceDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (prefs.doubleTapLock) lockScreen()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (anyPanelOpen() || flingConsumed) return
                home.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        })
        home.setOnTouchListener { _, ev -> emptySpaceDetector.onTouchEvent(ev) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) flingConsumed = false
        flingDetector.onTouchEvent(ev)
        if (flingConsumed && ev.actionMasked == MotionEvent.ACTION_UP) {
            // A swipe that started on an app name must not also count as a tap on it.
            ev.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * There is no public API to expand the notification shade. Try, in order:
     * 1. the accessibility service (if the user enabled it for double-tap lock),
     * 2. StatusBarManager.expandNotificationsPanel() via reflection (needs EXPAND_STATUS_BAR;
     *    works on most devices but is a hidden API and may be blocked by some vendors),
     * 3. a hint toast.
     */
    @SuppressLint("WrongConstant", "PrivateApi")
    private fun expandNotifications() {
        if (LockService.openNotifications()) return
        try {
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(sbm)
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.shade_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun lockScreen() {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
                Toast.makeText(this, R.string.lock_needs_p, Toast.LENGTH_SHORT).show()
            !LockService.lockScreen() ->
                Toast.makeText(this, R.string.lock_service_off, Toast.LENGTH_LONG).show()
        }
    }

    // ---- Drawer ----------------------------------------------------------------------------

    private fun ensureDrawer() {
        if (drawer != null) return
        val d = findViewById<ViewStub>(R.id.drawer_stub).inflate()
        drawer = d
        search = d.findViewById(R.id.search)
        list = d.findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.setHasFixedSize(true)
        list.itemAnimator = null // No item animations.
        search.gravity = GRAVITY[prefs.alignment.coerceIn(0, 2)] or Gravity.CENTER_VERTICAL

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                if (drawerOpen && !launching) runFilter(fromTyping = true)
            }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                when {
                    results.isNotEmpty() -> onAppClick(results[0])
                    adapter?.showFooter == true -> onSearchFilesClick() // No app matched.
                }
                true
            } else {
                false
            }
        }
    }

    private fun ensureAdapter() {
        if (adapter != null) return
        val size = prefs.textSize.coerceIn(0, 2)
        val a = AppAdapter(layoutInflater, results, this, GRAVITY[prefs.alignment.coerceIn(0, 2)], DRAWER_SP[size])
        adapter = a
        list.adapter = a
    }

    private fun dropAdapter() {
        if (drawer == null || adapter == null) return
        list.adapter = null
        list.recycledViewPool.clear()
        adapter = null
    }

    /** @param forceKeyboard open the keyboard even if "Open keyboard on swipe up" is off. */
    private fun openDrawer(pickSlot: Int, forceKeyboard: Boolean = false) {
        if (anyPanelOpen()) return
        ensureDrawer()
        ensureAdapter()
        val d = drawer ?: return
        dismissDialog()
        search.text.clear() // before drawerOpen=true, so the watcher ignores it
        search.setHint(if (pickSlot >= 0) R.string.pick_hint else R.string.search_hint)
        drawerOpen = true
        this.pickSlot = pickSlot
        launching = false
        lastQueryLength = 0
        runFilter(fromTyping = false)
        list.scrollToPosition(0)

        Ui.setDarkSystemIcons(window, false)
        home.visibility = View.INVISIBLE
        scrim.visibility = View.VISIBLE
        d.visibility = View.VISIBLE

        // Simple slide + fade. ViewPropertyAnimator honours the system "Animator duration
        // scale" automatically (0 = instant).
        scrim.animate().cancel()
        d.animate().cancel()
        scrim.alpha = 0f
        d.alpha = 0f
        d.translationY = slideDistance
        scrim.animate().alpha(1f).setDuration(ANIM_MS).start()
        d.animate().alpha(1f).translationY(0f).setDuration(ANIM_MS).start()

        if (forceKeyboard || drawerKeyboard) {
            search.requestFocus()
            search.post(showKeyboard)
        } // Otherwise the list is shown as-is; tapping the search field brings up the keyboard.
    }

    private fun closeDrawer(animate: Boolean) {
        if (!drawerOpen) return
        drawerOpen = false
        pickSlot = -1
        launching = false
        val d = drawer ?: return

        search.removeCallbacks(showKeyboard)
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.text.clear() // watcher ignores it (drawer closed)
        search.clearFocus()

        home.visibility = View.VISIBLE
        Ui.setDarkSystemIcons(window, darkText)

        scrim.animate().cancel()
        d.animate().cancel()
        if (animate) {
            scrim.animate().alpha(0f).setDuration(ANIM_MS).start()
            d.animate().alpha(0f).translationY(slideDistance).setDuration(ANIM_MS)
                .withEndAction(hideDrawerViews).start()
        } else {
            hideDrawerViews.run()
        }
    }

    // The whole result list changes per keystroke; DiffUtil would allocate on every key.
    @SuppressLint("NotifyDataSetChanged")
    private fun runFilter(fromTyping: Boolean) {
        val apps = repo.apps
        if (apps == null) {
            repo.load() // onAppsChanged() re-runs us when ready.
            return
        }
        val q: Editable = search.text
        var start = 0
        while (start < q.length && q[start] == ' ') start++
        AppFilter.filter(apps, q, start, showHidden, results, spare)
        // "Search files…" row only while actually searching (not when picking for home).
        adapter?.showFooter = start < q.length && pickSlot < 0
        adapter?.notifyDataSetChanged()
        if (fromTyping) list.scrollToPosition(0)

        val len = q.length
        val grew = len > lastQueryLength
        lastQueryLength = len
        // Auto-launch when exactly one match remains, unless the query starts with a space,
        // we're picking an app for a home slot, or the user is deleting characters.
        if (fromTyping && grew && autoLaunch && pickSlot < 0 && start == 0 && results.size == 1) {
            onAppClick(results[0])
        }
    }

    override fun onAppsChanged() {
        if (drawerOpen) runFilter(fromTyping = false)
        if (prefs.version != appliedVersion) renderFavorites() // e.g. an uninstalled favorite
    }

    // ---- File search ---------------------------------------------------------------------

    /**
     * Android 11+ doesn't let a launcher read your documents without "All files access",
     * so file search is handed to the system file picker (it has its own search, needs no
     * permission, and also covers Downloads, Drive, etc.). The picked file is then opened.
     */
    override fun onSearchFilesClick() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_PICK_FILE)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Framework Activity result API; no AndroidX activity dependency on purpose")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (widgetsPage?.onActivityResult(requestCode, resultCode, data) == true) return
        if (requestCode != REQUEST_PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(view)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Notes page ------------------------------------------------------------------------

    private fun ensureNotes() {
        if (notes != null) return
        val n = findViewById<ViewStub>(R.id.notes_stub).inflate()
        notes = n
        notesText = n.findViewById(R.id.notes_text)
        notesText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                if (!notesSettingText) notesDirty = true
            }
        })
    }

    private fun setNotesText(text: String) {
        notesSettingText = true
        notesText.setText(text)
        notesSettingText = false
    }

    private fun openNotes() {
        if (anyPanelOpen()) return
        ensureNotes()
        val n = notes ?: return
        dismissDialog()
        notesOpen = true
        if (!notesLoaded) {
            // Editing stays disabled until the file is read, so nothing typed can be overwritten.
            notesText.isEnabled = false
            NoteStore.load(this) { text ->
                setNotesText(text)
                notesLoaded = true
                notesDirty = false
                notesText.isEnabled = true
            }
        }

        Ui.setDarkSystemIcons(window, false)
        home.visibility = View.INVISIBLE
        scrim.visibility = View.VISIBLE
        n.visibility = View.VISIBLE

        // Same simple fade as the drawer, sliding in from the left.
        scrim.animate().cancel()
        n.animate().cancel()
        scrim.alpha = 0f
        n.alpha = 0f
        n.translationX = -slideDistance
        scrim.animate().alpha(1f).setDuration(ANIM_MS).start()
        n.animate().alpha(1f).translationX(0f).setDuration(ANIM_MS).start()
    }

    private fun closeNotes(animate: Boolean) {
        if (!notesOpen) return
        notesOpen = false
        val n = notes ?: return
        imm.hideSoftInputFromWindow(notesText.windowToken, 0)
        notesText.clearFocus()
        saveNotesIfDirty()

        home.visibility = View.VISIBLE
        Ui.setDarkSystemIcons(window, darkText)

        scrim.animate().cancel()
        n.animate().cancel()
        if (animate) {
            scrim.animate().alpha(0f).setDuration(ANIM_MS).start()
            n.animate().alpha(0f).translationX(-slideDistance).setDuration(ANIM_MS)
                .withEndAction(hideNotesViews).start()
        } else {
            hideNotesViews.run()
        }
    }

    // ---- Widgets page ----------------------------------------------------------------------

    private fun openWidgets() {
        if (anyPanelOpen()) return
        val page = widgetsPage ?: WidgetsPage(this, findViewById<ViewStub>(R.id.widgets_stub).inflate())
            .also { widgetsPage = it }
        val v = page.root
        dismissDialog()
        widgetsOpen = true
        page.show()

        Ui.setDarkSystemIcons(window, false)
        home.visibility = View.INVISIBLE
        scrim.visibility = View.VISIBLE
        v.visibility = View.VISIBLE

        // Same simple fade, sliding in from the right.
        scrim.animate().cancel()
        v.animate().cancel()
        scrim.alpha = 0f
        v.alpha = 0f
        v.translationX = slideDistance
        scrim.animate().alpha(1f).setDuration(ANIM_MS).start()
        v.animate().alpha(1f).translationX(0f).setDuration(ANIM_MS).start()
    }

    private fun closeWidgets(animate: Boolean) {
        if (!widgetsOpen) return
        widgetsOpen = false
        val page = widgetsPage ?: return
        val v = page.root
        page.hide()

        home.visibility = View.VISIBLE
        Ui.setDarkSystemIcons(window, darkText)

        scrim.animate().cancel()
        v.animate().cancel()
        if (animate) {
            scrim.animate().alpha(0f).setDuration(ANIM_MS).start()
            v.animate().alpha(0f).translationX(slideDistance).setDuration(ANIM_MS)
                .withEndAction(hideWidgetsViews).start()
        } else {
            hideWidgetsViews.run()
        }
    }

    private fun saveNotesIfDirty() {
        if (!notesDirty || !notesLoaded) return
        NoteStore.save(this, notesText.text.toString())
        notesDirty = false
    }

    // ---- App actions -----------------------------------------------------------------------

    override fun onAppClick(entry: AppEntry) {
        if (launching) return
        if (pickSlot >= 0) {
            prefs.setFavorite(pickSlot, entry.key, entry.display)
            renderFavorites()
            closeDrawer(animate = true)
            return
        }
        launching = launch(entry.key, null)
    }

    override fun onAppLongClick(entry: AppEntry) {
        if (pickSlot >= 0) return
        val items = arrayOf(
            getString(R.string.add_to_home),
            getString(R.string.rename),
            getString(if (entry.hidden) R.string.unhide else R.string.hide),
            getString(R.string.app_info),
            getString(R.string.uninstall),
        )
        showDialog(
            AlertDialog.Builder(this, Ui.DIALOG_THEME)
                .setTitle(entry.display)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> addToHome(entry)
                        1 -> showRename(entry)
                        2 -> repo.setHidden(entry.key, !entry.hidden)
                        3 -> openAppInfo(entry)
                        4 -> uninstall(entry)
                    }
                }
        )
    }

    private fun showRename(entry: AppEntry) {
        val pad = resources.getDimensionPixelSize(R.dimen.page_padding_h) / 2
        val input = EditText(this).apply {
            setSingleLine()
            setText(entry.display)
            setSelection(text.length)
        }
        val frame = FrameLayout(this).apply {
            setPadding(pad * 2, pad / 2, pad * 2, 0)
            addView(input)
        }
        showDialog(
            AlertDialog.Builder(this, Ui.DIALOG_THEME)
                .setTitle(R.string.rename)
                .setView(frame)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    repo.rename(entry.key, input.text.toString())
                    renderFavorites()
                }
                .setNeutralButton(R.string.reset) { _, _ ->
                    repo.rename(entry.key, null)
                    renderFavorites()
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    /** Launch by key without needing the app list (home favorites work before it loads). */
    private fun launch(key: String, source: View?): Boolean {
        val user = repo.userForSerial(AppEntry.serialOf(key))
        if (user == null) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()
            return false
        }
        val pkg = AppEntry.pkgOf(key)
        val bounds = source?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            android.graphics.Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        return try {
            launcherApps.startMainActivity(ComponentName(pkg, AppEntry.clsOf(key)), user, bounds, null)
            true
        } catch (_: Exception) {
            // Activity renamed by an app update: fall back to the package's current launcher entry.
            try {
                val info = launcherApps.getActivityList(pkg, user).firstOrNull()
                if (info != null) {
                    launcherApps.startMainActivity(info.componentName, user, bounds, null)
                    true
                } else {
                    Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()
                    false
                }
            } catch (_: Exception) {
                Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    private fun openAppInfo(entry: AppEntry) {
        val user = repo.userForSerial(entry.serial) ?: return
        try {
            launcherApps.startAppDetailsActivity(ComponentName(entry.pkg, entry.cls), user, null, null)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun uninstall(entry: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.pkg, null))
        // Lets the system uninstaller target a work-profile app (same extra Launcher3 uses).
        repo.userForSerial(entry.serial)?.let { intent.putExtra(EXTRA_USER, it) }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Dialog helpers --------------------------------------------------------------------

    private fun showDialog(builder: AlertDialog.Builder) {
        dismissDialog()
        dialog = builder.show()
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    private companion object {
        const val ANIM_MS = 150L
        const val EXTRA_USER = "android.intent.extra.USER"
        const val REQUEST_PICK_FILE = 1

        val GRAVITY = intArrayOf(Gravity.START, Gravity.CENTER_HORIZONTAL, Gravity.END)
        val CLOCK_SP = floatArrayOf(52f, 64f, 78f)
        val DATE_SP = floatArrayOf(14f, 16f, 18f)
        val FAV_SP = floatArrayOf(20f, 24f, 30f)
        val DRAWER_SP = floatArrayOf(17f, 19f, 22f)
    }
}
