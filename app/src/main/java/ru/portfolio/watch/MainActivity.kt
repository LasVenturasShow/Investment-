package ru.portfolio.watch

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

private val background = Color(0xFF0D131B)
private val panel = Color(0xFF19222E)
private val panel2 = Color(0xFF222E3B)
private val primary = Color(0xFF69CCD0)
private val subtle = Color(0xFF9AA9B7)
private val success = Color(0xFF6FDBAC)
private val alert = Color(0xFFFF9C8D)
private val shades = listOf(Color(0xFF66C5D1), Color(0xFF8B97F5), Color(0xFFF6BD72), Color(0xFF7BC9A6), Color(0xFFD69CD6), Color(0xFFB0BEC5))

class MainActivity : ComponentActivity() {
    private var model by mutableStateOf(MonitorState())
    private var loading by mutableStateOf(false)
    private var message by mutableStateOf("Первое обновление создаст исходный снимок без уведомления")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = PortfolioStore(this).readState()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 73)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = primary,
                onPrimary = background,
                background = background,
                surface = panel,
                onSurface = Color(0xFFF1F4F8),
                onBackground = Color(0xFFF1F4F8),
            )) {
                PortfolioScreen(model, loading, message,
                    onRefresh = ::refresh,
                    onSchedule = ::saveSchedule)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model = PortfolioStore(this).readState()
    }

    private fun refresh() {
        if (loading) return
        loading = true
        message = "Проверяем публичную страницу IntelInvest…"
        MonitorEngine.run(this) { outcome ->
            loading = false
            model = PortfolioStore(this).readState()
            message = if (outcome.isFailure) {
                "Ошибка: ${outcome.exceptionOrNull()?.message ?: "Неизвестная проблема"}. Старый снимок сохранён."
            } else {
                val result = outcome.getOrThrow()
                when {
                    result.initial -> "Исходный снимок сохранён. ${result.diagnostic}"
                    result.changes.isEmpty() -> "Изменений сделок нет. ${result.diagnostic}"
                    else -> "Обнаружено изменений: ${result.changes.size}. ${result.diagnostic}"
                }
            }
        }
    }

    private fun saveSchedule(enabled: Boolean, minutes: Long) {
        message = runCatching { MonitorEngine.configure(this, enabled, minutes) }
            .getOrElse { "Ошибка интервала: ${it.message}" }
        model = PortfolioStore(this).readState()
    }
}

@Composable
private fun PortfolioScreen(
    state: MonitorState, busy: Boolean, message: String,
    onRefresh: () -> Unit, onSchedule: (Boolean, Long) -> Unit
) {
    var input by remember(state.intervalMinutes) { mutableStateOf(state.intervalMinutes.toString()) }
    var switchState by remember(state.enabled) { mutableStateOf(state.enabled) }
    Column(Modifier.fillMaxSize().background(background).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("PORTFOLIO WATCH", color = primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text("Мой портфель", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("IntelInvest  ·  781662", color = subtle, fontSize = 13.sp)

        SurfaceCard {
            Text("ПОСЛЕДНЯЯ ПРОВЕРКА", color = subtle, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(5.dp))
            Text(if (state.lastChecked == 0L) "Ещё нет снимка" else dateText(state.lastChecked),
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("Операций в истории: ${state.tradesCount}", color = subtle, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth().height(51.dp),
                shape = RoundedCornerShape(13.dp)) {
                Text(if (busy) "Загружаем данные…" else "Проверить портфель", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(message, color = if (message.startsWith("Ошибка")) alert else subtle,
                fontSize = 12.sp, lineHeight = 18.sp)
        }

        SurfaceCard {
            Text("Структура активов", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("Доли по данным опубликованного портфеля", color = subtle, fontSize = 12.sp)
            if (state.holdings.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                    Text("Данные по долям активов пока не получены", color = subtle, textAlign = TextAlign.Center)
                }
            } else {
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AllocationRing(state.holdings) }
                Spacer(Modifier.height(18.dp))
                state.holdings.sortedByDescending { it.percent }.forEachIndexed { index, h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(shades[index % shades.size], RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.ticker, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h.name != h.ticker) Text(h.name, color = subtle, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(String.format(Locale("ru", "RU"), "%.1f%%", h.percent), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
                Text("Данные диаграммы: ${dateText(state.holdingsCheckedAt)}", color = subtle, fontSize = 11.sp)
            }
        }

        SurfaceCard {
            Text("Последние изменения", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (state.events.isEmpty()) {
                Text("Изменений пока не зафиксировано", color = subtle, fontSize = 13.sp)
            } else state.events.take(16).forEachIndexed { i, event ->
                val color = when (event.change.type) {
                    "BUY", "ADDED", "POSITION_ADDED" -> success
                    "SELL", "REMOVED", "POSITION_REMOVED" -> alert
                    else -> primary
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).background(color, RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(event.change.label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(dateText(event.timestamp), color = subtle, fontSize = 10.sp)
                        Text(event.change.description, color = subtle, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
                if (i < state.events.take(16).lastIndex) HorizontalDivider(color = panel2)
            }
        }

        SurfaceCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Фоновый мониторинг", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Проверка без открытия приложения", color = subtle, fontSize = 12.sp)
                }
                Switch(checked = switchState, onCheckedChange = { switchState = it })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = input,
                onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) input = it },
                label = { Text("Интервал в минутах") },
                supportingText = { Text("От 15 до 10080 мин. Android может отложить выполнение.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { val n = input.toLongOrNull(); if (n != null) onSchedule(switchState, n) },
                enabled = (input.toLongOrNull()?.let { it in 15L..10080L } == true),
                modifier = Modifier.fillMaxWidth()) { Text("Сохранить расписание") }
        }
        Text("Источник: intelinvest.ru/public/portfolio/781662/trades. Данные используются только локально; результаты зависят от доступности публичной страницы.",
            fontSize = 11.sp, color = subtle, lineHeight = 17.sp)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = panel, shape = RoundedCornerShape(19.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(19.dp), content = content)
    }
}

@Composable
private fun AllocationRing(holdings: List<Holding>) {
    val rows = holdings.sortedByDescending { it.percent }
    val total = rows.sumOf { it.percent }
    Box(Modifier.size(205.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            var from = -90f
            for ((index, row) in rows.withIndex()) {
                val sweep = (row.percent / total * 360f).toFloat()
                drawArc(shades[index % shades.size], from, sweep, false,
                    style = Stroke(width = 29.dp.toPx(), cap = StrokeCap.Butt))
                from += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(rows.size.toString(), fontSize = 31.sp, fontWeight = FontWeight.Bold)
            Text("активов", color = subtle, fontSize = 12.sp)
        }
    }
}
