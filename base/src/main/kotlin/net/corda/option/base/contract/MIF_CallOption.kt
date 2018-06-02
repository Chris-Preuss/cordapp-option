// Import Corda's core library
import net.corda.core.contracts
import net.corda.core.transactions
import net.corda.core.identity
import net.corda.core.node
import net.corda.core.flows

// Import Corda's finance library
import net.corda.finance.contracts.*
import net.corda.finance.utils.*

//import net.corda.option.base.SpotPrice
//import net.corda.option.base.Volatility
//import net.corda.option.base.state.OptionState
//import java.time.Duration

//Create a new class MIF_CallOption
class MIF_CallOption : Contract {
    companion object {
        const val OPTION_CONTRACT_ID = "net.corda.option.base.contract.MIF_CallOption"
    }

    override fun verify(tx: LedgerTransaction) {
        // We should only ever receive one command at a time
        val command = tx.commands.requireSingleCommand<Commands>()
	// take the time stamp of the transaction
	val timeWindow: TimeWindow? = tx.timeWindow
	when (command.value) {
            is Commands.Issue -> {
                requireThat {

		    // Constraints on the "shape" of the transaction.
                    // If cash states have to be combined/split into change, we may not have exactly one cash
                    // input/output.
                    "A MIF_CallOption output is created" using (tx.outputsOfType<MIF_CallOption>().size == 1)
                    "No other states are consumed" using (tx.inputs.size == cashInputs.size)
                    "Only one state is created (option)" using (tx.outputs.size == (cashOutputs.size + 1))
                    "Option issuances must be timestamped" using (tx.timeWindow != null)         

                    // Constraints on the contents of the transaction's components.
                    val option = tx.outputsOfType<MIF_CallOption>().single()
                    val timeWindow = tx.timeWindow!!
                    val premium = OptionState.calculatePremium(option, oracleCmd.value.volatility, oracleCmd.value.riskfree, 
                                                               oracleCmd.value.spotprice.value)
                        
                    // Constraints on the input set by the option seller
                    "The strike price must be non-negative" using (option.strikePrice.quantity > 0)
                    "The expiry date is not in the past" using (timeWindow.untilTime!! < option.expiryDate)
                    "The option is not exercised" using (!option.exercised)
                    "The exercised-on date is null" using (option.exercisedOnDate == null)
                    "The spot price at issuance matches the oracle's data" using
                            (option.spotPriceAtIssuance == oracleCmd.value.spotPrice.value)

                    // Constraints on the required signers.
                    "The issue command requires the issuer's signature" using (option.issuer.owningKey in command.signers)
                    
                    // We can't check for the presence of the oracle as a required signer, as their identity is not
                    // included in the transaction. We check for the oracle as a required signer in the flow instead
                }
            }

            is Commands.Move -> {
                requireThat {
                    // Constraints on the "shape" of the transaction.
                    val cashInputs = tx.inputsOfType<Cash.State>()
                    val cashOutputs = tx.outputsOfType<Cash.State>()
                    "Cash.State inputs are consumed" using (cashInputs.isNotEmpty())
                    "Cash.State outputs are created" using (cashOutputs.isNotEmpty())
                    "An OptionState input is consumed" using (tx.inputsOfType<OptionState>().size == 1)
                    "An OptionState output is created" using (tx.outputsOfType<OptionState>().size == 1)
                    "No other states are consumed" using (tx.inputs.size == (cashInputs.size + 1))
                    "No other states are created" using (tx.outputs.size == (cashOutputs.size + 1))
                    "Option issuances must be timestamped" using (tx.timeWindow != null)
                    tx.commands.requireSingleCommand<Cash.Commands.Move>()
                    val oracleCmd = tx.commands.requireSingleCommand<MIF_CallOption.OracleCommand>()

                    // Constraints on the contents of the transaction's components.
                    val inputOption = tx.inputsOfType<OptionState>().single()
                    val outputOption = tx.outputsOfType<OptionState>().single()
                    val cashTransferredToOldOwner = tx.outputsOfType<Cash.State>().sumCashBy(inputOption.owner)
                    val timeWindow = tx.timeWindow!!
                    val premium = OptionState.calculatePremium(outputOption, oracleCmd.value.volatility)
                    "The owner has changed" using (inputOption.owner != outputOption.owner)
                    "The spot price at purchase matches the oracle's data" using
                            (outputOption.spotPriceAtPurchase == oracleCmd.value.spotPrice.value)
                    "The options are otherwise identical" using
                            (inputOption == outputOption.copy(owner = inputOption.owner, spotPriceAtPurchase = inputOption.spotPriceAtPurchase))
                    "The amount of cash transferred matches the premium" using
                            (premium == cashTransferredToOldOwner.withoutIssuer())
                    "The time-window is no longer than 120 seconds" using
                            (Duration.between(timeWindow.fromTime, timeWindow.untilTime) <= Duration.ofSeconds(120))

                    // Constraints on the required signers.
                    "The transfer command requires the old owner's signature" using (inputOption.owner.owningKey in command.signers)
                    "The transfer command requires the new owner's signature" using (outputOption.owner.owningKey in command.signers)
                }
            }

            is Commands.Exercise -> {
                requireThat {
                    // Constraints on the "shape" of the transaction.
                    "An OptionState is consumed" using (tx.inputsOfType<OptionState>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "No other states are created" using (tx.outputs.size == 1)
                    "Exercises of options must be timestamped" using (tx.timeWindow?.fromTime != null)

                    // Constraints on the contents of the transaction's components.
                    val input = tx.inputsOfType<OptionState>().single()
                    val output = tx.outputsOfType<OptionState>().single()
                    val received = tx.outputs.map { it.data }.sumCashBy(input.owner)
                    val time = timeWindow?.fromTime ?: throw IllegalArgumentException("Redemptions must be timestamped")
		    
	            "the option must have matured" using (time >= input.maturityDate)
                    "the option holder recieve the underlying stock" using (received == input.underlyingStock)
                    "the option must be destroyed" using outputs.isEmpty()
                    "The input option is not yet exercised" using (!input.exercise)
                    "The time-window is no longer than 120 seconds" using

                    // Constraints on the required signers.
                    "The exercise command requires the owner's signature" using (input.owner.owningKey in command.signers)
                }
            }
		
	    is Commands.Redeem -> {
                    val input = tx.inputsOfType<OptionState>().single()
		    val output = tx.outputsOfType<OptionState>().single()       
                    val time = timeWindow?.fromTime ?: throw IllegalArgumentException("Redemptions must be timestamped")
		 requireThat {
                    "the option must have matured" using (time >= input.maturityDate)
                    "the option must be destroyed" using outputs.isEmpty()
                    "the transaction is signed by the owner of the option" using (input.owner.owningKey in command.signers)
                }
            }
		
            else -> throw IllegalArgumentException("Unknown command.")
        }
    }

    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Move : TypeOnlyCommandData(), Commands
        class Exercise : TypeOnlyCommandData(), Commands
        class Redeem : TypeOnlyCommandData(), Commands
    }

    class OracleCommand(val spotPrice: SpotPrice, val volatility: Volatility) : CommandData
}

//Option State

data class OptionState(
        val strikePrice: Amount<Currency>,
        val expiryDate: Instant,
        val underlyingStock: String,
        val issuer: Party,
        val owner: Party,
        val optionType: OptionType,
        var spotPriceAtPurchase: Amount<Currency> = Amount(0, strikePrice.token),
        val exercised: Boolean = false,
        override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {

    companion object {

        fun calculatePremium(optionState: OptionState, volatility: Volatility): Amount<Currency> {
            val blackScholes = BlackScholes(optionState.spotPriceAtPurchase.quantity.toDouble(), optionState.strikePrice.quantity.toDouble(), RISK_FREE_RATE, 100.toDouble(), volatility.value)
            val value = if (optionState.optionType == OptionType.CALL) {
                blackScholes.BSCall().toLong() * 100
            } else {
                blackScholes.BSPut().toLong() * 100
            }
            return Amount(value, optionState.strikePrice.token)
        }
    }

    override val participants get() = listOf(owner, issuer)

    override fun toString() = "${this.optionType.name} option on ${this.underlyingStock} at strike ${this.strikePrice} expiring on ${this.expiryDate}"
}
