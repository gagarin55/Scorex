package scorex.transaction

import com.google.common.primitives.{Bytes, Ints}
import org.h2.mvstore.MVStore
import play.api.libs.json.{JsArray, JsObject, Json}
import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.app.Application
import scorex.block.{Block, BlockField}
import scorex.network.message.Message
import scorex.network.{Broadcast, NetworkController, TransactionalMessagesRepo}
import scorex.settings.Settings
import scorex.transaction.SimpleTransactionModule.StoredInBlock
import scorex.transaction.state.database.UnconfirmedTransactionsDatabase
import scorex.transaction.state.database.blockchain.{StoredState, StoredBlockTree, StoredBlockchain}
import scorex.transaction.state.wallet.Payment
import scorex.utils._
import scorex.wallet.Wallet

import scala.concurrent.duration._
import scala.util.Try

case class TransactionsBlockField(override val value: Seq[Transaction])
  extends BlockField[Seq[Transaction]] {

  import SimpleTransactionModule.MaxTransactionsPerBlock

  override val name = "transactions"

  override lazy val json: JsObject = Json.obj(name -> JsArray(value.map(_.json)))

  override lazy val bytes: Array[Byte] = {
    val txCount = value.size.ensuring(_ <= MaxTransactionsPerBlock).toByte
    value.foldLeft(Array(txCount)) { case (bs, tx) =>
      val txBytes = tx.bytes
      bs ++ Bytes.ensureCapacity(Ints.toByteArray(txBytes.length), 4, 0) ++ txBytes
    }
  }
}


