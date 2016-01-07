package org.aksw.limes.core.execution.planning.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.aksw.limes.core.execution.planning.plan.NestedPlan;
import org.aksw.limes.core.execution.planning.plan.Instruction;
import org.aksw.limes.core.io.cache.Cache;
import org.aksw.limes.core.io.ls.LinkSpecification;
import org.aksw.limes.core.io.mapping.MemoryMapping;
import org.aksw.limes.core.io.parser.Parser;
import org.aksw.limes.core.measures.mapper.IMapper.Language;
import org.aksw.limes.core.measures.mapper.MappingOperations.Operator;
import org.aksw.limes.core.measures.mapper.atomic.EDJoin;
import org.aksw.limes.core.measures.mapper.atomic.JaroMapper;
import org.aksw.limes.core.measures.mapper.atomic.PPJoinPlusPlus;
import org.aksw.limes.core.measures.mapper.atomic.TotalOrderBlockingMapper;
import org.aksw.limes.core.measures.mapper.atomic.fastngram.FastNGram;
import org.aksw.limes.core.measures.measure.Measure;
import org.aksw.limes.core.measures.measure.MeasureFactory;
import org.aksw.limes.core.measures.measure.MeasureProcessor;
import org.apache.log4j.Logger;

/**
 *
 * Impelements Helios Planner class.
 * 
 * @author ngonga
 * @author kleanthi
 */
public class HeliosPlanner extends Planner {

    static Logger logger = Logger.getLogger("LIMES");
    public Cache source;
    public Cache target;
    Map<String, Double> averageSourcePropertyLength;
    Map<String, Double> stdDevSourceProperty;
    Map<String, Double> averageTargetPropertyLength;
    Map<String, Double> stdDevTargetProperty;
    public Language lang;

    /**
     * Constructor. Caches are needed for statistic computations.
     *
     * @param s
     *            Source cache
     * @param t
     *            Target get
     */
    public HeliosPlanner(Cache s, Cache t) {
	source = s;
	target = t;
	lang = Language.NULL;
    }

    /**
     * Computes atomic costs for a metric expression
     *
     * @param measure
     * @param threshold
     * @return runtime, estimated runtime cost of the metric expression
     */
    public double getAtomicRuntimeCosts(String measure, double threshold) {
	Measure m = MeasureFactory.getMeasure(measure);
	double runtime;
	if (m.getName().equalsIgnoreCase("levenshtein")) {
	    runtime = (new EDJoin()).getRuntimeApproximation(source.size(), target.size(), threshold, lang);
	} else if (m.getName().equalsIgnoreCase("euclidean")) {
	    runtime = (new TotalOrderBlockingMapper()).getRuntimeApproximation(source.size(), target.size(), threshold,
		    lang);
	} else if (m.getName().equalsIgnoreCase("qgrams")) {
	    runtime = (new FastNGram()).getRuntimeApproximation(source.size(), target.size(), threshold, lang);
	} else if (m.getName().equalsIgnoreCase("jaro")) {
	    runtime = (new JaroMapper()).getRuntimeApproximation(source.size(), target.size(), threshold, lang);
	} else {
	    runtime = (new PPJoinPlusPlus()).getRuntimeApproximation(source.size(), target.size(), threshold, lang);
	}
	logger.info("Runtime approximation for " + measure + " is " + runtime);
	return runtime;
    }

