package expo.modules.recorder

import android.content.Context
import android.location.Location
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import org.json.JSONObject

/**
 * Nightly tracks -> Fog of World tile conversion + Dropbox upload.
 * Marks only newly visited cells (merge is a bitwise OR into existing tiles),
 * then uploads modified tiles back to Dropbox (paths from the import
 * manifest, falling back to /Sync/<name>).
 */
object FowSync {
  private const val MASK1 = "olhwjsktri"
  private const val MASK2 = "eizxdwknmo"
  private const val CELLS = 8192 // cells per tile side
  private const val GAP_MS = 150_000L
  private const val GAP_M = 150f
  private const val MAX_SPEED_MPS = 80.0

  private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

  fun runIfDue(context: Context) {
    val prefs = RecorderModule.prefs(context)
    if (!prefs.getBoolean("tileSyncEnabled", false)) return
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    if (hour !in 2..5) return
    val today = dayFormat.format(Date())
    if (prefs.getString("tileSyncLastRun", "") == today) return
    prefs.edit().putString("tileSyncLastRun", today).apply()
    Thread {
      try {
        val report = run(context)
        prefs.edit().putString("tileSyncReport", "$today: $report").apply()
      } catch (e: Exception) {
        prefs.edit().putString("tileSyncReport", "$today: failed — ${e.message}").apply()
      }
    }.start()
  }

  fun run(context: Context): String {
    val prefs = RecorderModule.prefs(context)
    val fowDir = File(context.filesDir, "imports/fow").apply { mkdirs() }
    val tracksDir = RecorderModule.tracksDir(context)
    val today = dayFormat.format(Date())
    val convertedThrough = prefs.getString("tileSyncConvertedThrough", "") ?: ""

    // Completed days not yet converted (never today's still-growing file).
    val days = tracksDir.listFiles()
      ?.filter { it.name.endsWith(".csv") }
      ?.map { it.name.removeSuffix(".csv") }
      ?.filter { it < today && it > convertedThrough }
      ?.sorted()
      ?: emptyList()
    if (days.isEmpty()) return "nothing new to convert"

    // 1. Collect visited global cells from the tracks.
    val cellsByTile = HashMap<Int, HashSet<Int>>() // tileId -> packed cell (cy*CELLS+cx)
    for (day in days) {
      val lines = File(tracksDir, "$day.csv").readLines()
      var prev: DoubleArray? = null // ts, lat, lng
      for (line in lines) {
        val parts = line.split(",")
        if (parts.size < 3) continue
        val ts = parts[0].toDoubleOrNull() ?: continue
        val lat = parts[1].toDoubleOrNull() ?: continue
        val lng = parts[2].toDoubleOrNull() ?: continue
        markCell(cellsByTile, lng, lat)
        val p = prev
        if (p != null) {
          val dt = ts - p[0]
          val res = FloatArray(1)
          Location.distanceBetween(p[1], p[2], lat, lng, res)
          val speed = res[0] / (dt / 1000.0).coerceAtLeast(1.0)
          val isGap = (dt > GAP_MS && res[0] > GAP_M) || speed > MAX_SPEED_MPS
          if (!isGap) markLine(cellsByTile, p[2], p[1], lng, lat)
        }
        prev = doubleArrayOf(ts, lat, lng)
      }
    }
    if (cellsByTile.isEmpty()) {
      prefs.edit().putString("tileSyncConvertedThrough", days.last()).apply()
      return "no cells from ${days.size} day(s)"
    }

    // 2. Merge into tiles (only new cells change anything).
    val modified = ArrayList<String>()
    for ((tileId, cells) in cellsByTile) {
      val filename = encodeFilename(tileId)
      val file = File(fowDir, filename)
      val blocks: LinkedHashMap<Pair<Int, Int>, ByteArray> =
        if (file.exists()) decodeFull(file) ?: LinkedHashMap() else LinkedHashMap()
      var changed = 0
      for (packed in cells) {
        val cx = packed % CELLS
        val cy = packed / CELLS
        val bpos = Pair(cx / 64, cy / 64)
        val block = blocks.getOrPut(bpos) { ByteArray(FowCodec.BLOCK_SIZE) }
        val ix = cx % 64
        val iy = cy % 64
        val byteIdx = (ix shr 3) + iy * 8
        val bit = 1 shl (7 - (ix and 7))
        if (block[byteIdx].toInt() and bit == 0) {
          block[byteIdx] = (block[byteIdx].toInt() or bit).toByte()
          changed++
        }
      }
      if (changed > 0) {
        file.writeBytes(encodeTile(blocks))
        modified.add(filename)
      }
    }
    prefs.edit().putString("tileSyncConvertedThrough", days.last()).apply()
    if (modified.isEmpty()) return "converted ${days.size} day(s); all areas already unfogged"

    // 3. Upload modified tiles to Dropbox.
    var uploaded = 0
    var uploadError: String? = null
    try {
      val token = dropboxAccessToken(context)
      val manifest = readManifest(fowDir)
      for (name in modified) {
        val path = manifest.optString(name, "/Sync/$name")
        if (uploadFile(token, path, File(fowDir, name).readBytes())) uploaded++
      }
    } catch (e: Exception) {
      uploadError = e.message
    }
    return "converted ${days.size} day(s), ${modified.size} tile(s) updated, " +
      "$uploaded uploaded" + (uploadError?.let { " (upload error: $it)" } ?: "")
  }

