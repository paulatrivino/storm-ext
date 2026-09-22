package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPage
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Collections

/**
 * Serialized data stored in each [SearchResponse] of the provider.
 * The title is the localized one (es-MX), which is the term used by the
 * top-bar search button (QuickSearch) to look for sources across the rest
 * of the installed providers.
 */
data class CatalogoData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

data class TmdbCatalogoResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
) {
    val isTv get() = name != null || mediaType == "tv"
    val displayTitle get() = title ?: originalTitle ?: name ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbCatalogoPageResult(
    @JsonProperty("results") val results: List<TmdbCatalogoResult>? = null,
)

data class TmdbCatalogoDetails(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("genres") val genres: List<TmdbCatalogoGenre>? = null,
) {
    val displayTitle get() = title ?: name ?: originalTitle ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbCatalogoGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class CrossMetaData(
    @JsonProperty("isSuccess") val isSuccess: Boolean,
    @JsonProperty("sources") val sources: List<CrossSource>? = null,
)

data class CrossSource(
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("dataUrl") val dataUrl: String,
)

/**
 * Catalogo Infantil: meta provider for discovering children's and family
 * content (Mexican AA classification). On load, it searches other installed
 * providers (same language) for the title and, if found, delegates playback
 * to them. Movies get a Play button via CrossMetaData; TV series get the
 * matched provider's episodes list. If no match is found, the detail shows
 * metadata only (comingSoon) with a search button via QuickSearch.
 */
class CatalogoInfantilProvider : MainAPI() {
    override var name = "Catálogo Infantil"
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "mx"
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
    )
    override val hasMainPage = true

    private fun filterName(name: String): String {
        return Regex("""[^a-zA-Z0-9-]""").replace(name, "")
    }

    // Many providers append a year to the displayed title (e.g. "Deseo (2026)").
    // Strip a trailing year before comparing so those names still match.
    private fun comparableName(name: String): String {
        return filterName(Regex("""\s*\(?\d{4}\)?\s*$""").replace(name, ""))
    }

    private val validApis
        get() = apis.filter { it.lang == this.lang && it::class != this::class }

    // Resolving a title searches the other installed providers. CloudStream
    // calls load() for the home page preview (hero) row, so a slow load()
    // stalls the whole main page. All providers are therefore searched in
    // parallel and the whole search is capped by a short global deadline: the
    // worst case is a few seconds instead of one round of up to 10s per batch
    // of providers. The slowest providers are cut off individually, and only
    // COMPLETE results are cached per title so a truncated search is not
    // memoized as "no match" (a real match would stay hidden for the whole
    // cache window). A repeat load() is instant.
    private val providerSearchTimeoutMs = 5_000L
    private val findMatchesDeadlineMs = 6_000L
    private val maxMatchesForFallback = 4

    // Short-lived cache for TMDB responses so that reloading the home page
    // (CloudStream refetches every section at once) is instant instead of
    // hammering TMDB with many parallel requests.
    private val tmdbCacheMs = 15L * 60 * 1000
    private data class TmdbCacheEntry(val timestamp: Long, val body: String)
    private val tmdbCache = Collections.synchronizedMap(HashMap<String, TmdbCacheEntry>())

    // Short-lived cache for the provider-search outcome per title, so the search
    // is only paid for the first time a title is opened (a second open of the
    // same title, e.g. from another row or after the app's own load cache
    // evicts it, returns instantly).
    private val searchCacheMs = 10L * 60 * 1000
    private data class SearchCacheEntry(val timestamp: Long, val matches: List<SearchMatch>)
    private val searchCache = Collections.synchronizedMap(HashMap<String, SearchCacheEntry>())

    private class SearchMatch(val providerName: String, val loaded: LoadResponse)

    // A match must be of the same media type (movie vs series) as the item
    // being loaded, otherwise a same-named movie could be returned for a
    // series and vice versa.
    private fun SearchResponse.typeOk(isTv: Boolean): Boolean {
        val t = this.type
        return if (isTv) {
            t?.let { it != TvType.Movie && it != TvType.AnimeMovie } ?: (this is TvSeriesSearchResponse)
        } else {
            t?.let { it == TvType.Movie || it == TvType.AnimeMovie } ?: (this is MovieSearchResponse)
        }
    }

    private fun SearchResponse.yearOk(searchYear: Int?): Boolean {
        val y = when (this) {
            is MovieSearchResponse -> this.year
            is TvSeriesSearchResponse -> this.year
            else -> null
        } ?: return true
        return searchYear == null || y == searchYear
    }

    private suspend fun searchProvider(
        api: MainAPI,
        title: String,
        matchName: String,
        searchYear: Int?,
        isTv: Boolean,
    ): SearchMatch? {
        return try {
            withTimeout(providerSearchTimeoutMs) {
                val searchResult = api.search(title, 1) ?: return@withTimeout null
                val candidates = searchResult.items.filter { it.typeOk(isTv) }
                if (candidates.isEmpty()) return@withTimeout null

                // Prefer an exact title + year match; otherwise fall back to a
                // same-title match, since many providers report a different or no
                // year for the same movie (e.g. new releases, regional titles).
                val matched = candidates.firstOrNull {
                    comparableName(it.name).equals(matchName, ignoreCase = true) && it.yearOk(searchYear)
                } ?: candidates.firstOrNull {
                    comparableName(it.name).equals(matchName, ignoreCase = true)
                } ?: return@withTimeout null

                val loaded = api.load(matched.url) ?: return@withTimeout null
                SearchMatch(api.name, loaded)
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private suspend fun findMatches(
        title: String,
        matchName: String,
        searchYear: Int?,
        isTv: Boolean,
    ): List<SearchMatch> {
        val key = "$isTv|$matchName|$searchYear"
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.timestamp < searchCacheMs) return it.matches }

        // All providers are hit in parallel so that the wall-clock time is the
        // slowest provider, not the sum of all of them. The global deadline
        // bounds the whole search (e.g. when the title matches nothing and
        // every provider has to answer first).
        var complete = true
        val matches = withTimeoutOrNull(findMatchesDeadlineMs) {
            validApis.amap { api ->
                searchProvider(api, title, matchName, searchYear, isTv)
            }.filterNotNull()
        } ?: run {
            complete = false
            emptyList()
        }

        val result = matches.take(maxMatchesForFallback)

        // Cache only a complete result: a run cut short by the deadline (some
        // slow provider not yet answered) is not memoized as "no match", so a
        // real match is not hidden for the whole cache window.
        if (complete) {
            searchCache[key] = SearchCacheEntry(now, result)
            if (searchCache.size > 250) {
                searchCache.clear()
            }
        }
        return result
    }

    private val apiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val apiBase = "https://api.themoviedb.org/3"

    // Only the Mexican AA classification (under 7 years old): the exact
    // `certification` filter avoids titles without a registered MX certification
    // and, by not including "A", excludes non-kids content that does have it
    // (e.g. La Momia, Friends, Jesus 1979).
    private val kidsCertifications = listOf("AA")
    private val certificationCountry = "MX"

    // Kids genres for series: Family, Kids and Animation.
    // Comma (,) in TMDB's with_genres means AND: it works for series, where
    // almost all kids content combines the three genres.
    private val tvKidsGenres = "10751,10762,16"

    // For movies pipe (|) = OR is used: almost no movie has all three genres at
    // once (AND returns 0 results). OR + AA discards non-kids AA titles
    // (e.g. documentaries, dramas, concerts).
    private val movieKidsGenres = "10751|10762|16"

    // Minimum votes for movies: discards AA titles with 0-1 votes that are junk
    // or unknown regional releases (e.g. "Maruchan con Huevo").
    // Real kids content (Toy Story, Inside Out) accumulates thousands of votes.
    private val movieMinVotes = 10

    // Per-genre section configuration: the sort order is diversified so that
    // each row shows different content instead of repeating the same popularity.
    private data class GenreSection(val id: Int, val sortBy: String, val minVotes: Boolean)

    private val genreSections = mapOf(
        "animacion" to GenreSection(16, "popularity.desc", false),
        "familia" to GenreSection(10751, "vote_average.desc", true),
        "ninos" to GenreSection(10762, "primary_release_date.desc", false),
        "aventura" to GenreSection(12, "popularity.desc", false),
        "fantasia" to GenreSection(14, "vote_average.desc", true),
        "comedia" to GenreSection(35, "primary_release_date.desc", false),
    )

    // Deduplication across main page sections: titles already shown are not
    // repeated in the following rows. It resets after a time window.
    private val minSectionResults = 12
    private val seenWindowMs = 30L * 60 * 1000
    private val seenMainPageKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private var seenWindowStart = System.currentTimeMillis()

    private fun mainPageKey(result: TmdbCatalogoResult): String =
        "${result.isTv}-${result.id ?: result.displayTitle}"

    private fun dedupeMainPage(raw: List<TmdbCatalogoResult>): List<TmdbCatalogoResult> {
        val now = System.currentTimeMillis()
        if (now - seenWindowStart > seenWindowMs) {
            seenMainPageKeys.clear()
            seenWindowStart = now
        }
        val fresh = mutableListOf<TmdbCatalogoResult>()
        val repeats = mutableListOf<TmdbCatalogoResult>()
        for (result in raw) {
            if (mainPageKey(result) in seenMainPageKeys) {
                repeats.add(result)
            } else {
                seenMainPageKeys.add(mainPageKey(result))
                fresh.add(result)
            }
        }
        // never leave a row almost empty: gaps are filled with repeats
        val fill = (minSectionResults - fresh.size).coerceAtLeast(0)
        return fresh + repeats.take(fill)
    }

    override val mainPage = listOf(
        mainPage("recomendados", "Recomendados", false),
        mainPage("peliculas", "Películas Populares", false),
        mainPage("series", "Series Populares", false),
        mainPage("tendencias", "Tendencias", false),
        mainPage("estrenos", "Estrenos", false),
        mainPage("mejor", "Mejor Calificadas", false),
        mainPage("animacion", "Animación", false),
        mainPage("familia", "Familia", false),
        mainPage("ninos", "Niños", false),
        mainPage("aventura", "Aventura", false),
        mainPage("fantasia", "Fantasía", false),
        mainPage("comedia", "Comedia", false),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Cards are vertical with a portrait (poster) image.
        val horizontal = false

        val raw = when (request.data) {
            "recomendados" ->
                discoverMovies(page, "popularity.desc") +
                        discoverSeries(page, "popularity.desc")

            "peliculas" -> discoverMovies(page, "vote_average.desc", minVotes = true)
            "series" -> discoverSeries(page, "vote_average.desc", minVotes = true)

            "tendencias" ->
                // Fresh releases don't have an MX AA certification registered on
                // TMDB yet, so the certification filter is skipped for this row:
                // applying it to the current-year window returns nothing.
                discoverMovies(page, "popularity.desc", recent = true, applyCert = false) +
                        discoverSeries(page, "popularity.desc", recent = true, applyCert = false)

            "estrenos" ->
                discoverMovies(page, "primary_release_date.desc") +
                        discoverSeries(page, "first_air_date.desc")

            "mejor" ->
                discoverMovies(page, "popularity.desc") +
                        discoverSeries(page, "popularity.desc")

            "animacion", "familia", "ninos", "aventura", "fantasia", "comedia" -> {
                val config = genreSections[request.data] ?: return newHomePageResponse(
                    HomePageList(request.name, emptyList(), false),
                    hasNext = false
                )
                genreMovies(page, config) + genreSeries(page, config)
            }

            else -> emptyList()
        }

        val results = dedupeMainPage(raw).mapNotNull { it.toCatalogoSearchResponse(horizontal) }

        return newHomePageResponse(
            HomePageList(request.name, results, horizontal),
            hasNext = results.isNotEmpty()
        )
    }

    // The provider's own search is deliberately disabled:
    // the catalog only discovers content on the main page. The source search is
    // done from the detail with the magnifier (the app's QuickSearch).
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<CatalogoData>(url) ?: return null
        val id = data.id ?: return null

        val details = if (data.isTv) {
            parseJson<TmdbCatalogoDetails>(tmdbGet("/tv/$id"))
        } else {
            parseJson<TmdbCatalogoDetails>(tmdbGet("/movie/$id"))
        }

        val title = details.displayTitle.takeIf { it.isNotBlank() } ?: return null
        val type = if (data.isTv) TvType.TvSeries else TvType.Movie
        val year = data.year ?: details.year
        val poster = getImageUrl(details.posterPath)
        val backdrop = getImageUrl(details.backdropPath, "w780")
        val score = Score.from10(details.voteAverage)
        val tags = details.genres?.mapNotNull { it.name }

        // Search other providers for this title and collect playable data.
        // All providers are hit in parallel under a short global deadline so
        // that load() stays fast even when many providers are installed —
        // CloudStream calls load() for the home page preview row, so a slow
        // load() stalls the main page.
        val matchName = filterName(title)
        val searchYear = year
        val matches = findMatches(title, matchName, searchYear, data.isTv)

        if (matches.isEmpty()) {
            // No match found in other providers: return metadata-only (comingSoon)
            return if (data.isTv) {
                newTvSeriesLoadResponse(title, url, type, episodes = emptyList()) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                }
            } else {
                newMovieLoadResponse(title, url, type, dataUrl = "") {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                    this.duration = details.runtime?.let { it * 60 }
                }
            }
        }

        // Use the FIRST matched provider's response, overlay TMDB metadata
        val first = matches.first()

        return when (val loaded = first.loaded) {
            is MovieLoadResponse -> {
                // Store all matches in CrossMetaData for loadLinks delegation
                val movieMatches = matches.filter { it.loaded is MovieLoadResponse }
                val crossData = CrossMetaData(
                    true,
                    movieMatches.map { CrossSource(it.providerName, (it.loaded as MovieLoadResponse).dataUrl) }
                ).toJson()
                newMovieLoadResponse(title, url, type, dataUrl = crossData) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                    this.duration = details.runtime?.let { it * 60 }
                }
            }

            is TvSeriesLoadResponse -> {
                // Collect every provider that matched as a series so that when the
                // user plays an episode, loadLinks can fall back across all of
                // them instead of only the first match (a provider may lack
                // working links for an episode while another has them).
                val seriesMatches = matches.filter { it.loaded is TvSeriesLoadResponse }
                val wrappedEpisodes = loaded.episodes.map { ep ->
                    val sources = mutableListOf(CrossSource(first.providerName, ep.data))
                    seriesMatches.forEach { other ->
                        if (other.providerName == first.providerName) return@forEach
                        val otherLoaded = other.loaded as TvSeriesLoadResponse
                        otherLoaded.episodes.firstOrNull {
                            it.season == ep.season && it.episode == ep.episode
                        }?.let { sources.add(CrossSource(other.providerName, it.data)) }
                    }
                    val wrapped = CrossMetaData(true, sources).toJson()
                    @Suppress("DEPRECATION_ERROR")
                    Episode(
                        data = wrapped,
                        name = ep.name,
                        season = ep.season,
                        episode = ep.episode,
                        posterUrl = ep.posterUrl,
                        score = ep.score,
                        description = ep.description,
                        date = ep.date,
                        runTime = ep.runTime,
                    )
                }
                newTvSeriesLoadResponse(title, url, type, episodes = wrappedEpisodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                }
            }

            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            linksFound = true
            callback(link)
        }
        // Try CrossMetaData first (movies — contains list of sources)
        tryParseJson<CrossMetaData>(data)?.let { metaData ->
            if (!metaData.isSuccess) return false
            metaData.sources?.amap { source ->
                getApiFromNameNull(source.apiName)?.let {
                    try {
                        it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            return linksFound
        }
        // Try CrossSource (TV episodes — single source wrapping episode data)
        tryParseJson<CrossSource>(data)?.let { source ->
            getApiFromNameNull(source.apiName)?.let {
                try {
                    it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return linksFound
        }
        return false
    }

    private suspend fun tmdbGet(path: String, params: Map<String, String> = emptyMap()): String {
        val key = "$path${params.toSortedMap()}"
        val now = System.currentTimeMillis()
        tmdbCache[key]?.let { if (now - it.timestamp < tmdbCacheMs) return it.body }

        val body = app.get(
            url = "$apiBase$path",
            params = buildMap {
                put("api_key", apiKey)
                put("language", "es-MX")
                putAll(params)
            },
        ).text
        tmdbCache[key] = TmdbCacheEntry(now, body)
        return body
    }

    private suspend fun discoverMovies(
        page: Int,
        sortBy: String,
        recent: Boolean = false,
        minVotes: Boolean = false,
        applyCert: Boolean = true,
    ): List<TmdbCatalogoResult> {
        return kidsCertifications.flatMap { cert ->
            parseJson<TmdbCatalogoPageResult>(
                tmdbGet(
                    "/discover/movie",
                    buildMap {
                        put("page", "$page")
                        put("sort_by", sortBy)
                        put("with_genres", movieKidsGenres)
                        if (applyCert) {
                            put("certification_country", certificationCountry)
                            put("certification", cert)
                        }
                        if (recent) put("primary_release_date.gte", "${currentYear()}-01-01")
                        put("vote_count.gte", if (minVotes) "150" else "$movieMinVotes")
                    }
                )
            ).results ?: emptyList()
        }
    }

    private suspend fun discoverSeries(
        page: Int,
        sortBy: String,
        recent: Boolean = false,
        minVotes: Boolean = false,
        applyCert: Boolean = true,
    ): List<TmdbCatalogoResult> {
        return kidsCertifications.flatMap { cert ->
            parseJson<TmdbCatalogoPageResult>(
                tmdbGet(
                    "/discover/tv",
                    buildMap {
                        put("page", "$page")
                        put("sort_by", sortBy)
                        put("with_genres", tvKidsGenres)
                        if (applyCert) {
                            put("certification_country", certificationCountry)
                            put("certification", cert)
                        }
                        if (recent) put("first_air_date.gte", "${currentYear()}-01-01")
                        if (minVotes) put("vote_count.gte", "100")
                    }
                )
            ).results ?: emptyList()
        }
    }

    private suspend fun genreMovies(page: Int, config: GenreSection): List<TmdbCatalogoResult> {
        return kidsCertifications.flatMap { cert ->
            parseJson<TmdbCatalogoPageResult>(
                tmdbGet(
                    "/discover/movie",
                    buildMap {
                        put("page", "$page")
                        put("sort_by", config.sortBy)
                        put("with_genres", "${config.id}")
                        put("certification_country", certificationCountry)
                        put("certification", cert)
                        put("vote_count.gte", if (config.minVotes) "150" else "$movieMinVotes")
                    }
                )
            ).results ?: emptyList()
        }
    }

    private suspend fun genreSeries(page: Int, config: GenreSection): List<TmdbCatalogoResult> {
        return kidsCertifications.flatMap { cert ->
            parseJson<TmdbCatalogoPageResult>(
                tmdbGet(
                    "/discover/tv",
                    buildMap {
                        put("page", "$page")
                        put("sort_by", config.sortBy)
                        // TMDB's certification filter does not apply to TV, so the
                        // section requires the row genre PLUS the kids genres (AND)
                        // to exclude unsuitable sitcoms (e.g. Friends, Two and a
                        // Half Men).
                        put("with_genres", "${config.id},10751,10762,16")
                        put("certification_country", certificationCountry)
                        put("certification", cert)
                        if (config.minVotes) put("vote_count.gte", "100")
                    }
                )
            ).results ?: emptyList()
        }
    }

    private fun TmdbCatalogoResult.toCatalogoSearchResponse(
        preferLandscape: Boolean = false
    ): SearchResponse? {
        val display = displayTitle
        if (display.isBlank()) return null
        val id = this.id ?: return null

        // The card name is the localized title (es-MX): avoids showing titles in
        // their original language (e.g. anime in Japanese/Chinese) on the main page
        // and matches what the detail shows. It is also the term used by
        // QuickSearch in the installed mx/es providers.
        val data = CatalogoData(id, isTv, display, year).toJson()

        // Vertical cards use the portrait (poster) image.
        val poster = if (preferLandscape) {
            getImageUrl(backdropPath ?: posterPath, "w780")
        } else {
            getImageUrl(posterPath)
        }

        return if (isTv) {
            newTvSeriesSearchResponse(display, data, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = this@toCatalogoSearchResponse.year
                this.score = Score.from10(voteAverage)
            }
        } else {
            newMovieSearchResponse(display, data, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = this@toCatalogoSearchResponse.year
                this.score = Score.from10(voteAverage)
            }
        }
    }

    private fun getImageUrl(link: String?, size: String = "w500"): String? {
        link ?: return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$size$link" else link
    }

    private fun currentYear(): Int {
        return Calendar.getInstance().get(Calendar.YEAR)
    }
}
