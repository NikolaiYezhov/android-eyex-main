package com.eyex.app.ble

object TestHelper {
    private var mockMode = false
    fun enableMockMode() { mockMode = true }
    fun disableMockMode() { mockMode = false }
    fun isMockMode(): Boolean = mockMode
}
