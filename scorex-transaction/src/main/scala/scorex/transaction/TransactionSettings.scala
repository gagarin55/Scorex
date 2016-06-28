package scorex.transaction

import play.api.libs.json.JsObject

trait TransactionSettings {
  val settingsJSON: JsObject

  private val DefaultHistory = "blockchain"
  lazy val history = (settingsJSON \ "history").asOpt[String].getOrElse(DefaultHistory)

  private val DefaultMaxRollback = 100
  lazy val MaxRollback = (settingsJSON \ "maxRollback").asOpt[Int].getOrElse(DefaultMaxRollback)

  lazy val minimumTxFee = (settingsJSON \ "minimumTxFee").asOpt[Int].getOrElse(DefaultMinimumTxFee)
  private val DefaultMinimumTxFee = 1

  lazy val unconfirmedTxPoolSize = 1000
}
