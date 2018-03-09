package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props, Stash}
import beam.agentsim.Resource.{CheckInResource, NotifyResourceIdle, NotifyResourceInUse, RegisterResource}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicleReservation
import beam.agentsim.agents.modalBehaviors.ChoosesMode.ChoosesModeData
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.{NotifyLegEndTrigger, NotifyLegStartTrigger, StartLegTrigger}
import beam.agentsim.agents.modalBehaviors.{ChoosesMode, DrivesVehicle, ModeChoiceCalculator}
import beam.agentsim.agents.planning.{BeamPlan, Tour}
import beam.agentsim.agents.vehicles.VehicleProtocol._
import beam.agentsim.agents.vehicles._
import beam.agentsim.scheduler.BeamAgentScheduler.IllegalTriggerGoToError
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import beam.router.RoutingModel._
import beam.sim.{BeamServices, HasServices}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population._
import org.matsim.core.api.experimental.events.{EventsManager, TeleportationArrivalEvent}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.concurrent.duration._

/**
  */
object PersonAgent {

  def props(scheduler: ActorRef, services: BeamServices, modeChoiceCalculator: ModeChoiceCalculator, transportNetwork: TransportNetwork, router: ActorRef, rideHailingManager: ActorRef, eventsManager: EventsManager, personId: Id[PersonAgent], household: Household, plan: Plan,
            humanBodyVehicleId: Id[Vehicle]): Props = {
    Props(new PersonAgent(scheduler, services, modeChoiceCalculator, transportNetwork, router, rideHailingManager, eventsManager, personId, plan, humanBodyVehicleId))
  }

  trait PersonData

  case class EmptyPersonData() extends PersonData {}

  sealed trait InActivity extends BeamAgentState

  case object PerformingActivity extends InActivity

  sealed trait Traveling extends BeamAgentState

  case object ChoosingMode extends Traveling

  case object WaitingForReservationConfirmation extends Traveling

  case object Waiting extends Traveling

  case object ProcessingNextLegOrStartActivity extends Traveling

  case object WaitingToDrive extends Traveling

  case object Moving extends Traveling

  case object Driving extends Traveling

  case class ActivityStartTrigger(tick: Double) extends Trigger

  case class ActivityEndTrigger(tick: Double) extends Trigger

  case class PersonDepartureTrigger(tick: Double) extends Trigger

}

