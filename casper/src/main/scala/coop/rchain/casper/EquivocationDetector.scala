package coop.rchain.casper

import cats.{Applicative, Monad}
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.{BlockDagRepresentation, BlockStore}
import coop.rchain.casper.EquivocationRecord.SequenceNumber
import coop.rchain.casper.Estimator.Validator
import coop.rchain.casper.protocol.{BlockMessage, Bond, Justification}
import coop.rchain.casper.util.{DoublyLinkedDag, ProtoUtil}
import coop.rchain.casper.util.ProtoUtil.{
  bonds,
  findCreatorJustificationAncestorWithSeqNum,
  toLatestMessageHashes
}
import coop.rchain.shared.{Cell, Log, LogSource}

/**
  * A summary of the neglected equivocation algorithm is as follows.
  *
  * Every equivocation has one "base equivocation block" and multiple "children equivocation blocks" where the
  * "children equivocation blocks" have a sequence number that is one greater than the "base equivocation block".
  * To detect neglected equivocations, we keep a set of "equivocation record"s. An "equivocation record" is a tuple
  * containing equivocator's ID, the sequence number of the equivocation base block and a set of block hashes of blocks
  * that point to enough evidence to slash an equivocation corresponding to the "equivocation record".
  * Each time we discover an equivocation, we add a new "equivocation record" entry to the set with the validator's ID
  * and the base equivocation block's sequence number filled in. Each time we add a block to our view,
  * we loop through our "equivocations record"s and see if the block we want to add has enough information to detect
  * the equivocation corresponding to the "equivocation record". There are three cases:
  *
  * Case 1) The block has enough information and the block contains the equivocator in its justification,
  *         we slash the creator of that block
  * Case 2) The block has enough information and the block properly has rotated out the equivocator from its
  *         justification, we update the "equivocation record" so that the set contains this block.
  * Case 3) The block doesn't have enough information and so we do nothing.
  *
  * To ascertain whether a block has enough information to detect a particular equivocation, we loop through the
  * block's justifications and accumulate a set of children equivocation blocks that are reachable from
  * the block's justifications. If at any point while looping through the block's justifications, if we come across a
  * justification block that is in the set of block hashes, we immediately ascertain the block has enough information
  * to detect the equivocation corresponding to the "equivocation record". If at any point the set of children
  * equivocation blocks becomes larger than one in size, we also immediately ascertain the block has enough information
  * to detect the equivocation corresponding to the "equivocation record".
  */
sealed trait EquivocationDiscoveryStatus
final case object EquivocationNeglected extends EquivocationDiscoveryStatus
final case object EquivocationDetected  extends EquivocationDiscoveryStatus
final case object EquivocationOblivious extends EquivocationDiscoveryStatus

// This is the sequence number of the equivocator's base block
final case class EquivocationRecord(
    equivocator: Validator,
    equivocationBaseBlockSeqNum: SequenceNumber,
    equivocationDetectedBlockHashes: Set[BlockHash]
)

object EquivocationRecord {
  type SequenceNumber = Int
}

