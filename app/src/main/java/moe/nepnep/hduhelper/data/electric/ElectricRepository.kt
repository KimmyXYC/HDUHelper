package moe.nepnep.hduhelper.data.electric

import kotlinx.serialization.json.*

class ElectricRepository(private val session: NeoSession) : ElectricSource {
    private suspend fun get(path: String) = session.request("campuslife/electric/$path")
    override suspend fun binding(): ElectricBinding? {
        val data = get("binding")
        if (data == JsonNull) return null
        val obj = data.jsonObject
        val room = obj.long("roomId") ?: return null
        if (room <= 0) return null
        return ElectricBinding(room, obj.string("buildingName").orEmpty(), obj.string("floorName").orEmpty(), obj.string("roomName").orEmpty())
    }
    override suspend fun balance(): ElectricBalance {
        val obj = get("balance").jsonObject
        return ElectricBalance(obj.string("balance")?.toBigDecimalOrNull()?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString(), obj.long("time"))
    }
    override suspend fun history(): ElectricHistory {
        val obj = get("history").jsonObject
        val items = (obj["history"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            ElectricHistoryItem(it.string("time").orEmpty(), it.string("fee") ?: "—", it.string("change") ?: "—")
        }.sortedByDescending { it.date }
        return ElectricHistory(obj.string("average"), items)
    }
    private suspend fun options(path: String): List<ElectricOption> = get(path).jsonArray.map { it.jsonObject }.map {
        ElectricOption(it.long("id") ?: throw ElectricException(), it.string("name") ?: throw ElectricException())
    }
    override suspend fun buildings() = options("buildings")
    override suspend fun floors(building: Long) = options("floors?building_id=$building")
    override suspend fun rooms(floor: Long) = options("rooms?floor_id=$floor")
    override suspend fun bind(room: Long) { session.request("campuslife/electric/binding", "PUT", buildJsonObject { put("room_id", room) }) }
    override suspend fun unbind() { session.request("campuslife/electric/binding", "DELETE") }
}
