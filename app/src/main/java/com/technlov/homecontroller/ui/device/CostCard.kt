package com.technlov.homecontroller.ui.device

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import com.technlov.homecontroller.ui.components.GroupCard
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.technlov.homecontroller.R
import com.technlov.homecontroller.history.PowerHistoryUi
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.pow

/*
 * Electricity cost for a metered plug: today's and this month's cost by tariff zone, a mechanical-counter display that
 * runs between readings, a "since the demo started" counter, and a calculator for charging a battery at night.
 * Ported from the plug_cost web dashboard. Ukrainian household tariff by default: one base (day) price and zone
 * coefficients 0.5 (two-zone night) and 0.4 / 1.0 / 1.5 (three-zone night / half-peak / peak).
 */

/** One tariff zone: [hours] are [from, to) local hours. */
data class TariffZone(val nameRes: Int, val coef: Double, val hours: List<IntRange>)

object Tariffs {
    val ZONES: Map<Int, List<TariffZone>> = mapOf(
        1 to listOf(TariffZone(R.string.zone_all, 1.0, listOf(0 until 24))),
        2 to listOf(
            TariffZone(R.string.zone_night, 0.5, listOf(23 until 24, 0 until 7)),
            TariffZone(R.string.zone_day, 1.0, listOf(7 until 23)),
        ),
        3 to listOf(
            TariffZone(R.string.zone_night, 0.4, listOf(23 until 24, 0 until 7)),
            TariffZone(R.string.zone_peak, 1.5, listOf(8 until 11, 20 until 22)),
            TariffZone(R.string.zone_half, 1.0, listOf(7 until 8, 11 until 20, 22 until 23)),
        ),
    )

    fun zoneAt(zones: Int, hour: Int): TariffZone =
        ZONES.getValue(zones).firstOrNull { z -> z.hours.any { hour in it } } ?: ZONES.getValue(zones).first()

    fun zone(zones: Int, millis: Long, tz: ZoneId): TariffZone = zoneAt(zones, Instant.ofEpochMilli(millis).atZone(tz).hour)

    /** Price of the zone named [res] for a meter with [zones] zones, or the day price when it has no such zone. */
    fun price(base: Double, zones: Int, res: Int): Double =
        base * (ZONES.getValue(zones).firstOrNull { it.nameRes == res } ?: ZONES.getValue(zones).last()).coef
}

/** Settings and the client's appliances, kept on this phone. */
class CostPrefs(context: Context) {
    private val p = context.getSharedPreferences("cost", Context.MODE_PRIVATE)
    var base: Double
        get() = p.getString("base", "4.32")!!.toDoubleOrNull() ?: 4.32
        set(v) = p.edit().putString("base", v.toString()).apply()
    var zones: Int
        get() = p.getInt("zones", 1).coerceIn(1, 3)
        set(v) = p.edit().putInt("zones", v.coerceIn(1, 3)).apply()
    fun str(key: String, def: String): String = p.getString(key, def) ?: def
    fun putStr(key: String, v: String) = p.edit().putString(key, v).apply()
}

data class Appliance(val name: String, val watts: Double, val hours: Double) {
    val kwhPerDay: Double get() = watts * hours / 1000
}

private fun loadAppliances(prefs: CostPrefs): List<Appliance> = runCatching {
    val a = JSONArray(prefs.str("appliances", "[]"))
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Appliance(o.getString("n"), o.getDouble("w"), o.getDouble("h"))
    }
}.getOrDefault(emptyList())

private fun saveAppliances(prefs: CostPrefs, list: List<Appliance>) = prefs.putStr(
    "appliances",
    JSONArray(list.map { JSONObject().put("n", it.name).put("w", it.watts).put("h", it.hours) }).toString(),
)

