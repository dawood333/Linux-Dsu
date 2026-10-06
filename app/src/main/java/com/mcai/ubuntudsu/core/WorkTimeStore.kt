package com.mcai.ubuntudsu.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 日历工时记 · 数据层
 *
 * 用 SharedPreferences 持久化上下班打卡记录 + 全部配置（工作日、加班倍率、工资模式、上下班基准时间）。
 *  - 记录：JSON 数组 [{date:"yyyy-MM-dd", clockIn:epochSec, clockOut:epochSec|0}]，同日仅一条
 *  - 工资模式：HOURLY（时薪×工时）或 MONTHLY（月薪÷当月标准工时×实工）
 *  - 加班倍率：工作日 / 休息日 / 节假日 三个倍率（节假日固定 3.0，参照劳动法）
 *  - 工作日：一周 7 天位图（bit i = 周一..周日 是否工作日），休息日打卡自动按休息日倍率算加班
 *  - 迟到/早退：相对上下班基准时间判定
 *  - 吃饭/休息扣除：每日固定扣除时长（分钟），打卡总时长减去后为净工时
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

    // ============ 打卡记录 ============

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

    /** 上班打卡：当日无记录时创建；已有进行中或已完工返回 false */
    fun clockIn(date: String, nowEpochSec: Long): Boolean {
        val records = loadRecords().filter { it.date != date }.toMutableList()
        records.add(Record(date, nowEpochSec, 0L))
        return save(records)
    }

    /** 下班打卡：当日有进行中记录时闭合；否则 false */
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

    /** 删除某日全部记录（含进行中的），用于异常时间重打 */
    fun deleteRecord(date: String): Boolean {
        val records = loadRecords().filter { it.date != date }
        return save(records)
    }

    /** 覆盖写入某日上下班时间；clockOut=0 表示进行中。下班须晚于上班。 */
    fun upsertRecord(date: String, clockIn: Long, clockOut: Long): Boolean {
        if (clockIn <= 0L) return false
        if (clockOut != 0L && clockOut <= clockIn) return false
        val records = loadRecords().filter { it.date != date }.toMutableList()
        records.add(Record(date, clockIn, clockOut))
        return save(records)
    }

    // ============ 工资配置 ============

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

    /** 月标准工时（月薪模式下折算基准，默认 21.75×8≈174 小时/月） */
    fun getStdMonthHours(): Double =
        prefs.getString(KEY_STD_HOURS, "174")?.toDoubleOrNull() ?: 174.0

    fun setStdMonthHours(v: Double) {
        if (v > 0.0) prefs.edit().putString(KEY_STD_HOURS, v.toString()).commit()
    }

    // ============ 工作日设置（bit i = 周一..周日） ============

    /** 返回 7 个布尔：[周一,周二,...,周日]，默认周一~五工作日 */
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

    /** 某日（周一=0..周日=6）是否工作日 */
    fun isWorkday(date: String): Boolean {
        val wd = Calendar.getInstance().apply {
            time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date) ?: return false
        }.get(Calendar.DAY_OF_WEEK)
        val idx = if (wd == Calendar.SUNDAY) 6 else wd - 2
        val bits = getWorkdays()
        return idx < bits.size && bits[idx]
    }

    // ============ 加班倍率 ============

    /** 加班倍率是否启用（默认关闭，关闭时 UI 隐藏三个倍率参数） */
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

    // ============ 上下班基准（迟到/早退判定） ============

    /** 上班时间 "HH:mm"，默认 09:00 */
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

    // ============ 吃饭 / 休息扣除 ============

    /** 每日吃饭/休息扣除时长（分钟），默认 60；设 0 表示不扣除 */
    fun getRestDeductMinutes(): Int =
        prefs.getString(KEY_REST_DEDUCT, "60")?.toIntOrNull() ?: 60

    fun setRestDeductMinutes(minutes: Int) {
        if (minutes >= 0) prefs.edit().putString(KEY_REST_DEDUCT, minutes.toString()).commit()
    }

    /** 扣除吃饭/休息后的净工时（小时）；进行中/异常记录返回 0 */
    fun netHours(record: Record): Double {
        if (record.inProgress) return 0.0
        val deduct = getRestDeductMinutes() / 60.0
        return (record.hours - deduct).coerceAtLeast(0.0)
    }

    /** 把 "HH:mm" 基准 + 某日 0 点 转成该日某时刻的 epoch 秒 */
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
        val late: Boolean,      // 迟到：上班打卡晚于基准上班时间
        val early: Boolean,     // 早退：下班打卡早于基准下班时间
    )

    /** 按上下班基准判定某天迟到/早退 */
    fun evalLateEarly(date: String, record: Record): DayEval {
        val inBase = baseEpoch(date, getBaseClockIn())
        val outBase = baseEpoch(date, getBaseClockOut())
        val late = inBase > 0 && record.clockIn > inBase
        val early = outBase > 0 && record.clockOut > 0 && record.clockOut < outBase
        return DayEval(late, early)
    }

    // ============ 月度统计 ============

    /** 某月所有记录（date 前缀匹配 yyyy-MM），含进行中未闭合 */
    fun monthRecords(yearMonth: String): List<Record> =
        loadRecords().filter { it.date.startsWith(yearMonth) }

    /** 某月总工时（小时），未闭合记录不计入（只统计已完工的），并扣除吃饭/休息时长 */
    fun monthTotalHours(yearMonth: String): Double =
        monthRecords(yearMonth).filter { !it.inProgress }.sumOf { netHours(it) }

    /**
     * 某月工资：
     *  - 时薪模式：总工时 × 时薪
     *  - 月薪模式：总工时 ÷ 月标准工时 × 月薪
     */
    fun monthSalary(yearMonth: String): Double {
        val total = monthTotalHours(yearMonth)
        return when (getPayMode()) {
            PayMode.HOURLY -> total * getHourlyRate()
            PayMode.MONTHLY -> if (getStdMonthHours() > 0)
                total / getStdMonthHours() * getMonthlySalary() else 0.0
        }
    }

    /** 某日是否节假日（本地维护：仅按“非工作日”近似——休息日打卡视加班；法定节假日需用户另设，此处用节假日倍率） */

    // ============ 导出 ============

    /** 导出某月记录为 CSV 文本（含表头），用于复制/分享 */
    fun exportMonthCsv(yearMonth: String, monthLabel: String): String {
        val lines = mutableListOf("日期,上班,下班,工时(小时),备注")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (r in monthRecords(yearMonth).sortedBy { it.date }) {
            val ts = r.clockIn * 1000L
            val to = r.clockOut * 1000L
            val inProg = r.inProgress
            val remark = if (inProg) "进行中" else "已完工"
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
        return "$monthLabel 工时记录\n" + lines.joinToString("\n")
    }
}
