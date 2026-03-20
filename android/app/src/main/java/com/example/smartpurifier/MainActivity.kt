@file:Suppress("SpellCheckingInspection", "ObsoleteSdkInt", "RemoveRedundantQualifierName", "UnusedImport")

package com.example.smartpurifier

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import java.util.Locale

// ================= ENUMERĂRI ȘI VARIABILE GLOBALE =================
enum class IntervalTimp(val ore: Int, val puncte: Int) { ORE_4(4, 48), ORE_12(12, 144), ORE_24(24, 288) }
val culoriDisponibile = listOf(0xFF007AFF.toInt() to "Albastru", 0xFF00E676.toInt() to "Verde", 0xFFFF9500.toInt() to "Portocaliu", 0xFFFF2D55.toInt() to "Roșu", 0xFFAF52DE.toInt() to "Mov")

fun getMonthRoShort(month: Int) = arrayOf("", "ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec")[month]
fun getDayOfWeekRo(date: LocalDate): String = when(date.dayOfWeek) { DayOfWeek.MONDAY -> "Lu"; DayOfWeek.TUESDAY -> "Ma"; DayOfWeek.WEDNESDAY -> "Mi"; DayOfWeek.THURSDAY -> "Jo"; DayOfWeek.FRIDAY -> "Vi"; DayOfWeek.SATURDAY -> "Sâ"; DayOfWeek.SUNDAY -> "Du" }

fun parseIstoric(data: String?, isIndex: Boolean = false): List<Float> {
    if (data.isNullOrEmpty()) return emptyList()
    return data.split(",").mapNotNull {
        val v = it.toFloatOrNull()
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
        v <= 30f -> androidx.compose.ui.graphics.lerp(Color(0xFF8D6E63), Color(0xFF689F38), ((v - 0f) / 30f).coerceIn(0f, 1f))
        v <= 50f -> androidx.compose.ui.graphics.lerp(Color(0xFF689F38), Color(0xFF4CAF50), ((v - 30f) / 20f).coerceIn(0f, 1f))
        v <= 80f -> androidx.compose.ui.graphics.lerp(Color(0xFF4CAF50), Color(0xFF00796B), ((v - 50f) / 30f).coerceIn(0f, 1f))
        v <= 90f -> androidx.compose.ui.graphics.lerp(Color(0xFF00796B), Color(0xFF1976D2), ((v - 80f) / 10f).coerceIn(0f, 1f))
        v <= 100f -> androidx.compose.ui.graphics.lerp(Color(0xFF1976D2), Color(0xFF0D47A1), ((v - 90f) / 10f).coerceIn(0f, 1f))
        else -> Color(0xFF0D47A1)
    }
}

fun ascultaFirebase(db: DatabaseReference, child: String, type: Class<out Any>, onChange: (Any?) -> Unit) {
    db.child(child).addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) { onChange(snapshot.getValue(type)) }
        override fun onCancelled(error: DatabaseError) { Log.e("Firebase", "Eroare: ${error.message}") }
    })
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

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()

        FirebaseAuth.getInstance().signInAnonymously()

        val canalServiciu = NotificationChannel("SERVICIU_FUNDAL", "Rulare in fundal", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canalServiciu)

        val notificarePermanenta = NotificationCompat.Builder(this, "SERVICIU_FUNDAL")
            .setContentTitle("Smart Purifier Activ")
            .setContentText("Aparatul iti monitorizeaza aerul in fundal.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notificarePermanenta, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notificarePermanenta)
        }

        var notifPraf = true; var notifGaze = true; var notifOffline = true
        var lastUpdate = System.currentTimeMillis()
        var curentPm25 = 0f; var curentCo2 = 0

        dbRoot.child("setari/notif_praf").addValueEventListener(object: ValueEventListener { override fun onDataChange(s: DataSnapshot) { notifPraf = s.getValue(Boolean::class.java) ?: true }; override fun onCancelled(error: DatabaseError) {} })
        dbRoot.child("setari/notif_gaze").addValueEventListener(object: ValueEventListener { override fun onDataChange(s: DataSnapshot) { notifGaze = s.getValue(Boolean::class.java) ?: true }; override fun onCancelled(error: DatabaseError) {} })
        dbRoot.child("setari/notif_offline").addValueEventListener(object: ValueEventListener { override fun onDataChange(s: DataSnapshot) { notifOffline = s.getValue(Boolean::class.java) ?: true }; override fun onCancelled(error: DatabaseError) {} })

        dbRoot.child("last_update").addValueEventListener(object: ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                lastUpdate = s.getValue(Long::class.java) ?: System.currentTimeMillis()
                initLoadDone = true
                if (eraOffline) eraOffline = false
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        dbRoot.child("PM25").addValueEventListener(object: ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                curentPm25 = s.getValue(Double::class.java)?.toFloat() ?: 0f
                updateWidget(this@SmartPurifierService, curentPm25, curentCo2)
                if (notifPraf && curentPm25 >= 35f && (System.currentTimeMillis() - timpUltimaNotifPraf > 600000) && initLoadDone) {
                    trimiteAlarma("⚠️ Alerta Praf", "Nivel ridicat de PM2.5: $curentPm25 µg/m³!")
                    timpUltimaNotifPraf = System.currentTimeMillis()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        val gasListener = object: ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                var vocTemp = 0f; var noxTemp = 0f
                if(s.key == "CO2") { curentCo2 = s.getValue(Int::class.java) ?: 0; updateWidget(this@SmartPurifierService, curentPm25, curentCo2) }
                if(s.key == "VOC") vocTemp = s.getValue(Double::class.java)?.toFloat() ?: 0f
                if(s.key == "NOx") noxTemp = s.getValue(Double::class.java)?.toFloat() ?: 0f

                val vocV = if (vocTemp > 1000f) 0f else vocTemp
                val noxV = if (noxTemp > 1000f) 0f else noxTemp

                if (notifGaze && (curentCo2 >= 1500 || vocV >= 250f || noxV >= 150f) && (System.currentTimeMillis() - timpUltimaNotifGaze > 600000) && initLoadDone) {
                    val motiv = when { curentCo2 >= 1500 -> "CO2 a ajuns la $curentCo2 ppm!"; vocV >= 250f -> "VOC a ajuns la nivelul ${vocV.toInt()}!"; else -> "NOx a ajuns la nivelul ${noxV.toInt()}!" }
                    trimiteAlarma("⚠️ Alertă Aerisire", motiv)
                    timpUltimaNotifGaze = System.currentTimeMillis()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        dbRoot.child("CO2").addValueEventListener(gasListener)
        dbRoot.child("VOC").addValueEventListener(gasListener)
        dbRoot.child("NOx").addValueEventListener(gasListener)

        Thread {
            while(true) {
                if (initLoadDone) {
                    val dif = (System.currentTimeMillis() - lastUpdate) / 1000
                    val esteOfflineAcum = dif >= 60
                    if (esteOfflineAcum && !eraOffline && notifOffline) {
                        trimiteAlarma("⚠️ Dispozitiv Offline", "Purificatorul a pierdut conexiunea WiFi!")
                        eraOffline = true
                    }
                }
                Thread.sleep(2000)
            }
        }.start()
    }

    private fun updateWidget(context: Context, pm25: Float, co2: Int) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, SmartPurifierWidget::class.java)
        val remoteViews = RemoteViews(context.packageName, R.layout.widget_purificator)

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

        val pmColorInt = when { pm25 < 12 -> 0xFF00E676.toInt(); pm25 < 35 -> 0xFFFFEB3B.toInt(); pm25 < 55 -> 0xFFFF9800.toInt(); else -> 0xFFF44336.toInt() }
        val coColorInt = when { co2 < 800 -> 0xFF00E676.toInt(); co2 < 1000 -> 0xFFFFEB3B.toInt(); co2 < 1500 -> 0xFFFF9800.toInt(); else -> 0xFFF44336.toInt() }

        val pmBitmap = createWidgetDial(value = pm25, max = 55f, label = "PM", valString = String.format(Locale.US, "%.0f", pm25), colorInt = pmColorInt)
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

        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = strokeW
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        paint.color = 0xFF333333.toInt()
        canvas.drawArc(rect, 135f, 270f, false, paint)

        paint.color = colorInt
        val progressPercent = (value / max).coerceIn(0f, 1f)
        val sweepAngle = progressPercent * 270f
        if (sweepAngle > 0) {
            canvas.drawArc(rect, 135f, sweepAngle, false, paint)
        }

        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 55f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        val textY = rect.centerY() - ((paint.descent() + paint.ascent()) / 2)
        canvas.drawText(label, rect.centerX(), textY, paint)

        paint.textSize = 65f
        canvas.drawText(valString, rect.centerX(), size.toFloat() - 5f, paint)

        return bitmap
    }

    private fun trimiteAlarma(titlu: String, mesaj: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val builder = NotificationCompat.Builder(this, "ALERTE_AER")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(titlu)
                .setContentText(mesaj)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            with(NotificationManagerCompat.from(this)) { notify(System.currentTimeMillis().toInt(), builder.build()) }
        }
    }
}

