package com.mcai.ubuntudsu.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * [translated]Work time[translated] · data[translated]
 *
 * [translated] SharedPreferences [translated]End[translated] + All[translated]Work[translated]End[translated]
 *  - [translated]JSON [translated] [{date:"yyyy-MM-dd", clockIn:epochSec, clockOut:epochSec|0}][translated]
 *  - [translated]HOURLY[translated]×Work time[translated] MONTHLY[translated]÷[translated]Work time×[translated]
 *  - [translated]Work[translated] / Break[translated] / [translated] [translated] item[translated] 3.0[translated]
 *  - Work[translated] 7 [translated]bit i = [translated]..[translated] [translated]Work[translated]Break[translated]Auto[translated]Break[translated]
 *  - [translated]/[translated]End[translated]
 *  - [translated]/Break[translated]Work time
 */
class WorkTimeStore(private val context: Context) {

    enum class PayMode { HOURLY, MONTHLY }

    data class Record(
        val date: String,
        val clockIn: Long,
        var clockOut: Long,
    ) {
        val inProgress: Boolean get() = clockOut == 0L
        val hours: Double get() = if (clockOut > clockIn) (clockOut - clockIn) / 3600.0 else 0.0
    }

    private val prefs = context.getSharedPreferences("work_time", Context.MODE_PRIVATE)
    private val KEY_RECORDS = "records"
    private val KEY_RATE = "hourly_rate"
    private val KEY_SALARY = "monthly_salary"
    private val KEY_PAYMODE = "pay_mode"
    private val KEY_WORKDAYS = "workdays"
    private val KEY_RATE_WEEKDAY = "ot_rate_weekday"
    private val KEY_RATE_REST = "ot_rate_rest"
    private val KEY_RATE_HOLIDAY = "ot_rate_holiday"
    private val KEY_STD_HOURS = "std_month_hours"
    private val KEY_BASE_IN = "base_clock_in"
    private val KEY_BASE_OUT = "base_clock_out"
    private val KEY_REST_DEDUCT = "rest_deduct_minutes"
    private val KEY_OT_ENABLED = "ot_enabled"

    // ============ [translated] ============

