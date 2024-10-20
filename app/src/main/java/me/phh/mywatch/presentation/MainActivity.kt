/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter and
 * https://github.com/android/wear-os-samples/tree/main/ComposeAdvanced to find the most up to date
 * changes to the libraries and their usages.
 */

package me.phh.mywatch.presentation

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import me.phh.mywatch.presentation.theme.MyWatchTheme
import java.io.FileInputStream
import kotlin.concurrent.thread

import oplus.pb.McuSystemPb
import oplus.pb.PreferenceProvider
import oplus.pb.SystemTime
import java.io.FileOutputStream

fun l(s: String) {
    android.util.Log.d("PHHW", s)
}
fun l(s: String, t: Throwable) {
    android.util.Log.d("PHHW", s, t)
}

class OplusMcuReceiver {
    companion object {
        val instance = OplusMcuReceiver()
    }

    fun send(channel: Int, data: ByteArray) {
        // Open /dev/dcc_ctrl, write channel and data
        val file = FileOutputStream("/dev/dcc_data")
        val buffer = ByteArray(4004)
        buffer[0] = (channel and 0xff).toByte()
        buffer[1] = (channel shr 8 and 0xff).toByte()
        buffer[2] = (data.size and 0xff).toByte()
        buffer[3] = (data.size shr 8 and 0xff).toByte()
        data.copyInto(buffer, 4)
        file.write(buffer)
        file.close()
    }

    fun boot_notify() {
        // Write a McuSystemPb.boot_notify
        val pb = McuSystemPb.boot_notify.newBuilder()
            .setType(2)
            .setOobe(
                McuSystemPb.oobe_notify_t.newBuilder()
                    .setOobeStatus(1)
                    .setNeedUpdateWeather(1)
                    .build()
            )
            .setSysTime(SystemTime.req_update_sys_time_t.newBuilder()
                .setTime((System.currentTimeMillis() / 1000L).toInt())
                .setOldTime(0)
                .setTimezone(60)
                .setOldTime(60)
                .setType(0)
                .build()
            )
            .build()
        send(386 /* MSG_HOST_ANDROID_BOOT_NOTIFY */, pb.toByteArray())
    }

    val handler = Handler(HandlerThread("phhw").apply { start() }.looper)
    val handlers = mutableMapOf<Int, (ByteArray) -> Unit>()
    init {
        handlers[396 /* MSG_MCU_REBOOT_REASON_RESPONSE */] = { data ->
            // Decode as protobuf McuSystemPb.reboot_reason
            val pb = McuSystemPb.reboot_reason.parseFrom(data)
            if (pb.hasReason())
                l("Reboot reason: ${pb.reason}")
            if (pb.hasMcuSysTime())
                l("MCU time: ${pb.mcuSysTime}")
            if (pb.hasEStatus())
                l("EStatus: ${pb.eStatus}")

            handler.postDelayed({
                send(395 /* MSG_HOST_REQUEST_MCU_REBOOT_REASON */, ByteArray(0))
            }, 1000)
            handler.postDelayed({
                send(420 /* MSG_HOST_REQUEST_CALIBRATION_PARAM */, ByteArray(0))
            }, 2000)
        }
        handlers[421 /* MSG_MCU_RESPONSE_CALIBRATION_PARAM */] = { data ->
            // Decode as protobuf McuSystemPb.light_cali_rsp_t
            val pb = McuSystemPb.light_cali_rsp_t.parseFrom(data)
            l("Light calibration: $pb")
            handler.postDelayed({
                boot_notify()
            }, 1000)
            handler.postDelayed({
                send(511 /* MSG_HOST_REQUEST_MOTOR_CALI */, ByteArray(0))
            }, 2000)
        }
        handlers[377 /* MSG_MCU_CUR_WORK_MODE_RESPONE */] = { data ->
            // Decode as protobuf PreferenceProvider.OppoMcuWorkModeResponseT
            val pb = PreferenceProvider.OppoMcuWorkModeResponseT.parseFrom(data)
            l("Work mode: $pb")
        }
        handlers[405 /* MSG_MCU_OFF_WRIST_STATUS_UPDATE */] = { data ->
            // Decode as protobuf PreferenceProvider.OWStatus
            val pb = PreferenceProvider.OWStatus.parseFrom(data)
            l("Off wrist status: $pb")
        }
        handlers[388 /* MSG_WRIST_BAND_RESPONE */] = { data ->
            // Decode as protobuf PreferenceProvider.WristBandResponseT
            l("Wrist band response")
        }
        handlers[512 /* MSG_MCU_MOTOR_CALI_RESPONSE */] = { data ->
            // Decode as protobuf McuSystemPb.motor_cali_rsp_t
            val pb = McuSystemPb.motor_cali_rsp_t.parseFrom(data)
            l("Motor calibration: $pb")
        }
    }