// ================= ACTIVITY PRINCIPAL =================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try { FirebaseDatabase.getInstance().setPersistenceEnabled(true) } catch (_: Exception) {}

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnCompleteListener { pornireAplicatie() }
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

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("runInBackground", true)) {
            val intentService = Intent(this, SmartPurifierService::class.java)
            startForegroundService(intentService)
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

    var isDarkMode by remember { mutableStateOf(prefs.getBoolean("isDarkMode", true)) }
    var accentColor by remember { mutableStateOf(Color(prefs.getInt("themeColor", 0xFF007AFF.toInt()))) }
    var tipGrafic by remember { mutableIntStateOf(prefs.getInt("tipGrafic", 0)) }
    var runInBackground by remember { mutableStateOf(prefs.getBoolean("runInBackground", true)) }

    var categoryOrder by remember { mutableStateOf((prefs.getString("catOrder", "0,1,2") ?: "0,1,2").split(",").mapNotNull { it.toIntOrNull() }) }
    val onOrderChange: (List<Int>) -> Unit = { newOrder -> categoryOrder = newOrder; prefs.edit(commit = true) { putString("catOrder", newOrder.joinToString(",")) } }

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

    var rpmServer by remember { mutableIntStateOf(0) }
    var speedControlServer by remember { mutableFloatStateOf(0f) }
    var procentVentilatorServer by remember { mutableIntStateOf(0) }

    var nightModeApp by remember { mutableStateOf(false) }
    var notifPraf by remember { mutableStateOf(true) }
    var notifGaze by remember { mutableStateOf(true) }
    var notifOffline by remember { mutableStateOf(true) }
    var errorLogServer by remember { mutableStateOf("") }

    var pm1 by remember { mutableFloatStateOf(0f) }; var pm25 by remember { mutableFloatStateOf(0f) }
    var pm4 by remember { mutableFloatStateOf(0f) }; var pm10 by remember { mutableFloatStateOf(0f) }
    var co2 by remember { mutableIntStateOf(0) }; var voc by remember { mutableFloatStateOf(0f) }
    var nox by remember { mutableFloatStateOf(0f) }; var temp by remember { mutableFloatStateOf(0f) }
    var umiditate by remember { mutableFloatStateOf(0f) }

    var istoricPM1 by remember { mutableStateOf<List<Float>>(emptyList()) }; var istoricPM25 by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricPM4 by remember { mutableStateOf<List<Float>>(emptyList()) }; var istoricPM10 by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricCO2 by remember { mutableStateOf<List<Float>>(emptyList()) }; var istoricVOC by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricNOx by remember { mutableStateOf<List<Float>>(emptyList()) }; var istoricTemp by remember { mutableStateOf<List<Float>>(emptyList()) }
    var istoricUmid by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(ultimulUpdateServer, initLoadDone) {
        while (true) {
            if (initLoadDone) {
                diferentaSecunde = (System.currentTimeMillis() - ultimulUpdateServer) / 1000
            }
            delay(1000L)
        }
    }

    LaunchedEffect(Unit) {
        ascultaFirebase(database, "last_update", Long::class.java) { (it as? Long)?.let { v -> ultimulUpdateServer = v; initLoadDone = true } }
        ascultaFirebase(database, "RPM", Int::class.java) { (it as? Int)?.let { v -> rpmServer = v } }
        ascultaFirebase(database, "speed", Float::class.java) { (it as? Float)?.let { v -> speedControlServer = v } }
        ascultaFirebase(database, "ProcentVentilator", Int::class.java) { (it as? Int)?.let { v -> procentVentilatorServer = v } }

        ascultaFirebase(database, "setari/night_mode", Boolean::class.java) { (it as? Boolean)?.let { v -> nightModeApp = v } }
        ascultaFirebase(database, "setari/notif_praf", Boolean::class.java) { (it as? Boolean)?.let { v -> notifPraf = v } }
        ascultaFirebase(database, "setari/notif_gaze", Boolean::class.java) { (it as? Boolean)?.let { v -> notifGaze = v } }
        ascultaFirebase(database, "setari/notif_offline", Boolean::class.java) { (it as? Boolean)?.let { v -> notifOffline = v } }
        ascultaFirebase(database, "ErrorLog", String::class.java) { (it as? String)?.let { v -> errorLogServer = v } }

        ascultaFirebase(database, "PM1", Double::class.java) { (it as? Double)?.let { v -> pm1 = v.toFloat() } }
        ascultaFirebase(database, "PM25", Double::class.java) { (it as? Double)?.let { v -> pm25 = v.toFloat() } }
        ascultaFirebase(database, "PM4", Double::class.java) { (it as? Double)?.let { v -> pm4 = v.toFloat() } }
        ascultaFirebase(database, "PM10", Double::class.java) { (it as? Double)?.let { v -> pm10 = v.toFloat() } }
        ascultaFirebase(database, "CO2", Int::class.java) { (it as? Int)?.let { v -> co2 = v } }
        ascultaFirebase(database, "VOC", Double::class.java) { (it as? Double)?.let { v -> voc = v.toFloat() } }
        ascultaFirebase(database, "NOx", Double::class.java) { (it as? Double)?.let { v -> nox = v.toFloat() } }
        ascultaFirebase(database, "Temperatura", Double::class.java) { (it as? Double)?.let { v -> temp = v.toFloat() } }
        ascultaFirebase(database, "Umiditate", Double::class.java) { (it as? Double)?.let { v -> umiditate = v.toFloat() } }

        ascultaFirebase(database, "Istoric_PM1", String::class.java) { istoricPM1 = parseIstoric(it as? String) }
        ascultaFirebase(database, "Istoric_PM25", String::class.java) { istoricPM25 = parseIstoric(it as? String) }
        ascultaFirebase(database, "Istoric_PM4", String::class.java) { istoricPM4 = parseIstoric(it as? String) }
        ascultaFirebase(database, "Istoric_PM10", String::class.java) { istoricPM10 = parseIstoric(it as? String) }
        ascultaFirebase(database, "Istoric_CO2", String::class.java) { istoricCO2 = parseIstoric(it as? String) }
        ascultaFirebase(database, "Istoric_Temp", String::class.java) { istoricTemp = parseIstoric(it as? String) }
        ascultaFirebase(database, "Istoric_Umid", String::class.java) { istoricUmid = parseIstoric(it as? String) }
        ascultaFirebase(database, "Istoric_VOC", String::class.java) { istoricVOC = parseIstoric(it as? String, true) }
        ascultaFirebase(database, "Istoric_NOx", String::class.java) { istoricNOx = parseIstoric(it as? String, true) }
    }

    Scaffold(
        containerColor = bgMain,
        bottomBar = {
            NavigationBar(containerColor = surfaceColor, modifier = Modifier.height(60.dp)) {
                NavigationBarItem(icon = { Icon(Icons.Default.Home, null) }, selected = tabSelectat == 0, onClick = { tabSelectat = 0 }, colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor, unselectedIconColor = textSecondary, indicatorColor = if(isDarkMode) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)))
                NavigationBarItem(icon = { Icon(Icons.Default.DateRange, null) }, selected = tabSelectat == 1, onClick = { tabSelectat = 1 }, colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor, unselectedIconColor = textSecondary, indicatorColor = if(isDarkMode) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)))
                NavigationBarItem(icon = { Icon(Icons.Default.Settings, null) }, selected = tabSelectat == 2, onClick = { tabSelectat = 2 }, colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor, unselectedIconColor = textSecondary, indicatorColor = if(isDarkMode) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)))
            }
        }
    ) { paddingValori ->
        Box(modifier = Modifier.padding(paddingValori).fillMaxSize()) {
            when (tabSelectat) {
                0 -> EcranAcasa(prefs, categoryOrder, onOrderChange, initLoadDone, diferentaSecunde, speedControlServer, procentVentilatorServer, rpmServer, intervalSelectat, pm1, pm25, pm4, pm10, co2, voc, nox, temp, umiditate, istoricPM1, istoricPM25, istoricPM4, istoricPM10, istoricCO2, istoricVOC, istoricNOx, istoricTemp, istoricUmid, accentColor, textPrimary, textSecondary, surfaceColor, tipGrafic, onIntervalChange = { intervalSelectat = it }, onSpeedConfirmed = { database.child("speed").setValue(it.toInt()) })
                1 -> EcranArhiva(prefs, categoryOrder, onOrderChange, database, accentColor, textPrimary, textSecondary, surfaceColor, dividerColor, tipGrafic, istoricPM25, istoricCO2, istoricVOC, istoricNOx)
                2 -> EcranSetari(
                    context = context, nightModeActiv = nightModeApp, notifPraf = notifPraf, notifGaze = notifGaze, notifOffline = notifOffline, errorLogString = errorLogServer, runInBackground = runInBackground,
                    isDarkMode = isDarkMode, accentColor = accentColor, tipGrafic = tipGrafic, textPrimary = textPrimary, textSecondary = textSecondary, surfaceColor = surfaceColor,
                    istoricExportPM = istoricPM25, istoricExportCO2 = istoricCO2,
                    onNightModeChange = { database.child("setari/night_mode").setValue(it) },
                    onNotifPrafChange = { database.child("setari/notif_praf").setValue(it) },
                    onNotifGazeChange = { database.child("setari/notif_gaze").setValue(it) },
                    onNotifOfflineChange = { database.child("setari/notif_offline").setValue(it) },
                    onRunInBackgroundChange = {
                        runInBackground = it; prefs.edit(commit = true) { putBoolean("runInBackground", it) }
                        val i = Intent(context, SmartPurifierService::class.java)
                        if(it) { context.startForegroundService(i) } else { context.stopService(i) }
                    },
                    onThemeChange = { valMode -> isDarkMode = valMode; prefs.edit(commit = true) { putBoolean("isDarkMode", valMode) } },
                    onColorChange = { valColorInt -> accentColor = Color(valColorInt); prefs.edit(commit = true) { putInt("themeColor", valColorInt) } },
                    onTipGraficChange = { valTip -> tipGrafic = valTip; prefs.edit(commit = true) { putInt("tipGrafic", valTip) } },
                    onCleanSensor = { database.child("comenzi/curatare").setValue(true) },
                    onRestartBoard = { database.child("comenzi/restart").setValue(true) }
                )
            }
        }
    }
}

