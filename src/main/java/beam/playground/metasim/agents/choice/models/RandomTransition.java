package beam.playground.metasim.agents.choice.models;

import java.util.LinkedList;

import com.google.inject.Inject;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.services.BeamServices;

public class RandomTransition implements ChoiceModel {
	private BeamServices beamServices;

	@Inject
	public RandomTransition(BeamServices beamServices){
		super();
		this.beamServices = beamServices;
	}
	@Override
	public Transition selectTransition(BeamAgent agent, LinkedList<Transition> transitions) {
		return transitions.size() == 0 ? null : transitions.get(beamServices.getRandom().nextInt(transitions.size()));
	}
}
