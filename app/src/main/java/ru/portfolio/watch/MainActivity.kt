package ru.portfolio.watch

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private var wakeStatus by mutableStateOf<WakeStatus?>(null)
    private var loading by mutableStateOf(false)
    private var message by mutableStateOf("Первое обновление создаст исходный снимок без уведомления")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = PortfolioStore(this).readState()
        wakeStatus = PriorityWake.status(this)
        if (model.lastChecked > 0L) message = "Снимок сохранён. Последняя проверка: " + dateText(model.lastChecked)
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
                PortfolioScreen(model, wakeStatus, loading, message,
                    onRefresh = ::refresh,
                    onSchedule = ::saveSchedule,
                    onExactAlarmPermission = ::openExactAlarmSettings,
                    onBatteryPermission = ::openBatterySettings,
                    onNotificationPermission = ::openAppSettings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model = PortfolioStore(this).readState()
        if (model.lastChecked > 0L && message.startsWith("Первое обновление"))
            message = "Снимок уже существует. Фоновый мониторинг работает по сохранённому расписанию."
        runCatching { PriorityWake.ensureConfigured(this) }
        wakeStatus = PriorityWake.status(this)
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + packageName)))
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + packageName)))
            }.onFailure { openAppSettings() }
        }
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + packageName)))
        }.onFailure { openAppSettings() }
    }

    private fun refresh() {
        if (loading) return
        loading = true
        message = "Проверяем публичную страницу IntelInvest…"
        MonitorEngine.run(this) { outcome ->
            loading = false
            model = PortfolioStore(this).readState()
            wakeStatus = PriorityWake.status(this)
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
        wakeStatus = PriorityWake.status(this)
    }
}

