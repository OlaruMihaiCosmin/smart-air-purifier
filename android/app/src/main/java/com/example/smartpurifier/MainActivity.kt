@file:Suppress("SpellCheckingInspection", "AssignedValueIsNeverRead")

package com.example.smartpurifier

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ================= ENUMERĂRI ȘI VARIABILE GLOBALE =================
enum class IntervalTimp(val ore: Int, val puncte: Int) { ORE_4(4, 48), ORE_12(12, 144), ORE_24(24, 288) }
// ================= PALETA DE CULORI (sursa unica pentru toata aplicatia) =================
// Modifici o culoare AICI -> se schimba peste tot (grafice, gradiente, widget, tema, jurnal,
// scoruri, trenduri). Fundalurile (gri / negru / alb) sunt pastrate ca atare, doar adunate aici.
// Numele urmeaza paleta "Culori noi"; pentru a schimba o nuanta, editezi un singur rand.
object Paleta {
    // --- Fundaluri: gri / negru / alb (neschimbate) ---
    val Alb1   = Color(0xFFF2F2F7)
    val Alb2   = Color(0xFFE5E5EA)
    val Alb3   = Color(0xFFD1D1D6)
    val Gri1   = Color(0xFF8E8E93)
    val Gri2   = Color(0xFF3A3A3C)
    val Gri3   = Color(0xFF2C2C2E)
    val Gri4   = Color(0xFF1C1C1E)
    val Negru1 = Color(0xFF121212)
    val Negru2 = Color(0xFF000000)

    // --- Culori (paleta unificata) ---
    val Mov1        = Color(0xFF884AD2)  // Mov 1       - accent / jurnal / temp
    val Albastru3   = Color(0xFF0D47A1)  // Albastru 3  - umiditate ridicata
    val Albastru2   = Color(0xFF007AFF)  // Albastru 2  - accent / default / jurnal / umed-rece
    val Albastru1   = Color(0xFF1E88E5)  // Albastru 1  - gradient temp / umiditate
    val Turcoaz1    = Color(0xFF26A69A)  // Turcoaz 1   - umiditate
    val Verde3      = Color(0xFF008744)  // Verde 3     - verde accent inchis (scor1 / temp / grafice)
    val Verde1      = Color(0xFF4FA038)  // Verde 1     - verde principal (ui/curat/scor/trend)
    val Verde2      = Color(0xFF8CB342)  // Verde 2     - verde deschis (umiditate)
    val Galben1     = Color(0xFFFDD835)  // Galben 1
    val Portocaliu1 = Color(0xFFFF9500)  // Portocaliu 1
    val Maro1       = Color(0xFFBF764E)  // Maro 1      - umiditate uscata
    val Rosu1       = Color(0xFFD0281F)  // Rosu 1
    val Roz1        = Color(0xFFE84393)  // Roz 1 (roz viu, armonizat cu restul accentelor)

    // --- Culori Widget
    val GriWidget        = Color(0xFF45484D)  // inelul de fundal de pe cadranul widget
    val GriText          = Color(0xFF6E6E73)  // text secundar / fara-date
    val VerdeWidget      = Color(0xFF00C76D)  // Verde
    val GalbenWidget     = Color(0xFFFFF700)  // Galben
    val PortocaliuWidget = Color(0xFFFF9600)  // Portocaliu
    val RosuWidget       = Color(0xFFD45C61)  // Rosu
}

val culoriDisponibile = listOf(
    Paleta.Rosu1.toArgb() to "Roșu",
    Paleta.Roz1.toArgb() to "Roz",
    Paleta.Mov1.toArgb() to "Mov",
    Paleta.Albastru2.toArgb() to "Albastru",
    Paleta.Turcoaz1.toArgb() to "Turcoaz",
    Paleta.Verde3.toArgb() to "Verde",
)
enum class TrendScheme { POLLUTANTS, TEMPERATURE, HUMIDITY }

fun getMonthRoShort(month: Int) = arrayOf("", "ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec")[month]
fun getDayOfWeekRo(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.MONDAY -> "Lu"; DayOfWeek.TUESDAY -> "Ma"; DayOfWeek.WEDNESDAY -> "Mi"
    DayOfWeek.THURSDAY -> "Jo"; DayOfWeek.FRIDAY -> "Vi"; DayOfWeek.SATURDAY -> "Sâ"; DayOfWeek.SUNDAY -> "Du"
}

fun formatUptime(seconds: Long): String {
    val days = seconds / 86400; val hours = (seconds % 86400) / 3600; val mins = (seconds % 3600) / 60
    return if (days > 0L) "${days}d ${hours}h" else if (hours > 0L) "${hours}h ${mins}m" else "${mins}m"
}

fun parseIstoric(data: String?, isIndex: Boolean = false): List<Float> {
    if (data.isNullOrBlank()) return emptyList()
    return data.trim().split(",").mapNotNull {
        val v = it.trim().toFloatOrNull()
        if (v != null && isIndex && v > 1000f) -999f else v
    }
}

// Gradient continuu pe ancore (stil bara meteo): fiecare ancora (valoare -> culoare)
// marcheaza centrul unei benzi cu culoarea ei "pura"; intre ancore se interpoleaza
// liniar. Sub prima / peste ultima ancora, culoarea ramane fixa.
private fun gradientPeAncore(v: Float, ancore: List<Pair<Float, Color>>): Color {
    if (v <= ancore.first().first) return ancore.first().second
    if (v >= ancore.last().first) return ancore.last().second
    for (i in 0 until ancore.size - 1) {
        val (v2, c2) = ancore[i + 1]
        if (v <= v2) {
            val (v1, c1) = ancore[i]
            return androidx.compose.ui.graphics.lerp(c1, c2, ((v - v1) / (v2 - v1)).coerceIn(0f, 1f))
        }
    }
    return ancore.last().second
}

// Temperatura, adaptata intervalului dintr-un apartament (~16-30°C):
// sub 16 mov arctic, 16-18 albastru, 18-22 verde (ideal), 22-24 galben,
// 24-26 portocaliu, 26-30 rosu, 30+ rosu intens — cu gradient intre benzi.
private val ancoreTemperatura = listOf(
    16f to Paleta.Mov1,        // mov arctic (frig sever)
    18f to Paleta.Albastru2,   // albastru (racoare)
    20f to Paleta.Verde3,      // verde inchis (ideal racoros)
    21f to Paleta.Verde1,      // verde (confort)
    23f to Paleta.Galben1,     // galben (caldut)
    25f to Paleta.Portocaliu1, // portocaliu (cald)
    27f to Paleta.Rosu1        // rosu (prea cald)
)

fun getTempColor(v: Float): Color {
    if (v == -999f) return Color.Transparent
    return gradientPeAncore(v, ancoreTemperatura)
}

// Umiditatea, in stilul barei meteo (maro uscat -> verde -> turcoaz -> albastru inchis),
// centrata pe intervalul ideal dintr-un apartament (40-60% = verde).
private val ancoreUmiditate = listOf(
    30f to Paleta.Maro1,      // maro (aer foarte uscat)
    40f to Paleta.Verde2,     // verde deschis (acceptabil spre uscat)
    50f to Paleta.Verde1,     // verde (ideal 40-60%)
    60f to Paleta.Turcoaz1,   // turcoaz (usor umed)
    70f to Paleta.Albastru1,  // albastru (umed)
    80f to Paleta.Albastru3   // albastru inchis (excesiv)
)

fun getHumidColor(v: Float): Color {
    if (v == -999f) return Color.Transparent
    return gradientPeAncore(v, ancoreUmiditate)
}

// ================= GRADIENT POLUANTI (PM / CO2 / VOC / NOx) =================
// Aceeasi paleta de 4 culori pentru toti poluantii (verde curat -> rosu periculos),
// dar cu praguri (ancorele = valoare -> culoare) potrivite scarii fiecaruia.
// Ca sa ADAUGI mai multe ancore: pune inca o pereche "valoare to culoare" in lista
// poluantului respectiv (ex: 18f to galbenAer). Se interpoleaza automat intre ele.
private val verdeAer      = Paleta.Verde3       // curat
private val galbenAer     = Paleta.Galben1      // moderat
private val portocaliuAer = Paleta.Portocaliu1  // ridicat
private val rosuAer       = Paleta.Rosu1        // periculos

private fun gradientPoluant(v: Float, ancore: List<Pair<Float, Color>>): Color {
    if (v == -999f) return Color.Transparent
    return gradientPeAncore(v, ancore)
}

private val ancorePm1  = listOf(0f to verdeAer,   5f to galbenAer,   15f to portocaliuAer,   25f to rosuAer)
private val ancorePm25 = listOf(0f to verdeAer,  10f to galbenAer,   25f to portocaliuAer,   35f to rosuAer)
private val ancorePm4  = listOf(0f to verdeAer,  15f to galbenAer,   35f to portocaliuAer,   50f to rosuAer)
private val ancorePm10 = listOf(0f to verdeAer,  30f to galbenAer,   70f to portocaliuAer,  100f to rosuAer)
private val ancoreCo2  = listOf(450f to verdeAer, 1000f to galbenAer, 1500f to portocaliuAer, 2000f to rosuAer)
private val ancoreVoc  = listOf(100f to verdeAer, 200f to galbenAer,  325f to portocaliuAer,  450f to rosuAer)
private val ancoreNox  = listOf(0f to verdeAer,  85f to galbenAer,  225f to portocaliuAer,  400f to rosuAer)

fun getPm1Color(v: Float): Color  = gradientPoluant(v, ancorePm1)
fun getPm25Color(v: Float): Color = gradientPoluant(v, ancorePm25)
fun getPm4Color(v: Float): Color  = gradientPoluant(v, ancorePm4)
fun getPm10Color(v: Float): Color = gradientPoluant(v, ancorePm10)
fun getCo2Color(v: Float): Color  = gradientPoluant(v, ancoreCo2)
fun getVocColor(v: Float): Color  = gradientPoluant(v, ancoreVoc)
fun getNoxColor(v: Float): Color  = gradientPoluant(v, ancoreNox)

fun ascultaFirebase(db: DatabaseReference, child: String, type: Class<out Any>, onChange: (Any?) -> Unit): ValueEventListener {
    val listener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) { onChange(snapshot.getValue(type)) }
        override fun onCancelled(error: DatabaseError) { Log.e("Firebase", "Eroare $child: ${error.message}") }
    }
    db.child(child).addValueEventListener(listener)
    return listener
}

// ================= CALCUL TREND =================
@Composable
fun calculateTrend(currentRawValue: Float, updateTrigger: Long): Int {
    var lastValue by remember { mutableFloatStateOf(-1f) }
    var trend by remember { mutableIntStateOf(0) }
    LaunchedEffect(updateTrigger) {
        if (lastValue < 0f) {
            lastValue = currentRawValue
            return@LaunchedEffect
        }
        val diff = currentRawValue - lastValue
        trend = when {
            diff > 0.01f -> 1
            diff < -0.01f -> -1
            else -> 0
        }
        lastValue = currentRawValue
    }
    return trend
}

// ================= CUSTOM SLIDER =================
@Composable
fun SliderInteligentCustom(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    stepSize: Float = 0f,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    onDragStateChange: ((Boolean) -> Unit)? = null,
    accentColor: Color,
    textSecondary: Color,
    hasHoverStopZone: Boolean = false,
    stopZoneRange: ClosedFloatingPointRange<Float> = 1f..4f,
    isAuto: Boolean = false
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var latestValue by remember { mutableFloatStateOf(value) }
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    val thumbRadius = 14.dp
    val thumbRadiusPx = with(LocalDensity.current) { thumbRadius.toPx() }
    val trackHeight = 8.dp
    val trackHeightPx = with(LocalDensity.current) { trackHeight.toPx() }
    val trackColor = if (isAuto) textSecondary.copy(alpha = 0.15f) else textSecondary.copy(alpha = 0.25f)
    val activeTrackColor = if (isAuto) Color.Gray.copy(alpha = 0.5f) else accentColor

    fun calculateValue(xOffset: Float): Float {
        val trackWidth = canvasSize.width - 2 * thumbRadiusPx
        val clampedX = (xOffset - thumbRadiusPx).coerceIn(0f, trackWidth)
        val pct = clampedX / trackWidth
        var newValue = valueRange.start + pct * (valueRange.endInclusive - valueRange.start)
        if (stepSize > 0f) {
            val steps = ((newValue - valueRange.start) / stepSize).roundToInt()
            newValue = valueRange.start + steps * stepSize
        }
        return newValue.coerceIn(valueRange)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onDragStateChange?.invoke(true)
                    var newVal = calculateValue(down.position.x)
                    latestValue = newVal; onValueChange(newVal)
                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) break
                        change.consume()
                        newVal = calculateValue(change.position.x)
                        latestValue = newVal; onValueChange(newVal)
                    }
                    onDragStateChange?.invoke(false)
                    currentOnValueChangeFinished(latestValue)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            canvasSize = size
            val trackWidth = size.width - 2 * thumbRadiusPx
            val yCenter = size.height / 2f
            val endX = size.width - thumbRadiusPx
            drawLine(color = trackColor, start = Offset(thumbRadiusPx, yCenter), end = Offset(endX, yCenter), strokeWidth = trackHeightPx, cap = StrokeCap.Round)
            val pctCurent = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            val activeEndX = thumbRadiusPx + pctCurent * trackWidth
            if (pctCurent > 0f) drawLine(color = activeTrackColor, start = Offset(thumbRadiusPx, yCenter), end = Offset(activeEndX, yCenter), strokeWidth = trackHeightPx, cap = StrokeCap.Round)
            if (hasHoverStopZone) {
                val stopPctEnd = ((stopZoneRange.endInclusive - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                drawLine(color = Paleta.GriText, start = Offset(thumbRadiusPx, yCenter), end = Offset(thumbRadiusPx + stopPctEnd * trackWidth, yCenter), strokeWidth = trackHeightPx, cap = StrokeCap.Round)
            }
            drawCircle(color = Color.Black.copy(alpha = 0.1f), radius = thumbRadiusPx + 3f, center = Offset(activeEndX, yCenter + 4f))
            drawCircle(color = Color.White, radius = thumbRadiusPx, center = Offset(activeEndX, yCenter))
            drawCircle(color = Paleta.Alb2, radius = thumbRadiusPx, center = Offset(activeEndX, yCenter), style = Stroke(width = 2f))
        }
    }
}

// ================= WIDGET PROVIDER =================
// URL-ul bazei vine din secrete.properties prin BuildConfig (nimic privat hardcodat in sursa)
private const val WIDGET_DB_URL = BuildConfig.DB_URL

/**
 * Deseneaza un cadran rotund (arc de 260°) in stil Samsung One UI:
 * track subtil gri, arc colorat cu capete rotunjite, eticheta (alba) in centrul inelului
 * si valoarea mare (alba, bold) sub arc. value<0 / valString="--" = stare fara date.
 */
