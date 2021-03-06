/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.graalvm.compiler.phases.common.inlining.policy;

import static org.graalvm.compiler.core.common.GraalOptions.InlineEverything;
import static org.graalvm.compiler.core.common.GraalOptions.LimitInlinedInvokes;
import static org.graalvm.compiler.core.common.GraalOptions.MaximumDesiredSize;
import static org.graalvm.compiler.core.common.GraalOptions.MaximumInliningSize;
import static org.graalvm.compiler.core.common.GraalOptions.SmallCompiledLowLevelGraphSize;
import static org.graalvm.compiler.core.common.GraalOptions.TrivialInliningSize;

import java.util.Map;

import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.spi.Replacements;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.common.inlining.InliningUtil;
import org.graalvm.compiler.phases.common.inlining.info.InlineInfo;
import org.graalvm.compiler.phases.common.inlining.walker.MethodInvocation;

public class GreedyInliningPolicy extends AbstractInliningPolicy {

    private static final CounterKey inliningStoppedByMaxDesiredSizeCounter = DebugContext.counter("InliningStoppedByMaxDesiredSize");

    public GreedyInliningPolicy(Map<Invoke, Double> hints) {
        super(hints);
    }

    @Override
    public boolean continueInlining(StructuredGraph currentGraph) {
        if (InliningUtil.getNodeCount(currentGraph) >= MaximumDesiredSize.getValue(currentGraph.getOptions())) {
            DebugContext debug = currentGraph.getDebug();
            InliningUtil.logInliningDecision(debug, "inlining is cut off by MaximumDesiredSize");
            inliningStoppedByMaxDesiredSizeCounter.increment(debug);
            return false;
        }
        return true;
    }

    @Override
    public boolean isWorthInlining(Replacements replacements, MethodInvocation invocation, int inliningDepth, boolean fullyProcessed) {

        final InlineInfo info = invocation.callee();
        OptionValues options = info.graph().getOptions();
        final double probability = invocation.probability();
        final double relevance = invocation.relevance();

        if (InlineEverything.getValue(options)) {
            InliningUtil.logInlinedMethod(info, inliningDepth, fullyProcessed, "inline everything");
            return true;
        }

        if (isIntrinsic(replacements, info)) {
            InliningUtil.logInlinedMethod(info, inliningDepth, fullyProcessed, "intrinsic");
            return true;
        }

        if (info.shouldInline()) {
            InliningUtil.logInlinedMethod(info, inliningDepth, fullyProcessed, "forced inlining");
            return true;
        }

        double inliningBonus = getInliningBonus(info);
        int nodes = info.determineNodeCount();
        int lowLevelGraphSize = previousLowLevelGraphSize(info);

        if (SmallCompiledLowLevelGraphSize.getValue(options) > 0 && lowLevelGraphSize > SmallCompiledLowLevelGraphSize.getValue(options) * inliningBonus) {
            InliningUtil.logNotInlinedMethod(info, inliningDepth, "too large previous low-level graph (low-level-nodes: %d, relevance=%f, probability=%f, bonus=%f, nodes=%d)", lowLevelGraphSize,
                            relevance, probability, inliningBonus, nodes);
            return false;
        }

        if (nodes < TrivialInliningSize.getValue(options) * inliningBonus) {
            InliningUtil.logInlinedMethod(info, inliningDepth, fullyProcessed, "trivial (relevance=%f, probability=%f, bonus=%f, nodes=%d)", relevance, probability, inliningBonus, nodes);
            return true;
        }

        /*
         * TODO (chaeubl): invoked methods that are on important paths but not yet compiled -> will
         * be compiled anyways and it is likely that we are the only caller... might be useful to
         * inline those methods but increases bootstrap time (maybe those methods are also getting
         * queued in the compilation queue concurrently)
         */
        double invokes = determineInvokeProbability(info);
        if (LimitInlinedInvokes.getValue(options) > 0 && fullyProcessed && invokes > LimitInlinedInvokes.getValue(options) * inliningBonus) {
            InliningUtil.logNotInlinedMethod(info, inliningDepth, "callee invoke probability is too high (invokeP=%f, relevance=%f, probability=%f, bonus=%f, nodes=%d)", invokes, relevance,
                            probability, inliningBonus, nodes);
            return false;
        }

        double maximumNodes = computeMaximumSize(relevance, (int) (MaximumInliningSize.getValue(options) * inliningBonus));
        if (nodes <= maximumNodes) {
            InliningUtil.logInlinedMethod(info, inliningDepth, fullyProcessed, "relevance-based (relevance=%f, probability=%f, bonus=%f, nodes=%d <= %f)", relevance, probability, inliningBonus,
                            nodes, maximumNodes);
            return true;
        }

        InliningUtil.logNotInlinedMethod(info, inliningDepth, "relevance-based (relevance=%f, probability=%f, bonus=%f, nodes=%d > %f)", relevance, probability, inliningBonus, nodes, maximumNodes);
        return false;
    }
}
