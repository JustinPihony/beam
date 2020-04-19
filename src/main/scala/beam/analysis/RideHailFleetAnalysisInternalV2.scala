package beam.analysis

import java.util.concurrent.atomic.DoubleAdder

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{ParkingEvent, PathTraversalEvent, RefuelSessionEvent}
import beam.router.Modes.BeamMode
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import beam.utils.ProfilingUtils
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class RideHailFleetAnalysisInternalV2(
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  writeIteration: (String, SimulationTime, Double, Map[String, String], Boolean) => Unit
) {
  private val metersInMile: Double = 1609.34
  private val resolutionInSeconds = 60
  private val lastHour = 25
  private val secondsInHour = 60 * 60
  private val timeBins = 0 until lastHour * secondsInHour by resolutionInSeconds
  private var processedHour = 0

  private val states = List(
    "driving-full",
    "driving-reposition",
    "driving-topickup",
    "driving-tocharger",
    "queuing",
    "charging",
    "idle",
    "offline",
    "parked"
  )

  private val keys = states.zipWithIndex.toMap

  private val rideHailEvCav = mutable.Map[String, ArrayBuffer[Event]]()
  private val ridehailEvNonCav = mutable.Map[String, ArrayBuffer[Event]]()
  private val rideHailNonEvCav = mutable.Map[String, ArrayBuffer[Event]]()
  private val rideHailNonEvNonCav = mutable.Map[String, ArrayBuffer[Event]]()

  def processStats(event: Event): Unit = {
    event match {
      case refuelSessionEvent: RefuelSessionEvent =>
        if (refuelSessionEvent.energyInJoules > 0.0) {
          val vehicle = refuelSessionEvent.vehId.toString
          if (vehicle.contains("rideHail")) {
            if (rideHailEvCav.contains(vehicle)) {
              collectEvent(
                rideHailEvCav,
                refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
                vehicle,
                refuelSessionEvent.getTime
              )
            } else if (ridehailEvNonCav.contains(vehicle)) {
              collectEvent(
                ridehailEvNonCav,
                refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
                vehicle,
                refuelSessionEvent.getTime
              )
            } else if (rideHailNonEvCav.contains(vehicle)) {
              collectEvent(
                rideHailNonEvCav,
                refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
                vehicle,
                refuelSessionEvent.getTime
              )
            } else if (rideHailNonEvNonCav.contains(vehicle)) {
              collectEvent(
                rideHailNonEvNonCav,
                refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
                vehicle,
                refuelSessionEvent.getTime
              )
            }
          }
        }

      case pathTraversalEvent: PathTraversalEvent =>
        if (pathTraversalEvent.mode == BeamMode.CAR) {
          val vehicleTypeId = Id.create(pathTraversalEvent.vehicleType, classOf[BeamVehicleType])
          val isCAV = vehicleTypes(vehicleTypeId).automationLevel > 3
          val vehicle = pathTraversalEvent.vehicleId.toString
          val rideHail = vehicle.contains("rideHail")
          val ev = pathTraversalEvent.primaryFuelType == "Electricity"
          if (rideHail) {
            if (ev && isCAV) {
              collectEvent(
                rideHailEvCav,
                pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
                vehicle,
                pathTraversalEvent.time
              )
            } else if (ev && !isCAV) {
              collectEvent(
                ridehailEvNonCav,
                pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
                vehicle,
                pathTraversalEvent.time
              )
            } else if (!ev && isCAV) {
              collectEvent(
                rideHailNonEvCav,
                pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
                vehicle,
                pathTraversalEvent.time
              )
            } else if (!ev && !isCAV) {
              collectEvent(
                rideHailNonEvNonCav,
                pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
                vehicle,
                pathTraversalEvent.time
              )
            }
          }
        }

      case parkEvent: ParkingEvent =>
        val vehicle = parkEvent.vehicleId.toString

        if (rideHailEvCav.contains(vehicle)) {
          collectEvent(rideHailEvCav, parkEvent, vehicle, parkEvent.time)
        } else if (ridehailEvNonCav.contains(vehicle)) {
          collectEvent(ridehailEvNonCav, parkEvent, vehicle, parkEvent.time)
        } else if (rideHailNonEvCav.contains(vehicle)) {
          collectEvent(rideHailNonEvCav, parkEvent, vehicle, parkEvent.time)
        } else if (rideHailNonEvNonCav.contains(vehicle)) {
          collectEvent(rideHailNonEvNonCav, parkEvent, vehicle, parkEvent.time)
        }
      case _ =>
    }
  }

  def createGraph(): Unit = {
    processedHour = lastHour
    processVehicleStates()
  }

  private def collectEvent(
    vehicleEventTypeMap: mutable.Map[String, ArrayBuffer[Event]],
    event: Event,
    vehicle: String,
    eventTimeInSeconds: Double
  ): Unit = {
    val events = vehicleEventTypeMap.getOrElse(vehicle, new ArrayBuffer[Event]())
    events += event
    vehicleEventTypeMap(vehicle) = events.sortBy(_.getTime)
    val hour = (eventTimeInSeconds / secondsInHour).toInt
    if (hour > processedHour) {
      ProfilingUtils.timed(s"processVehicleStates V2 for hour $hour", x => println(x)) {
        processVehicleStates()
      }
      processedHour = hour
    }
  }

  private def processVehicleStates() {
    processEvents(rideHailEvCav, isRH = true, isCAV = true, "rh-ev-cav")
    processEvents(ridehailEvNonCav, isRH = true, isCAV = false, "rh-ev-nocav")
    processEvents(rideHailNonEvCav, isRH = true, isCAV = true, "rh-noev-cav")
    processEvents(rideHailNonEvNonCav, isRH = true, isCAV = false, "rh-noev-nocav")
  }

  private def processEvents(
    vehicleEventTypeMap: mutable.Map[String, ArrayBuffer[Event]],
    isRH: Boolean,
    isCAV: Boolean,
    graphName: String
  ) {
    class Utilization(
      ) {
      private val timeInternal: Array[Array[DoubleAdder]] =
        Array.fill[DoubleAdder](timeBins.size, keys.values.max + 1) {
          new DoubleAdder()
        }
      private val distanceInternal: Array[Array[DoubleAdder]] =
        Array.fill[DoubleAdder](timeBins.size, keys.values.max + 1) {
          new DoubleAdder()
        }

      def calculateTime: Array[Array[Double]] =
        timeInternal.map(arrayAdder => arrayAdder.map(adder => adder.doubleValue()))

      def calculateDistance: Array[Array[Double]] =
        distanceInternal.map(arrayAdder => arrayAdder.map(adder => adder.doubleValue()))

      def add(atime: Array[Array[Double]], adistance: Array[Array[Double]]): Unit = {
        for (idx1 <- timeBins.indices;
             idx2 <- 0 until keys.values.max + 1) {
          timeInternal(idx1)(idx2).add(atime(idx1)(idx2))
          distanceInternal(idx1)(idx2).add(adistance(idx1)(idx2))
        }
      }
    }

    val utilization: Utilization = new Utilization()

    vehicleEventTypeMap.values.par.foreach(vehicleEvents => {
      val (timeUtilization, distanceUtilization) = assignVehicleDayToLocationMatrix(vehicleEvents, isRH, isCAV)
      utilization.add(timeUtilization, distanceUtilization)
    })

    utilization.calculateTime.transpose.zipWithIndex.foreach {
      case (row, index) =>
        val key = states(index)
        val amountOfBinsPerHour = secondsInHour / resolutionInSeconds
        row.grouped(amountOfBinsPerHour).zipWithIndex.foreach {
          case (result, hour) =>
            if (hour <= processedHour)
              write(s"$graphName-count", result.sum / amountOfBinsPerHour, hour, key)
            else
              write(s"$graphName-count", 0, hour, key)
          case _ =>
        }
    }

    utilization.calculateDistance.transpose.zipWithIndex.foreach {
      case (row, index) =>
        val key = states(index)
        val amountOfBinsPerHour = secondsInHour / resolutionInSeconds
        row.grouped(amountOfBinsPerHour).zipWithIndex.foreach {
          case (result, hour) =>
            if (hour <= processedHour)
              write(s"$graphName-distance", (result.sum / amountOfBinsPerHour) * 12, hour, key)
            else
              write(s"$graphName-distance", 0, hour, key)
          case _ =>
        }
    }
  }

  private def write(metric: String, value: Double, time: Int, key: String): Unit = {
    val tags = Map("vehicle-state" -> key)
    writeIteration(
      metric,
      SimulationTime(time * 60 * 60),
      value,
      tags,
      true
    )
  }

  def resetStats(): Unit = {
    rideHailEvCav.clear()
    ridehailEvNonCav.clear()
    rideHailNonEvCav.clear()
    rideHailNonEvNonCav.clear()
    processedHour = 0
  }

  private def assignVehicleDayToLocationMatrix(
    days: ArrayBuffer[Event],
    isRH: Boolean,
    isCAV: Boolean
  ): (Array[Array[Double]], Array[Array[Double]]) = {
    val timeUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    val distanceUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)

    val idleActionIndex: Int =
      if (isRH) {
        if (isCAV) keys("idle")
        else keys("offline")
      } else {
        keys("parked")
      }

    timeBins.indices.foreach(timeUtilization(_)(idleActionIndex) += 1)

    days.zipWithIndex.foreach(eventIndex => {
      val event = eventIndex._1
      val idx = eventIndex._2
      val lastEvent = idx == days.size - 1

      var chargingNext = false
      var pickupNext = false

      if (!lastEvent) {
        val chargingDirectlyNext = days(idx + 1).getEventType == "RefuelSessionEvent"

        val chargingOneAfter =
          if (idx == days.size - 2)
            false
          else
            days(idx + 1).getEventType == "ParkEvent" && days(idx + 2).getEventType == "RefuelSessionEvent"

        chargingNext = chargingDirectlyNext || chargingOneAfter
        pickupNext = days(idx + 1) match {
          case pte: PathTraversalEvent => pte.numberOfPassengers >= 1
          case _                       => false
        }
      }

      val eventCharacteristics = classifyEventLocation(event, lastEvent, chargingNext, pickupNext, isRH, isCAV)

      val (afterEventStart, duringEvent) = timeBins
        .map(timeBin => {
          val eventStart = timeBin >= eventCharacteristics.start
          val duringEvent = eventStart && timeBin < eventCharacteristics.end
          (eventStart, duringEvent)
        })
        .unzip

      afterEventStart.zipWithIndex.foreach(indexValue => {
        if (indexValue._1)
          timeUtilization(indexValue._2).indices.foreach(actionIndex => {
            timeUtilization(indexValue._2)(actionIndex) = 0.0
          })
      })

      val eventIdx = keys(eventCharacteristics.eventType)
      duringEvent.zipWithIndex.foreach(indexValue => {
        if (indexValue._1) {
          timeUtilization(indexValue._2)(eventIdx) += 1.0
        }
      })

      event match {
        case pte: PathTraversalEvent =>
          val sum = duringEvent.count(during => during)
          val legLength = pte.legLength
          if (sum > 0) {
            val meanDistancePerTime = legLength / sum
            duringEvent.zipWithIndex.foreach(indexValue => {
              if (indexValue._1) {
                distanceUtilization(indexValue._2)(eventIdx) += meanDistancePerTime / metersInMile
              }
            })
          } else {
            val firstIndex = afterEventStart.indexOf(true)
            if (firstIndex > 0)
              distanceUtilization(firstIndex)(eventIdx) += legLength / metersInMile
          }
        case _ =>
      }

      eventCharacteristics.nextType.foreach(nextType => {
        val afterEventEnd = timeBins.map(_ >= eventCharacteristics.end)
        afterEventEnd.zipWithIndex.foreach(indexValue => {
          if (indexValue._1) {
            timeUtilization(indexValue._2)(keys(nextType)) += 1.0
          }
        })
      })
    })

    (timeUtilization, distanceUtilization)
  }

  private def classifyEventLocation(
    event: Event,
    lastEvent: Boolean,
    chargingNext: Boolean,
    pickupNext: Boolean,
    isRH: Boolean,
    isCAV: Boolean
  ): EventStatus = {
    event match {
      case event: PathTraversalEvent =>
        if (isRH) {
          if (event.numberOfPassengers >= 1) {
            if (lastEvent) {
              if (isCAV)
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("offline"))
            } else {
              if (chargingNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("queuing"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("idle"))
            }
          } else {
            if (lastEvent) {
              if (isCAV)
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("offline"))
            } else {
              if (chargingNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-tocharger", Some("queuing"))
              else if (pickupNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-topickup", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("idle"))
            }
          }
        } else {
          if (chargingNext)
            EventStatus(event.departureTime, event.arrivalTime, "driving-tocharger", Some("queuing"))
          else {
            if (event.numberOfPassengers >= 1)
              EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("queuing"))
            else
              EventStatus(event.departureTime, event.arrivalTime, "driving-topickup", Some("idle"))
          }
        }
      case rsEvent: RefuelSessionEvent =>
        val duration = rsEvent.sessionDuration
        if (isRH) {
          if (lastEvent) {
            if (isCAV)
              EventStatus(rsEvent.getTime, rsEvent.getTime + duration, "charging", Some("idle"))
            else
              EventStatus(rsEvent.getTime, rsEvent.getTime + duration, "charging", Some("offline"))
          } else
            EventStatus(rsEvent.getTime, rsEvent.getTime + duration, "charging", Some("idle"))
        } else {
          EventStatus(rsEvent.getTime, rsEvent.getTime + duration, "charging", Some("parked"))
        }
      case event: ParkingEvent =>
        if (isRH)
          EventStatus(event.getTime, lastHour * secondsInHour, "idle")
        else
          EventStatus(event.getTime, lastHour * secondsInHour, "parked")
      case _ =>
        EventStatus(0.0, 0.0, "Unknown")
    }
  }
}
