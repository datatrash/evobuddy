package sh.datatra.evobuddy

import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

val weekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
fun temp(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.1f°", it) } ?: "—"
fun timeLabel(value: Instant, zone: ZoneId) = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(value)
fun modeLabel(mode: String) = mapOf("Auto" to "Following schedule", "AutoWithEco" to "Eco", "Away" to "Away", "DayOff" to "Day off", "Custom" to "Custom", "HeatingOff" to "Heating off", "AutoWithReset" to "Resume all schedules")[mode] ?: mode
fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.objects() ?: emptyList()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.numberOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

data class Switchpoint(val minute: Int, val temperature: Double) {
    val time: String get() = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
}
data class WeekSchedule(val days: List<List<Switchpoint>>) {
    fun validate(zone: Room) {
        require(days.size == 7) { "A schedule needs all seven days." }
        days.forEach { points ->
            require(points.size in zone.minPoints..zone.maxPoints) { "Use ${zone.minPoints}–${zone.maxPoints} changes per day." }
            require(points.map { it.minute }.distinct().size == points.size) { "Two changes cannot start at the same time." }
            require(points == points.sortedBy { it.minute }) { "Schedule times must be in order." }
            points.forEach {
                require(it.minute in 0..1439 && it.minute % zone.timeStep == 0) { "Times must use ${zone.timeStep}-minute steps." }
                require(zone.validTemperature(it.temperature)) { "Temperature must be ${temp(zone.min)}–${temp(zone.max)} in ${zone.step}° steps." }
            }
        }
    }
    fun next(now: Instant, zone: ZoneId): Pair<Instant, Double>? {
        val local = now.atZone(zone)
        return (0..7).flatMap { offset ->
            val date = local.toLocalDate().plusDays(offset.toLong())
            days.getOrElse(date.dayOfWeek.value - 1) { emptyList() }.map { point ->
                date.atStartOfDay().plusMinutes(point.minute.toLong()).atZone(zone).toInstant() to point.temperature
            }
        }.filter { it.first > now }.minByOrNull { it.first }
    }
    fun target(now: Instant, zone: ZoneId): Double? {
        val local = now.atZone(zone)
        return (0..7).flatMap { offset ->
            val date = local.toLocalDate().minusDays(offset.toLong())
            days.getOrElse(date.dayOfWeek.value - 1) { emptyList() }.map {
                date.atStartOfDay().plusMinutes(it.minute.toLong()).atZone(zone).toInstant() to it.temperature
            }
        }.filter { it.first <= now }.maxByOrNull { it.first }?.second
    }
    fun json(): JSONObject = JSONObject().put("dailySchedules", JSONArray(days.mapIndexed { index, points ->
        JSONObject().put("dayOfWeek", weekdays[index]).put("switchpoints", JSONArray(points.map {
            JSONObject().put("timeOfDay", it.time + ":00").put("heatSetpoint", it.temperature)
        }))
    }))
    companion object {
        fun parse(json: JSONObject): WeekSchedule {
            val entries = json.objects("dailySchedules")
            require(entries.size == 7 && entries.map { it.getString("dayOfWeek") }.toSet() == weekdays.toSet()) { "The cloud returned an incomplete weekly schedule." }
            return WeekSchedule(weekdays.map { name ->
                entries.first { it.getString("dayOfWeek") == name }.objects("switchpoints").map {
                    require(!it.has("fanMode") && !it.has("coolSetpoint")) { "This schedule contains unsupported cooling or fan controls." }
                    val time = LocalTime.parse(it.getString("timeOfDay"))
                    require(time.second == 0) { "Schedule seconds must be zero." }
                    Switchpoint(time.hour * 60 + time.minute, it.getDouble("heatSetpoint"))
                }.sortedBy { it.minute }
            })
        }
    }
}
data class Room(
    val id: String, val name: String, val measured: Double? = null, val target: Double? = null,
    val mode: String = "FollowSchedule", val until: Instant? = null, val available: Boolean = true,
    val min: Double = 5.0, val max: Double = 35.0, val step: Double = 0.5,
    val minPoints: Int = 1, val maxPoints: Int = 6, val timeStep: Int = 10,
    val allowed: List<String> = listOf("FollowSchedule", "TemporaryOverride", "PermanentOverride"),
    val schedule: WeekSchedule? = null
) {
    fun validTemperature(value: Double) = value.isFinite() && value in min..max && abs((value - min) / step - round((value - min) / step)) < 0.001
}
data class ModeOption(val id: String, val permanent: Boolean, val temporary: Boolean, val timing: String = "Period", val resolution: String = "1.00:00:00", val maximum: String = "99.00:00:00")
data class Home(val locationId: String, val systemId: String, val name: String, val zoneId: String, val rooms: List<Room>, val modes: List<ModeOption>, val mode: String = "Auto", val modeUntil: Instant? = null) {
    val zone: ZoneId get() = ZoneId.of(zoneId)
    val key: String get() = "$locationId-$systemId"
}
data class Snapshot(val homes: List<Home>, val updated: Instant)
data class Shortcut(val id: String, val name: String, val homeKey: String, val roomIds: List<String>, val temperature: Double, val minutes: Int, val endTime: String? = null) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("home", homeKey)
        .put("rooms", JSONArray(roomIds)).put("temperature", temperature).put("minutes", minutes).put("endTime", endTime)
    fun expiry(home: Home, now: Instant): Instant = (endTime?.let { nextClockTime(now, home.zone, LocalTime.parse(it)) }
        ?: now.plusSeconds(minutes * 60L)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    companion object {
        fun parse(json: JSONObject): Shortcut {
            // Earlier previews stored one room. Preserve those shortcuts on upgrade.
            val ids = json.optJSONArray("rooms")?.let { array -> (0 until array.length()).map { array.getString(it) } }
                ?: listOf(json.getString("room"))
            require(ids.isNotEmpty() && ids.all { it.isNotBlank() }) { "A shortcut needs at least one room." }
            val end = json.optString("endTime").takeIf { it.isNotBlank() }?.let { LocalTime.parse(it).withSecond(0).withNano(0).toString() }
            return Shortcut(json.getString("id"), json.getString("name"), json.getString("home"), ids.distinct(), json.getDouble("temperature"), json.getInt("minutes"), end)
        }
    }
}
fun nextClockTime(now: Instant, zone: ZoneId, time: LocalTime): Instant {
    for (day in 0L..1L) {
        val local = now.atZone(zone).toLocalDate().plusDays(day).atTime(time.withSecond(0).withNano(0))
        val offsets = zone.rules.getValidOffsets(local)
        val candidates = if (offsets.isEmpty()) listOf(local.atZone(zone).toInstant()) else offsets.map { local.atOffset(it).toInstant() }
        candidates.filter { it > now }.minOrNull()?.let { return it }
    }
    error("Could not find the next end time.")
}
fun endTimeLabel(end: Instant, home: Home, now: Instant): String {
    val day = if (end.atZone(home.zone).toLocalDate() == now.atZone(home.zone).toLocalDate()) "today" else "tomorrow"
    return "${timeLabel(end, home.zone)} $day"
}
data class RoomCommandResult(val roomId: String, val name: String, val status: String)
fun sharedTemperatures(rooms: List<Room>): List<Double> {
    val first = rooms.firstOrNull() ?: return emptyList()
    if (!first.step.isFinite() || first.step <= 0) return emptyList()
    return (0..((first.max - first.min) / first.step).toInt().coerceIn(0, 10000))
        .map { first.min + it * first.step }.filter { value -> rooms.all { it.validTemperature(value) } }
}
data class RoomEvent(val at: Instant, val temperature: Double?, val resumesSchedule: Boolean)
fun scheduledTargetAt(room: Room, home: Home, at: Instant): Double? {
    val target = room.schedule?.target(at, home.zone) ?: return null
    val mode = if (home.modeUntil != null && home.modeUntil <= at) "Auto" else home.mode
    return when (mode) {
        "Auto", "AutoWithReset" -> target
        "AutoWithEco" -> (target - 3.0).coerceAtLeast(room.min)
        else -> null // Away/custom/off settings are held on the controller; do not invent a target.
    }
}
fun nextRoomEvent(room: Room, home: Home, now: Instant): RoomEvent? = when {
    room.mode == "PermanentOverride" -> null
    room.mode == "TemporaryOverride" && room.until != null && room.until > now ->
        RoomEvent(room.until, scheduledTargetAt(room, home, room.until), true)
    else -> room.schedule?.next(now, home.zone)?.let { RoomEvent(it.first, scheduledTargetAt(room, home, it.first), false) }
}
fun orderedRooms(home: Home, savedOrder: List<String>, favourites: Set<String>): List<Room> {
    if (savedOrder.isEmpty()) return home.rooms.sortedByDescending { "${home.key}:${it.id}" in favourites }
    val rank = savedOrder.distinct().withIndex().associate { it.value to it.index }
    return home.rooms.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
}
fun moveRoomOrder(ids: List<String>, from: String, to: String): List<String> {
    val result = ids.distinct().toMutableList()
    val source = result.indexOf(from)
    val target = result.indexOf(to)
    if (source < 0 || target < 0 || source == target) return result
    result.add(target, result.removeAt(source))
    return result
}

fun resolveZone(id: String): String {
    val mapped = mapOf("WEuropeStandardTime" to "Europe/Amsterdam", "GMTStandardTime" to "Europe/London", "RomanceStandardTime" to "Europe/Paris", "CentralEuropeStandardTime" to "Europe/Budapest", "CentralEuropeanStandardTime" to "Europe/Warsaw", "FLEStandardTime" to "Europe/Helsinki", "EEuropeStandardTime" to "Europe/Chisinau", "GTBStandardTime" to "Europe/Athens", "TurkeyStandardTime" to "Europe/Istanbul", "UTC" to "UTC")[id.replace(" ", "").replace(".", "")] ?: id
    return try { ZoneId.of(mapped).id } catch (_: Exception) { throw IllegalArgumentException("Unsupported controller time zone: $id. No schedule changes were made.") }
}

fun parseHomes(config: JSONArray): List<Home> = config.objects().flatMap { location ->
    val info = location.getJSONObject("locationInfo")
    val locationId = info.getString("locationId")
    val zone = resolveZone(info.getJSONObject("timeZone").getString("timeZoneId"))
    location.objects("gateways").flatMap { gateway -> gateway.objects("temperatureControlSystems").map { system ->
        val rooms = system.objects("zones").map { raw ->
            val cap = raw.getJSONObject("setpointCapabilities")
            val sched = raw.optJSONObject("scheduleCapabilities") ?: JSONObject()
            Room(raw.getString("zoneId"), raw.getString("name"), min = cap.getDouble("minHeatSetpoint"), max = cap.getDouble("maxHeatSetpoint"), step = cap.optDouble("valueResolution", 0.5),
                minPoints = sched.optInt("minSwitchpointsPerDay", 1), maxPoints = sched.optInt("maxSwitchpointsPerDay", 6),
                timeStep = durationMinutes(sched.optString("timingResolution", "00:10:00")).coerceAtLeast(1),
                allowed = cap.optJSONArray("allowedSetpointModes")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList())
        }
        val modes = system.objects("allowedSystemModes").map { ModeOption(it.getString("systemMode"), it.optBoolean("canBePermanent"), it.optBoolean("canBeTemporary"), it.optString("timingMode", "Period"), it.optString("timingResolution", "1.00:00:00"), it.optString("maxDuration", "99.00:00:00")) }
        Home(locationId, system.getString("systemId"), info.optString("name", "My home"), zone, rooms, modes)
    } }
}
fun durationMinutes(text: String): Int {
    val dayPart = if (text.contains('.')) text.substringBefore('.').toInt() else 0
    val parts = text.substringAfter('.', text).split(':').map { it.toInt() }
    return dayPart * 1440 + parts[0] * 60 + parts[1]
}
fun applyStatus(home: Home, status: JSONObject): Home {
    val system = status.objects("gateways").flatMap { it.objects("temperatureControlSystems") }.firstOrNull { it.getString("systemId") == home.systemId }
        ?: return home.copy(rooms = home.rooms.map { it.copy(available = false) })
    val mode = system.optJSONObject("systemModeStatus") ?: JSONObject()
    return home.copy(mode = mode.optString("mode", home.mode), modeUntil = mode.optString("timeUntil").toInstantOrNull(), rooms = home.rooms.map { room ->
        val raw = system.objects("zones").firstOrNull { it.getString("zoneId") == room.id }
        val reading = raw?.optJSONObject("temperatureStatus")
        val setpoint = raw?.optJSONObject("setpointStatus")
        room.copy(measured = reading?.numberOrNull("temperature"), available = reading?.optBoolean("isAvailable", false) == true,
            target = setpoint?.numberOrNull("targetHeatTemperature"), mode = setpoint?.optString("setpointMode", room.mode) ?: room.mode, until = setpoint?.optString("until")?.toInstantOrNull())
    })
}
fun String.toInstantOrNull(): Instant? = try { Instant.parse(this) } catch (_: Exception) { null }

fun Snapshot.json(): JSONObject = JSONObject().put("updated", updated.toString()).put("homes", JSONArray(homes.map { home ->
    JSONObject().put("location", home.locationId).put("system", home.systemId).put("name", home.name).put("zone", home.zoneId).put("mode", home.mode).put("until", home.modeUntil?.toString())
        .put("modes", JSONArray(home.modes.map { JSONObject().put("id", it.id).put("permanent", it.permanent).put("temporary", it.temporary).put("timing", it.timing).put("resolution", it.resolution).put("maximum", it.maximum) }))
        .put("rooms", JSONArray(home.rooms.map { room -> JSONObject().put("id", room.id).put("name", room.name).put("measured", room.measured).put("target", room.target).put("mode", room.mode).put("until", room.until?.toString()).put("available", room.available)
            .put("min", room.min).put("max", room.max).put("step", room.step).put("minPoints", room.minPoints).put("maxPoints", room.maxPoints).put("timeStep", room.timeStep).put("allowed", JSONArray(room.allowed)).put("schedule", room.schedule?.json()) }))
}))
fun snapshotFromJson(json: JSONObject): Snapshot = Snapshot(json.objects("homes").map { home ->
    Home(home.getString("location"), home.getString("system"), home.getString("name"), home.getString("zone"), home.objects("rooms").map { room ->
        Room(room.getString("id"), room.getString("name"), room.numberOrNull("measured"), room.numberOrNull("target"), room.getString("mode"), room.optString("until").toInstantOrNull(), room.getBoolean("available"), room.getDouble("min"), room.getDouble("max"), room.getDouble("step"), room.getInt("minPoints"), room.getInt("maxPoints"), room.getInt("timeStep"), room.getJSONArray("allowed").let { a -> (0 until a.length()).map { a.getString(it) } }, room.optJSONObject("schedule")?.let(WeekSchedule::parse))
    }, home.objects("modes").map { ModeOption(it.getString("id"), it.getBoolean("permanent"), it.getBoolean("temporary"), it.getString("timing"), it.getString("resolution"), it.getString("maximum")) }, home.getString("mode"), home.optString("until").toInstantOrNull())
}, Instant.parse(json.getString("updated")))

fun demoSnapshot(): Snapshot {
    val schedule = WeekSchedule(List(7) { listOf(Switchpoint(390, 20.5), Switchpoint(510, 17.0), Switchpoint(1020, 21.0), Switchpoint(1350, 16.0)) })
    val rooms = listOf(Room("living", "Living room", 20.1, 21.0), Room("kitchen", "Kitchen", 19.4, 20.0), Room("office", "Office", 19.8, 20.5, "TemporaryOverride", Instant.now().plusSeconds(3600)), Room("bedroom", "Bedroom", 17.8, 17.0), Room("bathroom", "Bathroom", 21.0, 22.0), Room("hall", "Hallway", 18.2, 17.0))
    val modes = listOf("Auto", "AutoWithEco", "Away", "DayOff", "Custom", "HeatingOff", "AutoWithReset").map { ModeOption(it, true, it in listOf("AutoWithEco", "Away", "DayOff", "Custom"), if (it == "AutoWithEco") "Duration" else "Period", if (it == "AutoWithEco") "01:00:00" else "1.00:00:00", if (it == "AutoWithEco") "1.00:00:00" else "99.00:00:00") }
    return Snapshot(listOf(Home("demo", "demo", "Our home", "Europe/Amsterdam", rooms.map { it.copy(schedule = schedule) }, modes)), Instant.now())
}
