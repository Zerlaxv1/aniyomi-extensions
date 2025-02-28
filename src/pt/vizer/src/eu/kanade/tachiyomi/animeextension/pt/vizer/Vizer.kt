package eu.kanade.tachiyomi.animeextension.pt.vizer

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.vizer.VizerFilters.FilterSearchParams
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.EpisodeListDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.PlayersDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.SearchItemDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.SearchResultDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.VideoDto
import eu.kanade.tachiyomi.animeextension.pt.vizer.dto.VideoLanguagesDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Vizer : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Vizer.tv"

    override val baseUrl = "https://vizer.tv"
    private val apiUrl = "$baseUrl/includes/ajax"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val pageType = preferences.getString(PREF_POPULAR_PAGE_KEY, PREF_POPULAR_PAGE_DEFAULT)!!
        val params = FilterSearchParams(
            orderBy = "vzViews",
            orderWay = "desc",
            type = pageType,
        )
        return searchAnimeRequest(page, "", params)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<SearchResultDto>()
        val animes = result.list.map(::animeFromObject)
        val hasNext = result.quantity == 35
        return AnimesPage(animes, hasNext)
    }

    private fun animeFromObject(item: SearchItemDto) = SAnime.create().apply {
        val (urlslug, imgslug) = when {
            item.status.isBlank() -> Pair("filme", "movies")
            else -> Pair("serie", "series")
        }
        url = "/$urlslug/online/${item.url}"
        title = item.title
        status = when (item.status) {
            "Retornando" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
        thumbnail_url = "$baseUrl/content/$imgslug/posterPt/342/${item.id}.webp"
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = apiRequest("getHomeSliderSeries=1")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val parsedData = response.parseAs<SearchResultDto>()
        val animes = parsedData.list.map(::animeFromObject)
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = VizerFilters.FILTER_LIST

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH).replace("/", "/online/")
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map(::searchAnimeByPathParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = VizerFilters.getSearchParameters(filters)
        return searchAnimeRequest(page, query, params)
    }

    private fun searchAnimeRequest(page: Int, query: String, params: FilterSearchParams): Request {
        val urlBuilder = "$apiUrl/ajaxPagination.php".toHttpUrl().newBuilder()
            .addQueryParameter("page", "${page - 1}")
            .addQueryParameter("categoryFilterYearMin", params.minYear)
            .addQueryParameter("categoryFilterYearMax", params.maxYear)
            .addQueryParameter("categoryFilterOrderBy", params.orderBy)
            .addQueryParameter("categoryFilterOrderWay", params.orderWay)
            .apply {
                if (query.isNotBlank()) addQueryParameter("search", query)

                when (params.type) {
                    "Movies" -> {
                        addQueryParameter("saga", "0")
                        addQueryParameter("categoriesListMovies", params.genre)
                    }
                    else -> {
                        addQueryParameter("categoriesListSeries", params.genre)
                        val isAnime = params.type == "anime"
                        addQueryParameter("anime", if (isAnime) "1" else "0")
                    }
                }
            }
        return GET(urlBuilder.build().toString(), headers)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val doc = response.asJsoup()
        setUrlWithoutDomain(doc.location())
        title = doc.selectFirst("section.ai > h2")!!.text()
        thumbnail_url = doc.selectFirst("meta[property=og:image]")!!.attr("content")

        description = buildString {
            append(doc.selectFirst("span.desc")!!.text() + "\n")
            doc.selectFirst("div.year")?.also { append("\nAno: ${it.text()}") }
            doc.selectFirst("div.tm")?.also { append("\nDuração: ${it.text()}") }
            doc.selectFirst("a.rating")?.also { append("\nNota: ${it.text()}") }
        }
    }

    // ============================== Episodes ==============================
    private fun getSeasonEps(seasonElement: Element): List<SEpisode> {
        val id = seasonElement.attr("data-season-id")
        val sname = seasonElement.text()
        val response = client.newCall(apiRequest("getEpisodes=$id")).execute()
        val episodes = response.parseAs<EpisodeListDto>().episodes
            .filter { it.released }
            .map {
                SEpisode.create().apply {
                    name = "Temp $sname: Ep ${it.name} - ${it.title}"
                    episode_number = it.name.toFloatOrNull() ?: 0F
                    url = it.id
                }
            }
        return episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.use { it.asJsoup() }
        val seasons = doc.select("div#seasonsList div.item[data-season-id]")
        return if (seasons.size > 0) {
            seasons.flatMap(::getSeasonEps).reversed()
        } else {
            listOf(
                SEpisode.create().apply {
                    name = "Filme"
                    episode_number = 1F
                    url = response.request.url.toString()
                },
            )
        }
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val url = episode.url
        return if (url.startsWith("https")) {
            // Its an real url, maybe from a movie
            GET(url, headers)
        } else {
            // Fake url, its an ID that will be used to get episode languages
            // (sub/dub) and then return the video link
            apiRequest("getEpisodeLanguages=$url")
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.use { it.body.string() }
        val videoObjectList = if (body.startsWith("{")) {
            json.decodeFromString<VideoLanguagesDto>(body).videos
        } else {
            val videoJson = body.substringAfterLast("videoPlayerBox(").substringBefore(");")
            json.decodeFromString<VideoLanguagesDto>(videoJson).videos
        }

        return videoObjectList.flatMap(::getVideosFromObject)
    }

    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }

    private fun getVideosFromObject(videoObj: VideoDto): List<Video> {
        val players = client.newCall(apiRequest("getVideoPlayers=${videoObj.id}"))
            .execute()
            .parseAs<PlayersDto>()
        val langPrefix = if (videoObj.lang == "1") "LEG" else "DUB"
        val videoList = players.iterator().mapNotNull { (name, status) ->
            if (status == "0") return@mapNotNull null
            val url = getPlayerUrl(videoObj.id, name)
            when (name) {
                "mixdrop" -> mixdropExtractor.videoFromUrl(url, langPrefix)
                "streamtape" -> streamtapeExtractor.videosFromUrl(url, "StreamTape($langPrefix)")
                else -> null
            }
        }.flatten()
        return videoList
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_POPULAR_PAGE_KEY
            title = PREF_POPULAR_PAGE_TITLE
            entries = PREF_POPULAR_PAGE_ENTRIES
            entryValues = PREF_POPULAR_PAGE_VALUES
            setDefaultValue(PREF_POPULAR_PAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = PREF_PLAYER_TITLE
            entries = PREF_PLAYER_ARRAY
            entryValues = PREF_PLAYER_ARRAY
            setDefaultValue(PREF_PLAYER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_ENTRIES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun getPlayerUrl(id: String, name: String): String {
        val req = GET("$baseUrl/embed/getPlay.php?id=$id&sv=$name")
        val body = client.newCall(req).execute().use { it.body.string() }
        return body.substringAfter("location.href=\"").substringBefore("\";")
    }

    private fun apiRequest(body: String): Request {
        val reqBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val newHeaders = headersBuilder().add("x-requested-with", "XMLHttpRequest").build()
        return POST("$apiUrl/publicFunctions.php", newHeaders, body = reqBody)
    }

    override fun List<Video>.sort(): List<Video> {
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(player) },
                { it.quality.contains(language) },
            ),
        ).reversed()
    }

    private inline fun <reified T> Response.parseAs(): T {
        return use { it.body.string() }.let(json::decodeFromString)
    }

    companion object {
        private const val PREF_POPULAR_PAGE_KEY = "pref_popular_page"
        private const val PREF_POPULAR_PAGE_DEFAULT = "movie"
        private const val PREF_POPULAR_PAGE_TITLE = "Página de Populares"
        private val PREF_POPULAR_PAGE_ENTRIES = arrayOf(
            "Animes",
            "Filmes",
            "Séries",
        )
        private val PREF_POPULAR_PAGE_VALUES = arrayOf(
            "anime",
            "movie",
            "serie",
        )

        private const val PREF_PLAYER_KEY = "pref_player"
        private const val PREF_PLAYER_DEFAULT = "MixDrop"
        private const val PREF_PLAYER_TITLE = "Player/Server favorito"
        private val PREF_PLAYER_ARRAY = arrayOf(
            "MixDrop",
            "StreamTape",
        )

        private const val PREF_LANGUAGE_KEY = "pref_language"
        private const val PREF_LANGUAGE_DEFAULT = "LEG"
        private const val PREF_LANGUAGE_TITLE = "Língua/tipo preferido"
        private val PREF_LANGUAGE_ENTRIES = arrayOf("Legendado", "Dublado")
        private val PREF_LANGUAGE_VALUES = arrayOf("LEG", "DUB")

        const val PREFIX_SEARCH = "path:"
    }
}