private fun creeazaCadranWidget(value: Float, max: Float, label: String, valString: String, colorInt: Int, etichetaVechime: String? = null, subscript: String? = null): android.graphics.Bitmap {
    val size = 320
    val bitmap = androidx.core.graphics.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    // Arc CIRCULAR (rect PATRAT!) in zona superioara; valoarea mare dedesubt — stil One UI.
    // (versiunea veche folosea un rect dreptunghiular -> arc turtit/eliptic, de-aia arata prost)
    val cx = size / 2f
    val arcCy = 152f
    val arcR = 130f     // inel mare, ca pe cadranele native One UI
    val strokeW = 40f   // grosimea inelului nativ One UI
    // Arc de 240° cu golul de 120° centrat jos (start 150°): mai scurt, ca sa nu se
    // atinga capetele de valoarea mare desenata dedesubt.
    val arcStart = 151.5f
    val arcSweep = 238f
    val rect = android.graphics.RectF(cx - arcR, arcCy - arcR, cx + arcR, arcCy + arcR)

    // Track subtil (inelul de fundal) — gri deschis, ca pe cadranele native One UI
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = strokeW
    paint.strokeCap = android.graphics.Paint.Cap.ROUND
    paint.color = Paleta.GriWidget.toArgb()
    canvas.drawArc(rect, arcStart, arcSweep, false, paint)

    // Arc colorat (progresul)
    paint.color = colorInt
    val sweep = (value / max).coerceIn(0f, 1f) * arcSweep
    if (sweep > 0f) canvas.drawArc(rect, arcStart, sweep, false, paint)

    // Eticheta (PM / CO2) in centrul inelului, cu subscript optional (PM₂.₅ / CO₂, stil One UI).
    // Alba, ca valoarea — mai vizibila si identica cu widgeturile native One UI.
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    paint.textSize = 72f
    val labelBaseline = arcCy - ((paint.descent() + paint.ascent()) / 2f) -9f
    if (subscript == null) {
        paint.textAlign = android.graphics.Paint.Align.CENTER
        canvas.drawText(label, cx, labelBaseline, paint)
    } else {
        // centram ansamblul "label + subscript" si desenam subscriptul mai mic, coborat
        paint.textAlign = android.graphics.Paint.Align.LEFT
        val latimeLabel = paint.measureText(label)
        paint.textSize = 44f
        val latimeSub = paint.measureText(subscript)
        paint.textSize = 72f
        val startX = cx - (latimeLabel + latimeSub) / 2f
        canvas.drawText(label, startX, labelBaseline, paint)
        paint.textSize = 44f
        canvas.drawText(subscript, startX + latimeLabel, labelBaseline + 20f, paint)
        paint.textAlign = android.graphics.Paint.Align.CENTER
    }

    // Indicator de vechime: cand cache-ul e vechi, varsta datelor apare in inel, sub eticheta
    if (etichetaVechime != null) {
        paint.color = Paleta.Gri1.toArgb()
        paint.textSize = 28f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        canvas.drawText(etichetaVechime, cx, arcCy + 52f, paint)
    }

    // Valoarea mare, sub arc
    paint.color = android.graphics.Color.WHITE
    paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    paint.textSize = 82f
    canvas.drawText(valString, cx, size - 19.5f, paint)

    return bitmap
}

/** Construieste RemoteViews-ul si actualizeaza toate instantele widgetului. pm25<0 / co2<0 = fara date (--). */
private fun randeazaWidget(context: Context, pm25: Float, co2: Int, varstaMinute: Long = 0L) {
    val mgr = AppWidgetManager.getInstance(context)
    val component = ComponentName(context, SmartPurifierWidget::class.java)
    if (mgr.getAppWidgetIds(component).isEmpty()) return

    val views = RemoteViews(context.packageName, R.layout.widget_purificator)
    val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
    val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

    // Date mai vechi de ~20 min (workerul ruleaza la 15) = probabil intrerupte: cadranele
    // devin gri si afiseaza varsta, ca sa nu para masuratori live valori de acum cateva ore.
    val esteVechi = varstaMinute >= 20
    val etichetaVechime = when {
        !esteVechi -> null
        varstaMinute < 60 -> "acum ${varstaMinute}m"
        varstaMinute < 1440 -> "acum ${varstaMinute / 60}h"
        else -> "acum ${varstaMinute / 1440}z"
    }

    val grayNoData = Paleta.GriText.toArgb()
    val arePm = pm25 >= 0f
    val areCo2 = co2 >= 0
    val pmColor  = if (!arePm || esteVechi)  grayNoData else when { pm25 < 5 -> Paleta.VerdeWidget.toArgb(); pm25 < 12 -> Paleta.GalbenWidget.toArgb(); pm25 < 35 -> Paleta.PortocaliuWidget.toArgb(); else -> Paleta.RosuWidget.toArgb() }
    val co2Color = if (!areCo2 || esteVechi) grayNoData else when { co2 < 800 -> Paleta.VerdeWidget.toArgb(); co2 < 1200 -> Paleta.GalbenWidget.toArgb(); co2 < 1600 -> Paleta.PortocaliuWidget.toArgb(); else -> Paleta.RosuWidget.toArgb() }

    // Sub 10 µg/m³ zecimala conteaza (5 vs 9 e o diferenta mare de calitate) — o afisam;
    // peste 10, valoarea intreaga e suficienta si tine cadranul aerisit.
    val pmText = if (!arePm) "--" else if (pm25 < 10f) "%.1f".format(pm25) else "%.0f".format(pm25)
    val pmBitmap  = creeazaCadranWidget(if (arePm) pm25 else 0f, 35f, "PM", pmText, pmColor, etichetaVechime, subscript = "2.5")
    val co2Bitmap = creeazaCadranWidget(if (areCo2) (co2 - 400f).coerceAtLeast(0f) else 0f, 1600f, "CO", if (areCo2) co2.toString() else "--", co2Color, etichetaVechime, subscript = "2")
    views.setImageViewBitmap(R.id.iv_widget_pm, pmBitmap)
    views.setImageViewBitmap(R.id.iv_widget_co2, co2Bitmap)
    views.setOnClickPendingIntent(R.id.iv_widget_pm, pendingIntent)
    views.setOnClickPendingIntent(R.id.iv_widget_co2, pendingIntent)
    mgr.updateAppWidget(component, views)
}

/** Salveaza ultimele valori in cache si redeseneaza widgetul (apelat cand sosesc date noi). */
private fun actualizeazaWidget(context: Context, pm25: Float, co2: Int) {
    context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit {
        putFloat("widgetPm25", pm25); putInt("widgetCo2", co2); putLong("widgetTs", System.currentTimeMillis())
    }
    randeazaWidget(context, pm25, co2)
}

/** Redeseneaza widgetul din ultimele valori salvate in cache (instant, fara retea). */
private fun randeazaWidgetDinCache(context: Context) {
    val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    val ts = prefs.getLong("widgetTs", 0L)
    if (ts <= 0L) { randeazaWidget(context, -1f, -1); return }
    val varstaMinute = ((System.currentTimeMillis() - ts) / 60000L).coerceAtLeast(0L)
    randeazaWidget(context, prefs.getFloat("widgetPm25", -1f), prefs.getInt("widgetCo2", -1), varstaMinute)
}

/** Best-effort: daca exista o sesiune Firebase activa, cere valori proaspete si actualizeaza widgetul. */
private fun cerereProaspataFirebase(context: Context) {
    try {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val db = FirebaseDatabase.getInstance(WIDGET_DB_URL).getReference("SenzoriAer")
        db.child("last_update").get().addOnSuccessListener { upSnap ->
            // Daca purificatorul nu mai trimite date, valorile din DB sunt vechi — nu le
            // stampilam ca proaspete; lasam cache-ul si indicatorul de vechime sa vorbeasca.
            val lastUpdate = (upSnap.value as? Number)?.toLong() ?: return@addOnSuccessListener
            if ((System.currentTimeMillis() - lastUpdate) / 1000 >= 90) { randeazaWidgetDinCache(context); return@addOnSuccessListener }
            db.child("PM25").get().addOnSuccessListener { pmSnap ->
                db.child("CO2").get().addOnSuccessListener { co2Snap ->
                    val pm = (pmSnap.value as? Number)?.toFloat()
                    val co2 = (co2Snap.value as? Number)?.toInt()
                    if (pm != null && co2 != null) actualizeazaWidget(context, pm, co2)
                }
            }
        }
    } catch (_: Exception) {}
}

class SmartPurifierWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Randam instant din cache (functioneaza si fara serviciu / fara retea, ex: dupa reboot),
        // apoi incercam best-effort o valoare proaspata din Firebase daca avem sesiune.
        randeazaWidgetDinCache(context)
        cerereProaspataFirebase(context)
    }
}

// ================= ALERTE PUSH (FCM) + VERIFICARE OFFLINE (WorkManager) =================
// Vechiul Foreground Service (dataSync) a fost eliminat: pe Android 15+ acest tip de
// serviciu este oprit fortat dupa ~6h/zi, deci monitorizarea 24/7 murea oricum.
// Noua arhitectura, cu consum de baterie aproape zero pe telefon:
//   - alertele de poluare sosesc ca notificari push FCM trimise DIRECT de ESP32
//     (ajung si cu aplicatia inchisa — sistemul trezeste aplicatia la primire);
//   - un worker periodic (WorkManager, ~15 min) verifica daca purificatorul mai
//     trimite date (alerta de offline) si improspateaza widgetul; ruleaza doar cu
//     internet si supravietuieste restartului telefonului (spre deosebire de FGS).

fun asiguraCanalAlerte(context: Context) {
    val canal = NotificationChannel("ALERTE_AER", "Alerte Calitate Aer", NotificationManager.IMPORTANCE_HIGH)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
}

fun arataNotificare(context: Context, titlu: String, mesaj: String) {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    asiguraCanalAlerte(context)
    val intentAplicatie = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntentAplicatie = android.app.PendingIntent.getActivity(
        context, System.currentTimeMillis().toInt(), intentAplicatie,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )
    val builder = NotificationCompat.Builder(context, "ALERTE_AER")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(titlu).setContentText(mesaj)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true).setContentIntent(pendingIntentAplicatie)
    NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
}

/** Aboneaza/dezaboneaza telefonul la alertele push si porneste/opreste verificarea periodica. */
fun configureazaAlerteFundal(context: Context, activ: Boolean) {
    val workManager = WorkManager.getInstance(context)
    if (activ) {
        FirebaseMessaging.getInstance().subscribeToTopic("alerte_aer")
        val cerere = PeriodicWorkRequestBuilder<VerificareOfflineWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork("verificare_offline", ExistingPeriodicWorkPolicy.UPDATE, cerere)
    } else {
        FirebaseMessaging.getInstance().unsubscribeFromTopic("alerte_aer")
        workManager.cancelUniqueWork("verificare_offline")
    }
}

class AlerteFcmService : FirebaseMessagingService() {
    override fun onMessageReceived(mesaj: RemoteMessage) {
        // Improspatam widgetul din payload-ul de date atasat alertei de catre ESP32
        val pm = mesaj.data["pm25"]?.toFloatOrNull()
        val co2 = mesaj.data["co2"]?.toIntOrNull()
        if (pm != null && co2 != null) actualizeazaWidget(this, pm, co2)
        // In fundal, mesajele de tip "notification" sunt afisate automat de sistem;
        // cand aplicatia e in prim-plan ele ajung aici si trebuie afisate manual.
        mesaj.notification?.let { n -> arataNotificare(this, n.title ?: "Smart Air", n.body ?: "") }
    }

    override fun onNewToken(token: String) {
        // Folosim topicuri (nu tokenuri individuale) — abonamentul se reface automat la token nou.
    }
}

/** Asteapta un Task Play Services intr-o corutina, cu propagarea erorii. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.asteapta(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

/**
 * Citire FRESH din RTDB prin REST (`https://.../cale.json?auth=token`). Ocolește COMPLET
 * cache-ul de persistență al SDK-ului — cauza reală a alertelor false de offline: `get()`-ul
 * SDK putea întoarce un `last_update` vechi din cache (re-sincronizarea după reconectare nu se
 * termină garantat înainte ca get() să se rezolve). REST întoarce mereu valoarea curentă de pe
 * server. Returnează corpul răspunsului ca text (ex. "1718900000000"), sau null la orice eroare.
 */
private fun rtdbRestGet(cale: String, token: String): String? {
    val url = URL("${WIDGET_DB_URL.trimEnd('/')}/$cale.json?auth=${URLEncoder.encode(token, "UTF-8")}")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
    }
    return try {
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() }.trim() else null
    } catch (_: Exception) { null } finally { conn.disconnect() }
}

class VerificareOfflineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) { randeazaWidgetDinCache(ctx); return@withContext Result.success() }

            // Token de autentificare (cache-uit, se reînnoiește singur dacă a expirat).
            val token = withTimeout(10000) { user.getIdToken(false).asteapta().token }
            if (token == null) { randeazaWidgetDinCache(ctx); return@withContext Result.success() }

            // Citiri FRESH prin REST — niciun cache SDK implicat. Dacă nu avem rețea/răspuns,
            // last_update e null → NU dăm niciun verdict (nu acuzăm placa pentru rețeaua noastră).
            val lastUpdate = rtdbRestGet("SenzoriAer/last_update", token)?.toLongOrNull()
            if (lastUpdate == null) { randeazaWidgetDinCache(ctx); return@withContext Result.success() }
            val pm = rtdbRestGet("SenzoriAer/PM25", token)?.toFloatOrNull()
            val co2 = rtdbRestGet("SenzoriAer/CO2", token)?.toIntOrNull()

            val secundeVechi = (System.currentTimeMillis() - lastUpdate) / 1000
            val prevLastUpdate = prefs.getLong("workerLastUpdate", 0L)
            val aAvansat = lastUpdate > prevLastUpdate   // placa a scris de la ultima verificare → e VIE

            // Offline DOAR dacă timestamp-ul e ȘI vechi (>90 s), ȘI nu a avansat față de verificarea
            // precedentă. A doua condiție elimină orice fals-pozitiv: o citire care a avansat = sigur
            // online (indiferent de ceasul telefonului); doar un timestamp efectiv ÎNGHEȚAT între două
            // verificări (la 15 min) declanșează alerta. Pe prima rulare (prev=0) nu alertăm.
            val esteOffline = secundeVechi >= 90 && !aAvansat && prevLastUpdate > 0L
            val eraOffline = prefs.getBoolean("workerEraOffline", false)

            if (esteOffline && !eraOffline && prefs.getBoolean("notifOffline", true)) {
                val deCat = if (secundeVechi < 3600) "${secundeVechi / 60} minute" else "${secundeVechi / 3600} ore"
                arataNotificare(ctx, "⚠️ Dispozitiv Offline", "Purificatorul nu a mai trimis date de $deCat!")
            }
            prefs.edit { putLong("workerLastUpdate", lastUpdate); putBoolean("workerEraOffline", esteOffline) }

            if (!esteOffline && pm != null && co2 != null) actualizeazaWidget(ctx, pm, co2)
            else randeazaWidgetDinCache(ctx)
        } catch (_: Exception) {
            randeazaWidgetDinCache(ctx)
        }
        Result.success()
    }
}

