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

package no.nordicsemi.android.cgms.repository

import android.annotation.SuppressLint
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.common.data.cgm.CGMSpecificOpsControlPointData
import no.nordicsemi.android.cgms.data.CGMRecordWithSequenceNumber
import no.nordicsemi.android.cgms.data.CGMServiceCommand
import no.nordicsemi.android.common.logger.NordicBlekLogger
import no.nordicsemi.android.kotlin.ble.client.main.callback.BleGattClient
import no.nordicsemi.android.kotlin.ble.client.main.connect
import no.nordicsemi.android.kotlin.ble.client.main.service.BleGattCharacteristic
import no.nordicsemi.android.kotlin.ble.client.main.service.BleGattServices
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionStateWithStatus
import no.nordicsemi.android.kotlin.ble.profile.battery.BatteryLevelParser
import no.nordicsemi.android.kotlin.ble.profile.cgm.CGMFeatureParser
import no.nordicsemi.android.kotlin.ble.profile.cgm.CGMMeasurementParser
import no.nordicsemi.android.kotlin.ble.profile.cgm.CGMSpecificOpsControlPointParser
import no.nordicsemi.android.kotlin.ble.profile.cgm.CGMStatusParser
import no.nordicsemi.android.kotlin.ble.profile.cgm.data.CGMErrorCode
import no.nordicsemi.android.kotlin.ble.profile.cgm.data.CGMOpCode
import no.nordicsemi.android.kotlin.ble.profile.gls.RecordAccessControlPointInputParser
import no.nordicsemi.android.kotlin.ble.profile.gls.RecordAccessControlPointParser
import no.nordicsemi.android.kotlin.ble.profile.gls.data.NumberOfRecordsData
import no.nordicsemi.android.kotlin.ble.profile.gls.data.RecordAccessControlPointData
import no.nordicsemi.android.kotlin.ble.profile.gls.data.RequestStatus
import no.nordicsemi.android.kotlin.ble.profile.gls.data.ResponseData
import no.nordicsemi.android.kotlin.ble.profile.racp.RACPOpCode
import no.nordicsemi.android.kotlin.ble.profile.racp.RACPResponseCode
import no.nordicsemi.android.service.DEVICE_DATA
import no.nordicsemi.android.service.NotificationService
import no.nordicsemi.android.ui.view.StringConst
import no.nordicsemi.android.utils.launchWithCatch
import java.util.*
import javax.inject.Inject

val CGMS_SERVICE_UUID: UUID = UUID.fromString("0000181F-0000-1000-8000-00805f9b34fb")
private val CGM_STATUS_UUID = UUID.fromString("00002AA9-0000-1000-8000-00805f9b34fb")
private val CGM_FEATURE_UUID = UUID.fromString("00002AA8-0000-1000-8000-00805f9b34fb")
private val CGM_MEASUREMENT_UUID = UUID.fromString("00002AA7-0000-1000-8000-00805f9b34fb")
private val CGM_OPS_CONTROL_POINT_UUID = UUID.fromString("00002AAC-0000-1000-8000-00805f9b34fb")

private val RACP_UUID = UUID.fromString("00002A52-0000-1000-8000-00805f9b34fb")

private val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
private val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
@AndroidEntryPoint
internal class CGMService : NotificationService() {

    @Inject
    lateinit var repository: CGMRepository

    @Inject
    lateinit var stringConst: StringConst

    private lateinit var client: BleGattClient

    private var secured = false

    private var recordAccessRequestInProgress = false

    private var sessionStartTime: Long = 0

    private lateinit var recordAccessControlPointCharacteristic: BleGattCharacteristic

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val device = intent!!.getParcelableExtra<ServerDevice>(DEVICE_DATA)!!

        startGattClient(device)

        repository.stopEvent
            .onEach { disconnect() }
            .launchIn(lifecycleScope)

        repository.command
            .onEach { onCommand(it) }
            .launchIn(lifecycleScope)

