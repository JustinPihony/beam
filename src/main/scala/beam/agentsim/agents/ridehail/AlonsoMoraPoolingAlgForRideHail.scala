package beam.agentsim.agents.ridehail

import beam.agentsim.agents.planning.Trip
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.{BeamSkimmer, TimeDistanceAndCost}
import beam.router.Modes.BeamMode
import org.jgrapht.graph.{DefaultEdge, DefaultUndirectedWeightedGraph}
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.List
import scala.collection.JavaConverters._

// *** Algorithm ***
class AlonsoMoraPoolingAlgForRideHail(
  demand: List[CustomerRequest],
  supply: List[RideHailVehicle],
  omega: Int,
  delta: Int,
  radius: Int,
  skimmer: BeamSkimmer
) {

  // Request Vehicle Graph
  def pairwiseRVGraph: RVGraph = {
    val rvG = RVGraph(classOf[RideHailTrip])
    for (r1 <- demand;
         r2 <- demand) {
      if (r1 != r2 && !rvG.containsEdge(r1, r2)) {
        getRidehailSchedule(List(r1.pickup, r1.dropoff, r2.pickup, r2.dropoff)).map { schedule =>
          rvG.addVertex(r2)
          rvG.addVertex(r1)
          rvG.addEdge(r1, r2, RideHailTrip(List(r1, r2), schedule))
        }
      }
    }
    for (v <- supply;
         r <- demand) {
      val distance = getTimeDistanceAndCost(v.schedule.head, r.pickup).distance.get
      if (!rvG.containsEdge(v, r) && distance <= radius && v.getFreeSeats >= 1) {
        getRidehailSchedule(v.schedule ++ List(r.pickup, r.dropoff)).map { schedule =>
          rvG.addVertex(v)
          rvG.addVertex(r)
          rvG.addEdge(v, r, RideHailTrip(List(r), schedule))
        }
      }
    }
    rvG
  }

  // Request Trip Vehicle Graph
  // can be parallelized by vehicle
  def rTVGraph(rvG: RVGraph): RTVGraph = {
    val rTvG = RTVGraph(classOf[DefaultEdge])
    for (v <- supply) {

      if (v.getFreeSeats > 0) {
        rTvG.addVertex(v)

        import scala.collection.mutable.{ListBuffer => MListBuffer}
        val individualRequestsList = MListBuffer.empty[RideHailTrip]
        for (t <- rvG.outgoingEdgesOf(v).asScala) {
          individualRequestsList.append(t)
          rTvG.addVertex(t)
          rTvG.addVertex(t.requests.head)
          rTvG.addEdge(t.requests.head, t)
          rTvG.addEdge(t, v)
        }

        if (v.getFreeSeats > 1) {
          val pairRequestsList = MListBuffer.empty[RideHailTrip]
          var index = 1
          for (t1 <- individualRequestsList) {
            for (t2 <- individualRequestsList
                   .drop(index)
                   .withFilter(x => rvG.containsEdge(t1.requests.head, x.requests.head))) {
              getRidehailSchedule(v.schedule ++ (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)))
                .map { schedule =>
                  val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
                  pairRequestsList.append(t)
                  rTvG.addVertex(t)
                  rTvG.addEdge(t1.requests.head, t)
                  rTvG.addEdge(t2.requests.head, t)
                  rTvG.addEdge(t, v)
                }
            }
            index += 1
          }

          val finalRequestsList: MListBuffer[RideHailTrip] = individualRequestsList ++ pairRequestsList
          for (k <- 3 until v.getFreeSeats + 1) {
            var index = 1
            val kRequestsList = MListBuffer.empty[RideHailTrip]
            for (t1 <- finalRequestsList) {
              for (t2 <- finalRequestsList
                     .drop(index)
                     .withFilter(
                       x =>
                         !(x.requests exists (s => t1.requests contains s)) && (t1.requests.size + x.requests.size) == k
                     )) {
                getRidehailSchedule(
                  v.schedule ++ (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff))
                ).map { schedule =>
                  val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
                  kRequestsList.append(t)
                  rTvG.addVertex(t)
                  t.requests.foldLeft(()) { case (_, r) => rTvG.addEdge(r, t) }
                  rTvG.addEdge(t, v)
                }
              }
              index += 1
            }
            finalRequestsList.appendAll(kRequestsList)
          }
        }
      }
    }
    rTvG
  }

  // a greedy assignment using a cost function
  def greedyAssignment(rtvG: RTVGraph): List[(RideHailTrip, RideHailVehicle, Int)] = {
    val V: Int = supply.foldLeft(0) { case (maxCapacity, v) => Math max (maxCapacity, v.getFreeSeats) }
    val C0: Int = omega + delta
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val Rok = MListBuffer.empty[CustomerRequest]
    val Vok = MListBuffer.empty[RideHailVehicle]
    val greedyAssignmentList = MListBuffer.empty[(RideHailTrip, RideHailVehicle, Int)]
    for (k <- V to 1 by -1) {
      rtvG
        .vertexSet()
        .asScala
        .filter(t => t.isInstanceOf[RideHailTrip] && t.asInstanceOf[RideHailTrip].requests.size == k)
        .map { trip =>
          (
            trip.asInstanceOf[RideHailTrip],
            rtvG
              .getEdgeTarget(
                rtvG.outgoingEdgesOf(trip).asScala.filter(e => rtvG.getEdgeTarget(e).isInstanceOf[RideHailVehicle]).head
              )
              .asInstanceOf[RideHailVehicle],
            trip.asInstanceOf[RideHailTrip].cost + demand.count(
              y => !(trip.asInstanceOf[RideHailTrip].requests map (_.person) contains y.person)
            ) * C0 / k
          )
        }
        .toList
        .sortBy(_._3)
        .foldLeft(()) {
          case (_, (trip, vehicle, cost)) =>
            if (!(trip.requests exists (r => Rok contains r)) &&
                !(Vok contains vehicle)) {
              Rok.appendAll(trip.requests)
              Vok.append(vehicle)
              greedyAssignmentList.append((trip, vehicle, cost))
            }
        }
    }
    greedyAssignmentList.toList
  }

  // ******************************
  // Helper functions {} Start
  private def getTimeDistanceAndCost(src: MobilityServiceRequest, dst: MobilityServiceRequest): TimeDistanceAndCost = {
    skimmer.getTimeDistanceAndCost(
      src.activity.getCoord,
      dst.activity.getCoord,
      src.time.toInt,
      BeamMode.CAR,
      BeamVehicleType.defaultCarBeamVehicleType.id
    )
  }
  private def getRidehailSchedule(requests: List[MobilityServiceRequest]): Option[List[MobilityServiceRequest]] = {
    val sortedRequests = requests.sortWith(_.time < _.time)
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val newPoolingList = MListBuffer(sortedRequests.head.copy())
    sortedRequests.drop(1).foldLeft(()) {
      case (_, curReq) =>
        val prevReq = newPoolingList.last
        val serviceTime = prevReq.serviceTime.toInt +
        getTimeDistanceAndCost(prevReq, curReq).timeAndCost.time.get
        val window: Int = curReq.tag match {
          case Pickup  => omega
          case Dropoff => delta
          case _       => 0
        }
        if (serviceTime <= curReq.time + window) {
          newPoolingList.append(curReq.copy(serviceTime = serviceTime))
        } else {
          return None
        }
    }
    Some(newPoolingList.toList)
  }
  // Helper functions {} End
  // ******************************
}