/** Cost of the stored hourly energy in [fromMillis, ∞) at today's tariff. */
private fun costSince(history: PowerHistoryUi, fromMillis: Long, base: Double, zones: Int, tz: ZoneId): Pair<Double, Double> {
    var kwh = 0.0
    var uah = 0.0
    for ((hour, e) in history.hourly) {
        if (hour < fromMillis) continue
        kwh += e
        uah += e * base * Tariffs.zone(zones, hour, tz).coef
    }
    return kwh to uah
}

/**
 * "Since the demo started", as a point in time: the hour it began in and the energy that hour already held. Priced on
 * demand, so the same demo can be shown under a single-rate, two-zone or three-zone tariff.
 */
data class DemoSession(val at: Long, val hour0: Long, val partialKwh: Double) {
    private fun since(h: PowerHistoryUi) = h.hourly.filter { it.first >= hour0 }

    fun kwh(h: PowerHistoryUi, runKwh: Double): Double = since(h).sumOf { it.second } - partialKwh + runKwh

    fun uah(h: PowerHistoryUi, runKwh: Double, priceNow: Double, priceAt: (Long) -> Double): Double =
        since(h).sumOf { it.second * priceAt(it.first) } - partialKwh * priceAt(hour0) + runKwh * priceNow

    companion object {
        private const val HOUR = 3_600_000L

        /** For rememberSaveable: three numbers, or nothing before the first reading. */
        val Saver: Saver<DemoSession?, Any> = Saver(
            save = { s -> s?.let { arrayListOf<Any>(it.at, it.hour0, it.partialKwh) } ?: arrayListOf<Any>() },
            restore = { v ->
                val l = v as List<*>
                if (l.size == 3) DemoSession(l[0] as Long, l[1] as Long, l[2] as Double) else null
            },
        )

        fun start(h: PowerHistoryUi, now: Long, runKwh: Double): DemoSession {
            val hour0 = now - Math.floorMod(now, HOUR)
            val held = h.hourly.firstOrNull { it.first == hour0 }?.second ?: 0.0
            return DemoSession(now, hour0, held + runKwh)
        }
    }
}

/** Rounding noise around zero (e.g. -1e-17) would print as a red "-0,000". */
internal fun clean(v: Double): Double = if (kotlin.math.abs(v) < 0.0005) 0.0 else v

/** Next displayed value: follows [target] up; ignores a step back of under half a kopeck; corrects a bigger one. */
internal fun settle(shown: Double, target: Double): Double =
    if (target >= shown || shown - target > 0.005) target else shown

private fun nf(digits: Int) = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = digits
    maximumFractionDigits = digits
}

