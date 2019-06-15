package beam.sim

import beam.analysis.RideHailUtilization
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class RideHailState extends LazyLogging{
  @volatile
  private var _allRideHailVehicles: Set[Id[Vehicle]] = Set.empty

  def setAllRideHailVehicles(vehicles: Set[Id[Vehicle]]): Unit = {
    _allRideHailVehicles = vehicles
  }

  def getAllRideHailVehicles: Set[Id[Vehicle]] = {
    _allRideHailVehicles
  }

  @volatile
  private var _rideHailUtilization: RideHailUtilization = RideHailUtilization(Set.empty, Set.empty, IndexedSeq.empty)

  def setRideHailUtilization(utilization: RideHailUtilization): Unit = {
    logger.info(s"Set new utilization. notMovedAtAll: ${utilization.notMovedAtAll.size}, movedWithoutPassenger: ${utilization.movedWithoutPassenger.size}, movedWithPassengers: ${utilization.movedWithPassengers.size}")
    _rideHailUtilization = utilization
  }

  def getRideHailUtilization: RideHailUtilization = {
    _rideHailUtilization
  }
}