  // --- geometry -------------------------------------------------------------

  private fun lngLatToCell(lng: Double, lat: Double): Pair<Long, Long> {
    val x = (lng + 180.0) / 360.0 * 512.0
    val rad = lat * Math.PI / 180.0
    val y = (Math.PI - kotlin.math.asinh(kotlin.math.tan(rad))) / (2.0 * Math.PI) * 512.0
    return Pair((x * CELLS).toLong(), (y * CELLS).toLong())
  }

  private fun markCell(map: HashMap<Int, HashSet<Int>>, lng: Double, lat: Double) {
    val (gx, gy) = lngLatToCell(lng, lat)
    markGlobal(map, gx, gy)
  }

  private fun markGlobal(map: HashMap<Int, HashSet<Int>>, gx: Long, gy: Long) {
    val world = 512L * CELLS
    if (gx < 0 || gy < 0 || gx >= world || gy >= world) return
    val tileX = (gx / CELLS).toInt()
    val tileY = (gy / CELLS).toInt()
    val tileId = tileY * 512 + tileX
    val cx = (gx % CELLS).toInt()
    val cy = (gy % CELLS).toInt()
    map.getOrPut(tileId) { HashSet() }.add(cy * CELLS + cx)
  }

  /** Bresenham between two fixes so the trail is continuous. */
  private fun markLine(
    map: HashMap<Int, HashSet<Int>>,
    lng0: Double, lat0: Double, lng1: Double, lat1: Double,
  ) {
    var (x0, y0) = lngLatToCell(lng0, lat0)
    val (x1, y1) = lngLatToCell(lng1, lat1)
    val dx = kotlin.math.abs(x1 - x0)
    val dy = -kotlin.math.abs(y1 - y0)
    val sx = if (x0 < x1) 1L else -1L
    val sy = if (y0 < y1) 1L else -1L
    var err = dx + dy
    var guard = 0
    while (guard++ < 500_000) {
      markGlobal(map, x0, y0)
      if (x0 == x1 && y0 == y1) break
      val e2 = 2 * err
      if (e2 >= dy) { err += dy; x0 += sx }
      if (e2 <= dx) { err += dx; y0 += sy }
    }
  }

  // --- tile encode ----------------------------------------------------------

  fun encodeFilename(tileId: Int): String {
    val idStr = tileId.toString()
    val md5 = MessageDigest.getInstance("MD5").digest(idStr.toByteArray())
      .joinToString("") { "%02x".format(it) }.substring(0, 4)
    val body = idStr.map { MASK1[it - '0'] }.joinToString("")
    val tail = idStr.takeLast(2).map { MASK2[it - '0'] }.joinToString("")
    return md5 + body + tail
  }