    /**
     * Computes atomic mapping sizes for a measure
     *
     * @param measure
     * @param threshold
     * @return size, estimated size of returned mapping
     */
    public double getAtomicMappingSizes(String measure, double threshold) {
	Measure m = MeasureFactory.getMeasure(measure);
	double size;
	if (m.getName().equalsIgnoreCase("levenshtein")) {
	    size = (new EDJoin()).getMappingSizeApproximation(source.size(), target.size(), threshold, lang);
	}
	if (m.getName().equalsIgnoreCase("euclidean")) {
	    size = (new TotalOrderBlockingMapper()).getMappingSizeApproximation(source.size(), target.size(), threshold,
		    lang);
	}
	if (m.getName().equalsIgnoreCase("qgrams")) {
	    size = (new FastNGram()).getMappingSizeApproximation(source.size(), target.size(), threshold, lang);
	} else if (m.getName().equalsIgnoreCase("jaro")) {
	    size = (new JaroMapper()).getMappingSizeApproximation(source.size(), target.size(), threshold, lang);
	} else {
	    size = (new PPJoinPlusPlus()).getMappingSizeApproximation(source.size(), target.size(), threshold, lang);
	}
	return size;
    }

    /**
     * Computes costs for a filtering
     *
     * @param filterExpression
     *            Expression used to filter
     * @param mappingSize
     *            Size of mapping
     * @return cost, estimated runtime cost of filteringInstruction(s)
     */
    public double getFilterCosts(List<String> measures, int mappingSize) {
	double cost = 0;
	for (String measure : measures) {
	    cost = cost + MeasureFactory.getMeasure(measure).getRuntimeApproximation(mappingSize);
	}
	logger.info("Runtime approximation for filter expression " + measures + " is " + cost);
	return cost;
    }

    public NestedPlan plan(LinkSpecification spec) {
	return plan(spec, source, target, new MemoryMapping(), new MemoryMapping());
    }

