package no.nordicsemi.android.toast.utils

import android.graphics.Color
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine

fun addLimitLine(chart: LineChart, value: Float, label: String) {
    val limitLine = LimitLine(value, label).apply {
        lineWidth = 2f
        lineColor = Color.RED
        enableDashedLine(10f, 10f, 0f)
    }
    chart.axisLeft.addLimitLine(limitLine)
}
