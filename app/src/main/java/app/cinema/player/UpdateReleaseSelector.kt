package app.cinema.player

import org.json.JSONArray

/** The in-app updater only accepts newer releases from this repository. */
internal object UpdateReleaseSelector {
    private const val REPO = "https://github.com/georg1995105-hash/Cinema/releases/download/"
    private val supported = Regex("""\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\d+)?""",
        RegexOption.IGNORE_CASE)

    fun isTestBuild(version: String): Boolean =
        version.contains(Regex("""-(?:alpha|beta|rc)\d+$""", RegexOption.IGNORE_CASE))

    fun select(releases: JSONArray, installed: String, includeTestBuilds: Boolean): ReleaseApk? {
        val candidates = mutableListOf<ReleaseApk>()
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            if (release.optBoolean("prerelease") && !includeTestBuilds) continue
            val version = release.optString("tag_name").removePrefix("v")
            if (!supported.matches(version) || !AppVersionComparator.isNewer(version, installed)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val url = asset.optString("browser_download_url")
                val name = asset.optString("name")
                // Reject arbitrary APKs and unrelated GitHub release assets.
                if (name != "Cinema-" + version + ".apk" ||
                    !url.startsWith(REPO + "v" + version + "/")) continue
                val digestText = asset.optString("digest").removePrefix("sha256:")
                val digest = digestText.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                candidates.add(ReleaseApk(version, url, asset.optLong("size"), digest))
            }
        }
        return candidates.maxWithOrNull { a, b ->
            AppVersionComparator.compare(a.version, b.version) ?: 0
        }
    }
}
