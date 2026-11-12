// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.di

import io.github.jadenyuki4.orbitmusic.data.InMemoryHistoryStore
import io.github.jadenyuki4.orbitmusic.domain.*
import io.github.jadenyuki4.orbitmusic.search.SearchViewModel
import io.github.jadenyuki4.orbitmusic.theme.ThemeViewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Isolated container: no global context or service lookup inside the data layer. */
fun createAppGraph(
    source: MusicSource,
    history: HistoryStore = InMemoryHistoryStore(),
) = koinApplication {
    modules(module {
        single<MusicSource> { source }
        single<HistoryStore> { history }
        factory { ThemeViewModel() }
        factory { SearchViewModel(get(), get()) }
    })
}

/** IDE previews never trigger network traffic. Runtime entry points supply the real source. */
object PreviewMusicSource : MusicSource {
    override suspend fun suggestions(query: String) = SourceResult.Success(emptyList<String>())
    override suspend fun search(query: String, kind: MusicKind?, page: String?, pageSize: Int) =
        SourceResult.Failure(SourceError.Unsupported)
    override suspend fun discover(page: String?, pageSize: Int) = SourceResult.Failure(SourceError.Unsupported)
    override suspend fun stream(songId: String) = SourceResult.Failure(SourceError.Unsupported)
}
