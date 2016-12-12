package beam.playground.services;

import java.util.Random;

import org.matsim.analysis.CalcLinkStats;
import org.matsim.analysis.IterationStopWatch;
import org.matsim.analysis.ScoreStats;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.listener.ControlerListener;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scoring.ScoringFunctionFactory;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;

import beam.playground.agents.BeamAgentPopulation;
import beam.playground.services.config.BeamConfigGroup;
import beam.sim.scheduler.Scheduler;
import beam.sim.traveltime.BeamRouter;

public class BeamServicesImpl implements BeamServices, MatsimServices {
	public static BeamConfigGroup beamConfig;
	private MatsimServices matsimServices;

	@Inject
	public BeamServicesImpl(MatsimServices matsimServices) {
		super();
		this.matsimServices = matsimServices;
	}
	@Override
	public Random getRandom() {
		return null;
	}
	@Override
	public BeamRouter getRouter() {
		return null;
	}
	@Override
	public Scheduler getScheduler() {
		return null;
	}
	@Override
	public BeamAgentPopulation getBeamAgentPopulation() {
		return null;
	}
	@Override
	public BeamConfigGroup getBeamConfigGroup() {
		return BeamServicesImpl.beamConfig;
	}
	public void setBeamConfigGroup(BeamConfigGroup group) {
		BeamServicesImpl.beamConfig = group;
	}
	@Override
	public IterationStopWatch getStopwatch() {
		return matsimServices.getStopwatch();
	}
	@Override
	public TravelTime getLinkTravelTimes() {
		return matsimServices.getLinkTravelTimes();
	}
	@Override
	public Provider<TripRouter> getTripRouterProvider() {
		return matsimServices.getTripRouterProvider();
	}
	@Override
	public TravelDisutility createTravelDisutilityCalculator() {
		return matsimServices.createTravelDisutilityCalculator();
	}
	@Override
	public LeastCostPathCalculatorFactory getLeastCostPathCalculatorFactory() {
		return matsimServices.getLeastCostPathCalculatorFactory();
	}
	@Override
	public ScoringFunctionFactory getScoringFunctionFactory() {
		return matsimServices.getScoringFunctionFactory();
	}
	@Override
	public Config getConfig() {
		return matsimServices.getConfig();
	}
	@Override
	public Scenario getScenario() {
		return matsimServices.getScenario();
	}
	@Override
	public EventsManager getEvents() {
		return matsimServices.getEvents();
	}
	@Override
	public Injector getInjector() {
		return matsimServices.getInjector();
	}
	@Override
	public CalcLinkStats getLinkStats() {
		return matsimServices.getLinkStats();
	}
	@Override
	public VolumesAnalyzer getVolumes() {
		return matsimServices.getVolumes();
	}
	@Override
	public ScoreStats getScoreStats() {
		return matsimServices.getScoreStats();
	}
	@Override
	public TravelDisutilityFactory getTravelDisutilityFactory() {
		return matsimServices.getTravelDisutilityFactory();
	}
	@Override
	public StrategyManager getStrategyManager() {
		return matsimServices.getStrategyManager();
	}
	@Override
	public OutputDirectoryHierarchy getControlerIO() {
		return matsimServices.getControlerIO();
	}
	@Override
	public void addControlerListener(ControlerListener controlerListener) {
		matsimServices.addControlerListener(controlerListener);
	}
	@Override
	public Integer getIterationNumber() {
		return matsimServices.getIterationNumber();
	}

}