    fun start() {
        thread {
            // write 1 to /sys/devices/platform/soc/soc:oplus,sensor-hub/rese
            try {
                val file = FileOutputStream("/sys/devices/platform/soc/soc:oplus,sensor-hub/reset")
                file.write("1".toByteArray())
                file.close()
            } catch (e: Exception) {
                l("Error writing to sensor hub reset", e)
            }

            val file = FileInputStream("/dev/dcc_data")
            val buffer = ByteArray(4004)
            while(true) {
                val readLen = file.read(buffer)
                if(readLen <= 0) {
                    Thread.sleep(1000)
                    continue
                }
                //Channel is the first two bytes of buffer, LSB
                val channel = (buffer[0].toInt() and 0xff) or (buffer[1].toInt() and 0xff shl 8)
                // Len is the next two bytes of buffer, LSB
                val len = (buffer[2].toInt() and 0xff) or (buffer[3].toInt() and 0xff shl 8)
                // Data is the rest of the buffer
                val data = buffer.sliceArray(4 until readLen)
                l("Channel: $channel, Len: $len, Data: ${data.toList()}")
                // Check that readLen is equal to len + 4
                if (readLen != len + 4) {
                    l("Read length mismatch: $readLen != $len + 4")
                }
                // Call the handler for the channel
                if (handlers.containsKey(channel)) {
                    try {
                        handlers[channel]?.invoke(data)
                    } catch (e: Exception) {
                        l("Error in handler for channel $channel", e)
                    }
                } else {
                    l("No handler for channel $channel")
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    val handler = Handler(HandlerThread("phhw").apply { start() }.looper)

    fun wakeup() {
        val powerManager = getSystemService(PowerManager::class.java)
        PowerManager::class.java.getMethod("wakeUp", Long::class.java).invoke(powerManager, SystemClock.uptimeMillis())
    }
    lateinit var wakeLock : android.os.PowerManager.WakeLock
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)
        OplusMcuReceiver.instance.start()
        wakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "phhw:wakeup"
        )
        val insetsController = window.insetsController
        insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val sensorManager = getSystemService(SensorManager::class.java)
        for(s in sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            l("Sensor: ${s.name} -- ${s.stringType} -- ${s.type}")
        }

        handler.post {
            // Open sensor android.sensor.step_counter, and log it
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            val ret = sensorManager.registerListener(object: android.hardware.SensorEventListener {
                override fun onSensorChanged(p0: SensorEvent) {
                    val steps = p0.values[0].toInt()
                    l("Steps: $steps")
                    stepCounter.value = "$steps steps"
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
                }
            }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            l("Step counter request: $ret")
        }

        if (false) handler.post {
            // Open sensor android.sensor.heart_rate for one shot, log it, and display it
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            sensorManager.registerListener(object: android.hardware.SensorEventListener {
                override fun onSensorChanged(p0: SensorEvent) {
                    val rate = p0.values[0].toInt()
                    l("Heart rate: $rate -- $p0")
                    heartRate.value = "$rate bpm"
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
                }
            }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        handler.post {
            // Open sensor com.google.wear.sensor.ppg_hrm and just log it
            val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL)
                .filter { it.stringType == "com.google.wear.sensor.ppg_hrm" }
                .firstOrNull()
            val ret = sensorManager.registerListener(object: android.hardware.SensorEventListener {
                override fun onSensorChanged(p0: SensorEvent) {
                    l("PPG HRM: $p0")
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
                }
            }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            l("PPG HRM request: $ret")
        }

        handler.post {
            // Open sensor com.google.wear.sensor.ppg_spo
            val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL)
                .filter { it.stringType == "com.google.wear.sensor.ppg_spo" }
                .firstOrNull()
            val ret = sensorManager.registerListener(object: android.hardware.SensorEventListener {
                override fun onSensorChanged(p0: SensorEvent) {
                    val v = p0.values[0]
                    l("SPO: $v == ${p0.values.toList()}")
                    spo.value = "${v * 100}%"
                }

                override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
                }
            }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            l("SPO request: $ret")
        }

        handler.post {
            // Open sensor com.google.wear.sensor.w_temperature
            val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL)
                .filter { it.stringType == "com.google.wear.sensor.w_temperature" }
                .firstOrNull()
            val ret = sensorManager.registerListener(object: android.hardware.SensorEventListener {
                override fun onSensorChanged(p0: SensorEvent) {
                    val temp = p0.values[0]
                    l("Temperature: $temp == $p0")
                    wristTemperature.value = "$temp C"
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
                }
            }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            l("Temperature request: $ret")
        }

        handler.post {
            // Open sensor android.sensor.wrist_tilt_gesture ( a trigger sensor )
            val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL)
                .filter { it.stringType == "android.sensor.wrist_tilt_gesture" }
                .firstOrNull()
            val ret = sensorManager.registerListener(object: android.hardware.SensorEventListener {
                override fun onSensorChanged(p0: SensorEvent) {
                    l("Wrist tilt: $p0")
                    extra.value = "wrist ${p0.values[0]}"
                    wakeup()
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
                }
            }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            l("Wrist tilt request: $ret ${sensor?.reportingMode}")
        }

        handler.post {
            // Open sensor android.sensor.low_latency_offbody_detect ( a normal continous sensor )
            val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL)
                .filter { it.stringType == "android.sensor.low_latency_offbody_detect" }
                .firstOrNull()
            val ret = sensorManager.registerListener(object: android.hardware.SensorEventListener {
                override fun onSensorChanged(p0: SensorEvent) {
                    l("Offbody: $p0 ${p0.values.toList()}")
                    extra.value = "llod ${p0.values[0]}"
                    wakeup()
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
                }
            }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            l("Offbody request: $ret")
        }

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            WearApp("Android")
        }
    }
}

val stepCounter = mutableStateOf("0 step")
val heartRate = mutableStateOf("0 bpm")
val wristTemperature = mutableStateOf("0 C")
val extra = mutableStateOf("")
val spo = mutableStateOf("101%")
@Composable
fun WearApp(greetingName: String) {
    MyWatchTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText(timeTextStyle = TextStyle(fontSize = 36.sp))
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    val steps by rememberSaveable { stepCounter }
                    val heartRate by rememberSaveable { heartRate }
                    Text(
                        steps,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.requiredWidth(90.dp)
                    )
                    Text(
                        heartRate,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.requiredWidth(90.dp)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    val spo by rememberSaveable { spo }
                    Text(
                        spo,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.requiredWidth(90.dp)
                    )
                    Text(
                        "",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.requiredWidth(90.dp)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    val temp by rememberSaveable { wristTemperature }
                    val extra by rememberSaveable { extra }
                    Text(
                        temp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.requiredWidth(90.dp)
                    )
                    Text(
                        extra,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.requiredWidth(90.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(greetingName: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = greetingName
    )
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp("Android")
}