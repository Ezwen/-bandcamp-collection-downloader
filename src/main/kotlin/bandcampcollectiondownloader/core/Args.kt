package bandcampcollectiondownloader.core

import picocli.CommandLine
import java.nio.file.Path
import java.nio.file.Paths

data class Args(
        @CommandLine.Parameters(arity = "1..1",
                description = ["The bandcamp user account from which all releases must be downloaded."])
        var bandcampUser: String = "",

        @CommandLine.Option(names = ["--cookies-file", "-c"], required = false,
                description = [
                  "A file containing valid bandcamp credential cookies.",
                  """The file must either be obtained using the Firefox extension "Cookie Quick Manager" (https://addons.mozilla.org/en-US/firefox/addon/cookie-quick-manager/) or using the Chrome extension "cookies.txt" (https://chrome.google.com/webstore/detail/cookiestxt/njabckikapfpffapmjgojcnbfjonfjfg).""",
                  "If no cookies file is provided, cookies from the local Firefox installation are used (Windows and Linux)."
                ])
        var pathToCookiesFile: Path? = null,

        @CommandLine.Option(names = ["--audio-format", "-f"], required = false,
                description = ["The chosen audio format of the files to download (default: \${DEFAULT-VALUE}).", "Possible values: flac, wav, aac-hi, mp3-320, aiff-lossless, vorbis, mp3-v0, alac."])
        var audioFormat: String = "vorbis",

        @CommandLine.Option(names = ["--download-folder", "-d"], required = false,
                description = ["The folder in which downloaded releases must be extracted.", "The following structure is considered: <pathToDownloadFolder>/<artist>/<year> - <release>.", "(default: current folder)"])
        var pathToDownloadFolder: Path = Paths.get("."),

        @CommandLine.Option(names = ["-h", "--help"], usageHelp = true, description = ["Display this help message."])
        var help: Boolean = false,

        @CommandLine.Option(names = ["-r", "--retries"], usageHelp = false, description = ["Amount of retries for each HTTP connection (default: 3)."])
        var retries: Int = 3,

        @CommandLine.Option(names = ["-t", "--timeout"], usageHelp = false, description = ["Timeout in ms before giving up an HTTP connection (default: 50000)."])
        var timeout: Int = 50000,

        @CommandLine.Option(names = ["-e", "--skip-failed-releases"], description = ["Skip releases that fail to download after the specified number of retries."])
        var ignoreFailedReleases: Boolean = false,

        @CommandLine.Option(names = ["-j", "--jobs"], usageHelp = false, description = ["Amount of parallel jobs (threads) to use (default: 4)."])
        var jobs: Int = 4,

        @CommandLine.Option(names = ["-v", "--version"], versionHelp = true, description = ["Display the version and exits."])
        var version: Boolean = false,
        
        @CommandLine.Option(names = ["-ft", "--filter-title"], usageHelp = false, description = ["Only downloads releases matching the specified title."])
        var filterTitle: String? = null,

        @CommandLine.Option(names = ["-fa", "--filter-artist"], usageHelp = false, description = ["Only downloads releases by the specified artist."])
        var filterArtist: String? = null,

        @CommandLine.Option(names = ["-fd", "--filter-date"], usageHelp = false, description = ["Only downloads releases bought after the specified ISO8601 data (eg. 2020-01-20)."])
        var filterDate: String? = null,

        @CommandLine.Option(names = ["-n", "--dry-run"], usageHelp = false, description = ["Perform a trial run with no changes made."])
        var dryRun: Boolean = false
)
