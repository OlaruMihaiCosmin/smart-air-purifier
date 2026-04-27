@file:Suppress("SpellCheckingInspection")

package com.example.smartpurifier

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ================= ENUMERĂRI ȘI VARIABILE GLOBALE =================
enum class IntervalTimp(val ore: Int, val puncte: Int) { ORE_4(4, 48), ORE_12(12, 144), ORE_24(24, 288) }
val culoriDisponibile = listOf(
    0xFF007AFF.toInt() to "Albastru",
    0xFF00E676.toInt() to "Verde",
    0xFFFF9500.toInt() to "Portocaliu",
    0xFFFF2D55.toInt() to "Roșu",
    0xFFAF52DE.toInt() to "Mov"
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

fun getTempColor(v: Float): Color {
    if (v == -999f) return Color.Transparent
    return when {
        v <= 16f -> Color(0xFF0D47A1)
        v <= 20f -> androidx.compose.ui.graphics.lerp(Color(0xFF0D47A1), Color(0xFF4CAF50), ((v - 16f) / 4f).coerceIn(0f, 1f))
        v <= 23f -> androidx.compose.ui.graphics.lerp(Color(0xFF4CAF50), Color(0xFFFBC02D), ((v - 20f) / 3f).coerceIn(0f, 1f))
        v <= 26f -> androidx.compose.ui.graphics.lerp(Color(0xFFFBC02D), Color(0xFFF57C00), ((v - 23f) / 3f).coerceIn(0f, 1f))
        v <= 28f -> androidx.compose.ui.graphics.lerp(Color(0xFFF57C00), Color(0xFFD32F2F), ((v - 26f) / 2f).coerceIn(0f, 1f))
        else -> Color(0xFF8B0000)
    }
}

fun getHumidColor(v: Float): Color {
    if (v == -999f) return Color.Transparent
    return when {
        v <= 0f -> Color(0xFF8D6E63)
        v <= 30f -> androidx.compose.ui.graphics.lerp(Color(0xFF8D6E63), Color(0xFF689F38), (v / 30f).coerceIn(0f, 1f))
        v <= 50f -> androidx.compose.ui.graphics.lerp(Color(0xFF689F38), Color(0xFF4CAF50), ((v - 30f) / 20f).coerceIn(0f, 1f))
        v <= 80f -> androidx.compose.ui.graphics.lerp(Color(0xFF4CAF50), Color(0xFF00796B), ((v - 50f) / 30f).coerceIn(0f, 1f))
        v <= 90f -> androidx.compose.ui.graphics.lerp(Color(0xFF00796B), Color(0xFF1976D2), ((v - 80f) / 10f).coerceIn(0f, 1f))
        v <= 100f -> androidx.compose.ui.graphics.lerp(Color(0xFF1976D2), Color(0xFF0D47A1), ((v - 90f) / 10f).coerceIn(0f, 1f))
        else -> Color(0xFF0D47A1)
    }
}

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
                drawLine(color = Color(0xFF6E6E73), start = Offset(thumbRadiusPx, yCenter), end = Offset(thumbRadiusPx + stopPctEnd * trackWidth, yCenter), strokeWidth = trackHeightPx, cap = StrokeCap.Round)
            }
            drawCircle(color = Color.Black.copy(alpha = 0.1f), radius = thumbRadiusPx + 3f, center = Offset(activeEndX, yCenter + 4f))
            drawCircle(color = Color.White, radius = thumbRadiusPx, center = Offset(activeEndX, yCenter))
            drawCircle(color = Color(0xFFE5E5EA), radius = thumbRadiusPx, center = Offset(activeEndX, yCenter), style = Stroke(width = 2f))
        }
    }
}

// ================= WIDGET PROVIDER =================
class SmartPurifierWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {}
}

// ================= SERVICIUL DE FUNDAL =================
class SmartPurifierService : Service() {
    private val dbRoot = FirebaseDatabase.getInstance("https://YOUR_PROJECT-default-rtdb.firebasedatabase.app/").getReference("SenzoriAer")
    private var timpUltimaNotifPraf = 0L
    private var timpUltimaNotifGaze = 0L
    private var eraOffline = false
    private var initLoadDone = false
    private val activeListeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun ataseazaListener(ref: DatabaseReference, listener: ValueEventListener) {
        ref.addValueEventListener(listener)
        activeListeners.add(ref to listener)
    }

