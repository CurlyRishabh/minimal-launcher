package com.minimal.launcher

/**
 * One launchable activity. Immutable: the repository swaps whole entries on change,
 * so the UI thread can read a published list without locking.
 *
 * [lower] is precomputed so filtering never allocates.
 */
class AppEntry(
    @JvmField val key: String,
    @JvmField val pkg: String,
    @JvmField val cls: String,
    @JvmField val serial: Long,
    /** Label reported by the system (what we cache on disk). */
    @JvmField val label: String,
    /** Label shown to the user (custom rename, or system label). */
    @JvmField val display: String,
    @JvmField val lower: String,
    @JvmField val hidden: Boolean,
) {
    @JvmField val id: Long = key.hashCode().toLong()

    companion object {
        /** Stable identity: user serial + component. Class names never contain '/'. */
        fun key(serial: Long, pkg: String, cls: String): String = "$serial/$pkg/$cls"

        fun serialOf(key: String): Long = key.substring(0, key.indexOf('/')).toLongOrNull() ?: -1

        fun pkgOf(key: String): String {
            val a = key.indexOf('/')
            return key.substring(a + 1, key.indexOf('/', a + 1))
        }

        fun clsOf(key: String): String = key.substring(key.indexOf('/', key.indexOf('/') + 1) + 1)
    }
}
