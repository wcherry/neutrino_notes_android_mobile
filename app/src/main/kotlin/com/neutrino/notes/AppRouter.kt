package com.neutrino.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neutrino.notes.core.model.NoteItem
import com.neutrino.notes.core.model.NoteTag
import com.neutrino.notes.core.model.NotesSection

/** The bottom bar. Favorites, Shared and Trash are sections of Notes, as five tabs is the comfortable most. */
enum class Tab(val label: String) { NOTES("Notes"), RECENT("Recent"), TAGS("Tags"), OFFLINE("Offline"), SETTINGS("Settings") }

/** A screen pushed over a tab. */
sealed class Route {
    data class Folder(val section: NotesSection, val folderId: String, val title: String) : Route()
    data class Editor(val item: NoteItem, val startEditing: Boolean = false) : Route()
    data class Tagged(val tag: NoteTag) : Route()
    object Devices : Route()
    object AppLockSettings : Route()
    object Pairing : Route()
    object RecoveryKit : Route()
    object KeyCode : Route()
    object SetUpEncryption : Route()
    object Trash : Route()
}

/**
 * Where the app is showing, for what arrives from outside the tabs — a link naming a note, or one
 * note opening another. Held in the process, so a link that lands during sign-in still opens once
 * the app shows.
 */
class AppRouter {
    var tab by mutableStateOf(Tab.NOTES)
    private val stacks = Tab.entries.associateWith { mutableStateListOf<Route>() }

    fun stack(tab: Tab): List<Route> = stacks.getValue(tab)
    val current: Route? get() = stacks.getValue(tab).lastOrNull()

    fun push(route: Route, on: Tab = tab) {
        tab = on
        stacks.getValue(on).add(route)
    }

    fun pop(): Boolean {
        val stack = stacks.getValue(tab)
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popWhile(predicate: (Route) -> Boolean) {
        val stack = stacks.getValue(tab)
        while (stack.isNotEmpty() && predicate(stack.last())) stack.removeAt(stack.lastIndex)
    }

    /** A second tap on the tab showing goes back to its root, as tab bars do. */
    fun select(tab: Tab) {
        if (this.tab == tab) stacks.getValue(tab).clear()
        this.tab = tab
    }

    /** A note to open from a link: onto Notes, with the stack reset so there is no unwalked back trail. */
    fun open(item: NoteItem) {
        stacks.getValue(Tab.NOTES).clear()
        push(Route.Editor(item), Tab.NOTES)
    }

    /** One note opening another — a `[[link]]` or a backlink — stays on the tab it was opened from, so Back returns. */
    fun follow(item: NoteItem) = push(Route.Editor(item))

    var pendingLink by mutableStateOf<String?>(null)
    var linkError by mutableStateOf<String?>(null)
    var newNoteRequested by mutableStateOf(false)

    fun reset() {
        stacks.values.forEach { it.clear() }
        tab = Tab.NOTES
        pendingLink = null
        linkError = null
        newNoteRequested = false
    }
}
