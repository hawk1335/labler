package io.github.toolicious.labler.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import io.github.toolicious.labler.model.SfntName
import io.github.toolicious.labler.render.FontRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * A font the user added. Identified by [family] rather than by a path, so re-adding the same
 * file from anywhere else still matches the templates that reference it.
 */
@Serializable
data class CustomFont(
    /** Family name read from the file. This is the key a template stores, it never changes. */
    val family: String,
    /** Name of the copy inside the app's fonts directory. */
    val fileName: String,
    /** Name of the picked file, shown so two similar fonts can be told apart. */
    val sourceName: String,
    /** Short name chosen by the user, empty to fall back to [family]. */
    val displayName: String = "",
) {
    val label: String get() = displayName.ifEmpty { family }
}

/**
 * Manages the fonts the user added: the copies under `filesDir/fonts`, their metadata, and the
 * Typefaces handed to [FontRegistry].
 *
 * Files are copied on import rather than referenced by URI, so a font stays available after the
 * original was moved or deleted, and no persistable permission has to survive.
 */
class CustomFontRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val json: Json,
    scope: CoroutineScope,
) {

    sealed interface AddResult {
        data class Added(val font: CustomFont) : AddResult
        data class Duplicate(val family: String) : AddResult
        data object Invalid : AddResult
        data object TooLarge : AddResult
        data object Failed : AddResult
    }

    private val dir = File(context.filesDir, "fonts")

    private val _fonts = MutableStateFlow<List<CustomFont>>(emptyList())

    /** Installed fonts, sorted alphabetically by their visible label. */
    val fonts: StateFlow<List<CustomFont>> = _fonts.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /**
     * False until the stored fonts have been read. Callers that report a missing font wait for
     * this, otherwise every custom font would look missing for the moment loading takes.
     */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        // Reading the files takes long enough to be worth keeping off app startup. Until this
        // finishes, templates render their fallback font, exactly as if the font were missing.
        scope.launch { load() }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        try {
            val stored = decode(settings.customFontsJson.first())
            // publish drops whatever no longer loads, so the stored list is pruned to match.
            publish(stored)
            val usable = _fonts.value
            if (usable.size != stored.size) persist(usable)
            removeUnreferencedFiles(usable)
        } finally {
            // Even a failed read has to release the waiters, otherwise every mutation below would
            // block forever and the editor would never report a missing font again.
            _ready.value = true
        }
    }

    /**
     * Mutations read the current list, so they must not run against the empty placeholder while
     * the stored one is still being read. Persisting from that placeholder would drop every font
     * the user already had.
     */
    private suspend fun awaitLoaded() {
        if (!_ready.value) ready.first { it }
    }

    suspend fun add(uri: Uri): AddResult = withContext(Dispatchers.IO) {
        awaitLoaded()
        val bytes = readBytes(uri) ?: return@withContext AddResult.Failed
        if (bytes.size > MAX_FILE_BYTES) return@withContext AddResult.TooLarge
        val family = SfntName.read(bytes)?.identity ?: return@withContext AddResult.Invalid
        if (_fonts.value.any { it.family == family }) return@withContext AddResult.Duplicate(family)

        val sourceName = pickedName(uri) ?: "$family.ttf"
        val file = writeCopy(bytes, extensionOf(sourceName)) ?: return@withContext AddResult.Failed
        // A readable name table does not mean the glyph data is usable, and a font that cannot
        // become a Typeface would sit in the list rendering as the fallback with no explanation.
        if (typefaceOf(file) == null) {
            file.delete()
            return@withContext AddResult.Invalid
        }

        val font = CustomFont(family = family, fileName = file.name, sourceName = sourceName)
        val next = _fonts.value + font
        persist(next)
        publish(next)
        AddResult.Added(font)
    }

    /**
     * Swaps the file behind an existing entry. The [family] key deliberately survives even when
     * the new file names itself differently, because templates are linked to that key and would
     * otherwise lose their font.
     */
    suspend fun replaceFile(family: String, uri: Uri): AddResult = withContext(Dispatchers.IO) {
        awaitLoaded()
        val existing = _fonts.value.find { it.family == family } ?: return@withContext AddResult.Failed
        val bytes = readBytes(uri) ?: return@withContext AddResult.Failed
        if (bytes.size > MAX_FILE_BYTES) return@withContext AddResult.TooLarge
        SfntName.read(bytes) ?: return@withContext AddResult.Invalid

        val sourceName = pickedName(uri) ?: existing.sourceName
        val file = writeCopy(bytes, extensionOf(sourceName)) ?: return@withContext AddResult.Failed
        if (typefaceOf(file) == null) {
            file.delete()
            return@withContext AddResult.Invalid
        }

        val updated = existing.copy(fileName = file.name, sourceName = sourceName)
        val next = _fonts.value.map { if (it.family == family) updated else it }
        persist(next)
        publish(next)
        File(dir, existing.fileName).delete()
        AddResult.Added(updated)
    }

    /** An empty [displayName] restores the family name as the label. */
    suspend fun rename(family: String, displayName: String) = withContext(Dispatchers.IO) {
        awaitLoaded()
        val next = _fonts.value.map {
            if (it.family == family) it.copy(displayName = displayName.trim()) else it
        }
        persist(next)
        publish(next)
    }

    /**
     * Removes the entry and its copy. Templates keep their reference and fall back to a built-in
     * font, so adding the same font again makes them correct once more.
     */
    suspend fun remove(family: String) = withContext(Dispatchers.IO) {
        awaitLoaded()
        val existing = _fonts.value.find { it.family == family } ?: return@withContext
        val next = _fonts.value.filterNot { it.family == family }
        persist(next)
        publish(next)
        File(dir, existing.fileName).delete()
    }

    private suspend fun publish(list: List<CustomFont>) {
        val sorted = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        val loaded = LinkedHashMap<String, Typeface>()
        // A font that no longer loads is dropped from both, so the list the UI offers and the
        // registry the renderer asks can never disagree. A template referencing it is then
        // reported as missing rather than quietly rendering the fallback.
        val usable = sorted.filter { font ->
            val typeface = typefaceOf(File(dir, font.fileName))
            if (typeface != null) loaded[font.family] = typeface
            typeface != null
        }
        withContext(Dispatchers.Main) { FontRegistry.setCustom(loaded) }
        _fonts.value = usable
    }

    private fun typefaceOf(file: File): Typeface? =
        runCatching { Typeface.createFromFile(file) }.getOrNull()

    private suspend fun persist(list: List<CustomFont>) {
        settings.saveCustomFonts(json.encodeToString(list))
    }

    private fun decode(raw: String): List<CustomFont> =
        runCatching { json.decodeFromString<List<CustomFont>>(raw) }.getOrDefault(emptyList())

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                // One byte past the cap is enough for the caller to report the file as too
                // large, without pulling an arbitrarily big pick into memory.
                if (out.size() > MAX_FILE_BYTES) break
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun pickedName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase().takeIf { it in FONT_EXTENSIONS } ?: "ttf"

    /** Writes to a temporary name first, so an interrupted copy never becomes a half font. */
    private fun writeCopy(bytes: ByteArray, extension: String): File? = runCatching {
        dir.mkdirs()
        val target = File(dir, "${UUID.randomUUID()}.$extension")
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (temp.renameTo(target)) {
            target
        } else {
            temp.delete()
            null
        }
    }.getOrNull()

    /** Cleans up copies of removed fonts and leftovers of interrupted writes. */
    private fun removeUnreferencedFiles(list: List<CustomFont>) {
        val keep = list.mapTo(HashSet()) { it.fileName }
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private companion object {
        // Generous, because CJK families and .ttc collections routinely run past 16 MB.
        const val MAX_FILE_BYTES = 32 * 1024 * 1024
        val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")
    }
}
