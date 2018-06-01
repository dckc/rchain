package coop.rchain.rnodeclient

import cats.effect.Effect
import io.circe.Json
import org.http4s.HttpService
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

import coop.rchain.node.model.diagnostics.DiagnosticsGrpc
import coop.rchain.casper.protocol.{BlockMessage, BlockQuery, DeployServiceGrpc, DeployString}

import com.google.protobuf.empty.Empty
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

class HelloWorldService[F[_]: Effect] extends Http4sDsl[F] {

  val service: HttpService[F] = {
    HttpService[F] {
      case GET -> Root => {
        val host = "0.0.0.0" // ISSUE: config?
        val port = 50000     // ISSUE: config?
        // ISSUE: when to build the channel? not every time, surely
        val channel      = ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build
        val blockingStub = DiagnosticsGrpc.blockingStub(channel)
        val peers        = blockingStub.listPeers(Empty()).peers

        Ok(Json.obj("peers" -> Json.fromInt(peers.size)))
      }
      case GET -> Root / "hello" / name =>
        Ok(Json.obj("message" -> Json.fromString(s"Hello, ${name}")))
    }
  }
}
