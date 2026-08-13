package io.github.toolicious.labler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The fixtures are synthetic sfnt byte arrays built right here, so the test needs no font
 * binary on disk and every field it depends on is visible in this file.
 */
class SfntNameTest {

    @Test
    fun `regular subfamily is dropped from the identity`() {
        val names = SfntName.read(font(entry(1, "Roboto"), entry(2, "Regular")))
        assertEquals("Roboto", names?.family)
        assertEquals("Roboto", names?.identity)
    }

    @Test
    fun `a real subfamily is appended so weights stay distinguishable`() {
        val names = SfntName.read(font(entry(1, "Roboto"), entry(2, "Bold")))
        assertEquals("Roboto Bold", names?.identity)
    }

    @Test
    fun `a missing subfamily leaves the family alone`() {
        assertEquals("Pacifico", SfntName.read(font(entry(1, "Pacifico")))?.identity)
    }

    @Test
    fun `typographic names win over the legacy ones`() {
        // A weight-specific file names itself "Roboto Condensed Light" typographically while
        // the legacy pair says "Roboto Condensed Light" / "Regular" for old applications.
        val bytes = font(
            entry(1, "Roboto Condensed Light"),
            entry(2, "Regular"),
            entry(16, "Roboto Condensed"),
            entry(17, "Light"),
        )
        assertEquals("Roboto Condensed Light", SfntName.read(bytes)?.identity)
    }

    @Test
    fun `windows english is preferred over other windows languages`() {
        val bytes = font(
            entry(1, "Schriftart", languageId = 0x0407),
            entry(1, "Typeface", languageId = 0x0409),
        )
        assertEquals("Typeface", SfntName.read(bytes)?.identity)
    }

    @Test
    fun `macintosh records are read when no windows record exists`() {
        val bytes = font(entry(1, "Old Mac Font", platformId = 1, encodingId = 0, languageId = 0))
        assertEquals("Old Mac Font", SfntName.read(bytes)?.identity)
    }

    @Test
    fun `surrounding and repeated whitespace is normalized`() {
        assertEquals("Wide Load", SfntName.read(font(entry(1, "  Wide   Load ")))?.identity)
    }

    @Test
    fun `opentype cff signature is accepted`() {
        val bytes = font(entry(1, "Cffish"), sfntVersion = 0x4F54544FL)
        assertEquals("Cffish", SfntName.read(bytes)?.identity)
    }

    @Test
    fun `a collection resolves to its first font`() {
        assertEquals("Collected", SfntName.read(collection(entry(1, "Collected")))?.identity)
    }

    @Test
    fun `non font input yields null`() {
        assertNull(SfntName.read(ByteArray(0)))
        assertNull(SfntName.read("just some text, definitely not a font".toByteArray()))
        assertNull(SfntName.read(ByteArray(512) { (it * 7).toByte() }))
    }

    @Test
    fun `a truncated font yields null instead of throwing`() {
        val full = font(entry(1, "Roboto"), entry(2, "Bold"))
        for (cut in 1 until full.size) {
            assertNull("expected null for a font cut to $cut bytes", SfntName.read(full.copyOf(cut)))
        }
    }

    @Test
    fun `a font without a name table yields null`() {
        assertNull(SfntName.read(font(entry(1, "Roboto"), tableTag = "glyf")))
    }

    // ---- fixtures ----------------------------------------------------------------

    private class Entry(
        val nameId: Int,
        val text: String,
        val platformId: Int,
        val encodingId: Int,
        val languageId: Int,
    )

    private fun entry(
        nameId: Int,
        text: String,
        platformId: Int = 3,
        encodingId: Int = 1,
        languageId: Int = 0x0409,
    ) = Entry(nameId, text, platformId, encodingId, languageId)

    /** One sfnt font holding a single table, by default the `name` table. */
    private fun font(
        vararg entries: Entry,
        sfntVersion: Long = 0x00010000L,
        tableTag: String = "name",
        base: Int = 0,
    ): ByteArray {
        val strings = ByteArrayOutputStream()
        val records = ByteArrayOutputStream()
        entries.forEach { e ->
            val text = e.text.toByteArray(if (e.platformId == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE)
            records.write(be16(e.platformId))
            records.write(be16(e.encodingId))
            records.write(be16(e.languageId))
            records.write(be16(e.nameId))
            records.write(be16(text.size))
            records.write(be16(strings.size()))
            strings.write(text)
        }

        val table = ByteArrayOutputStream()
        table.write(be16(0)) // format 0
        table.write(be16(entries.size))
        table.write(be16(6 + entries.size * 12)) // string storage, relative to the table start
        table.write(records.toByteArray())
        table.write(strings.toByteArray())

        val tableOffset = base + OFFSET_TABLE_BYTES + TABLE_RECORD_BYTES
        val out = ByteArrayOutputStream()
        out.write(be32(sfntVersion))
        out.write(be16(1)) // numTables
        out.write(be16(16)) // searchRange
        out.write(be16(0)) // entrySelector
        out.write(be16(0)) // rangeShift
        out.write(tableTag.toByteArray(Charsets.US_ASCII))
        out.write(be32(0)) // checksum, unused by the reader
        out.write(be32(tableOffset.toLong()))
        out.write(be32(table.size().toLong()))
        out.write(table.toByteArray())
        return out.toByteArray()
    }

    /** A "ttcf" collection wrapping exactly one font, whose table offsets shift by the header. */
    private fun collection(vararg entries: Entry): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ttcf".toByteArray(Charsets.US_ASCII))
        out.write(be32(0x00010000L)) // version 1.0
        out.write(be32(1)) // numFonts
        out.write(be32(COLLECTION_HEADER_BYTES.toLong()))
        out.write(font(*entries, base = COLLECTION_HEADER_BYTES))
        return out.toByteArray()
    }

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun be32(v: Long) =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private companion object {
        const val OFFSET_TABLE_BYTES = 12
        const val TABLE_RECORD_BYTES = 16
        const val COLLECTION_HEADER_BYTES = 16
    }
}
