package com.autogram.app.infrastructure.platform

import androidx.car.app.connection.CarConnection
import org.junit.Assert.assertEquals
import org.junit.Test

class CarConnectionMonitorTest {

    @Test fun `unknown provider response preserves cached projection`() {
        assertEquals(
            CarConnection.CONNECTION_TYPE_PROJECTION,
            CarConnectionMonitor.resolveConnectionType(
                queried = null,
                cached = CarConnection.CONNECTION_TYPE_PROJECTION
            )
        )
    }

    @Test fun `explicit provider disconnect overrides cached projection`() {
        assertEquals(
            CarConnection.CONNECTION_TYPE_NOT_CONNECTED,
            CarConnectionMonitor.resolveConnectionType(
                queried = CarConnection.CONNECTION_TYPE_NOT_CONNECTED,
                cached = CarConnection.CONNECTION_TYPE_PROJECTION
            )
        )
    }

    @Test fun `missing provider and cache default to disconnected`() {
        assertEquals(
            CarConnection.CONNECTION_TYPE_NOT_CONNECTED,
            CarConnectionMonitor.resolveConnectionType(queried = null, cached = null)
        )
    }
}