class PersonAgent(val scheduler: ActorRef, val beamServices: BeamServices, val modeChoiceCalculator: ModeChoiceCalculator, val transportNetwork: TransportNetwork, val router: ActorRef, val rideHailingManager: ActorRef, val eventsManager: EventsManager, override val id: Id[PersonAgent], val matsimPlan: Plan, val bodyId: Id[Vehicle]) extends BeamAgent[PersonData] with
  HasServices with ChoosesMode with DrivesVehicle[PersonData] with Stash {

  val _experiencedBeamPlan: BeamPlan = BeamPlan(matsimPlan)
  var _currentActivityIndex: Int = 0
  var _currentVehicle: VehicleStack = VehicleStack()
  var _currentTrip: Option[EmbodiedBeamTrip] = None
  var _restOfCurrentTrip: EmbodiedBeamTrip = EmbodiedBeamTrip.empty
  var currentTourPersonalVehicle: Option[Id[Vehicle]] = None

  override def logDepth: Int = 100

  startWith(Uninitialized, BeamAgentInfo(id, EmptyPersonData()))

  def activityOrMessage(ind: Int, msg: String): Either[String, Activity] = {
    if (ind < 0 || ind >= _experiencedBeamPlan.activities.length) Left(msg) else Right(_experiencedBeamPlan.activities(ind))
  }

  def currentActivity: Activity = _experiencedBeamPlan.activities(_currentActivityIndex)

  def nextActivity: Either[String, Activity] = {
    activityOrMessage(_currentActivityIndex + 1, "plan finished")
  }

  def prevActivity: Either[String, Activity] = {
    activityOrMessage(_currentActivityIndex - 1, "at start")
  }

  def currentTour: Tour = {
    stateName match {
      case PerformingActivity =>
        _experiencedBeamPlan.getTourContaining(currentActivity)
      case _ =>
        _experiencedBeamPlan.getTourContaining(nextActivity.right.get)
    }
  }

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(_), triggerId), _) =>
      goto(Initialized) replying completed(triggerId, schedule[ActivityStartTrigger](0.0, self))
  }

  when(Initialized) {
    case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      logDebug(s"starting at ${currentActivity.getType} @ $tick")
      goto(PerformingActivity) using info replying completed(triggerId, schedule[ActivityEndTrigger](currentActivity.getEndTime, self))
  }

  when(PerformingActivity) {
    case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), info: BeamAgentInfo[PersonData]) =>
      nextActivity.fold(
        msg => {
          logDebug(s"didn't get nextActivity because $msg")
          stop replying completed(triggerId)
        },
        nextAct => {
          logDebug(s"wants to go to ${nextAct.getType} @ $tick")
          holdTickAndTriggerId(tick, triggerId)
          goto(ChoosingMode) using info.copy(data = ChoosesModeData(), triggersToSchedule = Vector())
        }
      )
  }

  when(Waiting, stateTimeout = 1 second) {
    case Event(TriggerWithId(PersonDepartureTrigger(tick), triggerId), _) =>
      // We end our activity when we actually leave, not when we decide to leave, i.e. when we look for a bus or
      // hail a ride. We stay at the party until our Uber is there.
      eventsManager.processEvent(new ActivityEndEvent(tick, id, currentActivity.getLinkId, currentActivity.getFacilityId, currentActivity.getType))
      assert(currentActivity.getLinkId != null)
      eventsManager.processEvent(new PersonDepartureEvent(tick, id, currentActivity.getLinkId, _restOfCurrentTrip.tripClassifier.value))
      self ! ProcessNextLegOrStartActivity(triggerId, tick)
      goto(ProcessingNextLegOrStartActivity)

    /*
     * Learn as passenger that leg is starting
     */
    case Event(TriggerWithId(NotifyLegStartTrigger(tick, beamLeg), triggerId), _) if beamLeg == _restOfCurrentTrip.legs.head.beamLeg =>
      logDebug(s"NotifyLegStartTrigger received: $beamLeg")
      if (_restOfCurrentTrip.legs.head.beamVehicleId == _currentVehicle.outermostVehicle()) {
        logDebug(s"Already on vehicle: ${_currentVehicle.outermostVehicle()}")
        goto(Moving) replying completed(triggerId)
      } else {
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, id, _restOfCurrentTrip.legs.head.beamVehicleId))
        _currentVehicle = _currentVehicle.pushIfNew(_restOfCurrentTrip.legs.head.beamVehicleId)
        goto(Moving) replying completed(triggerId)
      }

    case Event(reservationResponse: ReservationResponse, info) =>
      val (tick, triggerId) = releaseTickAndTriggerId()
      reservationResponse.response.fold(
          error => {
            logError("replanning")
            holdTickAndTriggerId(tick, triggerId)
            goto(ChoosingMode) using info.copy(data = ChoosesModeData(), triggersToSchedule = Vector())
          },
          confirmation => {
            scheduler ! completed(triggerId)
            stay()
          }
        )
  }

  when(Moving) {
    /*
     * Learn as passenger that leg is ending
     */
    case Event(TriggerWithId(NotifyLegEndTrigger(tick, beamLeg), triggerId), _) if beamLeg == _restOfCurrentTrip.legs.head.beamLeg =>
      _restOfCurrentTrip = _restOfCurrentTrip.copy(legs = _restOfCurrentTrip.legs.tail)
      if (_restOfCurrentTrip.legs.head.beamVehicleId == _currentVehicle.outermostVehicle()) {
        // The next vehicle is the same as current so just update state and go to Waiting
        goto(Waiting) replying completed(triggerId)
      } else {
        // The next vehicle is different from current so we exit the current vehicle
        eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, id, _currentVehicle.outermostVehicle()))
        _currentVehicle = _currentVehicle.pop()
        self ! ProcessNextLegOrStartActivity(triggerId, tick)
        goto(ProcessingNextLegOrStartActivity)
      }
  }

  // Callback from DrivesVehicle. Analogous to NotifyLegEndTrigger, but when driving ourselves.
  override def passengerScheduleEmpty(tick: Double, triggerId: Long): State = {
    if (_restOfCurrentTrip.legs.head.unbecomeDriverOnCompletion) {
      beamServices.vehicles(_currentVehicle.outermostVehicle()).unsetDriver()
      eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, Id.createPersonId(id), _currentVehicle.outermostVehicle()))
      _currentVehicle = _currentVehicle.pop()
      if (!_currentVehicle.isEmpty) {
        _currentVehicleUnderControl = Some(beamServices.vehicles(_currentVehicle.outermostVehicle()))
      }
    }
    _restOfCurrentTrip = _restOfCurrentTrip.copy(legs = _restOfCurrentTrip.legs.tail)
    self ! ProcessNextLegOrStartActivity(triggerId, tick)
    goto(ProcessingNextLegOrStartActivity)
  }

  onTransition {
    case _ -> _ =>
      unstashAll()
  }

  /*
   * processNextLegOrStartActivity
   *
   * This should be called when it's time to either embark on another leg in a trip or to wrap up a trip that is
   * now complete. There are four outcomes possible:
   *
   * 1 There are more legs in the trip and the PersonAgent is the driver => stay in current state but schedule
   * StartLegTrigger
   * 2 There are more legs in the trip but the PersonAGent is a passenger => goto Waiting and schedule nothing
   * further (the driver will initiate the start of the leg)
   * 3 The trip is over and there are more activities in the agent plan => goto PerformingActivity and schedule end
   * of activity
   * 4 The trip is over and there are no more activities in the agent plan => goto Finished
   */
  case class ProcessNextLegOrStartActivity(triggerId: Long, tick: Double)

  when(ProcessingNextLegOrStartActivity) {
    case Event(ProcessNextLegOrStartActivity(triggerId: Long, tick: Double), _) =>
      (_restOfCurrentTrip.legs.headOption, nextActivity) match {
        case (Some(nextLeg), _) if nextLeg.asDriver =>
          passengerSchedule = PassengerSchedule()
          passengerSchedule.addLegs(Vector(nextLeg.beamLeg))

          if (_currentVehicle.isEmpty || _currentVehicle.outermostVehicle() != nextLeg.beamVehicleId) {
            val vehicle = beamServices.vehicles(nextLeg.beamVehicleId)
            vehicle.becomeDriver(self).fold(fa =>
              stop(Failure(s"I attempted to become driver of vehicle $id but driver ${vehicle.driver.get} already assigned.")),
              fb => {
                _currentVehicleUnderControl = Some(vehicle)
                eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), nextLeg.beamVehicleId))
              })
          }

          _currentVehicle = _currentVehicle.pushIfNew(nextLeg.beamVehicleId)

          // Can't depart earlier than it is now
          val newTriggerTime = math.max(nextLeg.beamLeg.startTime, tick)
          scheduler ! completed(triggerId, schedule[StartLegTrigger](newTriggerTime, self, nextLeg.beamLeg))
          goto(WaitingToDrive)
        case (Some(nextLeg), _) if nextLeg.beamLeg.mode.isTransit() =>
          holdTickAndTriggerId(tick, triggerId)
          val legSegment = _restOfCurrentTrip.legs.takeWhile(leg => leg.beamVehicleId == nextLeg.beamVehicleId)
          val resRequest = new ReservationRequest(legSegment.head.beamLeg, legSegment.last.beamLeg, VehiclePersonId(legSegment.head.beamVehicleId, id))
          TransitDriverAgent.selectByVehicleId(legSegment.head.beamVehicleId) ! resRequest
          goto(Waiting)
        case (Some(_), _) =>
          scheduler ! completed(triggerId)
          goto(Waiting)
        case (None, Right(activity)) =>
          _currentActivityIndex = _currentActivityIndex + 1
          currentTourPersonalVehicle match {
            case Some(personalVeh) =>
              if (currentActivity.getType.equals("Home")) {
                context.parent ! ReleaseVehicleReservation(id, personalVeh)
                context.parent ! CheckInResource(personalVeh, None)
                currentTourPersonalVehicle = None
              }
            case None =>
          }
          val endTime = if (activity.getEndTime >= tick && Math.abs(activity.getEndTime) < Double.PositiveInfinity) {
            activity.getEndTime
          } else if (activity.getEndTime >= 0.0 && activity.getEndTime < tick) {
            tick
          } else {
            //            logWarn(s"Activity endTime is negative or infinite ${activity}, assuming duration of 10
            // minutes.")
            //TODO consider ending the day here to match MATSim convention for start/end activity
            tick + 60 * 10
          }
          // Report travelled distance for inclusion in experienced plans.
          // We currently get large unaccountable differences in round trips, e.g. work -> home may
          // be twice as long as home -> work. Probably due to long links, and the location of the activity
          // on the link being undefined.
          eventsManager.processEvent(new TeleportationArrivalEvent(tick, id, _currentTrip.get.legs.map(l => l.beamLeg.travelPath.distanceInM).sum))
          assert(activity.getLinkId != null)
          eventsManager.processEvent(new PersonArrivalEvent(tick, id, activity.getLinkId, _currentTrip.get.tripClassifier.value))
          _currentTrip = None
          eventsManager.processEvent(new ActivityStartEvent(tick, id, activity.getLinkId, activity.getFacilityId, activity.getType))
          scheduler ! completed(triggerId, schedule[ActivityEndTrigger](endTime, self))
          goto(PerformingActivity)
        case (None, Left(msg)) =>
          logDebug(msg)
          scheduler ! completed(triggerId)
          stop
      }

  }

  def cancelTrip(legsToCancel: Vector[EmbodiedBeamLeg], startingVehicle: VehicleStack): Unit = {
    if (legsToCancel.nonEmpty) {
      var inferredVehicle = startingVehicle
      var exitNextVehicle = false
      var prevLeg = legsToCancel.head

      if (inferredVehicle.nestedVehicles.nonEmpty) inferredVehicle = inferredVehicle.pop()

      for (leg <- legsToCancel) {
        if (exitNextVehicle || (!prevLeg.asDriver && leg.beamVehicleId != prevLeg.beamVehicleId)) inferredVehicle =
          inferredVehicle.pop()

        if (inferredVehicle.isEmpty || inferredVehicle.outermostVehicle() != leg.beamVehicleId) {
          inferredVehicle = inferredVehicle.pushIfNew(leg.beamVehicleId)
          if (inferredVehicle.nestedVehicles.size > 1 && !leg.asDriver && leg.beamLeg.mode.isTransit) {
            TransitDriverAgent.selectByVehicleId(inferredVehicle
              .outermostVehicle()) ! RemovePassengerFromTrip(VehiclePersonId(inferredVehicle.penultimateVehicle(), id))
          }
        }
        exitNextVehicle = leg.asDriver && leg.unbecomeDriverOnCompletion
        prevLeg = leg
      }
    }
  }

  override def postStop(): Unit = {
    if (_restOfCurrentTrip.legs.nonEmpty) {
      cancelTrip(_restOfCurrentTrip.legs, _currentVehicle)
    }
    super.postStop()
  }

  val myUnhandled: StateFunction = {
    case Event(TriggerWithId(NotifyLegStartTrigger(_, _), _), _) =>
      stash()
      stay
    case Event(TriggerWithId(NotifyLegEndTrigger(_, _), _), _) =>
      stash()
      stay
    case Event(NotifyResourceInUse(_, _), _) =>
      stay()
    case Event(RegisterResource(_), _) =>
      stay()
    case Event(NotifyResourceIdle(_, _), _) =>
      stay()
    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))
    case Event(StateTimeout, _) =>
      log.error("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      stop(Failure("Timeout - this probably means this agent was not getting a reply it was expecting."))
    case Event(Finish, _) =>
      log.warning("Still travelling at end of simulation.")
      log.warning("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      stop
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  override def logPrefix(): String = s"PersonAgent:$id "

}






