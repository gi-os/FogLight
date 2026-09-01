package expo.modules.recorder

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONObject

/**
 * Today's weather, offered to the rest of the collection.
 *
 * The recorder is the only app on this phone allowed to know where it is, and weather is a
 * function of where you are — so anything else that wants a temperature would otherwise need
 * its own location permission, its own GPS budget and its own copy of this app's mistakes.
 * Instead it asks here and gets **weather only**: no coordinate is in the cursor, none is in
 * any column, and the coordinate behind the numbers was rounded to ~1 km before it ever left
 * the phone.
 *
 * Read-only, one path, one row:
 *
 * - `content://com.gios.lightfog.weather/today` —
 *   `updatedAt` (Long, epoch ms of the fetch), `tempC` (Double), `hiC` (Double), `loC`
 *   (Double), `code` (Int, WMO), `description` (String, e.g. "Clear"), `precipPct` (Int 0-100).
 *
 * Stale weather is still served — `updatedAt` is right there and how old is too old is the
 * reader's call, not this provider's. Nothing cached, or a cache that will not parse, is an
 * empty cursor: absent, never broken.
 */
class WeatherProvider : ContentProvider() {

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
        if (uri.pathSegments.firstOrNull() != PATH_TODAY) return cursor

        runCatching {
            val raw = RecorderModule.prefs(context).getString(WeatherFetcher.KEY_CACHE, null)
                ?: return cursor
            val json = JSONObject(raw)
            val updatedAt = json.getLong("updatedAt")
            val code = json.getInt("code")
            // Explicitly Any?, or Kotlin infers the intersection of the column types and warns
            // about reifying it. A cursor row is a heterogeneous list by definition.
            cursor.addRow(
                arrayOf<Any?>(
                    updatedAt,
                    json.getDouble("tempC"),
                    json.getDouble("hiC"),
                    json.getDouble("loC"),
                    code,
                    Weather.describe(code),
                    json.optInt("precipPct", 0),
                ),
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.lightfog.weather"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        val COLUMNS =
            arrayOf("updatedAt", "tempC", "hiC", "loC", "code", "description", "precipPct")
        const val PATH_TODAY = "today"
    }
}
