package app.quiet.launcher.feature.underwater

import android.content.Context
import app.quiet.launcher.core.model.AppEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A folder of favourites on the underwater Home (ids are [app.quiet.launcher.core.model.AppKey.id]). */
data class DockFolder(val id: Long, val name: String, val members: List<String>)

/** One row of the underwater dock: a favourite, or a folder of favourites. */
sealed interface DockItem {
    val key: String
    data class App(val app: AppEntry) : DockItem { override val key get() = app.key.id }
    data class Folder(val folder: DockFolder, val apps: List<AppEntry>) : DockItem { override val key get() = "folder:${folder.id}" }
}

/**
 * Folders of favourites, made by dropping one favourite's glass drop onto another (Quiet
 * Underwater only). Members stay favourites in the favourites table, so the classic edition (which
 * never reads this) simply shows them as a flat list. A folder sits where its first member is in
 * the favourites order. Persisted in SharedPreferences "dock_folders" as JSON.
 */
class DockFolders private constructor(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("dock_folders", Context.MODE_PRIVATE)
    private val _folders = MutableStateFlow(load())
    val folders: StateFlow<List<DockFolder>> = _folders.asStateFlow()

    /** Groups [favourites] (in their order) into dock rows. A folder with fewer than two live members shows as its app. */
    fun items(favourites: List<AppEntry>, folders: List<DockFolder> = _folders.value): List<DockItem> {
        val byId = favourites.associateBy { it.key.id }
        val folderOf = HashMap<String, DockFolder>()
        for (f in folders) {
            val live = f.members.filter { it in byId }
            if (live.size >= 2) live.forEach { folderOf[it] = f }
        }
        val out = ArrayList<DockItem>(favourites.size)
        val emitted = HashSet<Long>()
        for (a in favourites) {
            val f = folderOf[a.key.id]
            if (f == null) out += DockItem.App(a)
            else if (emitted.add(f.id)) out += DockItem.Folder(f, f.members.mapNotNull { byId[it] })
        }
        return out
    }

    /** Drops [dragged] onto [target]: joins the target's folder, or makes a new one of the two. */
    fun drop(dragged: AppEntry, target: DockItem) {
        val did = dragged.key.id
        val cleaned = _folders.value.map { it.copy(members = it.members - did) }
        val next = when (target) {
            is DockItem.Folder -> cleaned.map { if (it.id == target.folder.id) it.copy(members = it.members + did) else it }
            is DockItem.App -> {
                val tid = target.app.key.id
                val withoutT = cleaned.map { it.copy(members = it.members - tid) }
                withoutT + DockFolder(System.currentTimeMillis(), nameFor(target.app, dragged), listOf(tid, did))
            }
        }
        save(next)
    }

    /** Takes [app] out of folder [folderId] (it stays a favourite, in its own row). */
    fun remove(folderId: Long, app: AppEntry) {
        save(_folders.value.map { if (it.id == folderId) it.copy(members = it.members - app.key.id) else it })
    }

    fun rename(folderId: Long, name: String) {
        val n = name.trim().ifEmpty { return }
        save(_folders.value.map { if (it.id == folderId) it.copy(name = n) else it })
    }

    private fun nameFor(a: AppEntry, b: AppEntry): String = "${a.label} & ${b.label}".let { if (it.length > 22) a.label else it }

    private fun save(list: List<DockFolder>) {
        val kept = list.filter { it.members.size >= 2 }
        _folders.value = kept
        val arr = JSONArray()
        kept.forEach { f ->
            arr.put(JSONObject().put("id", f.id).put("name", f.name).put("members", JSONArray(f.members)))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(): List<DockFolder> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val m = o.getJSONArray("members")
            DockFolder(o.getLong("id"), o.optString("name", "Folder"), (0 until m.length()).map { m.getString(it) })
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY = "folders"
        @Volatile private var instance: DockFolders? = null
        fun get(ctx: Context): DockFolders = instance ?: synchronized(this) { instance ?: DockFolders(ctx).also { instance = it } }
    }
}
