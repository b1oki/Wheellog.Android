package com.cooper.wheellog.data

import android.content.Context
import android.net.Uri
import android.os.Build
import com.cooper.wheellog.ElectroClub
import com.cooper.wheellog.R
import com.cooper.wheellog.utils.LogHeaderEnum
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Locale

object TripParser {
    private val header = HashMap<LogHeaderEnum, Int>()
    private var lastErrorValue: String? = null

    val lastError: String
        get() {
            val res = lastErrorValue ?: ""
            lastErrorValue = null
            return res
        }

    fun parseFile(context: Context, fileName: String, path: String, uri: Uri): List<LogTick> {
        lastErrorValue = null
        val inputStream: InputStream?
        try {
            inputStream = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 9 or less
                FileInputStream(File(path))
            } else {
                // Android 10+
                context.contentResolver.openInputStream(uri)
            }
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
            return emptyList()
        }
        if (inputStream == null) {
            lastErrorValue = context.getString(R.string.error_inputstream_null)
            Timber.wtf(lastErrorValue)
            return emptyList()
        }

        // read header
        val reader = BufferedReader(InputStreamReader(inputStream))
        val headerLine = reader.readLine().split(",").toTypedArray()
        for (i in headerLine.indices) {
            try {
                header[LogHeaderEnum.valueOf(headerLine[i].uppercase())] = i
            } catch (ignored: IllegalArgumentException) {
            }
        }
        if (!header.containsKey(LogHeaderEnum.LATITUDE) || !header.containsKey(LogHeaderEnum.LONGITUDE)) {
            inputStream.close()
            lastErrorValue = context.getString(R.string.error_this_file_without_gps, fileName)
            Timber.wtf(lastErrorValue)
            return emptyList()
        }

        val sdfTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val resultList = ArrayList<LogTick>()

        try {
            reader.forEachLine { line ->
                val row = line.split(",")
                val timeString = row[header[LogHeaderEnum.TIME]!!]
                val logTick = LogTick(
                    timeString = timeString,
                    time = sdfTime.parse(timeString)!!.time / 100f,
                    latitude = row[header[LogHeaderEnum.LATITUDE]!!].toDoubleOrNull() ?: 0.0,
                    longitude = row[header[LogHeaderEnum.LONGITUDE]!!].toDoubleOrNull() ?: 0.0,
                    altitude = row[header[LogHeaderEnum.GPS_ALT]!!].toDoubleOrNull() ?: 0.0,
                    batteryLevel = row[header[LogHeaderEnum.BATTERY_LEVEL]!!].toIntOrNull() ?: 0,
                    voltage = row[header[LogHeaderEnum.VOLTAGE]!!].toDoubleOrNull() ?: 0.0,
                    current = row[header[LogHeaderEnum.CURRENT]!!].toDoubleOrNull() ?: 0.0,
                    power = row[header[LogHeaderEnum.POWER]!!].toDoubleOrNull() ?: 0.0,
                    speed = row[header[LogHeaderEnum.SPEED]!!].toDoubleOrNull() ?: 0.0,
                    speedGps = row[header[LogHeaderEnum.GPS_SPEED]!!].toDoubleOrNull() ?: 0.0,
                    temperature = row[header[LogHeaderEnum.SYSTEM_TEMP]!!].toIntOrNull() ?: 0,
                    pwm = row[header[LogHeaderEnum.PWM]!!].toDoubleOrNull() ?: 0.0,
                    distance = row[header[LogHeaderEnum.DISTANCE]!!].toIntOrNull() ?: 0
                )
                resultList.add(logTick)
            }
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
            return resultList
        } finally {
            inputStream.close()
        }

        val dao = ElectroClub.instance.dao ?: return resultList
        var trip = dao.getTripByFileName(fileName)
        if (trip == null) {
            trip = TripDataDbEntry(fileName = fileName)
            dao.insert(trip)
        }
        try {
            val first = resultList.first()
            val last = resultList.last()
            trip.apply {
                duration = ((last.time - first.time) / 600.0).toInt()
                distance = resultList.maxOf { it.distance } - first.distance
                maxSpeedGps = resultList.maxOf { it.speedGps }.toFloat()
                maxCurrent = resultList.maxOf { it.current }.toFloat()
                maxPwm = resultList.maxOf { it.pwm }.toFloat()
                maxPower = resultList.maxOf { it.power }.toFloat()
                maxSpeed = resultList.maxOf { it.speed }.toFloat()
                avgSpeed = resultList.map { it.speed }.average().toFloat()
                consumptionTotal = resultList.map { it.power }.average().toFloat() * duration / 36F
                consumptionByKm = consumptionTotal * 1000F / distance
            }
            dao.update(trip)
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
        }

        return resultList
    }
}