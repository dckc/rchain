package coop.rchain.rnodeclient

import cats.effect.Effect
import io.circe.Json
import org.http4s.HttpService
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

import coop.rchain.node.model.diagnostics.{DiagnosticsGrpc}
import coop.rchain.node.model.repl.{EvalRequest, ReplGrpc, ReplResponse}
import coop.rchain.casper.protocol.{BlockMessage, BlockQuery, DeployServiceGrpc, DeployString}

import com.google.protobuf.empty.Empty
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

class HelloWorldService[F[_]: Effect] extends Http4sDsl[F] {

  def makeChannel(): ManagedChannel = {
    // ISSUE: when to build the channel? not every time, surely
    val host = "0.0.0.0" // ISSUE: config?
    val port = 50000     // ISSUE: config?
    return ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build
  }

  val service: HttpService[F] = {
    HttpService[F] {
      case GET -> Root => {
        val blockingStub = DiagnosticsGrpc.blockingStub(makeChannel())
        val peers        = blockingStub.listPeers(Empty()).peers

        Ok(Json.obj("peers" -> Json.fromInt(peers.size)))
      }
      case GET -> Root / "hello" / name =>
        Ok(Json.obj("message" -> Json.fromString(s"Hello, ${name}")))
      case GET -> Root / "eval" / program =>
        val blockingStub = ReplGrpc.blockingStub(makeChannel())
        val output       = blockingStub.eval(EvalRequest(program)).output

        Ok(Json.obj("output" -> Json.fromString(output)))
    }
  }
}
