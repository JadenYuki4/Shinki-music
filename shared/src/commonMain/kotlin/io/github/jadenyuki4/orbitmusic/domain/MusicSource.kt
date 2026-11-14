// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jadenyuki4.orbitmusic.domain

/** Source-neutral models; IDs belong to the source and are not cached audio URLs. */
enum class MusicKind { Song, Album, Artist, Playlist }

object ShinkiAppInfo {
    const val APP_NAME = "Shinki music"
    const val AUTHOR = "JadenYuki4"
    const val EDITION = RELEASE_EDITION
    const val VERSION = RELEASE_VERSION
    const val BUILD_NUMBER = RELEASE_BUILD
    const val PREAMP_DEFAULT_DB = -6.0f
    val EQ_FREQUENCIES_HZ = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val EQ_FREQUENCY_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
}

/**
 * SpotiFLAC service definitions ported from `spotiflac-main/lib/constants/music_services.dart`
 * combined with multi-repository streaming sources.
 */
enum class SpotiFlacServiceInfo(
    val id: String,
    val label: String,
    val colorHex: Long,
    val linkedRepo: MusicRepository,
) {
    Spotify("spotify", "Spotify", 0xFF1DB954, MusicRepository.All),
    Deezer("deezer", "Deezer", 0xFFA238FF, MusicRepository.All),
    Tidal("tidal", "Tidal Hi-Res", 0xFF0077B5, MusicRepository.YouTubeMusic),
    Qobuz("qobuz", "Qobuz 24-bit", 0xFF0052CC, MusicRepository.YouTubeMusic),
    YouTubeMusic("ytmusic", "YT Music", 0xFFFF0033, MusicRepository.YouTubeMusic),
    YouTube("youtube", "YouTube", 0xFFFF453A, MusicRepository.YouTubeVideos),
    SoundCloud("soundcloud", "SoundCloud", 0xFFFF5500, MusicRepository.SoundCloud),
    Bandcamp("bandcamp", "Bandcamp", 0xFF1DA0C3, MusicRepository.Bandcamp),
}

/**
 * SpotiFLAC 10-band graphic equalizer presets ported directly from
 * `spotiflac-main/lib/models/equalizer_settings.dart`.
 */
enum class SpotiFlacEqPreset(
    val id: String,
    val displayName: String,
    val bandsDb: List<Float>,
    val mappedProfile: SoundProfile,
) {
    Flat("flat", "Plano", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), SoundProfile.Flat),
    BassBoost("bass_boost", "Refuerzo de graves", listOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f), SoundProfile.BassBoost),
    TrebleBoost("treble_boost", "Refuerzo de agudos", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 5f), SoundProfile.HiFi),
    Vocal("vocal", "Vocal", listOf(-2f, -2f, -1f, 0f, 2f, 3f, 3f, 2f, 0f, -1f), SoundProfile.AnimeOST),
    Rock("rock", "Rock", listOf(4f, 3f, 1f, -1f, -2f, 0f, 2f, 3f, 4f, 4f), SoundProfile.BassBoost),
    Pop("pop", "Pop", listOf(-1f, 1f, 3f, 4f, 3f, 1f, -1f, -1f, 1f, 2f), SoundProfile.AnimeOST),
    Jazz("jazz", "Jazz", listOf(3f, 2f, 1f, 1f, -1f, -1f, 0f, 1f, 2f, 3f), SoundProfile.HiFi),
    Classical("classical", "Clásica", listOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 1f, 2f, 3f), SoundProfile.HiFi),
    Electronic("electronic", "Electrónica", listOf(5f, 4f, 1f, -1f, -2f, 0f, 1f, 3f, 4f, 4f), SoundProfile.BassBoost),
    Acoustic("acoustic", "Acústica", listOf(3f, 2f, 1f, 2f, 2f, 2f, 3f, 2f, 1f, 2f), SoundProfile.AnimeOST),
}

/**
 * Multi-repository audio sources so songs not listed in the strict YouTube Music catalog
 * (such as anime openings/OSTs, remixes, covers, indie tracks, SoundCloud & Bandcamp releases) are all found.
 */
enum class MusicRepository(
    val label: String,
    val shortLabel: String,
    val badge: String,
    val description: String,
) {
    All(
        "Multi-Fuente (Todos)",
        "✦ Todos",
        "MULTI",
        "Combina YT Music + YouTube + SoundCloud + Bandcamp + Catálogo completo",
    ),
    YouTubeMusic(
        "YouTube Music",
        "♪ YT Music",
        "YT MUSIC",
        "Catálogo oficial de canciones y álbumes de YouTube Music",
    ),
    YouTubeVideos(
        "YouTube Completo (OSTs / MVs)",
        "▶ YouTube",
        "YOUTUBE",
        "Catálogo completo de YouTube: openings de animé, covers, remixes y conciertos",
    ),
    SoundCloud(
        "SoundCloud",
        "SoundCloud",
        "SOUNDCLOUD",
        "Repositorio de pistas independientes, electrónica, lo-fi y remixes",
    ),
    Bandcamp(
        "Bandcamp",
        "◈ Bandcamp",
        "BANDCAMP",
        "Repositorio de álbumes indie, bandas sonoras y lanzamientos de artistas",
    ),
}