object EquivocationDetector {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  def checkEquivocations[F[_]: Monad: Log](
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash],
      block: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Either[InvalidBlock, ValidBlock]] =
    for {
      maybeLatestMessageOfCreatorHash <- dag.latestMessageHash(block.sender)
      maybeCreatorJustification       = creatorJustificationHash(block)
      isNotEquivocation               = maybeCreatorJustification == maybeLatestMessageOfCreatorHash
      result <- if (isNotEquivocation) {
                 Applicative[F].pure(Right(Valid))
               } else if (requestedAsDependency(block, blockBufferDependencyDag)) {
                 Applicative[F].pure(Left(AdmissibleEquivocation))
               } else {
                 for {
                   sender <- PrettyPrinter.buildString(block.sender).pure[F]
                   creatorJustificationHash = PrettyPrinter.buildString(
                     maybeCreatorJustification.getOrElse(ByteString.EMPTY)
                   )
                   latestMessageOfCreator = PrettyPrinter.buildString(
                     maybeLatestMessageOfCreatorHash.getOrElse(ByteString.EMPTY)
                   )
                   _ <- Log[F].warn(
                         s"Ignorable equivocation: sender is $sender, creator justification is $creatorJustificationHash, latest message of creator is $latestMessageOfCreator"
                       )
                 } yield Left(IgnorableEquivocation)
               }
    } yield result

  private def requestedAsDependency(
      block: BlockMessage,
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash]
  ): Boolean =
    blockBufferDependencyDag.parentToChildAdjacencyList.contains(block.blockHash)

  private def creatorJustificationHash(block: BlockMessage): Option[BlockHash] =
    for {
      maybeCreatorJustification <- ProtoUtil.creatorJustification(block)
    } yield maybeCreatorJustification.latestBlockHash

  // See summary of algorithm above
  def checkNeglectedEquivocationsWithUpdate[F[_]: Monad: BlockStore](
      block: BlockMessage,
      dag: BlockDagRepresentation[F],
      genesis: BlockMessage
  )(implicit state: Cell[F, CasperState]): F[Either[InvalidBlock, ValidBlock]] =
    for {
      neglectedEquivocationDetected <- isNeglectedEquivocationDetectedWithUpdate[F](
                                        block,
                                        dag,
                                        genesis
                                      )
      status = if (neglectedEquivocationDetected) {
        Left(NeglectedEquivocation)
      } else {
        Right(Valid)
      }
    } yield status

  private def isNeglectedEquivocationDetectedWithUpdate[F[_]: Monad: BlockStore](
      block: BlockMessage,
      dag: BlockDagRepresentation[F],
      genesis: BlockMessage
  )(implicit state: Cell[F, CasperState]): F[Boolean] =
    for {
      s <- Cell[F, CasperState].read
      neglectedEquivocationDetected <- s.equivocationsTracker.toList.existsM { equivocationRecord =>
                                        updateEquivocationsTracker[F](
                                          block,
                                          dag,
                                          equivocationRecord,
                                          genesis
                                        )
                                      }
    } yield neglectedEquivocationDetected

  /**
    * If an equivocation is detected, it is added to the equivocationDetectedBlockHashes, which keeps track
    * of the block hashes that correspond to the blocks from which an equivocation can be justified.
    *
    * @return Whether a neglected equivocation was discovered.
    */
  private def updateEquivocationsTracker[F[_]: Monad: BlockStore](
      block: BlockMessage,
      dag: BlockDagRepresentation[F],
      equivocationRecord: EquivocationRecord,
      genesis: BlockMessage
  )(implicit state: Cell[F, CasperState]): F[Boolean] =
    for {
      equivocationDiscoveryStatus <- getEquivocationDiscoveryStatus[F](
                                      block,
                                      dag,
                                      equivocationRecord,
                                      genesis
                                    )
      neglectedEquivocationDetected = equivocationDiscoveryStatus match {
        case EquivocationNeglected =>
          true
        case EquivocationDetected =>
          false
        case EquivocationOblivious =>
          false
      }
      _ <- if (equivocationDiscoveryStatus == EquivocationDetected) {
            Cell[F, CasperState].modify { s =>
              val updatedEquivocationDetectedBlockHashes = equivocationRecord.equivocationDetectedBlockHashes + block.blockHash
              val newEquivocationsTracker = s.equivocationsTracker - equivocationRecord + (
                equivocationRecord
                  .copy(equivocationDetectedBlockHashes = updatedEquivocationDetectedBlockHashes)
                )
              s.copy(equivocationsTracker = newEquivocationsTracker)
            }
          } else ().pure[F]
    } yield neglectedEquivocationDetected

  private def getEquivocationDiscoveryStatus[F[_]: Monad: BlockStore](
      block: BlockMessage,
      dag: BlockDagRepresentation[F],
      equivocationRecord: EquivocationRecord,
      genesis: BlockMessage
  ): F[EquivocationDiscoveryStatus] = {
    val equivocatingValidator = equivocationRecord.equivocator
    val latestMessages        = toLatestMessageHashes(block.justifications)
    val maybeEquivocatingValidatorBond =
      bonds(block).find(_.validator == equivocatingValidator)
    maybeEquivocatingValidatorBond match {
      case Some(Bond(_, stake)) =>
        getEquivocationDiscoveryStatusForBondedValidator[F](
          dag,
          equivocationRecord,
          latestMessages,
          stake,
          genesis
        )
      case None =>
        /*
         * Since block has dropped equivocatingValidator from the bonds, it has acknowledged the equivocation.
         * The combination of Validate.transactions and Validate.bondsCache ensure that you can only drop
         * validators through transactions to the proof of stake contract.
         */
        Applicative[F].pure(EquivocationDetected)
    }
  }

  private def getEquivocationDiscoveryStatusForBondedValidator[F[_]: Monad: BlockStore](
      blockDag: BlockDagRepresentation[F],
      equivocationRecord: EquivocationRecord,
      latestMessages: Map[Validator, BlockHash],
      stake: Long,
      genesis: BlockMessage
  ): F[EquivocationDiscoveryStatus] =
    if (stake > 0L) {
      for {
        equivocationDetectable <- isEquivocationDetectable[F](
                                   blockDag,
                                   latestMessages.toSeq,
                                   equivocationRecord,
                                   Set.empty[BlockMessage],
                                   genesis
                                 )
      } yield
        if (equivocationDetectable) {
          EquivocationNeglected
        } else {
          EquivocationOblivious
        }
    } else {
      // TODO: This case is not necessary if assert(stake > 0) in the PoS contract
      Applicative[F].pure(EquivocationDetected)
    }

  private def isEquivocationDetectable[F[_]: Monad: BlockStore](
      blockDag: BlockDagRepresentation[F],
      latestMessages: Seq[(Validator, BlockHash)],
      equivocationRecord: EquivocationRecord,
      equivocationChildren: Set[BlockMessage],
      genesis: BlockMessage
  ): F[Boolean] =
    latestMessages match {
      case Nil => false.pure[F]
      case (_, justificationBlockHash) +: remainder =>
        isEquivocationDetectableAfterViewingBlock[F](
          blockDag,
          justificationBlockHash,
          equivocationRecord,
          equivocationChildren,
          remainder,
          genesis
        )
    }

  private def isEquivocationDetectableAfterViewingBlock[F[_]: Monad: BlockStore](
      blockDag: BlockDagRepresentation[F],
      justificationBlockHash: BlockHash,
      equivocationRecord: EquivocationRecord,
      equivocationChildren: Set[BlockMessage],
      remainder: Seq[(Validator, BlockHash)],
      genesis: BlockMessage
  ): F[Boolean] =
    if (equivocationRecord.equivocationDetectedBlockHashes.contains(justificationBlockHash)) {
      true.pure[F]
    } else {
      for {
        justificationBlock <- ProtoUtil.unsafeGetBlock[F](justificationBlockHash)
        equivocationDetected <- isEquivocationDetectableThroughChildren[F](
                                 blockDag,
                                 equivocationRecord,
                                 equivocationChildren,
                                 remainder,
                                 justificationBlock,
                                 genesis
                               )
      } yield equivocationDetected
    }

  private def isEquivocationDetectableThroughChildren[F[_]: Monad: BlockStore](
      blockDag: BlockDagRepresentation[F],
      equivocationRecord: EquivocationRecord,
      equivocationChildren: Set[BlockMessage],
      remainder: Seq[(Validator, BlockHash)],
      justificationBlock: BlockMessage,
      genesis: BlockMessage
  ): F[Boolean] = {
    val equivocatingValidator = equivocationRecord.equivocator
    val equivocationBaseBlockSeqNum =
      equivocationRecord.equivocationBaseBlockSeqNum
    for {
      updatedEquivocationChildren <- maybeAddEquivocationChild[F](
                                      blockDag,
                                      justificationBlock,
                                      equivocatingValidator,
                                      equivocationBaseBlockSeqNum,
                                      equivocationChildren,
                                      genesis
                                    )
      equivocationDetected <- if (updatedEquivocationChildren.size > 1) {
                               true.pure[F]
                             } else {
                               isEquivocationDetectable[F](
                                 blockDag,
                                 remainder,
                                 equivocationRecord,
                                 updatedEquivocationChildren,
                                 genesis
                               )
                             }
    } yield equivocationDetected
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw")) // TODO remove throw
  private def maybeAddEquivocationChild[F[_]: Monad: BlockStore](
      blockDag: BlockDagRepresentation[F],
      justificationBlock: BlockMessage,
      equivocatingValidator: Validator,
      equivocationBaseBlockSeqNum: SequenceNumber,
      equivocationChildren: Set[BlockMessage],
      genesis: BlockMessage
  ): F[Set[BlockMessage]] =
    // TODO: Is this a safe check? Or should I just check block hash?
    if (justificationBlock == genesis) {
      equivocationChildren.pure[F]
    } else if (justificationBlock.sender == equivocatingValidator) {
      // This is a special case as the justificationBlock might be the equivocation child
      if (justificationBlock.seqNum > equivocationBaseBlockSeqNum) {
        addEquivocationChild[F](
          blockDag,
          justificationBlock,
          equivocationBaseBlockSeqNum,
          equivocationChildren
        )
      } else {
        equivocationChildren.pure[F]
      }
    } else {
      // Latest according to the justificationBlock
      val maybeLatestEquivocatingValidatorBlockHash: Option[BlockHash] =
        toLatestMessageHashes(justificationBlock.justifications).get(equivocatingValidator)
      maybeLatestEquivocatingValidatorBlockHash match {
        case Some(blockHash) =>
          for {
            latestEquivocatingValidatorBlock <- ProtoUtil.unsafeGetBlock[F](blockHash)
            updatedEquivocationChildren <- if (latestEquivocatingValidatorBlock.seqNum > equivocationBaseBlockSeqNum) {
                                            addEquivocationChild[F](
                                              blockDag,
                                              latestEquivocatingValidatorBlock,
                                              equivocationBaseBlockSeqNum,
                                              equivocationChildren
                                            )
                                          } else {
                                            equivocationChildren.pure[F]
                                          }
          } yield updatedEquivocationChildren
        case None =>
          throw new Exception(
            "justificationBlock is missing justification pointers to equivocatingValidator even though justificationBlock isn't a part of equivocationDetectedBlockHashes for this equivocation record."
          )
      }
    }

  @SuppressWarnings(Array("org.wartremover.warts.Throw")) // TODO remove throw
  private def addEquivocationChild[F[_]: Monad: BlockStore](
      blockDag: BlockDagRepresentation[F],
      justificationBlock: BlockMessage,
      equivocationBaseBlockSeqNum: SequenceNumber,
      equivocationChildren: Set[BlockMessage]
  ): F[Set[BlockMessage]] =
    findCreatorJustificationAncestorWithSeqNum[F](
      blockDag,
      justificationBlock,
      equivocationBaseBlockSeqNum + 1
    ).flatMap {
      case Some(equivocationChildHash) =>
        for {
          equivocationChild <- ProtoUtil.unsafeGetBlock[F](equivocationChildHash)
        } yield equivocationChildren + equivocationChild
      case None =>
        throw new Exception(
          "creator justification ancestor with lower sequence number hasn't been added to the blockDAG yet."
        )
    }
}