    fun loadRecords(): List<Record> = try {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Record(
                date = o.getString("date"),
                clockIn = o.getLong("clockIn"),
                clockOut = o.optLong("clockOut", 0L),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(records: List<Record>): Boolean = try {
        val arr = JSONArray()
        for (r in records) {
            val o = JSONObject()
            o.put("date", r.date)
            o.put("clockIn", r.clockIn)
            o.put("clockOut", r.clockOut)
            arr.put(o)
        }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).commit()
    } catch (e: Exception) {
        false
    }

    fun getRecordFor(date: String): Record? = loadRecords().firstOrNull { it.date == date }

    /** Start[translated]In progress[translated]CompletedBack false */
    fun clockIn(date: String, nowEpochSec: Long): Boolean {
        val records = loadRecords().filter { it.date != date }.toMutableList()
        records.add(Record(date, nowEpochSec, 0L))
        return save(records)
    }

    /** End[translated]In progress[translated] false */
    fun clockOut(date: String, nowEpochSec: Long): Boolean {
        val records = loadRecords().toMutableList()
        val target = records.firstOrNull { it.date == date } ?: return false
        if (target.clockOut != 0L || target.clockIn == 0L) return false
        if (nowEpochSec <= target.clockIn) return false
        target.clockOut = nowEpochSec
        records.removeAt(records.indexOf(target))
        records.add(target)
        return save(records)
    }

    /** Delete[translated]All[translated]In progress[translated] */
    fun deleteRecord(date: String): Boolean {
        val records = loadRecords().filter { it.date != date }
        return save(records)
    }

    /** [translated]End[translated]clockOut=0 [translated]In progress[translated]End[translated]Start[translated] */
    fun upsertRecord(date: String, clockIn: Long, clockOut: Long): Boolean {
        if (clockIn <= 0L) return false
        if (clockOut != 0L && clockOut <= clockIn) return false
        val records = loadRecords().filter { it.date != date }.toMutableList()
        records.add(Record(date, clockIn, clockOut))
        return save(records)
    }

    // ============ [translated] ============

    fun getPayMode(): PayMode =
        if (prefs.getString(KEY_PAYMODE, PayMode.HOURLY.name) == PayMode.MONTHLY.name)
            PayMode.MONTHLY else PayMode.HOURLY

    fun setPayMode(mode: PayMode) {
        prefs.edit().putString(KEY_PAYMODE, mode.name).commit()
    }

    fun getHourlyRate(): Double =
        prefs.getString(KEY_RATE, null)?.toDoubleOrNull() ?: 0.0

    fun setHourlyRate(rate: Double): Boolean {
        if (rate <= 0.0) return false
        prefs.edit().putString(KEY_RATE, rate.toString()).commit()
        return true
    }

    fun getMonthlySalary(): Double =
        prefs.getString(KEY_SALARY, null)?.toDoubleOrNull() ?: 0.0

    fun setMonthlySalary(v: Double): Boolean {
        if (v <= 0.0) return false
        prefs.edit().putString(KEY_SALARY, v.toString()).commit()
        return true
    }

    /** [translated]Work time[translated] 21.75×8≈174 [translated]/[translated] */
    fun getStdMonthHours(): Double =
        prefs.getString(KEY_STD_HOURS, "174")?.toDoubleOrNull() ?: 174.0

    fun setStdMonthHours(v: Double) {
        if (v > 0.0) prefs.edit().putString(KEY_STD_HOURS, v.toString()).commit()
    }

    // ============ Work[translated]Settings[translated]bit i = [translated]..[translated] ============

    /** Back 7  item[translated][[translated],[translated],...,[translated]][translated]~[translated]Work[translated] */
    fun getWorkdays(): BooleanArray {
        val raw = prefs.getString(KEY_WORKDAYS, null) ?: "1111100"
        val b = BooleanArray(7)
        for (i in 0 until 7) {
            b[i] = i < raw.length && raw[i] == '1'
        }
        return b
    }

    fun setWorkdays(bits: BooleanArray) {
        val s = StringBuilder()
        for (i in 0 until 7) s.append(if (i < bits.size && bits[i]) '1' else '0')
        prefs.edit().putString(KEY_WORKDAYS, s.toString()).commit()
    }

    /** [translated]=0..[translated]=6[translated]Work[translated] */
    fun isWorkday(date: String): Boolean {
        val wd = Calendar.getInstance().apply {
            time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date) ?: return false
        }.get(Calendar.DAY_OF_WEEK)
        val idx = if (wd == Calendar.SUNDAY) 6 else wd - 2
        val bits = getWorkdays()
        return idx < bits.size && bits[idx]
    }

    // ============ [translated] ============

    /** [translated]Close[translated]Close[translated] UI [translated] item[translated] */
    fun getOtEnabled(): Boolean = prefs.getString(KEY_OT_ENABLED, "0") == "1"

    fun setOtEnabled(enabled: Boolean) {
        prefs.edit().putString(KEY_OT_ENABLED, if (enabled) "1" else "0").commit()
    }

    fun getOtRateWeekday(): Double =
        prefs.getString(KEY_RATE_WEEKDAY, "1.5")?.toDoubleOrNull() ?: 1.5

    fun setOtRateWeekday(v: Double) =
        prefs.edit().putString(KEY_RATE_WEEKDAY, v.toString()).commit()

    fun getOtRateRest(): Double =
        prefs.getString(KEY_RATE_REST, "2.0")?.toDoubleOrNull() ?: 2.0

    fun setOtRateRest(v: Double) =
        prefs.edit().putString(KEY_RATE_REST, v.toString()).commit()

    fun getOtRateHoliday(): Double =
        prefs.getString(KEY_RATE_HOLIDAY, "3.0")?.toDoubleOrNull() ?: 3.0

    fun setOtRateHoliday(v: Double) =
        prefs.edit().putString(KEY_RATE_HOLIDAY, v.toString()).commit()

    // ============ [translated]End[translated]/[translated] ============

    /** Start[translated] "HH:mm"[translated] 09:00 */
    fun getBaseClockIn(): String = prefs.getString(KEY_BASE_IN, "09:00") ?: "09:00"