class SimpleTransactionModule(implicit val settings: TransactionSettings with Settings, application: Application)
  extends TransactionModule[StoredInBlock] with ScorexLogging {

  import SimpleTransactionModule._

  val consensusModule = application.consensusModule
  val networkController = application.networkController

  val TransactionSizeLength = 4
  val InitialBalance = 60000000000L

  private val instance = this

  override val utxStorage: UnconfirmedTransactionsStorage = new UnconfirmedTransactionsDatabase(settings)

  override val blockStorage = new BlockStorage {

    val db = settings.dataDirOpt match {
      case Some(dataFolder) => new MVStore.Builder().fileName(dataFolder + s"/blockchain.dat").compress().open()
      case None => new MVStore.Builder().open()
    }

    override val MaxRollback: Int = settings.MaxRollback

    override val history: History = settings.history match {
      case s: String if s.equalsIgnoreCase("blockchain") =>
        new StoredBlockchain(db)(consensusModule, instance)
      case s: String if s.equalsIgnoreCase("blocktree") =>
        new StoredBlockTree(settings.dataDirOpt, MaxRollback)(consensusModule, instance)
      case s =>
        log.error(s"Unknown history storage: $s. Use StoredBlockchain...")
        new StoredBlockchain(db)(consensusModule, instance)
    }

    override val state = new StoredState(db)

  }

  /**
    * In Lagonaki, transaction-related data is just sequence of transactions. No Merkle-tree root of txs / state etc
    *
    * @param bytes - serialized sequence of transaction
    * @return
    */
  override def parseBytes(bytes: Array[Byte]): Try[TransactionsBlockField] = Try {
    bytes.isEmpty match {
      case true => TransactionsBlockField(Seq())
      case false =>
        val txData = bytes.tail
        val txCount = bytes.head // so 255 txs max
        formBlockData((1 to txCount).foldLeft((0: Int, Seq[LagonakiTransaction]())) { case ((pos, txs), _) =>
          val transactionLengthBytes = txData.slice(pos, pos + TransactionSizeLength)
          val transactionLength = Ints.fromByteArray(transactionLengthBytes)
          val transactionBytes = txData.slice(pos + TransactionSizeLength, pos + TransactionSizeLength + transactionLength)
          val transaction = LagonakiTransaction.parseBytes(transactionBytes).get

          (pos + TransactionSizeLength + transactionLength, txs :+ transaction)
        }._2)
    }
  }

  override def formBlockData(transactions: StoredInBlock): TransactionsBlockField = TransactionsBlockField(transactions)

  //TODO asInstanceOf
  override def transactions(block: Block): StoredInBlock =
    block.transactionDataField.asInstanceOf[TransactionsBlockField].value

  override def packUnconfirmed(): StoredInBlock = {
    clearIncorrectTransactions()
    blockStorage.state.validate(utxStorage.all().sortBy(-_.fee).take(MaxTransactionsPerBlock))
  }

  //todo: check: clear unconfirmed txs on receiving a block
  override def clearFromUnconfirmed(data: StoredInBlock): Unit = {
    data.foreach(tx => utxStorage.getBySignature(tx.signature) match {
      case Some(unconfirmedTx) => utxStorage.remove(unconfirmedTx)
      case None =>
    })

    clearIncorrectTransactions()
  }

  //Romove too old or invalid transactions from  UnconfirmedTransactionsPool
  def clearIncorrectTransactions(): Unit = {
    val lastBlockTs = blockStorage.history.lastBlock.timestampField.value

    val txs = utxStorage.all()
    val notTooOld = txs.filter { tx =>
      if ((lastBlockTs - tx.timestamp).millis > MaxTimeForUnconfirmed) utxStorage.remove(tx)
      (lastBlockTs - tx.timestamp).millis <= MaxTimeForUnconfirmed
    }

    notTooOld.diff(blockStorage.state.validate(txs)).foreach(tx => utxStorage.remove(tx))
  }

  override def onNewOffchainTransaction(transaction: Transaction): Unit =
    if (utxStorage.putIfNew(transaction)) {
      val spec = TransactionalMessagesRepo.TransactionMessageSpec
      val ntwMsg = Message(spec, Right(transaction), None)
      networkController ! NetworkController.SendToNetwork(ntwMsg, Broadcast)
    }

  def createPayment(payment: Payment, wallet: Wallet): Option[PaymentTransaction] = {
    wallet.privateKeyAccount(payment.sender).map { sender =>
      createPayment(sender, new Account(payment.recipient), payment.amount, payment.fee)
    }
  }

  def createPayment(sender: PrivateKeyAccount, recipient: Account, amount: Long, fee: Long): PaymentTransaction = {
    val time = NTP.correctedTime()
    val sig = PaymentTransaction.generateSignature(sender, recipient, amount, fee, time, Array.empty)
    val payment = new PaymentTransaction(sender, recipient, amount, fee, time, Array.empty, sig)
    if (blockStorage.state.isValid(payment)) onNewOffchainTransaction(payment)
    payment
  }

  override def genesisData: BlockField[StoredInBlock] = {
    val ipoMembers = List(
      "3Mb4mR4taeYS3wci78SntztFwLoaS6Wbg81",
      "3MbWTyn6Tg7zL6XbdN8TLcFMfhWX77hKcmc",
      "3Mn3UAtrpGY3cwiqLYf973q29oDR2LpnMYv"
    )

    val timestamp = 0L
    val totalBalance = InitialBalance

    val txs = ipoMembers.map { addr =>
      val recipient = new Account(addr)
      GenesisTransaction(recipient, totalBalance / ipoMembers.length, timestamp)
    }

    TransactionsBlockField(txs)
  }

  override def isValid(block: Block): Boolean = {
    val lastBlockTs = blockStorage.history.lastBlock.timestampField.value
    lazy val txsAreNew = block.transactions.forall(tx => (lastBlockTs - tx.timestamp).millis <= MaxTxAndBlockDiff)
    lazy val blockIsValid = blockStorage.state.isValid(block.transactions, blockStorage.history.heightOf(block))
    txsAreNew && blockIsValid
  }

}

object SimpleTransactionModule {
  type StoredInBlock = Seq[Transaction]

  val MaxTimeForUnconfirmed = 90.minutes
  val MaxTxAndBlockDiff = 2.hour
  val MaxTransactionsPerBlock = 100
}