    override fun onCreate() {
        super.onCreate()
        val canalServiciu = NotificationChannel("SERVICIU_FUNDAL", "Rulare in fundal", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canalServiciu)

        val intentAplicatie = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentAplicatie = android.app.PendingIntent.getActivity(
            this, 0, intentAplicatie,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notificarePermanenta = NotificationCompat.Builder(this, "SERVICIU_FUNDAL")
            .setContentTitle("Smart Purifier Activ")
            .setContentText("Aparatul iti monitorizeaza aerul in fundal.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntentAplicatie)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notificarePermanenta, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notificarePermanenta)
        }

        var notifPraf = true; var notifGaze = true; var notifOffline = true
        var lastUpdate = System.currentTimeMillis()
        var curentPm25 = 0f; var curentCo2 = 0
        var curentVoc = 0f; var curentNox = 0f
        var nightModeStatus = false
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        ataseazaListener(dbRoot.child("app/notif_praf"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { notifPraf = s.getValue(Boolean::class.java) ?: true }
            override fun onCancelled(error: DatabaseError) {}
        })
        ataseazaListener(dbRoot.child("app/notif_gaze"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { notifGaze = s.getValue(Boolean::class.java) ?: true }
            override fun onCancelled(error: DatabaseError) {}
        })
        ataseazaListener(dbRoot.child("app/notif_offline"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { notifOffline = s.getValue(Boolean::class.java) ?: true }
            override fun onCancelled(error: DatabaseError) {}
        })
        ataseazaListener(dbRoot.child("setari/night_mode"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { nightModeStatus = s.getValue(Boolean::class.java) ?: false }
            override fun onCancelled(error: DatabaseError) {}
        })
        ataseazaListener(dbRoot.child("last_update"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                lastUpdate = s.getValue(Long::class.java) ?: System.currentTimeMillis()
                initLoadDone = true
                if (eraOffline) eraOffline = false
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        fun checkGazAlert() {
            if (!notifGaze || !initLoadDone) return
            val alertCo2 = prefs.getFloat("alertCo2", 1500f).toInt()
            val alertVoc = prefs.getFloat("alertVoc", 250f)
            val alertNox = prefs.getFloat("alertNox", 150f)
            val notifFreq = prefs.getInt("notifFreqMins", 10) * 60000L
            if (System.currentTimeMillis() - timpUltimaNotifGaze <= notifFreq) return
            val motiv = when {
                curentCo2 >= alertCo2 -> "CO2 a ajuns la $curentCo2 ppm!"
                curentVoc >= alertVoc -> "VOC a ajuns la nivelul ${curentVoc.toInt()}!"
                curentNox >= alertNox -> "NOx a ajuns la nivelul ${curentNox.toInt()}!"
                else -> return
            }
            trimiteAlarma("⚠️ Alertă Aerisire", motiv)
            timpUltimaNotifGaze = System.currentTimeMillis()
        }

        ataseazaListener(dbRoot.child("PM25"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                curentPm25 = s.getValue(Double::class.java)?.toFloat() ?: 0f
                updateWidget(this@SmartPurifierService, curentPm25, curentCo2)
                val alertPm = prefs.getFloat("alertPm", 35f)
                val notifFreq = prefs.getInt("notifFreqMins", 10) * 60000L
                if (notifPraf && curentPm25 >= alertPm && (System.currentTimeMillis() - timpUltimaNotifPraf > notifFreq) && initLoadDone) {
                    trimiteAlarma("⚠️ Alerta Praf", "Nivel ridicat de PM2.5: %.1f µg/m³!".format(curentPm25))
                    timpUltimaNotifPraf = System.currentTimeMillis()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        ataseazaListener(dbRoot.child("CO2"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                curentCo2 = s.getValue(Int::class.java) ?: 0
                updateWidget(this@SmartPurifierService, curentPm25, curentCo2)
                checkGazAlert()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        ataseazaListener(dbRoot.child("VOC"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                curentVoc = (s.getValue(Double::class.java)?.toFloat() ?: 0f).let { if (it > 1000f) 0f else it }
                checkGazAlert()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        ataseazaListener(dbRoot.child("NOx"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                curentNox = (s.getValue(Double::class.java)?.toFloat() ?: 0f).let { if (it > 1000f) 0f else it }
                checkGazAlert()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        serviceScope.launch {
            var lastScheduleCheck = 0L
            while (isActive) {
                val currentTime = System.currentTimeMillis()
                if (initLoadDone) {
                    val dif = (currentTime - lastUpdate) / 1000
                    if (dif >= 60 && !eraOffline && notifOffline) {
                        trimiteAlarma("⚠️ Dispozitiv Offline", "Purificatorul a pierdut conexiunea WiFi!")
                        eraOffline = true
                    }
                }
                if (currentTime - lastScheduleCheck > 60000) {
                    lastScheduleCheck = currentTime
                    val nightModeAuto = prefs.getBoolean("nightModeAuto", false)
                    if (nightModeAuto) {
                        val startHr = prefs.getInt("nmStartHr", 23); val startMin = prefs.getInt("nmStartMin", 0)
                        val endHr = prefs.getInt("nmEndHr", 7); val endMin = prefs.getInt("nmEndMin", 0)
                        val localTime = LocalTime.now()
                        val currentMinutes = localTime.hour * 60 + localTime.minute
                        val startMinutes = startHr * 60 + startMin; val endMinutes = endHr * 60 + endMin
                        val shouldBeOn = if (startMinutes <= endMinutes) currentMinutes in startMinutes until endMinutes
                        else currentMinutes !in endMinutes until startMinutes
                        if (shouldBeOn && !nightModeStatus) dbRoot.child("setari/night_mode").setValue(true)
                        else if (!shouldBeOn && nightModeStatus) dbRoot.child("setari/night_mode").setValue(false)
                    }
                }
                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        activeListeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        activeListeners.clear()
    }

    private fun updateWidget(context: Context, pm25: Float, co2: Int) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, SmartPurifierWidget::class.java)
        val remoteViews = RemoteViews(context.packageName, R.layout.widget_purificator)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val pmColorInt = when { pm25 < 12 -> 0xFF00E676.toInt(); pm25 < 35 -> 0xFFFFEB3B.toInt(); pm25 < 55 -> 0xFFFF9800.toInt(); else -> 0xFFF44336.toInt() }
        val coColorInt = when { co2 < 800 -> 0xFF00E676.toInt(); co2 < 1000 -> 0xFFFFEB3B.toInt(); co2 < 1500 -> 0xFFFF9800.toInt(); else -> 0xFFF44336.toInt() }
        val pmBitmap = createWidgetDial(value = pm25, max = 55f, label = "PM", valString = "%.0f".format(pm25), colorInt = pmColorInt)
        val co2Bitmap = createWidgetDial(value = (co2 - 400f).coerceAtLeast(0f), max = 1100f, label = "CO2", valString = co2.toString(), colorInt = coColorInt)
        remoteViews.setImageViewBitmap(R.id.iv_widget_pm, pmBitmap)
        remoteViews.setImageViewBitmap(R.id.iv_widget_co2, co2Bitmap)
        remoteViews.setOnClickPendingIntent(R.id.iv_widget_pm, pendingIntent)
        remoteViews.setOnClickPendingIntent(R.id.iv_widget_co2, pendingIntent)
        appWidgetManager.updateAppWidget(componentName, remoteViews)
    }

    private fun createWidgetDial(value: Float, max: Float, label: String, valString: String, colorInt: Int): android.graphics.Bitmap {
        val size = 300
        val bitmap = androidx.core.graphics.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val strokeW = 34f
        val rect = android.graphics.RectF(strokeW, strokeW, size - strokeW, size - strokeW - 45f)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = strokeW
        paint.strokeCap = android.graphics.Paint.Cap.ROUND; paint.color = 0xFF333333.toInt()
        canvas.drawArc(rect, 135f, 270f, false, paint)
        paint.color = colorInt
        val sweepAngle = (value / max).coerceIn(0f, 1f) * 270f
        if (sweepAngle > 0) canvas.drawArc(rect, 135f, sweepAngle, false, paint)
        paint.style = android.graphics.Paint.Style.FILL; paint.color = android.graphics.Color.WHITE
        paint.textSize = 55f; paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        canvas.drawText(label, rect.centerX(), rect.centerY() - ((paint.descent() + paint.ascent()) / 2), paint)
        paint.textSize = 65f
        canvas.drawText(valString, rect.centerX(), size.toFloat() - 5f, paint)
        return bitmap
    }

    private fun trimiteAlarma(titlu: String, mesaj: String) {
        val intentAplicatie = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentAplicatie = android.app.PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intentAplicatie,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val builder = NotificationCompat.Builder(this, "ALERTE_AER")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(titlu).setContentText(mesaj)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true).setContentIntent(pendingIntentAplicatie)
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
}

// ================= ACTIVITY PRINCIPAL =================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { FirebaseDatabase.getInstance().setPersistenceEnabled(true) } catch (_: Exception) {}
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.email != "YOUR_APP_EMAIL") {
            auth.signOut()
            auth.signInWithEmailAndPassword("YOUR_APP_EMAIL", "YOUR_PASSWORD")
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
                                        "Eroare Firebase:\n\n$mesajEroare\n\nVerifică adresa de email și parola în consola Firebase!",
                                        color = Color.Red, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
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
        val channel = NotificationChannel("ALERTE_AER", "Alerte Calitate Aer", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("runInBackground", true)) {
            startForegroundService(Intent(this, SmartPurifierService::class.java))
        }
        setContent { MaterialTheme { PurificatorAppMain() } }
    }
}

// ================= COMPONENTA PRINCIPALA =================
@Composable
fun PurificatorAppMain() {
    val context = LocalContext.current
    val database = FirebaseDatabase.getInstance("https://YOUR_PROJECT-default-rtdb.firebasedatabase.app/").getReference("SenzoriAer")
    val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val liveModeJob = remember { mutableStateOf<Job?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START,
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    liveModeJob.value?.cancel(); liveModeJob.value = null
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

    var isDarkMode by remember { mutableStateOf(prefs.getBoolean("isDarkMode", true)) }
    var accentColor by remember { mutableStateOf(Color(prefs.getInt("themeColor", 0xFF007AFF.toInt()))) }
    var tipGrafic by remember { mutableIntStateOf(prefs.getInt("tipGrafic", 0)) }
    var runInBackground by remember { mutableStateOf(prefs.getBoolean("runInBackground", true)) }
    var categoryOrder by remember { mutableStateOf((prefs.getString("catOrder", "0,1,2") ?: "0,1,2").split(",").mapNotNull { it.toIntOrNull() }) }

    val bgMain = if (isDarkMode) Color(0xFF000000) else Color(0xFFF2F2F7)
    val surfaceColor = if (isDarkMode) Color(0xFF1C1C1E) else Color.White
    val textPrimary = if (isDarkMode) Color.White else Color.Black
    val textSecondary = if (isDarkMode) Color.Gray else Color.DarkGray
    val dividerColor = if (isDarkMode) Color.DarkGray else Color(0xFFD1D1D6)

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
        asculta("app/notif_praf", Boolean::class.java) { (it as? Boolean)?.let { v -> notifPraf = v } }
        asculta("app/notif_gaze", Boolean::class.java) { (it as? Boolean)?.let { v -> notifGaze = v } }
        asculta("app/notif_offline", Boolean::class.java) { (it as? Boolean)?.let { v -> notifOffline = v } }
        asculta("ErrorLog", String::class.java) { (it as? String)?.let { v -> errorLogServer = v } }
        asculta("PM1", Double::class.java) { (it as? Double)?.let { v -> pm1 = v.toFloat() } }
        asculta("PM25", Double::class.java) { (it as? Double)?.let { v -> pm25 = v.toFloat() } }
        asculta("PM4", Double::class.java) { (it as? Double)?.let { v -> pm4 = v.toFloat() } }
        asculta("PM10", Double::class.java) { (it as? Double)?.let { v -> pm10 = v.toFloat() } }
        asculta("CO2", Int::class.java) { (it as? Int)?.let { v -> co2 = v } }
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

    Scaffold(
        containerColor = bgMain,
        bottomBar = {
            NavigationBar(containerColor = surfaceColor, modifier = Modifier.height(60.dp)) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, null) }, selected = tabSelectat == 0,
                    onClick = { tabSelectat = 0 },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor, unselectedIconColor = textSecondary, indicatorColor = if (isDarkMode) Color(0xFF2C2C2E) else Color(0xFFE5E5EA))
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.DateRange, null) }, selected = tabSelectat == 1,
                    onClick = { tabSelectat = 1 },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor, unselectedIconColor = textSecondary, indicatorColor = if (isDarkMode) Color(0xFF2C2C2E) else Color(0xFFE5E5EA))
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, null) }, selected = tabSelectat == 2,
                    onClick = { tabSelectat = 2 },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor, unselectedIconColor = textSecondary, indicatorColor = if (isDarkMode) Color(0xFF2C2C2E) else Color(0xFFE5E5EA))
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
                    context = context, prefs = prefs, nightModeActiv = nightModeApp, liveModeActiv = liveModeServer,
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
                    onNotifPrafChange = { database.child("app/notif_praf").setValue(it) },
                    onNotifGazeChange = { database.child("app/notif_gaze").setValue(it) },
                    onNotifOfflineChange = { database.child("app/notif_offline").setValue(it) },
                    onRunInBackgroundChange = { v ->
                        runInBackground = v
                        prefs.edit(commit = true) { putBoolean("runInBackground", v) }
                        val i = Intent(context, SmartPurifierService::class.java)
                        if (v) context.startForegroundService(i) else context.stopService(i)
                    },
                    onThemeChange = { v -> isDarkMode = v; prefs.edit(commit = true) { putBoolean("isDarkMode", v) } },
                    onColorChange = { v -> accentColor = Color(v); prefs.edit(commit = true) { putInt("themeColor", v) } },
                    onTipGraficChange = { v -> tipGrafic = v; prefs.edit(commit = true) { putInt("tipGrafic", v) } },
                    onCleanSensor = { database.child("setari/curatare").setValue(true) },
                    onRestartBoard = { database.child("setari/restart").setValue(true) },
                    onTempOffsetChange = { v -> database.child("setari/temp_offset").setValue((v * 10f).roundToInt() / 10.0) },
                    onResetCalibrari = { database.child("setari/reset_calibrare").setValue(true) }
                )
            }
        }
    }
}

