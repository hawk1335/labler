package io.github.toolicious.labler.ui.fonts

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.toolicious.labler.App
import io.github.toolicious.labler.data.CustomFontRepository.AddResult
import kotlinx.coroutines.launch

class FontsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as App).container.customFonts

    val fonts = repo.fonts

    /** Imports sequentially so that one bad file in a multi-selection does not stop the rest. */
    fun add(uris: List<Uri>, onDone: (List<AddResult>) -> Unit) {
        viewModelScope.launch { onDone(uris.map { repo.add(it) }) }
    }

    fun replaceFile(family: String, uri: Uri, onDone: (AddResult) -> Unit) {
        viewModelScope.launch { onDone(repo.replaceFile(family, uri)) }
    }

    fun rename(family: String, displayName: String) {
        viewModelScope.launch { repo.rename(family, displayName) }
    }

    fun remove(family: String) {
        viewModelScope.launch { repo.remove(family) }
    }
}