// ================= ECRAN SCOR ZILNIC =================
@Composable
fun CardScorZilnic(istoricPM25: List<Float>, istoricCO2: List<Float>, istoricVOC: List<Float>, istoricNOx: List<Float>, surfaceColor: Color, textPrimary: Color, textSecondary: Color) {
    val pmValid = istoricPM25.filter { it != -999f }; val co2Valid = istoricCO2.filter { it != -999f }
    val vocValid = istoricVOC.filter { it != -999f }; val noxValid = istoricNOx.filter { it != -999f }

    val avgPm = if (pmValid.isNotEmpty()) pmValid.average().toFloat() else 0f
    val avgCo2 = if (co2Valid.isNotEmpty()) co2Valid.average().toFloat() else 400f
    val avgVoc = if (vocValid.isNotEmpty()) vocValid.average().toFloat() else 0f
    val avgNox = if (noxValid.isNotEmpty()) noxValid.average().toFloat() else 0f

    val sPm = (100f - (avgPm / 35f * 50f)).coerceIn(0f, 100f)
    val sCo2 = (100f - ((avgCo2 - 400f) / 1100f * 100f)).coerceIn(0f, 100f)
    val sVoc = (100f - (avgVoc / 250f * 100f)).coerceIn(0f, 100f)
    val sNox = (100f - (avgNox / 150f * 100f)).coerceIn(0f, 100f)

    var scorFinal = ((sPm * 0.4f) + (sCo2 * 0.3f) + (sVoc * 0.15f) + (sNox * 0.15f)).roundToInt()
    if (pmValid.isEmpty()) scorFinal = 100

    val colorScor = when { scorFinal >= 90 -> Color.Green; scorFinal >= 70 -> Color(0xFFFFC107); scorFinal >= 50 -> Color(0xFFFF9800); else -> Color.Red }
    val mesaj = when { scorFinal >= 90 -> "Aer Excelent azi"; scorFinal >= 70 -> "Aer Bun, mai aerisește"; scorFinal >= 50 -> "Calitate Medie"; else -> "Poluare Ridicată!" }

    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(surfaceColor).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) { drawArc(color = colorScor.copy(alpha = 0.2f), startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round)); drawArc(color = colorScor, startAngle = -90f, sweepAngle = (scorFinal / 100f) * 360f, useCenter = false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round)) }
            Text("$scorFinal", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.width(16.dp))
        Column { Text("Scor Sănătate Zilnic", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(mesaj, color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
    }
}

// ================= DRAG & DROP & GLOBAL STATE =================
@Composable
fun DraggableCategoryGroup(
    prefs: SharedPreferences, surfaceColor: Color, textSecondary: Color, categoryOrder: List<Int>, onOrderChange: (List<Int>) -> Unit,
    contentPraf: @Composable () -> Unit, contentGaze: @Composable () -> Unit, contentClima: @Composable () -> Unit
) {
    var expPraf by remember { mutableStateOf(prefs.getBoolean("expPraf", true)) }
    var expGaze by remember { mutableStateOf(prefs.getBoolean("expGaze", true)) }
    var expClima by remember { mutableStateOf(prefs.getBoolean("expClima", true)) }
    var draggedCatId by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val swapThreshold = with(LocalDensity.current) { 64.dp.toPx() }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        categoryOrder.forEach { catId ->
            val isDragged = draggedCatId == catId; val zIndex = if (isDragged) 1f else 0f; val offsetY = if (isDragged) dragOffsetY else 0f
            val titlu = when(catId) { 0 -> "PARTICULE (µg/m³)"; 1 -> "GAZE ȘI CHIMICALE"; else -> "CLIMĂ" }
            val isExp = when(catId) { 0 -> expPraf; 1 -> expGaze; else -> expClima }
            val toggleExpand = { when(catId) { 0 -> { expPraf = !expPraf; prefs.edit(commit = true) { putBoolean("expPraf", expPraf) } }; 1 -> { expGaze = !expGaze; prefs.edit(commit = true) { putBoolean("expGaze", expGaze) } }; 2 -> { expClima = !expClima; prefs.edit(commit = true) { putBoolean("expClima", expClima) } } } }

            Box(modifier = Modifier.zIndex(zIndex).offset { IntOffset(0, offsetY.roundToInt()) }) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(surfaceColor).pointerInput(catId) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedCatId = catId; dragOffsetY = 0f }, onDragEnd = { draggedCatId = null; dragOffsetY = 0f }, onDragCancel = { draggedCatId = null; dragOffsetY = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume(); dragOffsetY += dragAmount.y; val currentPos = categoryOrder.indexOf(catId)
                                    if (dragOffsetY > swapThreshold && currentPos < categoryOrder.size - 1) { val mutList = categoryOrder.toMutableList(); val temp = mutList[currentPos]; mutList[currentPos] = mutList[currentPos + 1]; mutList[currentPos + 1] = temp; onOrderChange(mutList); dragOffsetY -= swapThreshold }
                                    else if (dragOffsetY < -swapThreshold && currentPos > 0) { val mutList = categoryOrder.toMutableList(); val temp = mutList[currentPos]; mutList[currentPos] = mutList[currentPos - 1]; mutList[currentPos - 1] = temp; onOrderChange(mutList); dragOffsetY += swapThreshold }
                                }
                            )
                        }.clickable { toggleExpand() }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) { Text(titlu, color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Icon(imageVector = if (isExp) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = "Expand", tint = textSecondary) }
                    AnimatedVisibility(visible = isExp) { Column(modifier = Modifier.padding(top = 8.dp)) { when(catId) { 0 -> contentPraf(); 1 -> contentGaze(); 2 -> contentClima() } } }
                }
            }
        }
    }
}

