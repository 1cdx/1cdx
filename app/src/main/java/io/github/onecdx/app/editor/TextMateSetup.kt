package io.github.onecdx.app.editor

import android.content.Context
import io.github.onecdx.app.domain.LanguageInfo
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Memuat tema Dark+ dan grammar TextMate dari assets sekali saja, di background,
 * supaya pembukaan aplikasi tidak tertahan parsing JSON grammar.
 */
object TextMateSetup {
    private const val THEME_NAME = "dark_plus"
    private const val THEME_PATH = "textmate/dark_plus.json"
    private const val LANGUAGES_PATH = "textmate/languages.json"

    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val assets = context.applicationContext.assets
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
                val themes = ThemeRegistry.getInstance()
                val source = IThemeSource.fromInputStream(
                    FileProviderRegistry.getInstance().tryGetInputStream(THEME_PATH), THEME_PATH, null,
                )
                themes.loadTheme(ThemeModel(source, THEME_NAME).apply { isDark = true })
                themes.setTheme(THEME_NAME)
                GrammarRegistry.getInstance().loadGrammars(LANGUAGES_PATH)
                ready.complete(Unit)
            } catch (t: Throwable) {
                ready.completeExceptionally(t)
            }
        }
    }

    /** Menunggu sampai siap. Melempar exception jika aset TextMate gagal dimuat. */
    suspend fun awaitReady() = ready.await()

    fun createColorScheme(): EditorColorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())

    /** Bahasa baru untuk setiap pergantian tab: CodeEditor menghancurkan bahasa lama saat diganti. */
    fun createLanguage(info: LanguageInfo): Language {
        val scope = info.scopeName ?: return EmptyLanguage()
        return try {
            TextMateLanguage.create(scope, true)
        } catch (e: Exception) {
            EmptyLanguage()
        }
    }
}
