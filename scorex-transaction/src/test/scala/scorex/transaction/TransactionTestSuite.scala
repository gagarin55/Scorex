package scorex.transaction

import org.scalatest.Suites
import scorex.transaction.state.UnconfirmedTransactionsDatabaseSpecification

class TransactionTestSuite extends Suites(
  new TransactionSpecification,
  new StoredStateUnitTests,
  new RowSpecification,
  new GenesisTransactionSpecification,
  new UnconfirmedTransactionsDatabaseSpecification
)
