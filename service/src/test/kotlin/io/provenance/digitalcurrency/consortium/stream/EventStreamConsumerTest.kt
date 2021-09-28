package io.provenance.digitalcurrency.consortium.stream

import com.google.protobuf.ByteString
import io.mockk.every
import io.mockk.mockkStatic
import io.provenance.attribute.v1.Attribute
import io.provenance.digitalcurrency.consortium.BaseIntegrationTest
import io.provenance.digitalcurrency.consortium.DEFAULT_AMOUNT
import io.provenance.digitalcurrency.consortium.TEST_ADDRESS
import io.provenance.digitalcurrency.consortium.TEST_MEMBER_ADDRESS
import io.provenance.digitalcurrency.consortium.config.BankClientProperties
import io.provenance.digitalcurrency.consortium.config.EventStreamProperties
import io.provenance.digitalcurrency.consortium.config.ProvenanceProperties
import io.provenance.digitalcurrency.consortium.config.ServiceProperties
import io.provenance.digitalcurrency.consortium.domain.CoinMovementRecord
import io.provenance.digitalcurrency.consortium.domain.CoinMovementTable
import io.provenance.digitalcurrency.consortium.domain.MT
import io.provenance.digitalcurrency.consortium.domain.MTT
import io.provenance.digitalcurrency.consortium.domain.MarkerTransferRecord
import io.provenance.digitalcurrency.consortium.domain.MarkerTransferStatus
import io.provenance.digitalcurrency.consortium.domain.MigrationRecord
import io.provenance.digitalcurrency.consortium.domain.TST
import io.provenance.digitalcurrency.consortium.domain.TxStatus
import io.provenance.digitalcurrency.consortium.domain.TxStatusRecord
import io.provenance.digitalcurrency.consortium.domain.TxType
import io.provenance.digitalcurrency.consortium.extension.toByteArray
import io.provenance.digitalcurrency.consortium.frameworks.toOutput
import io.provenance.digitalcurrency.consortium.getBurnEvent
import io.provenance.digitalcurrency.consortium.getDefaultTransactionResponse
import io.provenance.digitalcurrency.consortium.getErrorTransactionResponse
import io.provenance.digitalcurrency.consortium.getMarkerTransferEvent
import io.provenance.digitalcurrency.consortium.getMigrationEvent
import io.provenance.digitalcurrency.consortium.getMintEvent
import io.provenance.digitalcurrency.consortium.getTransferEvent
import io.provenance.digitalcurrency.consortium.pbclient.RpcClient
import io.provenance.digitalcurrency.consortium.pbclient.api.rpc.BlockId
import io.provenance.digitalcurrency.consortium.pbclient.api.rpc.BlockResponse
import io.provenance.digitalcurrency.consortium.pbclient.api.rpc.PartSetHeader
import io.provenance.digitalcurrency.consortium.pbclient.fetchBlock
import io.provenance.digitalcurrency.consortium.randomTxHash
import io.provenance.digitalcurrency.consortium.service.PbcService
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.MockBean
import java.time.OffsetDateTime
import java.util.UUID

class EventStreamConsumerTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var eventStreamProperties: EventStreamProperties

    @Autowired
    private lateinit var bankClientProperties: BankClientProperties

    @Autowired
    private lateinit var provenanceProperties: ProvenanceProperties

    @Autowired
    private lateinit var serviceProperties: ServiceProperties

    @MockBean
    lateinit var eventStreamFactory: EventStreamFactory

    @Autowired
    lateinit var pbcServiceMock: PbcService

    @MockBean
    private lateinit var rpcClientMock: RpcClient

    private lateinit var eventStreamConsumer: EventStreamConsumer

    @BeforeEach
    fun beforeEach() {
        reset(eventStreamFactory)
        reset(pbcServiceMock)
        reset(rpcClientMock)

        whenever(pbcServiceMock.managerAddress).thenReturn(TEST_MEMBER_ADDRESS)
    }

    @BeforeAll
    fun beforeAll() {
        eventStreamConsumer = EventStreamConsumer(
            eventStreamFactory,
            pbcServiceMock,
            rpcClientMock,
            bankClientProperties,
            eventStreamProperties,
            serviceProperties,
            provenanceProperties,
        )
    }

    @Nested
    inner class CoinMovementEvents {
        private val mint = getMintEvent(
            dccDenom = serviceProperties.dccDenom,
            bankDenom = bankClientProperties.denom
        )

        private val burn = getTransferEvent(
            toAddress = TEST_MEMBER_ADDRESS,
            denom = serviceProperties.dccDenom
        )

        private val transfer = getMarkerTransferEvent(
            toAddress = TEST_MEMBER_ADDRESS,
            denom = bankClientProperties.denom
        )

        @Test
        fun `coinMovement - events without bank parties are ignored`() {
            val blockTime = OffsetDateTime.now()
            val blockResponse = BlockResponse(
                block = Block(
                    header = BlockHeader(0, blockTime.toString()),
                    data = BlockData(emptyList()),
                ),
                blockId = BlockId("", PartSetHeader(0, ""))
            )

            whenever(pbcServiceMock.getAttributeByTagName(any(), eq(bankClientProperties.kycTagName)))
                .thenReturn(null)

            mockkStatic(RpcClient::fetchBlock)
            every { rpcClientMock.fetchBlock(0) } returns blockResponse

            eventStreamConsumer.handleCoinMovementEvents(
                blockHeight = blockResponse.block.header.height,
                mints = listOf(mint),
                burns = listOf(burn),
                transfers = listOf(transfer),
            )

            Assertions.assertEquals(0, transaction { CoinMovementRecord.all().count() })
        }

        @Test
        fun `coinMovement - mints, burns, and transfers for bank parties are persisted`() {
            val blockTime = OffsetDateTime.now()
            val blockResponse = BlockResponse(
                block = Block(
                    header = BlockHeader(0, blockTime.toString()),
                    data = BlockData(emptyList()),
                ),
                blockId = BlockId("", PartSetHeader(0, ""))
            )

            whenever(pbcServiceMock.getAttributeByTagName(any(), eq(bankClientProperties.kycTagName)))
                .thenReturn(
                    Attribute.newBuilder()
                        .setName(bankClientProperties.kycTagName)
                        .setValue(ByteString.copyFrom(UUID.randomUUID().toByteArray()))
                        .build()
                )

            mockkStatic(RpcClient::fetchBlock)
            every { rpcClientMock.fetchBlock(0) } returns blockResponse

            eventStreamConsumer.handleCoinMovementEvents(
                blockHeight = blockResponse.block.header.height,
                mints = listOf(mint),
                burns = listOf(burn),
                transfers = listOf(transfer),
            )

            Assertions.assertEquals(3, transaction { CoinMovementRecord.all().count() })
        }

        @Test
        fun `coinMovement - block reentry is ignored`() {
            val blockTime = OffsetDateTime.now()
            val blockResponse = BlockResponse(
                block = Block(
                    header = BlockHeader(0, blockTime.toString()),
                    data = BlockData(emptyList()),
                ),
                blockId = BlockId("", PartSetHeader(0, ""))
            )

            whenever(pbcServiceMock.getAttributeByTagName(any(), eq(bankClientProperties.kycTagName)))
                .thenReturn(
                    Attribute.newBuilder()
                        .setName(bankClientProperties.kycTagName)
                        .setValue(ByteString.copyFrom(UUID.randomUUID().toByteArray()))
                        .build()
                )

            mockkStatic(RpcClient::fetchBlock)
            every { rpcClientMock.fetchBlock(0) } returns blockResponse

            eventStreamConsumer.handleCoinMovementEvents(
                blockHeight = blockResponse.block.header.height,
                mints = listOf(mint),
                burns = listOf(burn),
                transfers = listOf(transfer),
            )

            Assertions.assertEquals(3, transaction { CoinMovementRecord.all().count() })

            eventStreamConsumer.handleCoinMovementEvents(
                blockHeight = blockResponse.block.header.height,
                mints = listOf(mint),
                burns = listOf(burn),
                transfers = listOf(transfer),
            )
            eventStreamConsumer.handleCoinMovementEvents(
                blockHeight = blockResponse.block.header.height,
                mints = listOf(mint),
                burns = listOf(burn),
                transfers = listOf(transfer),
            )
            eventStreamConsumer.handleCoinMovementEvents(
                blockHeight = blockResponse.block.header.height,
                mints = listOf(mint),
                burns = listOf(burn),
                transfers = listOf(transfer),
            )

            Assertions.assertEquals(3, transaction { CoinMovementRecord.all().count() })
        }

        @Test
        fun `coinMovement - batched messages in one tx are persisted`() {
            val blockTime = OffsetDateTime.now()
            val blockResponse = BlockResponse(
                block = Block(
                    header = BlockHeader(0, blockTime.toString()),
                    data = BlockData(emptyList()),
                ),
                blockId = BlockId("", PartSetHeader(0, ""))
            )

            whenever(pbcServiceMock.getAttributeByTagName(any(), eq(bankClientProperties.kycTagName)))
                .thenReturn(
                    Attribute.newBuilder()
                        .setName(bankClientProperties.kycTagName)
                        .setValue(ByteString.copyFrom(UUID.randomUUID().toByteArray()))
                        .build()
                )

            mockkStatic(RpcClient::fetchBlock)
            every { rpcClientMock.fetchBlock(0) } returns blockResponse

            eventStreamConsumer.handleCoinMovementEvents(
                blockHeight = blockResponse.block.header.height,
                mints = listOf(
                    getMintEvent(
                        txHash = "tx1",
                        dccDenom = serviceProperties.dccDenom,
                        bankDenom = bankClientProperties.denom
                    )
                ),
                burns = listOf(
                    getTransferEvent(
                        txHash = "tx1",
                        toAddress = TEST_MEMBER_ADDRESS,
                        denom = serviceProperties.dccDenom
                    )
                ),
                transfers = listOf(
                    getMarkerTransferEvent(
                        txHash = "tx1",
                        toAddress = TEST_MEMBER_ADDRESS,
                        denom = bankClientProperties.denom
                    )
                ),
            )

            Assertions.assertEquals(3, transaction { CoinMovementRecord.all().count() })
            Assertions.assertEquals(
                listOf("tx1-0", "tx1-1", "tx1-2"),
                transaction { CoinMovementRecord.all().toList() }.map { it.txHash() }.sorted(),
            )
        }

        @Test
        fun `coinMovement - check v1 vs v2 output`() {
            insertCoinMovement(txHash = "abc-0", denom = serviceProperties.dccDenom)

            transaction {
                CoinMovementTable.update { it[legacyTxHash] = "abc" }
            }

            insertCoinMovement(txHash = "abc-0", denom = serviceProperties.dccDenom)
            insertCoinMovement(txHash = "xyz-0", denom = serviceProperties.dccDenom)
            insertCoinMovement(txHash = "xyz-0", denom = serviceProperties.dccDenom)
            insertCoinMovement(txHash = "xyz-1", denom = serviceProperties.dccDenom)
            insertCoinMovement(txHash = "xyz-2", denom = serviceProperties.dccDenom)

            Assertions.assertEquals(4, transaction { CoinMovementRecord.all().count() })
            Assertions.assertArrayEquals(
                listOf("abc-0", "xyz-0", "xyz-1", "xyz-2").toTypedArray(),
                transaction { CoinMovementRecord.all().toList() }.map { it._txHashV2.value }.sorted().toTypedArray(),
            )
            Assertions.assertArrayEquals(
                listOf("abc", "xyz-0", "xyz-1", "xyz-2").toTypedArray(),
                transaction { CoinMovementRecord.all().toList() }.toOutput().transactions.map { it.txId }.sorted()
                    .toTypedArray(),
            )
        }
    }

    @Nested
    inner class MigrationEvents {
        @Test
        fun `event is not a migrate, tx hash does not exist, does not persist, does not process`() {
            val txHash = randomTxHash()
            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                burns = listOf(
                    Burn(
                        contractAddress = TEST_ADDRESS,
                        denom = "dummyDenom",
                        amount = DEFAULT_AMOUNT.toString(),
                        memberId = TEST_ADDRESS,
                        height = 1L,
                        txHash = txHash
                    )
                ),
                mints = listOf(),
                redemptions = listOf(),
                transfers = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock, never()).getTransaction(any())

            transaction {
                Assertions.assertEquals(MigrationRecord.find { MT.txHash eq txHash }.count(), 0)
            }
        }

        @Test
        fun `migration hash exists, does not persist, does not process`() {
            val txHash = randomTxHash()
            insertMigration(txHash)
            val migrationEvent = getMigrationEvent(txHash)
            val txResponseSuccess = getDefaultTransactionResponse(txHash)

            whenever(pbcServiceMock.getTransaction(any())).thenReturn(txResponseSuccess)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(),
                mints = listOf(),
                burns = listOf(),
                redemptions = listOf(),
                migrations = listOf(migrationEvent)
            )

            verify(pbcServiceMock, never()).getTransaction(any())

            transaction {
                Assertions.assertEquals(MigrationRecord.find { MT.txHash eq txHash }.count(), 1)
            }
        }

        @Test
        fun `valid migration persists, processes`() {
            val txHash = randomTxHash()
            val migrationEvent = getMigrationEvent(txHash)

            val txResponseSuccess = getDefaultTransactionResponse(txHash)
            whenever(pbcServiceMock.getTransaction(any())).thenReturn(txResponseSuccess)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(),
                mints = listOf(),
                burns = listOf(),
                redemptions = listOf(),
                migrations = listOf(migrationEvent)
            )

            verify(pbcServiceMock).getTransaction(txHash)

            transaction {
                Assertions.assertEquals(MigrationRecord.find { (MT.txHash eq txHash) and MT.sent.isNull() }.count(), 1)
            }
        }

        @Test
        fun `tx failed, don't persist migration`() {
            val txHash = randomTxHash()
            val migration = getMigrationEvent(txHash)
            val txResponseFail = getErrorTransactionResponse(txHash)

            whenever(pbcServiceMock.getTransaction(any())).thenReturn(txResponseFail)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(),
                mints = listOf(),
                burns = listOf(),
                redemptions = listOf(),
                migrations = listOf(migration)
            )

            verify(pbcServiceMock).getTransaction(txHash)
            transaction {
                Assertions.assertEquals(MigrationRecord.find { MT.txHash eq txHash }.count(), 0)
            }
        }
    }

    @Nested
    inner class MarkerTransferEvents {
        @Test
        fun `event is not a transfer, tx hash, does not exist, does not persist, does not process`() {
            val txHash = randomTxHash()
            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                burns = listOf(
                    Burn(
                        contractAddress = TEST_ADDRESS,
                        denom = "dummyDenom",
                        amount = DEFAULT_AMOUNT.toString(),
                        memberId = TEST_ADDRESS,
                        height = 1L,
                        txHash = txHash
                    )
                ),
                mints = listOf(),
                redemptions = listOf(),
                transfers = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock, never()).getTransaction(any())

            transaction {
                Assertions.assertEquals(TxStatusRecord.find { TST.txHash eq txHash }.count(), 0)
                Assertions.assertEquals(MarkerTransferRecord.find { MTT.txHash eq txHash }.count(), 0)
            }
        }

        @Test
        fun `transfer hash exists, does not persist, does not process`() {
            val txHash = randomTxHash()
            val transfer = insertMarkerTransfer(txHash, denom = serviceProperties.dccDenom)
            insertTxStatus(transfer.id.value, txHash, TxType.TRANSFER_CONTRACT, TxStatus.COMPLETE)
            val transferEvent = getTransferEvent(txHash, denom = serviceProperties.dccDenom)
            val txResponseSuccess = getDefaultTransactionResponse(txHash)

            whenever(pbcServiceMock.getTransaction(any())).thenReturn(txResponseSuccess)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(transferEvent),
                mints = listOf(),
                burns = listOf(),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock, never()).getTransaction(any())

            transaction {
                Assertions.assertEquals(TxStatusRecord.find { TST.txHash eq txHash }.count(), 1)
                Assertions.assertEquals(MarkerTransferRecord.find { MTT.txHash eq txHash }.count(), 1)
            }
        }

        @Test
        fun `recipient is not the member bank instance, does not persist, does not process`() {
            val txHash = randomTxHash()
            val transfer = getTransferEvent(txHash, "invalidrecipient", denom = serviceProperties.dccDenom)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(transfer),
                mints = listOf(),
                burns = listOf(),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock, never()).getTransaction(any())

            transaction {
                Assertions.assertEquals(TxStatusRecord.find { TST.txHash eq txHash }.count(), 0)
                Assertions.assertEquals(MarkerTransferRecord.find { MTT.txHash eq txHash }.count(), 0)
            }
        }

        @Test
        fun `recipient is the member bank instance, denom is not valid, does not persist, does not process`() {
            val txHash = randomTxHash()
            val transfer = getTransferEvent(txHash, denom = "invaliddenom")

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(transfer),
                mints = listOf(),
                burns = listOf(),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock, never()).getTransaction(any())

            transaction {
                Assertions.assertEquals(TxStatusRecord.find { TST.txHash eq txHash }.count(), 0)
                Assertions.assertEquals(MarkerTransferRecord.find { MTT.txHash eq txHash }.count(), 0)
            }
        }

        @Test
        fun `valid transfer persists, processes`() {
            val txHash = randomTxHash()
            val transfer = getTransferEvent(txHash, toAddress = TEST_MEMBER_ADDRESS, denom = serviceProperties.dccDenom)

            val txResponseSuccess = getDefaultTransactionResponse(txHash)
            whenever(pbcServiceMock.getTransaction(any())).thenReturn(txResponseSuccess)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(transfer),
                mints = listOf(),
                burns = listOf(),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock).getTransaction(txHash)

            transaction {
                Assertions.assertEquals(TxStatusRecord.find { TST.txHash eq txHash }.count(), 0)
                Assertions.assertEquals(
                    MarkerTransferRecord.find {
                        (MTT.txHash eq txHash) and (MTT.status eq MarkerTransferStatus.INSERTED)
                    }.count(),
                    1
                )
            }
        }

        @Test
        fun `tx failed, don't persist transfer`() {
            val txHash = randomTxHash()
            val transfer =
                getTransferEvent(txHash, toAddress = TEST_MEMBER_ADDRESS, denom = serviceProperties.dccDenom)
            val txResponseFail = getErrorTransactionResponse(txHash)

            whenever(pbcServiceMock.getTransaction(any())).thenReturn(txResponseFail)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(transfer),
                mints = listOf(),
                burns = listOf(),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock).getTransaction(txHash)
            transaction {
                Assertions.assertEquals(TxStatusRecord.find { TST.txHash eq txHash }.count(), 0)
                Assertions.assertEquals(MarkerTransferRecord.find { MTT.txHash eq txHash }.count(), 0)
            }
        }
    }

    @Nested
    inner class AllOtherEvents {
        @Test
        fun `tx status exists and is complete already`() {
            val txHash = randomTxHash()
            insertTxStatus(UUID.randomUUID(), txHash, TxType.BURN_CONTRACT, TxStatus.COMPLETE)
            val burn = getBurnEvent(txHash, serviceProperties.dccDenom)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(),
                mints = listOf(),
                burns = listOf(burn),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock, never()).getTransaction(txHash)

            transaction {
                val newStatus = TxStatusRecord.find { TST.txHash eq txHash }.firstOrNull()
                Assertions.assertNotNull(newStatus)
                Assertions.assertEquals(newStatus!!.status, TxStatus.COMPLETE)
            }
        }

        @Test
        fun `tx status exists as error, should not update status`() {
            val txHash = randomTxHash()
            insertTxStatus(UUID.randomUUID(), txHash, TxType.BURN_CONTRACT, TxStatus.ERROR)
            val burn = getBurnEvent(txHash, serviceProperties.dccDenom)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(),
                mints = listOf(),
                burns = listOf(burn),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock, never()).getTransaction(txHash)

            transaction {
                val newStatus = TxStatusRecord.find { TST.txHash eq txHash }.firstOrNull()
                Assertions.assertNotNull(newStatus)
                Assertions.assertEquals(newStatus!!.status, TxStatus.ERROR)
            }
        }

        @Test
        fun `tx status exists, blockchain response does not exist, should update to error`() {
            val txHash = randomTxHash()
            insertTxStatus(UUID.randomUUID(), txHash, TxType.BURN_CONTRACT, TxStatus.PENDING)
            val burn = getBurnEvent(txHash, serviceProperties.dccDenom)

            whenever(pbcServiceMock.getTransaction(txHash)).thenReturn(null)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(),
                mints = listOf(),
                burns = listOf(burn),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock).getTransaction(txHash)

            transaction {
                val newStatus = TxStatusRecord.find { TST.txHash eq txHash }.firstOrNull()
                Assertions.assertNotNull(newStatus)
                Assertions.assertEquals(newStatus!!.status, TxStatus.ERROR)
            }
        }

        @Test
        fun `tx status exists, blockchain response is error, should update to error`() {
            val txHash = randomTxHash()
            insertTxStatus(UUID.randomUUID(), txHash, TxType.BURN_CONTRACT, TxStatus.PENDING)
            val burn = getBurnEvent(txHash, serviceProperties.dccDenom)
            val txResponse = getErrorTransactionResponse(txHash)

            whenever(pbcServiceMock.getTransaction(txHash)).thenReturn(txResponse)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(),
                mints = listOf(),
                burns = listOf(burn),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock).getTransaction(txHash)

            transaction {
                val newStatus = TxStatusRecord.find { TST.txHash eq txHash }.firstOrNull()
                Assertions.assertNotNull(newStatus)
                Assertions.assertEquals(newStatus!!.status, TxStatus.ERROR)
            }
        }

        @Test
        fun `tx status exists, blockchain response is not error, should update to complete`() {
            val txHash = randomTxHash()
            insertTxStatus(UUID.randomUUID(), txHash, TxType.BURN_CONTRACT, TxStatus.PENDING)
            val burn = getBurnEvent(txHash, serviceProperties.dccDenom)
            val txResponse = getDefaultTransactionResponse(txHash)

            whenever(pbcServiceMock.getTransaction(txHash)).thenReturn(txResponse)

            eventStreamConsumer.handleEvents(
                blockHeight = 50,
                transfers = listOf(),
                mints = listOf(),
                burns = listOf(burn),
                redemptions = listOf(),
                migrations = listOf()
            )

            verify(pbcServiceMock).getTransaction(txHash)

            transaction {
                val newStatus = TxStatusRecord.find { TST.txHash eq txHash }.firstOrNull()
                Assertions.assertNotNull(newStatus)
                Assertions.assertEquals(newStatus!!.status, TxStatus.COMPLETE)
            }
        }
    }
}
