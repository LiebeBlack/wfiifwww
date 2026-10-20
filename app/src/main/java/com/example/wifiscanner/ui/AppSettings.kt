package com.example.wifiscanner.ui

import android.content.Context
import androidx.core.content.edit
import com.example.wifiscanner.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the shell: appearance, how the list is grouped and the
 * default sort order.
 *
 * Kept in SharedPreferences (no extra dependency, no I/O on the main thread
 * beyond the initial read) and exposed as a [StateFlow] so the whole tree
 * recomposes when a setting changes — switching the theme is instant and
 * survives process death.
 */
data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val dynamicColor: Boolean = false,
    val sort: DeviceSort = DeviceSort.Ip,
    val groupByKind: Boolean = true,
)

/** Reads and persists [UiSettings]. */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<UiSettings> = _state.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _state.value = _state.value.copy(themeMode = mode)
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    fun setDynamicColor(enabled: Boolean) {
        _state.value = _state.value.copy(dynamicColor = enabled)
        prefs.edit { putBoolean(KEY_DYNAMIC, enabled) }
    }

    fun setSort(sort: DeviceSort) {
        _state.value = _state.value.copy(sort = sort)
        prefs.edit { putString(KEY_SORT, sort.name) }
    }

    fun setGroupByKind(grouped: Boolean) {
        _state.value = _state.value.copy(groupByKind = grouped)
        prefs.edit { putBoolean(KEY_GROUP, grouped) }
    }

    /** Rebuilds the snapshot from disk; unknown or stale values fall back. */
    private fun load(): UiSettings {
        val defaults = UiSettings()
        val theme = prefs.getString(KEY_THEME, null)
            ?.let { raw -> ThemeMode.entries.firstOrNull { it.name == raw } }
            ?: defaults.themeMode
        val sort = prefs.getString(KEY_SORT, null)
            ?.let { raw -> DeviceSort.entries.firstOrNull { it.name == raw } }
            ?: defaults.sort
        return UiSettings(
            themeMode = theme,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, defaults.dynamicColor),
            sort = sort,
            groupByKind = prefs.getBoolean(KEY_GROUP, defaults.groupByKind),
        )
    }

    private companion object {
        const val FILE = "wifi_scanner_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_SORT = "sort_order"
        const val KEY_GROUP = "group_by_kind"
    }
}
