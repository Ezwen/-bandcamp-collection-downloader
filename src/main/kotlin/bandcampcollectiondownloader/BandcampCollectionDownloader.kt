package bandcampcollectiondownloader

import com.google.gson.Gson
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.zeroturnaround.zip.ZipUtil
import retrieveCookiesFromFile
import retrieveFirefoxCookies
import java.lang.Thread.sleep
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern

data class ParsedFanpageData(
        val fan_data: FanData,
        val collection_data: CollectionData
)

data class FanData(
        val fan_id: String
)

data class CollectionData(
        val batch_size: Int,
        val item_count: Int,
        val last_token: String,
        val redownload_urls: Map<String, String>
)

data class ParsedCollectionItems(
        val more_available: Boolean,
        val last_token: String,
        val redownload_urls: Map<String, String>
)

data class ParsedBandcampData(
        @Suppress("ArrayInDataClass") val digital_items: Array<DigitalItem>
)

data class DigitalItem(
        val downloads: Map<String, Map<String, String>>,
        val package_release_date: String,
        val title: String,
        val artist: String,
        val download_type: String,
        val art_id: String
)

data class ParsedStatDownload(
        val download_url: String
)


/**
 * Core function called from the main
 */
fun downloadAll(cookiesFile: Path?, bandcampUser: String, downloadFormat: String, downloadFolder: Path, retries: Int, timeout: Int, stopAtFirstExistingAlbum: Boolean) {
    val gson = Gson()
    val cookies =

            if (cookiesFile != null) {
                // Parse JSON cookies (obtained with "Cookie Quick Manager" Firefox addon)
                println("Loading provided cookies file: $cookiesFile")
                retrieveCookiesFromFile(cookiesFile, gson)
            } else {
                // Try to find cookies stored in default firefox profile
                println("No provided cookies file, using Firefox cookies.")
                retrieveFirefoxCookies()
            }

    // Get collection page with cookies, hence with download links
    val doc = try {
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
    println("""Found collection page: "${doc.title()}"""")

    // Get download pages
    val fanPageBlob = getDataBlobFromFanPage(doc, gson)
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
            println("Requesting collection_items API older than token $lastToken")
            val theRest = try {
                Jsoup.connect("https://bandcamp.com/api/fancollection/1/collection_items")
                        .ignoreContentType(true)
                        .timeout(timeout)
                        .cookies(cookies)
                        .requestBody("{\"fan_id\": $fanId, \"older_than_token\": \"$lastToken\"}")
                        .post()
            } catch (e: HttpStatusException) {
                throw e
            }

            val parsedCollectionData = gson.fromJson(theRest.wholeText(), ParsedCollectionItems::class.java)
            collection.putAll(parsedCollectionData.redownload_urls)

            lastToken = parsedCollectionData.last_token
            moreAvailable = parsedCollectionData.more_available
        }
    }

    val cacheFile = downloadFolder.resolve("bandcamp-collection-downloader.cache")
    val cache = loadCache(cacheFile).toMutableList()

    // For each download page
    for ((saleItemId, redownloadUrl) in collection) {
        // less Bandcamp-intensive way of checking already downloaded things
        if (saleItemId in cache) {
            println("Sale Item ID $saleItemId is already downloaded; skipping")
            continue
        }

        val downloadPageJsonParsed = getDataBlobFromDownloadPage(redownloadUrl, cookies, gson, timeout)

        // Extract data from blob
        val digitalItem = downloadPageJsonParsed.digital_items[0]
        var albumtitle = digitalItem.title
        var artist = digitalItem.artist
        val releaseDate = digitalItem.package_release_date
        val releaseYear = releaseDate.subSequence(7, 11)
        val isSingleTrack: Boolean = digitalItem.download_type == "t"
        val url = digitalItem.downloads[downloadFormat]?.get("url").orEmpty()
        val artid = digitalItem.art_id

        // Replace invalid chars by similar unicode chars
        albumtitle = replaceInvalidCharsByUnicode(albumtitle)
        artist = replaceInvalidCharsByUnicode(artist)

        // Prepare artist and album folder
        val albumFolderName = "$releaseYear - $albumtitle"
        val artistFolderPath = Paths.get("$downloadFolder").resolve(artist)
        val albumFolderPath = artistFolderPath.resolve(albumFolderName)

        // Download album, with as many retries as configured
        var downloaded = false
        val attempts = retries + 1
        for (i in 1..attempts) {
            if (i > 1) {
                println("Retrying download (${i - 1}/$retries).")
                sleep(1000)
            }
            try {
                downloaded = downloadAlbum(artistFolderPath, albumFolderPath, albumtitle, url, cookies, gson, isSingleTrack, artid, timeout)
                if (saleItemId !in cache) {
                    cache.add(saleItemId)
                    addToCache(cacheFile, saleItemId)
                }
                break
            } catch (e: Throwable) {
                println("""Error while downloading: "${e.javaClass.name}: ${e.message}".""")
                if (i == attempts) {
                    throw BandCampDownloaderError("Could not download album after $retries retries.")
                }
            }
        }

        if (!downloaded && stopAtFirstExistingAlbum) {
            println("Stopping the process since one album pre-exists in the download folder.")
            break
        }
    }
}


