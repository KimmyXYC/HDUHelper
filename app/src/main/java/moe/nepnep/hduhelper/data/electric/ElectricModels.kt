package moe.nepnep.hduhelper.data.electric

import kotlinx.serialization.Serializable

@Serializable
class NeoTokens(val account: String, val accessToken: String, val refreshToken: String,
    val accessExpireAt: Long = 0, val refreshExpireAt: Long = 0) {
    override fun toString() = "NeoTokens([redacted])"
}
interface NeoStore {
    fun load(): NeoTokens?
    fun save(session: NeoTokens)
    fun clear()
}
data class ElectricOption(val id: Long, val name: String)
data class ElectricBinding(val roomId: Long, val buildingName: String, val floorName: String, val roomName: String) {
    val label get() = listOf(buildingName, floorName, roomName).filter { it.isNotBlank() }.joinToString(" ")
}
data class ElectricBalance(val amount: String?, val time: Long?)
data class ElectricHistoryItem(val date: String, val fee: String, val change: String)
data class ElectricHistory(val average: String?, val items: List<ElectricHistoryItem>)
class ElectricException(val loginRequired: Boolean = false, val verification: Boolean = false,
    message: String = "电费查询失败，请重试") : Exception(message)
interface ElectricSource {
    suspend fun binding(): ElectricBinding?
    suspend fun balance(): ElectricBalance
    suspend fun history(): ElectricHistory
    suspend fun buildings(): List<ElectricOption>
    suspend fun floors(building: Long): List<ElectricOption>
    suspend fun rooms(floor: Long): List<ElectricOption>
    suspend fun bind(room: Long)
    suspend fun unbind()
}
