package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class CinemaDevicesTest {
    @Test fun detectsPhoneTabletAndTvWithoutFragmentingApk() {
        assertEquals(CinemaDevice.PHONE, CinemaDevices.classify(false, 360))
        assertEquals(CinemaDevice.TABLET, CinemaDevices.classify(false, 600))
        assertEquals(CinemaDevice.TABLET, CinemaDevices.classify(false, 800))
        assertEquals(CinemaDevice.TV, CinemaDevices.classify(true, 360))
    }

    @Test fun gridIsReadableOnEachScreenClass() {
        assertEquals(2, CinemaDevices.columns(CinemaDevice.PHONE, 5, 360))
        assertEquals(3, CinemaDevices.columns(CinemaDevice.TABLET, 5, 600))
        assertEquals(5, CinemaDevices.columns(CinemaDevice.TV, 3, 960))
        assertEquals(6, CinemaDevices.columns(CinemaDevice.TV, 7, 960))
        assertEquals(7, CinemaDevices.columns(CinemaDevice.TV, 7, 1400))
    }
}
