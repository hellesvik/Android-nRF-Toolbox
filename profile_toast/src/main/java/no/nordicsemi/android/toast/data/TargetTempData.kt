package no.nordicsemi.android.toast.data

data class TargetTempData(
    val targetTemp: Int,
    val sensorContact: Boolean,
    val energyExpanded: Int?,
    val rrIntervals: List<Int>,
)