    /**
     * Generates a NestedPlan based on the optimality assumption used in
     * databases
     *
     * @param spec
     *            Input link specification
     * @param source
     *            Source cache
     * @param target
     *            Target cache
     * @param sourceMapping
     *            Size of source mapping
     * @param targetMapping
     *            Size of target mapping
     * @return plan, a NestedPlan for the input link specification
     */
    public NestedPlan plan(LinkSpecification spec, Cache source, Cache target, MemoryMapping sourceMapping,
	    MemoryMapping targetMapping) {
	NestedPlan plan = new NestedPlan();
	// atomic specs are simply ran
	if (spec.isAtomic()) {
	    // here we should actually choose between different implementations
	    // of the operators based on their runtimeCost
	    Parser p = new Parser(spec.getFilterExpression(), spec.getThreshold());
	    plan.setInstructionList(new ArrayList<Instruction>());
	    plan.addInstruction(new Instruction(Instruction.Command.RUN, spec.getFilterExpression(),
		    spec.getThreshold() + "", -1, -1, 0));
	    plan.setRuntimeCost(getAtomicRuntimeCosts(p.getOperator(), spec.getThreshold()));
	    plan.setMappingSize(getAtomicMappingSizes(p.getOperator(), spec.getThreshold()));
	    // there is a function in EDJoin that does that
	    plan.setSelectivity(plan.getMappingSize() / (double) (source.size() * target.size()));
	    // System.out.println("Plan for " + spec.filterExpression + ":\n" +
	    // plan);
	} else {
	    // no optimization for non AND operators really
	    if (!spec.getOperator().equals(Operator.AND)) {
		List<NestedPlan> children = new ArrayList<NestedPlan>();
		// set children and update costs
		plan.setRuntimeCost(0);
		for (LinkSpecification child : spec.getChildren()) {
		    NestedPlan childPlan = plan(child, source, target, sourceMapping, targetMapping);
		    children.add(childPlan);
		    plan.setRuntimeCost(plan.getRuntimeCost() + childPlan.getRuntimeCost());
		}
		// add costs of union, which are 1
		plan.setRuntimeCost(plan.getRuntimeCost() + (spec.getChildren().size() - 1));
		plan.setSubPlans(children);
		// set operator
		double selectivity;
		if (spec.getOperator().equals(Operator.OR)) {
		    plan.setOperator(Instruction.Command.UNION);
		    selectivity = 1 - children.get(0).getSelectivity();
		    plan.setRuntimeCost(children.get(0).getRuntimeCost());
		    for (int i = 1; i < children.size(); i++) {
			selectivity = selectivity * (1 - children.get(i).getSelectivity());
			// add filtering costs based on approximation of mapping
			// size
			if (plan.getFilteringInstruction() != null) {
			    plan.setRuntimeCost(plan.getRuntimeCost()
				    + MeasureProcessor.getCosts(plan.getFilteringInstruction().getMeasureExpression(),
					    source.size() * target.size() * (1 - selectivity)));
			}
		    }
		    plan.setSelectivity(1 - selectivity);
		} else if (spec.getOperator().equals(Operator.MINUS)) {
		    plan.setOperator(Instruction.Command.DIFF);
		    // p(A \ B \ C \ ... ) = p(A) \ p(B U C U ...)
		    selectivity = children.get(0).getSelectivity();
		    for (int i = 1; i < children.size(); i++) {
			selectivity = selectivity * (1 - children.get(i).getSelectivity());
			// add filtering costs based on approximation of mapping
			// size
			if (plan.getFilteringInstruction() != null) {
			    plan.setRuntimeCost(plan.getRuntimeCost()
				    + MeasureProcessor.getCosts(plan.getFilteringInstruction().getMeasureExpression(),
					    source.size() * target.size() * (1 - selectivity)));
			}
		    }
		    plan.setSelectivity(selectivity);
		} else if (spec.getOperator().equals(Operator.XOR)) {
		    plan.setOperator(Instruction.Command.XOR);
		    // A XOR B = (A U B) \ (A & B)
		    selectivity = children.get(0).getSelectivity();
		    for (int i = 1; i < children.size(); i++) {
			selectivity = (1 - (1 - selectivity) * (1 - children.get(i).getSelectivity()))
				* (1 - selectivity * children.get(i).getSelectivity());
			// add filtering costs based on approximation of mapping
			// size
			if (plan.getFilteringInstruction() != null) {
			    plan.setRuntimeCost(plan.getRuntimeCost()
				    + MeasureProcessor.getCosts(plan.getFilteringInstruction().getMeasureExpression(),
					    source.size() * target.size() * selectivity));
			}
		    }
		    plan.setSelectivity(selectivity);
		}
		plan.setFilteringInstruction(new Instruction(Instruction.Command.FILTER, spec.getFilterExpression(),
			spec.getThreshold() + "", -1, -1, 0));
	    } // here we can optimize.
	    else if (spec.getOperator().equals(Operator.AND)) {
		List<NestedPlan> children = new ArrayList<NestedPlan>();
		plan.setRuntimeCost(0);
		double selectivity = 1d;
		for (LinkSpecification child : spec.getChildren()) {
		    NestedPlan childPlan = plan(child);
		    children.add(childPlan);
		    plan.setRuntimeCost(plan.getRuntimeCost() + childPlan.getRuntimeCost());
		    selectivity = selectivity * childPlan.getSelectivity();
		}
		plan = getBestConjunctivePlan(children, selectivity);
	    }
	}
	return plan;
    }

    /**
     * Compute the left-order best instructionList for a list of plans. Only
     * needed when AND has more than 2 children. Simply splits the task in
     * computing the best instructionList for (leftmost, all others)
     *
     * @param plans
     *            List of plans
     * @param selectivity
     *            Selectivity of the instructionList (known beforehand)
     * @return NestedPlan
     */
    public NestedPlan getBestConjunctivePlan(List<NestedPlan> plans, double selectivity) {
	if (plans == null) {
	    return null;
	}
	if (plans.isEmpty()) {
	    return new NestedPlan();
	}
	if (plans.size() == 1) {
	    return plans.get(0);
	}
	if (plans.size() == 2) {
	    return getBestConjunctivePlan(plans.get(0), plans.get(1), selectivity);
	} else {
	    NestedPlan left = plans.get(0);
	    plans.remove(plans.get(0));
	    return getBestConjunctivePlan(left, plans, selectivity);
	}
    }

