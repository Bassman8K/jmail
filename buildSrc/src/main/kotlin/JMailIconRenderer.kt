import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Renders the JMail application icon with Java2D and packs it into the container formats the
 * native installers expect.
 *
 * Generating icons during the build (rather than committing binaries) keeps the repository
 * text-only and guarantees `./run.sh package` works on a clean checkout without ImageMagick,
 * `iconutil` or any other image tooling being installed.
 */
object JMailIconRenderer {

    private val brandStart = Color(0x4F46E5) // indigo 600
    private val brandEnd = Color(0x7C3AED) // violet 600
    private val envelope = Color(0xFFFFFF)
    private val envelopeShade = Color(0xE0E7FF)
    private val accent = Color(0xF59E0B) // amber 500 — the "unread" dot

    /** Sizes rendered once and reused by every container format. */
    val sizes = listOf(16, 32, 64, 128, 256, 512, 1024)

    fun renderPng(size: Int): ByteArray = renderPng(size, rounded = true)

    /**
     * The iOS app icon, which has to be a fully opaque square: iOS applies its own corner
     * mask, and an icon carrying an alpha channel is rejected. So the artwork runs edge to
     * edge here rather than being rounded off like the desktop icon.
     */
    fun renderIosAppIconPng(size: Int): ByteArray = renderPng(size, rounded = false)

    private fun renderPng(size: Int, rounded: Boolean): ByteArray {
        require(size > 0) { "Icon size must be positive, was $size" }

        val type = if (rounded) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(size, size, type)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val s = size.toFloat()

        // Squircle-ish background with the brand gradient — square when iOS will mask it.
        val corner = if (rounded) s * 0.45f else 0f
        g.paint = GradientPaint(0f, 0f, brandStart, s, s, brandEnd)
        g.fill(RoundRectangle2D.Float(0f, 0f, s, s, corner, corner))

        // Envelope body.
        val left = s * 0.20f
        val top = s * 0.30f
        val width = s * 0.60f
        val height = s * 0.40f
        val bodyCorner = s * 0.06f
        g.paint = envelope
        g.fill(RoundRectangle2D.Float(left, top, width, height, bodyCorner, bodyCorner))

        // Folded flap, shaded so the fold still reads at 16px.
        val flap = Path2D.Float().apply {
            moveTo(left, top)
            lineTo(left + width / 2f, top + height * 0.62f)
            lineTo(left + width, top)
            closePath()
        }
        g.paint = envelopeShade
        g.fill(flap)
        g.paint = brandStart
        g.stroke = BasicStroke((s * 0.03f).coerceAtLeast(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(flap)

        // Unread accent dot — the brand's signature mark.
        if (size >= 32) {
            val dot = s * 0.18f
            g.paint = accent
            g.fill(Ellipse2D.Float(s * 0.64f, s * 0.14f, dot, dot))
        }

        g.dispose()

        return ByteArrayOutputStream().use { out ->
            check(ImageIO.write(image, "png", out)) { "No PNG writer available in this JVM" }
            out.toByteArray()
        }
    }

    /** Apple `.icns` container; entries embed PNG payloads (supported since OS X 10.7). */
    fun icns(pngBySize: Map<Int, ByteArray>): ByteArray {
        val typeBySize = mapOf(128 to "ic07", 256 to "ic08", 512 to "ic09", 1024 to "ic10")
        val body = ByteArrayOutputStream()
        pngBySize.toSortedMap().forEach { (size, png) ->
            val type = typeBySize[size]
                ?: error("No ICNS entry type for ${size}px; use one of ${typeBySize.keys}")
            body.write(type.toByteArray(Charsets.US_ASCII))
            body.write(intBE(png.size + 8)) // length includes the 8-byte entry header
            body.write(png)
        }
        return ByteArrayOutputStream().use { out ->
            out.write("icns".toByteArray(Charsets.US_ASCII))
            out.write(intBE(body.size() + 8))
            out.write(body.toByteArray())
            out.toByteArray()
        }
    }

    /** Windows `.ico` container with PNG-compressed entries (Vista and newer). */
    fun ico(pngBySize: Map<Int, ByteArray>): ByteArray {
        val entries = pngBySize.toSortedMap()
        require(entries.isNotEmpty()) { "An .ico file needs at least one image" }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 1, 0)) // reserved, type = 1 (icon)
        out.write(shortLE(entries.size))

        var offset = ICO_HEADER_BYTES + entries.size * ICO_ENTRY_BYTES
        entries.forEach { (size, png) ->
            val dimension = if (size >= 256) 0 else size // 0 encodes 256 in the ICO format
            out.write(dimension)
            out.write(dimension)
            out.write(0) // palette entries (0 = no palette)
            out.write(0) // reserved
            out.write(shortLE(1)) // colour planes
            out.write(shortLE(32)) // bits per pixel
            out.write(intLE(png.size))
            out.write(intLE(offset))
            offset += png.size
        }
        entries.forEach { (_, png) -> out.write(png) }
        return out.toByteArray()
    }

    private const val ICO_HEADER_BYTES = 6
    private const val ICO_ENTRY_BYTES = 16

    private fun intBE(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun intLE(value: Int) = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun shortLE(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())
}
