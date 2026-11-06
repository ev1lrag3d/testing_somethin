package echo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import echo.desktop.core.YouTubeSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun accent() = Color(("FF" + Settings.accent).toLong(16))

/** Like the phone app: a Material You style palette grown from one accent colour (default rose #ED5564). */
private fun colors(dark: Boolean): ColorScheme {
    val a = accent()
    val bg = lerp(if (dark) Color(0xFF151011) else Color(0xFFFFF8F7), a, if (dark) 0.04f else 0.03f)
    fun tint(t: Float) = lerp(bg, a, t)
    return if (dark)
        darkColorScheme(
            primary = lerp(a, Color.White, 0.4f), onPrimary = lerp(a, Color.Black, 0.8f),
            primaryContainer = tint(0.4f), onPrimaryContainer = Color(0xFFFFE9EA),
            background = bg, surface = bg, onSurface = Color(0xFFECE0E0),
            surfaceVariant = tint(0.14f), onSurfaceVariant = Color(0xFFC9B8B9),
            surfaceContainerLow = tint(0.05f), surfaceContainerHigh = if (Settings.glass) Color(0x22FFFFFF) else tint(0.1f),
        )
    else
        lightColorScheme(
            primary = lerp(a, Color.Black, 0.2f), onPrimary = Color.White,
            primaryContainer = tint(0.25f), onPrimaryContainer = Color(0xFF2B0A0F),
            background = bg, surface = bg, onSurface = Color(0xFF201A1A),
            surfaceVariant = tint(0.1f), onSurfaceVariant = Color(0xFF534343),
            surfaceContainerLow = tint(0.04f), surfaceContainerHigh = if (Settings.glass) Color(0x14000000) else tint(0.09f),
        )
}

enum class View { Home, Search, Liked, Detail, History, Stats, Settings, Community, Artist }

