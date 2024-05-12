        package no.nordicsemi.android.toast.data

data class ToastData(
    val heartRate: Int,
    val sensorContact: Boolean,
    val energyExpanded: Int?,
    val rrIntervals: List<Int>
)
