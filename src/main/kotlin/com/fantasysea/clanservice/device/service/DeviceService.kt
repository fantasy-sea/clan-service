package com.fantasysea.clanservice.device.service

import com.fantasysea.clanservice.device.dto.DeviceResponse
import com.fantasysea.clanservice.device.dto.RegisterDeviceRequest
import com.fantasysea.clanservice.device.dto.RegisterDeviceResponse
import com.fantasysea.clanservice.device.dto.UpdateDeviceRequest

interface DeviceService {

    fun register(request: RegisterDeviceRequest): RegisterDeviceResponse

    fun getDevice(deviceId: String): DeviceResponse

    fun updateDevice(deviceId: String, request: UpdateDeviceRequest): DeviceResponse

    fun deleteDevice(deviceId: String)

    // 以下三个方法供后续 WebSocket 在线状态 Phase 使用（后端设计 §57）。
    fun markOnline(deviceId: String)

    fun markOffline(deviceId: String)

    fun updateLastSeen(deviceId: String)
}
