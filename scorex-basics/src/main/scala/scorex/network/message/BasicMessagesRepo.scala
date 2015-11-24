package scorex.network.message

import java.net.{InetAddress, InetSocketAddress}
import java.util

import com.google.common.primitives.{Bytes, Ints}
import scorex.block.Block
import scorex.crypto.{EllipticCurveImpl, SigningFunctions}
import scorex.crypto.SigningFunctions._
import scorex.network.message.Message._

import scala.util.Try


object BasicMessagesRepo {

  object GetPeersSpec extends MessageSpec[Unit] {
    override val messageCode: Message.MessageCode = 1: Byte

    override def deserializeData(bytes: Array[Byte]): Try[Unit] =
      Try(require(bytes.isEmpty, "Non-empty data for GetPeers"))

    override def serializeData(data: Unit) = Array()
  }

  object GetPeersMessage extends Message(GetPeersSpec, Right(Unit))


  object PeersSpec extends MessageSpec[Seq[InetSocketAddress]] {
    private val AddressLength = 4
    private val PortLength = 4
    private val DataLength = 4

    override val messageCode: Message.MessageCode = 2: Byte

    override def deserializeData(bytes: Array[Byte]): Try[Seq[InetSocketAddress]] = Try {
      require(bytes.isEmpty, "Non-empty data for GetPeers")

      //READ LENGTH
      val lengthBytes = util.Arrays.copyOfRange(bytes, 0, DataLength)
      val length = Ints.fromByteArray(lengthBytes)

      //CHECK IF DATA MATCHES LENGTH
      if (bytes.length != DataLength + (length * (AddressLength + PortLength)))
        throw new Exception("Data does not match length")

      (0 to length - 1).map { i =>
        val position = lengthBytes.length + (i * (AddressLength + PortLength))
        val addressBytes = util.Arrays.copyOfRange(bytes, position, position + AddressLength)
        val address = InetAddress.getByAddress(addressBytes)
        val portBytes = util.Arrays.copyOfRange(bytes, position + AddressLength, position + AddressLength + PortLength)
        new InetSocketAddress(address, Ints.fromByteArray(portBytes))
      }
    }

    override def serializeData(peers: Seq[InetSocketAddress]) = {
      val length = peers.size
      val lengthBytes = Bytes.ensureCapacity(Ints.toByteArray(length), DataLength, 0)

      peers.foldLeft(lengthBytes) { case (bs, peer) =>
        Bytes.concat(bs,
          peer.getAddress.getAddress, Bytes.ensureCapacity(Ints.toByteArray(peer.getPort), 4, 0))
      }
    }
  }

  trait SignaturesSeqSpec extends MessageSpec[Seq[SigningFunctions.Signature]] {

    import scorex.crypto.EllipticCurveImpl.SignatureLength

    private val DataLength = 4

    override def deserializeData(bytes: Array[Byte]): Try[Seq[Signature]] = Try {
      val lengthBytes = bytes.take(DataLength)
      val length = Ints.fromByteArray(lengthBytes)

      //CHECK IF DATA MATCHES LENGTH
      if (bytes.length != DataLength + (length * SignatureLength))
        throw new Exception("Data does not match length")

      //CREATE HEADERS LIST
      (0 to length - 1).map { i =>
        val position = DataLength + (i * SignatureLength)
        bytes.slice(position, position + SignatureLength)
      }.toSeq
    }

    override def serializeData(signatures: Seq[Signature]): Array[Byte] = {
      val length = signatures.size
      val lengthBytes = Bytes.ensureCapacity(Ints.toByteArray(length), DataLength, 0)

      //WRITE SIGNATURES
      signatures.foldLeft(lengthBytes) { case (bs, header) => Bytes.concat(bs, header) }
    }
  }

  object GetSignaturesSpec extends SignaturesSeqSpec {
    override val messageCode: MessageCode = 20: Byte
  }

  object SignaturesSpec extends SignaturesSeqSpec {
    override val messageCode: MessageCode = 21: Byte
  }

  object GetBlockSpec extends MessageSpec[SigningFunctions.Signature] {
    override val messageCode: MessageCode = 22: Byte

    override def serializeData(signature: Signature): Array[Byte] = signature

    override def deserializeData(bytes: Array[Byte]): Try[Signature] = Try {
      require(bytes.length == EllipticCurveImpl.SignatureLength, "Data does not match length")
      bytes
    }
  }

  //todo: height removed, check the code using this message type
  object BlockMessageSpec extends MessageSpec[Block] {
    override val messageCode: MessageCode = 23: Byte

    override def serializeData(block: Block): Array[Byte] = block.bytes

    override def deserializeData(bytes: Array[Byte]): Try[Block] = Block.parse(bytes)
  }

  object ScoreMessageSpec extends MessageSpec[(Int, BigInt)] {
    override val messageCode: MessageCode = 24: Byte

    override def serializeData(heightAndScore: (Int, BigInt)): Array[Byte] = {
      val scoreBytes = heightAndScore._2.toByteArray
      val bb = java.nio.ByteBuffer.allocate(4 + scoreBytes.length)
      bb.putInt(heightAndScore._1)
      bb.put(scoreBytes)
      bb.array()
    }

    override def deserializeData(bytes: Array[Byte]): Try[(Int, BigInt)] = Try {
      val heightBytes = bytes.take(4)
      val height = Ints.fromByteArray(heightBytes)
      val score = BigInt(bytes.takeRight(bytes.length - 4))
      (height, score)
    }
  }

  val specs = Seq(GetPeersSpec, PeersSpec, GetSignaturesSpec, SignaturesSpec,
    GetBlockSpec, BlockMessageSpec, ScoreMessageSpec)
}