    /**
     * Computes the best conjunctive instructionList for a instructionList
     * against a list of plans by calling back the method
     *
     * @param left
     *            Left instructionList
     * @param plans
     *            List of other plans
     * @param selectivity
     *            Overall selectivity
     * @return NestedPlan
     */
    public NestedPlan getBestConjunctivePlan(NestedPlan left, List<NestedPlan> plans, double selectivity) {
	if (plans == null) {
	    return left;
	}
	if (plans.isEmpty()) {
	    return left;
	}
	if (plans.size() == 1) {
	    return getBestConjunctivePlan(left, plans.get(0), selectivity);
	} else {
	    NestedPlan right = getBestConjunctivePlan(plans, selectivity);
	    return getBestConjunctivePlan(left, right, selectivity);
	}
    }

    /**
     * Computes the best conjunctive instructionList for one pair of nested
     * plans
     *
     * @param left
     *            Left instructionList
     * @param right
     *            Right instructionList
     * @param selectivity
     * @return NestedPlan
     */
    public NestedPlan getBestConjunctivePlan(NestedPlan left, NestedPlan right, double selectivity) {
	double runtime1 = 0, runtime2, runtime3;
	NestedPlan result = new NestedPlan();
	double mappingSize = source.size() * target.size() * right.getSelectivity();

	// first instructionList: run both children and then merge
	runtime1 = left.getRuntimeCost() + right.getRuntimeCost();
	// second instructionList: run left child and use right child as filter
	runtime2 = left.getRuntimeCost();
	runtime2 = runtime2 + getFilterCosts(right.getAllMeasures(), (int) Math.ceil(mappingSize));
	// third instructionList: run right child and use left child as filter
	runtime3 = right.getRuntimeCost();
	mappingSize = source.size() * target.size() * left.getSelectivity();
	runtime3 = runtime3 + getFilterCosts(left.getAllMeasures(), (int) Math.ceil(mappingSize));

	double min = Math.min(Math.min(runtime3, runtime2), runtime1);

	if (min == runtime1) {
	    result.setOperator(Instruction.Command.INTERSECTION);
	    result.setFilteringInstruction(null);
	    List<NestedPlan> plans = new ArrayList<NestedPlan>();
	    plans.add(left);
	    plans.add(right);
	    result.setSubPlans(plans);

	} else if (min == runtime2) {
	    if (right.isFlat()) {
		result.setFilteringInstruction(new Instruction(Instruction.Command.FILTER, right.getEquivalentMeasure(),
			right.getInstructionList().get(0).getThreshold() + "", -1, -1, 0));
	    } else {
		result.setFilteringInstruction(new Instruction(Instruction.Command.FILTER, right.getEquivalentMeasure(),
			right.getFilteringInstruction().getThreshold() + "", -1, -1, 0));
	    }
	    result.setOperator(null);
	    List<NestedPlan> plans = new ArrayList<NestedPlan>();
	    plans.add(left);
	    result.setSubPlans(plans);
	} else {
	    if (left.isFlat()) {
		result.setFilteringInstruction(new Instruction(Instruction.Command.FILTER, left.getEquivalentMeasure(),
			left.getInstructionList().get(0).getThreshold() + "", -1, -1, 0));
	    } else {
		result.setFilteringInstruction(new Instruction(Instruction.Command.FILTER, left.getEquivalentMeasure(),
			left.getFilteringInstruction().getThreshold() + "", -1, -1, 0));
	    }
	    result.setOperator(null);
	    List<NestedPlan> plans = new ArrayList<NestedPlan>();
	    plans.add(right);
	    result.setSubPlans(plans);
	}
	result.setRuntimeCost(min);
	result.setSelectivity(selectivity);
	result.setMappingSize(source.size() * target.size() * selectivity);

	return result;
    }
}