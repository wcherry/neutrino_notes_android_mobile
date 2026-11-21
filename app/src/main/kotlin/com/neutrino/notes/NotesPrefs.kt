package com.neutrino.notes

import android.content.SharedPreferences
import com.neutrino.notes.core.model.NoteSortOrder
import com.neutrino.notes.core.model.NotesSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppTheme(val title: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The editor's face. Monospaced by default, as on iOS: Markdown's markers line up in a fixed pitch. */
enum class EditorFont(val title: String) { MONOSPACED("Monospaced"), SYSTEM("System"), SERIF("Serif") }

/** The user's preferences, in the account's own `nn.settings` file under `nn.` keys. */
class NotesPrefs(private val prefs: SharedPreferences) {

    private val _theme = MutableStateFlow(AppTheme.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: AppTheme.SYSTEM)
    val theme: StateFlow<AppTheme> = _theme
    fun setTheme(v: AppTheme) { prefs.edit().putString(THEME, v.name).apply(); _theme.value = v }

    private val _fontSize = MutableStateFlow(prefs.getFloat(FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(FONT_SIZE_RANGE))
    val fontSize: StateFlow<Float> = _fontSize
    fun setFontSize(v: Float) { val c = v.coerceIn(FONT_SIZE_RANGE); prefs.edit().putFloat(FONT_SIZE, c).apply(); _fontSize.value = c }

    private val _editorFont = MutableStateFlow(EditorFont.entries.firstOrNull { it.name == prefs.getString(EDITOR_FONT, null) } ?: EditorFont.MONOSPACED)
    val editorFont: StateFlow<EditorFont> = _editorFont
    fun setEditorFont(v: EditorFont) { prefs.edit().putString(EDITOR_FONT, v.name).apply(); _editorFont.value = v }

    private val _autoSync = MutableStateFlow(prefs.getBoolean(AUTO_SYNC, true))
    /** When off, the queue drains only when asked — pull to refresh, Sync Now. */
    val autoSync: StateFlow<Boolean> = _autoSync
    fun setAutoSync(v: Boolean) { prefs.edit().putBoolean(AUTO_SYNC, v).apply(); _autoSync.value = v }

    private val _syncOnCellular = MutableStateFlow(prefs.getBoolean(SYNC_CELLULAR, true))
    val syncOnCellular: StateFlow<Boolean> = _syncOnCellular
    fun setSyncOnCellular(v: Boolean) { prefs.edit().putBoolean(SYNC_CELLULAR, v).apply(); _syncOnCellular.value = v }

    private val _cacheRecents = MutableStateFlow(prefs.getBoolean(CACHE_RECENTS, true))
    val cacheRecents: StateFlow<Boolean> = _cacheRecents
    fun setCacheRecents(v: Boolean) { prefs.edit().putBoolean(CACHE_RECENTS, v).apply(); _cacheRecents.value = v }

    private val _cacheFavorites = MutableStateFlow(prefs.getBoolean(CACHE_FAVORITES, true))
    val cacheFavorites: StateFlow<Boolean> = _cacheFavorites
    fun setCacheFavorites(v: Boolean) { prefs.edit().putBoolean(CACHE_FAVORITES, v).apply(); _cacheFavorites.value = v }

    private val _sortOrder = MutableStateFlow(NoteSortOrder.entries.firstOrNull { it.name == prefs.getString(SORT, null) })
    /** Null until the user picks one, so each section keeps its own default — Recent stays newest first. */
    val sortOrder: StateFlow<NoteSortOrder?> = _sortOrder
    fun setSortOrder(v: NoteSortOrder?) { prefs.edit().putString(SORT, v?.name).apply(); _sortOrder.value = v }
    fun sortOrder(section: NotesSection): NoteSortOrder = _sortOrder.value ?: section.defaultSortOrder

    private val _smartLists = MutableStateFlow(prefs.getBoolean(SMART_LISTS, true))
    val smartLists: StateFlow<Boolean> = _smartLists
    fun setSmartLists(v: Boolean) { prefs.edit().putBoolean(SMART_LISTS, v).apply(); _smartLists.value = v }

    private val _openInPreview = MutableStateFlow(prefs.getBoolean(OPEN_IN_PREVIEW, false))
    /** Open a note rendered rather than as its source; a new note always opens for writing. */
    val openInPreview: StateFlow<Boolean> = _openInPreview
    fun setOpenInPreview(v: Boolean) { prefs.edit().putBoolean(OPEN_IN_PREVIEW, v).apply(); _openInPreview.value = v }

    fun resetToDefaults() {
        setTheme(AppTheme.SYSTEM); setFontSize(DEFAULT_FONT_SIZE); setEditorFont(EditorFont.MONOSPACED)
        setAutoSync(true); setSyncOnCellular(true); setCacheRecents(true); setCacheFavorites(true)
        setSortOrder(null); setSmartLists(true); setOpenInPreview(false)
    }

    companion object {
        val FONT_SIZE_RANGE = 11f..24f
        const val DEFAULT_FONT_SIZE = 16f
        /** Quiet time before an autosave — the iOS app's 1.5 s. */
        const val AUTO_SAVE_DELAY_MS = 1_500L

        private const val THEME = "nn.theme"
        private const val FONT_SIZE = "nn.font_size"
        private const val EDITOR_FONT = "nn.editor_font"
        private const val AUTO_SYNC = "nn.auto_sync"
        private const val SYNC_CELLULAR = "nn.sync_on_cellular"
        private const val CACHE_RECENTS = "nn.cache_recents"
        private const val CACHE_FAVORITES = "nn.cache_favorites"
        private const val SORT = "nn.sort_order"
        private const val SMART_LISTS = "nn.smart_lists"
        private const val OPEN_IN_PREVIEW = "nn.open_in_preview"
    }
}
