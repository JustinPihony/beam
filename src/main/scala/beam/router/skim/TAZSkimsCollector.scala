package beam.router.skim

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import akka.pattern.ask
import akka.pattern.pipe
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.BeamServices

import scala.concurrent.{ExecutionContext, Future}

class TAZSkimsCollector(scheduler: ActorRef, beamServices: BeamServices, vehicleManagers: Seq[ActorRef])
    extends Actor
    with ActorLogging {
  import TAZSkimsCollector._
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher
  private val eos: Int = getTimeInSeconds(beamServices.beamScenario.beamConfig.beam.agentsim.endTime)
  private val timeBin: Int = beamServices.beamConfig.beam.router.skim.taz_skimmer.timeBin

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      Future(scheduler ? ScheduleTrigger(TAZSkimsCollectionTrigger(timeBin), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(TAZSkimsCollectionTrigger(tick), triggerId) =>
      vehicleManagers.foreach(_ ! TAZSkimsCollectionTrigger(tick))
      if (tick + timeBin <= eos) {
        sender ! CompletionNotice(triggerId, Vector(ScheduleTrigger(TAZSkimsCollectionTrigger(tick + timeBin), self)))
      } else {
        sender ! CompletionNotice(triggerId)
      }

    case Finish =>
      context.children.foreach(_ ! Finish)
  }

  private def getTimeInSeconds(time: String): Int = {
    val timeAr = time.split(":")
    timeAr(0).toInt * 3600 + timeAr(1).toInt * 60 + timeAr(2).toInt
  }
}

object TAZSkimsCollector {
  case class TAZSkimsCollectionTrigger(tick: Int) extends Trigger

  def props(scheduler: ActorRef, services: BeamServices, actorsForTazSkimmer: Seq[ActorRef]): Props = {
    Props(new TAZSkimsCollector(scheduler, services, actorsForTazSkimmer))
  }
}
