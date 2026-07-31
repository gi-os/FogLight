package expo.modules.recorder

import android.content.ContentProvider
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
 * `content://com.gios.lightfog.stays/stays/2026-07-30`
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
        val cursor = MatrixCursor(COLUMNS)
        val context = context ?: return cursor
        // The day is the last path segment, and it is the file name the recorder already uses.
        // Validated rather than trusted: this is an exported provider, and the segment ends up in a
        // File path, so anything that is not exactly a date is refused before it gets there.
        val day = uri.lastPathSegment.orEmpty()
        if (!DAY.matches(day)) return cursor

        val file = File(RecorderModule.tracksDir(context), "$day.csv")
        if (!file.isFile) return cursor

        val fixes = runCatching { file.readLines().mapNotNull(Stays::parse) }.getOrDefault(emptyList())
        Stays.of(fixes).forEach { stay ->
            // Explicitly Any?, or Kotlin infers the intersection of Long, Double and Int and
            // warns about reifying it. A cursor row is a heterogeneous list by definition.
            cursor.addRow(
                arrayOf<Any?>(stay.startMs, stay.endMs, stay.latitude, stay.longitude, stay.fixes),
            )
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

        /** `YYYY-MM-DD`, and nothing else — the segment becomes part of a file path. */
        val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