@Composable
private fun PortfolioScreen(
    state: MonitorState, wakeStatus: WakeStatus?, busy: Boolean, message: String,
    onRefresh: () -> Unit, onSchedule: (Boolean, Long) -> Unit,
    onExactAlarmPermission: () -> Unit, onBatteryPermission: () -> Unit,
    onNotificationPermission: () -> Unit
) {
    var input by remember(state.intervalMinutes) { mutableStateOf(state.intervalMinutes.toString()) }
    var switchState by remember(state.enabled) { mutableStateOf(state.enabled) }
    Column(Modifier.fillMaxSize().background(background).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("PORTFOLIO WATCH", color = primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text("Портфель под наблюдением", color = Color(0xFFF1F4F8), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("IntelInvest  ·  781662", color = subtle, fontSize = 13.sp)

        SurfaceCard {
            Text("ПОСЛЕДНЯЯ ПРОВЕРКА", color = subtle, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(5.dp))
            Text(if (state.lastChecked == 0L) "Ещё нет снимка" else dateText(state.lastChecked),
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (state.portfolioValueRub != null) {
                Text("СТОИМОСТЬ ВСЕХ АКТИВОВ", color = subtle, fontSize = 11.sp, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(3.dp))
                Text(RubValues.display(state.portfolioValueRub), fontSize = 26.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFFF1F4F8))
                Text("На момент проверки: ${dateText(state.valuationCheckedAt)}",
                    color = subtle, fontSize = 11.sp)
                if (state.baselineValueRub != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("При первом снимке: ${RubValues.display(state.baselineValueRub)}",
                        color = subtle, fontSize = 12.sp)
                    Text("Первая оценка: ${dateText(state.baselineValueAt)}",
                        color = subtle, fontSize = 11.sp)
                    val current = state.portfolioValueRub.toBigDecimalOrNull()
                    val first = state.baselineValueRub.toBigDecimalOrNull()
                    if (current != null && first != null) {
                        val delta = current.subtract(first)
                        Text("Изменение стоимости: ${if (delta.signum() >= 0) "+" else "−"}${RubValues.display(delta.abs().toPlainString())}",
                            color = if (delta.signum() >= 0) success else alert, fontSize = 13.sp)
                        Text("Учитывает рыночные цены и операции, не является доходностью.",
                            color = subtle, fontSize = 10.sp)
                    }
                }
            } else {
                Text("Актуальная стоимость не подтверждена", color = alert, fontSize = 12.sp)
                if (state.valuationCheckedAt > 0L) Text("Последняя подтверждённая оценка: " + dateText(state.valuationCheckedAt),
                    color = subtle, fontSize = 11.sp)
            }
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
            Text("Стоимость, доля и результат открытых позиций", color = subtle, fontSize = 12.sp)
            if (state.holdingsNote.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(state.holdingsNote, color = if (state.portfolioValueRub == null || state.holdingsNote.contains("запаздывает")) alert else success,
                    fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (state.holdings.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                    Text(if (state.holdingsVerified) "Открытых позиций нет" else "Актуальный состав не подтверждён. Старый список скрыт.",
                        color = subtle, textAlign = TextAlign.Center)
                }
            } else {
                Spacer(Modifier.height(18.dp))
                if (state.portfolioValueRub != null && state.holdings.sumOf { it.percent } > 0.0)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AllocationRing(state.holdings) }
                else Text("Диаграмма временно недоступна: не все оценки подтверждены.", color = subtle, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                state.holdings.sortedByDescending { it.percent }.forEachIndexed { index, h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(shades[index % shades.size], RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.ticker, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h.name != h.ticker) Text(h.name, color = subtle, fontSize = 11.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Количество: ${h.quantity}", color = subtle, fontSize = 12.sp)
                            Text("Стоимость: ${RubValues.display(h.marketValueRub)}",
                                color = subtle, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            val change = h.openProfitRub?.toBigDecimalOrNull()
                            val changeColor = when {
                                change == null -> subtle
                                change.signum() > 0 -> success
                                change.signum() < 0 -> alert
                                else -> subtle
                            }
                            Text(RubValues.displaySignedRub(h.openProfitRub),
                                color = changeColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(RubValues.displaySignedPercent(h.openProfitPercent),
                                color = changeColor, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(if (state.portfolioValueRub != null) String.format(Locale("ru", "RU"), "Доля %.1f%%", h.percent) else "Доля —",
                                color = subtle, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Прибыль/убыток: текущая стоимость минус вложения в открытый остаток. Закрытые сделки не учитываются.",
                    color = subtle, fontSize = 11.sp, lineHeight = 16.sp)
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
            Spacer(Modifier.height(14.dp))
            Text("Приоритетная фоновая проверка", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("Пробуждение в режиме сна + приоритетная проверка + резерв. Не требует зарядки, но Android может задерживать запуск.",
                color = subtle, fontSize = 12.sp, lineHeight = 17.sp)
            if (wakeStatus != null) {
                Spacer(Modifier.height(10.dp))
                Text(if (wakeStatus.exactAllowed) "Точные будильники: разрешены" else "Точные будильники: не разрешены",
                    color = if (wakeStatus.exactAllowed) success else alert, fontSize = 12.sp)
                if (!wakeStatus.exactAllowed)
                    OutlinedButton(onClick = onExactAlarmPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Разрешить точные будильники")
                    }
                Spacer(Modifier.height(6.dp))
                Text(if (wakeStatus.batteryUnrestricted) "Ограничение батареи: снято" else "Ограничение батареи: действует",
                    color = if (wakeStatus.batteryUnrestricted) success else alert, fontSize = 12.sp)
                if (!wakeStatus.batteryUnrestricted)
                    OutlinedButton(onClick = onBatteryPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Разрешить работу без ограничений")
                    }
                Spacer(Modifier.height(6.dp))
                Text(if (wakeStatus.notificationsAllowed) "Уведомления: разрешены" else "Уведомления: отключены",
                    color = if (wakeStatus.notificationsAllowed) success else alert, fontSize = 12.sp)
                if (!wakeStatus.notificationsAllowed)
                    OutlinedButton(onClick = onNotificationPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть разрешения уведомлений")
                    }
                if (wakeStatus.powerSaveActive) Text("Включено энергосбережение. Возможны задержки системных задач.",
                    color = alert, fontSize = 12.sp)
                if (state.enabled) {
                    Spacer(Modifier.height(10.dp))
                    Text("Следующий сигнал: " + dateText(wakeStatus.nextDue), color = subtle, fontSize = 11.sp)
                    Text("Последнее пробуждение: " + dateText(wakeStatus.lastWake), color = subtle, fontSize = 11.sp)
                    Text("Последний запуск: " + dateText(wakeStatus.lastAttempt), color = subtle, fontSize = 11.sp)
                    Text("Успешная фоновая проверка: " + dateText(wakeStatus.lastSuccess),
                        color = subtle, fontSize = 11.sp)
                    if (wakeStatus.lastFailure.isNotBlank())
                        Text("Последняя ошибка: " + wakeStatus.lastFailure, color = alert, fontSize = 11.sp)
                }
            }
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
