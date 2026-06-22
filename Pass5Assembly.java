
package sysml2uml;

import java.util.*;
import java.nio.file.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.uml2.uml.*;

/**
 * PASS 5 + Post-processing: 数据流装配与控制流输出
 * - BIND CONNECTOR deep search
 * - DEFERRED FLOW RESOLUTION
 * - DEFERRED SUCCESSION FLOW RESOLUTION
 * - DATA FLOW PASS: ObjectFlow creation
 * - PASS 5: ControlFlow assembly from finalRoutedEdges
 * - UML model save and validation report
 */
class Pass5Assembly {
    static void run(Resource resource, PipelineContext ctx, String xmiPath) throws Exception {

        // ===================================================================
        // BIND CONNECTOR PASS: 深度搜索 BindingConnectorAsUsage (可能在非标准 containment 中)
        // ===================================================================
        if (!ctx.isCalcModel) {
            Set<String> processedBindConnectorIds = new HashSet<>();
            java.util.Iterator<EObject> iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                for (java.util.Iterator<EObject> deepIt = obj.eAllContents(); deepIt.hasNext();) {
                    EObject deep = deepIt.next();
                    String deepCn = deep.eClass().getName();
                    if (deepCn.contains("BindingConnector")) {
                        // 去重: 同一个 BindingConnector 只处理一次
                        String deepId = null;
                        try {
                            deepId = ((org.omg.sysml.lang.sysml.Element) deep).getElementId();
                        } catch (Exception ignored) {
                            // ignored
                        }
                        if (deepId != null && !processedBindConnectorIds.add(deepId))
                            continue;

                        List<String> endFeatureIds = new ArrayList<>();
                        for (EObject rel : deep.eContents()) {
                            if (rel.eClass().getName().equals("EndFeatureMembership")) {
                                for (EObject refUsage : rel.eContents()) {
                                    String refCn = refUsage.eClass().getName();
                                    if (refCn.contains("ReferenceUsage") || refCn.contains("Feature")) {
                                        for (EObject sub : refUsage.eContents()) {
                                            if (sub.eClass().getName().equals("ReferenceSubsetting")) {
                                                // 1. referencedFeature (EcoreUtil.resolve)
                                                EStructuralFeature rfFeat = sub.eClass()
                                                        .getEStructuralFeature("referencedFeature");
                                                if (rfFeat != null) {
                                                    Object refObj = sub.eGet(rfFeat);
                                                    if (refObj instanceof EObject) {
                                                        EObject resolved = EcoreUtil.resolve((EObject) refObj, sub);
                                                        if (resolved instanceof org.omg.sysml.lang.sysml.Element) {
                                                            String refId = ((org.omg.sysml.lang.sysml.Element) resolved)
                                                                    .getElementId();
                                                            if (refId != null) {
                                                                endFeatureIds.add(refId);
                                                            }
                                                        }
                                                    }
                                                }
                                                // 2. FeatureChaining: 取最后一个 chainingFeature
                                                String lastChainId = null;
                                                for (EObject fc : sub.eContents()) {
                                                    if (fc.eClass().getName().equals("Feature")) {
                                                        for (EObject fcc : fc.eContents()) {
                                                            if (fcc.eClass().getName().equals("FeatureChaining")) {
                                                                try {
                                                                    EStructuralFeature cfFeat = fcc.eClass()
                                                                            .getEStructuralFeature("chainingFeature");
                                                                    if (cfFeat != null) {
                                                                        Object cfVal = fcc.eGet(cfFeat);
                                                                        if (cfVal instanceof EObject) {
                                                                            EObject cfResolved = EcoreUtil
                                                                                    .resolve((EObject) cfVal, fcc);
                                                                            if (cfResolved instanceof org.omg.sysml.lang.sysml.Element) {
                                                                                lastChainId = ((org.omg.sysml.lang.sysml.Element) cfResolved)
                                                                                        .getElementId();
                                                                            }
                                                                        }
                                                                    }
                                                                } catch (Exception ignored) {
                                                                    // ignored
                                                                }
                                                            }
                                                        }
                                                        try {
                                                            String fcId = ((org.omg.sysml.lang.sysml.Element) fc)
                                                                    .getElementId();
                                                            if (fcId != null && lastChainId == null) {
                                                                lastChainId = fcId;
                                                            }
                                                        } catch (Exception ignored) {
                                                            // ignored
                                                        }
                                                    }
                                                }
                                                if (lastChainId != null && !endFeatureIds.contains(lastChainId)) {
                                                    endFeatureIds.add(lastChainId);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (endFeatureIds.size() >= 2) {
                            String[] end1 = ctx.featureIdToPinInfo.get(endFeatureIds.get(0));
                            String[] end2 = ctx.featureIdToPinInfo.get(endFeatureIds.get(1));
                            // 尝试额外候选 ID
                            if (end1 == null && endFeatureIds.size() > 2) {
                                for (String eid : endFeatureIds.subList(2, endFeatureIds.size())) {
                                    end1 = ctx.featureIdToPinInfo.get(eid);
                                    if (end1 != null) {
                                        break;
                                    }
                                }
                            }
                            if (end2 == null && endFeatureIds.size() > 2) {
                                for (String eid : endFeatureIds.subList(2, endFeatureIds.size())) {
                                    end2 = ctx.featureIdToPinInfo.get(eid);
                                    if (end2 != null) {
                                        break;
                                    }
                                }
                            }
                            if (end1 != null && end2 != null) {
                                ctx.bindConnectorBindings.add(new String[]{end1[0], end1[1], end2[0], end2[1]});
                                System.out.println("[BIND PASS] " + end1[1] + " <-> " + end2[1] + " (action1="
                                        + end1[0].substring(0, Math.min(8, end1[0].length())) + " action2="
                                        + end2[0].substring(0, Math.min(8, end2[0].length())) + ")");
                            } else {
                                System.out.println("[BIND PASS WARN] Unresolved bind: "
                                        + endFeatureIds.get(0).substring(0, Math.min(8, endFeatureIds.get(0).length()))
                                        + " (" + (end1 != null ? "found" : "not found") + ") <-> "
                                        + endFeatureIds.get(1).substring(0, Math.min(8, endFeatureIds.get(1).length()))
                                        + " (" + (end2 != null ? "found" : "not found") + ")");
                            }
                        }
                    }
                }
            }
            System.out.println("[BIND PASS] Total " + ctx.bindConnectorBindings.size()
                    + " bind connectors, featureIdToPinInfo has " + ctx.featureIdToPinInfo.size()
                    + " entries, processed " + processedBindConnectorIds.size() + " unique connectors");
        }

        // ===================================================================
        // DEFERRED FLOW RESOLUTION: Resolve FlowUsage bindings now that all actions are
        // in nameToIdMap
        // ===================================================================
        for (String[] dflow : ctx.deferredFlows) {
            String srcActionName = dflow[0], srcPinName = dflow[1];
            String tgtActionName = dflow[2], tgtPinName = dflow[3];
            String srcId = MainRunner.nameToIdMap.get(srcActionName);
            String tgtId = MainRunner.nameToIdMap.get(tgtActionName);
            if (srcId != null && tgtId != null) {
                ctx.pinBindings.add(new String[]{tgtId, tgtPinName, srcId, srcPinName});
                System.out.println(
                        "[FLOW] " + srcActionName + "." + srcPinName + " \u2192 " + tgtActionName + "." + tgtPinName);
            } else {
                System.out.println("[FLOW WARN] Unresolved: " + srcActionName + "." + srcPinName + " \u2192 "
                        + tgtActionName + "." + tgtPinName + " (srcId=" + srcId + ", tgtId=" + tgtId + ")");
            }
        }

        // ===================================================================
        // DEFERRED SUCCESSION FLOW RESOLUTION: SuccessionFlowUsage \u2192 ObjectFlow
        // (pinBindings)
        // ControlFlow \u8fb9\u5df2\u5728 PASS 4 \u4e4b\u524d\u6dfb\u52a0\u5230
        // logicalEdges
        // ===================================================================
        for (String[] dsflow : ctx.deferredSuccessionFlows) {
            String srcActionName = dsflow[0], srcPinName = dsflow[1];
            String tgtActionName = dsflow[2], tgtPinName = dsflow[3];
            String srcId = MainRunner.nameToIdMap.get(srcActionName);
            String tgtId = MainRunner.nameToIdMap.get(tgtActionName);
            if (srcId != null && tgtId != null) {
                // ObjectFlow: data from source pin to target pin
                ctx.pinBindings.add(new String[]{tgtId, tgtPinName, srcId, srcPinName});
                System.out.println("[SUCCESSION FLOW] " + srcActionName + "." + srcPinName + " \u2192 " + tgtActionName
                        + "." + tgtPinName + " (ObjectFlow)");
            } else {
                System.out.println("[SUCCESSION FLOW WARN] Unresolved: " + srcActionName + "." + srcPinName + " \u2192 "
                        + tgtActionName + "." + tgtPinName + " (srcId=" + srcId + ", tgtId=" + tgtId + ")");
            }
        }

        // ===================================================================
        // DATA FLOW PASS: \u521b\u5efa ObjectFlow \u8fde\u63a5 in/out Pin
        // \u4e4b\u95f4\u7684\u6570\u636e\u6d41
        // ===================================================================
        if (!ctx.isCalcModel && ctx.activity != null) {
            int objFlowCount = 0;
            for (String[] binding : ctx.pinBindings) {
                String targetActionId = binding[0];
                String targetParamName = binding[1];
                String sourceActionId = binding[2];
                String sourceParamName = binding[3];

                Map<String, OutputPin> srcMap = ctx.actionOutputPins.get(sourceActionId);
                Map<String, InputPin> tgtMap = ctx.actionInputPins.get(targetActionId);

                if (srcMap != null && tgtMap != null) {
                    OutputPin srcPin = srcMap.get(sourceParamName);
                    InputPin tgtPin = tgtMap.get(targetParamName);
                    if (srcPin != null && tgtPin != null) {
                        ObjectFlow of = ctx.factory.createObjectFlow();
                        of.setSource(srcPin);
                        of.setTarget(tgtPin);
                        ctx.activity.getEdges().add(of);
                        objFlowCount++;
                        System.out.println("[DATA FLOW] " + sourceParamName + " -> " + targetParamName);
                    }
                }
            }
            // ----- Bind Connector ObjectFlows (InputPin \u2194 InputPin) -----
            int bindFlowCount = 0;
            for (String[] bind : ctx.bindConnectorBindings) {
                String actionId1 = bind[0], pinName1 = bind[1];
                String actionId2 = bind[2], pinName2 = bind[3];
                // \u5c1d\u8bd5 InputPin \u2192 InputPin
                Map<String, InputPin> inMap1 = ctx.actionInputPins.get(actionId1);
                Map<String, InputPin> inMap2 = ctx.actionInputPins.get(actionId2);
                InputPin pin1 = (inMap1 != null) ? inMap1.get(pinName1) : null;
                InputPin pin2 = (inMap2 != null) ? inMap2.get(pinName2) : null;
                // \u4e5f\u5c1d\u8bd5 OutputPin \u2192 InputPin \u6216 InputPin \u2192 OutputPin
                if (pin1 == null) {
                    Map<String, OutputPin> outMap1 = ctx.actionOutputPins.get(actionId1);
                    OutputPin outPin1 = (outMap1 != null) ? outMap1.get(pinName1) : null;
                    if (outPin1 != null && pin2 != null) {
                        ObjectFlow of = ctx.factory.createObjectFlow();
                        of.setSource(outPin1);
                        of.setTarget(pin2);
                        ctx.activity.getEdges().add(of);
                        bindFlowCount++;
                        System.out.println("[BIND FLOW] " + pinName1 + " -> " + pinName2 + " (out->in)");
                        continue;
                    }
                }
                if (pin2 == null) {
                    Map<String, OutputPin> outMap2 = ctx.actionOutputPins.get(actionId2);
                    OutputPin outPin2 = (outMap2 != null) ? outMap2.get(pinName2) : null;
                    if (pin1 != null && outPin2 != null) {
                        ObjectFlow of = ctx.factory.createObjectFlow();
                        of.setSource(outPin2);
                        of.setTarget(pin1);
                        ctx.activity.getEdges().add(of);
                        bindFlowCount++;
                        System.out.println("[BIND FLOW] " + pinName2 + " -> " + pinName1 + " (out->in)");
                        continue;
                    }
                }
                if (pin1 != null && pin2 != null) {
                    ObjectFlow of = ctx.factory.createObjectFlow();
                    of.setSource(pin1);
                    of.setTarget(pin2);
                    ctx.activity.getEdges().add(of);
                    bindFlowCount++;
                    System.out.println("[BIND FLOW] " + pinName1 + " -> " + pinName2 + " (in->in)");
                } else {
                    System.out.println("[BIND FLOW WARN] Pin not found: " + pinName1 + "(" + (pin1 != null) + "), "
                            + pinName2 + "(" + (pin2 != null) + ")");
                }
            }
            objFlowCount += bindFlowCount;
            int totalInPins = 0, totalOutPins = 0;
            for (Map<String, InputPin> m : ctx.actionInputPins.values())
                totalInPins += m.size();
            for (Map<String, OutputPin> m : ctx.actionOutputPins.values())
                totalOutPins += m.size();
            System.out.println("[DATA FLOW PASS] " + objFlowCount + " ObjectFlows (" + bindFlowCount + " bind), "
                    + totalInPins + " InputPins, " + totalOutPins + " OutputPins, " + ctx.pinBindings.size()
                    + " bindings, " + ctx.bindConnectorBindings.size() + " bindConnectors");
        }

        // ===================================================================
        // PASS 5: \u63a7\u5236\u6d41\u88c5\u914d\u4e0e\u8f93\u51fa (\u4ec5\u975e calc
        // \u6a21\u578b)
        // ===================================================================
        if (!ctx.isCalcModel) {
            Set<String> safeDrawnFilters = new HashSet<>();
            for (MainRunner.EdgeData edge : ctx.finalRoutedEdges) {
                ActivityNode srcNode = MainRunner.umlNodes.get(edge.source);
                ActivityNode tgtNode = ctx.dynamicMerges.containsKey(edge.target)
                        ? ctx.dynamicMerges.get(edge.target)
                        : MainRunner.umlNodes.get(edge.target);

                if (srcNode != null && tgtNode != null && !edge.source.equals(edge.target)) {
                    String key = srcNode.hashCode() + " -> " + tgtNode.hashCode();
                    // \u6709 guard \u7684\u8fb9\u59cb\u7ec8\u521b\u5efa
                    // (\u4e0d\u53c2\u4e0e\u53bb\u91cd), \u65e0 guard \u7684\u8fb9\u624d\u53bb\u91cd
                    if (edge.guard != null || !safeDrawnFilters.contains(key)) {
                        ControlFlow flow = ctx.factory.createControlFlow();

                        // \u5e94\u7528 Guard \u6761\u4ef6
                        if (edge.guard != null) {
                            OpaqueExpression expr = ctx.factory.createOpaqueExpression();
                            expr.getBodies().add(edge.guard);
                            flow.setGuard(expr);
                        }

                        ctx.activity.getEdges().add(flow);
                        flow.setSource(srcNode);
                        flow.setTarget(tgtNode);
                        safeDrawnFilters.add(key);
                    }
                }
            }
        } // end if (!ctx.isCalcModel) \u2014 PASS 5 \u88c5\u914d\u7ed3\u675f

        String outputPath = xmiPath.replace(".sysmlx", ".uml");
        URI umlUri = URI.createFileURI(outputPath);
        Resource umlResource = ctx.resourceSet.createResource(umlUri);
        umlResource.getContents().add(ctx.umlModel);
        umlResource.save(null);

        // ===== UML \u6a21\u578b\u9a8c\u8bc1 =====
        System.out.println("\n========== UML \u6a21\u578b\u9a8c\u8bc1\u62a5\u544a ==========");
        System.out.println(
                "[\u9a8c\u8bc1] \u6d3b\u52a8\u56fe: " + (ctx.activity != null ? ctx.activity.getName() : "null"));
        System.out.println("[\u9a8c\u8bc1] UML \u8282\u70b9\u6570\u91cf: " + MainRunner.umlNodes.size());
        System.out.println("[\u9a8c\u8bc1] \u903b\u8f91\u8fb9\u6570\u91cf: "
                + (ctx.isCalcModel ? ctx.activity.getEdges().size() : ctx.finalRoutedEdges.size()));
        System.out.println("[\u9a8c\u8bc1] \u53d8\u91cf\u6570\u91cf: "
                + (ctx.activity != null ? ctx.activity.getVariables().size() : 0));

        // \u68c0\u67e5\u662f\u5426\u6240\u6709 ActionUsage
        // \u90fd\u6709\u5bf9\u5e94\u7684 UML \u8282\u70b9
        int actionCount = 0;
        int nodeCount = 0;
        for (ActivityNode node : MainRunner.umlNodes.values()) {
            if (!(node instanceof MergeNode) && !(node instanceof DecisionNode)) {
                nodeCount++;
            }
        }
        System.out.println(
                "[\u9a8c\u8bc1] \u521b\u5efa\u7684 ActivityNode \u6570\u91cf (\u4e0d\u542b\u63a7\u5236\u8282\u70b9): "
                        + nodeCount);
        System.out.println(
                "[\u9a8c\u8bc1] \u521b\u5efa\u7684\u63a7\u5236\u6d41\u6570\u91cf: " + ctx.activity.getEdges().size());

        // \u68c0\u67e5 WhileLoopActionUsage \u7684\u6761\u4ef6\u63d0\u53d6
        if (!MainRunner.whileLoopCondText.isEmpty()) {
            System.out.println("[\u9a8c\u8bc1] WhileLoop \u6761\u4ef6\u63d0\u53d6:");
            for (String loopId : MainRunner.whileLoopCondText.keySet()) {
                System.out.println("  - Loop " + loopId + ": condition=" + MainRunner.whileLoopCondText.get(loopId)
                        + ", isUntil=" + MainRunner.whileLoopIsUntil.getOrDefault(loopId, false));
            }
        }

        // \u68c0\u67e5 LoopActionUsage \u7684\u6761\u4ef6\u63d0\u53d6
        if (!MainRunner.loopConditionText.isEmpty()) {
            System.out.println("[\u9a8c\u8bc1] Loop \u6761\u4ef6\u63d0\u53d6:");
            for (String loopId : MainRunner.loopConditionText.keySet()) {
                System.out.println("  - Loop " + loopId + ": condition=" + MainRunner.loopConditionText.get(loopId));
            }
        }

        System.out.println("[\u9a8c\u8bc1] ===== \u9a8c\u8bc1\u5b8c\u6210 =====\n");

        System.out.println(
                "[COMPLETED] UML \u6a21\u578b\u751f\u6210\u5b8c\u6bd5\u3002\u9690\u5f0f Fork\u3001\u5faa\u73af\u7ed3\u6784\u4e0e Guard \u6761\u4ef6\u5df2\u6ce8\u5165\u3002");
    }
}