/** "Electricity cost" and "night charging" cards, for a device with power history. */
@Composable
fun CostSection(history: PowerHistoryUi, livePowerW: Double?) {
    val context = LocalContext.current
    val prefs = remember { CostPrefs(context) }
    var base by remember { mutableStateOf(prefs.base) }
    var zones by remember { mutableStateOf(prefs.zones) }
    val tz = remember { ZoneId.systemDefault() }

    // Anchor: the cost of the stored energy at the moment the history was built. Between rebuilds (once a minute)
    // the counter runs on at the live power, capped at two minutes so a stale reading cannot run away.
    val now0 = history.to
    val day0 = Instant.ofEpochMilli(now0).atZone(tz).toLocalDate().atStartOfDay(tz).toInstant().toEpochMilli()
    val month0 = Instant.ofEpochMilli(now0).atZone(tz).toLocalDate().withDayOfMonth(1).atStartOfDay(tz).toInstant().toEpochMilli()
    val (todayKwh, todayUah) = costSince(history, day0, base, zones, tz)
    val (monthKwh, monthUah) = costSince(history, month0, base, zones, tz)
    val allKwh = history.hourly.sumOf { it.second }
    val powerW = livePowerW ?: history.nowW ?: 0.0

    var frameMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { frameMs = System.currentTimeMillis() } }
    // The zone, and so the price, follows the clock: at 23:00, 7:00 and the peak edges it changes at once.
    val zoneNow = Tariffs.zone(zones, frameMs, tz)
    val priceNow = base * zoneNow.coef
    // Run on from the last STORED sample (samples are kept per minute, so the stored energy ends a minute or two
    // before "now"); running from [now0] instead lost that stretch at every reload and stalled the counter.
    val anchor = history.lastSampleAt ?: now0
    val dt = ((frameMs - anchor).coerceIn(0L, 150_000L)) / 3_600_000.0
    val runKwh = powerW / 1000 * dt
    // Holds through tiny step-backs (a reload replacing the estimate with stored energy) so the drums don't jitter,
    // but a real gap is corrected: running on at a power that has since dropped must not stay over-counted.
    var shown by remember(base, zones, day0) { mutableStateOf(0.0) }
    shown = settle(shown, todayUah + runKwh * priceNow)
    val shownKwh = todayKwh + runKwh

    // Since the demo started. Stored as a point in time (hour + energy already in that hour), so it can be priced under
    // any tariff. rememberSaveable: the list drops this card's state when it scrolls out of view, which reset a plain
    // remember; saved state survives scrolling and rotation and starts afresh only when the device page is opened
    // anew or "Start from zero" is tapped.
    var session by rememberSaveable(stateSaver = DemoSession.Saver) { mutableStateOf<DemoSession?>(null) }
    if (session == null && history.hourly.isNotEmpty()) session = DemoSession.start(history, frameMs, runKwh)
    val ses = session
    val sesKwh = ses?.kwh(history, runKwh) ?: 0.0
    val sesUah = ses?.uah(history, runKwh, priceNow) { h -> base * Tariffs.zone(zones, h, tz).coef } ?: 0.0
    var shownSes by remember(ses, base, zones) { mutableStateOf(0.0) }
    shownSes = settle(shownSes, sesUah)
    val ratePerSec = powerW / 1000 / 3600 * priceNow
    // What a two-zone and a three-zone meter would save against a single-rate one (every hour at the day price), for
    // the whole stored history and for the demo. Shown whatever meter is chosen above.
    val singleAll = (allKwh + runKwh) * base
    val singleSes = sesKwh * base
    val savings = listOf(2, 3).map { z ->
        val priceZ = { h: Long -> base * Tariffs.zone(z, h, tz).coef }
        val zonedAll = history.hourly.sumOf { (h, e) -> e * priceZ(h) } + runKwh * priceZ(frameMs)
        val zonedSes = ses?.uah(history, runKwh, priceZ(frameMs), priceZ) ?: 0.0
        Triple(z, clean(singleAll - zonedAll), clean(singleSes - zonedSes))
    }
    val firstHour = history.hourly.firstOrNull()?.first

    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (history.hourly.isEmpty()) {
                    Text(stringResource(R.string.cost_waiting), color = scheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.cost_session), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Odometer(shownSes, ratePerSec)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ses != null) stringResource(
                            R.string.cost_session_info,
                            nf(4).format(sesKwh.coerceAtLeast(0.0)),
                            nf(1).format((frameMs - ses.at) / 60_000.0),
                        ) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { session = DemoSession.start(history, System.currentTimeMillis(), runKwh) }) {
                        Text(stringResource(R.string.cost_reset))
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(stringResource(R.string.cost_now), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.cost_per_hour), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    // To a tenth of a kopeck, so it visibly moves with the power the plug reports.
                    Text(
                        nf(3).format(powerW / 1000 * priceNow) + " ₴",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                    )
                }
                LabelValue(stringResource(R.string.cost_zone), stringResource(R.string.cost_zone_price, stringResource(zoneNow.nameRes), nf(2).format(priceNow)))
                HorizontalDivider()
                Text(stringResource(R.string.cost_today), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Odometer(shown, ratePerSec)
                LabelValue(stringResource(R.string.cost_month), stringResource(R.string.cost_kwh_uah, nf(3).format(monthKwh + runKwh), nf(2).format(monthUah + runKwh * priceNow)))
                HorizontalDivider()
                Text(stringResource(R.string.cost_saving_title), style = MaterialTheme.typography.titleSmall)
                val dateFmt = remember { java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm") }
                Text(
                    stringResource(R.string.cost_saving_all, firstHour?.let { dateFmt.format(Instant.ofEpochMilli(it).atZone(tz)) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                val good = Color(0xFF15803D)
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.cost_col_all), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                    Text(stringResource(R.string.cost_col_session), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                }
                savings.forEach { (z, all, s) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (z == 2) R.string.meter_short_2 else R.string.meter_short_3), modifier = Modifier.weight(1f))
                        Text(nf(2).format(all) + " ₴", fontWeight = FontWeight.Bold, color = if (all >= 0) good else scheme.error, modifier = Modifier.width(96.dp))
                        Text(nf(3).format(s) + " ₴", fontWeight = FontWeight.Bold, color = if (s >= 0) good else scheme.error, modifier = Modifier.width(96.dp))
                    }
                }
                Text(stringResource(R.string.cost_saving_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                HorizontalDivider()
                TariffEditor(base, zones) { b, z ->
                    base = b; zones = z
                    prefs.base = b; prefs.zones = z
                }
                Text(stringResource(R.string.cost_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        NightChargingCard(prefs, base, powerW)
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A mechanical counter: each digit is a drum 0..9,0. The lowest drum turns continuously; a higher one rolls over only
 * while every drum below it passes 9 -> 0, as on an old meter. Kopecks are orange; thousandths of a kopeck are small,
 * and blur when they spin too fast to read.
 */
@Composable
fun Odometer(value: Double, ratePerSec: Double, intDigits: Int = 3) {
    val places = (intDigits - 1 downTo 0).toList() + listOf(-1, -2) + listOf(-3, -4, -5)
    Row(
        Modifier
            .background(Color(0xFF1C1917), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        places.forEachIndexed { i, place ->
            if (place == -1) Text(",", color = Color(0xFFFAFAF9), fontSize = 34.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            val small = place <= -3
            val unit = 10.0.pow(place)
            val scaled = value / unit
            val d = floor(scaled).toLong().mod(10L).toDouble()
            val frac = scaled - floor(scaled)
            val pos = if (i == places.lastIndex) d + frac else d + (if (frac > 0.9) (frac - 0.9) * 10 else 0.0)
            Drum(
                pos = pos.toFloat(),
                height = if (small) 20.dp else 40.dp,
                width = if (small) 14.dp else 26.dp,
                color = when {
                    small -> Color(0xFFA8A29E)
                    place < 0 -> Color(0xFFFB923C)
                    else -> Color(0xFFFAFAF9)
                },
                blur = ratePerSec / unit > 12,
            )
        }
        Text(" ₴", color = Color(0xFFFAFAF9), fontSize = 22.sp, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun Drum(pos: Float, height: Dp, width: Dp, color: Color, blur: Boolean) {
    val hPx = with(LocalDensity.current) { height.toPx() }
    Box(
        Modifier
            .padding(horizontal = 1.dp)
            .size(width, height)
            .clipToBounds()
            .background(Color(0xFF0C0A09), RoundedCornerShape(3.dp)),
    ) {
        // unbounded: the strip of 11 digits is taller than the drum's window; without it Compose squeezes every digit
        // after the first to zero height and the drum only ever shows "0".
        Column(
            Modifier
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = -pos * hPx; alpha = if (blur) 0.55f else 1f },
        ) {
            for (n in 0..10) {
                Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
                    Text(
                        (n % 10).toString(),
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = (height.value * 0.8f).sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TariffEditor(base: Double, zones: Int, onApply: (Double, Int) -> Unit) {
    var text by remember(base) { mutableStateOf(nf(2).format(base)) }
    var z by remember(zones) { mutableStateOf(zones) }
    var msg by remember { mutableStateOf("") }
    val zoneNames = listOf(R.string.meter_1, R.string.meter_2, R.string.meter_3).map { stringResource(it) }
    val chipNames = listOf(R.string.meter_short_1, R.string.meter_short_2, R.string.meter_short_3).map { stringResource(it) }
    val invalid = stringResource(R.string.cost_price_invalid)
    val appliedFmt = stringResource(R.string.cost_applied)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.cost_meter), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The meter type applies at once: the price follows the current hour's zone straight away.
            (1..3).forEach { n ->
                FilterChip(selected = z == n, onClick = {
                    z = n
                    onApply(base, n)
                    msg = String.format(appliedFmt, nf(2).format(base), zoneNames[n - 1])
                }, label = { Text(chipNames[n - 1]) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.cost_price_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = {
                val p = text.replace(',', '.').trim().toDoubleOrNull()
                if (p == null || p <= 0 || p >= 100) {
                    msg = invalid
                } else {
                    onApply(p, z)
                    msg = String.format(appliedFmt, nf(2).format(p), zoneNames[z - 1])
                }
            }) { Text(stringResource(R.string.cost_apply)) }
        }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The client's appliances and what charging a battery at night saves against using the grid by day. */
@Composable
private fun NightChargingCard(prefs: CostPrefs, base: Double, powerW: Double) {
    val scheme = MaterialTheme.colorScheme
    val appliances = remember { mutableStateListOf<Appliance>().apply { addAll(loadAppliances(prefs)) } }
    var name by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("8") }
    var manualW by remember { mutableStateOf("") }
    var clientZones by remember { mutableStateOf(prefs.str("c_zones", "2").toIntOrNull()?.coerceIn(1, 3) ?: 2) }
    var peak by remember { mutableStateOf(prefs.str("c_peak", "0") == "1") }
    var eff by remember { mutableStateOf(prefs.str("c_eff", "85")) }
    var cap by remember { mutableStateOf(prefs.str("c_cap", "2")) }
    var price by remember { mutableStateOf(prefs.str("c_price", "0")) }
    var cycles by remember { mutableStateOf(prefs.str("c_cycles", "3000")) }
    val defaultName = stringResource(R.string.calc_default_name)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Foldable(Icons.Rounded.Kitchen, stringResource(R.string.calc_devices)) {
            NumField(name, { name = it }, stringResource(R.string.calc_name), Modifier.fillMaxWidth(), number = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(hours, { hours = it }, stringResource(R.string.calc_hours), Modifier.weight(1f))
                NumField(manualW, { manualW = it }, stringResource(R.string.calc_watts), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    val w = manualW.toNum()?.takeIf { it > 0 } ?: powerW.takeIf { it > 0 } ?: return@FilledTonalButton
                    appliances += Appliance(name.ifBlank { "$defaultName ${appliances.size + 1}" }, w, hours.toNum() ?: 0.0)
                    saveAppliances(prefs, appliances)
                    name = ""; manualW = ""
                }) { Text(stringResource(R.string.calc_record)) }
            }
            appliances.forEachIndexed { i, a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.calc_device_row, nf(0).format(a.watts), nf(1).format(a.hours), nf(2).format(a.kwhPerDay)),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { appliances.removeAt(i); saveAppliances(prefs, appliances) }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove))
                    }
                }
            }
        }
        Foldable(Icons.Rounded.BatteryChargingFull, stringResource(R.string.calc_title)) {
            Text(stringResource(R.string.calc_client_meter), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.meter_short_1, R.string.meter_short_2, R.string.meter_short_3).forEachIndexed { i, res ->
                    FilterChip(selected = clientZones == i + 1, onClick = { clientZones = i + 1; prefs.putStr("c_zones", "${i + 1}") }, label = { Text(stringResource(res)) })
                }
            }
            if (clientZones == 3) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.calc_when_peak), Modifier.weight(1f))
                    Switch(checked = peak, onCheckedChange = { peak = it; prefs.putStr("c_peak", if (it) "1" else "0") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(eff, { eff = it; prefs.putStr("c_eff", it) }, stringResource(R.string.calc_eff), Modifier.weight(1f))
                NumField(cap, { cap = it; prefs.putStr("c_cap", it) }, stringResource(R.string.calc_capacity), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(price, { price = it; prefs.putStr("c_price", it) }, stringResource(R.string.calc_station_price), Modifier.weight(1f))
                NumField(cycles, { cycles = it; prefs.putStr("c_cycles", it) }, stringResource(R.string.calc_cycles), Modifier.weight(1f))
            }

            val kwh = appliances.sumOf { it.kwhPerDay }
            if (kwh <= 0) {
                Text(stringResource(R.string.calc_add_first), color = scheme.onSurfaceVariant)
            } else {
                val night = Tariffs.price(base, clientZones, if (clientZones == 1) R.string.zone_all else R.string.zone_night)
                val dayP = when (clientZones) {
                    1 -> Tariffs.price(base, 1, R.string.zone_all)
                    2 -> Tariffs.price(base, 2, R.string.zone_day)
                    else -> Tariffs.price(base, 3, if (peak) R.string.zone_peak else R.string.zone_half)
                }
                val e = ((eff.toNum() ?: 85.0) / 100).coerceIn(0.5, 1.0)
                val capK = cap.toNum() ?: 0.0
                val shift = if (capK > 0) minOf(kwh, capK) else kwh
                val grid = shift * dayP
                val batt = shift / e * night
                val save = grid - batt
                val bp = price.toNum() ?: 0.0
                val cyc = (cycles.toNum() ?: 3000.0).coerceAtLeast(1.0)
                val wear = if (bp > 0 && capK > 0) bp / (cyc * capK) * shift else 0.0
                val net = save - wear
                val good = Color(0xFF15803D)
                val bad = scheme.error
                LabelValue(stringResource(R.string.calc_energy), stringResource(R.string.cost_kwh, nf(2).format(kwh)))
                if (shift < kwh) LabelValue(stringResource(R.string.calc_fits), stringResource(R.string.cost_kwh, nf(2).format(shift)))
                LabelValue(stringResource(R.string.calc_grid, nf(2).format(dayP)), stringResource(R.string.cost_uah_day, nf(2).format(grid)))
                LabelValue(stringResource(R.string.calc_batt, nf(2).format(night), nf(0).format(e * 100)), stringResource(R.string.cost_uah_day, nf(2).format(batt)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.calc_saving), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.calc_per_day_month, nf(2).format(save), nf(0).format(save * 30)), fontWeight = FontWeight.Bold, color = if (save > 0) good else bad)
                }
                Text(
                    stringResource(R.string.calc_saving_sub, nf(0).format(if (grid > 0) save / grid * 100 else 0.0), nf(0).format(save * 365)),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                if (wear > 0) {
                    LabelValue(stringResource(R.string.calc_wear), "−" + stringResource(R.string.cost_uah_day, nf(2).format(wear)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.calc_net), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.calc_per_day_month, nf(2).format(net), nf(0).format(net * 30)), fontWeight = FontWeight.Bold, color = if (net > 0) good else bad)
                    }
                }
                if (clientZones == 1) Text(stringResource(R.string.calc_single_zone), color = bad, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.calc_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** A card that opens and closes on its header, like "Diagnostics"; closed at first, remembered while scrolling. */
@Composable
private fun Foldable(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    GroupCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = stringResource(if (open) R.string.action_collapse else R.string.action_expand),
                modifier = Modifier.rotate(rotation),
            )
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

private fun String.toNum(): Double? = replace(',', '.').trim().toDoubleOrNull()

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, number: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier,
    )
}