// ================= TAB 1: DASHBOARD =================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcranAcasa(
    prefs: SharedPreferences, categoryOrder: List<Int>, onOrderChange: (List<Int>) -> Unit,
    initLoadDone: Boolean, diferentaSecunde: Long, speedControlServer: Float, procentVentilatorServer: Int, rpmServer: Int, intervalSelectat: IntervalTimp,
    pm1: Float, pm25: Float, pm4: Float, pm10: Float, co2: Int, voc: Float, nox: Float, temp: Float, umiditate: Float,
    istoricPM1: List<Float>, istoricPM25: List<Float>, istoricPM4: List<Float>, istoricPM10: List<Float>,
    istoricCO2: List<Float>, istoricVOC: List<Float>, istoricNOx: List<Float>, istoricTemp: List<Float>, istoricUmid: List<Float>,
    accentColor: Color, textPrimary: Color, textSecondary: Color, surfaceColor: Color, tipGrafic: Int,
    onIntervalChange: (IntervalTimp) -> Unit, onSpeedConfirmed: (Float) -> Unit
) {
    var localSliderValue by remember { mutableFloatStateOf(speedControlServer) }
    var isDragging by remember { mutableStateOf(false) }

    // Crosshair Global State
    var globalTouchIndex by remember { mutableStateOf<Int?>(null) }
    var activeChartId by remember { mutableStateOf<String?>(null) }
    val onGlobalTouch: (Int?, String?) -> Unit = { idx, id -> globalTouchIndex = idx; activeChartId = id }

    LaunchedEffect(speedControlServer) { if (!isDragging) { localSliderValue = speedControlServer } }

    val animPm1 by animateFloatAsState(pm1, tween(1000, easing = FastOutSlowInEasing))
    val animPm25 by animateFloatAsState(pm25, tween(1000, easing = FastOutSlowInEasing))
    val animPm4 by animateFloatAsState(pm4, tween(1000, easing = FastOutSlowInEasing))
    val animPm10 by animateFloatAsState(pm10, tween(1000, easing = FastOutSlowInEasing))
    val animCo2 by animateFloatAsState(co2.toFloat(), tween(1000, easing = FastOutSlowInEasing))
    val animVoc by animateFloatAsState(voc, tween(1000, easing = FastOutSlowInEasing))
    val animNox by animateFloatAsState(nox, tween(1000, easing = FastOutSlowInEasing))
    val animTemp by animateFloatAsState(temp, tween(1000, easing = FastOutSlowInEasing))
    val animUmid by animateFloatAsState(umiditate, tween(1000, easing = FastOutSlowInEasing))

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

        // Logica Noua Putere / Procent
        val isAuto = localSliderValue == 0f
        val isOprit = localSliderValue < 5f && !isAuto
        val displaySliderValue = if (isAuto) procentVentilatorServer.toFloat() else localSliderValue
        val statusBtnText = if (isOprit) "OPRIT" else "${displaySliderValue.roundToInt()}%"

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Putere: $statusBtnText", color = if (isOprit) Color.Red.copy(alpha = 0.8f) else textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Button(onClick = { localSliderValue = 0f; onSpeedConfirmed(0f) }, colors = ButtonDefaults.buttonColors(containerColor = if (isAuto) accentColor else surfaceColor), shape = RoundedCornerShape(8.dp), border = if (!isAuto) BorderStroke(1.dp, textSecondary.copy(alpha = 0.5f)) else null, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) { Text("AUTO", color = if (isAuto) Color.White else textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Text(text = "$rpmServer RPM", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }

        val activeSliderColor = if (isOprit) Color.Red.copy(alpha = 0.7f) else accentColor
        val sliderColors = if (isAuto) SliderDefaults.colors(thumbColor = Color.Gray, activeTrackColor = Color.Gray.copy(alpha = 0.5f), inactiveTrackColor = textSecondary.copy(alpha = 0.2f)) else SliderDefaults.colors(thumbColor = activeSliderColor, activeTrackColor = activeSliderColor)

        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 14.dp)) { val w = size.width; val h = size.height; val centerX = 0.5f * w; drawLine(color = textSecondary.copy(alpha = 0.6f), start = Offset(centerX, h / 2 - 14.dp.toPx()), end = Offset(centerX, h / 2 + 2.dp.toPx()), strokeWidth = 4f, cap = StrokeCap.Round) }
            Slider(value = displaySliderValue, onValueChange = { isDragging = true; localSliderValue = it }, onValueChangeFinished = { isDragging = false; onSpeedConfirmed(localSliderValue) }, valueRange = 0f..100f, steps = 99, colors = sliderColors)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IntervalTimp.entries.forEach { interval ->
                val isSelected = intervalSelectat == interval
                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (isSelected) accentColor else surfaceColor).clickable { onIntervalChange(interval) }.padding(horizontal = 24.dp, vertical = 8.dp)) { Text("${interval.ore}h", color = if (isSelected) Color.White else textPrimary, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        DraggableCategoryGroup(prefs = prefs, surfaceColor = surfaceColor, textSecondary = textSecondary, categoryOrder = categoryOrder, onOrderChange = onOrderChange,
            contentPraf = {
                SenzorCard("PM 1.0", animPm1, false, istoricPM1, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM1", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 10) Color.Green else if (v < 20) Color(0xFFFFC107) else if (v < 30) Color(0xFFFF9800) else Color.Red }
                SenzorCard("PM 2.5", animPm25, false, istoricPM25, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM25", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 12) Color.Green else if (v < 35) Color(0xFFFFC107) else if (v < 55) Color(0xFFFF9800) else Color.Red }
                SenzorCard("PM 4.0", animPm4, false, istoricPM4, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM4", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 25) Color.Green else if (v < 50) Color(0xFFFFC107) else if (v < 75) Color(0xFFFF9800) else Color.Red }
                SenzorCard("PM 10", animPm10, false, istoricPM10, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "PM10", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 50) Color.Green else if (v < 100) Color(0xFFFFC107) else if (v < 150) Color(0xFFFF9800) else Color.Red }
            },
            contentGaze = {
                SenzorCard("CO2 (ppm)", animCo2, false, istoricCO2, intervalSelectat, false, null, true, textPrimary, textSecondary, tipGrafic, "CO2", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 800) Color.Green else if (v < 1000) Color(0xFFFFC107) else if (v < 1500) Color(0xFFFF9800) else Color.Red }
                SenzorCard("VOC (Index)", animVoc, voc > 1000f, istoricVOC, intervalSelectat, false, null, true, textPrimary, textSecondary, tipGrafic, "VOC", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 150) Color.Green else if (v < 250) Color(0xFFFFC107) else if (v < 400) Color(0xFFFF9800) else Color.Red }
                SenzorCard("NOx (Index)", animNox, nox > 1000f, istoricNOx, intervalSelectat, false, null, true, textPrimary, textSecondary, tipGrafic, "NOx", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 20) Color.Green else if (v < 150) Color(0xFFFFC107) else if (v < 300) Color(0xFFFF9800) else Color.Red }
            },
            contentClima = {
                SenzorCard("Temperatură (°C)", animTemp, false, istoricTemp, intervalSelectat, false, null, false, textPrimary, textSecondary, tipGrafic, "Temp", globalTouchIndex, activeChartId, onGlobalTouch, ::getTempColor)
                SenzorCard("Umiditate (%)", animUmid, false, istoricUmid, intervalSelectat, false, 100f, false, textPrimary, textSecondary, tipGrafic, "Umid", globalTouchIndex, activeChartId, onGlobalTouch, ::getHumidColor)
            }
        )
        Spacer(modifier = Modifier.height(24.dp))
        CardScorZilnic(istoricPM25, istoricCO2, istoricVOC, istoricNOx, surfaceColor, textPrimary, textSecondary)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ================= TAB 2: ARHIVĂ =================
@Composable
fun EcranArhiva(prefs: SharedPreferences, categoryOrder: List<Int>, onOrderChange: (List<Int>) -> Unit, database: DatabaseReference, accentColor: Color, textPrimary: Color, textSecondary: Color, surfaceColor: Color, dividerColor: Color, tipGrafic: Int, pmLive: List<Float>, co2Live: List<Float>, vocLive: List<Float>, noxLive: List<Float>) {
    var arhivaData by remember { mutableStateOf<Map<String, Map<String, List<Float>>>>(emptyMap()) }
    var listaZileReale by remember { mutableStateOf<List<Pair<String, LocalDate>>>(emptyList()) }
    var saptamanaSelectata by remember { mutableStateOf<LocalDate?>(null) }
    var ziSelectata by remember { mutableStateOf<String?>(null) }
    val formatter = DateTimeFormatter.ofPattern("dd_MM_yyyy")

    // Crosshair Global State pt Arhiva
    var globalTouchIndex by remember { mutableStateOf<Int?>(null) }
    var activeChartId by remember { mutableStateOf<String?>(null) }
    val onGlobalTouch: (Int?, String?) -> Unit = { idx, id -> globalTouchIndex = idx; activeChartId = id }

    LaunchedEffect(Unit) {
        database.child("Arhiva_Zilnica").orderByKey().limitToLast(28).addValueEventListener(object : ValueEventListener {
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
                listaZileReale = zileParsate.sortedBy { it.second }
                if (listaZileReale.isNotEmpty() && saptamanaSelectata == null) {
                    val latestDay = listaZileReale.last().second
                    saptamanaSelectata = latestDay.with(DayOfWeek.MONDAY)
                    ziSelectata = listaZileReale.last().first
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    val saptamaniMap = remember(listaZileReale) { listaZileReale.groupBy { it.second.with(DayOfWeek.MONDAY) } }
    val saptamaniValide = saptamaniMap.keys.sorted().takeLast(4)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("ARHIVĂ CALITATE AER", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        if (saptamaniValide.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Arhivă goală", color = textSecondary) }; return@Column }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            saptamaniValide.forEach { luneaSaptamanii ->
                val isSelected = saptamanaSelectata == luneaSaptamanii
                val duminica = luneaSaptamanii.plusDays(6)
                val m1 = getMonthRoShort(luneaSaptamanii.monthValue)
                val m2 = getMonthRoShort(duminica.monthValue)
                val titlu = if (m1 == m2) "${luneaSaptamanii.dayOfMonth}-${duminica.dayOfMonth}\n$m1" else "${luneaSaptamanii.dayOfMonth} $m1\n${duminica.dayOfMonth} $m2"

                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (isSelected) accentColor else surfaceColor).clickable { saptamanaSelectata = luneaSaptamanii; ziSelectata = saptamaniMap[luneaSaptamanii]?.last()?.first }.padding(vertical = 8.dp, horizontal = 2.dp), contentAlignment = Alignment.Center) {
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
                        Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (isSelected) accentColor else surfaceColor).clickable { ziSelectata = ziGasitaInDb.first }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(getDayOfWeekRo(dataCurentaDinGrila), color = if (isSelected) Color.White.copy(alpha = 0.8f) else textSecondary, fontSize = 10.sp)
                                Text(dataCurentaDinGrila.dayOfMonth.toString(), color = if (isSelected) Color.White else textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    } else { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = dividerColor)
        Spacer(modifier = Modifier.height(16.dp))

        val dateZi = arhivaData[ziSelectata] ?: emptyMap()

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            DraggableCategoryGroup(
                prefs = prefs, surfaceColor = surfaceColor, textSecondary = textSecondary, categoryOrder = categoryOrder, onOrderChange = onOrderChange,
                contentPraf = { SenzorCard("PM 2.5 (Praf)", 0f, true, dateZi["PM25"] ?: emptyList(), IntervalTimp.ORE_24, true, null, false, textPrimary, textSecondary, tipGrafic, "AR_PM25", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 12) Color.Green else if (v < 35) Color(0xFFFFC107) else if (v < 55) Color(0xFFFF9800) else Color.Red } },
                contentGaze = {
                    SenzorCard("CO2 (Dioxid de Carbon)", 0f, true, dateZi["CO2"] ?: emptyList(), IntervalTimp.ORE_24, true, null, true, textPrimary, textSecondary, tipGrafic, "AR_CO2", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 800) Color.Green else if (v < 1000) Color(0xFFFFC107) else if (v < 1500) Color(0xFFFF9800) else Color.Red }
                    SenzorCard("VOC (Chimicale)", 0f, true, dateZi["VOC"] ?: emptyList(), IntervalTimp.ORE_24, true, null, true, textPrimary, textSecondary, tipGrafic, "AR_VOC", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 150) Color.Green else if (v < 250) Color(0xFFFFC107) else if (v < 400) Color(0xFFFF9800) else Color.Red }
                    SenzorCard("NOx (Gaze eșapament)", 0f, true, dateZi["NOx"] ?: emptyList(), IntervalTimp.ORE_24, true, null, true, textPrimary, textSecondary, tipGrafic, "AR_NOx", globalTouchIndex, activeChartId, onGlobalTouch) { v -> if (v == -999f) Color.Transparent else if (v < 20) Color.Green else if (v < 150) Color(0xFFFFC107) else if (v < 300) Color(0xFFFF9800) else Color.Red }
                },
                contentClima = {
                    SenzorCard("Temperatură (°C)", 0f, true, dateZi["Temp"] ?: emptyList(), IntervalTimp.ORE_24, true, null, false, textPrimary, textSecondary, tipGrafic, "AR_Temp", globalTouchIndex, activeChartId, onGlobalTouch, ::getTempColor)
                    SenzorCard("Umiditate RH (%)", 0f, true, dateZi["RH"] ?: emptyList(), IntervalTimp.ORE_24, true, 100f, false, textPrimary, textSecondary, tipGrafic, "AR_Umid", globalTouchIndex, activeChartId, onGlobalTouch, ::getHumidColor)
                }
            )
            Spacer(modifier = Modifier.height(32.dp))
            CardScorZilnic(pmLive, co2Live, vocLive, noxLive, surfaceColor, textPrimary, textSecondary)
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ================= TAB 3: SETĂRI =================
@Composable
fun EcranSetari(
    context: Context, nightModeActiv: Boolean, notifPraf: Boolean, notifGaze: Boolean, notifOffline: Boolean, errorLogString: String, runInBackground: Boolean,
    isDarkMode: Boolean, accentColor: Color, tipGrafic: Int, textPrimary: Color, textSecondary: Color, surfaceColor: Color,
    istoricExportPM: List<Float>, istoricExportCO2: List<Float>,
    onNightModeChange: (Boolean) -> Unit, onNotifPrafChange: (Boolean) -> Unit,
    onNotifGazeChange: (Boolean) -> Unit, onNotifOfflineChange: (Boolean) -> Unit, onRunInBackgroundChange: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit, onColorChange: (Int) -> Unit, onTipGraficChange: (Int) -> Unit,
    onCleanSensor: () -> Unit, onRestartBoard: () -> Unit
) {
    val (showCleanDialog, setShowCleanDialog) = remember { mutableStateOf(false) }
    val (showRestartDialog, setShowRestartDialog) = remember { mutableStateOf(false) }
    val (showErrorDialog, setShowErrorDialog) = remember { mutableStateOf(false) }

    val dividerColor = if (isDarkMode) Color.DarkGray else Color(0xFFD1D1D6)

    val (showExportDialog, setShowExportDialog) = remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var csvContentToSave by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        isExporting = false
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(csvContentToSave.toByteArray())
            }
        }
    }

    if (showCleanDialog) {
        AlertDialog(onDismissRequest = { setShowCleanDialog(false) }, title = { Text("Curățare Senzor SEN66", fontWeight = FontWeight.Bold) }, text = { Text("Această comandă va porni ventilatorul intern al senzorului la turație maximă pentru a elimina depunerile de praf. Ești sigur?") }, confirmButton = { TextButton(onClick = { setShowCleanDialog(false); onCleanSensor() }) { Text("Curăță", color = Color(0xFF007AFF)) } }, dismissButton = { TextButton(onClick = { setShowCleanDialog(false) }) { Text("Anulează", color = textSecondary) } }, containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary)
    }

    if (showRestartDialog) {
        AlertDialog(onDismissRequest = { setShowRestartDialog(false) }, title = { Text("Restartare Sistem", fontWeight = FontWeight.Bold, color = Color.Red) }, text = { Text("Plăcuța ESP32 va fi oprită și repornită forțat. Conexiunea WiFi se va întrerupe pentru câteva secunde. Continuăm?") }, confirmButton = { TextButton(onClick = { setShowRestartDialog(false); onRestartBoard() }) { Text("Restart", color = Color.Red) } }, dismissButton = { TextButton(onClick = { setShowRestartDialog(false) }) { Text("Anulează", color = textSecondary) } }, containerColor = surfaceColor, titleContentColor = textPrimary, textContentColor = textPrimary)
    }

    if (showErrorDialog) {
        val parsedErrors = errorLogString.split("|").mapNotNull { logEntry ->
            val parts = logEntry.split("#")
            if (parts.size == 2) {
                val ts = parts[0].toLongOrNull() ?: return@mapNotNull logEntry
                val d = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDateTime()
                "${d.format(DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss"))} - ${parts[1]}"
            } else {
                logEntry.ifBlank { null }
            }
        }.reversed()

        Dialog(
            onDismissRequest = { setShowErrorDialog(false) },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = surfaceColor
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Jurnal Erori (24h)", fontWeight = FontWeight.Bold, color = textPrimary, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        if (parsedErrors.isEmpty()) {
                            item { Text("Sistemul funcționează perfect! Nicio eroare.", color = Color.Green) }
                        } else {
                            items(parsedErrors) { err ->
                                Text(err, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { setShowErrorDialog(false) }) {
                            Text("Închide", color = textPrimary)
                        }
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isExporting) setShowExportDialog(false) },
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

                        Button(onClick = {
                            val sb = java.lang.StringBuilder()
                            sb.append("Minute_In_Urma,PM2.5,CO2_ppm\n")
                            val maxPuncte = minOf(istoricExportPM.size, istoricExportCO2.size)
                            for (i in 0 until maxPuncte) {
                                val minAsteptate = (maxPuncte - 1 - i) * 5
                                sb.append("$minAsteptate,${istoricExportPM[i]},${istoricExportCO2[i]}\n")
                            }
                            csvContentToSave = sb.toString()
                            exportLauncher.launch("Live_Aer_24h.csv")
                            setShowExportDialog(false)
                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = surfaceColor, contentColor = textPrimary), border = BorderStroke(1.dp, dividerColor)) {
                            Text("Ultimele 24 de ore (Live)")
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(onClick = {
                            isExporting = true
                            val db = FirebaseDatabase.getInstance("https://YOUR_PROJECT-default-rtdb.firebasedatabase.app/").getReference("SenzoriAer/Arhiva_Zilnica")
                            db.get().addOnSuccessListener { snapshot ->
                                val sb = java.lang.StringBuilder()
                                sb.append("Data,Ora,PM2.5,CO2_ppm,VOC_Index,NOx_Index,Temp_C,Umiditate_RH\n")

                                for (ziSnap in snapshot.children) {
                                    val dateStr = ziSnap.key ?: continue
                                    val pmList = parseIstoric(ziSnap.child("PM25").getValue(String::class.java))
                                    val co2List = parseIstoric(ziSnap.child("CO2").getValue(String::class.java))
                                    val vocList = parseIstoric(ziSnap.child("VOC").getValue(String::class.java), true)
                                    val noxList = parseIstoric(ziSnap.child("NOx").getValue(String::class.java), true)
                                    val tempList = parseIstoric(ziSnap.child("Temp").getValue(String::class.java))
                                    val humList = parseIstoric(ziSnap.child("Umid").getValue(String::class.java))

                                    val maxP = pmList.size
                                    for (i in 0 until maxP) {
                                        val pm = pmList.getOrNull(i) ?: -999f
                                        if (pm == -999f) continue
                                        val co2 = co2List.getOrNull(i) ?: -999f
                                        val voc = vocList.getOrNull(i) ?: -999f
                                        val nox = noxList.getOrNull(i) ?: -999f
                                        val temp = tempList.getOrNull(i) ?: -999f
                                        val hum = humList.getOrNull(i) ?: -999f
                                        val totalMin = i * 5
                                        val ora = String.format(Locale.US, "%02d:%02d", totalMin / 60, totalMin % 60)
                                        sb.append("$dateStr,$ora,$pm,${co2.toInt()},${voc.toInt()},${nox.toInt()},$temp,$hum\n")
                                    }
                                }
                                csvContentToSave = sb.toString()
                                exportLauncher.launch("Arhiva_Completă_Aer.csv")
                                setShowExportDialog(false)
                            }.addOnFailureListener {
                                isExporting = false
                                setShowExportDialog(false)
                            }
                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)) {
                            Text("Toată Arhiva Salvată")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { setShowExportDialog(false) }) { Text("Anulează", color = textSecondary) } },
            containerColor = surfaceColor, titleContentColor = textPrimary
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("1. TEMĂ APLICAȚIE", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onThemeChange(false) }) { Box(modifier = Modifier.size(130.dp, 90.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFF2F2F7)).border(if (!isDarkMode) 2.dp else 1.dp, if (!isDarkMode) accentColor else Color.LightGray, RoundedCornerShape(16.dp)).padding(12.dp)) { Column { Box(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(Color.White)); Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(accentColor)); Spacer(Modifier.width(8.dp)); Box(modifier = Modifier.width(40.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.LightGray)) } } }; Spacer(modifier = Modifier.height(12.dp)); Text("Light", color = textPrimary, fontSize = 16.sp); RadioButton(selected = !isDarkMode, onClick = { onThemeChange(false) }, colors = RadioButtonDefaults.colors(selectedColor = accentColor, unselectedColor = textSecondary)) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onThemeChange(true) }) { Box(modifier = Modifier.size(130.dp, 90.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF121212)).border(if (isDarkMode) 2.dp else 1.dp, if (isDarkMode) accentColor else Color.DarkGray, RoundedCornerShape(16.dp)).padding(12.dp)) { Column { Box(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2C2C2E))); Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(accentColor)); Spacer(Modifier.width(8.dp)); Box(modifier = Modifier.width(40.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Gray)) } } }; Spacer(modifier = Modifier.height(12.dp)); Text("Dark", color = textPrimary, fontSize = 16.sp); RadioButton(selected = isDarkMode, onClick = { onThemeChange(true) }, colors = RadioButtonDefaults.colors(selectedColor = accentColor, unselectedColor = textSecondary)) }
        }

        Spacer(modifier = Modifier.height(24.dp)); Text("2. CULOARE ACCENT", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).padding(16.dp), horizontalArrangement = Arrangement.SpaceAround) { culoriDisponibile.forEach { (colorInt, _) -> val isSelected = accentColor == Color(colorInt); Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(colorInt)).border(width = if (isSelected) 3.dp else 0.dp, color = if (isSelected) textPrimary else Color.Transparent, shape = CircleShape).clickable { onColorChange(colorInt) }) } }

        Spacer(modifier = Modifier.height(24.dp)); Text("3. STIL GRAFICE", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) { val optiuni = listOf("Linii", "Curbe", "Bare"); optiuni.forEachIndexed { index, titlu -> val isSelected = tipGrafic == index; Box(modifier = Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(8.dp)).background(if (isSelected) accentColor else Color.Transparent).clickable { onTipGraficChange(index) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Text(titlu, color = if (isSelected) Color.White else textPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp) } } }

        Spacer(modifier = Modifier.height(24.dp)); Text("4. SETĂRI HARDWARE", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(12.dp))
        SetareSwitch("Night Mode (Mod Somn)", "Oprește ecranul OLED și LED-urile aparatului fizic.", nightModeActiv, accentColor, textPrimary, textSecondary, surfaceColor, onNightModeChange)

        Spacer(modifier = Modifier.height(24.dp)); Text("5. NOTIFICĂRI PUSH", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(12.dp))
        SetareSwitch("Alerte Praf (PM2.5)", "Te notifică când PM2.5 depășește nivelul de 35.", notifPraf, accentColor, textPrimary, textSecondary, surfaceColor, onNotifPrafChange)
        Spacer(modifier = Modifier.height(8.dp)); SetareSwitch("Alertă Aerisire", "Te notifică când CO2 trece de 1500, VOC de 250 sau NOx de 150.", notifGaze, accentColor, textPrimary, textSecondary, surfaceColor, onNotifGazeChange)
        Spacer(modifier = Modifier.height(8.dp)); SetareSwitch("Alertă Deconectare", "Primești notificare când aparatul cade din rețea.", notifOffline, accentColor, textPrimary, textSecondary, surfaceColor, onNotifOfflineChange)
        Spacer(modifier = Modifier.height(8.dp)); SetareSwitch("Rulare în fundal", "Serviciu pentru primirea alertelor cu aplicația închisă.", runInBackground, accentColor, textPrimary, textSecondary, surfaceColor, onRunInBackgroundChange)

        Spacer(modifier = Modifier.height(24.dp)); Text("6. MANTENANȚĂ ȘI EXPORT", color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = { setShowExportDialog(true) }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Share, contentDescription = "Export", tint = Color(0xFF34C759)); Spacer(Modifier.width(16.dp)); Text("Exportă Datele (.CSV)", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { setShowCleanDialog(true) }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Settings, contentDescription = "Clean", tint = accentColor); Spacer(Modifier.width(16.dp)); Text("Curățare Senzor SEN66", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { setShowErrorDialog(true) }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, contentDescription = "Errors", tint = Color(0xFFFF9500)); Spacer(Modifier.width(16.dp)); Text("Jurnal Erori (Ultimele 24h)", color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Button(onClick = { setShowRestartDialog(true) }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, contentDescription = "Restart", tint = Color.Red); Spacer(Modifier.width(16.dp)); Text("Restartare Sistem", color = Color.Red, fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SetareSwitch(titlu: String, descriere: String, checked: Boolean, accentColor: Color, textPrimary: Color, textSecondary: Color, surfaceColor: Color, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaceColor).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) { Text(titlu, color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium); Text(descriere, color = textSecondary, fontSize = 14.sp) }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accentColor, uncheckedThumbColor = Color(0xFF8E8E93), uncheckedTrackColor = if (surfaceColor == Color.White) Color(0xFFE5E5EA) else Color(0xFF3A3A3C), uncheckedBorderColor = Color.Transparent))
    }
}

