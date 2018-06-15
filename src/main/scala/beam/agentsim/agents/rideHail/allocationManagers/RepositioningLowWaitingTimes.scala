package beam.agentsim.agents.rideHail.allocationManagers

import beam.agentsim.agents.rideHail.{RideHailingManager, TNCIterationStats}
import beam.router.BeamRouter.Location
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class RepositioningLowWaitingTimes(val rideHailingManager: RideHailingManager, tncIterationStats: Option[TNCIterationStats]) extends RideHailResourceAllocationManager {

  val isBufferedRideHailAllocationMode = false

  def proposeVehicleAllocation(vehicleAllocationRequest: VehicleAllocationRequest): Option[VehicleAllocation] = {
    None
  }

  def allocateVehicles(allocationsDuringReservation: Vector[(VehicleAllocationRequest, Option[VehicleAllocation])]): Vector[(VehicleAllocationRequest, Option[VehicleAllocation])] = {
    log.error("batch procesbsing is not implemented for DefaultRideHailResourceAllocationManager")
    allocationsDuringReservation
  }

  override def repositionVehicles(tick: Double): Vector[(Id[Vehicle], Location)] = {


    //sortedTAZ.take(numberOfVehiclesToReposition)


    // TODO: location has to be fixed for random (same for all iterations)! -> confirm that not changing
    //-> start with home or middle point, as fixed


    //intial distribution according to demand (=home).


    /*

    planning horzion of 20min

repositioningTimer=5min
bin size=5min
look at TAZ for next 20min, which have highest number of idle vehicles Sum (over 4x5min slotes)
  -> take top n% (repositioning rate)
    -> only consider TAZs in distance 2miles
    -> calculate attractionScore of TAZs and take top 5% (again for next 20min, sum).
    -> assign according to weight probabilistically
-> if need additional


    -> which tnc to reposition?
      -> go through all idle tncs
      -> if taxi


      -> find areas with taxis with long idle time
        -> threshhold parameter min idle time and max share to reposition


      -> ide now + idle for a longer time




    -> 	Randomly sampling (This is what we implement first)
		-> search radius: idle time in that area make as window (TAZ areas you can reach in 10min)
		-> sum up the demand (number of requests + waiting time + idle time)
			-> draw circle
			-> negative weight
			-> on TAZ
			-> sum per TAZ and time slot.

			=> talk at Matsim wrkshop: one without distribution, random, this method.
				=> demonstrate


				look at demand in 10min at the TAZ around me

				probabilityOfServing(taz_i)=score(taz_i)/sumOfScores
				-> score(taz_i)=alpha*demand+betta*waitingTimes

=>



rideHailStats



     */

    tncIterationStats match {
      case Some(tncIterationStats) =>
        // iteration >0
        //tncIterationStats.getRideHailStatsInfo()


        val idleVehicles = rideHailingManager.getIdleVehicles
        val fleetSize = rideHailingManager.resources.size // TODO: get proper number here from rideHailManager
      val timeWindowSizeInSecForDecidingAboutRepositioning = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.timeWindowSizeInSecForDecidingAboutRepositioning
        val percentageOfVehiclesToReposition = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.percentageOfVehiclesToReposition
        val maxNumberOfVehiclesToReposition = (fleetSize * percentageOfVehiclesToReposition).toInt

        val repositionCircleRadisInMeters = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.repositionCircleRadisInMeters
        val minimumNumberOfIdlingVehiclesThreshholdForRepositioning = rideHailingManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThreshholdForRepositioning

        val allowIncreasingRadiusIfMostDemandOutside = true
        val minReachableDemandByVehiclesSelectedForReposition = 0.1



        //tncIterationStats.printMap()

        assert(maxNumberOfVehiclesToReposition > 0, "Using RepositioningLowWaitingTimes allocation Manager but percentageOfVehiclesToReposition results in 0 respositioning - use Default Manager if not repositioning needed")


        val vehiclesToReposition = tncIterationStats.getVehiclesWhichAreBiggestCandidatesForIdling(idleVehicles, maxNumberOfVehiclesToReposition, tick, timeWindowSizeInSecForDecidingAboutRepositioning, minimumNumberOfIdlingVehiclesThreshholdForRepositioning)

        var circleSize = repositionCircleRadisInMeters

        while (tncIterationStats.demandRatioInCircleToOutside(vehiclesToReposition, circleSize, tick, timeWindowSizeInSecForDecidingAboutRepositioning) < minReachableDemandByVehiclesSelectedForReposition) {
          circleSize = repositionCircleRadisInMeters * 2
        }

        val whichTAZToRepositionTo: Vector[(Id[Vehicle], Location)] = tncIterationStats.whichCoordToRepositionTo(vehiclesToReposition, repositionCircleRadisInMeters, tick, timeWindowSizeInSecForDecidingAboutRepositioning, rideHailingManager.beamServices)

        if (vehiclesToReposition.nonEmpty) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }

        if (whichTAZToRepositionTo.nonEmpty) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }

        whichTAZToRepositionTo
      case None =>
        // iteration 0
        Vector()
    }


    // if (rideHailingManager.getIdleVehicles().size >= 2) {
    // val origin=rideHailingManager.getIdleVehicles().values.toVector
    //  val destination=scala.util.Random.shuffle(origin)
    // (for ((o,d)<-(origin zip destination)) yield (o.vehicleId,d.currentLocation.loc)) //.splitAt(4)._1
    // } else {
    // Vector()
    // }
  }
}




