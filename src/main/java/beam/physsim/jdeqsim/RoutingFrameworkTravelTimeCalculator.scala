package beam.physsim.jdeqsim
import java.io.File
import java.nio.charset.Charset
import java.util
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors}

import beam.agentsim.events.PathTraversalEvent
import beam.physsim.routingTool._
import beam.sim.BeamServices
import com.google.common.io.Files
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Coordinate
import org.apache.commons.lang.time.StopWatch
import org.matsim.api.core.v01.network.Link
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.JavaConverters._
import scala.collection.convert.ImplicitConversionsToScala._
import scala.collection.mutable
import scala.concurrent.ExecutionContext

class RoutingFrameworkTravelTimeCalculator(
  private val beamServices: BeamServices
) extends LazyLogging {

  private val numOfThreads: Int =
    if (Runtime.getRuntime.availableProcessors() <= 2) 1 else Runtime.getRuntime.availableProcessors() - 2
  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    numOfThreads,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("routing-framework-worker-%d").build()
  )
  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)

  def getLink2TravelTimes(
    pathTraversalEvents: util.Collection[PathTraversalEvent],
    iterationEndsEvent: IterationEndsEvent,
    links: util.Collection[_ <: Link],
    maxHour: Int
  ): java.util.Map[String, Array[Double]] = {
    val iterationNumber: Int = iterationEndsEvent.getIteration
    val routingToolDirectory: String = iterationEndsEvent.getServices.getControlerIO.getOutputFilename("routing-tool")
    val startTime: Long = System.currentTimeMillis
    val routingToolWrapper: RoutingToolWrapper = new RoutingToolWrapperImpl(beamServices, routingToolDirectory)
    logger.info("Finished creation of graph {}", System.currentTimeMillis - startTime)

    val id2Link = links.toStream.map(x => x.getId.toString.toInt -> x).toMap
    val graph: RoutingToolGraph = RoutingToolsGraphReaderImpl.read(routingToolWrapper.generateGraph())
    val coordinateToRTVertexId: Map[Coordinate, Long] =
      graph.vertices.map(x => x.coordinate -> x.id).toMap

    val osmInfoHolder: OsmInfoHolder = new OsmInfoHolder(beamServices)
    val hour2Events: Map[Int, List[PathTraversalEvent]] = pathTraversalEvents.toStream
      .map(x => x.departureTime / 3600 -> x)
      .groupBy(_._1)
      .mapValues(_.map(_._2).toList)

    val hour2Way2TravelTimes: Map[Int, Map[Long, Double]] = hour2Events.toList
      .sortBy(_._1)
      .map {
        case (hour, events) => {
          val stopWatch: StopWatch = new StopWatch
          stopWatch.start()
          val ods: List[(Long, Long)] = events
            .filter { x =>
              if (x.linkIds.isEmpty) {
                logger.info("Path traversal event doesn't have any related links")
                false
              } else true
            }
            .map(event => linkWayId(id2Link(event.linkIds.head)) -> linkWayId(id2Link(event.linkIds.last)))
            //skip current event if way id is not present
            .filter(x => x._1 != -1 && x._2 != -1)
            .map {
              case (firstWayId, secondWayId) =>
                val firstLinkCoordinates: Seq[Coordinate] = osmInfoHolder.getCoordinatesForWayId(firstWayId)
                val origin: Coordinate = firstLinkCoordinates.head
                var destination: Coordinate = null
                if (firstWayId == secondWayId) {
                  destination = firstLinkCoordinates.last
                } else {
                  val secondLinkCoordinates: Seq[Coordinate] = osmInfoHolder.getCoordinatesForWayId(secondWayId)
                  destination = secondLinkCoordinates.last
                }
                val firstId: Long = coordinateToRTVertexId
                  .getOrElse(origin, getRoutingToolVertexId(coordinateToRTVertexId, origin))
                val secondId: Long = coordinateToRTVertexId
                  .getOrElse(destination, getRoutingToolVertexId(coordinateToRTVertexId, destination))

                (firstId, secondId)
            }

          logger.warn("Generated {} ods, for hour {} in {}", ods.size, hour, stopWatch.getTime)
          stopWatch.reset()
          stopWatch.start()
          routingToolWrapper.generateOd(iterationNumber, hour, ods)
          logger.info("Running for hour {}", hour)
          val assignResult: (File, File, File) = routingToolWrapper.assignTraffic(iterationNumber, hour)
          logger.info("Assigned traffic in {}", stopWatch.getTime)
          var wayId2TravelTime: Map[Long, Double] = null
          wayId2TravelTime = Files
            .readLines(assignResult._1, Charset.defaultCharset)
            .toStream
            .drop(2)
            .map((x: String) => x.split(","))
            // picking only result of 10th iteration
            .filter(x => x(0) == "10")
            // way id into bpr
            .map(x => x(4).toLong -> x(5).toDouble / 10.0)
            .groupBy(_._1)
            .mapValues(x => x.map(_._2).sum / x.size)
          (hour, wayId2TravelTime)
        }
      }
      .toMap

    val travelTimeMap: mutable.Map[String, Array[Double]] = new mutable.HashMap[String, Array[Double]]
    val totalNumberOfLinks: Int = links.size
    val linksFailedToResolve: AtomicInteger = new AtomicInteger(0)

    val stopWatch: StopWatch = new StopWatch
    stopWatch.start()

    links.toStream
      .filter(_.getAttributes.getAttribute("origid") == null)
      .foreach(x => {
        linksFailedToResolve.incrementAndGet
        val travelTimes: Array[Double] = new Array[Double](maxHour)
        util.Arrays.fill(travelTimes, x.getLength / x.getFreespeed)
        travelTimeMap.put(x.getId.toString, travelTimes)
      })

    links.toStream
      .filter(_.getAttributes.getAttribute("origid") != null)
      .groupBy(linkWayId)
      .foreach {
        case (wayId, linksInWay) =>
          linksInWay.foreach { link =>
            var atLeastOneHour: Boolean = false

            val travelTimeByHour = (0 until maxHour).map { hour =>
              hour2Way2TravelTimes.get(hour).flatMap(_.get(wayId)) match {
                case Some(travelTime) =>
                  atLeastOneHour = true
                  travelTime / linksInWay.size

                case None =>
                  link.getLength / link.getFreespeed
              }
            }.toArray

            if (!atLeastOneHour) linksFailedToResolve.incrementAndGet
            travelTimeMap.put(link.getId.toString, travelTimeByHour)
          }
      }

    logger.info("Total links: {}, failed to assign travel time: {}", totalNumberOfLinks, linksFailedToResolve.get)

    logger.info("Created travel times in {}", System.currentTimeMillis - startTime)

    travelTimeMap.asJava
  }

  private def linkWayId(link: Link): Long = {
    val origid: Any = link.getAttributes.getAttribute("origid")
    if (origid == null) -1 else origid.toString.toLong
  }

  private def getRoutingToolVertexId(coordinateToRTVertexId: Map[Coordinate, Long], origin: Coordinate): Long = {
    coordinateToRTVertexId.minBy(x => x._1.distance(origin))._2
  }

}
