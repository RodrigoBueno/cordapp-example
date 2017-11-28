package com.example.contract

import com.example.state.IOUState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A implementation of a basic smart contract in Corda.
 *
 * This contract enforces rules regarding the creation of a valid [IOUState], which in turn encapsulates an [IOU].
 *
 * For a new [IOU] to be issued onto the ledger, a transaction is required which takes:
 * - Zero input states.
 * - One output state: the new [IOU].
 * - An Create() command with the public keys of both the lender and the borrower.
 *
 * All contracts must sub-class the [Contract] interface.
 */
open class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "com.example.contract.IOUContract"
    }

    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.single()
        when(command.value){
            is Commands.Create -> verifyCreate(tx, command)
            is Commands.Pay -> verifyPay(tx, command)
            else -> throw IllegalAccessException("Command not recognized.")
        }

    }
    private fun verifyPay(tx: LedgerTransaction, command: CommandWithParties<CommandData>){
        requireThat {
            //tem 1 input
            "Only one input can be consumed when paying an IOU." using (tx.inputStates.size == 1)
            //tem 1 output
            "Only one output should be created when paying an IOU." using (tx.outputs.size == 1)
            //os dois assinaram
            "Everyone should sign the transaction." using (
                        command.signers.containsAll(
                                tx.outputs
                                        .flatMap{ it.data.participants }
                                        .map { it.owningKey } ))
            val input = tx.inputsOfType<IOUState>().single()
            val output = tx.outputsOfType<IOUState>().single()
            //valor pago > 0
            "The payed value should be positive." using (output.paymentValue > 0 )
            //valor pago >= valor emprestado
            "The payed value should be equal or higher than the borrowed value." using (output.paymentValue >= input.value )
            //status entrada == Criado
            "The input status was 'Criado'" using (input.status == "Criado")
            //status saida == Pago
            "The output status should be 'Pago'." using (output.status == "Pago")
            //tem que pagar com juros
            "The interest should be payed in full." using (interestPayed(output))
        }
    }

    private fun interestPayed(state: IOUState): Boolean {
        val monthsLate = (Instant.now().epochSecond - state.dueDate.epochSecond) / 60 / 60 / 24 / 30
        if (monthsLate > 0) {
            val valueToPay = Math.floor(
                    Math.pow(
                            1 + state.interest.toDouble() / 100,
                            monthsLate.toDouble()).times(state.value))
                            .toInt()

            return valueToPay == state.paymentValue
        } else
            return state.paymentValue == state.value
    }

    private fun verifyCreate(tx: LedgerTransaction, command: CommandWithParties<CommandData>) {
        requireThat {
            // Generic constraints around the IOU transaction.
            "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val out = tx.outputsOfType<IOUState>().single()
            "The lender and the borrower cannot be the same entity." using (out.lender != out.borrower)
            "All of the participants must be signers." using (command.signers.containsAll(out.participants.map { it.owningKey }))

            // IOU-specific constraints.
            "The IOU's value must be non-negative." using (out.value > 0)
            //status criado
            "Status should be 'Criado'." using (out.status == "Criado")
            //juros >= 0
            "The interest value should be positive." using (out.interest >= 0)
            //date > ontem | removi a condição para testar com dado retroativo. Não é correto usar Instant.now()
            // para conseguir a data, a informação deveria vir de algum serviço fora do ledger, mas é suficiente para
            // representar o que está ocorrendo
//            "The due date shouldn't be in the past." using (Instant.now()
//                    .minus(1, ChronoUnit.DAYS)
//                    .isBefore(out.dueDate))
            // payment value == 0
            "The payment value shouldn't be initialized." using (out.paymentValue == 0)
        }
    }

    /**
     * This contract only implements one command, Create.
     */
    interface Commands : CommandData {
        class Create : Commands
        class Pay: Commands
    }
}