        return START_REDELIVER_INTENT
    }

    private fun onCommand(command: CGMServiceCommand) = lifecycleScope.launch{
        when (command) {
            CGMServiceCommand.REQUEST_ALL_RECORDS -> requestAllRecords()
            CGMServiceCommand.REQUEST_LAST_RECORD -> requestLastRecord()
            CGMServiceCommand.REQUEST_FIRST_RECORD -> requestFirstRecord()
            CGMServiceCommand.DISCONNECT -> client.disconnect()
        }
    }

    private fun startGattClient(device: ServerDevice) = lifecycleScope.launch {
        val logger = NordicBlekLogger(this@CGMService, stringConst.APP_NAME, "CGM", device.address)

        client = device.connect(this@CGMService, logger = logger)

        repository.loggerEvent
            .onEach { logger.launch() }
            .launchIn(lifecycleScope)

        client.connectionStateWithStatus
            .onEach { repository.onConnectionStateChanged(it) }
            .filterNotNull()
            .onEach { stopIfDisconnected(it) }
            .launchIn(lifecycleScope)

        if (!client.isConnected) {
            repository.onInitComplete(device)
            return@launch
        }

        client.discoverServices()
            .filterNotNull()
            .onEach { configureGatt(it, device) }
            .launchIn(lifecycleScope)
    }

    private suspend fun configureGatt(services: BleGattServices, device: ServerDevice) {
        val cgmService = services.findService(CGMS_SERVICE_UUID)!!
        val statusCharacteristic = cgmService.findCharacteristic(CGM_STATUS_UUID)!!
        val featureCharacteristic = cgmService.findCharacteristic(CGM_FEATURE_UUID)!!
        val measurementCharacteristic = cgmService.findCharacteristic(CGM_MEASUREMENT_UUID)!!
        val opsControlPointCharacteristic = cgmService.findCharacteristic(CGM_OPS_CONTROL_POINT_UUID)!!
        recordAccessControlPointCharacteristic = cgmService.findCharacteristic(RACP_UUID)!!
        val batteryService = services.findService(BATTERY_SERVICE_UUID)!!
        val batteryLevelCharacteristic = batteryService.findCharacteristic(BATTERY_LEVEL_CHARACTERISTIC_UUID)!!

        batteryLevelCharacteristic.getNotifications()
            .mapNotNull { BatteryLevelParser.parse(it) }
            .onEach { repository.onBatteryLevelChanged(it) }
            .launchIn(lifecycleScope)

        measurementCharacteristic.getNotifications()
            .mapNotNull { CGMMeasurementParser.parse(it) }
            .onEach {
                if (sessionStartTime == 0L && !recordAccessRequestInProgress) {
                    val timeOffset = it.minOf { it.timeOffset }
                    sessionStartTime = System.currentTimeMillis() - timeOffset * 60000L
                }

                val result = it.map {
                    val timestamp = sessionStartTime + it.timeOffset * 60000L
                    CGMRecordWithSequenceNumber(it.timeOffset, it, timestamp)
                }

                repository.onDataReceived(result)
            }
            .launchIn(lifecycleScope)

        opsControlPointCharacteristic.getNotifications()
            .mapNotNull { CGMSpecificOpsControlPointParser.parse(it) }
            .onEach {
                if (it.isOperationCompleted) {
                    sessionStartTime = if (it.requestCode == CGMOpCode.CGM_OP_CODE_START_SESSION) {
                        System.currentTimeMillis()
                    } else {
                        0
                    }
                } else {
                    if (it.requestCode == CGMOpCode.CGM_OP_CODE_START_SESSION && it.errorCode == CGMErrorCode.CGM_ERROR_PROCEDURE_NOT_COMPLETED) {
                        sessionStartTime = 0
                    } else if (it.requestCode == CGMOpCode.CGM_OP_CODE_STOP_SESSION) {
                        sessionStartTime = 0
                    }
                }
            }
            .launchIn(lifecycleScope)

        recordAccessControlPointCharacteristic.getNotifications()
            .mapNotNull { RecordAccessControlPointParser.parse(it) }
            .onEach { onAccessControlPointDataReceived(it) }
            .launchIn(lifecycleScope)

        lifecycleScope.launchWithCatch {
            val featuresEnvelope = featureCharacteristic.read().let { CGMFeatureParser.parse(it) }!!
            secured = featuresEnvelope.features.e2eCrcSupported
        }

        lifecycleScope.launchWithCatch {
            val statusEnvelope = statusCharacteristic.read().let { CGMStatusParser.parse(it) }!!
            if (!statusEnvelope.status.sessionStopped) {
                sessionStartTime = System.currentTimeMillis() - statusEnvelope.timeOffset * 60000L
            }
        }

        if (sessionStartTime == 0L) {
            opsControlPointCharacteristic.write(CGMSpecificOpsControlPointData.startSession(secured).value!!)
        }

        repository.onInitComplete(device)
    }

    private fun onAccessControlPointDataReceived(data: RecordAccessControlPointData) = lifecycleScope.launch {
        when (data) {
            is NumberOfRecordsData -> onNumberOfRecordsReceived(data.numberOfRecords)
            is ResponseData -> when (data.responseCode) {
                RACPResponseCode.RACP_RESPONSE_SUCCESS -> onRecordAccessOperationCompleted(data.requestCode)
                RACPResponseCode.RACP_ERROR_NO_RECORDS_FOUND -> onRecordAccessOperationCompletedWithNoRecordsFound()
                RACPResponseCode.RACP_ERROR_OP_CODE_NOT_SUPPORTED,
                RACPResponseCode.RACP_ERROR_INVALID_OPERATOR,
                RACPResponseCode.RACP_ERROR_OPERATOR_NOT_SUPPORTED,
                RACPResponseCode.RACP_ERROR_INVALID_OPERAND,
                RACPResponseCode.RACP_ERROR_ABORT_UNSUCCESSFUL,
                RACPResponseCode.RACP_ERROR_PROCEDURE_NOT_COMPLETED,
                RACPResponseCode.RACP_ERROR_OPERAND_NOT_SUPPORTED -> onRecordAccessOperationError(data.responseCode)
            }
        }
    }

    private fun onRecordAccessOperationCompleted(requestCode: RACPOpCode) {
        val status = when (requestCode) {
            RACPOpCode.RACP_OP_CODE_ABORT_OPERATION -> RequestStatus.ABORTED
            else -> RequestStatus.SUCCESS
        }
        repository.onNewRequestStatus(status)
    }

    private fun onRecordAccessOperationCompletedWithNoRecordsFound() {
        repository.onNewRequestStatus(RequestStatus.SUCCESS)
    }

    private suspend fun onNumberOfRecordsReceived(numberOfRecords: Int) {
        if (numberOfRecords > 0) {
            if (repository.hasRecords) {
                recordAccessControlPointCharacteristic.write(
                    RecordAccessControlPointInputParser.reportStoredRecordsGreaterThenOrEqualTo(repository.highestSequenceNumber).value
                )
            } else {
                recordAccessControlPointCharacteristic.write(
                    RecordAccessControlPointInputParser.reportAllStoredRecords().value
                )
            }
        }
        repository.onNewRequestStatus(RequestStatus.SUCCESS)
    }

    private fun onRecordAccessOperationError(response: RACPResponseCode) {
        if (response == RACPResponseCode.RACP_ERROR_OP_CODE_NOT_SUPPORTED) {
            repository.onNewRequestStatus(RequestStatus.NOT_SUPPORTED)
        } else {
            repository.onNewRequestStatus(RequestStatus.FAILED)
        }
    }

    private fun clear() {
        repository.clear()
    }

    private suspend fun requestLastRecord() {
        clear()
        repository.onNewRequestStatus(RequestStatus.PENDING)
        recordAccessControlPointCharacteristic.write(RecordAccessControlPointInputParser.reportLastStoredRecord().value)
    }

    private suspend fun requestFirstRecord() {
        clear()
        repository.onNewRequestStatus(RequestStatus.PENDING)
        recordAccessControlPointCharacteristic.write(RecordAccessControlPointInputParser.reportFirstStoredRecord().value)
    }

    private suspend fun requestAllRecords() {
        clear()
        repository.onNewRequestStatus(RequestStatus.PENDING)
        recordAccessControlPointCharacteristic.write(RecordAccessControlPointInputParser.reportNumberOfAllStoredRecords().value)
    }

    private fun stopIfDisconnected(connectionState: GattConnectionStateWithStatus) {
        if (connectionState.state == GattConnectionState.STATE_DISCONNECTED) {
            stopSelf()
        }
    }

    private fun disconnect() {
        client.disconnect()
    }
}
