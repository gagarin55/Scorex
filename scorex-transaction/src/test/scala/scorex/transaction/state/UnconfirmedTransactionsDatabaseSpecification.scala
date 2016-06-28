package scorex.transaction.state

import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import scorex.account.PrivateKeyAccount
import scorex.transaction.{PaymentTransaction, TransactionSettings}
import scorex.transaction.state.database.UnconfirmedTransactionsDatabase


object TestTransactionSettings extends TransactionSettings {
  import play.api.libs.json.Json

  val settingsJSON = Json.obj()
  override lazy val minimumTxFee = 3
}

class UnconfirmedTransactionsDatabaseSpecification extends PropSpec
  with PropertyChecks with Matchers {

  property("if fee low minimumFee tx shouldn't be in utx database") {
    val utxDb = new UnconfirmedTransactionsDatabase(TestTransactionSettings)
    forAll { (senderSeed: Array[Byte],
              recipientSeed: Array[Byte],
              attachment: Array[Byte],
              time: Long,
              amount: Long,
              fee: Long) =>
      val sender = new PrivateKeyAccount(senderSeed)
      val recipient = new PrivateKeyAccount(recipientSeed)

      val tx = PaymentTransaction(sender, recipient, amount, fee, time, attachment)
      if (tx.fee < TestTransactionSettings.minimumTxFee)
        utxDb.putIfNew(tx) shouldEqual false
    }
  }
}
