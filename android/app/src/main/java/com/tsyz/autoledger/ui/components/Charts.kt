package com.tsyz.autoledger.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.tsyz.autoledger.core.util.Money
import com.tsyz.autoledger.report.CategoryTotal
import com.tsyz.autoledger.report.DailyPoint
import com.tsyz.autoledger.report.MonthlyTrendPoint
import com.tsyz.autoledger.ui.theme.ChartPalette
import com.tsyz.autoledger.ui.theme.ExpenseRed
import com.tsyz.autoledger.ui.theme.IncomeGreen

private val axisTextColor = AndroidColor.rgb(120, 132, 126)
private val gridColor = AndroidColor.argb(40, 120, 132, 126)

@Composable
fun CategoryBarChart(
    categories: List<CategoryTotal>,
    modifier: Modifier = Modifier
) {
    val visible = categories.take(6)
    val labels = visible.map { it.category }
    val values = visible.map { it.amountCents / 100f }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(210.dp),
        factory = { context ->
            BarChart(context).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setNoDataText("暂无支出数据")
                setScaleEnabled(false)
                setFitBars(true)
                axisRight.isEnabled = false
                axisLeft.axisMinimum = 0f
                axisLeft.textColor = axisTextColor
                axisLeft.gridColor = gridColor
                axisLeft.axisLineColor = gridColor
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(false)
                xAxis.granularity = 1f
                xAxis.textColor = axisTextColor
                xAxis.axisLineColor = gridColor
                extraBottomOffset = 6f
            }
        },
        update = { chart ->
            if (values.isEmpty()) {
                chart.clear()
                chart.invalidate()
                return@AndroidView
            }
            val entries = values.mapIndexed { index, value -> BarEntry(index.toFloat(), value) }
            val dataSet = BarDataSet(entries, "支出").apply {
                color = ExpenseRed.toArgb()
                valueTextColor = axisTextColor
                valueTextSize = 10f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        Money.format((value * 100).toLong())
                }
            }
            chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            chart.data = BarData(dataSet).apply { barWidth = 0.55f }
            chart.invalidate()
        }
    )
}

@Composable
fun CategoryPieChart(
    categories: List<CategoryTotal>,
    modifier: Modifier = Modifier
) {
    val visible = categories.take(6)
    val entries = visible.map { PieEntry(it.amountCents / 100f, it.category) }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(220.dp),
        factory = { context ->
            PieChart(context).apply {
                description.isEnabled = false
                setNoDataText("暂无支出数据")
                isDrawHoleEnabled = true
                setHoleColor(AndroidColor.TRANSPARENT)
                holeRadius = 52f
                transparentCircleRadius = 55f
                setDrawEntryLabels(false)
                setCenterTextColor(axisTextColor)
                legend.textColor = axisTextColor
                legend.isWordWrapEnabled = true
                setUsePercentValues(true)
                setExtraOffsets(6f, 6f, 6f, 6f)
            }
        },
        update = { chart ->
            if (entries.isEmpty()) {
                chart.clear()
                chart.invalidate()
                return@AndroidView
            }
            val dataSet = PieDataSet(entries, "").apply {
                setColors(ChartPalette.map { it.toArgb() })
                valueTextColor = AndroidColor.WHITE
                valueTextSize = 10f
                sliceSpace = 2f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = "%.0f%%".format(value)
                }
            }
            chart.setCenterText("支出构成")
            chart.data = PieData(dataSet)
            chart.invalidate()
        }
    )
}

@Composable
fun DailyExpenseChart(
    daily: List<DailyPoint>,
    modifier: Modifier = Modifier
) {
    val entries = daily.mapIndexed { index, point -> Entry(index.toFloat(), point.expenseCents / 100f) }
    val labels = daily.map { it.day }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(200.dp),
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setNoDataText("暂无支出数据")
                setScaleEnabled(false)
                setPinchZoom(false)
                axisRight.isEnabled = false
                axisLeft.axisMinimum = 0f
                axisLeft.textColor = axisTextColor
                axisLeft.gridColor = gridColor
                axisLeft.axisLineColor = gridColor
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(false)
                xAxis.granularity = 1f
                xAxis.textColor = axisTextColor
                xAxis.axisLineColor = gridColor
                xAxis.labelCount = 6
                extraBottomOffset = 6f
            }
        },
        update = { chart ->
            if (entries.isEmpty()) {
                chart.clear()
                chart.invalidate()
                return@AndroidView
            }
            val dataSet = LineDataSet(entries, "支出").apply {
                color = ExpenseRed.toArgb()
                setCircleColor(ExpenseRed.toArgb())
                lineWidth = 2f
                circleRadius = 2.5f
                setDrawCircleHole(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }
            chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            chart.data = LineData(dataSet)
            chart.invalidate()
        }
    )
}

@Composable
fun MonthlyTrendChart(
    trend: List<MonthlyTrendPoint>,
    modifier: Modifier = Modifier
) {
    val entriesExpense = trend.mapIndexed { index, point -> Entry(index.toFloat(), point.expenseCents / 100f) }
    val entriesIncome = trend.mapIndexed { index, point -> Entry(index.toFloat(), point.incomeCents / 100f) }
    val labels = trend.map { if (it.month.length >= 7) it.month.substring(5) else it.month }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(220.dp),
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                setNoDataText("暂无趋势数据")
                setScaleEnabled(false)
                setPinchZoom(false)
                axisRight.isEnabled = false
                axisLeft.axisMinimum = 0f
                axisLeft.textColor = axisTextColor
                axisLeft.gridColor = gridColor
                axisLeft.axisLineColor = gridColor
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(false)
                xAxis.granularity = 1f
                xAxis.textColor = axisTextColor
                xAxis.axisLineColor = gridColor
                extraBottomOffset = 6f
                legend.textColor = axisTextColor
            }
        },
        update = { chart ->
            if (entriesExpense.isEmpty()) {
                chart.clear()
                chart.invalidate()
                return@AndroidView
            }
            val expenseSet = LineDataSet(entriesExpense, "支出").apply {
                color = ExpenseRed.toArgb()
                setCircleColor(ExpenseRed.toArgb())
                lineWidth = 2f
                circleRadius = 2.5f
                setDrawCircleHole(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }
            val incomeSet = LineDataSet(entriesIncome, "收入").apply {
                color = IncomeGreen.toArgb()
                setCircleColor(IncomeGreen.toArgb())
                lineWidth = 2f
                circleRadius = 2.5f
                setDrawCircleHole(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }
            chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            chart.data = LineData(expenseSet, incomeSet)
            chart.invalidate()
        }
    )
}
