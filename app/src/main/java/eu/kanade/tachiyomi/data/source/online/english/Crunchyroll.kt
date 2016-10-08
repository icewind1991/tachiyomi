package eu.kanade.tachiyomi.data.source.online.english

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.network.GET
import eu.kanade.tachiyomi.data.network.POST
import eu.kanade.tachiyomi.data.network.asObservable
import eu.kanade.tachiyomi.data.network.newCallWithProgress
import eu.kanade.tachiyomi.data.source.EN
import eu.kanade.tachiyomi.data.source.Language
import eu.kanade.tachiyomi.data.source.model.MangasPage
import eu.kanade.tachiyomi.data.source.model.Page
import eu.kanade.tachiyomi.data.source.online.LoginSource
import eu.kanade.tachiyomi.data.source.online.OnlineSource
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable

class Crunchyroll(context: Context, override val id: Int) : OnlineSource(context), LoginSource {
    private var sessionId: String = ""

    private var auth: String = ""

    override val name = "Crunchyroll"

    override val baseUrl = "http://api-manga.crunchyroll.com"

    override val lang: Language get() = EN

    override fun popularMangaInitialUrl() = "$baseUrl/list_series?device_type=com.crunchyroll.manga.android&api_ver=1.0"

    override val supportsLatest = false

    override fun popularMangaParse(response: Response, page: MangasPage) {
        val data = JSONArray(response.body().string())

        for (i in 0..(data.length() - 1)) {
            val series = data.getJSONObject(i)
            if (series.has("locale")) {
                Manga.create(id).apply {
                    popularMangaFromData(series, this)
                    page.mangas.add(this)
                }
            }
        }
    }

    fun popularMangaFromData(series: JSONObject, manga: Manga) {
        manga.url = "/list_chapters?device_type=com.crunchyroll.manga.android&api_ver=1.0&series_id=" + series.getString("series_id")
        val details = series.getJSONObject("locale").getJSONObject("enUS")
        manga.title = details.getString("name")
        manga.description = details.getString("description")
        manga.artist = series.getString("authors")
        manga.thumbnail_url = details.getString("thumb_url")
    }

    // we already have the details from the initial request
    override fun fetchMangaDetails(manga: Manga): Observable<Manga> = Observable.just(manga)

    override fun searchMangaParse(response: Response, page: MangasPage, query: String, filters: List<Filter>) {
        throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun latestUpdatesInitialUrl(): String {
        throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun latestUpdatesParse(response: Response, page: MangasPage) {
        throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun mangaDetailsParse(response: Response, manga: Manga) {
        // noop
    }

    override fun chapterListParse(response: Response, chapters: MutableList<Chapter>) {
        val data = JSONObject(response.body().string())
        val chaptersData = data.getJSONArray("chapters")

        for (i in 0..(chaptersData.length() - 1)) {
            val chapter = chaptersData.getJSONObject(i)
            Chapter.create().apply {
                chapterFromData(chapter, this)
                chapters.add(this)
            }
        }
        chapters.reverse()
    }

    fun chapterFromData(chapterData: JSONObject, chapter: Chapter) {
        val details = chapterData.getJSONObject("locale").getJSONObject("enUS")
        chapter.url = "/list_chapter?device_type=com.crunchyroll.manga.android&api_ver=1.0&chapter_id=${chapterData.getString("chapter_id")}"
        chapter.name = details.getString("name")
        chapter.chapter_number = chapterData.getString("number").toFloat()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun searchMangaRequest(page: MangasPage, query: String, filters: List<Filter>): Request {
        throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun searchMangaInitialUrl(query: String, filters: List<Filter>) = ""

    override fun fetchPageListFromNetwork(chapter: Chapter): Observable<List<Page>> {
        if (!isLogged()) {
            val username = preferences.sourceUsername(this)
            val password = preferences.sourcePassword(this)

            if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                return Observable.error(Exception("User not logged"))
            } else {
                return login(username, password).flatMap { super.fetchPageListFromNetwork(chapter) }
            }

        } else {
            return super.fetchPageListFromNetwork(chapter)
        }
    }

    override fun pageListRequest(chapter: Chapter): Request {
        return GET(baseUrl + chapter.url + "&session_id=$sessionId&auth=$auth", headers)
    }

    fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> Manga.ONGOING
        status.contains("Completed") -> Manga.COMPLETED
        else -> Manga.UNKNOWN
    }

    override fun pageListParse(response: Response, pages: MutableList<Page>) {
        val data = JSONObject(response.body().string())
        val pagesData = data.getJSONArray("pages")

        for (i in 0..(pagesData.length() - 1)) {
            val pageData = pagesData.getJSONObject(i)
            pages.add(Page(i, "", pageData.getString("image_url")))
        }
    }

    override fun imageResponse(page: Page): Observable<Response> = client
            .newCallWithProgress(imageRequest(page), page)
            .asObservable()
            .map {
                // decode the response body
                val contentType = it.body().contentType()
                val body = ResponseBody.create(contentType, decodeImageBody(it.body().bytes()))
                it.newBuilder().body(body).build()
            }
            .doOnNext {
                if (!it.isSuccessful) {
                    it.close()
                    throw RuntimeException("Not a valid response")
                }
            }

    fun decodeImageBody(input: ByteArray): ByteArray {
        val result = ByteArray(input.size)

        for (i in result.indices) {
            result[i] = (input[i].toInt() xor 66).toByte()
        }

        return result
    }

    fun startSession(): Observable<String> {
        if (!sessionId.isEmpty()) {
            return Observable.just(sessionId)
        }
        val form = FormBody.Builder().apply {
            add("device_type", "com.crunchyroll.manga.android")
            add("api_ver", "1.0")
            add("device_id", "dummy")
            add("access_token", "FLpcfZH4CbW4muO")
        }
        return client.newCall(POST("$baseUrl/cr_start_session", headers, form.build()))
                .asObservable()
                .map {
                    sessionId = JSONObject(it.body().string()).getJSONObject("data").getString("session_id")
                    sessionId
                }
    }

    override fun login(username: String, password: String) = startSession()
            .flatMap { doLogin(username, password, it) }

    fun doLogin(username: String, password: String, sessionId: String): Observable<Boolean> {
        val form = FormBody.Builder().apply {
            add("device_type", "com.crunchyroll.manga.android")
            add("api_ver", "1.0")
            add("hash_id", "dummy")
            add("session_id", sessionId)
            add("account", username)
            add("password", password)
        }
        return client.newCall(POST("$baseUrl/cr_login", headers, form.build()))
                .asObservable()
                .map { isAuthenticationSuccessful(it) }
    }

    override fun isAuthenticationSuccessful(response: Response): Boolean {
        val data = JSONObject(response.body().string())
        val success = data.getBoolean("error") == false
        if (success) {
            auth = data.getJSONObject("data").getString("auth")
            return true
        } else {
            return false
        }
    }

    override fun isLogged(): Boolean = !auth.isEmpty()
}