// ================= COMPONENTE GRAFICE =================
@Composable
fun SenzorCard(titlu: String, valoareLive: Float, formatInit: Boolean, istoricTotal: List<Float>, interval: IntervalTimp, esteArhiva: Boolean, yMaxCap: Float?, faraZecimale: Boolean, textPrimary: Color, textSecondary: Color, tipGrafic: Int, chartId: String, globalTouchIndex: Int?, activeChartId: String?, onGlobalTouch: (Int?, String?) -> Unit, colorLogic: (Float) -> Color) {
    val istoricTaiat = istoricTotal.takeLast(interval.puncte)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(titlu, color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)

            val valoareCurenta = istoricTotal.lastOrNull() ?: 0f
            val displayColor = if (formatInit || (esteArhiva && valoareCurenta == -999f)) textSecondary else colorLogic(valoareLive)

            val stringValoare = if (esteArhiva) "-" else if (formatInit) "Init" else if (faraZecimale) valoareLive.roundToInt().toString() else String.format(Locale.US, "%.1f", valoareLive)
            if (!esteArhiva) Text(stringValoare, color = displayColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        GraficAvansat(chartId, istoricTaiat, interval, esteArhiva, yMaxCap, faraZecimale, textPrimary, textSecondary, tipGrafic, globalTouchIndex, activeChartId, onGlobalTouch, colorLogic)
    }
}

