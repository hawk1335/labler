package io.github.toolicious.labler.model

/**
 * Minimal reader for the `name` table of an sfnt font file (TrueType, OpenType, and
 * font collections).
 *
 * Custom fonts are referenced in templates by the family name stored inside the file
 * rather than by a path, so the very same font added again from a different location
 * still matches an existing template. Parsing doubles as validation: a file this reader
 * does not understand is not a font we can install.
 *
 * The parser is deliberately defensive because it reads user-supplied files. Every offset
 * is bounds-checked and any inconsistency yields null instead of an exception.
 */
object SfntName {

    private const val TAG_NAME = 0x6E616D65L // "name"

    private const val SFNT_TRUETYPE = 0x00010000L
    private const val SFNT_CFF = 0x4F54544FL // "OTTO"
    private const val SFNT_TRUE = 0x74727565L // "true"
    private const val SFNT_COLLECTION = 0x74746366L // "ttcf"

    // Name IDs as defined by the OpenType specification.
    private const val ID_FAMILY = 1
    private const val ID_SUBFAMILY = 2
    private const val ID_TYPOGRAPHIC_FAMILY = 16
    private const val ID_TYPOGRAPHIC_SUBFAMILY = 17

    private const val LANG_EN_US = 0x0409

    private val WHITESPACE = Regex("\\s+")

    data class Names(val family: String, val subfamily: String) {
        /**
         * The key a template stores. "Regular" carries no information and is dropped, so
         * Roboto-Regular.ttf becomes "Roboto" while Roboto-Bold.ttf becomes "Roboto Bold".
         * Several weights of one family therefore stay distinguishable, and the result is
         * derived purely from the file, which is what lets a template find its font again
         * on another device.
         */
        val identity: String =
            if (subfamily.isEmpty() || subfamily.equals("Regular", ignoreCase = true)) family
            else "$family $subfamily"
    }

    /** Returns the names of the (first) font in [bytes], or null if this is not a usable font. */
    fun read(bytes: ByteArray): Names? {
        val base = fontOffset(bytes) ?: return null
        val nameOffset = tableOffset(bytes, base) ?: return null
        val records = nameRecords(bytes, nameOffset) ?: return null

        // The two naming schemes must be taken as a pair. Mixing them would combine the
        // typographic family "Open Sans" with the legacy subfamily "Regular" on a file whose
        // legacy pair reads "Open Sans Light" / "Regular", collapsing every weight of that
        // family onto one key so only the first of them could ever be installed.
        val typographic = pick(bytes, records, ID_TYPOGRAPHIC_FAMILY)?.let { family ->
            pick(bytes, records, ID_TYPOGRAPHIC_SUBFAMILY)?.let { Names(family, it) }
        }
        return typographic ?: pick(bytes, records, ID_FAMILY)?.let { family ->
            Names(family, pick(bytes, records, ID_SUBFAMILY) ?: "")
        }
    }

    /** Start of the offset table: 0 for a plain font, the first entry for a "ttcf" collection. */
    private fun fontOffset(b: ByteArray): Int? {
        val tag = b.u32(0) ?: return null
        if (tag != SFNT_COLLECTION) return if (tag.isSfntVersion()) 0 else null

        val numFonts = b.u32(8) ?: return null
        if (numFonts < 1) return null
        val first = b.u32(12) ?: return null
        if (first > Int.MAX_VALUE) return null
        val offset = first.toInt()
        val inner = b.u32(offset) ?: return null
        return if (inner.isSfntVersion()) offset else null
    }

    private fun Long.isSfntVersion() = this == SFNT_TRUETYPE || this == SFNT_CFF || this == SFNT_TRUE

    /** Absolute offset of the `name` table, looked up in the offset table at [base]. */
    private fun tableOffset(b: ByteArray, base: Int): Int? {
        val numTables = b.u16(base + 4) ?: return null
        for (i in 0 until numTables) {
            val record = base + 12 + i * 16
            if (b.u32(record) != TAG_NAME) continue
            val offset = b.u32(record + 8) ?: return null
            val length = b.u32(record + 12) ?: return null
            // A name table shorter than its own header cannot hold a single record.
            if (length < 6 || offset + length > b.size) return null
            return offset.toInt()
        }
        return null
    }

    private class Record(
        val platformId: Int,
        val languageId: Int,
        val nameId: Int,
        val length: Int,
        val start: Int,
    )

    private fun nameRecords(b: ByteArray, nameOffset: Int): List<Record>? {
        val count = b.u16(nameOffset + 2) ?: return null
        val stringOffset = b.u16(nameOffset + 4) ?: return null
        val storage = nameOffset + stringOffset

        val records = ArrayList<Record>(count)
        for (i in 0 until count) {
            val r = nameOffset + 6 + i * 12
            val platformId = b.u16(r) ?: return null
            val languageId = b.u16(r + 4) ?: return null
            val nameId = b.u16(r + 6) ?: return null
            val length = b.u16(r + 8) ?: return null
            val offset = b.u16(r + 10) ?: return null
            // One broken record must not disqualify the whole file, the others may still
            // carry the family name.
            if (storage + offset + length > b.size) continue
            records += Record(platformId, languageId, nameId, length, storage + offset)
        }
        return records.ifEmpty { null }
    }

    /** Windows/en-US first, then any Windows record, then Unicode, then Macintosh. */
    private fun rank(r: Record): Int = when {
        r.platformId == 3 && r.languageId == LANG_EN_US -> 0
        r.platformId == 3 -> 1
        r.platformId == 0 -> 2
        r.platformId == 1 && r.languageId == 0 -> 3
        r.platformId == 1 -> 4
        else -> 5
    }

    private fun pick(b: ByteArray, records: List<Record>, nameId: Int): String? =
        records.filter { it.nameId == nameId }
            .minByOrNull { rank(it) }
            ?.let { decode(b, it) }
            ?.takeIf { it.isNotEmpty() }

    private fun decode(b: ByteArray, r: Record): String {
        // Platform 3 (Windows) and 0 (Unicode) store UTF-16BE. Platform 1 (Macintosh) stores
        // MacRoman, and since font names are practically always ASCII, ISO-8859-1 is a close
        // enough stand-in that never throws on an unmappable byte.
        val charset = if (r.platformId == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE
        return String(b, r.start, r.length, charset)
            .filter { it.code != 0 } // some files pad their strings with NUL
            .replace(WHITESPACE, " ")
            .trim()
    }

    private fun ByteArray.u16(i: Int): Int? {
        if (i < 0 || i + 2 > size) return null
        return ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)
    }

    private fun ByteArray.u32(i: Int): Long? {
        val high = u16(i) ?: return null
        val low = u16(i + 2) ?: return null
        return (high.toLong() shl 16) or low.toLong()
    }
}
