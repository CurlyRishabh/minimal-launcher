package com.minimal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.AtomicFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * Source of truth for the app list.
 *
 * - Labels are expensive to load, so the list is cached in a small text file.
 * - The cache is refreshed only by LauncherApps.Callback (while the process is alive) and, on
 *   process start, by PackageManager.getChangedPackages() — which lists just the packages that
 *   changed since we last looked. A full rescan happens only after a reboot (sequence numbers
 *   reset) or when the user taps "Refresh app list". Nothing is rescanned on resume.
 * - All mutation happens on one low-priority background thread; the UI gets immutable snapshots.
 */
class AppRepository private constructor(private val context: Context) {

    fun interface Listener {
        fun onAppsChanged()
    }

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val prefs = Prefs.get(context)
    private val cache = AtomicFile(context.getFileStreamPath(CACHE_FILE))
    private val myPackage = context.packageName
    private val mySerial = userManager.getSerialNumberForUser(Process.myUserHandle())
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "app-list")
    }

    // ---- Background thread only ------------------------------------------------------------
    private var entries: ArrayList<AppEntry>? = null
    private var callbackRegistered = false
    private var changesChecked = false

    // ---- Main thread only ------------------------------------------------------------------
    /** Latest published snapshot, sorted, including hidden apps. Null until loaded / after trim. */
    var apps: List<AppEntry>? = null
        private set
    var listener: Listener? = null
    private var loadQueued = false

    /** Main thread. Cheap if already loaded. */
    fun load() {
        if (apps != null || loadQueued) return
        loadQueued = true
        executor.execute {
            ensureLoaded()
            publish()
        }
    }

    /** Main thread. Drop in-memory data; it is rebuilt lazily from the file cache. */
    fun trim() {
        apps = null
        executor.execute { entries = null }
    }

    /** Main thread. Full rescan (Settings → Refresh app list). */
    fun refreshAll() {
        executor.execute {
            ensureLoaded()
            entries = scanAll()
            publish()
        }
    }

    /** Main thread. Pass null/blank to restore the system label. */
    fun rename(key: String, name: String?) {
        prefs.setRename(key, name?.trim())
        rebuildLater(key)
    }

    fun setHidden(key: String, hidden: Boolean) {
        prefs.setHidden(key, hidden)
        rebuildLater(key)
    }

    fun userForSerial(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    // ---- Internals (background thread) -----------------------------------------------------

    private fun rebuildLater(key: String) = executor.execute {
        ensureLoaded()
        val list = entries ?: return@execute
        for (i in list.indices) {
            val e = list[i]
            if (e.key == key) {
                list[i] = entry(e.serial, e.pkg, e.cls, e.label)
                break
            }
        }
        list.sortWith(ORDER)
        publish()
    }

    private fun ensureLoaded() {
        if (!callbackRegistered) {
            launcherApps.registerCallback(callback, main)
            callbackRegistered = true
        }
        if (entries == null) entries = readCache() ?: scanAll()
        if (!changesChecked) {
            changesChecked = true
            applySystemChanges()
        }
    }

    /** Once per process: catch up with installs/updates that happened while we were dead. */
    private fun applySystemChanges() {
        val boot = currentBootCount()
        val seq = prefs.packageSequence
        if (boot == -1 || boot != prefs.bootCount || seq < 0) {
            // Rebooted (sequence numbers reset) or first run: one full scan.
            entries = scanAll()
            return
        }
        val changed = context.packageManager.getChangedPackages(seq) ?: return
        val list = entries ?: return
        val profiles = launcherApps.profiles
        for (pkg in changed.packageNames) {
            for (user in profiles) refreshPackage(list, pkg, user)
        }
        list.sortWith(ORDER)
        writeCache(list)
        prefs.setPackageState(boot, changed.sequenceNumber)
    }

    private fun scanAll(): ArrayList<AppEntry> {
        val old = entries
        val out = ArrayList<AppEntry>(256)
        for (user in launcherApps.profiles) {
            val serial = userManager.getSerialNumberForUser(user)
            if (userManager.isQuietModeEnabled(user)) {
                // Paused work profile reports no activities; keep what we knew.
                old?.filterTo(out) { it.serial == serial }
                continue
            }
            for (info in launcherApps.getActivityList(null, user)) {
                val cn = info.componentName
                if (cn.packageName == myPackage) continue
                out.add(entry(serial, cn.packageName, cn.className, info.label.toString()))
            }
        }
        out.sortWith(ORDER)
        writeCache(out)
        prefs.setPackageState(currentBootCount(), currentSequence())
        return out
    }

    private fun refreshPackage(list: ArrayList<AppEntry>, pkg: String, user: UserHandle) {
        if (pkg == myPackage) return
        if (userManager.isQuietModeEnabled(user)) return
        val serial = userManager.getSerialNumberForUser(user)
        val activities = try {
            launcherApps.getActivityList(pkg, user)
        } catch (_: SecurityException) {
            return
        }
        list.removeAll { it.pkg == pkg && it.serial == serial }
        for (info in activities) {
            list.add(entry(serial, pkg, info.componentName.className, info.label.toString()))
        }
        if (activities.isEmpty()) prefs.removeFavoritesFor(pkg, serial)
    }

    private fun onPackages(pkgs: Array<out String>, user: UserHandle) = executor.execute {
        ensureLoaded()
        val list = entries ?: return@execute
        for (p in pkgs) refreshPackage(list, p, user)
        list.sortWith(ORDER)
        writeCache(list)
        prefs.setPackageState(currentBootCount(), currentSequence())
        publish()
    }

    private fun entry(serial: Long, pkg: String, cls: String, label: String): AppEntry {
        val key = AppEntry.key(serial, pkg, cls)
        val display = prefs.rename(key)
            ?: if (serial != mySerial) context.getString(R.string.work_suffix, label) else label
        return AppEntry(key, pkg, cls, serial, label, display, display.lowercase(), prefs.isHidden(key))
    }

    private fun publish() {
        val snapshot = ArrayList(entries ?: return)
        main.post {
            apps = snapshot
            loadQueued = false
            listener?.onAppsChanged()
        }
    }

    private fun currentBootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun currentSequence(): Int =
        context.packageManager.getChangedPackages(0)?.sequenceNumber ?: 0

    // ---- File cache: "v1" header, then serial \t package \t class \t label per line ---------

    private fun readCache(): ArrayList<AppEntry>? {
        return try {
            cache.openRead().use { stream ->
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                if (reader.readLine() != CACHE_VERSION) return null
                val out = ArrayList<AppEntry>(256)
                val validSerials = HashMap<Long, Boolean>(4)
                while (true) {
                    val line = reader.readLine() ?: break
                    val p = line.split('\t', limit = 4)
                    if (p.size != 4) continue
                    val serial = p[0].toLongOrNull() ?: continue
                    val valid = validSerials.getOrPut(serial) { userForSerial(serial) != null }
                    if (valid) out.add(entry(serial, p[1], p[2], p[3]))
                }
                out.sortWith(ORDER)
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(list: List<AppEntry>) {
        val sb = StringBuilder(list.size * 64)
        sb.append(CACHE_VERSION).append('\n')
        for (e in list) {
            sb.append(e.serial).append('\t').append(e.pkg).append('\t').append(e.cls).append('\t')
                .append(e.label.replace('\t', ' ').replace('\n', ' ')).append('\n')
        }
        val out = try {
            cache.startWrite()
        } catch (_: Exception) {
            return
        }
        try {
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            cache.finishWrite(out)
        } catch (_: Exception) {
            cache.failWrite(out)
        }
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) =
            onPackages(arrayOf(packageName), user)

        override fun onPackageRemoved(packageName: String, user: UserHandle) =
            onPackages(arrayOf(packageName), user)

        override fun onPackageChanged(packageName: String, user: UserHandle) =
            onPackages(arrayOf(packageName), user)

        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            onPackages(packageNames, user)

        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            onPackages(packageNames, user)
    }

    companion object {
        private const val CACHE_FILE = "apps.cache"
        private const val CACHE_VERSION = "v1"

        private val ORDER = Comparator<AppEntry> { a, b ->
            val c = String.CASE_INSENSITIVE_ORDER.compare(a.display, b.display)
            if (c != 0) c else a.key.compareTo(b.key)
        }

        @SuppressLint("StaticFieldLeak") // Holds the application context only.
        @Volatile private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
