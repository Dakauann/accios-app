package com.gdreducacional.totemapp.pickup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PickupQueueStatus(val pending: Int, val rejected: Int)
data class PickupQueueInspection(val pendingIds: Set<String>, val rejectedIds: Set<String>)
data class PickupRejectedEvent(val id: String, val studentName: String, val reason: String)

/**
 * No event is removed until the server acknowledges its ID. Rejected events
 * remain on the device for operator follow-up rather than being lost.
 */
class PickupExitQueue(context: Context) {
    private val store = PickupEncryptedStore(context, "pending-exits")

    @Synchronized
    fun append(events: List<JSONObject>, studentNames: Map<String, String> = emptyMap()) {
        require(events.isNotEmpty())
        val queue = read()
        val byId = (0 until queue.length()).associate { index ->
            val entry = queue.getJSONObject(index)
            entry.getJSONObject("event").getString("id") to entry
        }.toMutableMap()
        for (event in events) {
            val id = event.getString("id")
            require(UUID.fromString(id).toString() == id)
            val previous = byId[id]
            if (previous != null) {
                require(previous.getJSONObject("event").toString() == event.toString())
                continue
            }
            val entry = JSONObject().put("event", JSONObject(event.toString()))
            studentNames[id]?.let { entry.put("studentName", it) }
            queue.put(entry)
            byId[id] = entry
        }
        write(queue)
    }

    @Synchronized
    fun pendingBatch(limit: Int = 50): List<JSONObject> {
        require(limit in 1..50)
        val queue = read()
        val result = ArrayList<JSONObject>()
        for (index in 0 until queue.length()) {
            val entry = queue.getJSONObject(index)
            if (!entry.has("rejection")) {
                result.add(JSONObject(entry.getJSONObject("event").toString()))
                if (result.size == limit) break
            }
        }
        return result
    }

    @Synchronized
    fun applyResults(results: JSONArray) {
        val accepted = HashSet<String>()
        val rejected = HashMap<String, String>()
        for (index in 0 until results.length()) {
            val result = results.getJSONObject(index)
            val id = result.getString("id")
            when (result.getString("status")) {
                "accepted" -> accepted.add(id)
                "rejected" -> rejected[id] = result.optString("message", "Retirada não aceita pelo servidor.")
            }
        }
        val queue = read()
        val next = JSONArray()
        for (index in 0 until queue.length()) {
            val entry = queue.getJSONObject(index)
            val id = entry.getJSONObject("event").getString("id")
            if (id in accepted) continue
            rejected[id]?.let { entry.put("rejection", it.take(300)) }
            next.put(entry)
        }
        write(next)
    }

    @Synchronized
    fun status(): PickupQueueStatus {
        val queue = read()
        var rejected = 0
        for (index in 0 until queue.length()) if (queue.getJSONObject(index).has("rejection")) rejected++
        return PickupQueueStatus(queue.length() - rejected, rejected)
    }

    @Synchronized
    fun pendingOnlineNames(): Map<String, String> {
        val queue = read()
        val names = mutableMapOf<String, String>()
        for (index in 0 until queue.length()) {
            val entry = queue.getJSONObject(index)
            val event = entry.getJSONObject("event")
            if (!entry.has("rejection") && !event.getBoolean("offline")) {
                names[event.getString("id")] = entry.optString("studentName", "Aluno")
            }
        }
        return names
    }

    @Synchronized
    fun inspect(ids: Set<String>): PickupQueueInspection {
        val queue = read()
        val pending = mutableSetOf<String>()
        val rejected = mutableSetOf<String>()
        for (index in 0 until queue.length()) {
            val entry = queue.getJSONObject(index)
            val id = entry.getJSONObject("event").getString("id")
            if (id !in ids) continue
            if (entry.has("rejection")) rejected.add(id) else pending.add(id)
        }
        return PickupQueueInspection(pending, rejected)
    }

    @Synchronized
    fun rejectedEvents(): List<PickupRejectedEvent> {
        val queue = read()
        val result = ArrayList<PickupRejectedEvent>()
        for (index in 0 until queue.length()) {
            val entry = queue.getJSONObject(index)
            if (!entry.has("rejection")) continue
            result.add(PickupRejectedEvent(
                id = entry.getJSONObject("event").getString("id"),
                studentName = entry.optString("studentName", "Aluno"),
                reason = entry.getString("rejection")
            ))
        }
        return result
    }

    private fun read(): JSONArray {
        val raw = store.read() ?: return JSONArray()
        return JSONObject(raw).getJSONArray("events")
    }

    private fun write(queue: JSONArray) {
        store.write(JSONObject().put("events", queue).toString())
    }
}
