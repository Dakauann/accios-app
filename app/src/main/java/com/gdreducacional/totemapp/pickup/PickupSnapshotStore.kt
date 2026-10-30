package com.gdreducacional.totemapp.pickup

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class PickupSnapshot(
    val payload: String,
    val snapshotId: String,
    val anchor: PickupTimeAnchor
)

/**
 * The device only accepts a complete roster after the server has returned a
 * version and a bounded validity. The prior roster survives any failed write.
 */
class PickupSnapshotStore(context: Context) {
    private val encrypted = PickupEncryptedStore(context, "snapshot")

    fun clear() = encrypted.delete()

    fun save(payload: String, startedElapsed: Long, receivedElapsed: Long, bootCount: Int): PickupSnapshot {
        require(bootCount >= 0 && startedElapsed >= 0 && receivedElapsed >= startedElapsed)
        val json = JSONObject(payload)
        val snapshotId = validatePayload(json)
        val generated = isoMillis(json.getString("generatedAt"))
        val serverTime = isoMillis(json.getString("serverTime"))
        val validUntil = isoMillis(json.getString("validUntil"))
        require(generated <= serverTime && serverTime < validUntil && validUntil - generated <= 86_400_000L)
        val anchor = PickupTimeAnchor(serverTime, receivedElapsed, bootCount, receivedElapsed - startedElapsed, validUntil)
        require(PickupClock.current(anchor, receivedElapsed, bootCount) != null)
        val envelope = JSONObject()
            .put("payload", json)
            .put("receivedElapsed", receivedElapsed)
            .put("roundTrip", receivedElapsed - startedElapsed)
            .put("bootCount", bootCount)
        encrypted.write(envelope.toString())
        return PickupSnapshot(json.toString(), snapshotId, anchor)
    }

    fun load(): PickupSnapshot? {
        val raw = encrypted.read() ?: return null
        val envelope = JSONObject(raw)
        val json = envelope.getJSONObject("payload")
        val snapshotId = validatePayload(json)
        val generated = isoMillis(json.getString("generatedAt"))
        val serverTime = isoMillis(json.getString("serverTime"))
        val validUntil = isoMillis(json.getString("validUntil"))
        require(generated <= serverTime && serverTime < validUntil && validUntil - generated <= 86_400_000L)
        val anchor = PickupTimeAnchor(
            serverTimeMillis = serverTime,
            receivedElapsedMillis = envelope.getLong("receivedElapsed"),
            bootCount = envelope.getInt("bootCount"),
            roundTripMillis = envelope.getLong("roundTrip"),
            validUntilMillis = validUntil
        )
        return PickupSnapshot(json.toString(), snapshotId, anchor)
    }

    private fun validatePayload(json: JSONObject): String {
        val snapshotId = json.getString("snapshotId")
        require(UUID.fromString(snapshotId).toString() == snapshotId.lowercase(Locale.US))
        val dataset = json.getJSONArray("dataset")
        val dimension = json.getInt("embeddingDimension")
        require((dataset.length() == 0 && dimension == 0) || (dataset.length() > 0 && dimension > 0))
        val seen = HashSet<String>()
        for (index in 0 until dataset.length()) {
            val person = dataset.getJSONObject(index)
            val id = person.getString("id")
            require(id.isNotBlank() && seen.add(id))
            require(person.getString("entityType") in setOf("user", "pickup_person"))
            require(person.getString("name").isNotBlank())
            val vector = person.getJSONArray("embedding")
            require(vector.length() == dimension)
            for (i in 0 until vector.length()) require(vector.getDouble(i).isFinite())
            val students = person.getJSONArray("students")
            require(students.length() > 0)
            val seenStudents = HashSet<String>()
            for (i in 0 until students.length()) {
                val student = students.getJSONObject(i)
                require(student.getString("studentHasBranchId").let { it.isNotBlank() && seenStudents.add(it) })
                require(student.getString("studentId").isNotBlank())
                require(student.getString("name").isNotBlank())
                require(student.getString("className").isNotBlank())
                val validity = student.getString("validity")
                val date = if (student.isNull("validDate")) null else student.getString("validDate")
                require(
                    (validity == "UNLIMITED" && date == null) ||
                        (validity in setOf("UNTIL_DATE", "ON_DATE") && date != null && PickupClock.isValidDate(date))
                )
                if (!student.isNull("photoBase64")) require(student.getString("photoBase64").length <= 500_000)
            }
        }
        return snapshotId
    }

    private fun isoMillis(value: String): Long {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val parsed = formatter.parse(value) ?: throw IllegalArgumentException("Data inválida")
        require(formatter.format(parsed) == value)
        return parsed.time
    }
}
