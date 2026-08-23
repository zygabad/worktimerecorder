package com.zygabad.worktimerecorder.testutil

import android.content.SharedPreferences

/**
 * Minimal, fully-working SharedPreferences implementation backed by a plain map, for tests that
 * need a real PrefsManager instance (not a mock of it) so its actual getter/setter logic runs.
 * SharedPreferences/Editor are interfaces with no framework stub bodies, so implementing them
 * directly here needs no Robolectric/instrumentation.
 */
class FakeSharedPreferences : SharedPreferences {
    val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map

    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
    }
}

private class FakeEditor(private val target: FakeSharedPreferences) : SharedPreferences.Editor {
    private val pending = mutableMapOf<String, Any?>()
    private val removals = mutableSetOf<String>()
    private var clearAll = false

    override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }

    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
        apply { pending[key!!] = values }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }

    override fun remove(key: String?): SharedPreferences.Editor = apply { removals.add(key!!) }

    override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

    override fun commit(): Boolean {
        applyChanges()
        return true
    }

    override fun apply() {
        applyChanges()
    }

    private fun applyChanges() {
        if (clearAll) target.map.clear()
        removals.forEach { target.map.remove(it) }
        target.map.putAll(pending)
    }
}
