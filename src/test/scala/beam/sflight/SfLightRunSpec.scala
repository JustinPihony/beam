package beam.sflight

import java.io.File

import beam.agentsim.events.ModeChoiceEvent
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamServices, RunBeam}
import beam.utils.FileUtils
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.ScenarioUtils
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by colinsheppard
  */

class SfLightRunSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll {

  "SF Light" must {
    "run without error and at least one person chooses car mode" in {
      val config = ConfigFactory.parseFile(new File("test/input/sf-light/sf-light-1k.conf")).resolve()
        .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig = configBuilder.buildMatSamConf()
      val beamConfig = BeamConfig(config)
      FileUtils.setConfigOutputFile(beamConfig.beam.outputs.outputDirectory, beamConfig.beam.agentsim.simulationName, matsimConfig)
      val scenario = ScenarioUtils.loadScenario(matsimConfig)
      var nCarTrips = 0
      val injector = org.matsim.core.controler.Injector.createInjector(scenario.getConfig, new AbstractModule() {
        override def install(): Unit = {
          install(module(scenario, config))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case modeChoiceEvent: ModeChoiceEvent =>
                  if (modeChoiceEvent.getAttributes.get("mode").equals("car")) {
                    nCarTrips = nCarTrips + 1
                  }
                case _ =>
              }
            }
          })
        }
      })
      val controler = injector.getInstance(classOf[BeamServices]).controler
      controler.run()
      assert(nCarTrips > 1)
    }
  }

}
