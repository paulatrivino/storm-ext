package com.stormunblessed

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class EmbedSports : ExtractorApi() {
    override val name = "EmbedSports"
    override val mainUrl = "https://embed.st"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink> {
        val page = app.get(url, referer = referer).text

        val iframeSrc = Regex("""src="(https://embedhd\.st[^"]+)""").find(page)?.groupValues?.get(1)
        if (iframeSrc != null) {
            return extractFromIframeChain(iframeSrc, url)
        }

        return extractFromWebView(url, referer)
    }

    private suspend fun extractFromIframeChain(iframeSrc: String, referer: String): List<ExtractorLink> {
        val hdPage = app.get(iframeSrc, referer = referer).text
        val fid = Regex("""fid\s*=\s*"([^"]+)""").find(hdPage)?.groupValues?.get(1)
            ?: return emptyList()

        val maestroUrl = "https://exposestrat.com/maestrohd1.php?player=desktop&live=$fid"
        val maestroPage = app.get(maestroUrl, referer = iframeSrc).text

        val arrayStart = "return(["
        val joinEnd = "].join(\"\")"
        val startIdx = maestroPage.indexOf(arrayStart)
        val endIdx = maestroPage.indexOf(joinEnd, startIdx)
        if (startIdx == -1 || endIdx == -1) return emptyList()

        val arrayContent = maestroPage.substring(startIdx + arrayStart.length - 1, endIdx + joinEnd.length)
        val chars = Regex("\"([^\"]*)\"").findAll(arrayContent).map { it.groupValues[1] }.toList()
        if (chars.isEmpty()) return emptyList()

        val streamUrl = chars.joinToString("").replace("\\/", "/")

        return listOf(
            newExtractorLink(name, name, streamUrl) {
                this.type = ExtractorLinkType.M3U8
                this.referer = "https://exposestrat.com/"
            }
        )
    }

    private suspend fun extractFromWebView(url: String, referer: String?): List<ExtractorLink> {
        val m3u8Resolver = WebViewResolver(
            interceptUrl = Regex(""".*\.m3u8.*"""),
            additionalUrls = listOf(Regex(""".*\.m3u8.*""")),
            useOkhttp = false,
            timeout = 60_000L
        )
        val response = app.get(url, referer = referer, interceptor = m3u8Resolver)
        val finalUrl = response.url
        if (finalUrl.contains(".m3u8")) {
            return listOf(
                newExtractorLink(name, name, finalUrl) {
                    this.type = ExtractorLinkType.M3U8
                    this.referer = "$mainUrl/"
                }
            )
        }
        return emptyList()
    }
}