  /** Full decode keeping each block's 515 bytes (bitmap + extra). */
  private fun decodeFull(file: File): LinkedHashMap<Pair<Int, Int>, ByteArray>? {
    val blocks = FowCodec.decodeTileWithExtra(file) ?: return null
    return blocks
  }

  private fun encodeTile(blocks: Map<Pair<Int, Int>, ByteArray>): ByteArray {
    val header = ByteArray(FowCodec.TILE_HEADER_SIZE)
    val sorted = blocks.entries.sortedBy { it.key.first + it.key.second * FowCodec.TILE_WIDTH }
    val blockData = ByteArray(FowCodec.BLOCK_SIZE * sorted.size)
    var idx = 1
    for ((pos, block) in sorted) {
      val i = pos.first + pos.second * FowCodec.TILE_WIDTH
      header[i * 2] = (idx and 0xFF).toByte()          // little-endian uint16
      header[i * 2 + 1] = ((idx shr 8) and 0xFF).toByte()
      // recompute checksum: keep top 2 bits of extra byte 1, set 14-bit count*2+1
      var count = 0
      for (b in 0 until FowCodec.BLOCK_BITMAP_SIZE) {
        count += Integer.bitCount(block[b].toInt() and 0xFF)
      }
      val chk = (count shl 1) + 1
      block[FowCodec.BLOCK_BITMAP_SIZE + 1] =
        ((block[FowCodec.BLOCK_BITMAP_SIZE + 1].toInt() and 0xC0) or ((chk shr 8) and 0x3F)).toByte()
      block[FowCodec.BLOCK_BITMAP_SIZE + 2] = (chk and 0xFF).toByte()
      System.arraycopy(block, 0, blockData, (idx - 1) * FowCodec.BLOCK_SIZE, FowCodec.BLOCK_SIZE)
      idx++
    }
    val raw = header + blockData
    val deflater = Deflater()
    deflater.setInput(raw)
    deflater.finish()
    val out = java.io.ByteArrayOutputStream(raw.size / 2)
    val buf = ByteArray(64 * 1024)
    while (!deflater.finished()) {
      val n = deflater.deflate(buf)
      out.write(buf, 0, n)
    }
    deflater.end()
    return out.toByteArray()
  }

  // --- dropbox ----------------------------------------------------------------

  private fun readManifest(fowDir: File): JSONObject {
    val f = File(fowDir, "_paths.json")
    return if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
    else JSONObject()
  }

  private fun dropboxAccessToken(context: Context): String {
    val prefs = RecorderModule.prefs(context)
    val appKey = prefs.getString("dbxAppKey", null) ?: throw IllegalStateException("no Dropbox app key")
    val refresh = prefs.getString("dbxRefreshToken", null) ?: throw IllegalStateException("Dropbox not connected")
    val body = "grant_type=refresh_token" +
      "&refresh_token=" + URLEncoder.encode(refresh, "UTF-8") +
      "&client_id=" + URLEncoder.encode(appKey, "UTF-8")
    val conn = URL("https://api.dropboxapi.com/oauth2/token").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    conn.outputStream.use { it.write(body.toByteArray()) }
    if (conn.responseCode != 200) throw IllegalStateException("token refresh HTTP ${conn.responseCode}")
    val json = JSONObject(conn.inputStream.bufferedReader().readText())
    return json.getString("access_token")
  }

  private fun uploadFile(token: String, path: String, bytes: ByteArray): Boolean {
    val arg = JSONObject().put("path", path).put("mode", "overwrite").put("mute", true)
    val conn = URL("https://content.dropboxapi.com/2/files/upload").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Authorization", "Bearer $token")
    conn.setRequestProperty("Dropbox-API-Arg", arg.toString())
    conn.setRequestProperty("Content-Type", "application/octet-stream")
    conn.outputStream.use { it.write(bytes) }
    val ok = conn.responseCode == 200
    conn.disconnect()
    return ok
  }
}
