package bandcampcollectiondownloader.core

import bandcampcollectiondownloader.util.Util
import com.google.gson.Gson
import org.jsoup.Connection.Method
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.util.*
import java.util.regex.Pattern

class BandcampAPIConnector constructor(private val bandcampUser: String, private val cookies: Map<String, String>, private val timeout: Int, private val retries: Int) {

    private var bandcampPageName: String? = null
    private val gson = Gson()

    private val saleItemsIDs2saleItemsURLs: MutableMap<String, String> = HashMap<String, String>()
    private val saleItemsIDs2digitalItems: MutableMap<String, DigitalItem?> = HashMap<String, DigitalItem?>()

    private var initialized: Boolean = false

    private data class ParsedFanpageData(
        val fan_data: FanData,
        val collection_data: CollectionData
    )

    private data class FanData(
            val fan_id: String
    )

    private data class CollectionData(
            val batch_size: Int,
            val item_count: Int,
            val last_token: String,
            val redownload_urls: Map<String, String>
    )

    private data class ParsedCollectionItems(
            val more_available: Boolean,
            val last_token: String,
            val redownload_urls: Map<String, String>
    )

    private data class ParsedBandcampData(
            @Suppress("ArrayInDataClass") val digital_items: Array<DigitalItem>
    )

    data class DigitalItem(
            val downloads: Map<String, Map<String, String>>?,
            val package_release_date: String?,
            val title: String,
            val artist: String,
            val download_type: String,
            val art_id: String,
            val release_date: String,
            val sold_date: String
    )

    private data class ParsedStatDownload(
            val download_url: String?,
            val url: String
    )

    fun init() {

        if (!initialized) {

            // Get collection page with cookies, hence with download links
            val doc =
                    Util.retry({
                        try {
                            Jsoup.connect("https://bandcamp.com/$bandcampUser")
                                    .timeout(timeout)
                                    .cookies(cookies)
                                    .get()
                        } catch (e: HttpStatusException) {
                            if (e.statusCode == 404) {
                                throw BandCampDownloaderError("The bandcamp user '$bandcampUser' does not exist.")
                            } else {
                                throw e
                            }
                        }
                    }, retries)
            // Get download pages
            val downloadPageJson = doc!!.select("#pagedata").attr("data-blob")
            val fanPageBlob = gson.fromJson(downloadPageJson, ParsedFanpageData::class.java)

            val collection = fanPageBlob.collection_data.redownload_urls.toMutableMap()

            if (collection.isEmpty()) {
                throw BandCampDownloaderError("No download links could by found in the collection page. This can be caused by an outdated or invalid cookies file.")
            }

            // Get the rest of the collection
            if (fanPageBlob.collection_data.item_count > fanPageBlob.collection_data.batch_size) {
                val fanId = fanPageBlob.fan_data.fan_id
                var lastToken = fanPageBlob.collection_data.last_token
                var moreAvailable = true
                while (moreAvailable) {
                    // Append download pages from this api endpoint as well
                    val theRest =
                            Util.retry({
                                Jsoup.connect("https://bandcamp.com/api/fancollection/1/collection_items")
                                        .ignoreContentType(true)
                                        .timeout(timeout)
                                        .cookies(cookies)
                                        .method(Method.POST)
                                        .requestBody("{\"fan_id\": $fanId, \"older_than_token\": \"$lastToken\"}")
                                        .execute()
                            }, retries)

                    val parsedCollectionData = gson.fromJson(theRest!!.body(), ParsedCollectionItems::class.java)
                    collection.putAll(parsedCollectionData.redownload_urls)

                    lastToken = parsedCollectionData.last_token
                    moreAvailable = parsedCollectionData.more_available
                }
            }

            this.saleItemsIDs2saleItemsURLs.putAll(collection)
            this.bandcampPageName = doc.title()
            this.initialized = true
        }
    }

    fun getBandcampPageName(): String? {
        init()
        return bandcampPageName
    }

    fun getAllSaleItemIDs(): Set<String> {
        init()
        return saleItemsIDs2saleItemsURLs.keys
    }


    fun getCoverURL(saleItemID: String): String {
        init()
        val artid = this.retrieveDigitalItemData(saleItemID)!!.art_id
        return "https://f4.bcbits.com/img/a${artid}_10"
    }

    fun retrieveDigitalItemData(saleItemID: String): DigitalItem? {
        init()

        if (!this.saleItemsIDs2digitalItems.containsKey(saleItemID)) {

            val saleItemURL = this.saleItemsIDs2saleItemsURLs[saleItemID]

            // Get page content
            Util.retry({
                try {
                    val downloadPage = Jsoup.connect(saleItemURL)
                            .cookies(cookies)
                            .timeout(timeout).get()

                    // Get data blob
                    val downloadPageJson = downloadPage.select("#pagedata").attr("data-blob")
                    val data = gson.fromJson(downloadPageJson, ParsedBandcampData::class.java)
                    this.saleItemsIDs2digitalItems[saleItemID] = data.digital_items[0]

                } catch (e: HttpStatusException) {

                    // If 404, then the download page is not usable anymore for some reason (eg. a refund was given for the purchase)
                    if (e.statusCode == 404)
                        this.saleItemsIDs2digitalItems[saleItemID] = null
                    else
                        throw e
                }
            }, retries)

        }

        return this.saleItemsIDs2digitalItems[saleItemID]
    }


    fun retrieveRealDownloadURL(saleItemID: String, audioFormat: String): String? {
        init()

        // Some releases have no digital items (eg. vinyl only) or no downloads or no urls, so we return null in such cases
        val digitalItem = this.retrieveDigitalItemData(saleItemID) ?: return null
        val downloads = digitalItem.downloads ?: return null
        val download = downloads[audioFormat] ?: return null
        val downloadUrl = download["url"] ?: return null

        val random = Random()

        // Construct statdownload request URL
        val statdownloadURL: String = downloadUrl
                .replace("/download/", "/statdownload/")
                .replace("http:", "https:") + "&.vrs=1" + "&.rand=" + random.nextInt()

        // Get statdownload JSON
        val statedownloadUglyBody: String = Util.retry({
            Jsoup.connect(statdownloadURL)
                    .cookies(cookies)
                    .timeout(timeout)
                    .get().body().select("body")[0].text().toString()
        }, retries)!!

        val prefixPattern = Pattern.compile("""if\s*\(\s*window\.Downloads\s*\)\s*\{\s*Downloads\.statResult\s*\(\s*""")
        val suffixPattern = Pattern.compile("""\s*\)\s*};""")
        val statdownloadJSON: String =
                prefixPattern.matcher(
                        suffixPattern.matcher(statedownloadUglyBody)
                                .replaceAll("")
                ).replaceAll("")

        // Parse statdownload JSON

        val statdownloadParsed = gson.fromJson(statdownloadJSON, ParsedStatDownload::class.java)

        return statdownloadParsed.download_url ?: downloadUrl
    }

}
