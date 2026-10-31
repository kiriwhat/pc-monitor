package com.pcmonitor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** 数据系列：颜色 + 数值列表 */
data class ChartSeries(val color: Color, val values: List<Float>)

/**
 * 自绘实时曲线图（无第三方图表库）。
 * 支持多系列叠加；yMax<=0 时按数据最大值动态缩放。
 */
@Composable
fun LiveChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    yMax: Float = 100f,
    showFill: Boolean = true,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(164.dp)) {
            val step = size.width / 60f // 显示最近 60 个点
            val yScale = if (yMax <= 0) 1f else size.height / yMax

            // 网格线（25%/50%/75%）
            for (i in 1..3) {
                val y = size.height * i / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
            }

            for (cs in series) {
                val values = cs.values
                if (values.isEmpty()) continue
                val visible = values.takeLast(60)
                if (visible.size < 2) continue

                val path = Path()
                val points = visible.mapIndexed { i, v ->
                    val x = size.width - (visible.size - 1 - i) * step
                    val y = size.height - (v.coerceIn(0f, if (yMax <= 0) Float.MAX_VALUE else yMax)) * yScale
                    Offset(x, y.coerceIn(0f, size.height))
                }
                path.moveTo(points.first().x, points.first().y)
                for (p in points.drop(1)) path.lineTo(p.x, p.y)

                // 填充渐变
                if (showFill) {
                    val fillPath = Path().apply {
                        moveTo(points.first().x, size.height)
                        for (p in points) lineTo(p.x, p.y)
                        lineTo(points.last().x, size.height)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        color = cs.color.copy(alpha = 0.12f)
                    )
                }
                // 曲线
                drawPath(
                    path = path,
                    color = cs.color,
                    style = Stroke(width = 5f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                )
            }

            // 底线
            drawLine(color = lineColor, start = Offset(0f, size.height - 1f),
                end = Offset(size.width, size.height - 1f), strokeWidth = 1f)
        }
    }
}
