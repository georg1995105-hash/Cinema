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

    @Test fun tabletDefaultsGrowWithDisplayWidth() {
        assertEquals(5, CinemaDevices.defaultColumns(CinemaDevice.TABLET, 600))
        assertEquals(6, CinemaDevices.defaultColumns(CinemaDevice.TABLET, 800))
        assertEquals(7, CinemaDevices.defaultColumns(CinemaDevice.TABLET, 1024))
        assertEquals(3, CinemaDevices.defaultColumns(CinemaDevice.PHONE, 360))
    }

    @Test fun gridIsReadableOnEachScreenClass() {
        assertEquals(2, CinemaDevices.columns(CinemaDevice.PHONE, 5, 360))
        assertEquals(5, CinemaDevices.columns(CinemaDevice.TABLET, 5, 600))
        assertEquals(6, CinemaDevices.columns(CinemaDevice.TABLET, 6, 800))
        assertEquals(7, CinemaDevices.columns(CinemaDevice.TABLET, 7, 1200))
        assertEquals(5, CinemaDevices.columns(CinemaDevice.TABLET, 3, 600))
        assertEquals(5, CinemaDevices.columns(CinemaDevice.TV, 3, 960))
        assertEquals(6, CinemaDevices.columns(CinemaDevice.TV, 7, 960))
        assertEquals(7, CinemaDevices.columns(CinemaDevice.TV, 7, 1400))
    }
}
