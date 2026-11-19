package app.cinema.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseSelectorTest {
    private fun release(version: String, test: Boolean = false): JSONObject {
        val url = "https://github.com/georg1995105-hash/Cinema/releases/download/v" +
            version + "/Cinema-" + version + ".apk"
        return JSONObject()
            .put("tag_name", "v" + version)
            .put("draft", false)
            .put("prerelease", test)
            .put("assets", JSONArray().put(JSONObject()
                .put("name", "Cinema-" + version + ".apk")
                .put("browser_download_url", url)
                .put("size", 75_000_000)))
    }

    @Test fun testInstallReceivesEveryNewRcAndStableUpgrade() {
        val releases = JSONArray()
            .put(release("0.4.44"))
            .put(release("0.4.46-rc1", true))
            .put(release("0.4.45-rc1", true))
        assertEquals("0.4.46-rc1",
            UpdateReleaseSelector.select(releases, "0.4.44-rc1", true)?.version)
        assertTrue(UpdateReleaseSelector.isTestBuild("0.4.44-rc1"))
    }

    @Test fun stableUsersStayStableUnlessTheyEnableTesting() {
        val releases = JSONArray()
            .put(release("0.4.46-rc1", true))
            .put(release("0.4.45"))
        assertEquals("0.4.45",
            UpdateReleaseSelector.select(releases, "0.4.44", false)?.version)
        assertEquals("0.4.46-rc1",
            UpdateReleaseSelector.select(releases, "0.4.44", true)?.version)
    }

    @Test fun downgradeAndSpoofedAssetsNeverTriggerAnUpdate() {
        val spoof = release("0.4.46")
        spoof.getJSONArray("assets").getJSONObject(0)
            .put("browser_download_url", "https://example.com/steal.apk")
        val old = release("0.4.40")
        assertNull(UpdateReleaseSelector.select(JSONArray().put(spoof).put(old),
            "0.4.45-rc1", true))
        assertNull(UpdateReleaseSelector.select(JSONArray().put(release("0.4.44")),
            "0.4.45-rc1", true))
    }

    @Test fun draftIsNotDistributed() {
        val draft = release("0.4.90", true).put("draft", true)
        assertNull(UpdateReleaseSelector.select(JSONArray().put(draft), "0.4.44-rc1", true))
    }
    @Test fun stableUpdateIsVisibleToExistingRcAndStableInstalls() {
        val releases = JSONArray()
            .put(release("0.4.46"))
            .put(release("0.4.45-rc1", true))
        for (current in listOf("0.4.36", "0.4.44-rc1", "0.4.45-rc1", "0.4.46-rc1")) {
            assertEquals("0.4.46",
                UpdateReleaseSelector.select(releases, current, true)?.version)
        }
        assertEquals("0.4.46",
            UpdateReleaseSelector.select(releases, "0.4.36", false)?.version)
        assertNull(UpdateReleaseSelector.select(releases, "0.4.46", true))
    }

}
