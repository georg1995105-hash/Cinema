package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class AppVersionComparatorTest {
    @Test fun stableReleaseCannotDowngradeInstalledReleaseCandidate() {
        assertFalse(AppVersionComparator.isNewer("0.4.36", "0.4.42-rc1"))
        assertFalse(AppVersionComparator.isNewer("0.4.41", "0.4.42-rc1"))
    }

    @Test fun newerStableReleaseIsOffered() {
        assertTrue(AppVersionComparator.isNewer("0.4.43", "0.4.42-rc1"))
        assertTrue(AppVersionComparator.isNewer("0.4.42", "0.4.42-rc1"))
        assertTrue(AppVersionComparator.isNewer("0.5.0", "0.4.42-rc1"))
    }

    @Test fun releaseCandidateOrderingWorks() {
        assertTrue(AppVersionComparator.isNewer("0.4.42-rc2", "0.4.42-rc1"))
        assertTrue(AppVersionComparator.isNewer("0.4.42-rc1", "0.4.42-beta3"))
        assertFalse(AppVersionComparator.isNewer("0.4.42-rc1", "0.4.42"))
        assertFalse(AppVersionComparator.isNewer("0.4.42-rc1", "0.4.42-rc1"))
    }

    @Test fun malformedVersionCannotEnableUnsafeUpdate() {
        assertFalse(AppVersionComparator.isNewer("0.4.43", "unknown"))
        assertFalse(AppVersionComparator.isNewer("bad", "0.4.42-rc1"))
        assertFalse(AppVersionComparator.isNewer("0.4.42-rc", "0.4.42-rc1"))
        assertNull(AppVersionComparator.compare("0.4.43", "unknown"))
    }

    @Test fun stableVersionsCompareNumerically() {
        assertTrue(AppVersionComparator.isNewer("0.4.100", "0.4.99"))
        assertFalse(AppVersionComparator.isNewer("0.4.9", "0.4.36"))
        assertTrue(AppVersionComparator.isNewer("v1.0.0", "0.9.99"))
    }
}