// ================= ECRAN SCOR ZILNIC — CALCUL ÎMBUNĂTĂȚIT =================
/**
 * Calculul scorului de calitate aer pe ultimele 24h.
 *
 * Îmbunătățiri față de versiunea anterioară:
 * 1. Sub-scorurile sunt acum non-liniare (penalty exponențial la depășiri severe)
 * 2. Penalizare suplimentară dacă ORICARE poluant depășește pragul critic (override)
 * 3. Ponderi ajustate: PM2.5 rămâne cel mai important (45%), CO2 30%, VOC 15%, NOx 10%
 * 4. Bonus mic (+3 puncte) dacă toate valorile sunt sub pragul "excelent" simultan
 * 5. Dacă datele lipsesc complet pentru un poluant, sub-scorul lui e ignorat din medie
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
    data class SubScor(val valoare: Float, val areDate: Boolean)

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

    val scorFinal = remember(istoricPM25, istoricCO2, istoricVOC, istoricNOx) {
        val pmValid = istoricPM25.filter { it != -999f && it >= 0f }
        val co2Valid = istoricCO2.filter { it != -999f && it >= 0f }
        val vocValid = istoricVOC.filter { it != -999f && it >= 0f }
        val noxValid = istoricNOx.filter { it != -999f && it >= 0f }

        // Dacă nu avem absolut nicio dată, returnăm 100
        if (pmValid.isEmpty() && co2Valid.isEmpty()) return@remember 100

        val avgPm = if (pmValid.isNotEmpty()) pmValid.average().toFloat() else -1f
        val avgCo2 = if (co2Valid.isNotEmpty()) co2Valid.average().toFloat() else -1f
        val avgVoc = if (vocValid.isNotEmpty()) vocValid.average().toFloat() else -1f
        val avgNox = if (noxValid.isNotEmpty()) noxValid.average().toFloat() else -1f

        // Sub-scoruri cu non-linearitate
        val subScoruri = listOfNotNull(
            if (avgPm >= 0f) SubScor(calcSubScorPm(avgPm), true) to 0.45f else null,
            if (avgCo2 >= 0f) SubScor(calcSubScorCo2(avgCo2), true) to 0.30f else null,
            if (avgVoc >= 0f) SubScor(calcSubScorVoc(avgVoc), true) to 0.15f else null,
            if (avgNox >= 0f) SubScor(calcSubScorNox(avgNox), true) to 0.10f else null
        )

        // Re-normalizăm ponderile dacă lipsesc date
        val totalPondere = subScoruri.sumOf { it.second.toDouble() }.toFloat()
        val scorPonderat = if (totalPondere > 0f) {
            subScoruri.sumOf { (sub, pondere) -> (sub.valoare * pondere / totalPondere).toDouble() }.toFloat()
        } else 100f

        // Penalizare critică: dacă vreun poluant depășește pragul grav, taiem scorul
        val penalizareCritica = when {
            avgPm > 55f -> 15f
            avgCo2 > 1500f -> 10f
            avgVoc > 400f -> 8f
            avgNox > 300f -> 5f
            else -> 0f
        }

        // Bonus dacă totul e în zona excelentă
        val bonus = if (
            (avgPm < 0f || avgPm <= 12f) &&
            (avgCo2 < 0f || avgCo2 <= 700f) &&
            (avgVoc < 0f || avgVoc <= 100f) &&
            (avgNox < 0f || avgNox <= 20f)
        ) 3f else 0f

        (scorPonderat - penalizareCritica + bonus).coerceIn(0f, 100f).roundToInt()
    }

    val colorScor = when {
        scorFinal >= 90 -> Color(0xFF4CAF50)   // Verde
        scorFinal >= 75 -> Color(0xFF8BC34A)   // Verde-gălbui
        scorFinal >= 60 -> Color(0xFFFFC107)   // Galben
        scorFinal >= 40 -> Color(0xFFFF9800)   // Portocaliu
        else -> Color(0xFFF44336)              // Roșu
    }
    val mesaj = when {
        scorFinal >= 90 -> "Aer Excelent azi"
        scorFinal >= 75 -> "Aer Bun"
        scorFinal >= 60 -> "Calitate Medie, mai aerisește"
        scorFinal >= 40 -> "Calitate Scăzută"
        else -> "Poluare Ridicată!"
    }

    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(surfaceColor).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(color = colorScor.copy(alpha = 0.2f), startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                drawArc(color = colorScor, startAngle = -90f, sweepAngle = (scorFinal / 100f) * 360f, useCenter = false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            }
            Text("$scorFinal", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
                diferentaSecunde < 60 -> Color(0xFFFF9800)
                else -> Color.Red
            }
            Text(statusText, color = statusColor, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))

        val isAuto = speedControlServer == 0f
        val isOprit = localSliderValue < 5f && !isAuto
        val displaySliderValue = if (isAuto) procentVentilatorServer.toFloat().coerceIn(1f, 100f) else localSliderValue.coerceIn(1f, 100f)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Putere: ${if (isOprit) "OPRIT" else "${displaySliderValue.roundToInt()}%"}",
                color = if (isOprit) Color.Red.copy(alpha = 0.8f) else textPrimary,
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
                SenzorCard("PM 1.0", animPm1, false, istoricPM1, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM1", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendPm1, lastHistoryTimeUnix) { v -> if (v == -999f) Color.Transparent else if (v < 10) Color.Green else if (v < 20) Color(0xFFFFC107) else if (v < 30) Color(0xFFFF9800) else Color.Red }
                SenzorCard("PM 2.5", animPm25, false, istoricPM25, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM25", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendPm25, lastHistoryTimeUnix) { v -> if (v == -999f) Color.Transparent else if (v < 12) Color.Green else if (v < 35) Color(0xFFFFC107) else if (v < 55) Color(0xFFFF9800) else Color.Red }
                SenzorCard("PM 4.0", animPm4, false, istoricPM4, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM4", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendPm4, lastHistoryTimeUnix) { v -> if (v == -999f) Color.Transparent else if (v < 25) Color.Green else if (v < 50) Color(0xFFFFC107) else if (v < 75) Color(0xFFFF9800) else Color.Red }
                SenzorCard("PM 10", animPm10, false, istoricPM10, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM10", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendPm10, lastHistoryTimeUnix) { v -> if (v == -999f) Color.Transparent else if (v < 50) Color.Green else if (v < 100) Color(0xFFFFC107) else if (v < 150) Color(0xFFFF9800) else Color.Red }
            },
            contentGaze = {
                SenzorCard("CO2 (ppm)", animCo2, false, istoricCO2, intervalSelectat, false, null, true, textPrimary, textSecondary, tipGrafic, "CO2", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendCo2, lastHistoryTimeUnix) { v -> if (v == -999f) Color.Transparent else if (v < 800) Color.Green else if (v < 1000) Color(0xFFFFC107) else if (v < 1500) Color(0xFFFF9800) else Color.Red }
                SenzorCard("VOC (Index)", animVoc, voc > 1000f, istoricVOC, intervalSelectat, false, null, true, textPrimary, textSecondary, tipGrafic, "VOC", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendVoc, lastHistoryTimeUnix) { v -> if (v == -999f) Color.Transparent else if (v < 150) Color.Green else if (v < 250) Color(0xFFFFC107) else if (v < 400) Color(0xFFFF9800) else Color.Red }
                SenzorCard("NOx (Index)", animNox, nox > 1000f, istoricNOx, intervalSelectat, false, null, true, textPrimary, textSecondary, tipGrafic, "NOx", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, trendNox, lastHistoryTimeUnix) { v -> if (v == -999f) Color.Transparent else if (v < 20) Color.Green else if (v < 150) Color(0xFFFFC107) else if (v < 300) Color(0xFFFF9800) else Color.Red }
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

// ================= TAB 2: ARHIVĂ =================
@Composable
fun EcranArhiva(
    prefs: SharedPreferences, categoryOrder: List<Int>, onOrderChange: (List<Int>) -> Unit,
    database: DatabaseReference, accentColor: Color, textPrimary: Color, textSecondary: Color,
    surfaceColor: Color, dividerColor: Color, tipGrafic: Int
) {
    var arhivaData by remember { mutableStateOf<Map<String, Map<String, List<Float>>>>(emptyMap()) }
    var listaZileReale by remember { mutableStateOf<List<Pair<String, LocalDate>>>(emptyList()) }
    var saptamanaSelectata by remember { mutableStateOf<LocalDate?>(null) }
    var ziSelectata by remember { mutableStateOf<String?>(null) }
    val formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd")

    var globalTouchIndex by remember { mutableStateOf<Int?>(null) }
    var activeChartId by remember { mutableStateOf<String?>(null) }
    val onGlobalTouch: (Int?, String?) -> Unit = { idx, id -> globalTouchIndex = idx; activeChartId = id }

    DisposableEffect(Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val mapNou = mutableMapOf<String, Map<String, List<Float>>>()
                val zileParsate = mutableListOf<Pair<String, LocalDate>>()
                for (ziSnap in snapshot.children) {
                    val key = ziSnap.key ?: continue
                    try {
                        val dateObj = LocalDate.parse(key, formatter)
                        zileParsate.add(key to dateObj)
                        mapNou[key] = mapOf(
                            "PM25" to parseIstoric(ziSnap.child("PM25").getValue(String::class.java)),
                            "CO2" to parseIstoric(ziSnap.child("CO2").getValue(String::class.java)),
                            "VOC" to parseIstoric(ziSnap.child("VOC").getValue(String::class.java), true),
                            "NOx" to parseIstoric(ziSnap.child("NOx").getValue(String::class.java), true),
                            "Temp" to parseIstoric(ziSnap.child("Temp").getValue(String::class.java)),
                            "RH" to parseIstoric(ziSnap.child("Umid").getValue(String::class.java))
                        )
                    } catch (_: Exception) { continue }
                }
                arhivaData = mapNou
                listaZileReale = zileParsate
                if (zileParsate.isNotEmpty()) {
                    val ultimaZi = zileParsate.last()
                    val saptamanaUltimeiZile = ultimaZi.second.with(DayOfWeek.MONDAY)
                    if (saptamanaSelectata == null || zileParsate.none { it.first == ziSelectata }) {
                        saptamanaSelectata = saptamanaUltimeiZile
                        ziSelectata = ultimaZi.first
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        val query = database.child("Arhiva_Zilnica").orderByKey().limitToLast(28)
        query.addValueEventListener(listener)
        onDispose { query.removeEventListener(listener) }
    }

    val saptamaniMap = remember(listaZileReale) { listaZileReale.groupBy { it.second.with(DayOfWeek.MONDAY) } }
    val saptamaniValide = remember(saptamaniMap) { saptamaniMap.keys.sorted().takeLast(4) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("ARHIVĂ CALITATE AER", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    SenzorCard("PM 2.5 (Praf)", 0f, true, dateZi["PM25"] ?: emptyList(), IntervalTimp.ORE_24, true, null, false, textPrimary, textSecondary, tipGrafic, "AR_PM25", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, 0, 0L) { v -> if (v == -999f) Color.Transparent else if (v < 12) Color.Green else if (v < 35) Color(0xFFFFC107) else if (v < 55) Color(0xFFFF9800) else Color.Red }
                },
                contentGaze = {
                    SenzorCard("CO2 (Dioxid de Carbon)", 0f, true, dateZi["CO2"] ?: emptyList(), IntervalTimp.ORE_24, true, null, true, textPrimary, textSecondary, tipGrafic, "AR_CO2", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, 0, 0L) { v -> if (v == -999f) Color.Transparent else if (v < 800) Color.Green else if (v < 1000) Color(0xFFFFC107) else if (v < 1500) Color(0xFFFF9800) else Color.Red }
                    SenzorCard("VOC (Chimicale)", 0f, true, dateZi["VOC"] ?: emptyList(), IntervalTimp.ORE_24, true, null, true, textPrimary, textSecondary, tipGrafic, "AR_VOC", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, 0, 0L) { v -> if (v == -999f) Color.Transparent else if (v < 150) Color.Green else if (v < 250) Color(0xFFFFC107) else if (v < 400) Color(0xFFFF9800) else Color.Red }
                    SenzorCard("NOx (Gaze eșapament)", 0f, true, dateZi["NOx"] ?: emptyList(), IntervalTimp.ORE_24, true, null, true, textPrimary, textSecondary, tipGrafic, "AR_NOx", globalTouchIndex, activeChartId, onGlobalTouch, TrendScheme.POLLUTANTS, 0, 0L) { v -> if (v == -999f) Color.Transparent else if (v < 20) Color.Green else if (v < 150) Color(0xFFFFC107) else if (v < 300) Color(0xFFFF9800) else Color.Red }
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
    context: Context, prefs: SharedPreferences, nightModeActiv: Boolean, liveModeActiv: Boolean,
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
    onTempOffsetChange: (Float) -> Unit, onResetCalibrari: () -> Unit
) {
    // FIX: Toate dialogurile folosesc acum `var` simplu — elimină warningul "Assigned value is never read"
    // care apărea din cauza destructuring-ului `val (show, setShow) = remember { mutableStateOf(false) }`
    // unde `setShow` era interpretat ca valoare niciodată citită de către IDE.
    var showCleanDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var showResetCalibDialog by remember { mutableStateOf(false) }
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
            title = { Text("Restartare Sistem", fontWeight = FontWeight.Bold, color = Color.Red) },
            text = { Text("Plăcuța ESP32 va fi oprită și repornită forțat. Conexiunea WiFi se va întrerupe pentru câteva secunde. Continuăm?") },
            confirmButton = { TextButton(onClick = { showRestartDialog = false; onRestartBoard() }) { Text("Restart", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showRestartDialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }
    if (showResetCalibDialog) {
        AlertDialog(
            onDismissRequest = { showResetCalibDialog = false },
            title = { Text("Resetare Calibrări", fontWeight = FontWeight.Bold, color = Color.Red) },
            text = { Text("Atenție! Această acțiune va șterge permanent memoria internă a algoritmului VOC. Senzorul va avea nevoie de 12-24 de ore să se re-calibreze. Aparatul se va restarta automat. Ești sigur?") },
            confirmButton = { TextButton(onClick = { showResetCalibDialog = false; onResetCalibrari() }) { Text("Resetare", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showResetCalibDialog = false }) { Text("Anulează", color = textSecondary) } },
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
                            item { Text("Sistemul funcționează perfect! Nicio eroare.", color = Color.Green) }
                        } else {
                            items(parsedErrors) { err ->
                                val errColor = when {
                                    err.contains("Calibrare VOC", ignoreCase = true) -> textPrimary
                                    err.contains("Curatare", ignoreCase = true) || err.contains("Offset", ignoreCase = true) -> Color(0xFFFFFF0A)
                                    err.contains("OTA", ignoreCase = true) || err.contains("USB", ignoreCase = true) -> Color(0xFFFF9600)
                                    err.contains("refacuta", ignoreCase = true) -> Color(0xFFAF52DE)
                                    err.contains("creata", ignoreCase = true) -> Color(0xFF34C759)
                                    err.contains("Eroare", ignoreCase = true) || err.contains("WDT", ignoreCase = true) ||
                                            err.contains("Panic", ignoreCase = true) || err.contains("Blocat", ignoreCase = true) ||
                                            err.contains("Restart", ignoreCase = true) || err.contains("Pierdut", ignoreCase = true) ||
                                            err.contains("Deconectat", ignoreCase = true) || err.contains("Fail", ignoreCase = true) -> Color.Red
                                    else -> Color(0xFF007AFF)
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
                                val db = FirebaseDatabase.getInstance("https://YOUR_PROJECT-default-rtdb.firebasedatabase.app/").getReference("SenzoriAer/Arhiva_Zilnica")
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
                                            for (i in pmList.indices) {
                                                val pm = pmList.getOrNull(i) ?: -999f
                                                if (pm == -999f) continue
                                                val totalMin = i * 5
                                                append("$dateStr,${"%02d:%02d".format(totalMin / 60, totalMin % 60)},$pm,${(co2List.getOrNull(i) ?: -999f).toInt()},${(vocList.getOrNull(i) ?: -999f).toInt()},${(noxList.getOrNull(i) ?: -999f).toInt()},${tempList.getOrNull(i) ?: -999f},${humList.getOrNull(i) ?: -999f}\n")
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

    // Dialog prag PM2.5
    var showPmDialog by remember { mutableStateOf(false) }
    var pmVal by remember { mutableStateOf(prefs.getFloat("alertPm", 35f).toInt().toString()) }
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
            confirmButton = { TextButton(onClick = { pmVal.toFloatOrNull()?.let { prefs.edit(commit = true) { putFloat("alertPm", it) } }; showPmDialog = false }) { Text("Salvează", color = accentColor) } },
            dismissButton = { TextButton(onClick = { showPmDialog = false }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary
        )
    }

    // Dialog praguri gaze
    var showGazeDialog by remember { mutableStateOf(false) }
    var co2Val by remember { mutableStateOf(prefs.getFloat("alertCo2", 1500f).toInt().toString()) }
    var vocVal by remember { mutableStateOf(prefs.getFloat("alertVoc", 250f).toInt().toString()) }
    var noxVal by remember { mutableStateOf(prefs.getFloat("alertNox", 150f).toInt().toString()) }
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
                    co2Val.toFloatOrNull()?.let { prefs.edit(commit = true) { putFloat("alertCo2", it) } }
                    vocVal.toFloatOrNull()?.let { prefs.edit(commit = true) { putFloat("alertVoc", it) } }
                    noxVal.toFloatOrNull()?.let { prefs.edit(commit = true) { putFloat("alertNox", it) } }
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
        val startDialog = TimePickerDialog(context, { _, h, m -> startHr = h; startMin = m; prefs.edit(commit = true) { putInt("nmStartHr", h); putInt("nmStartMin", m) } }, startHr, startMin, true)
        val endDialog = TimePickerDialog(context, { _, h, m -> endHr = h; endMin = m; prefs.edit(commit = true) { putInt("nmEndHr", h); putInt("nmEndMin", m) } }, endHr, endMin, true)
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
                            onCheckedChange = { autoNightMode = it; prefs.edit(commit = true) { putBoolean("nightModeAuto", it) } },
                            modifier = Modifier.scale(0.7f).height(24.dp),
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accentColor, uncheckedThumbColor = Color(0xFF8E8E93), uncheckedTrackColor = if (surfaceColor == Color.White) Color(0xFFE5E5EA) else Color(0xFF3A3A3C), uncheckedBorderColor = Color.Transparent)
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
            Box(modifier = Modifier.size(130.dp, 90.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFF2F2F7)).border(if (!isDarkMode) 3.dp else 1.dp, if (!isDarkMode) accentColor else Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(12.dp).clickable { onThemeChange(false) }) {
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
            Box(modifier = Modifier.size(130.dp, 90.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF121212)).border(if (isDarkMode) 3.dp else 1.dp, if (isDarkMode) accentColor else Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(12.dp).clickable { onThemeChange(true) }) {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2C2C2E)))
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
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).padding(16.dp), horizontalArrangement = Arrangement.SpaceAround) {
            culoriDisponibile.forEach { (colorInt, _) ->
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(colorInt)).border(width = if (accentColor == Color(colorInt)) 3.dp else 0.dp, color = if (accentColor == Color(colorInt)) textPrimary else Color.Transparent, shape = CircleShape).clickable { onColorChange(colorInt) })
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
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
                Switch(checked = liveModeActiv, onCheckedChange = onLiveModeChange, modifier = Modifier.scale(0.55f), colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accentColor, uncheckedThumbColor = Color(0xFF8E8E93), uncheckedTrackColor = if (surfaceColor == Color.White) Color(0xFFE5E5EA) else Color(0xFF3A3A3C), uncheckedBorderColor = Color.Transparent))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).clickable { showLiveModeDialog = true }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("RAM", color = textSecondary, fontSize = 10.sp); Text("${telemetrieRam / 1024} KB", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("CPU", color = textSecondary, fontSize = 10.sp); Text("${"%.1f".format(telemetrieTemp)}°C", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("WiFi", color = textSecondary, fontSize = 10.sp); Text("$telemetrieRssi dBm", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Uptime", color = textSecondary, fontSize = 10.sp); Text(formatUptime(telemetrieUptime), color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { showErrorDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, null, tint = Color(0xFFFF9500)); Spacer(Modifier.width(16.dp)); Text("Jurnal Erori (Ultimele 24h)", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { showRestartDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null, tint = Color.Red); Spacer(Modifier.width(16.dp)); Text("Restartare Sistem", color = Color.Red, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { showResetCalibDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Refresh, null, tint = Color(0xFFFF9500)); Spacer(Modifier.width(16.dp)); Text("Resetare Calibrări VOC", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { showCleanDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Settings, null, tint = accentColor); Spacer(Modifier.width(16.dp)); Text("Curățare Senzor SEN66", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { showExportDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Share, null, tint = Color(0xFF34C759)); Spacer(Modifier.width(16.dp)); Text("Exportă Datele (.csv)", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }

        // Offset temperatură
        var localOffset by remember { mutableFloatStateOf(tempOffsetServer) }
        var isOffsetDragging by remember { mutableStateOf(false) }
        LaunchedEffect(tempOffsetServer) { if (!isOffsetDragging) localOffset = tempOffsetServer }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Offset Temperatură", color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("${if (localOffset > 0) "+" else ""}${"%.1f".format(localOffset)} °C", color = accentColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text("Compensează căldura radiată de ventilator pentru o citire perfectă.", color = textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            SliderInteligentCustom(value = localOffset, valueRange = -3f..3f, stepSize = 0.1f, onValueChange = { isOffsetDragging = true; localOffset = it }, onValueChangeFinished = { isOffsetDragging = false; onTempOffsetChange(it) }, onDragStateChange = { isOffsetDragging = it }, accentColor = accentColor, textSecondary = textSecondary)
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("3. NOTIFICĂRI PUSH", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        SetareSwitch("Alerte Praf (PM2.5)", "Te notifică când depășește ${prefs.getFloat("alertPm", 35f).toInt()}.", notifPraf, accentColor, textPrimary, textSecondary, surfaceColor, onClick = { showPmDialog = true }) { onNotifPrafChange(it) }
        Spacer(modifier = Modifier.height(8.dp))
        SetareSwitch("Alertă Aerisire", "Când gazele (CO2/VOC/NOx) depășesc pragurile.", notifGaze, accentColor, textPrimary, textSecondary, surfaceColor, onClick = { showGazeDialog = true }) { onNotifGazeChange(it) }
        Spacer(modifier = Modifier.height(8.dp))
        SetareSwitch("Alertă Deconectare", "Primești notificare când aparatul cade din rețea.", notifOffline, accentColor, textPrimary, textSecondary, surfaceColor) { onNotifOfflineChange(it) }
        Spacer(modifier = Modifier.height(8.dp))
        SetareSwitch("Rulare în fundal", "Serviciu pentru primirea alertelor cu aplicația închisă.", runInBackground, accentColor, textPrimary, textSecondary, surfaceColor) { onRunInBackgroundChange(it) }
        Spacer(modifier = Modifier.height(8.dp))

        // Pauză alerte
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).padding(16.dp)) {
            var notifFreq by remember { mutableFloatStateOf(((prefs.getInt("notifFreqMins", 10) / 10f).roundToInt() * 10).toFloat().coerceIn(10f, 120f)) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pauză Alerte", color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("${notifFreq.roundToInt()} min", color = accentColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text("Timpul de așteptare între notificări pentru a evita spam-ul.", color = textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            SliderInteligentCustom(value = notifFreq, valueRange = 10f..120f, stepSize = 10f, onValueChange = { notifFreq = it }, onValueChangeFinished = { prefs.edit(commit = true) { putInt("notifFreqMins", it.roundToInt()) } }, accentColor = accentColor, textSecondary = textSecondary)
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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).clickable { onClick() }.padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(titlu, color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(descriere, color = textSecondary, fontSize = 14.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accentColor, uncheckedThumbColor = Color(0xFF8E8E93), uncheckedTrackColor = if (surfaceColor == Color.White) Color(0xFFE5E5EA) else Color(0xFF3A3A3C), uncheckedBorderColor = Color.Transparent)
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
    douaZecimaleLive: Boolean = false, colorLogic: (Float) -> Color
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
                        trendScheme == TrendScheme.POLLUTANTS -> if (isUp) Color(0xFFE53935) else Color(0xFF43A047)
                        trendScheme == TrendScheme.TEMPERATURE -> if (isUp) Color(0xFFE53935) else Color(0xFF1E88E5)
                        trendScheme == TrendScheme.HUMIDITY -> if (isUp) Color(0xFF1E88E5) else Color(0xFFFBC02D)
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
        GraficAvansat(chartId, istoricTaiat, interval, esteArhiva, yMaxCap, faraZecimale, textPrimary, textSecondary, tipGrafic, globalTouchIndex, activeChartId, onGlobalTouch, anchorUnix, colorLogic)
    }
}

@Composable
fun GraficAvansat(
    chartId: String, istoric: List<Float>, interval: IntervalTimp, esteArhiva: Boolean, yMaxCap: Float?,
    faraZecimale: Boolean, textPrimary: Color, textSecondary: Color, tipGrafic: Int,
    globalTouchIndex: Int?, activeChartId: String?, onGlobalTouch: (Int?, String?) -> Unit,
    anchorUnix: Long, colorLogic: (Float) -> Color
) {
    if (istoric.isEmpty()) { Text("Colectare date...", color = textSecondary, fontSize = 12.sp); return }

    val istoricValid = remember(istoric) { istoric.filter { it != -999f } }
    var maxVal = (istoricValid.maxOrNull() ?: 1f) * 1.1f
    var minVal = (istoricValid.minOrNull() ?: 0f) * 0.9f
    if (yMaxCap != null && maxVal > yMaxCap) maxVal = yMaxCap
    if (minVal < 0f) minVal = 0f
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
                    val cx = (x1 + x2) / 2f
                    fillPath.cubicTo(cx, y1, cx, y2, x2, y2)
                    drawPath(Path().apply { moveTo(x1, y1); cubicTo(cx, y1, cx, y2, x2, y2) }, color = colorLogic(val2), style = Stroke(6f, cap = StrokeCap.Round))
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