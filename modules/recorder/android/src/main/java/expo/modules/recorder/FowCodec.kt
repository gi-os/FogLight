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
  fun renderTile(context: Context, path: String, sizePx: Int, color: Int, style: Int): String? {
    val src = File(path)
    if (!src.exists()) return null
    val outDir = File(context.cacheDir, "fow").apply { mkdirs() }
    val out = File(outDir, "${src.name}_${sizePx}_${color.toUInt().toString(16)}_$style.png")
    if (out.exists() && out.lastModified() >= src.lastModified()) return out.absolutePath

    val blocks = decodeTile(src) ?: return null
    val pxPerBlock = sizePx / TILE_WIDTH
    if (pxPerBlock <= 0) return null
    val cellsPerPx = BITMAP_WIDTH / pxPerBlock
    if (cellsPerPx <= 0) return null
    val cellsPerRegion = cellsPerPx * cellsPerPx

    // Visited-coverage grid (0..255 per pixel), blurred, then colorized as
    // dark fog with a glowing rim along clearing boundaries.
    val frac = IntArray(sizePx * sizePx)
    for ((pos, bitmap) in blocks) {
      val baseX = pos.first * pxPerBlock
      val baseY = pos.second * pxPerBlock
      for (py in 0 until pxPerBlock) {
        for (px in 0 until pxPerBlock) {
          var cnt = 0
          for (cy in py * cellsPerPx until (py + 1) * cellsPerPx) {
            for (cx in px * cellsPerPx until (px + 1) * cellsPerPx) {
              val bit = bitmap[(cx shr 3) + cy * 8].toInt() and (1 shl (7 - (cx and 7)))
              if (bit != 0) cnt++
            }
          }
          if (cnt > 0) {
            frac[(baseY + py) * sizePx + baseX + px] = (cnt * 255) / cellsPerRegion
          }
        }
      }
    }
    val bmp: Bitmap
    when (style) {
      STYLE_PIXEL, STYLE_PIXEL2X -> {
        // Binary mask: a pixel clears once a quarter of its cells are visited.
        for (i in frac.indices) frac[i] = if (frac[i] * 4 >= 255) 255 else 0
        if (style == STYLE_PIXEL2X) {
          val doubled = scale2x(frac, sizePx, sizePx)
          bmp = Bitmap.createBitmap(sizePx * 2, sizePx * 2, Bitmap.Config.ARGB_8888)
          bmp.setPixels(colorize(doubled, color), 0, sizePx * 2, 0, 0, sizePx * 2, sizePx * 2)
        } else {
          bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
          bmp.setPixels(colorize(frac, color), 0, sizePx, 0, 0, sizePx, sizePx)
        }
      }
      else -> {
        blurGrid(frac, sizePx, sizePx, radius = 1)
        bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(colorize(frac, color), 0, sizePx, 0, 0, sizePx, sizePx)
      }
    }
    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()
    return out.absolutePath
  }

  const val STYLE_SMOOTH = 0
  const val STYLE_PIXEL = 1
  const val STYLE_PIXEL2X = 2

  /**
   * Scale2x / EPX — the classic pixel-art upscaler (same family as emulator
   * filters): doubles resolution, rounding staircase edges without blur.
   */
  private fun scale2x(m: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h * 4)
    val w2 = w * 2
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = m[y * w + x]
        val a = if (y > 0) m[(y - 1) * w + x] else p       // up
        val b = if (x < w - 1) m[y * w + x + 1] else p     // right
        val c = if (x > 0) m[y * w + x - 1] else p         // left
        val d = if (y < h - 1) m[(y + 1) * w + x] else p   // down
        var e0 = p; var e1 = p; var e2 = p; var e3 = p
        if (c == a && c != d && a != b) e0 = a
        if (a == b && a != c && b != d) e1 = b
        if (d == c && d != b && c != a) e2 = c
        if (b == d && b != a && d != c) e3 = b
        val oy = y * 2 * w2 + x * 2
        out[oy] = e0
        out[oy + 1] = e1
        out[oy + w2] = e2
        out[oy + w2 + 1] = e3
      }
    }
    return out
  }

  /** Two-pass box blur over an int grid (feathering for coverage values). */
  private fun blurGrid(a: IntArray, w: Int, h: Int, radius: Int) {
    if (radius <= 0) return
    val tmp = IntArray(w * h)
    val div = radius * 2 + 1
    for (y in 0 until h) {
      val row = y * w
      var sum = 0
      for (x in -radius..radius) sum += a[row + x.coerceIn(0, w - 1)]
      for (x in 0 until w) {
        tmp[row + x] = sum / div
        sum += a[row + (x + radius + 1).coerceAtMost(w - 1)] -
          a[row + (x - radius).coerceAtLeast(0)]
      }
    }
    for (x in 0 until w) {
      var sum = 0
      for (y in -radius..radius) sum += tmp[y.coerceIn(0, h - 1) * w + x]
      for (y in 0 until h) {
        a[y * w + x] = sum / div
        sum += tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x] -
          tmp[(y - radius).coerceAtLeast(0) * w + x]
      }
    }
  }

  /** Coverage (0..255) -> ARGB: fog alpha thins linearly with coverage. */
  private fun colorize(frac: IntArray, fog: Int): IntArray {
    val fogA = (fog ushr 24) and 0xFF
    val rgb = fog and 0x00FFFFFF
    val out = IntArray(frac.size)
    for (i in out.indices) {
      val alpha = (fogA * (255 - frac[i])) / 255
      out[i] = (alpha shl 24) or rgb
    }
    return out
  }

  /**
   * One world-spanning inverted-fog PNG: opaque fog everywhere, thinned by
   * the fraction of visited cells contributing to each pixel.
   * sizePx must divide 65536 (use 2048 or 4096).
   */
  fun renderOverview(context: Context, dirPath: String, sizePx: Int, color: Int): String? {
    val dir = File(dirPath)
    val files = dir.listFiles()?.filter { decodeFilename(it.name) != null } ?: return null
    if (files.isEmpty()) return null
    val outDir = File(context.cacheDir, "fow").apply { mkdirs() }
    val out = File(outDir, "overview_${sizePx}_${color.toUInt().toString(16)}.png")
    val newest = files.maxOf { it.lastModified() }
    if (out.exists() && out.lastModified() >= newest) return out.absolutePath

    val worldBlocks = MAP_WIDTH * TILE_WIDTH // 65536
    val blocksPerPx = worldBlocks / sizePx
    if (blocksPerPx < 1) return null
    val cellsPerPx = blocksPerPx * blocksPerPx * BITMAP_WIDTH * BITMAP_WIDTH

    val visited = IntArray(sizePx * sizePx)
    for (file in files) {
      val id = decodeFilename(file.name) ?: continue
      val tileX = id % MAP_WIDTH
      val tileY = id / MAP_WIDTH
      val blocks = decodeTile(file) ?: continue
      for ((pos, bitmap) in blocks) {
        var cnt = 0
        for (b in bitmap) cnt += Integer.bitCount(b.toInt() and 0xFF)
        val x = (tileX * TILE_WIDTH + pos.first) / blocksPerPx
        val y = (tileY * TILE_WIDTH + pos.second) / blocksPerPx
        if (x in 0 until sizePx && y in 0 until sizePx) {
          visited[y * sizePx + x] += cnt
        }
      }
    }

    val frac = IntArray(sizePx * sizePx)
    for (i in frac.indices) {
      // Boost sparse coverage so thin travel still clears visible fog.
      frac[i] = ((visited[i].toDouble() * 24.0 * 255.0) / cellsPerPx).toInt().coerceAtMost(255)
    }
    val bmp: Bitmap
    when (style) {
      STYLE_PIXEL, STYLE_PIXEL2X -> {
        // Binary mask: a pixel clears once a quarter of its cells are visited.
        for (i in frac.indices) frac[i] = if (frac[i] * 4 >= 255) 255 else 0
        if (style == STYLE_PIXEL2X) {
          val doubled = scale2x(frac, sizePx, sizePx)
          bmp = Bitmap.createBitmap(sizePx * 2, sizePx * 2, Bitmap.Config.ARGB_8888)
          bmp.setPixels(colorize(doubled, color), 0, sizePx * 2, 0, 0, sizePx * 2, sizePx * 2)
        } else {
          bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
          bmp.setPixels(colorize(frac, color), 0, sizePx, 0, 0, sizePx, sizePx)
        }
      }
      else -> {
        blurGrid(frac, sizePx, sizePx, radius = 1)
        bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(colorize(frac, color), 0, sizePx, 0, 0, sizePx, sizePx)
      }
    }
    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()
    return out.absolutePath
  }

  const val STYLE_SMOOTH = 0
  const val STYLE_PIXEL = 1
  const val STYLE_PIXEL2X = 2

  /**
   * Scale2x / EPX — the classic pixel-art upscaler (same family as emulator
   * filters): doubles resolution, rounding staircase edges without blur.
   */
  private fun scale2x(m: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h * 4)
    val w2 = w * 2
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = m[y * w + x]
        val a = if (y > 0) m[(y - 1) * w + x] else p       // up
        val b = if (x < w - 1) m[y * w + x + 1] else p     // right
        val c = if (x > 0) m[y * w + x - 1] else p         // left
        val d = if (y < h - 1) m[(y + 1) * w + x] else p   // down
        var e0 = p; var e1 = p; var e2 = p; var e3 = p
        if (c == a && c != d && a != b) e0 = a
        if (a == b && a != c && b != d) e1 = b
        if (d == c && d != b && c != a) e2 = c
        if (b == d && b != a && d != c) e3 = b
        val oy = y * 2 * w2 + x * 2
        out[oy] = e0
        out[oy + 1] = e1
        out[oy + w2] = e2
        out[oy + w2 + 1] = e3
      }
    }
    return out
  }
}