@Composable
fun GraficAvansat(chartId: String, istoric: List<Float>, interval: IntervalTimp, esteArhiva: Boolean, yMaxCap: Float?, faraZecimale: Boolean, textPrimary: Color, textSecondary: Color, tipGrafic: Int, globalTouchIndex: Int?, activeChartId: String?, onGlobalTouch: (Int?, String?) -> Unit, colorLogic: (Float) -> Color) {
    if (istoric.isEmpty()) { Text("Colectare date...", color = textSecondary, fontSize = 12.sp); return }

    val istoricValid = istoric.filter { it != -999f }
    var maxVal = (istoricValid.maxOrNull() ?: 1f) * 1.1f
    var minVal = (istoricValid.minOrNull() ?: 0f) * 0.9f
    if (yMaxCap != null && maxVal > yMaxCap) maxVal = yMaxCap
    if (minVal < 0f) minVal = 0f
    val range = if (maxVal == minVal) 1f else maxVal - minVal

    val maxPuncte = interval.puncte
    val offsetStartIndex = maxPuncte - istoric.size

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(start = 30.dp, bottom = 20.dp, end = 10.dp, top = 20.dp)
            // AICI ESTE REZOLVAREA DEFINITIVĂ A TOUCH-ULUI!
            .pointerInput(chartId, maxPuncte) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume() // Oprim scroll-ul paginii imediat

                    val w = size.width
                    val distantaX = w / (maxPuncte - 1).coerceAtLeast(1).toFloat()
                    val startIdx = (down.position.x / distantaX).roundToInt().coerceIn(0, maxPuncte - 1)
                    onGlobalTouch(startIdx, chartId)

                    val pointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointerId }

                        if (change == null || !change.pressed) {
                            // Degetul a fost ridicat sau gestul anulat
                            break
                        }

                        change.consume() // Ținem scroll-ul blocat
                        val moveIdx = (change.position.x / distantaX).roundToInt().coerceIn(0, maxPuncte - 1)
                        onGlobalTouch(moveIdx, chartId)
                    }

                    onGlobalTouch(null, null)
                }
            }
    ) {
        val w = size.width; val h = size.height
        val distantaX = w / (maxPuncte - 1).coerceAtLeast(1).toFloat()

        // Linii de fundal orizontale
        for (i in 0 until 3) {
            val yPos = h - (i * (h / 2)); val valoareEticheta = minVal + (i * (range / 2))
            val etichetaText = if (faraZecimale) { String.format(Locale.US, "%.0f", valoareEticheta) } else { if (range < 5f) String.format(Locale.US, "%.1f", valoareEticheta) else String.format(Locale.US, "%.0f", valoareEticheta) }
            drawLine(textSecondary.copy(alpha = 0.3f), Offset(0f, yPos), Offset(w, yPos), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.drawText(etichetaText, -10f, yPos + 10f, android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 28f; textAlign = android.graphics.Paint.Align.RIGHT })
        }

        // Etichete timp pe axa X
        for (i in 0..4) {
            val xPos = i * (w / 4)
            val eticheta = if (esteArhiva) { arrayOf("00:00", "06:00", "12:00", "18:00", "24:00")[i] } else {
                val oreInUrma = interval.ore - (i * (interval.ore / 4f))
                if (oreInUrma == 0f) "Acum" else "-${String.format(Locale.US, "%.0f", oreInUrma)}h"
            }
            drawContext.canvas.nativeCanvas.drawText(eticheta, xPos, h + 40f, android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER })
        }

        // DESENARE GRAFIC EFECTIV
        if (tipGrafic == 2) {
            for (i in istoric.indices) { val v = istoric[i]; if (v == -999f) continue; val x = (offsetStartIndex + i) * distantaX; val baseRectTop = h - ((v - minVal) / range) * h; val rectTop = baseRectTop.coerceAtMost(h - 4f); val barWidth = (distantaX * 0.7f).coerceIn(4f, 20f); drawRoundRect(color = colorLogic(v), topLeft = Offset(x - barWidth / 2, rectTop), size = Size(barWidth, h - rectTop), cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)) }
        } else if (tipGrafic == 0 || tipGrafic == 1) {
            var fillPath: Path? = null
            var startX = 0f
            var lastX = 0f

            for (i in 0 until istoric.size - 1) {
                val val1 = istoric[i]; val val2 = istoric[i+1]
                if (val1 == -999f || val2 == -999f) {
                    if (fillPath != null) {
                        fillPath.lineTo(lastX, h); fillPath.lineTo(startX, h); fillPath.close()
                        drawPath(fillPath, brush = Brush.verticalGradient(listOf(colorLogic(maxVal).copy(alpha=0.4f), colorLogic(minVal).copy(alpha=0.0f)), startY = 0f, endY = h))
                        fillPath = null
                    }
                    continue
                }

                val x1 = (offsetStartIndex + i) * distantaX; val y1 = h - ((val1 - minVal) / range) * h
                val x2 = (offsetStartIndex + i + 1) * distantaX; val y2 = h - ((val2 - minVal) / range) * h

                if (fillPath == null) { fillPath = Path().apply { moveTo(x1, y1) }; startX = x1 }

                if (tipGrafic == 1) { // Curbe
                    val cx = (x1 + x2) / 2f
                    fillPath.cubicTo(cx, y1, cx, y2, x2, y2)
                    val strokeSegment = Path().apply { moveTo(x1, y1); cubicTo(cx, y1, cx, y2, x2, y2) }
                    drawPath(strokeSegment, color = colorLogic(val2), style = Stroke(6f, cap = StrokeCap.Round))
                } else { // Linii
                    fillPath.lineTo(x2, y2)
                    drawLine(colorLogic(val2), Offset(x1, y1), Offset(x2, y2), strokeWidth = 6f, cap = StrokeCap.Round)
                }
                lastX = x2
            }
            if (fillPath != null) {
                fillPath.lineTo(lastX, h); fillPath.lineTo(startX, h); fillPath.close()
                drawPath(fillPath, brush = Brush.verticalGradient(listOf(colorLogic(maxVal).copy(alpha=0.4f), colorLogic(minVal).copy(alpha=0.0f)), startY = 0f, endY = h))
            }
            // Punctele peste linii
            for (i in istoric.indices) { val v = istoric[i]; if (v == -999f) continue; val x = (offsetStartIndex + i) * distantaX; val y = h - ((v - minVal) / range) * h; drawCircle(colorLogic(v), radius = 4f, center = Offset(x, y)) }
        }

        // Indicator Miezul Noptii
        if (!esteArhiva) {
            val now = LocalTime.now(); val pointsSinceMidnight = (now.hour * 60 + now.minute) / 5; val midnightIndex = istoric.size - 1 - pointsSinceMidnight
            if (midnightIndex in 0 until istoric.size) { val xMidnight = (offsetStartIndex + midnightIndex) * distantaX; drawLine(color = textSecondary.copy(alpha = 0.5f), start = Offset(xMidnight, 0f), end = Offset(xMidnight, h), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)); drawContext.canvas.nativeCanvas.drawText("00:00", xMidnight, 15f, android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f; textAlign = android.graphics.Paint.Align.CENTER }) }
        }

        // CROSSHAIR GLOBAL (Atingerea pe ecran)
        globalTouchIndex?.let { touchIndexGlobal ->
            val indexInIstoric = touchIndexGlobal - offsetStartIndex
            if (indexInIstoric in istoric.indices && istoric[indexInIstoric] != -999f) {
                val xFix = touchIndexGlobal * distantaX; val valoareTouch = istoric[indexInIstoric]; val yFix = h - ((valoareTouch - minVal) / range) * h

                // Traseaza linia verticala punctata si pe acest grafic
                drawLine(textPrimary.copy(alpha = 0.6f), Offset(xFix, 0f), Offset(xFix, h), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                drawCircle(textPrimary, 12f, Offset(xFix, yFix)); drawCircle(colorLogic(valoareTouch), 8f, Offset(xFix, yFix))

                val bgPaint = android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; alpha = 230; style = android.graphics.Paint.Style.FILL }
                val textToDraw = if (faraZecimale) String.format(Locale.US, "%.0f", valoareTouch) else String.format(Locale.US, "%.1f", valoareTouch)
                var tooltipY = yFix - 45f; if (tooltipY < -10f) tooltipY = yFix + 60f
                drawContext.canvas.nativeCanvas.drawRoundRect(android.graphics.RectF(xFix - 50f, tooltipY - 35f, xFix + 50f, tooltipY + 15f), 12f, 12f, bgPaint)
                drawContext.canvas.nativeCanvas.drawText(textToDraw, xFix, tooltipY, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 34f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; typeface = android.graphics.Typeface.DEFAULT_BOLD })

                // Bula de TIMP este desenata DOAR in graficul in care tinem efectiv degetul (activeChartId)
                if (activeChartId == chartId) {
                    val timpTouchString = if (esteArhiva) {
                        val totalMin = indexInIstoric * 5
                        String.format(Locale.US, "%02d:%02d", totalMin / 60, totalMin % 60)
                    } else {
                        val now = LocalTime.now()
                        val minuteRotunjite = (now.minute / 5) * 5
                        val roundedNow = now.withMinute(minuteRotunjite).withSecond(0).withNano(0)
                        val timeAtPoint = roundedNow.minusMinutes(((istoric.size - 1 - indexInIstoric) * 5).toLong())
                        String.format(Locale.US, "%02d:%02d", timeAtPoint.hour, timeAtPoint.minute)
                    }
                    val timeBubbleY = h + 25f
                    drawContext.canvas.nativeCanvas.drawRoundRect(android.graphics.RectF(xFix - 45f, timeBubbleY - 20f, xFix + 45f, timeBubbleY + 10f), 8f, 8f, bgPaint)
                    drawContext.canvas.nativeCanvas.drawText(timpTouchString, xFix, timeBubbleY, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 24f; textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD })
                }
            }
        }
    }
}