enum class AudioQuality(
    val label: String,
    val shortLabel: String,
    val description: String,
    val targetBitrateKbps: Int,
    val approxSizeLabel: String,
    val spotiFlacBadge: String,
    val bitDepthLabel: String,
) {
    High(
        "Hi-Res Lossless / Máxima (24-bit)",
        "FLAC · 24-bit",
        "Mejor calidad de sonido disponible (Hi-Res 24-bit / 48 kHz)",
        160,
        "~1.3 MB/min",
        "FLAC · 24-bit · 48 kHz",
        "24-bit",
    ),
    Medium(
        "CD Lossless / Media (16-bit)",
        "FLAC · 16-bit",
        "Equilibrio entre fidelidad de estudio (16-bit / 44.1 kHz) y peso moderado",
        128,
        "~0.9 MB/min",
        "FLAC · 16-bit · 44.1 kHz",
        "16-bit",
    ),
    Low(
        "Calidad Baja (Ahorro / Ligero)",
        "Baja · Ligero",
        "Menor peso de archivo y bajo consumo de datos móviles",
        64,
        "~0.4 MB/min",
        "OPUS · 160 kbps · Ligero",
        "160k",
    ),
}

enum class AudioFormatPreference(
    val label: String,
    val shortLabel: String,
    val mimeKeyword: String?,
) {
    Auto("FLAC / Auto (Óptimo)", "FLAC/AUTO", null),
    Opus("Opus / WebM (Alta eficiencia)", "OPUS", "webm"),
    M4A("M4A / ALAC (Apple Lossless)", "M4A", "mp4"),
    MP3("MP3 / 320k (Universal)", "MP3", "mpeg"),
}

enum class SoundProfile(
    val label: String,
    val description: String,
) {
    AnimeOST("Vocal / Anime", "Realza voces, cuerdas y claridad instrumental (+3 dB en 1k-2k Hz)"),
    HiFi("Agudos y Hi-Fi", "Claridad extendida en agudos y presencia dinámica (+5 dB en 16k Hz)"),
    BassBoost("Graves potentes", "Refuerzo cálido de subgraves y percusión (+6 dB en 31-62 Hz)"),
    Flat("Plano (Estudio)", "Respuesta plana de estudio sin coloración (0.0 dB en las 10 bandas)"),
}

data class MusicItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: MusicKind,
    val artworkUrl: String? = null,
    val durationSeconds: Long? = null,
    val isrc: String? = null,
    val musicBrainzId: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    val repositoryBadge: String
        get() = when {
            id.contains("soundcloud.com", ignoreCase = true) -> "SOUNDCLOUD"
            id.contains("bandcamp.com", ignoreCase = true) -> "BANDCAMP"
            id.contains("music.youtube.com", ignoreCase = true) -> "YT MUSIC"
            subtitle.endsWith(" - Topic", ignoreCase = true) -> "YT MUSIC"
            id.contains("youtube.com", ignoreCase = true) || id.contains("youtu.be", ignoreCase = true) -> "YOUTUBE"
            else -> "ORBIT"
        }
}

data class MusicPage(val items: List<MusicItem>, val nextPage: String? = null)

data class AudioStream(
    val url: String,
    val mimeType: String,
    val expiresAtEpochSeconds: Long? = null,
    val bitrateKbps: Int? = null,
    val codec: String? = null,
    val formatLabel: String? = null,
    val approxBytesPerMinute: Long? = null,
)

sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val error: SourceError) : SourceResult<Nothing>
}
enum class SourceError { Network, RateLimited, Unavailable, Unsupported, SourceChanged, InvalidRequest, VerificationRequired }

/** Implementations must honor cancellation; a page is capped at [pageSize] results. */
interface MusicSource {
    var repository: MusicRepository
        get() = MusicRepository.All
        set(_) {}

    suspend fun lyrics(item: MusicItem): SourceResult<String?> = SourceResult.Success(null)
    suspend fun related(songId: String): SourceResult<List<MusicItem>> = SourceResult.Success(emptyList())
    suspend fun suggestions(query: String): SourceResult<List<String>>
    suspend fun search(query: String, kind: MusicKind?, page: String? = null,
        pageSize: Int = 30): SourceResult<MusicPage>
    suspend fun discover(page: String? = null, pageSize: Int = 30): SourceResult<MusicPage>
    suspend fun stream(songId: String): SourceResult<AudioStream>
    suspend fun stream(
        songId: String,
        quality: AudioQuality,
        format: AudioFormatPreference = AudioFormatPreference.Auto,
    ): SourceResult<AudioStream> = stream(songId)
}