// ***** Graph Structure *****
sealed trait RTVGraphNode {
  def getId: String
  override def toString: String = s"[$getId]"
}
sealed trait RVGraphNode extends RTVGraphNode
// customer requests
case class CustomerRequest(person: Person, pickup: MobilityServiceRequest, dropoff: MobilityServiceRequest)
    extends RVGraphNode {
  override def getId: String = person.getId.toString
}
// Ride Hail vehicles, capacity and their predefined schedule
case class RideHailVehicle(vehicle: Vehicle, schedule: List[MobilityServiceRequest]) extends RVGraphNode {
  override def getId: String = vehicle.getId.toString
  private val nbOfPassengers: Int = schedule.count(_.tag == Dropoff)
  private val maxOccupancy
    : Int = vehicle.getType.getCapacity.getSeats // TODO to get information from elsewhere (see cav)
  def getFreeSeats: Int = maxOccupancy - nbOfPassengers
}
// Trip that can be satisfied by one or more ride hail vehicle
case class RideHailTrip(requests: List[CustomerRequest], schedule: List[MobilityServiceRequest])
    extends DefaultEdge
    with RTVGraphNode {
  override def getId: String = requests.foldLeft(s"trip:") { case (c, x) => c + s"$x -> " }
  val cost: Int = schedule.foldLeft(0) { case (c, r)                     => c + (r.serviceTime - r.time).toInt }
}

case class RVGraph(clazz: Class[RideHailTrip]) extends DefaultUndirectedWeightedGraph[RVGraphNode, RideHailTrip](clazz)
case class RTVGraph(clazz: Class[DefaultEdge]) extends DefaultUndirectedWeightedGraph[RTVGraphNode, DefaultEdge](clazz)

// CAV structure
sealed trait MobilityServiceRequestType
case object Pickup extends MobilityServiceRequestType
case object Dropoff extends MobilityServiceRequestType
case object Relocation extends MobilityServiceRequestType
case object Init extends MobilityServiceRequestType

case class MobilityServiceRequest(
  person: Option[org.matsim.api.core.v01.Id[Person]],
  activity: Activity,
  time: Double,
  trip: Trip,
  defaultMode: BeamMode,
  tag: MobilityServiceRequestType,
  serviceTime: Double,
  routingRequestId: Option[Int] = None
) {
  val nextActivity = Some(trip.activity)

  def formatTime(secs: Double): String = {
    s"${(secs / 3600).toInt}:${((secs % 3600) / 60).toInt}:${(secs % 60).toInt}"
  }
  override def toString: String =
    s"${formatTime(time)}|$tag|${person.getOrElse("na")}|${activity.getType}| => ${formatTime(serviceTime)}"
}
