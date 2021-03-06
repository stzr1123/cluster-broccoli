package de.frosner.broccoli.nomad

import java.net.ConnectException

import cats.data.EitherT
import cats.syntax.either._
import cats.instances.future._
import com.netaporter.uri.Uri
import com.netaporter.uri.dsl._
import de.frosner.broccoli.nomad.models._
import play.api.http.HeaderNames._
import play.api.http.MimeTypes.{JSON, TEXT}
import play.api.http.Status._
import play.api.libs.ws.{WSClient, WSResponse}
import shapeless.tag
import shapeless.tag.@@
import squants.Quantity
import squants.information.{Bytes, Information}

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * A client for the HTTP API of Nomad.
  */
class NomadHttpClient(
    baseUri: Uri,
    client: WSClient
)(implicit override val executionContext: ExecutionContext)
    extends NomadClient {

  private class NodeClient(nodeV1Uri: Uri) extends NomadNodeClient {

    private val v1Client: Uri = nodeV1Uri / "client"

    /**
      * Get resource usage statistics of an allocation.
      *
      * If parsing allocation stats fails we treat it as a not-found allocation.
        This is done because Nomad might return something malformed while the allocation is still being built.
      *
      * @param allocationId The ID of the allocation
      * @return The resource statistics of the allocation with the given ID.
      */
    override def getAllocationStats(allocationId: @@[String, Allocation.Id]): NomadT[AllocationStats] =
      lift(client.url(v1Client / "allocation" / allocationId / "stats").withHeaders(ACCEPT -> JSON).get())
        .subflatMap { response =>
          val maybeAllocationStats = for {
            responseJson <- Try(response.json).toOption
            allocationStats <- responseJson.validate[AllocationStats].asOpt
          } yield allocationStats
          maybeAllocationStats.toRight(NomadError.NotFound)
        }

    /**
      * Get the log of a task on an allocation.
      *
      * @param allocationId The ID of the allocation
      * @param taskName     The name of the task
      * @param stream       The kind of log to fetch
      * @return The task log
      */
    override def getTaskLog(
        allocationId: @@[String, Allocation.Id],
        taskName: @@[String, Task.Name],
        stream: LogStreamKind,
        offset: Option[@@[Quantity[Information], TaskLog.Offset]]
    ): NomadT[TaskLog] =
      lift(
        client
          .url(v1Client / "fs" / "logs" / allocationId)
          .withQueryString(
            Seq("task" -> taskName,
                "type" -> stream.entryName,
                // Request the plain text log without framing and do not follow the log
                "plain" -> "true",
                "follow" -> "false") ++
              offset
                .map { size =>
                  Seq(
                    // If an offset is given, at the corresponding parameters to the query
                    "origin" -> "end",
                    // Round to nearest integer get the (double) value out, and convert it to an integral string
                    "offset" -> (size in Bytes).rint.value.toInt.toString
                  )
                }
                .getOrElse(Seq.empty): _*
          )
          .withHeaders(ACCEPT -> TEXT)
          .get())
        .ensureOr(fromClientHTTPError)(_.status == OK)
        .map(log => TaskLog(stream, tag[TaskLog.Contents](log.body)))

    /**
      * Create a Nomad error from a HTTP error response from the client API, ie, /v1/client/….
      *
      * As of Nomad 0.5.x this API does not return any semantic error codes; all errors map to status code 500, with some
      * information about the error in the plain text request body.
      *
      * This method tries and guess the cause of the error and turn the 500 into a reasonable NomadError.
      *
      * If you're not requesting the client API, use fromHTTPError!.
      *
      * @param response The response of the client API
      * @return A best guess NomadError corresponding to the response.
      */
    private def fromClientHTTPError(response: WSResponse): NomadError = response.status match {
      case INTERNAL_SERVER_ERROR if response.body.trim().startsWith("unknown allocation ID") => NomadError.NotFound
      case _                                                                                 => fromHTTPError(response)
    }
  }

  /**
    * The base URI for the Nomad V1 HTTP API
    */
  private val v1: Uri = baseUri / "v1"

  /**
    * Get a job.
    *
    * @param jobId The ID of the job
    * @return The job
    */
  override def getJob(jobId: @@[String, Job.Id]): NomadT[Job] =
    for {
      response <- lift(client.url(v1 / "job" / jobId).withHeaders(ACCEPT -> JSON).get())
        .ensureOr(fromHTTPError)(_.status == OK)
    } yield response.json.as[Job]

  /**
    * Get allocations for a job.
    *
    * @param jobId The ID of the job
    * @return The list of allocations for the job
    */
  override def getAllocationsForJob(jobId: String @@ Job.Id): NomadT[WithId[immutable.Seq[Allocation]]] =
    for {
      response <- lift(client.url(v1 / "job" / jobId / "allocations").withHeaders(ACCEPT -> JSON).get())
        .ensureOr(fromHTTPError)(_.status == OK)
    } yield WithId(jobId, response.json.as[immutable.Seq[Allocation]])

  /**
    * Get an allocation.
    *
    * @param id The alloction to query
    * @return The allocation or an error
    */
  override def getAllocation(id: String @@ Allocation.Id): NomadT[Allocation] =
    lift(client.url(v1 / "allocation" / id).withHeaders(ACCEPT -> JSON).get())
      .ensureOr(fromHTTPError)(_.status == OK)
      .map(_.json.as[Allocation])

  override def getNode(id: @@[String, Node.Id]): NomadT[Node] =
    lift(client.url(v1 / "node" / id).withHeaders(ACCEPT -> JSON).get())
      .ensureOr(fromHTTPError)(_.status == OK)
      .map(_.json.as[Node])

  /**
    * Get a client to access a specific Nomad node.
    *
    * @param node The node to access
    * @return A client to access the given node.
    */
  override def nodeClient(node: Node): NomadNodeClient = {
    val nodeAddress = parseNodeAddress(node.httpAddress)
    new NodeClient(v1.copy(host = nodeAddress.host, port = nodeAddress.port))
  }

  /**
    * Create a Nomad error from a HTTP error response.
    *
    * @param response The error response
    * @return The corresponding NomadError
    */
  private def fromHTTPError(response: WSResponse): NomadError = response.status match {
    case NOT_FOUND => NomadError.NotFound
    // For unexpected errors throw an exception instead to trigger logging
    case _ => throw new UnexpectedNomadHttpApiError(response)
  }

  /**
    * Parse the HTTP address of a node into a partial URI
    *
    * @param httpAddress The HTTP address
    * @return The partial URI
    */
  private def parseNodeAddress(httpAddress: String @@ Node.HttpAddress): Uri = httpAddress.split(":", 2) match {
    case Array(host, port, _*) => Uri().withHost(host).withPort(port.toInt)
    case Array(host)           => Uri().withHost(host)
    case _                     => Uri()
  }

  /**
    * Lift a WSResponse into the Nomad Either transformer.
    *
    * Transforms some exceptions into proper Nomad errors.
    *
    * @param response The response
    * @return The response in the nomad monad, with some exceptions caught.
    */
  private def lift(response: Future[WSResponse]): NomadT[WSResponse] =
    EitherT(response.map(_.asRight).recover {
      case _: ConnectException => NomadError.Unreachable.asLeft
    })
}
