package expo.modules.recorder

import android.content.ContentProvider
import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

/**
 * Where you were on a day, offered to the rest of the collection.
 *
 * This app already records the track and already converts it nightly into tiles, so it is the one
 * place that should ever ask the OS for a location — nothing else in the collection needs the
 * permission, the battery cost or the responsibility. What it hands over is **stays**, not fixes and
 * not tiles: a tile says which square of the world you crossed, and a journal wants the places you
 * stopped.
 *
 * Read-only, one row per stay, queried by day. Privacy zones are already filtered at the point the
 * track is written, so anything inside one was never on disk to serve.
 *
 * Two paths:
 *
 * - `content://com.gios.lightfog.stays/stays/2026-07-30` — where you stopped, with coordinates.
 * - `content://com.gios.lightfog.stays/zones/2026-07-30` — arrivals at a place you have *named*,
 *   with no coordinates at all. Home and work are privacy zones: their fixes never reach the track,
 *   so they can never be a stay, and a journal that showed only the places you went out to would be
 *   missing most of a life. What is served is "went home at 19:40" — which locates you only if you
 *   already know where home is, and whoever is reading this does.
 */
class StaysProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = context ?: return MatrixCursor(COLUMNS)
        val segments = uri.pathSegments
        val day = segments.lastOrNull().orEmpty()
        // Validated rather than trusted: this is an exported provider and the segment ends up in a
        // File path, so anything that is not exactly a date is refused before it gets there.
        if (!DAY.matches(day)) return MatrixCursor(COLUMNS)

        return if (segments.firstOrNull() == PATH_ZONES) {
            zones(context, day)
        } else {
            stays(context, day)
        }
    }

    private fun stays(context: Context, day: String): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val file = File(RecorderModule.tracksDir(context), "$day.csv")
        if (!file.isFile) return cursor

        val fixes = runCatching { file.readLines().mapNotNull(Stays::parse) }.getOrDefault(emptyList())
        Stays.of(fixes).forEach { stay ->
            // Explicitly Any?, or Kotlin infers the intersection of Long, Double and Int and warns
            // about reifying it. A cursor row is a heterogeneous list by definition.
            cursor.addRow(
                arrayOf<Any?>(stay.startMs, stay.endMs, stay.latitude, stay.longitude, stay.fixes),
            )
        }
        return cursor
    }

    /**
     * Arrivals at a named zone. Timestamps and names, and deliberately nothing else.
     *
     * There is no position in this cursor and none in the file behind it. That is the whole point:
     * the journal learns you went home, and gains no way to tell anyone where home is.
     */
    private fun zones(context: Context, day: String): Cursor {
        val cursor = MatrixCursor(ZONE_COLUMNS)
        val file = File(RecorderModule.zonesDir(context), "$day.csv")
        if (!file.isFile) return cursor

        runCatching {
            file.readLines().forEach { line ->
                val parts = line.split(',')
                if (parts.size < 2) return@forEach
                val at = parts[0].trim().toLongOrNull() ?: return@forEach
                val name = parts[1].trim().takeIf { it.isNotEmpty() } ?: return@forEach
                cursor.addRow(arrayOf<Any?>(at, name))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.lightfog.stay"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        val COLUMNS = arrayOf("start_ms", "end_ms", "latitude", "longitude", "fixes")
        val ZONE_COLUMNS = arrayOf("at_ms", "name")
        const val PATH_ZONES = "zones"

        /** `YYYY-MM-DD`, and nothing else — the segment becomes part of a file path. */
        val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