    fun setBaseClockIn(hhmm: String) {
        if (hhmm.matches(Regex("\\d{1,2}:\\d{2}")))
            prefs.edit().putString(KEY_BASE_IN, hhmm).commit()
    }

    fun getBaseClockOut(): String = prefs.getString(KEY_BASE_OUT, "18:00") ?: "18:00"

    fun setBaseClockOut(hhmm: String) {
        if (hhmm.matches(Regex("\\d{1,2}:\\d{2}")))
            prefs.edit().putString(KEY_BASE_OUT, hhmm).commit()
    }

    // ============ [translated] / Break[translated] ============

    /** [translated]/Break[translated] 60[translated] 0 [translated] */
    fun getRestDeductMinutes(): Int =
        prefs.getString(KEY_REST_DEDUCT, "60")?.toIntOrNull() ?: 60

    fun setRestDeductMinutes(minutes: Int) {
        if (minutes >= 0) prefs.edit().putString(KEY_REST_DEDUCT, minutes.toString()).commit()
    }

    /** [translated]/Break[translated]Work time[translated]In progress/[translated]Back 0 */
    fun netHours(record: Record): Double {
        if (record.inProgress) return 0.0
        val deduct = getRestDeductMinutes() / 60.0
        return (record.hours - deduct).coerceAtLeast(0.0)
    }

    /** [translated] "HH:mm" [translated] + [translated] 0 [translated] [translated] epoch [translated] */
    private fun baseEpoch(date: String, hhmm: String): Long {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val c = Calendar.getInstance().apply {
            time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date) ?: return 0L
        }
        return c.timeInMillis / 1000L + h * 3600L + m * 60L
    }

    data class DayEval(
        val late: Boolean,      // 迟到：Start打卡晚于基准Start时间
        val early: Boolean,     // 早退：End打卡早于基准End时间
    )

    /** [translated]End[translated]/[translated] */
    fun evalLateEarly(date: String, record: Record): DayEval {
        val inBase = baseEpoch(date, getBaseClockIn())
        val outBase = baseEpoch(date, getBaseClockOut())
        val late = inBase > 0 && record.clockIn > inBase
        val early = outBase > 0 && record.clockOut > 0 && record.clockOut < outBase
        return DayEval(late, early)
    }

    // ============ [translated] ============

    /** [translated]All[translated]date [translated] yyyy-MM[translated]In progress[translated] */
    fun monthRecords(yearMonth: String): List<Record> =
        loadRecords().filter { it.date.startsWith(yearMonth) }

    /** [translated]Work time[translated]Completed[translated]/Break[translated] */
    fun monthTotalHours(yearMonth: String): Double =
        monthRecords(yearMonth).filter { !it.inProgress }.sumOf { netHours(it) }

    /**
     * [translated]
     *  - [translated]Work time × [translated]
     *  - [translated]Work time ÷ [translated]Work time × [translated]
     */
    fun monthSalary(yearMonth: String): Double {
        val total = monthTotalHours(yearMonth)
        return when (getPayMode()) {
            PayMode.HOURLY -> total * getHourlyRate()
            PayMode.MONTHLY -> if (getStdMonthHours() > 0)
                total / getStdMonthHours() * getMonthlySalary() else 0.0
        }
    }

    /** [translated]Local[translated]“[translated]Work[translated]”[translated]——Break[translated] */

    // ============ [translated] ============

    /** [translated] CSV [translated]Copy/[translated] */
    fun exportMonthCsv(yearMonth: String, monthLabel: String): String {
        val lines = mutableListOf("Date,Start,End,Work Time (hours),Notes")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (r in monthRecords(yearMonth).sortedBy { it.date }) {
            val ts = r.clockIn * 1000L
            val to = r.clockOut * 1000L
            val inProg = r.inProgress
            val remark = if (inProg) "In progress" else "Completed"
            lines.add(
                String.format(
                    Locale.getDefault(),
                    "%s,%s,%s,%.2f,%s",
                    r.date,
                    if (r.clockIn > 0) df.format(ts) else "-",
                    if (!inProg) df.format(to) else "-",
                    netHours(r),
                    remark
                )
            )
        }
        return "$monthLabel Work time records\n" + lines.joinToString("\n")
    }
}