// ================= ACTIVITY PRINCIPAL =================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Garda de configuratie: fara secrete.properties completat, afisam instructiuni
        // clare in loc sa crapam cu un URL gol la prima initializare Firebase.
        if (BuildConfig.DB_URL.isEmpty() || BuildConfig.FB_EMAIL.isEmpty() || BuildConfig.FB_PASS.isEmpty()) {
            setContent {
                MaterialTheme {
                    Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                        Text(
                            "Configurație lipsă!\n\nCopiază secrete.properties.example ca secrete.properties, completează smartair.dbUrl / smartair.fbEmail / smartair.fbPass și refă build-ul.",
                            color = Paleta.Rosu1, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
            return
        }
        try { FirebaseDatabase.getInstance().setPersistenceEnabled(true) } catch (_: Exception) {}
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.email != BuildConfig.FB_EMAIL) {
            auth.signOut()
            auth.signInWithEmailAndPassword(BuildConfig.FB_EMAIL, BuildConfig.FB_PASS)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        pornireAplicatie()
                    } else {
                        val mesajEroare = task.exception?.message ?: "Eroare necunoscută la rețea."
                        Log.e("Firebase", "Eroare autentificare: $mesajEroare")
                        setContent {
                            MaterialTheme {
                                Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                                    Text(
                                        "Eroare Firebase:\n\n$mesajEroare\n\nVerifică datele din secrete.properties și contul din consola Firebase!",
                                        color = Paleta.Rosu1, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
        } else {
            pornireAplicatie()
        }
    }

    private fun pornireAplicatie() {
        asiguraCanalAlerte(this)
        // canalul vechiului serviciu permanent nu mai e folosit — il scoatem din setarile sistemului
        getSystemService(NotificationManager::class.java).deleteNotificationChannel("SERVICIU_FUNDAL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        configureazaAlerteFundal(this, prefs.getBoolean("runInBackground", true))
        setContent { MaterialTheme { PurificatorAppMain() } }
    }
}

// ================= COMPONENTA PRINCIPALA =================
@Composable
fun PurificatorAppMain() {
    val context = LocalContext.current
    val database = FirebaseDatabase.getInstance(WIDGET_DB_URL).getReference("SenzoriAer")
    val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val liveModeJob = remember { mutableStateOf<Job?>(null) }

    var isDarkMode by remember { mutableStateOf(prefs.getBoolean("isDarkMode", true)) }
    var accentColor by remember { mutableStateOf(Color(prefs.getInt("themeColor", Paleta.Albastru2.toArgb()))) }
    var tipGrafic by remember { mutableIntStateOf(prefs.getInt("tipGrafic", 0)) }
    var runInBackground by remember { mutableStateOf(prefs.getBoolean("runInBackground", true)) }
    var categoryOrder by remember { mutableStateOf((prefs.getString("catOrder", "0,1,2") ?: "0,1,2").split(",").mapNotNull { it.toIntOrNull() }) }

    val bgMain = if (isDarkMode) Paleta.Negru2 else Paleta.Alb1
    val surfaceColor = if (isDarkMode) Paleta.Gri4 else Color.White
    val textPrimary = if (isDarkMode) Color.White else Color.Black
    val textSecondary = if (isDarkMode) Color.Gray else Color.DarkGray
    val dividerColor = if (isDarkMode) Color.DarkGray else Paleta.Alb3

    var tabSelectat by remember { mutableIntStateOf(0) }
    var intervalSelectat by remember { mutableStateOf(IntervalTimp.ORE_12) }

    var initLoadDone by remember { mutableStateOf(false) }
    var ultimulUpdateServer by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var diferentaSecunde by remember { mutableLongStateOf(0L) }
    var lastHistoryTimeUnix by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }

    var rpmServer by remember { mutableIntStateOf(0) }
    var speedControlServer by remember { mutableFloatStateOf(0f) }
    var procentVentilatorServer by remember { mutableIntStateOf(0) }

    var telemetrieRam by remember { mutableIntStateOf(0) }
    var telemetrieTemp by remember { mutableFloatStateOf(0f) }
    var telemetrieRssi by remember { mutableIntStateOf(0) }
    var telemetrieUptime by remember { mutableLongStateOf(0L) }
    var tempOffsetServer by remember { mutableFloatStateOf(0f) }
    var luminozitateServer by remember { mutableIntStateOf(100) }
    var luminozitateLedServer by remember { mutableIntStateOf(150) }

    var nightModeApp by remember { mutableStateOf(false) }
    var liveModeServer by remember { mutableStateOf(false) }
    var notifPraf by remember { mutableStateOf(true) }
    var notifGaze by remember { mutableStateOf(true) }
    var notifOffline by remember { mutableStateOf(true) }
    var errorLogServer by remember { mutableStateOf("") }

    var pm1 by remember { mutableFloatStateOf(0f) }; var pm25 by remember { mutableFloatStateOf(0f) }
    var pm4 by remember { mutableFloatStateOf(0f) }; var pm10 by remember { mutableFloatStateOf(0f) }
    var co2 by remember { mutableIntStateOf(0) }; var voc by remember { mutableFloatStateOf(0f) }
    var nox by remember { mutableFloatStateOf(0f) }; var temp by remember { mutableFloatStateOf(0f) }
    var umiditate by remember { mutableFloatStateOf(0f) }

    var istoricPM1 by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricPM25 by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricPM4 by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricPM10 by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricCO2 by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricVOC by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricNOx by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricTemp by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricUmid by remember { mutableStateOf<List<Float>>(emptyList()) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START,
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    liveModeJob.value?.cancel(); liveModeJob.value = null
                    // Reconectare fortata la revenirea din background. IMPORTANT: operam pe ACEEASI
                    // instanta ca listenerii (getInstance(WIDGET_DB_URL)) — pe instanta implicita,
                    // daca URL-ul difera fie si printr-un slash, am reconecta alt obiect si ecranul
                    // ar ramane pe date vechi pana la restart. goOnline() singur e no-op cand clientul
                    // CREDE ca e online (socket "zombie" inghetat de Android), deci cand datele sunt
                    // invechite rupem explicit conexiunea (goOffline) inainte sa o refacem.
                    val db = FirebaseDatabase.getInstance(WIDGET_DB_URL)
                    if (initLoadDone && (System.currentTimeMillis() - ultimulUpdateServer) / 1000 > 20) {
                        db.goOffline()
                    }
                    db.goOnline()
                    database.child("setari/live_mode").setValue(true)
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP,
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    if (!prefs.getBoolean("liveModePermanent", false)) {
                        liveModeJob.value?.cancel()
                        liveModeJob.value = coroutineScope.launch {
                            delay(10000)
                            database.child("setari/live_mode").setValue(false)
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ultimulUpdateServer, initLoadDone) {
        while (true) {
            if (initLoadDone) diferentaSecunde = (System.currentTimeMillis() - ultimulUpdateServer) / 1000
            delay(1000L)
        }
    }

    DisposableEffect(Unit) {
        val activeListeners = mutableListOf<Pair<String, ValueEventListener>>()
        fun asculta(child: String, type: Class<out Any>, onChange: (Any?) -> Unit) {
            val listener = ascultaFirebase(database, child, type, onChange)
            activeListeners.add(child to listener)
        }

        asculta("last_update", Long::class.java) { (it as? Long)?.let { v -> ultimulUpdateServer = v; initLoadDone = true } }
        asculta("last_history_time", Long::class.java) { (it as? Long)?.let { v -> lastHistoryTimeUnix = v } }
        asculta("RPM", Int::class.java) { (it as? Int)?.let { v -> rpmServer = v } }
        asculta("setari/speed", Int::class.java) { (it as? Int)?.let { v -> speedControlServer = v.toFloat() } }
        asculta("ProcentVentilator", Int::class.java) { (it as? Int)?.let { v -> procentVentilatorServer = v } }
        asculta("Telemetrie_RAM_Liber", Int::class.java) { (it as? Int)?.let { v -> telemetrieRam = v } }
        asculta("Telemetrie_Temp_Core", Double::class.java) { (it as? Double)?.let { v -> telemetrieTemp = v.toFloat() } }
        asculta("Telemetrie_WiFi_RSSI", Int::class.java) { (it as? Int)?.let { v -> telemetrieRssi = v } }
        asculta("Telemetrie_Uptime_Sec", Long::class.java) { (it as? Long)?.let { v -> telemetrieUptime = v } }
        asculta("Setari_Offset_Temp", Double::class.java) { (it as? Double)?.let { v -> tempOffsetServer = v.toFloat() } }
        asculta("setari/luminozitate", Int::class.java) { (it as? Int)?.let { v -> luminozitateServer = v } }
        asculta("setari/luminozitate_led", Int::class.java) { (it as? Int)?.let { v -> luminozitateLedServer = v } }
        asculta("setari/night_mode", Boolean::class.java) { (it as? Boolean)?.let { v -> nightModeApp = v } }
        asculta("setari/live_mode", Boolean::class.java) { (it as? Boolean)?.let { v -> liveModeServer = v } }
        // Pragurile/comutatoarele de alerte stau acum sub /setari ca sa le citeasca si ESP32
        // (el trimite alertele push); notif_offline ramane doar pe telefon (workerul îl
        // citeste din prefs, de aceea îl oglindim la fiecare schimbare).
        asculta("setari/notif_praf", Boolean::class.java) { (it as? Boolean)?.let { v -> notifPraf = v } }
        asculta("setari/notif_gaze", Boolean::class.java) { (it as? Boolean)?.let { v -> notifGaze = v } }
        asculta("app/notif_offline", Boolean::class.java) { (it as? Boolean)?.let { v -> notifOffline = v; prefs.edit { putBoolean("notifOffline", v) } } }
        asculta("ErrorLog", String::class.java) { (it as? String)?.let { v -> errorLogServer = v } }
        asculta("PM1", Double::class.java) { (it as? Double)?.let { v -> pm1 = v.toFloat() } }
        // Widgetul se actualizeaza DOAR cand datele sunt plauzibil live (last_update recent):
        // la pornire, persistenta Firebase livreaza intai valorile vechi din cache, iar fara
        // gating le-am stampila ca "proaspete" si indicatorul de vechime ar minti.
        asculta("PM25", Double::class.java) { (it as? Double)?.let { v -> pm25 = v.toFloat(); if ((System.currentTimeMillis() - ultimulUpdateServer) / 1000 < 90) actualizeazaWidget(context, pm25, co2) } }
        asculta("PM4", Double::class.java) { (it as? Double)?.let { v -> pm4 = v.toFloat() } }
        asculta("PM10", Double::class.java) { (it as? Double)?.let { v -> pm10 = v.toFloat() } }
        asculta("CO2", Int::class.java) { (it as? Int)?.let { v -> co2 = v; if ((System.currentTimeMillis() - ultimulUpdateServer) / 1000 < 90) actualizeazaWidget(context, pm25, co2) } }
        asculta("VOC", Double::class.java) { (it as? Double)?.let { v -> voc = v.toFloat() } }
        asculta("NOx", Double::class.java) { (it as? Double)?.let { v -> nox = v.toFloat() } }
        asculta("Temperatura", Double::class.java) { (it as? Double)?.let { v -> temp = v.toFloat() } }
        asculta("Umiditate", Double::class.java) { (it as? Double)?.let { v -> umiditate = v.toFloat() } }
        asculta("Istoric_PM1", String::class.java) { istoricPM1 = parseIstoric(it as? String) }
        asculta("Istoric_PM25", String::class.java) { istoricPM25 = parseIstoric(it as? String) }
        asculta("Istoric_PM4", String::class.java) { istoricPM4 = parseIstoric(it as? String) }
        asculta("Istoric_PM10", String::class.java) { istoricPM10 = parseIstoric(it as? String) }
        asculta("Istoric_CO2", String::class.java) { istoricCO2 = parseIstoric(it as? String) }
        asculta("Istoric_Temp", String::class.java) { istoricTemp = parseIstoric(it as? String) }
        asculta("Istoric_Umid", String::class.java) { istoricUmid = parseIstoric(it as? String) }
        asculta("Istoric_VOC", String::class.java) { istoricVOC = parseIstoric(it as? String, true) }
        asculta("Istoric_NOx", String::class.java) { istoricNOx = parseIstoric(it as? String, true) }

        onDispose {
            activeListeners.forEach { (child, listener) -> database.child(child).removeEventListener(listener) }
        }
    }

    // Sincronizam o data pe sesiune setarile detinute de telefon catre placa (praguri de
    // alerta, fereastra Night Mode): ESP32 le citeste din /setari si actioneaza singur —
    // asa primesc valori si instalarile existente, nu doar cele care re-salveaza din dialoguri.
    LaunchedEffect(Unit) {
        database.child("setari/alert_pm").setValue(prefs.getFloat("alertPm", 20f).toDouble())
        database.child("setari/alert_co2").setValue(prefs.getFloat("alertCo2", 1600f).toInt())
        database.child("setari/alert_voc").setValue(prefs.getFloat("alertVoc", 250f).toDouble())
        database.child("setari/alert_nox").setValue(prefs.getFloat("alertNox", 30f).toDouble())
        database.child("setari/notif_freq_min").setValue(prefs.getInt("notifFreqMins", 60))
        database.child("setari/nm_auto").setValue(prefs.getBoolean("nightModeAuto", false))
        database.child("setari/nm_start").setValue(prefs.getInt("nmStartHr", 23) * 60 + prefs.getInt("nmStartMin", 0))
        database.child("setari/nm_end").setValue(prefs.getInt("nmEndHr", 8) * 60 + prefs.getInt("nmEndMin", 0))
    }

    Scaffold(
        containerColor = bgMain,
        bottomBar = {
            NavigationBar(containerColor = surfaceColor, modifier = Modifier.height(60.dp)) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, null) }, selected = tabSelectat == 0,
                    onClick = { tabSelectat = 0 },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor, unselectedIconColor = textSecondary, indicatorColor = if (isDarkMode) Paleta.Gri3 else Paleta.Alb2)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.DateRange, null) }, selected = tabSelectat == 1,
                    onClick = { tabSelectat = 1 },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor, unselectedIconColor = textSecondary, indicatorColor = if (isDarkMode) Paleta.Gri3 else Paleta.Alb2)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, null) }, selected = tabSelectat == 2,
                    onClick = { tabSelectat = 2 },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor, unselectedIconColor = textSecondary, indicatorColor = if (isDarkMode) Paleta.Gri3 else Paleta.Alb2)
                )
            }
        }
    ) { paddingValori ->
        Box(modifier = Modifier.padding(paddingValori).fillMaxSize()) {
            when (tabSelectat) {
                0 -> EcranAcasa(
                    prefs = prefs,
                    categoryOrder = categoryOrder,
                    onOrderChange = { newOrd ->
                        categoryOrder = newOrd
                        prefs.edit(commit = true) { putString("catOrder", newOrd.joinToString(",")) }
                    },
                    initLoadDone = initLoadDone,
                    ultimulUpdateServer = ultimulUpdateServer,
                    diferentaSecunde = diferentaSecunde,
                    lastHistoryTimeUnix = lastHistoryTimeUnix,
                    speedControlServer = speedControlServer,
                    procentVentilatorServer = procentVentilatorServer,
                    rpmServer = rpmServer,
                    intervalSelectat = intervalSelectat,
                    pm1 = pm1, pm25 = pm25, pm4 = pm4, pm10 = pm10,
                    co2 = co2, voc = voc, nox = nox, temp = temp, umiditate = umiditate,
                    istoricPM1 = istoricPM1, istoricPM25 = istoricPM25, istoricPM4 = istoricPM4, istoricPM10 = istoricPM10,
                    istoricCO2 = istoricCO2, istoricVOC = istoricVOC, istoricNOx = istoricNOx, istoricTemp = istoricTemp, istoricUmid = istoricUmid,
                    accentColor = accentColor, textPrimary = textPrimary, textSecondary = textSecondary, surfaceColor = surfaceColor, tipGrafic = tipGrafic,
                    onIntervalChange = { intervalSelectat = it },
                    onSpeedConfirmed = { database.child("setari/speed").setValue(it.toInt()) }
                )
                1 -> EcranArhiva(
                    prefs = prefs,
                    categoryOrder = categoryOrder,
                    onOrderChange = { newOrd ->
                        categoryOrder = newOrd
                        prefs.edit(commit = true) { putString("catOrder", newOrd.joinToString(",")) }
                    },
                    database = database,
                    accentColor = accentColor, textPrimary = textPrimary, textSecondary = textSecondary,
                    surfaceColor = surfaceColor, dividerColor = dividerColor, tipGrafic = tipGrafic
                )
                2 -> EcranSetari(
                    context = context, prefs = prefs, database = database, nightModeActiv = nightModeApp, liveModeActiv = liveModeServer,
                    notifPraf = notifPraf, notifGaze = notifGaze, notifOffline = notifOffline, errorLogString = errorLogServer,
                    runInBackground = runInBackground, isDarkMode = isDarkMode, accentColor = accentColor, tipGrafic = tipGrafic,
                    textPrimary = textPrimary, textSecondary = textSecondary, surfaceColor = surfaceColor,
                    istoricExportPM = istoricPM25, istoricExportCO2 = istoricCO2, dividerColor = dividerColor,
                    telemetrieRam = telemetrieRam, telemetrieTemp = telemetrieTemp, telemetrieRssi = telemetrieRssi,
                    telemetrieUptime = telemetrieUptime, tempOffsetServer = tempOffsetServer,
                    luminozitateServer = luminozitateServer, luminozitateLedServer = luminozitateLedServer,
                    onNightModeChange = { database.child("setari/night_mode").setValue(it) },
                    onLuminozitateChange = { database.child("setari/luminozitate").setValue(it) },
                    onLuminozitateLedChange = { database.child("setari/luminozitate_led").setValue(it) },
                    onLiveModeChange = { database.child("setari/live_mode").setValue(it) },
                    onNotifPrafChange = { database.child("setari/notif_praf").setValue(it) },
                    onNotifGazeChange = { database.child("setari/notif_gaze").setValue(it) },
                    onNotifOfflineChange = { database.child("app/notif_offline").setValue(it) },
                    onRunInBackgroundChange = { v ->
                        runInBackground = v
                        prefs.edit(commit = true) { putBoolean("runInBackground", v) }
                        configureazaAlerteFundal(context, v)
                    },
                    onThemeChange = { v -> isDarkMode = v; prefs.edit(commit = true) { putBoolean("isDarkMode", v) } },
                    onColorChange = { v -> accentColor = Color(v); prefs.edit(commit = true) { putInt("themeColor", v) } },
                    onTipGraficChange = { v -> tipGrafic = v; prefs.edit(commit = true) { putInt("tipGrafic", v) } },
                    onCleanSensor = { database.child("setari/curatare").setValue(true) },
                    onRestartBoard = { database.child("setari/restart").setValue(true) },
                    onTempOffsetChange = { v -> database.child("setari/temp_offset").setValue((v * 10f).roundToInt() / 10.0) },
                    onResetVoc = { database.child("setari/reset_voc").setValue(true) },
                    onCalibrareCo2 = { database.child("setari/calibrare_co2").setValue(true) }
                )
            }
        }
    }
}

