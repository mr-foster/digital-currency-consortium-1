package io.provenance.digitalcurrency.consortium.frameworks

import io.provenance.digitalcurrency.consortium.annotation.NotTest
import io.provenance.digitalcurrency.consortium.api.DepositFiatRequest
import io.provenance.digitalcurrency.consortium.bankclient.BankClient
import io.provenance.digitalcurrency.consortium.config.CoroutineProperties
import io.provenance.digitalcurrency.consortium.config.logger
import io.provenance.digitalcurrency.consortium.config.withMdc
import io.provenance.digitalcurrency.consortium.domain.AddressRegistrationRecord
import io.provenance.digitalcurrency.consortium.domain.MarkerTransferRecord
import io.provenance.digitalcurrency.consortium.domain.TxStatus
import io.provenance.digitalcurrency.consortium.extension.mdc
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

class MarkerTransferDirective(
    override val id: UUID
) : Directive()

class MarkerTransferOutcome(
    override val id: UUID
) : Outcome()

@Component
@NotTest
class MarkerTransferQueue(
    private val bankClient: BankClient,
    coroutineProperties: CoroutineProperties,
) :
    ActorModel<MarkerTransferDirective, MarkerTransferOutcome> {
    private val log = logger()

    @EventListener(DataSourceConnectedEvent::class)
    fun startProcessing() {
        log.info("start marker transfer queueing framework")
        start()
    }

    override val numWorkers: Int = coroutineProperties.numWorkers
    override val pollingDelayMillis: Long = coroutineProperties.pollingDelayMs

    override suspend fun loadMessages(): List<MarkerTransferDirective> =
        transaction {
            MarkerTransferRecord.findPending().map { MarkerTransferDirective(it.id.value) }
        }

    override fun processMessage(message: MarkerTransferDirective): MarkerTransferOutcome {
        transaction {
            MarkerTransferRecord.findPendingForUpdate(message.id).first().let { transfer ->
                withMdc(*transfer.mdc()) {
                    // TODO - handle if active address no longer exists due to deregistration
                    val registration = AddressRegistrationRecord.findActiveByAddress(transfer.fromAddress)
                    checkNotNull(registration) { "Address ${transfer.fromAddress} is not registered" }

                    // Let bank know of dcc deposit to member bank.
                    try {
                        bankClient.depositFiat(
                            DepositFiatRequest(
                                uuid = transfer.id.value,
                                bankAccountUUID = registration.bankAccountUuid,
                                amount = transfer.fiatAmount
                            )
                        )

                        MarkerTransferRecord.updateStatus(transfer.id.value, TxStatus.ACTION_COMPLETE)
                    } catch (e: Exception) {
                        log.error("sending fiat deposit request to bank failed; it will retry.", e)
                    }
                }
            }
        }
        return MarkerTransferOutcome(message.id)
    }

    override fun onMessageSuccess(result: MarkerTransferOutcome) {
        log.info("marker transfer queue successfully processed tx request uuid ${result.id}.")
    }

    override fun onMessageFailure(message: MarkerTransferDirective, e: Exception) {
        log.error("marker transfer queue got error for tx request uuid ${message.id}", e)
    }
}
