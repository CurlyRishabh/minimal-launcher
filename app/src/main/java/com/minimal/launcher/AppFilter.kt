package com.minimal.launcher

/**
 * Allocation-free search. The query is read straight from the EditText's Editable;
 * output lists are reused across keystrokes.
 *
 * Ranking: matches at the start of a word come first, then matches anywhere.
 * Within each group the source order (alphabetical) is kept.
 */
object AppFilter {

    private const val NONE = 0
    private const val CONTAINS = 1
    private const val WORD_START = 2

    /**
     * @param query  raw text; characters before [qStart] are ignored (leading spaces).
     * @param out    receives results (cleared first).
     * @param spare  scratch list for the "contains" group (cleared).
     */
    fun filter(
        source: List<AppEntry>,
        query: CharSequence,
        qStart: Int,
        showHidden: Boolean,
        out: ArrayList<AppEntry>,
        spare: ArrayList<AppEntry>,
    ) {
        out.clear()
        spare.clear()
        val qLen = query.length - qStart
        val n = source.size
        for (i in 0 until n) {
            val e = source[i]
            if (e.hidden && !showHidden) continue
            if (qLen <= 0) {
                out.add(e)
                continue
            }
            when (match(e.lower, query, qStart, qLen)) {
                WORD_START -> out.add(e)
                CONTAINS -> spare.add(e)
            }
        }
        // ArrayList.addAll() would allocate via toArray(); copy by hand.
        for (i in 0 until spare.size) out.add(spare[i])
        spare.clear()
    }

    private fun match(s: String, q: CharSequence, qStart: Int, qLen: Int): Int {
        var found = NONE
        val last = s.length - qLen
        var i = 0
        while (i <= last) {
            if (regionMatches(s, i, q, qStart, qLen)) {
                if (i == 0 || !Character.isLetterOrDigit(s[i - 1])) return WORD_START
                found = CONTAINS
            }
            i++
        }
        return found
    }

    private fun regionMatches(s: String, offset: Int, q: CharSequence, qStart: Int, len: Int): Boolean {
        for (j in 0 until len) {
            if (s[offset + j] != Character.toLowerCase(q[qStart + j])) return false
        }
        return true
    }
}
