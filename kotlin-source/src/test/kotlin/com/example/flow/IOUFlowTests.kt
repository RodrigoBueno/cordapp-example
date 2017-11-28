package com.example.flow

import com.example.schema.IOUSchemaV1
import com.example.state.IOUState
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.node.services.queryBy
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.testing.chooseIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.setCordappPackages
import net.corda.testing.unsetCordappPackages
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IOUFlowTests {
    lateinit var network: MockNetwork
    lateinit var a: StartedNode<MockNetwork.MockNode>
    lateinit var b: StartedNode<MockNetwork.MockNode>

    @Before
    fun setup() {
        setCordappPackages("com.example.contract")
        network = MockNetwork()
        val nodes = network.createSomeNodes(2)
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        nodes.partyNodes.forEach { it.internals.registerCustomSchemas(setOf(IOUSchemaV1)) }
        nodes.partyNodes.forEach { it.registerInitiatedFlow(PayFlow.Acceptor::class.java) }
        nodes.partyNodes.forEach { it.registerInitiatedFlow(ExampleFlow.Acceptor::class.java) }
        network.runNetwork()
    }

    @After
    fun tearDown() {
        unsetCordappPackages()
        network.stopNodes()
    }

    @Test
    fun `flow rejects invalid IOUs`() {
        val flow = ExampleFlow.Initiator(-1, b.info.chooseIdentity())
        val future = a.services.startFlow(flow).resultFuture
        network.runNetwork()

        // The IOUContract specifies that IOUs cannot have negative values.
        assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by the initiator`() {
        val flow = ExampleFlow.Initiator(1, b.info.chooseIdentity())
        val future = a.services.startFlow(flow).resultFuture
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(b.info.chooseIdentity().owningKey)
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by the acceptor`() {
        val flow = ExampleFlow.Initiator(1, b.info.chooseIdentity())
        val future = a.services.startFlow(flow).resultFuture
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(a.info.chooseIdentity().owningKey)
    }

    @Test
    fun `flow records a transaction in both parties' transaction storages`() {
        val flow = ExampleFlow.Initiator(1, b.info.chooseIdentity())
        val future = a.services.startFlow(flow).resultFuture
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both transaction storages.
        for (node in listOf(a, b)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `recorded transaction has no inputs and a single output, the input IOU`() {
        val iouValue = 1
        val flow = ExampleFlow.Initiator(iouValue, b.info.chooseIdentity())
        val future = a.services.startFlow(flow).resultFuture
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both vaults.
        for (node in listOf(a, b)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            val txOutputs = recordedTx!!.tx.outputs
            assert(txOutputs.size == 1)

            val recordedState = txOutputs[0].data as IOUState
            assertEquals(recordedState.value, iouValue)
            assertEquals(recordedState.lender, a.info.chooseIdentity())
            assertEquals(recordedState.borrower, b.info.chooseIdentity())
        }
    }

    @Test
    fun `flow records the correct IOU in both parties' vaults`() {
        val iouValue = 1
        val flow = ExampleFlow.Initiator(1, b.info.chooseIdentity())
        val future = a.services.startFlow(flow).resultFuture
        network.runNetwork()
        future.getOrThrow()

        // We check the recorded IOU in both vaults.
        for (node in listOf(a, b)) {
            node.database.transaction {
                val ious = node.services.vaultService.queryBy<IOUState>().states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(recordedState.value, iouValue)
                assertEquals(recordedState.lender, a.info.chooseIdentity())
                assertEquals(recordedState.borrower, b.info.chooseIdentity())
            }
        }
    }

    @Test
    fun `should not pay payed IOU`() {
        val iouValue = 1
        val dueDate = Instant.now().plusSeconds(100)

        val iouId = createIOU(dueDate, iouValue)

        payIOU(iouId, iouValue, dueDate, 1)

        val payFlow = PayFlow.Initiator(1, iouId)
        val payFuture = b.services.startFlow(payFlow).resultFuture

        network.runNetwork()

        assertFailsWith<TransactionVerificationException.ContractRejection> { payFuture.getOrThrow() }

    }

    @Test
    fun `should not reject IOU if payment consider the interest`() {
        val iouValue = 100
        val dueDate = Instant.now().minus(5 * 30, ChronoUnit.DAYS)

        val iouId = createIOU(dueDate, iouValue)

        val payFlow = PayFlow.Initiator(161, iouId)
        val payFuture = b.services.startFlow(payFlow).resultFuture

        network.runNetwork()
        payFuture.getOrThrow()

    }

    private fun payIOU(iouId: UUID, iouValue: Int, dueDate: Instant?, payedValue: Int) {
        val payFlow = PayFlow.Initiator(payedValue, iouId)
        val payFuture = b.services.startFlow(payFlow).resultFuture

        network.runNetwork()
        payFuture.getOrThrow()

        for (node in listOf(a, b)) {
            node.database.transaction {
                val ious = node.services.vaultService.queryBy<IOUState>().states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(recordedState.value, iouValue)
                assertEquals(recordedState.lender, a.info.chooseIdentity())
                assertEquals(recordedState.borrower, b.info.chooseIdentity())
                assertEquals(recordedState.dueDate, dueDate)
                assertEquals(recordedState.paymentValue, payedValue)
                assertEquals(recordedState.status, "Pago")
            }
        }
    }

    private fun createIOU(dueDate: Instant, iouValue: Int): UUID {
        val flow = ExampleFlow.Initiator(iouValue, b.info.chooseIdentity(), dueDate = dueDate, interest = 10)
        val future = a.services.startFlow(flow).resultFuture
        network.runNetwork()
        future.getOrThrow()

        // We check the recorded IOU in both vaults.
        for (node in listOf(a, b)) {
            node.database.transaction {
                val ious = node.services.vaultService.queryBy<IOUState>().states
                assertEquals(1, ious.size)
                val recordedState = ious.single().state.data
                assertEquals(recordedState.value, iouValue)
                assertEquals(recordedState.lender, a.info.chooseIdentity())
                assertEquals(recordedState.borrower, b.info.chooseIdentity())
                assertEquals(recordedState.dueDate, dueDate)
                assertEquals(recordedState.paymentValue, 0)
                assertEquals(recordedState.status, "Criado")
            }
        }

        val iouId = a.database.transaction {
            a.services.vaultService.queryBy<IOUState>().states.single().state.data.linearId.id
        }
        return iouId
    }

}
