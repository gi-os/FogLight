package expo.modules.recorder

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Fog of World sync-tile codec (read-only).
 * Format: world = 512x512 tiles; tile file = zlib(header 128*128 uint16-LE
 * block indices + 515-byte blocks: 512-byte 64x64 bitmap + 3 extra bytes).
 * Filename embeds the tile id with digit mask "olhwjsktri".
 */
object FowCodec {
  const val MAP_WIDTH = 512
  const val TILE_WIDTH = 128
  const val BITMAP_WIDTH = 64
  const val TILE_HEADER_LEN = TILE_WIDTH * TILE_WIDTH
  const val TILE_HEADER_SIZE = TILE_HEADER_LEN * 2
  const val BLOCK_BITMAP_SIZE = 512
  const val BLOCK_SIZE = BLOCK_BITMAP_SIZE + 3
  private const val MASK1 = "olhwjsktri"

  fun decodeFilename(name: String): Int? {
    if (name.length < 7) return null
    var id = 0
    for (ch in name.substring(4, name.length - 2)) {
      val v = MASK1.indexOf(ch)
      if (v < 0) return null
      id = id * 10 + v
      if (id >= MAP_WIDTH * MAP_WIDTH) return null
    }
    return id
  }

  private fun inflate(file: File): ByteArray? = try {
    val compressed = file.readBytes()
    val inflater = Inflater()
    inflater.setInput(compressed)
    val out = ByteArrayOutputStream(compressed.size * 4)
    val buf = ByteArray(64 * 1024)
    while (!inflater.finished()) {
      val n = inflater.inflate(buf)
      if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
      out.write(buf, 0, n)
    }
    inflater.end()
    out.toByteArray()
  } catch (_: Exception) {
    null
  }

  /** (blockX, blockY) -> 512-byte visited bitmap */
  fun decodeTile(file: File): Map<Pair<Int, Int>, ByteArray>? {
    val data = inflate(file) ?: return null
    if (data.size < TILE_HEADER_SIZE) return null
    val header = ByteBuffer.wrap(data, 0, TILE_HEADER_SIZE)
      .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val blocks = HashMap<Pair<Int, Int>, ByteArray>()
    for (i in 0 until TILE_HEADER_LEN) {
      val idx = header.get(i).toInt() and 0xFFFF
      if (idx > 0) {
        val start = TILE_HEADER_SIZE + (idx - 1) * BLOCK_SIZE
        if (start + BLOCK_BITMAP_SIZE > data.size) continue
        blocks[Pair(i % TILE_WIDTH, i / TILE_WIDTH)] =
          data.copyOfRange(start, start + BLOCK_BITMAP_SIZE)
      }
    }
    return blocks
  }

  /** Step-by-step decode report for debugging device-side failures. */
  fun inspect(path: String): String {
    val f = File(path)
    if (!f.exists()) return "missing: $path"
    val raw = try { f.readBytes() } catch (e: Exception) { return "read fail: ${e.message}" }
    val head = raw.take(4).joinToString(" ") { "%02x".format(it) }
    val sb = StringBuilder("size=${raw.size} head=[$head]")
    val data = try {
      val inflater = Inflater()
      inflater.setInput(raw)
      val out = java.io.ByteArrayOutputStream(raw.size * 4)
      val buf = ByteArray(64 * 1024)
      while (!inflater.finished()) {
        val n = inflater.inflate(buf)
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        out.write(buf, 0, n)
      }
      inflater.end()
      out.toByteArray()
    } catch (e: Exception) {
      return sb.append(" inflate EXC: ${e.javaClass.simpleName} ${e.message}").toString()
    }
    sb.append(" inflated=${data.size}")
    if (data.size < TILE_HEADER_SIZE) return sb.append(" (< header size $TILE_HEADER_SIZE)").toString()
    val header = ByteBuffer.wrap(data, 0, TILE_HEADER_SIZE)
      .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var nonZero = 0
    var maxIdx = 0
    for (i in 0 until TILE_HEADER_LEN) {
      val idx = header.get(i).toInt() and 0xFFFF
      if (idx > 0) { nonZero++; if (idx > maxIdx) maxIdx = idx }
    }
    val expected = TILE_HEADER_SIZE + maxIdx * BLOCK_SIZE
    sb.append(" hdrBlocks=$nonZero maxIdx=$maxIdx expectedSize>=$expected")
    return sb.toString()
  }

  /**
   * Rasterize one tile to a transparent PNG (visited cells -> color).
   * sizePx must keep cells-per-pixel a multiple of 8 (use 1024, 512 or 256).
   */
  fun renderTile(context: Context, path: String, sizePx: Int, color: Int): String? {
    val src = File(path)
    if (!src.exists()) return null
    val outDir = File(context.cacheDir, "fow").apply { mkdirs() }
    val out = File(outDir, "${src.name}_${sizePx}_${color.toUInt().toString(16)}.png")
    if (out.exists() && out.lastModified() >= src.lastModified()) return out.absolutePath

    val blocks = decodeTile(src) ?: return null
    val pxPerBlock = sizePx / TILE_WIDTH
    val cellsPerPx = BITMAP_WIDTH / pxPerBlock
    val bytesPerPxX = cellsPerPx / 8
    if (pxPerBlock <= 0 || bytesPerPxX <= 0) return null

    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for ((pos, bitmap) in blocks) {
      val baseX = pos.first * pxPerBlock
      val baseY = pos.second * pxPerBlock
      for (py in 0 until pxPerBlock) {
        for (px in 0 until pxPerBlock) {
          var any = false
          val rowStart = py * cellsPerPx
          outer@ for (r in 0 until cellsPerPx) {
            val j = rowStart + r
            for (b in 0 until bytesPerPxX) {
              if (bitmap[(px * bytesPerPxX + b) + j * 8].toInt() != 0) {
                any = true
                break@outer
              }
            }
          }
          if (any) bmp.setPixel(baseX + px, baseY + py, color)
        }
      }
    }
    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()
    return out.absolutePath
  }

  /** One world-spanning PNG plotting every visited block across all tiles in dir. */
  fun renderOverview(context: Context, dirPath: String, sizePx: Int, color: Int): String? {
    val dir = File(dirPath)
    val files = dir.listFiles()?.filter { decodeFilename(it.name) != null } ?: return null
    if (files.isEmpty()) return null
    val outDir = File(context.cacheDir, "fow").apply { mkdirs() }
    val out = File(outDir, "overview_${sizePx}_${color.toUInt().toString(16)}.png")
    val newest = files.maxOf { it.lastModified() }
    if (out.exists() && out.lastModified() >= newest) return out.absolutePath

    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val worldBlocks = MAP_WIDTH * TILE_WIDTH // 65536
    val scale = sizePx.toDouble() / worldBlocks
    for (file in files) {
      val id = decodeFilename(file.name) ?: continue
      val tileX = id % MAP_WIDTH
      val tileY = id / MAP_WIDTH
      val blocks = decodeTile(file) ?: continue
      for (pos in blocks.keys) {
        val x = ((tileX * TILE_WIDTH + pos.first) * scale).toInt().coerceIn(0, sizePx - 1)
        val y = ((tileY * TILE_WIDTH + pos.second) * scale).toInt().coerceIn(0, sizePx - 1)
        bmp.setPixel(x, y, color)
      }
    }
    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()
    return out.absolutePath
  }
}