// ================= ECRAN SCOR ZILNIC — CALCUL ÎMBUNĂTĂȚIT =================
/**
 * Scor de calitate a aerului pe ultimele 24h (0-100), reproiectat ca să reflecte expunerea REALĂ:
 *
 * 1. Sub-scor non-liniar (curbe tip EPA) per poluant, calculat la FIECARE probă de 5 min — nu pe
 *    media zilei. Astfel vârfurile scurte de poluare contează (media valorilor le-ar ascunde).
 * 2. La fiecare moment: scor = 50% "poluantul cel mai slab" + 50% media ponderată
 *    (PM2.5 0.40, CO2 0.30, VOC 0.20, NOx 0.10) — un singur poluant prost trage scorul în jos.
 * 3. Scorul zilei = 70% media tuturor probelor + 30% media celor mai proaste ~10% probe (~2.4h) —
 *    penalizează perioadele proaste fără ca un vârf izolat să distrugă toată ziua.
 * 4. Datele lipsă (-999) sau indecșii invalizi sunt ignorați; ponderile se renormalizează singure.
 * 5. Fără date suficiente -> -1 (UI afișează "—").
 */
@Composable
fun CardScorZilnic(
    istoricPM25: List<Float>,
    istoricCO2: List<Float>,
    istoricVOC: List<Float>,
    istoricNOx: List<Float>,
    surfaceColor: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    fun calcSubScorPm(avg: Float): Float {
        // EPA PM2.5 breakpoints: 0-12 excelent, 12-35 moderat, 35-55 nesănătos sensibili, 55+ nesănătos
        return when {
            avg <= 0f -> 100f
            avg <= 12f -> 100f - (avg / 12f) * 10f               // 100→90
            avg <= 35f -> 90f - ((avg - 12f) / 23f) * 35f        // 90→55
            avg <= 55f -> 55f - ((avg - 35f) / 20f) * 35f        // 55→20
            else -> (20f - ((avg - 55f) / 10f) * 10f).coerceAtLeast(0f)
        }
    }

    fun calcSubScorCo2(avg: Float): Float {
        return when {
            avg <= 600f -> 100f
            avg <= 800f -> 100f - ((avg - 600f) / 200f) * 15f    // 100→85
            avg <= 1000f -> 85f - ((avg - 800f) / 200f) * 25f   // 85→60
            avg <= 1500f -> 60f - ((avg - 1000f) / 500f) * 40f  // 60→20
            else -> (20f - ((avg - 1500f) / 500f) * 20f).coerceAtLeast(0f)
        }
    }

    fun calcSubScorVoc(avg: Float): Float {
        return when {
            avg <= 100f -> 100f
            avg <= 150f -> 100f - ((avg - 100f) / 50f) * 15f     // 100→85
            avg <= 250f -> 85f - ((avg - 150f) / 100f) * 30f     // 85→55
            avg <= 400f -> 55f - ((avg - 250f) / 150f) * 35f     // 55→20
            else -> (20f - ((avg - 400f) / 100f) * 20f).coerceAtLeast(0f)
        }
    }

    fun calcSubScorNox(avg: Float): Float {
        return when {
            avg <= 20f -> 100f
            avg <= 50f -> 100f - ((avg - 20f) / 30f) * 20f       // 100→80
            avg <= 150f -> 80f - ((avg - 50f) / 100f) * 40f      // 80→40
            avg <= 300f -> 40f - ((avg - 150f) / 150f) * 30f     // 40→10
            else -> (10f - ((avg - 300f) / 100f) * 10f).coerceAtLeast(0f)
        }
    }

    // -1 = date insuficiente; altfel 0..100 (vezi descrierea algoritmului de mai sus).
    val scorFinal = remember(istoricPM25, istoricCO2, istoricVOC, istoricNOx) {
        val n = maxOf(istoricPM25.size, istoricCO2.size, istoricVOC.size, istoricNOx.size)
        if (n == 0) return@remember -1

        val instScores = ArrayList<Float>(n)
        for (i in 0 until n) {
            var sumaW = 0f; var sumaSW = 0f; var worst = Float.MAX_VALUE
            fun adauga(s: Float, w: Float) { sumaSW += s * w; sumaW += w; if (s < worst) worst = s }

            istoricPM25.getOrNull(i)?.let { if (it != -999f && it >= 0f) adauga(calcSubScorPm(it),  0.40f) }
            istoricCO2.getOrNull(i)?.let  { if (it != -999f && it >= 0f) adauga(calcSubScorCo2(it), 0.30f) }
            istoricVOC.getOrNull(i)?.let  { if (it in 1f..500f)          adauga(calcSubScorVoc(it), 0.20f) }
            istoricNOx.getOrNull(i)?.let  { if (it in 1f..500f)          adauga(calcSubScorNox(it), 0.10f) }

            // La fiecare moment: 50% cel mai slab poluant + 50% media ponderată a celor disponibili
            if (sumaW > 0f) instScores.add(0.5f * worst + 0.5f * (sumaSW / sumaW))
        }

        if (instScores.isEmpty()) return@remember -1

        val medieZi = instScores.average().toFloat()
        // Media celor mai proaste ~10% probe (cel puțin 1) — captează perioadele de poluare
        val sortate = instScores.sorted()
        val nrProaste = maxOf(1, kotlin.math.ceil(instScores.size * 0.10f).toInt())
        val medieProaste = sortate.take(nrProaste).average().toFloat()

        // 70% condiții tipice + 30% cele mai proaste perioade
        (0.70f * medieZi + 0.30f * medieProaste).coerceIn(0f, 100f).roundToInt()
    }

    val areScor = scorFinal >= 0
    val colorScor = when {
        !areScor -> textSecondary
        scorFinal >= 90 -> Paleta.Verde3   // Verde
        scorFinal >= 75 -> Paleta.Verde1   // Verde-gălbui
        scorFinal >= 60 -> Paleta.Galben1   // Galben
        scorFinal >= 40 -> Paleta.Portocaliu1   // Portocaliu
        else -> Paleta.Rosu1              // Roșu
    }
    val mesaj = when {
        !areScor -> "Se colectează date..."
        scorFinal >= 90 -> "Aer Excelent azi"
        scorFinal >= 75 -> "Aer Bun"
        scorFinal >= 60 -> "Calitate Medie, mai aerisește"
        scorFinal >= 40 -> "Calitate Scăzută"
        else -> "Poluare Ridicată!"
    }

    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(surfaceColor).border(1.dp, if (surfaceColor == Color.White) Paleta.Alb3 else Color.Transparent, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(color = colorScor.copy(alpha = 0.2f), startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                if (areScor) drawArc(color = colorScor, startAngle = -90f, sweepAngle = (scorFinal / 100f) * 360f, useCenter = false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            }
            Text(if (areScor) "$scorFinal" else "—", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Calitate Aer (Ultimele 24h)", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(mesaj, color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ================= DRAG & DROP — REPARAT =================
/**
 * Fix-uri față de versiunea anterioară:
 * 1. dragOffsetY e acum un MutableState separat per categorie prin offset local în closure
 * 2. Swap-ul se face prin index direct, fără calcul repetat al indexOf (care poate fi -1)
 * 3. Threshold-ul e calculat o singură dată în px, nu în fiecare frame
 * 4. onDragEnd/onDragCancel resetează ÎNTOTDEAUNA offset-ul la 0, chiar și dacă un swap a eșuat
 */
@Composable
fun DraggableCategoryGroup(
    prefs: SharedPreferences,
    surfaceColor: Color,
    textSecondary: Color,
    categoryOrder: List<Int>,
    onOrderChange: (List<Int>) -> Unit,
    contentPraf: @Composable () -> Unit,
    contentGaze: @Composable () -> Unit,
    contentClima: @Composable () -> Unit
) {
    var expPraf by remember { mutableStateOf(prefs.getBoolean("expPraf", true)) }
    var expGaze by remember { mutableStateOf(prefs.getBoolean("expGaze", true)) }
    var expClima by remember { mutableStateOf(prefs.getBoolean("expClima", true)) }

    // Un singur state pentru ID-ul tras și offset-ul Y — resetate împreună
    var draggedCatId by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val swapThresholdDp = 56.dp
    val density = LocalDensity.current
    val swapThresholdPx = remember(density) { with(density) { swapThresholdDp.toPx() } }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        categoryOrder.forEachIndexed { index, catId ->
            key(catId) {
                val isDragged = draggedCatId == catId
                val titlu = when (catId) { 0 -> "PARTICULE (µg/m³)"; 1 -> "GAZE ȘI CHIMICALE"; else -> "CLIMĂ" }
                val isExp = when (catId) { 0 -> expPraf; 1 -> expGaze; else -> expClima }

                val toggleExpand: () -> Unit = {
                    when (catId) {
                        0 -> { expPraf = !expPraf; prefs.edit(commit = true) { putBoolean("expPraf", expPraf) } }
                        1 -> { expGaze = !expGaze; prefs.edit(commit = true) { putBoolean("expGaze", expGaze) } }
                        2 -> { expClima = !expClima; prefs.edit(commit = true) { putBoolean("expClima", expClima) } }
                    }
                }

                Box(
                    modifier = Modifier
                        .zIndex(if (isDragged) 2f else 0f)
                        .offset { IntOffset(0, if (isDragged) dragOffsetY.roundToInt() else 0) }
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(surfaceColor)
                                .border(1.dp, if (surfaceColor == Color.White) Paleta.Alb3 else Color.Transparent, RoundedCornerShape(8.dp))
                                .pointerInput(catId, index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedCatId = catId
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            draggedCatId = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggedCatId = null
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val newOffset = dragOffsetY + dragAmount.y
                                            val currentIndex = categoryOrder.indexOf(catId)
                                            if (currentIndex < 0) return@detectDragGesturesAfterLongPress

                                            when {
                                                newOffset > swapThresholdPx && currentIndex < categoryOrder.size - 1 -> {
                                                    val mutList = categoryOrder.toMutableList()
                                                    val tmp = mutList[currentIndex]
                                                    mutList[currentIndex] = mutList[currentIndex + 1]
                                                    mutList[currentIndex + 1] = tmp
                                                    onOrderChange(mutList)
                                                    // Resetăm offsetul la diferența rămasă după swap
                                                    dragOffsetY = newOffset - swapThresholdPx
                                                }
                                                newOffset < -swapThresholdPx && currentIndex > 0 -> {
                                                    val mutList = categoryOrder.toMutableList()
                                                    val tmp = mutList[currentIndex]
                                                    mutList[currentIndex] = mutList[currentIndex - 1]
                                                    mutList[currentIndex - 1] = tmp
                                                    onOrderChange(mutList)
                                                    dragOffsetY = newOffset + swapThresholdPx
                                                }
                                                else -> {
                                                    dragOffsetY = newOffset
                                                }
                                            }
                                        }
                                    )
                                }
                                .clickable { if (draggedCatId == null) toggleExpand() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(titlu, color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Icon(
                                imageVector = if (isExp) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isExp) "Restrânge" else "Extinde",
                                tint = textSecondary
                            )
                        }
                        AnimatedVisibility(visible = isExp) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                when (catId) {
                                    0 -> contentPraf()
                                    1 -> contentGaze()
                                    2 -> contentClima()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================= TAB 1: DASHBOARD =================
@Composable
fun EcranAcasa(
    prefs: SharedPreferences, categoryOrder: List<Int>, onOrderChange: (List<Int>) -> Unit,
    initLoadDone: Boolean, ultimulUpdateServer: Long, diferentaSecunde: Long, lastHistoryTimeUnix: Long,
    speedControlServer: Float, procentVentilatorServer: Int, rpmServer: Int, intervalSelectat: IntervalTimp,
    pm1: Float, pm25: Float, pm4: Float, pm10: Float, co2: Int, voc: Float, nox: Float, temp: Float, umiditate: Float,
    istoricPM1: List<Float>, istoricPM25: List<Float>, istoricPM4: List<Float>, istoricPM10: List<Float>,
    istoricCO2: List<Float>, istoricVOC: List<Float>, istoricNOx: List<Float>, istoricTemp: List<Float>, istoricUmid: List<Float>,
    accentColor: Color, textPrimary: Color, textSecondary: Color, surfaceColor: Color, tipGrafic: Int,
    onIntervalChange: (IntervalTimp) -> Unit, onSpeedConfirmed: (Float) -> Unit
) {
    var localSliderValue by remember { mutableFloatStateOf(speedControlServer.coerceAtLeast(1f)) }
    var isDragging by remember { mutableStateOf(false) }

    val trendPm1 = calculateTrend(pm1, ultimulUpdateServer)
    val trendPm25 = calculateTrend(pm25, ultimulUpdateServer)
    val trendPm4 = calculateTrend(pm4, ultimulUpdateServer)
    val trendPm10 = calculateTrend(pm10, ultimulUpdateServer)
    val trendCo2 = calculateTrend(co2.toFloat(), ultimulUpdateServer)
    val trendVoc = calculateTrend(voc, ultimulUpdateServer)
    val trendNox = calculateTrend(nox, ultimulUpdateServer)
    val trendTemp = calculateTrend(temp, ultimulUpdateServer)
    val trendUmid = calculateTrend(umiditate, ultimulUpdateServer)

    var globalTouchIndex by remember { mutableStateOf<Int?>(null) }
    var activeChartId by remember { mutableStateOf<String?>(null) }
    val onGlobalTouch: (Int?, String?) -> Unit = { idx, id -> globalTouchIndex = idx; activeChartId = id }

    LaunchedEffect(speedControlServer) { if (!isDragging) localSliderValue = speedControlServer.coerceAtLeast(1f) }

    val animPm1 by animateFloatAsState(pm1, tween(1000, easing = FastOutSlowInEasing), label = "pm1")
    val animPm25 by animateFloatAsState(pm25, tween(1000, easing = FastOutSlowInEasing), label = "pm25")
    val animPm4 by animateFloatAsState(pm4, tween(1000, easing = FastOutSlowInEasing), label = "pm4")
    val animPm10 by animateFloatAsState(pm10, tween(1000, easing = FastOutSlowInEasing), label = "pm10")
    val animCo2 by animateFloatAsState(co2.toFloat(), tween(1000, easing = FastOutSlowInEasing), label = "co2")
    val animVoc by animateFloatAsState(voc, tween(1000, easing = FastOutSlowInEasing), label = "voc")
    val animNox by animateFloatAsState(nox, tween(1000, easing = FastOutSlowInEasing), label = "nox")
    val animTemp by animateFloatAsState(temp, tween(1000, easing = FastOutSlowInEasing), label = "temp")
    val animUmid by animateFloatAsState(umiditate, tween(1000, easing = FastOutSlowInEasing), label = "umid")

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SISTEM CONTROL", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            val statusText = when {
                !initLoadDone -> "Conectare..."
                diferentaSecunde < 10 -> "Conectat: Acum"
                diferentaSecunde < 30 -> "Conectat: ${diferentaSecunde}s"
                diferentaSecunde < 60 -> "Deconectat: ${diferentaSecunde}s"
                diferentaSecunde < 3600 -> "Deconectat: ${diferentaSecunde / 60}m"
                else -> "Deconectat: ${diferentaSecunde / 3600}h"
            }
            val statusColor = when {
                !initLoadDone -> textSecondary
                diferentaSecunde < 30 -> textSecondary
                diferentaSecunde < 60 -> Paleta.Portocaliu1
                else -> Paleta.Rosu1
            }
            Text(statusText, color = statusColor, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))

        val isAuto = speedControlServer == 0f
        val isOprit = localSliderValue < 5f && !isAuto
        // În AUTO, cât timp utilizatorul trage de slider arătăm poziția degetului (preluare
        // manuală), nu ținta de pe server — altfel thumb-ul nu urmărea degetul până la
        // confirmarea round-trip-ului prin Firebase.
        val displaySliderValue = if (isAuto && !isDragging) procentVentilatorServer.toFloat().coerceIn(1f, 100f) else localSliderValue.coerceIn(1f, 100f)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Putere: ${if (isOprit) "OPRIT" else "${displaySliderValue.roundToInt()}%"}",
                color = if (isOprit) Paleta.Rosu1.copy(alpha = 0.8f) else textPrimary,
                fontSize = 16.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Start
            )
            Button(
                onClick = { localSliderValue = 1f; onSpeedConfirmed(0f) },
                colors = ButtonDefaults.buttonColors(containerColor = if (isAuto) accentColor else surfaceColor),
                shape = RoundedCornerShape(8.dp),
                border = if (!isAuto) BorderStroke(1.dp, textSecondary.copy(alpha = 0.5f)) else null,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("AUTO", color = if (isAuto) Color.White else textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "$rpmServer RPM", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.End
            )
        }

        SliderInteligentCustom(
            value = displaySliderValue, valueRange = 1f..100f, stepSize = 1f,
            onValueChange = { isDragging = true; localSliderValue = it },
            onValueChangeFinished = { finalValue -> isDragging = false; onSpeedConfirmed(finalValue) },
            onDragStateChange = { isDragging = it },
            accentColor = accentColor, textSecondary = textSecondary,
            hasHoverStopZone = true, stopZoneRange = 1f..4f, isAuto = isAuto
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IntervalTimp.entries.forEach { interval ->
                val isSelected = intervalSelectat == interval
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) accentColor else surfaceColor)
                        .clickable { onIntervalChange(interval) }
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text("${interval.ore}h", color = if (isSelected) Color.White else textPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        DraggableCategoryGroup(
            prefs = prefs, surfaceColor = surfaceColor, textSecondary = textSecondary,
            categoryOrder = categoryOrder, onOrderChange = onOrderChange,
            contentPraf = {
                SenzorCard("PM 1.0", animPm1, false, istoricPM1, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM1", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendPm1, lastHistoryTimeUnix, colorLogic = ::getPm1Color)
                SenzorCard("PM 2.5", animPm25, false, istoricPM25, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM25", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendPm25, lastHistoryTimeUnix, colorLogic = ::getPm25Color)
                SenzorCard("PM 4.0", animPm4, false, istoricPM4, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM4", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendPm4, lastHistoryTimeUnix, colorLogic = ::getPm4Color)
                SenzorCard("PM 10", animPm10, false, istoricPM10, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM10", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendPm10, lastHistoryTimeUnix, colorLogic = ::getPm10Color)
            },
            contentGaze = {
                SenzorCard("CO2 (ppm)", animCo2, false, istoricCO2, intervalSelectat, false, null, true, textPrimary, textSecondary, tipGrafic, "CO2", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendCo2, lastHistoryTimeUnix, colorLogic = ::getCo2Color)
                SenzorCard("VOC (Index)", animVoc, voc > 1000f, istoricVOC, intervalSelectat, false, 500f, true, textPrimary, textSecondary, tipGrafic, "VOC", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendVoc, lastHistoryTimeUnix, esteIndex = true, colorLogic = ::getVocColor)
                SenzorCard("NOx (Index)", animNox, nox > 1000f, istoricNOx, intervalSelectat, false, 500f, true, textPrimary, textSecondary, tipGrafic, "NOx", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendNox, lastHistoryTimeUnix, esteIndex = true, colorLogic = ::getNoxColor)
            },
            contentClima = {
                SenzorCard("Temperatură (°C)", animTemp, false, istoricTemp, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "Temp", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.TEMPERATURE, trendTemp, lastHistoryTimeUnix, douaZecimaleLive = true, colorLogic = ::getTempColor)
                SenzorCard("Umiditate (%)", animUmid, false, istoricUmid, intervalSelectat, false, 100f, false, textPrimary, textSecondary, tipGrafic, "Umid", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.HUMIDITY, trendUmid, lastHistoryTimeUnix, douaZecimaleLive = true, colorLogic = ::getHumidColor)
            }
        )
        Spacer(modifier = Modifier.height(24.dp))
        CardScorZilnic(istoricPM25, istoricCO2, istoricVOC, istoricNOx, surfaceColor, textPrimary, textSecondary)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// Parseaza un nod-zi din arhiva in cele 6 serii folosite de grafice (helper comun
// pentru listenerul de 28 de zile si pentru incarcarea la cerere a istoricului vechi).
private fun parseZiArhiva(ziSnap: DataSnapshot): Map<String, List<Float>> = mapOf(
    "PM25" to parseIstoric(ziSnap.child("PM25").getValue(String::class.java)),
    "CO2" to parseIstoric(ziSnap.child("CO2").getValue(String::class.java)),
    "VOC" to parseIstoric(ziSnap.child("VOC").getValue(String::class.java), true),
    "NOx" to parseIstoric(ziSnap.child("NOx").getValue(String::class.java), true),
    "Temp" to parseIstoric(ziSnap.child("Temp").getValue(String::class.java)),
    "RH" to parseIstoric(ziSnap.child("Umid").getValue(String::class.java))
)

// ================= TAB 2: ARHIVĂ =================
@Composable
fun EcranArhiva(
    prefs: SharedPreferences, categoryOrder: List<Int>, onOrderChange: (List<Int>) -> Unit,
    database: DatabaseReference, accentColor: Color, textPrimary: Color, textSecondary: Color,
    surfaceColor: Color, dividerColor: Color, tipGrafic: Int
) {
    // Doua surse: "recenta" (cele 4 saptamani afisate, in timp real, randare INSTANT din cache)
    // si "veche" (restul istoricului, incarcat O SINGURA DATA cu .get() abia cand utilizatorul
    // navigheaza in trecut). Asa pagina se deschide instant, iar arhiva veche nu se descarca
    // degeaba daca nu te uiti la ea.
    var arhivaRecenta by remember { mutableStateOf<Map<String, Map<String, List<Float>>>>(emptyMap()) }
    var listaRecenta by remember { mutableStateOf<List<Pair<String, LocalDate>>>(emptyList()) }
    var arhivaVeche by remember { mutableStateOf<Map<String, Map<String, List<Float>>>>(emptyMap()) }
    var listaVeche by remember { mutableStateOf<List<Pair<String, LocalDate>>>(emptyList()) }
    var istoricExtinsIncarcat by remember { mutableStateOf(false) }
    var seIncarcaIstoric by remember { mutableStateOf(false) }
    var existaIstoricMaiVechi by remember { mutableStateOf(false) }

    var saptamanaSelectata by remember { mutableStateOf<LocalDate?>(null) }
    var ziSelectata by remember { mutableStateOf<String?>(null) }
    var paginaSaptamani by remember { mutableIntStateOf(0) }   // 0 = cele mai recente 4 saptamani
    val formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd")

    // Fereastra implicita = EXACT 4 saptamani complete, nu "ultimele 28 de zile" (care ar prinde
    // 1-2 zile dintr-a 5-a saptamana calendaristica). ANCORA = luni a celei mai recente saptamani
    // CU DATE, NU saptamana calendaristica curenta: altfel, la inceput de saptamana noua (inainte
    // sa existe vreo zi scrisa pentru saptamana curenta), cea mai veche dintre cele 4 saptamani
    // complete ar cadea exact in afara ferestrei (startAt o exclude) si ar lipsi — bug "off by one".
    var luniReferinta by remember { mutableStateOf(LocalDate.now().with(DayOfWeek.MONDAY)) }
    LaunchedEffect(Unit) {
        database.child("Arhiva_Zilnica").orderByKey().limitToLast(1).get().addOnSuccessListener { snap ->
            snap.children.lastOrNull()?.key
                ?.let { runCatching { LocalDate.parse(it, formatter) }.getOrNull() }
                ?.let { luniReferinta = it.with(DayOfWeek.MONDAY) }
        }
    }
    val startKey = remember(luniReferinta) { luniReferinta.minusWeeks(3).format(formatter) }
    val endKeyVechi = remember(luniReferinta) { luniReferinta.minusWeeks(3).minusDays(1).format(formatter) }

    var globalTouchIndex by remember { mutableStateOf<Int?>(null) }
    var activeChartId by remember { mutableStateOf<String?>(null) }
    val onGlobalTouch: (Int?, String?) -> Unit = { idx, id -> globalTouchIndex = idx; activeChartId = id }

    DisposableEffect(startKey) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val mapNou = mutableMapOf<String, Map<String, List<Float>>>()
                val zileParsate = mutableListOf<Pair<String, LocalDate>>()
                for (ziSnap in snapshot.children) {
                    val key = ziSnap.key ?: continue
                    try {
                        zileParsate.add(key to LocalDate.parse(key, formatter))
                        mapNou[key] = parseZiArhiva(ziSnap)
                    } catch (_: Exception) { continue }
                }
                arhivaRecenta = mapNou
                listaRecenta = zileParsate
                // Selectie initiala doar daca nu am ales inca nimic (NU resetam cand userul
                // s-a dus deja in istoric — altfel l-am smulge inapoi la zi cand se refac datele).
                if (saptamanaSelectata == null && zileParsate.isNotEmpty()) {
                    val ultimaZi = zileParsate.last()
                    saptamanaSelectata = ultimaZi.second.with(DayOfWeek.MONDAY)
                    ziSelectata = ultimaZi.first
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        // Doar cele 4 saptamani afisate (de la luni a saptamanii -3) -> randare instant din cache.
        val query = database.child("Arhiva_Zilnica").orderByKey().startAt(startKey)
        query.addValueEventListener(listener)
        onDispose { query.removeEventListener(listener) }
    }

    // Verificam IEFTIN (o singura zi) daca exista arhiva mai veche decat fereastra afisata,
    // ca sa stim daca aratam sageata inapoi — fara a descarca tot istoricul.
    LaunchedEffect(endKeyVechi) {
        database.child("Arhiva_Zilnica").orderByKey().endAt(endKeyVechi).limitToLast(1).get()
            .addOnSuccessListener { existaIstoricMaiVechi = it.hasChildren() }
    }

    // Vederile combinate (vechi + recent; recentul are prioritate la suprapuneri).
    val arhivaData = remember(arhivaVeche, arhivaRecenta) { arhivaVeche + arhivaRecenta }
    val listaZileReale = remember(listaVeche, listaRecenta) {
        (listaVeche + listaRecenta).distinctBy { it.first }.sortedBy { it.second }
    }
    val saptamaniMap = remember(listaZileReale) { listaZileReale.groupBy { it.second.with(DayOfWeek.MONDAY) } }
    val toateSaptamanile = remember(saptamaniMap) { saptamaniMap.keys.sorted() }
    val saptamaniValide = remember(toateSaptamanile, paginaSaptamani) {
        toateSaptamanile.dropLast(paginaSaptamani * 4).takeLast(4)
    }
    val existaSaptamaniMaiVechi = toateSaptamanile.size > (paginaSaptamani + 1) * 4
    // Mai exista istoric dincolo de fereastra afisata, neadus inca? (din proba ieftina de mai sus)
    val potExtindeIstoric = !istoricExtinsIncarcat && existaIstoricMaiVechi

    fun selecteazaPagina(pagina: Int) {
        val vizibile = toateSaptamanile.dropLast(pagina * 4).takeLast(4)
        if (vizibile.isEmpty()) return
        paginaSaptamani = pagina
        vizibile.lastOrNull()?.let { sapt ->
            saptamanaSelectata = sapt
            ziSelectata = saptamaniMap[sapt]?.last()?.first
        }
    }

    // Incarca O SINGURA DATA tot istoricul ramas si avanseaza imediat la pagina urmatoare,
    // selectand corect din lista deja completa (fara a depinde de o recompunere ulterioara).
    fun extindeSiAvanseaza() {
        if (seIncarcaIstoric) return
        seIncarcaIstoric = true
        database.child("Arhiva_Zilnica").get().addOnSuccessListener { snapshot ->
            val mapNou = mutableMapOf<String, Map<String, List<Float>>>()
            val zile = mutableListOf<Pair<String, LocalDate>>()
            for (ziSnap in snapshot.children) {
                val key = ziSnap.key ?: continue
                try { zile.add(key to LocalDate.parse(key, formatter)); mapNou[key] = parseZiArhiva(ziSnap) } catch (_: Exception) { continue }
            }
            arhivaVeche = mapNou; listaVeche = zile
            istoricExtinsIncarcat = true; seIncarcaIstoric = false
            val combinat = (zile + listaRecenta).distinctBy { it.first }.sortedBy { it.second }
            val toate = combinat.map { it.second.with(DayOfWeek.MONDAY) }.distinct().sorted()
            val tinta = paginaSaptamani + 1
            val vizibile = toate.dropLast(tinta * 4).takeLast(4)
            if (vizibile.isNotEmpty()) {
                paginaSaptamani = tinta
                val sapt = vizibile.last()
                saptamanaSelectata = sapt
                ziSelectata = combinat.filter { it.second.with(DayOfWeek.MONDAY) == sapt }.maxByOrNull { it.second }?.first
            }
        }.addOnFailureListener { seIncarcaIstoric = false }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ARHIVĂ CALITATE AER", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existaSaptamaniMaiVechi || potExtindeIstoric) {
                    Box(
                        modifier = Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(8.dp))
                            .background(surfaceColor)
                            .clickable(enabled = !seIncarcaIstoric) {
                                if (existaSaptamaniMaiVechi) selecteazaPagina(paginaSaptamani + 1)
                                else extindeSiAvanseaza()   // prima coborare in istoricul vechi: il aducem acum
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (seIncarcaIstoric) CircularProgressIndicator(color = textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        else Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Săptămânile anterioare", tint = textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                if (paginaSaptamani > 0) {
                    Box(
                        modifier = Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(8.dp))
                            .background(surfaceColor)
                            .clickable { selecteazaPagina(paginaSaptamani - 1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Săptămânile următoare", tint = textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (saptamaniValide.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Arhivă goală", color = textSecondary) }
            return@Column
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            saptamaniValide.forEach { luneaSaptamanii ->
                val isSelected = saptamanaSelectata == luneaSaptamanii
                val duminica = luneaSaptamanii.plusDays(6)
                val m1 = getMonthRoShort(luneaSaptamanii.monthValue); val m2 = getMonthRoShort(duminica.monthValue)
                val titlu = if (m1 == m2) "${luneaSaptamanii.dayOfMonth}-${duminica.dayOfMonth}\n$m1"
                else "${luneaSaptamanii.dayOfMonth} $m1\n${duminica.dayOfMonth} $m2"
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) accentColor else surfaceColor)
                        .clickable {
                            saptamanaSelectata = luneaSaptamanii
                            ziSelectata = saptamaniMap[luneaSaptamanii]?.last()?.first
                        }
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(titlu, color = if (isSelected) Color.White else textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (saptamanaSelectata != null) {
                for (i in 0L..6L) {
                    val dataCurentaDinGrila = saptamanaSelectata!!.plusDays(i)
                    val ziGasitaInDb = listaZileReale.find { it.second == dataCurentaDinGrila }
                    if (ziGasitaInDb != null) {
                        val isSelected = ziSelectata == ziGasitaInDb.first
                        Box(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) accentColor else surfaceColor)
                                .clickable { ziSelectata = ziGasitaInDb.first }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(getDayOfWeekRo(dataCurentaDinGrila), color = if (isSelected) Color.White.copy(alpha = 0.8f) else textSecondary, fontSize = 10.sp)
                                Text(dataCurentaDinGrila.dayOfMonth.toString(), color = if (isSelected) Color.White else textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = dividerColor)
        Spacer(modifier = Modifier.height(16.dp))

        val dateZi = arhivaData[ziSelectata] ?: emptyMap()
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            DraggableCategoryGroup(
                prefs = prefs, surfaceColor = surfaceColor, textSecondary = textSecondary,
                categoryOrder = categoryOrder, onOrderChange = onOrderChange,
                contentPraf = {
                    SenzorCard("PM 2.5 (Praf)", 0f, true, dateZi["PM25"] ?: emptyList(), IntervalTimp.ORE_24, true, null, false, textPrimary, textSecondary, tipGrafic, "AR_PM25", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, 0, 0L, colorLogic = ::getPm25Color)
                },
                contentGaze = {
                    SenzorCard("CO2 (Dioxid de Carbon)", 0f, true, dateZi["CO2"] ?: emptyList(), IntervalTimp.ORE_24, true, null, true, textPrimary, textSecondary, tipGrafic, "AR_CO2", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, 0, 0L, colorLogic = ::getCo2Color)
                    SenzorCard("VOC (Chimicale)", 0f, true, dateZi["VOC"] ?: emptyList(), IntervalTimp.ORE_24, true, 500f, true, textPrimary, textSecondary, tipGrafic, "AR_VOC", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, 0, 0L, esteIndex = true, colorLogic = ::getVocColor)
                    SenzorCard("NOx (Gaze eșapament)", 0f, true, dateZi["NOx"] ?: emptyList(), IntervalTimp.ORE_24, true, 500f, true, textPrimary, textSecondary, tipGrafic, "AR_NOx", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, 0, 0L, esteIndex = true, colorLogic = ::getNoxColor)
                },
                contentClima = {
                    SenzorCard("Temperatură (°C)", 0f, true, dateZi["Temp"] ?: emptyList(), IntervalTimp.ORE_24, true, null, false, textPrimary, textSecondary, tipGrafic, "AR_Temp", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.TEMPERATURE, 0, 0L, colorLogic = ::getTempColor)
                    SenzorCard("Umiditate RH (%)", 0f, true, dateZi["RH"] ?: emptyList(), IntervalTimp.ORE_24, true, 100f, false, textPrimary, textSecondary, tipGrafic, "AR_Umid", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.HUMIDITY, 0, 0L, colorLogic = ::getHumidColor)
                }
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ================= TAB 3: SETĂRI =================
@Composable
fun EcranSetari(
    context: Context, prefs: SharedPreferences, database: DatabaseReference, nightModeActiv: Boolean, liveModeActiv: Boolean,
    notifPraf: Boolean, notifGaze: Boolean, notifOffline: Boolean, errorLogString: String, runInBackground: Boolean,
    isDarkMode: Boolean, accentColor: Color, tipGrafic: Int, textPrimary: Color, textSecondary: Color, surfaceColor: Color,
    istoricExportPM: List<Float>, istoricExportCO2: List<Float>, dividerColor: Color,
    telemetrieRam: Int, telemetrieTemp: Float, telemetrieRssi: Int, telemetrieUptime: Long, tempOffsetServer: Float,
    luminozitateServer: Int, luminozitateLedServer: Int,
    onNightModeChange: (Boolean) -> Unit, onLuminozitateChange: (Int) -> Unit, onLuminozitateLedChange: (Int) -> Unit,
    onLiveModeChange: (Boolean) -> Unit, onNotifPrafChange: (Boolean) -> Unit,
    onNotifGazeChange: (Boolean) -> Unit, onNotifOfflineChange: (Boolean) -> Unit, onRunInBackgroundChange: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit, onColorChange: (Int) -> Unit, onTipGraficChange: (Int) -> Unit,
    onCleanSensor: () -> Unit, onRestartBoard: () -> Unit,
    onTempOffsetChange: (Float) -> Unit, onResetVoc: () -> Unit, onCalibrareCo2: () -> Unit
) {
    // FIX: Toate dialogurile folosesc acum `var` simplu — elimină warningul "Assigned value is never read"
    // care apărea din cauza destructuring-ului `val (show, setShow) = remember { mutableStateOf(false) }`
    // unde `setShow` era interpretat ca valoare niciodată citită de către IDE.
    var showCleanDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var showResetVocDialog by remember { mutableStateOf(false) }
    var showCalibCo2Dialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var csvContentToSave by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        isExporting = false
        if (uri != null) { context.contentResolver.openOutputStream(uri)?.use { it.write(csvContentToSave.toByteArray()) } }
    }

    if (showCleanDialog) {
        AlertDialog(
            onDismissRequest = { showCleanDialog = false },
            title = { Text("Curățare Senzor SEN66", fontWeight = FontWeight.Bold) },
            text = { Text("Această comandă va porni ventilatorul intern al senzorului la turație maximă pentru a elimina depunerile de praf. Ești sigur?") },
            confirmButton = { TextButton(onClick = { showCleanDialog = false; onCleanSensor() }) { Text("Curăță", color = accentColor) } },
            dismissButton = { TextButton(onClick = { showCleanDialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Restartare Sistem", fontWeight = FontWeight.Bold, color = Paleta.Rosu1) },
            text = { Text("Plăcuța ESP32 va fi oprită și repornită forțat. Conexiunea WiFi se va întrerupe pentru câteva secunde. Continuăm?") },
            confirmButton = { TextButton(onClick = { showRestartDialog = false; onRestartBoard() }) { Text("Restart", color = Paleta.Rosu1) } },
            dismissButton = { TextButton(onClick = { showRestartDialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }
    if (showResetVocDialog) {
        AlertDialog(
            onDismissRequest = { showResetVocDialog = false },
            title = { Text("Resetare Calibrare VOC", fontWeight = FontWeight.Bold, color = Paleta.Rosu1) },
            text = { Text("Această acțiune șterge permanent memoria învățată a algoritmului VOC. Senzorul va avea nevoie de 12-24 de ore să se re-calibreze singur. Aparatul se restartează automat. Ești sigur?") },
            confirmButton = { TextButton(onClick = { showResetVocDialog = false; onResetVoc() }) { Text("Resetare", color = Paleta.Rosu1) } },
            dismissButton = { TextButton(onClick = { showResetVocDialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }
    if (showCalibCo2Dialog) {
        AlertDialog(
            onDismissRequest = { showCalibCo2Dialog = false },
            title = { Text("Calibrare CO2 (aer proaspăt)", fontWeight = FontWeight.Bold) },
            text = { Text("Senzorul de CO2 va fi recalibrat forțat (FRC) la nivelul aerului curat de afară (~420 ppm).\n\nIMPORTANT: ține aparatul PORNIT la aer proaspăt — geam larg deschis sau afară — cel puțin 3 minute ÎNAINTE de a apăsa Calibrează. Altfel calibrarea va ieși greșită!\n\nRezultatul e permanent, măsurarea continuă imediat (fără restart), iar corecția aplicată apare în Jurnalul de Erori. Continui?") },
            confirmButton = { TextButton(onClick = { showCalibCo2Dialog = false; onCalibrareCo2() }) { Text("Calibrează", color = accentColor) } },
            dismissButton = { TextButton(onClick = { showCalibCo2Dialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }

    if (showErrorDialog) {
        val parsedErrors = remember(errorLogString) {
            errorLogString.split("|").mapNotNull { logEntry ->
                val parts = logEntry.split("#")
                if (parts.size == 2) {
                    val ts = parts[0].toLongOrNull() ?: return@mapNotNull logEntry
                    val d = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    "${d.format(DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss"))} - ${parts[1]}"
                } else {
                    logEntry.ifBlank { null }
                }
            }.reversed()
        }
        Dialog(onDismissRequest = { showErrorDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = surfaceColor) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Jurnal Erori (24h)", fontWeight = FontWeight.Bold, color = textPrimary, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        if (parsedErrors.isEmpty()) {
                            item { Text("Sistemul funcționează perfect! Nicio eroare.", color = Paleta.Verde1) }
                        } else {
                            items(parsedErrors) { err ->
                                val errColor = when {
                                    err.contains("Calibrare VOC", ignoreCase = true) -> textPrimary
                                    err.contains("Curatare", ignoreCase = true) || err.contains("Offset", ignoreCase = true) -> Paleta.Galben1
                                    err.contains("OTA", ignoreCase = true) || err.contains("USB", ignoreCase = true) -> Paleta.Portocaliu1
                                    err.contains("refacuta", ignoreCase = true) -> Paleta.Mov1
                                    err.contains("creata", ignoreCase = true) -> Paleta.Verde1
                                    err.contains("Eroare", ignoreCase = true) || err.contains("WDT", ignoreCase = true) ||
                                            err.contains("Panic", ignoreCase = true) || err.contains("Blocat", ignoreCase = true) ||
                                            err.contains("Restart", ignoreCase = true) || err.contains("Pierdut", ignoreCase = true) ||
                                            err.contains("Deconectat", ignoreCase = true) || err.contains("Fail", ignoreCase = true) -> Paleta.Rosu1
                                    else -> Paleta.Albastru2
                                }
                                Text(err, color = errColor, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showErrorDialog = false }) { Text("Închide", color = textPrimary) }
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isExporting) showExportDialog = false },
            title = { Text("Exportă Datele (CSV)", fontWeight = FontWeight.Bold) },
            text = {
                if (isExporting) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        CircularProgressIndicator(color = accentColor)
                        Spacer(Modifier.height(16.dp))
                        Text("Descărcare arhivă din Cloud...", color = textSecondary)
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Alege ce perioadă dorești să descarci:", color = textPrimary)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val sb = StringBuilder().apply {
                                    append("Minute_In_Urma,PM2.5,CO2_ppm\n")
                                    val maxPuncte = minOf(istoricExportPM.size, istoricExportCO2.size)
                                    for (i in 0 until maxPuncte) {
                                        append("${(maxPuncte - 1 - i) * 5},${istoricExportPM[i]},${istoricExportCO2[i]}\n")
                                    }
                                }
                                csvContentToSave = sb.toString()
                                exportLauncher.launch("Live_Aer_24h.csv")
                                showExportDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = surfaceColor, contentColor = textPrimary),
                            border = BorderStroke(1.dp, dividerColor)
                        ) { Text("Ultimele 24 de ore (Live)") }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isExporting = true
                                val db = FirebaseDatabase.getInstance(WIDGET_DB_URL).getReference("SenzoriAer/Arhiva_Zilnica")
                                db.get().addOnSuccessListener { snapshot ->
                                    val sb = StringBuilder().apply {
                                        append("Data,Ora,PM2.5,CO2_ppm,VOC_Index,NOx_Index,Temp_C,Umiditate_RH\n")
                                        for (ziSnap in snapshot.children) {
                                            val dateStr = ziSnap.key ?: continue
                                            val pmList = parseIstoric(ziSnap.child("PM25").getValue(String::class.java))
                                            val co2List = parseIstoric(ziSnap.child("CO2").getValue(String::class.java))
                                            val vocList = parseIstoric(ziSnap.child("VOC").getValue(String::class.java), true)
                                            val noxList = parseIstoric(ziSnap.child("NOx").getValue(String::class.java), true)
                                            val tempList = parseIstoric(ziSnap.child("Temp").getValue(String::class.java))
                                            val humList = parseIstoric(ziSnap.child("Umid").getValue(String::class.java))
                                            // Randul se scrie daca ORICARE serie are valoare la slotul i (inainte se
                                            // sarea tot randul cand lipsea PM2.5 si se pierdeau CO2/Temp valide);
                                            // valorile lipsa raman celule goale, nu -999.
                                            val celula = { v: Float, intreg: Boolean -> if (v == -999f) "" else if (intreg) "${v.toInt()}" else "$v" }
                                            val nrSloturi = maxOf(pmList.size, co2List.size, vocList.size, noxList.size, tempList.size, humList.size)
                                            for (i in 0 until nrSloturi) {
                                                val pm = pmList.getOrNull(i) ?: -999f
                                                val co2V = co2List.getOrNull(i) ?: -999f
                                                val vocV = vocList.getOrNull(i) ?: -999f
                                                val noxV = noxList.getOrNull(i) ?: -999f
                                                val tempV = tempList.getOrNull(i) ?: -999f
                                                val humV = humList.getOrNull(i) ?: -999f
                                                if (pm == -999f && co2V == -999f && vocV == -999f && noxV == -999f && tempV == -999f && humV == -999f) continue
                                                val totalMin = i * 5
                                                append("$dateStr,${"%02d:%02d".format(totalMin / 60, totalMin % 60)},${celula(pm, false)},${celula(co2V, true)},${celula(vocV, true)},${celula(noxV, true)},${celula(tempV, false)},${celula(humV, false)}\n")
                                            }
                                        }
                                    }
                                    csvContentToSave = sb.toString()
                                    exportLauncher.launch("Arhiva_Completă_Aer.csv")
                                    showExportDialog = false
                                }.addOnFailureListener { isExporting = false; showExportDialog = false }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                        ) { Text("Toată Arhiva Salvată") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExportDialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary
        )
    }

    // Dialog prag PM2.5 (alertPmState tine descrierea switch-ului sincronizata dupa salvare)
    var showPmDialog by remember { mutableStateOf(false) }
    var pmVal by remember { mutableStateOf(prefs.getFloat("alertPm", 20f).toInt().toString()) }
    var alertPmState by remember { mutableFloatStateOf(prefs.getFloat("alertPm", 20f)) }
    if (showPmDialog) {
        AlertDialog(
            onDismissRequest = { showPmDialog = false },
            title = { Text("Setare Prag PM2.5", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = pmVal, onValueChange = { pmVal = it },
                    label = { Text("Limită alertă (µg/m³)") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, cursorColor = accentColor, focusedBorderColor = accentColor, unfocusedBorderColor = textSecondary, focusedLabelColor = accentColor, unfocusedLabelColor = textSecondary)
                )
            },
            confirmButton = { TextButton(onClick = { pmVal.toFloatOrNull()?.let { prefs.edit(commit = true) { putFloat("alertPm", it) }; alertPmState = it; database.child("setari/alert_pm").setValue(it.toDouble()) }; showPmDialog = false }) { Text("Salvează", color = accentColor) } },
            dismissButton = { TextButton(onClick = { showPmDialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }

    // Dialog praguri gaze
    var showGazeDialog by remember { mutableStateOf(false) }
    var co2Val by remember { mutableStateOf(prefs.getFloat("alertCo2", 1600f).toInt().toString()) }
    var vocVal by remember { mutableStateOf(prefs.getFloat("alertVoc", 250f).toInt().toString()) }
    var noxVal by remember { mutableStateOf(prefs.getFloat("alertNox", 30f).toInt().toString()) }
    if (showGazeDialog) {
        AlertDialog(
            onDismissRequest = { showGazeDialog = false },
            title = { Text("Praguri Alerte Gaze", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(value = co2Val, onValueChange = { co2Val = it }, label = { Text("CO2 (ppm)") }, singleLine = true, modifier = Modifier.padding(bottom = 8.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, cursorColor = accentColor, focusedBorderColor = accentColor, unfocusedBorderColor = textSecondary, focusedLabelColor = accentColor, unfocusedLabelColor = textSecondary))
                    OutlinedTextField(value = vocVal, onValueChange = { vocVal = it }, label = { Text("VOC (Index)") }, singleLine = true, modifier = Modifier.padding(bottom = 8.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, cursorColor = accentColor, focusedBorderColor = accentColor, unfocusedBorderColor = textSecondary, focusedLabelColor = accentColor, unfocusedLabelColor = textSecondary))
                    OutlinedTextField(value = noxVal, onValueChange = { noxVal = it }, label = { Text("NOx (Index)") }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, cursorColor = accentColor, focusedBorderColor = accentColor, unfocusedBorderColor = textSecondary, focusedLabelColor = accentColor, unfocusedLabelColor = textSecondary))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    co2Val.toFloatOrNull()?.let { prefs.edit(commit = true) { putFloat("alertCo2", it) }; database.child("setari/alert_co2").setValue(it.toInt()) }
                    vocVal.toFloatOrNull()?.let { prefs.edit(commit = true) { putFloat("alertVoc", it) }; database.child("setari/alert_voc").setValue(it.toDouble()) }
                    noxVal.toFloatOrNull()?.let { prefs.edit(commit = true) { putFloat("alertNox", it) }; database.child("setari/alert_nox").setValue(it.toDouble()) }
                    showGazeDialog = false
                }) { Text("Salvează", color = accentColor) }
            },
            dismissButton = { TextButton(onClick = { showGazeDialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }

    // Dialog Night Mode
    var showNightModeDialog by remember { mutableStateOf(false) }
    if (showNightModeDialog) {
        var autoNightMode by remember { mutableStateOf(prefs.getBoolean("nightModeAuto", false)) }
        var startHr by remember { mutableIntStateOf(prefs.getInt("nmStartHr", 23)) }
        var startMin by remember { mutableIntStateOf(prefs.getInt("nmStartMin", 0)) }
        var endHr by remember { mutableIntStateOf(prefs.getInt("nmEndHr", 7)) }
        var endMin by remember { mutableIntStateOf(prefs.getInt("nmEndMin", 0)) }
        var localLumin by remember { mutableFloatStateOf(luminozitateServer.toFloat()) }
        var isLuminDragging by remember { mutableStateOf(false) }
        var localLedLumin by remember { mutableFloatStateOf((luminozitateLedServer / 2.55f).coerceIn(0f, 100f)) }
        var isLedLuminDragging by remember { mutableStateOf(false) }
        LaunchedEffect(luminozitateServer) { if (!isLuminDragging) localLumin = luminozitateServer.toFloat() }
        LaunchedEffect(luminozitateLedServer) { if (!isLedLuminDragging) localLedLumin = (luminozitateLedServer / 2.55f).coerceIn(0f, 100f) }
        val startDialog = TimePickerDialog(context, { _, h, m -> startHr = h; startMin = m; prefs.edit(commit = true) { putInt("nmStartHr", h); putInt("nmStartMin", m) }; database.child("setari/nm_start").setValue(h * 60 + m) }, startHr, startMin, true)
        val endDialog = TimePickerDialog(context, { _, h, m -> endHr = h; endMin = m; prefs.edit(commit = true) { putInt("nmEndHr", h); putInt("nmEndMin", m) }; database.child("setari/nm_end").setValue(h * 60 + m) }, endHr, endMin, true)
        AlertDialog(
            onDismissRequest = { showNightModeDialog = false },
            title = { Text("Setări Ecran & Night Mode", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Luminozitate Ecran: ${localLumin.roundToInt()}%", color = textPrimary, fontSize = 14.sp)
                    SliderInteligentCustom(value = localLumin, valueRange = 0f..100f, stepSize = 1f, onValueChange = { isLuminDragging = true; localLumin = it }, onValueChangeFinished = { isLuminDragging = false; onLuminozitateChange(it.roundToInt()) }, onDragStateChange = { isLuminDragging = it }, accentColor = accentColor, textSecondary = textSecondary)
                    Spacer(Modifier.height(16.dp))
                    Text("Luminozitate LED: ${localLedLumin.roundToInt()}%", color = textPrimary, fontSize = 14.sp)
                    SliderInteligentCustom(value = localLedLumin, valueRange = 0f..100f, stepSize = 1f, onValueChange = { isLedLuminDragging = true; localLedLumin = it }, onValueChangeFinished = { isLedLuminDragging = false; onLuminozitateLedChange((it * 2.55f).roundToInt()) }, onDragStateChange = { isLedLuminDragging = it }, accentColor = accentColor, textSecondary = textSecondary)
                    Spacer(Modifier.height(16.dp)); HorizontalDivider(color = dividerColor); Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Automatizare Night Mode", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = autoNightMode,
                            onCheckedChange = { autoNightMode = it; prefs.edit(commit = true) { putBoolean("nightModeAuto", it) }; database.child("setari/nm_auto").setValue(it) },
                            modifier = Modifier.scale(0.7f).height(24.dp),
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accentColor, uncheckedThumbColor = Paleta.Gri1, uncheckedTrackColor = if (surfaceColor == Color.White) Paleta.Alb2 else Paleta.Gri2, uncheckedBorderColor = Color.Transparent)
                        )
                    }
                    AnimatedVisibility(visible = autoNightMode) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Button(onClick = { startDialog.show() }, colors = ButtonDefaults.buttonColors(containerColor = dividerColor.copy(alpha = 0.3f)), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                                    Text("Start: ${"%02d:%02d".format(startHr, startMin)}", color = textPrimary, fontSize = 12.sp)
                                }
                                Button(onClick = { endDialog.show() }, colors = ButtonDefaults.buttonColors(containerColor = dividerColor.copy(alpha = 0.3f)), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                                    Text("Stop: ${"%02d:%02d".format(endHr, endMin)}", color = textPrimary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNightModeDialog = false }) { Text("Gata", color = accentColor) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }

    // Dialog Live Mode
    var showLiveModeDialog by remember { mutableStateOf(false) }
    if (showLiveModeDialog) {
        var localLivePerm by remember { mutableStateOf(prefs.getBoolean("liveModePermanent", false)) }
        AlertDialog(
            onDismissRequest = { showLiveModeDialog = false },
            title = { Text("Comportament Live Mode", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().clickable { localLivePerm = false }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !localLivePerm, onClick = { localLivePerm = false }, colors = RadioButtonDefaults.colors(selectedColor = accentColor))
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text("Normal (Baterie)", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("Se oprește la 10 secunde după ce ieși din aplicație.", color = textSecondary, fontSize = 12.sp)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable { localLivePerm = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = localLivePerm, onClick = { localLivePerm = true }, colors = RadioButtonDefaults.colors(selectedColor = accentColor))
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text("Permanent (Consum Mare)", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("Stă pornit non-stop cât timp e comutatorul activat.", color = textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { prefs.edit(commit = true) { putBoolean("liveModePermanent", localLivePerm) }; showLiveModeDialog = false }) {
                    Text("Salvează", color = accentColor)
                }
            },
            dismissButton = { TextButton(onClick = { showLiveModeDialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("1. PERSONALIZARE INTERFAȚĂ", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            // Tema light
            Box(modifier = Modifier.size(130.dp, 90.dp).clip(RoundedCornerShape(16.dp)).background(Paleta.Alb1).border(if (!isDarkMode) 3.dp else 1.dp, if (!isDarkMode) accentColor else Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(12.dp).clickable { onThemeChange(false) }) {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(accentColor))
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.width(40.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.LightGray))
                    }
                }
            }
            // Tema dark
            Box(modifier = Modifier.size(130.dp, 90.dp).clip(RoundedCornerShape(16.dp)).background(Paleta.Negru1).border(if (isDarkMode) 3.dp else 1.dp, if (isDarkMode) accentColor else Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(12.dp).clickable { onThemeChange(true) }) {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(Paleta.Gri3))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(accentColor))
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.width(40.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Gray))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).border(1.dp, if (surfaceColor == Color.White) Paleta.Alb3 else Color.Transparent, RoundedCornerShape(12.dp)).padding(16.dp), horizontalArrangement = Arrangement.SpaceAround) {
            culoriDisponibile.forEach { (colorInt, _) ->
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(colorInt)).border(width = if (accentColor == Color(colorInt)) 3.dp else 0.dp, color = if (accentColor == Color(colorInt)) textPrimary else Color.Transparent, shape = CircleShape).clickable { onColorChange(colorInt) })
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).border(1.dp, if (surfaceColor == Color.White) Paleta.Alb3 else Color.Transparent, RoundedCornerShape(12.dp)).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Bars", "Spline", "Step").forEachIndexed { index, titlu ->
                Box(modifier = Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(8.dp)).background(if (tipGrafic == index) accentColor else Color.Transparent).clickable { onTipGraficChange(index) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(titlu, color = if (tipGrafic == index) Color.White else textPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SetareSwitch("Ecran & Night Mode", "Setări luminozitate și programare.", nightModeActiv, accentColor, textPrimary, textSecondary, surfaceColor, onClick = { showNightModeDialog = true }) { onNightModeChange(it) }
        Spacer(modifier = Modifier.height(32.dp))

        Row(modifier = Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("2. HARDWARE ȘI SENZOR", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE", color = textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Switch(checked = liveModeActiv, onCheckedChange = onLiveModeChange, modifier = Modifier.scale(0.55f), colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accentColor, uncheckedThumbColor = Paleta.Gri1, uncheckedTrackColor = if (surfaceColor == Color.White) Paleta.Alb2 else Paleta.Gri2, uncheckedBorderColor = Color.Transparent))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).border(1.dp, if (surfaceColor == Color.White) Paleta.Alb3 else Color.Transparent, RoundedCornerShape(12.dp)).clickable { showLiveModeDialog = true }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("RAM", color = textSecondary, fontSize = 10.sp); Text("${telemetrieRam / 1024} KB", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("CPU", color = textSecondary, fontSize = 10.sp); Text("${"%.1f".format(telemetrieTemp)}°C", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("WiFi", color = textSecondary, fontSize = 10.sp); Text("$telemetrieRssi dBm", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Uptime", color = textSecondary, fontSize = 10.sp); Text(formatUptime(telemetrieUptime), color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { showErrorDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), border = if (surfaceColor == Color.White) BorderStroke(1.dp, Paleta.Alb3) else null, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, null, tint = Paleta.Mov1); Spacer(Modifier.width(16.dp)); Text("Jurnal Erori (Ultimele 24h)", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { showRestartDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), border = if (surfaceColor == Color.White) BorderStroke(1.dp, Paleta.Alb3) else null, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null, tint = Paleta.Rosu1); Spacer(Modifier.width(16.dp)); Text("Restartare Sistem", color = Paleta.Rosu1, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { showResetVocDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), border = if (surfaceColor == Color.White) BorderStroke(1.dp, Paleta.Alb3) else null, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Refresh, null, tint = Paleta.Portocaliu1); Spacer(Modifier.width(16.dp)); Text("Resetare Calibrare VOC", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { showCalibCo2Dialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), border = if (surfaceColor == Color.White) BorderStroke(1.dp, Paleta.Alb3) else null, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Refresh, null, tint = Paleta.Galben1); Spacer(Modifier.width(16.dp)); Text("Calibrare CO2 (aer proaspăt)", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { showCleanDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), border = if (surfaceColor == Color.White) BorderStroke(1.dp, Paleta.Alb3) else null, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Settings, null, tint = Paleta.Albastru1); Spacer(Modifier.width(16.dp)); Text("Curățare Senzor SEN66", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { showExportDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), border = if (surfaceColor == Color.White) BorderStroke(1.dp, Paleta.Alb3) else null, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Share, null, tint = Paleta.Verde1); Spacer(Modifier.width(16.dp)); Text("Exportă Datele (.csv)", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }

        // Offset temperatură (ascuns implicit — apare la tap pe card, ca să nu fie modificat accidental la scroll)
        var localOffset by remember { mutableFloatStateOf(tempOffsetServer) }
        var isOffsetDragging by remember { mutableStateOf(false) }
        var offsetExtins by remember { mutableStateOf(false) }
        val offsetRotatie by animateFloatAsState(if (offsetExtins) 180f else 0f, label = "offsetChevron")
        LaunchedEffect(tempOffsetServer) { if (!isOffsetDragging) localOffset = tempOffsetServer }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).border(1.dp, if (surfaceColor == Color.White) Paleta.Alb3 else Color.Transparent, RoundedCornerShape(12.dp)).padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable { offsetExtins = !offsetExtins }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Offset Temperatură", color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (localOffset > 0) "+" else ""}${"%.1f".format(localOffset)} °C", color = accentColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Extinde", tint = textSecondary, modifier = Modifier.padding(start = 4.dp).rotate(offsetRotatie))
                }
            }
            AnimatedVisibility(visible = offsetExtins) {
                Column {
                    Text("Compensează căldura radiată de ventilator pentru o citire perfectă.", color = textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                    SliderInteligentCustom(value = localOffset, valueRange = -3f..3f, stepSize = 0.1f, onValueChange = { isOffsetDragging = true; localOffset = it }, onValueChangeFinished = { isOffsetDragging = false; onTempOffsetChange(it) }, onDragStateChange = { isOffsetDragging = it }, accentColor = accentColor, textSecondary = textSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("3. NOTIFICĂRI PUSH", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        SetareSwitch("Alerte Praf (PM2.5)", "Te notifică când depășește ${alertPmState.toInt()} µg/m³.", notifPraf, accentColor, textPrimary, textSecondary, surfaceColor, onClick = { showPmDialog = true }) { onNotifPrafChange(it) }
        Spacer(modifier = Modifier.height(8.dp))
        SetareSwitch("Alertă Aerisire", "Când gazele (CO2/VOC/NOx) depășesc pragurile.", notifGaze, accentColor, textPrimary, textSecondary, surfaceColor, onClick = { showGazeDialog = true }) { onNotifGazeChange(it) }
        Spacer(modifier = Modifier.height(8.dp))
        SetareSwitch("Alertă Deconectare", "Primești notificare când aparatul cade din rețea.", notifOffline, accentColor, textPrimary, textSecondary, surfaceColor) { onNotifOfflineChange(it) }
        Spacer(modifier = Modifier.height(8.dp))
        SetareSwitch("Alerte în fundal", "Notificări push de la aparat + verificare offline periodică (fără serviciu permanent).", runInBackground, accentColor, textPrimary, textSecondary, surfaceColor) { onRunInBackgroundChange(it) }
        Spacer(modifier = Modifier.height(8.dp))

        // Pauză alerte (ascuns implicit — apare la tap pe card)
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).border(1.dp, if (surfaceColor == Color.White) Paleta.Alb3 else Color.Transparent, RoundedCornerShape(12.dp)).padding(16.dp)) {
            var notifFreq by remember { mutableFloatStateOf(((prefs.getInt("notifFreqMins", 10) / 10f).roundToInt() * 10).toFloat().coerceIn(10f, 120f)) }
            var pauzaExtins by remember { mutableStateOf(false) }
            val pauzaRotatie by animateFloatAsState(if (pauzaExtins) 180f else 0f, label = "pauzaChevron")
            Row(modifier = Modifier.fillMaxWidth().clickable { pauzaExtins = !pauzaExtins }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Pauză Alerte", color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${notifFreq.roundToInt()} min", color = accentColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Extinde", tint = textSecondary, modifier = Modifier.padding(start = 4.dp).rotate(pauzaRotatie))
                }
            }
            AnimatedVisibility(visible = pauzaExtins) {
                Column {
                    Text("Timpul de așteptare între notificări pentru a evita spam-ul.", color = textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                    SliderInteligentCustom(value = notifFreq, valueRange = 10f..120f, stepSize = 10f, onValueChange = { notifFreq = it }, onValueChangeFinished = { prefs.edit(commit = true) { putInt("notifFreqMins", it.roundToInt()) }; database.child("setari/notif_freq_min").setValue(it.roundToInt()) }, accentColor = accentColor, textSecondary = textSecondary)
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ================= SETARE SWITCH =================
@Composable
fun SetareSwitch(
    titlu: String, descriere: String, checked: Boolean,
    accentColor: Color, textPrimary: Color, textSecondary: Color, surfaceColor: Color,
    onClick: () -> Unit = {}, onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).border(1.dp, if (surfaceColor == Color.White) Paleta.Alb3 else Color.Transparent, RoundedCornerShape(12.dp)).clickable { onClick() }.padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(titlu, color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(descriere, color = textSecondary, fontSize = 14.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accentColor, uncheckedThumbColor = Paleta.Gri1, uncheckedTrackColor = if (surfaceColor == Color.White) Paleta.Alb2 else Paleta.Gri2, uncheckedBorderColor = Color.Transparent)
        )
    }
}

// ================= COMPONENTE GRAFICE =================
@Composable
fun SenzorCard(
    titlu: String, valoareLive: Float, formatInit: Boolean, istoricTotal: List<Float>, interval: IntervalTimp,
    esteArhiva: Boolean, yMaxCap: Float?, faraZecimale: Boolean, textPrimary: Color, textSecondary: Color,
    tipGrafic: Int, chartId: String, globalTouchIndex: Int?, activeChartId: String?,
    onGlobalTouch: (Int?, String?) -> Unit, trendScheme: TrendScheme, trendValue: Int, lastHistoryTimeUnix: Long,
    douaZecimaleLive: Boolean = false, esteIndex: Boolean = false, colorLogic: (Float) -> Color
) {
    val currentUnix = System.currentTimeMillis() / 1000
    val intervaleLipsa = if (!esteArhiva && lastHistoryTimeUnix > 0) ((currentUnix - lastHistoryTimeUnix) / 300).toInt().coerceAtLeast(0) else 0

    val istoricTaiat = remember(istoricTotal, intervaleLipsa, interval) {
        val padded = if (intervaleLipsa > 0 && intervaleLipsa < interval.puncte) istoricTotal + List(intervaleLipsa) { -999f } else istoricTotal
        padded.takeLast(interval.puncte)
    }
    val anchorUnix = if (esteArhiva) 0L else if (lastHistoryTimeUnix > 0) lastHistoryTimeUnix + (intervaleLipsa * 300L) else currentUnix

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(titlu, color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val displayColor = if (formatInit || (esteArhiva && (istoricTotal.lastOrNull() ?: 0f) == -999f)) textSecondary else colorLogic(valoareLive)
                if (!esteArhiva && !formatInit) {
                    val isNeutral = trendValue == 0; val isUp = trendValue > 0
                    val arrowColor = when {
                        isNeutral -> textSecondary
                        trendScheme == TrendScheme.POLLUTANTS -> if (isUp) Paleta.Rosu1 else Paleta.Verde1
                        trendScheme == TrendScheme.TEMPERATURE -> if (isUp) Paleta.Rosu1 else Paleta.Albastru1
                        trendScheme == TrendScheme.HUMIDITY -> if (isUp) Paleta.Albastru1 else Paleta.Maro1
                        else -> textSecondary
                    }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(end = 6.dp).height(24.dp)) {
                        if (isNeutral) Text("-", color = arrowColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        else Text("^", color = arrowColor, fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.rotate(if (isUp) 0f else 180f))
                    }
                }
                val stringValoare = if (esteArhiva) "-"
                else if (formatInit) "Init"
                else if (faraZecimale) valoareLive.roundToInt().toString()
                else if (douaZecimaleLive) "%.2f".format(valoareLive)
                else "%.1f".format(valoareLive)
                if (!esteArhiva) Text(stringValoare, color = displayColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        GraficAvansat(chartId, istoricTaiat, interval, esteArhiva, yMaxCap, faraZecimale, esteIndex, textPrimary, textSecondary, tipGrafic, globalTouchIndex, activeChartId, onGlobalTouch, anchorUnix, colorLogic)
    }
}

@Composable
fun GraficAvansat(
    chartId: String, istoric: List<Float>, interval: IntervalTimp, esteArhiva: Boolean, yMaxCap: Float?,
    faraZecimale: Boolean, esteIndex: Boolean, textPrimary: Color, textSecondary: Color, tipGrafic: Int,
    globalTouchIndex: Int?, activeChartId: String?, onGlobalTouch: (Int?, String?) -> Unit,
    anchorUnix: Long, colorLogic: (Float) -> Color
) {
    if (istoric.isEmpty()) { Text("Colectare date...", color = textSecondary, fontSize = 12.sp); return }

    val istoricValid = remember(istoric) { istoric.filter { it != -999f } }
    var maxVal = (istoricValid.maxOrNull() ?: 1f) * 1.1f
    var minVal = (istoricValid.minOrNull() ?: 0f) * 0.9f
    if (yMaxCap != null && maxVal > yMaxCap) maxVal = yMaxCap
    if (minVal < 0f) minVal = 0f
    // Indici SEN66 (VOC/NOx): scalare NATURALA, ca restul graficelor (min*0.9 .. max*1.1).
    // DOAR in cazul degenerat (valoare ~constanta, ex: NOx mereu 1) span-ul natural e prea mic
    // si etichetele ar iesi identice ("1 1 1"). Atunci ancoram la valoarea curenta cu un span de 2
    // -> etichete distincte (ex: 1 / 2 / 3), cu linia jos. Valorile variabile/mari NU se ating.
    if (esteIndex && (maxVal - minVal) < 2f) {
        val vConst = istoricValid.minOrNull() ?: minVal
        minVal = kotlin.math.floor(vConst).coerceAtLeast(0f)
        maxVal = minVal + 2f
        if (yMaxCap != null && maxVal > yMaxCap) { maxVal = yMaxCap; minVal = (maxVal - 2f).coerceAtLeast(0f) }
    }
    val range = if (maxVal == minVal) 1f else maxVal - minVal
    val maxPuncte = interval.puncte
    val offsetStartIndex = maxPuncte - istoric.size

    // Paint-urile sunt stabile — recreate doar la schimbarea culorii textSecondary
    val bgPaint = remember { android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; alpha = 230; style = android.graphics.Paint.Style.FILL } }
    val etichetaYPaint = remember(textSecondary) { android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 28f; textAlign = android.graphics.Paint.Align.RIGHT } }
    val etichetaXPaint = remember { android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER } }
    val zeroEtichetaPaint = remember { android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f; textAlign = android.graphics.Paint.Align.CENTER } }
    val tooltipValoarePaint = remember { android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 34f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    val tooltipTimpPaint = remember { android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 24f; textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD } }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(start = 30.dp, bottom = 20.dp, end = 10.dp, top = 10.dp)
            .pointerInput(chartId, maxPuncte) {
                detectDragGestures(
                    onDragStart = { offset -> onGlobalTouch((offset.x / (size.width / (maxPuncte - 1).coerceAtLeast(1).toFloat())).roundToInt().coerceIn(0, maxPuncte - 1), chartId) },
                    onDragEnd = { onGlobalTouch(null, null) },
                    onDragCancel = { onGlobalTouch(null, null) },
                    onDrag = { change, _ -> onGlobalTouch((change.position.x / (size.width / (maxPuncte - 1).coerceAtLeast(1).toFloat())).roundToInt().coerceIn(0, maxPuncte - 1), chartId) }
                )
            }
            .pointerInput(chartId, maxPuncte) {
                detectTapGestures(onPress = { offset ->
                    onGlobalTouch((offset.x / (size.width / (maxPuncte - 1).coerceAtLeast(1).toFloat())).roundToInt().coerceIn(0, maxPuncte - 1), chartId)
                    tryAwaitRelease()
                    onGlobalTouch(null, null)
                })
            }
    ) {
        val w = size.width; val h = size.height
        val distantaX = w / (maxPuncte - 1).coerceAtLeast(1).toFloat()

        // Linii de grilă și etichete Y
        for (i in 0 until 3) {
            val yPos = h - (i * (h / 2))
            val valoareEticheta = minVal + (i * (range / 2))
            val etichetaText = if (faraZecimale) "%.0f".format(valoareEticheta) else if (range < 5f) "%.1f".format(valoareEticheta) else "%.0f".format(valoareEticheta)
            drawLine(textSecondary.copy(alpha = 0.3f), Offset(0f, yPos), Offset(w, yPos), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.drawText(etichetaText, -10f, yPos + 10f, etichetaYPaint)
        }

        // Etichete X
        for (i in 0..4) {
            val xPos = i * (w / 4)
            val eticheta = if (esteArhiva) arrayOf("00:00", "06:00", "12:00", "18:00", "24:00")[i]
            else {
                val ore = interval.ore - (i * (interval.ore / 4f))
                if (ore == 0f) "Acum" else "-${("%.0f".format(ore))}h"
            }
            drawContext.canvas.nativeCanvas.drawText(eticheta, xPos, h + 40f, etichetaXPaint)
        }

        // Desenare grafic
        if (tipGrafic == 0) {
            // Bars
            for (i in istoric.indices) {
                val v = istoric[i]; if (v == -999f) continue
                val x = (offsetStartIndex + i) * distantaX
                val baseRectTop = h - ((v - minVal) / range) * h
                val rectTop = baseRectTop.coerceAtMost(h - 4f)
                val barWidth = (distantaX * 0.7f).coerceIn(4f, 20f)
                drawRoundRect(color = colorLogic(v), topLeft = Offset(x - barWidth / 2, rectTop), size = Size(barWidth, h - rectTop), cornerRadius = CornerRadius(barWidth / 2, barWidth / 2))
            }
        } else if (tipGrafic == 1 || tipGrafic == 2) {
            // Spline / Step
            var fillPath: Path? = null; var startX = 0f; var lastX = 0f
            for (i in 0 until istoric.size - 1) {
                val val1 = istoric[i]; val val2 = istoric[i + 1]
                if (val1 == -999f || val2 == -999f) {
                    if (fillPath != null) {
                        fillPath.lineTo(lastX, h); fillPath.lineTo(startX, h); fillPath.close()
                        drawPath(fillPath, brush = Brush.verticalGradient(listOf(colorLogic(maxVal).copy(alpha = 0.7f), colorLogic(minVal).copy(alpha = 0.1f)), startY = 0f, endY = h))
                        fillPath = null
                    }
                    continue
                }
                val x1 = (offsetStartIndex + i) * distantaX; val y1 = h - ((val1 - minVal) / range) * h
                val x2 = (offsetStartIndex + i + 1) * distantaX; val y2 = h - ((val2 - minVal) / range) * h
                if (fillPath == null) { fillPath = Path().apply { moveTo(x1, y1) }; startX = x1 }
                if (tipGrafic == 1) {
                    // Spline Catmull-Rom: tangenta in fiecare punct tine cont de punctele vecine,
                    // deci curba curge lin prin puncte (fara forma de "∫" data de tangentele orizontale).
                    val arePrev = i - 1 >= 0 && istoric[i - 1] != -999f
                    val x0 = if (arePrev) (offsetStartIndex + i - 1) * distantaX else x1
                    val y0 = if (arePrev) h - ((istoric[i - 1] - minVal) / range) * h else y1
                    val areNext = i + 2 < istoric.size && istoric[i + 2] != -999f
                    val x3 = if (areNext) (offsetStartIndex + i + 2) * distantaX else x2
                    val y3 = if (areNext) h - ((istoric[i + 2] - minVal) / range) * h else y2
                    val c1x = x1 + (x2 - x0) / 6f; val c1y = y1 + (y2 - y0) / 6f
                    val c2x = x2 - (x3 - x1) / 6f; val c2y = y2 - (y3 - y1) / 6f
                    fillPath.cubicTo(c1x, c1y, c2x, c2y, x2, y2)
                    drawPath(Path().apply { moveTo(x1, y1); cubicTo(c1x, c1y, c2x, c2y, x2, y2) }, color = colorLogic(val2), style = Stroke(6f, cap = StrokeCap.Round))
                } else {
                    fillPath.lineTo(x2, y1); fillPath.lineTo(x2, y2)
                    drawPath(Path().apply { moveTo(x1, y1); lineTo(x2, y1); lineTo(x2, y2) }, color = colorLogic(val2), style = Stroke(6f, cap = StrokeCap.Round))
                }
                lastX = x2
            }
            if (fillPath != null) {
                fillPath.lineTo(lastX, h); fillPath.lineTo(startX, h); fillPath.close()
                drawPath(fillPath, brush = Brush.verticalGradient(listOf(colorLogic(maxVal).copy(alpha = 0.8f), colorLogic(minVal).copy(alpha = 0.15f)), startY = 0f, endY = h))
            }
        }

        // Linia de miezul nopții (00:00)
        if (!esteArhiva) {
            val zone = ZoneId.systemDefault()
            val midnightUnix = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
            val pointsSinceMidnight = ((anchorUnix - midnightUnix) / 300).toInt()
            val midnightIndex = interval.puncte - 1 - pointsSinceMidnight
            if (midnightIndex in 0 until interval.puncte) {
                val xMidnight = midnightIndex * distantaX
                drawLine(color = textSecondary.copy(alpha = 0.5f), start = Offset(xMidnight, 0f), end = Offset(xMidnight, h), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                drawContext.canvas.nativeCanvas.drawText("00:00", xMidnight, 15f, zeroEtichetaPaint)
            }
        }

        // Tooltip interactiv
        globalTouchIndex?.let { touchIndexGlobal ->
            val indexInIstoric = touchIndexGlobal - offsetStartIndex
            if (indexInIstoric in istoric.indices && istoric[indexInIstoric] != -999f) {
                val xFix = touchIndexGlobal * distantaX
                val valoareTouch = istoric[indexInIstoric]
                val yFix = h - ((valoareTouch - minVal) / range) * h
                drawLine(textPrimary.copy(alpha = 0.6f), Offset(xFix, 0f), Offset(xFix, h), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                drawCircle(textPrimary, 12f, Offset(xFix, yFix))
                drawCircle(colorLogic(valoareTouch), 8f, Offset(xFix, yFix))
                val textToDraw = if (faraZecimale) "%.0f".format(valoareTouch) else "%.1f".format(valoareTouch)
                var tooltipY = yFix - 45f; if (tooltipY < -10f) tooltipY = yFix + 60f
                drawContext.canvas.nativeCanvas.drawRoundRect(android.graphics.RectF(xFix - 50f, tooltipY - 35f, xFix + 50f, tooltipY + 15f), 12f, 12f, bgPaint)
                drawContext.canvas.nativeCanvas.drawText(textToDraw, xFix, tooltipY, tooltipValoarePaint)
                if (activeChartId == chartId) {
                    val timpTouchString = if (esteArhiva) {
                        val totalMin = indexInIstoric * 5
                        "%02d:%02d".format(totalMin / 60, totalMin % 60)
                    } else {
                        val pointUnix = anchorUnix - (maxPuncte - 1 - touchIndexGlobal) * 300L
                        val dateTime = Instant.ofEpochSecond(pointUnix).atZone(ZoneId.systemDefault())
                        val roundedMinute = (dateTime.minute / 5.0).roundToInt() * 5
                        val finalTime = if (roundedMinute == 60) dateTime.plusHours(1).withMinute(0) else dateTime.withMinute(roundedMinute)
                        "%02d:%02d".format(finalTime.hour, finalTime.minute)
                    }
                    val timeBubbleY = h + 25f
                    drawContext.canvas.nativeCanvas.drawRoundRect(android.graphics.RectF(xFix - 45f, timeBubbleY - 20f, xFix + 45f, timeBubbleY + 10f), 8f, 8f, bgPaint)
                    drawContext.canvas.nativeCanvas.drawText(timpTouchString, xFix, timeBubbleY, tooltipTimpPaint)
                }
            }
        }
    }
}