fun main() = application {
    val player = remember { AudioCache.clear(); VlcPlayer() } // leftovers from a crash
    Window(
        onCloseRequest = {
            player.release()
            AudioCache.clear() // songs are temporary: nothing stays on the PC after closing
            exitApplication()
        },
        title = "Echo Music (Desktop) v${System.getProperty("echomusic.version") ?: "dev"}",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        val dark = when (Settings.themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
        MaterialTheme(colorScheme = colors(dark)) {
            val cs = MaterialTheme.colorScheme
            val bg =
                if (Settings.glass) Modifier.background(Brush.linearGradient(listOf(lerp(cs.background, accent(), if (dark) 0.45f else 0.35f), cs.background)))
                else Modifier.background(cs.background)
            Surface(modifier = Modifier.fillMaxSize().then(bg), color = Color.Transparent) { EchoApp(player) }
        }
    }
}

@Composable
fun EchoApp(player: VlcPlayer) {
    val scope = rememberCoroutineScope()
    val playback = remember { Playback(player, scope) }
    val state by player.state.collectAsState()

    var view by remember { mutableStateOf(View.Home) }
    var volume by remember { mutableStateOf(0.8f) }
    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<LyricLine>?>(null) }

    var query by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var searchStatus by remember { mutableStateOf("") }

    var detailStatus by remember { mutableStateOf("") }
    var detailSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }

    val feed = remember { Feed(playback) }
    val community = remember { Community() }
    var artistPage by remember { mutableStateOf<com.music.innertube.pages.ArtistPage?>(null) }
    var artistStatus by remember { mutableStateOf("") }
    var artistHits by remember { mutableStateOf<List<com.music.innertube.models.ArtistItem>>(emptyList()) }

    fun openArtist(id: String?) {
        if (id == null) return
        view = View.Artist
        artistPage = null
        artistStatus = "Loading artist..."
        scope.launch {
            withContext(Dispatchers.IO) { YouTubeSetup.ensureInit(); YouTube.artist(id) }
                .onSuccess { artistPage = it }
                .onFailure { artistStatus = "Could not open artist: ${it.message ?: it::class.simpleName}" }
        }
    }

    LaunchedEffect(Unit) {
        if (Settings.cookie.isNotBlank()) YouTube.cookie = Settings.cookie
        community.checkReleases()
    }
    // A finished track starts the next one (respecting shuffle and repeat).
    LaunchedEffect(state.ended) { if (state.ended > 0) playback.next(auto = true) }
    LaunchedEffect(volume) { player.setVolume((volume * 100).toInt()) }
    LaunchedEffect(playback.current?.id) {
        lyrics = null
        val song = playback.current ?: return@LaunchedEffect
        lyrics = withContext(Dispatchers.IO) { Lyrics.fetch(song) }.getOrDefault(emptyList())
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        view = View.Search
        searchStatus = "Searching..."
        scope.launch {
            withContext(Dispatchers.IO) {
                    YouTubeSetup.ensureInit()
                    YouTube.search(q, YouTube.SearchFilter.FILTER_SONG)
                }
                .onSuccess {
                    songs = it.items.filterIsInstance<SongItem>()
                    searchStatus = if (songs.isEmpty()) "No songs found." else "Results for \"$q\""
                }
                .onFailure { searchStatus = "Search failed: ${it.message ?: it::class.simpleName}" }
            artistHits =
                withContext(Dispatchers.IO) { YouTube.search(q, YouTube.SearchFilter.FILTER_ARTIST) }
                    .getOrNull()?.items?.filterIsInstance<com.music.innertube.models.ArtistItem>()?.take(6).orEmpty()
        }
    }

    fun open(item: YTItem) {
        view = View.Detail
        detailStatus = "Loading ${item.title}..."
        detailSongs = emptyList()
        scope.launch {
            withContext(Dispatchers.IO) {
                    YouTubeSetup.ensureInit()
                    when (item) {
                        is AlbumItem -> YouTube.album(item.browseId).map { it.songs }
                        is PlaylistItem -> YouTube.playlist(item.id).map { it.songs }
                        else -> Result.success(emptyList<SongItem>())
                    }
                }
                .onSuccess {
                    detailSongs = it
                    detailStatus = item.title
                }
                .onFailure { detailStatus = "Could not open: ${it.message ?: it::class.simpleName}" }
        }
    }

    var showPlayer by remember { mutableStateOf(false) }
    var focusTick by remember { mutableStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    androidx.compose.runtime.CompositionLocalProvider(LocalOpenArtist provides { showPlayer = false; openArtist(it) }) {
        Box(
            Modifier.fillMaxSize().focusRequester(rootFocus).focusable().onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val len = state.lengthMs
                when {
                    e.isCtrlPressed && e.key == Key.F || e.key == Key.Slash -> { view = View.Search; focusTick++; true }
                    e.key == Key.Escape -> { if (showPlayer) showPlayer = false else { focusManager.clearFocus(); rootFocus.requestFocus() }; true }
                    e.key == Key.Spacebar || e.key == Key.MediaPlayPause -> { player.togglePause(); true }
                    e.isCtrlPressed && e.key == Key.DirectionRight || e.key == Key.MediaNext || e.key == Key.N -> { playback.next(); true }
                    e.isCtrlPressed && e.key == Key.DirectionLeft || e.key == Key.MediaPrevious || e.key == Key.P -> { playback.previous(); true }
                    e.key == Key.DirectionRight -> { player.seekTo((state.positionMs + 5000).coerceAtMost(len)); true }
                    e.key == Key.DirectionLeft -> { player.seekTo((state.positionMs - 5000).coerceAtLeast(0)); true }
                    e.key == Key.DirectionUp -> { volume = (volume + 0.05f).coerceAtMost(1f); true }
                    e.key == Key.DirectionDown -> { volume = (volume - 0.05f).coerceAtLeast(0f); true }
                    e.key == Key.L -> { playback.current?.let { playback.toggleLike(it) }; true }
                    e.key == Key.S -> { playback.shuffle = !playback.shuffle; true }
                    e.key == Key.R -> { playback.repeat = when (playback.repeat) { Repeat.Off -> Repeat.All; Repeat.All -> Repeat.One; Repeat.One -> Repeat.Off }; true }
                    e.key == Key.Enter && playback.current != null -> { showPlayer = !showPlayer; true }
                    e.isCtrlPressed && e.key == Key.One -> { view = View.Home; true }
                    e.isCtrlPressed && e.key == Key.Two -> { view = View.Search; focusTick++; true }
                    e.isCtrlPressed && e.key == Key.Three -> { view = View.Liked; true }
                    e.isCtrlPressed && e.key == Key.Four -> { view = View.Community; true }
                    else -> false
                }
            }
        ) {
            LaunchedEffect(Unit) { rootFocus.requestFocus() }
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
                when (view) {
                    View.Home -> FeedHome(feed, { list, i -> playback.play(list, i) }, { view = View.Community })
                    View.Search ->
                        Column {
                            GlassSearchField(query, { query = it }, focusTick) { search() }
                            SearchResults(searchStatus, songs, artistHits) { playback.play(songs, it) }
                        }
                    View.Liked -> SearchResults("Liked songs (${playback.liked.size})", playback.liked) { playback.play(playback.liked, it) }
                    View.Detail -> SearchResults(detailStatus, detailSongs) { playback.play(detailSongs, it) }
                    View.History ->
                        SearchResults("History (${playback.history.size} plays)", playback.history.take(200)) { playback.play(playback.history, it) }
                    View.Community -> CommunityScreen(community)
                    View.Artist ->
                        ArtistScreen(
                            artistPage, artistStatus, community,
                            onFollow = { f -> scope.launch { artistPage?.let { if (f) community.follow(it) else community.unfollow(it.artist.id) } } },
                            onPlay = { list, i -> playback.play(list, i) },
                            onOpen = { open(it) },
                        )
                    View.Stats -> StatsScreen(playback.history, playback.plays)
                    View.Settings ->
                        SettingsScreen(playback) { title, list ->
                            detailStatus = title
                            detailSongs = list
                            view = View.Detail
                        }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniPlayer(playback, state, player) { showPlayer = true }
                NavPill(view) { view = it; if (it == View.Search) focusTick++ }
            }
            if (showPlayer && playback.current != null) {
                FullPlayer(playback, state, player, lyrics, volume, { volume = it }) { showPlayer = false }
            }
        }
    }
}
