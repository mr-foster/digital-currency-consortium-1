package io.provenance.digitalcurrency.consortium.domain

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import java.time.OffsetDateTime
import java.util.UUID

typealias CRBT = CoinRedeemBurnTable

object CoinRedeemBurnTable : BaseCoinRequestTable(name = "coin_redeem_burn")

open class CoinRedeemBurnEntityClass : BaseCoinRequestEntityClass<CRBT, CoinRedeemBurnRecord>(CRBT) {

    fun updateStatus(uuid: UUID, newStatus: TxStatus) =
        findById(uuid)!!.let {
            it.status = newStatus
            it.updated = OffsetDateTime.now()
        }

    fun findTxnCompleted() = find { CRBT.status eq TxStatus.TXN_COMPLETE }

    fun findTxnCompletedForUpdate(uuid: UUID) = find { (CRBT.id eq uuid) and (CRBT.status eq TxStatus.TXN_COMPLETE) }.forUpdate()
}

class CoinRedeemBurnRecord(uuid: EntityID<UUID>) : BaseCoinRequestRecord(CRBT, uuid) {
    companion object : CoinRedeemBurnEntityClass()
}
