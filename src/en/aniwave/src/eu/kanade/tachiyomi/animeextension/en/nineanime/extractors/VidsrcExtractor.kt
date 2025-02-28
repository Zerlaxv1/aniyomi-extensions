package eu.kanade.tachiyomi.animeextension.en.nineanime.extractors

import android.util.Base64
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animeextension.en.nineanime.MediaResponseBody
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class VidsrcExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val cacheControl = CacheControl.Builder().noStore().build()
    private val noCacheClient = client.newBuilder()
        .cache(null)
        .build()

    private val keys by lazy {
        noCacheClient.newCall(
            GET("https://raw.githubusercontent.com/Claudemirovsky/worstsource-keys/keys/keys.json", cache = cacheControl),
        ).execute().parseAs<List<String>>()
    }

    fun videosFromUrl(embedLink: String, name: String, type: String): List<Video> {
        val hosterName = when (name) {
            "vidplay" -> "VidPlay"
            else -> "MyCloud"
        }
        val host = embedLink.toHttpUrl().host
        val apiUrl = getApiUrl(embedLink, keys)

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Host", host)
            add("Referer", URLDecoder.decode(embedLink, "UTF-8"))
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val response = client.newCall(
            GET(apiUrl, apiHeaders),
        ).execute()

        val data = runCatching {
            response.parseAs<MediaResponseBody>()
        }.getOrElse { // Keys are out of date
            val newKeys = noCacheClient.newCall(
                GET("https://raw.githubusercontent.com/Claudemirovsky/worstsource-keys/keys/keys.json", cache = cacheControl),
            ).execute().parseAs<List<String>>()
            val newApiUrL = getApiUrl(embedLink, newKeys)
            client.newCall(
                GET(newApiUrL, apiHeaders),
            ).execute().parseAs()
        }

        return playlistUtils.extractFromHls(
            data.result.sources.first().file,
            referer = "https://$host/",
            videoNameGen = { q -> "$hosterName - $type - $q" },
            subtitleList = data.result.tracks.toTracks(),
        )
    }

    private fun getApiUrl(embedLink: String, keyList: List<String>): String {
        val host = embedLink.toHttpUrl().host
        val params = embedLink.toHttpUrl().let { url ->
            url.queryParameterNames.map {
                Pair(it, url.queryParameter(it) ?: "")
            }
        }
        val vidId = embedLink.substringAfterLast("/").substringBefore("?")
        val encodedID = encodeID(vidId, keyList)
        val apiSlug = callFromFuToken(host, encodedID)

        return buildString {
            append("https://")
            append(host)
            append("/")
            append(apiSlug)
            if (params.isNotEmpty()) {
                append("?")
                append(
                    params.joinToString("&") {
                        "${it.first}=${it.second}"
                    },
                )
            }
        }
    }

    private fun encodeID(videoID: String, keyList: List<String>): String {
        val rc4Key1 = SecretKeySpec(keyList[0].toByteArray(), "RC4")
        val rc4Key2 = SecretKeySpec(keyList[1].toByteArray(), "RC4")
        val cipher1 = Cipher.getInstance("RC4")
        val cipher2 = Cipher.getInstance("RC4")
        cipher1.init(Cipher.DECRYPT_MODE, rc4Key1, cipher1.parameters)
        cipher2.init(Cipher.DECRYPT_MODE, rc4Key2, cipher2.parameters)
        var encoded = videoID.toByteArray()

        encoded = cipher1.doFinal(encoded)
        encoded = cipher2.doFinal(encoded)
        encoded = Base64.encode(encoded, Base64.DEFAULT)
        return encoded.toString(Charsets.UTF_8).replace("/", "_").trim()
    }

    private fun callFromFuToken(host: String, data: String): String {
        val fuTokenScript = client.newCall(
            GET("https://$host/futoken"),
        ).execute().use { it.body.string() }

        val js = buildString {
            append("(function")
            append(
                fuTokenScript.substringAfter("window")
                    .substringAfter("function")
                    .replace("jQuery.ajax(", "")
                    .substringBefore("+location.search"),
            )
            append("}(\"$data\"))")
        }

        return QuickJs.create().use {
            it.evaluate(js)?.toString()!!
        }
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }

    private fun List<MediaResponseBody.Result.SubTrack>.toTracks(): List<Track> {
        return filter {
            it.kind == "captions"
        }.mapNotNull {
            runCatching {
                Track(
                    it.file,
                    it.label,
                )
            }.getOrNull()
        }
    }
}