class BandCampDownloaderError(s: String) : Exception(s)

fun loadCache(path: Path) : List<String> {
    if (!path.toFile().exists()) {
        return emptyList()
    }

    return path.toFile().readLines()
}

fun addToCache(path: Path, line: String) {
    if (!Files.exists(path)) {
        Files.createFile(path)
    }
    path.toFile().appendText(line + "\n")
}

fun downloadAlbum(artistFolderPath: Path?, albumFolderPath: Path, albumtitle: String, url: String, cookies: Map<String, String>, gson: Gson, isSingleTrack: Boolean, artid: String, timeout: Int) : Boolean {
    // If the artist folder does not exist, we create it
    if (!Files.exists(artistFolderPath)) {
        Files.createDirectories(artistFolderPath)
    }

    // If the album folder does not exist, we create it
    if (!Files.exists(albumFolderPath)) {
        Files.createDirectories(albumFolderPath)
    }

    // If the folder is empty, or if it only contains the zip.part file, we proceed
    val amountFiles = albumFolderPath.toFile().listFiles().size
    if (amountFiles < 2) {

        val outputFilePath: Path = prepareDownload(albumtitle, url, cookies, gson, albumFolderPath, timeout)

        // If this is a zip, we unzip
        if (!isSingleTrack) {

            // Unzip
            try {
                ZipUtil.unpack(outputFilePath.toFile(), albumFolderPath.toFile())
            } finally {
                // Delete zip
                Files.delete(outputFilePath)
            }
        }

        // Else if this is a single track, we just fetch the cover
        else {
            val coverURL = "https://f4.bcbits.com/img/a${artid}_10"
            println("Downloading cover ($coverURL)...")
            downloadFile(coverURL, albumFolderPath, "cover.jpg", timeout)
        }

        println("done.")
        return true

    } else {
        println("Album $albumtitle already done, skipping")
        return false
    }
}

fun getDataBlobFromFanPage(doc: Document, gson: Gson): ParsedFanpageData {
    println("Analyzing fan page")

    // Get data blob
    val downloadPageJson = doc.select("#pagedata").attr("data-blob")
    return gson.fromJson(downloadPageJson, ParsedFanpageData::class.java)
}

fun getDataBlobFromDownloadPage(downloadPageURL: String?, cookies: Map<String, String>, gson: Gson, timeout: Int): ParsedBandcampData {
    println("Analyzing download page $downloadPageURL")

    // Get page content
    val downloadPage = Jsoup.connect(downloadPageURL)
            .cookies(cookies)
            .timeout(timeout).get()

    // Get data blob
    val downloadPageJson = downloadPage.select("#pagedata").attr("data-blob")
    return gson.fromJson(downloadPageJson, ParsedBandcampData::class.java)
}

fun prepareDownload(albumtitle: String, url: String, cookies: Map<String, String>, gson: Gson, albumFolderPath: Path, timeout: Int): Path {
    println("Preparing download of $albumtitle ($url)...")

    val random = Random()

    // Construct statdownload request URL
    val statdownloadURL: String = url
            .replace("/download/", "/statdownload/")
            .replace("http", "https") + "&.vrs=1" + "&.rand=" + random.nextInt()

    // Get statdownload JSON
    println("Getting download link ($statdownloadURL)")
    val statedownloadUglyBody: String = Jsoup.connect(statdownloadURL)
            .cookies(cookies)
            .timeout(timeout)
            .get().body().select("body")[0].text().toString()

    val prefixPattern = Pattern.compile("""if\s*\(\s*window\.Downloads\s*\)\s*\{\s*Downloads\.statResult\s*\(\s*""")
    val suffixPattern = Pattern.compile("""\s*\)\s*};""")
    val statdownloadJSON: String =
            prefixPattern.matcher(
                    suffixPattern.matcher(statedownloadUglyBody)
                            .replaceAll("")
            ).replaceAll("")

    // Parse statdownload JSON and get real download URL, and retrieve url
    val statdownloadParsed: ParsedStatDownload = gson.fromJson(statdownloadJSON, ParsedStatDownload::class.java)
    val realDownloadURL = statdownloadParsed.download_url

    println("Downloading $albumtitle ($realDownloadURL)")

    // Download content
    return downloadFile(realDownloadURL, albumFolderPath, timeout = timeout)
}
