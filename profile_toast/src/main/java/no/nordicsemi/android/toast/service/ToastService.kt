/*
 * Copyright (c) 2022, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.toast.service

import android.annotation.SuppressLint
import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattServices
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionStateWithStatus
import no.nordicsemi.android.kotlin.ble.profile.battery.BatteryLevelParser
import no.nordicsemi.android.toast.service.obj.TemperatureDataParser
import no.nordicsemi.android.service.DEVICE_DATA
import no.nordicsemi.android.service.NotificationService
import no.nordicsemi.android.toast.service.obj.TargetTempDataParser
import java.util.*
import javax.inject.Inject

val Toast_SERVICE_UUID: UUID = UUID.fromString("00001523-1212-8eee-1523-70a5770a5700")
private val TOAST_TEMPERATURE_CHARACTERISTIC_UUID = UUID.fromString("00001525-1212-8eee-1523-70a5770a5700")

private val TOAST_POWER_CHARACTERISTIC_UUID = UUID.fromString("00001524-1212-8eee-1523-70a5770a5700")

private val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
private val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

private val TOAST_TARGET_TEMP_CHARACTERISTIC_UUID = UUID.fromString("00001527-1212-8eee-1523-70a5770a5700")

@SuppressLint("MissingPermission")
@AndroidEntryPoint
internal class ToastService : NotificationService() {

    @Inject
    lateinit var repository: ToastRepository

    private var client: ClientBleGatt? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        repository.setServiceRunning(true)

        val device = IntentCompat.getParcelableExtra(intent!!, DEVICE_DATA, ServerDevice::class.java)!!

        startGattClient(device)

        repository.stopEvent
            .onEach { disconnect() }
            .launchIn(lifecycleScope)

        return START_REDELIVER_INTENT
    }

    private fun startGattClient(device: ServerDevice) = lifecycleScope.launch {
        val client = ClientBleGatt.connect(this@ToastService, device, lifecycleScope, logger = { p, s -> repository.log(p, s) })
        this@ToastService.client = client

        client.waitForBonding()

        client.connectionStateWithStatus
            .onEach { repository.onConnectionStateChanged(it) }
            .filterNotNull()
            .onEach { stopIfDisconnected(it) }
            .launchIn(lifecycleScope)

        if (!client.isConnected) {
            return@launch
        }

        try {
            val services = client.discoverServices()
            configureGatt(services)
        } catch (e: Exception) {
            repository.onMissingServices()
        }
    }

    private suspend fun configureGatt(services: ClientBleGattServices) {
        val toastService = services.findService(Toast_SERVICE_UUID)!!
        val toastMeasurementCharacteristic = toastService.findCharacteristic(TOAST_TEMPERATURE_CHARACTERISTIC_UUID)!!
        val toastTargetTempCharacteristic = toastService.findCharacteristic(TOAST_TARGET_TEMP_CHARACTERISTIC_UUID)!!

        toastMeasurementCharacteristic.getNotifications()
            .mapNotNull { TemperatureDataParser.parse(it) }
            .onEach { repository.onTemperatureDataChanged(it) }
            .catch { it.printStackTrace() }
            .launchIn(lifecycleScope)

        toastTargetTempCharacteristic.getNotifications()
            .mapNotNull { TargetTempDataParser.parse(it) }
            .onEach { repository.onTargetTempDataChanged(it) }
            .catch { it.printStackTrace() }
            .launchIn(lifecycleScope)

        // Battery service is optional
        services.findService(BATTERY_SERVICE_UUID)
            ?.findCharacteristic(BATTERY_LEVEL_CHARACTERISTIC_UUID)
            ?.getNotifications()
            ?.mapNotNull { BatteryLevelParser.parse(it) }
            ?.onEach { repository.onBatteryLevelChanged(it) }
            ?.catch { it.printStackTrace() }
            ?.launchIn(lifecycleScope)
    }

    private fun stopIfDisconnected(connectionState: GattConnectionStateWithStatus) {
        if (connectionState.state == GattConnectionState.STATE_DISCONNECTED) {
            stopSelf()
        }
    }

    private fun disconnect() {
        client?.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.setServiceRunning(false)
    }
}
