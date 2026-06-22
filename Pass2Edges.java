
package sysml2uml;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.uml2.uml.*;

/**
 * PASS 2b-2f: 边构建
 * 包含以下子 Pass:
 * - PASS 2b: typed action def 顺序动作提取
 * - PASS 2c: decide 语法 TransitionUsage 延迟边解析 + .sysml succession 边
 * - PASS 2e: XMI-based succession edge building
 * - PASS 2d: .sysml 提取 fork/join 边
 * - PASS 2f: XMI-based succession for fork/join/merge
 */
class Pass2Edges {
    static void run(Resource resource, PipelineContext ctx) {
        pass2b_TypedActionDef(resource, ctx);

        pass2c_DecideTransition(resource, ctx);

        pass2e_XmiSuccession(resource, ctx);
        pass2d_ForkJoin(resource, ctx);
        pass2f_XmiForkJoinMerge(resource, ctx);
    }

    // ===================================================================
    // PASS 2c: decide 语法 TransitionUsage 延迟边解析 & .sysml succession
    // ===================================================================
    private static void pass2c_DecideTransition(Resource resource, PipelineContext ctx) {
        // 提前创建 Start/End 节点, 防止 PASS 4 丢弃 "END_NODE" 边
        if (ctx.lastDecideDecisionId != null) {
            if (!MainRunner.umlNodes.containsKey("START_NODE")) {
                InitialNode earlyStart = (InitialNode) ctx.activity.createOwnedNode("Start",
                        UMLPackage.Literals.INITIAL_NODE);
                MainRunner.umlNodes.put("START_NODE", earlyStart);
            }
            if (!MainRunner.umlNodes.containsKey("END_NODE")) {
                ActivityFinalNode earlyEnd = (ActivityFinalNode) ctx.activity.createOwnedNode("End",
                        UMLPackage.Literals.ACTIVITY_FINAL_NODE);
                MainRunner.umlNodes.put("END_NODE", earlyEnd);
            }
        }

        // TransitionUsage 延迟边解析
        System.out.println("[DEBUG] decideDeferredEdges (" + ctx.decideDeferredEdges.size() + " entries):");
        for (int di = 0; di < ctx.decideDeferredEdges.size(); di++) {
            String[] d = ctx.decideDeferredEdges.get(di);
            System.out.println("[DEBUG]   [" + di + "] src=" + d[0] + " tgt=" + d[1] + " guard=" + d[2]);
        }
        System.out.println("[DEBUG] allDecideDecisionIds: " + ctx.allDecideDecisionIds);
        for (String[] deferred : ctx.decideDeferredEdges) {
            String srcId = deferred[0];
            String tgtName = deferred[1];
            String guard = deferred[2];
            String tgtId = MainRunner.nameToIdMap.get(tgtName);
            if (tgtId != null && MainRunner.umlNodes.containsKey(tgtId)) {
                MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, tgtId, guard));
            } else {
                System.out.println("[DEBUG] TransitionUsage 延迟边未解析: " + tgtName + " (src=" + srcId + ")");
            }
        }

        // .sysml 提取 succession 边 (decide 结构)
        if (ctx.lastDecideDecisionId == null) {
            return;
        }
        try {
            System.out.println("[DEBUG] PASS 2c succession: entering");
            String text = ctx.sysmlText;

            System.out.println("[DEBUG] PASS 2c: text length=" + text.length());
            List<int[]> matchPositions = new ArrayList<>();

            Matcher mMerge2 = Pattern.compile("then\\s+merge\\s+(\\w+)\\s*;", Pattern.MULTILINE).matcher(text);
            while (mMerge2.find()) {
                matchPositions.add(new int[]{mMerge2.start(), 0, 0, mMerge2.start(), mMerge2.end()});
            }
            Matcher mAction = Pattern.compile("then\\s+action\\s+(\\w+)\\s*:", Pattern.MULTILINE).matcher(text);
            while (mAction.find()) {
                matchPositions.add(new int[]{mAction.start(), 1, 0, mAction.start(), mAction.end()});
            }
            Matcher mDecide = Pattern.compile("then\\s+decide\\s*;", Pattern.MULTILINE).matcher(text);
            while (mDecide.find()) {
                matchPositions.add(new int[]{mDecide.start(), 2, 0, mDecide.start(), mDecide.end()});
            }
            Matcher mPlain = Pattern.compile("then\\s+(\\w+)\\b[^;]*;", Pattern.MULTILINE).matcher(text);
            while (mPlain.find()) {
                String target = mPlain.group(1);
                if ("merge".equals(target) || "action".equals(target) || "decide".equals(target)) {
                    continue;
                }
                boolean overlap = false;
                for (int[] mp : matchPositions) {
                    if (mPlain.start() >= mp[3] && mPlain.start() < mp[4]) {
                        overlap = true;
                        break;
                    }
                }
                if (!overlap) {
                    matchPositions.add(new int[]{mPlain.start(), 3, 0, mPlain.start(), mPlain.end()});
                }
            }
            matchPositions.sort((a, b) -> Integer.compare(a[0], b[0]));

            List<String> mainChain = new ArrayList<>();
            for (int[] mp : matchPositions) {
                int pos = mp[0];
                int type = mp[1];
                int endPos = Math.min(pos + 200, text.length());
                String sub = text.substring(pos, endPos);
                if (type == 0) {
                    Matcher m = Pattern.compile("then\\s+merge\\s+(\\w+)\\s*;").matcher(sub);
                    if (m.find())
                        mainChain.add("merge:" + m.group(1));
                } else if (type == 1) {
                    Matcher m = Pattern.compile("then\\s+action\\s+(\\w+)\\s*:").matcher(sub);
                    if (m.find())
                        mainChain.add(m.group(1));
                } else if (type == 2) {
                    mainChain.add("decide");
                } else {
                    Matcher m = Pattern.compile("then\\s+(\\w+)\\b").matcher(sub);
                    if (m.find())
                        mainChain.add(m.group(1));
                }
            }
            System.out.println("[DEBUG] PASS 2c mainChain (before filter): " + mainChain);

            List<String> filtered = new ArrayList<>();
            boolean afterDecide = false;
            for (String item : mainChain) {
                if ("decide".equals(item)) {
                    afterDecide = true;
                    filtered.add(item);
                    continue;
                }
                if (afterDecide) {
                    String resolved = item.startsWith("merge:") ? item.substring(6) : item;
                    if (ctx.sysmlIfTargets.contains(resolved))
                        continue;
                    if (resolved.equals(ctx.sysmlMergeNodeName))
                        continue;
                    if ("done".equals(resolved))
                        continue;
                }
                filtered.add(item);
            }
            mainChain = filtered;
            System.out.println("[DEBUG] PASS 2c mainChain (after filter): " + mainChain);
            System.out.println("[DEBUG] sysmlMergeNodeName: " + ctx.sysmlMergeNodeName);
            System.out.println("[DEBUG] nameToIdMap keys: " + MainRunner.nameToIdMap.keySet());
            System.out.println("[DEBUG] sysmlIfTargets: " + ctx.sysmlIfTargets);

            String prevId = "START_NODE";
            String mergeNodeId = null;
            for (String item : mainChain) {
                String resolvedName = item;
                if (item.startsWith("merge:")) {
                    resolvedName = item.substring(6);
                    mergeNodeId = MainRunner.nameToIdMap.get(resolvedName);
                } else if ("decide".equals(item)) {
                    resolvedName = MainRunner.umlNodes.get(ctx.lastDecideDecisionId) != null
                            ? MainRunner.umlNodes.get(ctx.lastDecideDecisionId).getName()
                            : item;
                }
                String targetId = MainRunner.nameToIdMap.get(resolvedName);
                if (targetId != null && MainRunner.umlNodes.containsKey(targetId)) {
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, targetId));
                    prevId = targetId;
                }
            }

            if (ctx.sysmlIfTargets.size() >= 2) {
                String lastTarget = ctx.sysmlIfTargets.get(ctx.sysmlIfTargets.size() - 1);
                String lastTargetId = MainRunner.nameToIdMap.get(lastTarget);
                if (lastTargetId != null) {
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(lastTargetId, "END_NODE"));
                }
                for (int i = 0; i < ctx.sysmlIfTargets.size() - 1; i++) {
                    String ifTarget = ctx.sysmlIfTargets.get(i);
                    String loopPattern = ifTarget + "[\\s\\S]*?then\\s+"
                            + (ctx.sysmlMergeNodeName != null ? ctx.sysmlMergeNodeName : "\\w+") + "\\s*;";
                    boolean hasLoop = Pattern.compile(loopPattern).matcher(text).find();
                    System.out.println("[DEBUG] loop-back check: " + ifTarget + " → pattern=" + loopPattern
                            + " → found=" + hasLoop);
                    if (hasLoop) {
                        String ifTargetId = MainRunner.nameToIdMap.get(ifTarget);
                        if (ifTargetId != null && mergeNodeId != null) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(ifTargetId, mergeNodeId));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[ERROR] PASS 2c succession failed: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===================================================================
    // PASS 2b: typed action def 顺序动作提取 (ActionDefinition FM 链)
    // ===================================================================
    private static void pass2b_TypedActionDef(Resource resource, PipelineContext ctx) {
        for (String actDefId : new ArrayList<>(MainRunner.nameToIdMap.keySet())) {
            String actDefNodeId = MainRunner.nameToIdMap.get(actDefId);
            TreeIterator<EObject> iterator = resource.getAllContents();
            EObject actionDefObj = null;
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                if (obj instanceof org.omg.sysml.lang.sysml.Element) {
                    String objId = getElementIdSafely(obj);
                    if (objId != null && objId.equals(actDefNodeId)
                            && obj.eClass().getName().contains("ActionDefinition")) {
                        actionDefObj = obj;
                        break;
                    }
                }
            }
            if (actionDefObj == null) {
                continue;
            }
            for (EObject fm : actionDefObj.eContents()) {
                if (!fm.eClass().getName().equals("FeatureMembership")) {
                    continue;
                }
                for (EObject inner : fm.eContents()) {
                    String ic = inner.eClass().getName();
                    if ("SuccessionAsUsage".equals(ic)) {
                        continue;
                    }
                    if ("WhileLoopActionUsage".equals(ic)) {
                        String whileId = getElementIdSafely(inner);
                        String mergeId = MainRunner.whileLoopMergeIds.get(whileId);
                        if (mergeId != null) {
                            ctx.sequentialActionIds.add(mergeId);
                        }
                        continue;
                    }
                    String innerId = getElementIdSafely(inner);
                    if (innerId == null) {
                        continue;
                    }
                    String innerName = getDeclaredNameSafely(inner);
                    if (innerName != null && !innerName.isEmpty()) {
                        continue;
                    }
                    if (MainRunner.umlNodes.containsKey(innerId)) {
                        continue;
                    }
                    if ("AssignmentActionUsage".equals(ic) || "ActionUsage".equals(ic)) {
                        String expr = ExpressionUtils.extractAssignmentText(inner);
                        String nodeName = expr.isEmpty()
                                ? ic + "_" + innerId.substring(0, 8)
                                : expr.replaceAll("[^a-zA-Z0-9_]", "_");
                        String bodyText = expr.isEmpty() ? null : expr;
                        ActivityNode act;
                        if (bodyText != null && !bodyText.isEmpty() && bodyText.contains("=")) {
                            act = UmlHelper.createOpaqueActionForAssignment(ctx.activity, nodeName, bodyText);
                        } else {
                            act = UmlHelper.createCallBehaviorActionWithBody(ctx.activity, nodeName, bodyText,
                                    "SysMLv2");
                        }
                        MainRunner.umlNodes.put(innerId, act);
                        MainRunner.uuidToNameMap.put(innerId, nodeName);
                        ctx.sequentialActionIds.add(innerId);
                    }
                }
            }
        }
    }

    // ===================================================================
    // PASS 2e: XMI-based succession edge building (nested decide 结构补全)
    // ===================================================================
    private static void pass2e_XmiSuccession(Resource resource, PipelineContext ctx) {
        if (ctx.lastDecideDecisionId == null) {
            return;
        }
        MainRunner.logicalEdges.clear();
        ctx.decideDeferredEdges.clear();
        try {
            System.out.println("[DEBUG] PASS 2e: XMI-based succession edge building");
            EObject mainActionDef = null;
            TreeIterator<EObject> iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                if (obj.eClass().getName().contains("ActionDefinition")
                        && obj instanceof org.omg.sysml.lang.sysml.Element) {
                    boolean hasDecision = false;
                    for (java.util.Iterator<EObject> it2 = obj.eAllContents(); it2.hasNext();) {
                        if (it2.next().eClass().getName().equals("DecisionNode")) {
                            hasDecision = true;
                            break;
                        }
                    }
                    if (hasDecision) {
                        mainActionDef = obj;
                        break;
                    }
                }
            }
            if (mainActionDef != null) {
                String mainDefName = getDeclaredNameSafely(mainActionDef);
                System.out.println("[DEBUG] PASS 2e: main ActionDef = " + mainDefName);

                List<String> elementIds = new ArrayList<>();
                Map<String, String> elementType = new HashMap<>();
                Map<String, String[]> successionInfo = new HashMap<>();
                List<String[]> transitionEdges = new ArrayList<>();
                Map<String, Integer> elementOrder = new HashMap<>();
                int order = 0;

                for (EObject fm : mainActionDef.eContents()) {
                    if (!fm.eClass().getName().equals("FeatureMembership")) {
                        continue;
                    }
                    for (EObject inner : fm.eContents()) {
                        String ic = inner.eClass().getName();
                        String iId = getElementIdSafely(inner);
                        if (iId == null) {
                            continue;
                        }

                        if (ic.contains("ActionUsage") || "MergeNode".equals(ic) || "DecisionNode".equals(ic)) {
                            elementIds.add(iId);
                            elementType.put(iId, ic);
                            elementOrder.put(iId, order++);
                            System.out.println("[DEBUG] PASS 2e: node[" + (order - 1) + "] = " + ic + " id=" + iId);
                        } else if ("SuccessionAsUsage".equals(ic)) {
                            String targetId = null;
                            boolean isDone = false;
                            for (EObject efm : inner.eContents()) {
                                if (efm.eClass().getName().equals("EndFeatureMembership")) {
                                    for (EObject ref : efm.eContents()) {
                                        if (ref.eClass().getName().contains("ReferenceUsage")) {
                                            for (EObject rs : ref.eContents()) {
                                                if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                    try {
                                                        var rfFeat = rs.eClass()
                                                                .getEStructuralFeature("referencedFeature");
                                                        if (rfFeat != null) {
                                                            Object rfVal = rs.eGet(rfFeat);
                                                            if (rfVal instanceof EObject) {
                                                                targetId = getElementIdSafely((EObject) rfVal);
                                                            }
                                                        }
                                                    } catch (Exception ignored) {
                                                    }
                                                    for (EObject rsChild : rs.eContents()) {
                                                        if (rsChild.eClass().getName().equals("ReferenceSubsetting")) {
                                                            try {
                                                                var hrefFeat = rsChild.eClass()
                                                                        .getEStructuralFeature("referencedFeature");
                                                                if (hrefFeat != null) {
                                                                    Object hrefVal = rsChild.eGet(hrefFeat);
                                                                    if (hrefVal != null && hrefVal.toString()
                                                                            .contains("Actions.sysmlx")) {
                                                                        isDone = true;
                                                                    }
                                                                }
                                                            } catch (Exception ignored) {
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            String prevElemId = elementIds.isEmpty() ? null : elementIds.get(elementIds.size() - 1);
                            if (targetId != null) {
                                successionInfo.put(iId, new String[]{"explicit", targetId, prevElemId});
                            } else if (isDone) {
                                successionInfo.put(iId, new String[]{"terminal", null, prevElemId});
                            } else {
                                successionInfo.put(iId, new String[]{"implicit", null, prevElemId});
                            }
                        } else if ("TransitionUsage".equals(ic)) {
                            String srcId = null;
                            String guardText = null;
                            String tgtId = null;
                            for (EObject child : inner.eContents()) {
                                String ccn = child.eClass().getName();
                                if ("Membership".equals(ccn)) {
                                    try {
                                        var meFeat = child.eClass().getEStructuralFeature("memberElement");
                                        if (meFeat != null) {
                                            Object meVal = child.eGet(meFeat);
                                            if (meVal instanceof EObject) {
                                                srcId = getElementIdSafely((EObject) meVal);
                                            }
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                                if ("TransitionFeatureMembership".equals(ccn)) {
                                    for (EObject expr : child.eContents()) {
                                        if (expr.eClass().getName().contains("OperatorExpression")) {
                                            String g = ExpressionUtils.buildExpressionText(expr);
                                            if (g != null && !g.isEmpty() && ExpressionUtils.isValidExpression(g)) {
                                                guardText = g;
                                            }
                                        }
                                    }
                                }
                                if ("OwningMembership".equals(ccn)) {
                                    for (EObject succ : child.eContents()) {
                                        if (succ.eClass().getName().equals("SuccessionAsUsage")) {
                                            for (EObject efm : succ.eContents()) {
                                                if (efm.eClass().getName().equals("EndFeatureMembership")) {
                                                    for (EObject ref : efm.eContents()) {
                                                        if (ref.eClass().getName().contains("ReferenceUsage")) {
                                                            for (EObject rs : ref.eContents()) {
                                                                if (rs.eClass().getName()
                                                                        .equals("ReferenceSubsetting")) {
                                                                    try {
                                                                        var rfFeat = rs.eClass().getEStructuralFeature(
                                                                                "referencedFeature");
                                                                        if (rfFeat != null) {
                                                                            Object rfVal = rs.eGet(rfFeat);
                                                                            if (rfVal instanceof EObject) {
                                                                                tgtId = getElementIdSafely(
                                                                                        (EObject) rfVal);
                                                                            }
                                                                        }
                                                                    } catch (Exception ignored) {
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (srcId != null && tgtId != null) {
                                transitionEdges.add(new String[]{srcId, tgtId, guardText});
                                System.out.println("[DEBUG] PASS 2e: transition " + srcId + " → " + tgtId + " ["
                                        + guardText + "]");
                            }
                        }
                    }
                }

                List<String> originalElementIds = new ArrayList<>(elementIds);
                List<String> dnIds = new ArrayList<>();
                List<String> nonDnIds = new ArrayList<>();
                for (String eid : elementIds) {
                    if ("DecisionNode".equals(elementType.get(eid)))
                        dnIds.add(eid);
                    else
                        nonDnIds.add(eid);
                }
                elementIds.clear();
                elementIds.addAll(dnIds);
                elementIds.addAll(nonDnIds);
                for (int oi = 0; oi < elementIds.size(); oi++) {
                    elementOrder.put(elementIds.get(oi), oi);
                }
                System.out.println("[DEBUG] PASS 2e: reordered elementIds (DN first)");

                if (!elementIds.isEmpty()) {
                    String firstId = elementIds.get(0);
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData("START_NODE", firstId));
                    System.out.println("[DEBUG] PASS 2e: START_NODE → " + firstId);
                }

                Set<String> transitionTargets = new HashSet<>();
                for (String[] te : transitionEdges) {
                    transitionTargets.add(te[1]);
                }

                for (int i = 0; i < elementIds.size() - 1; i++) {
                    String srcId = elementIds.get(i);
                    String tgtId = elementIds.get(i + 1);
                    System.out.println("[DEBUG] PASS 2e: seq-check [" + i + "] src=" + srcId + "("
                            + elementType.get(srcId) + ") tgt=" + tgtId + "(" + elementType.get(tgtId) + ")");
                    if ("DecisionNode".equals(elementType.get(srcId)))
                        continue;
                    if ("DecisionNode".equals(elementType.get(tgtId)))
                        continue;
                    if (transitionTargets.contains(srcId) && transitionTargets.contains(tgtId)) {
                        continue;
                    }
                    if (transitionTargets.contains(tgtId))
                        continue;
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, tgtId));
                    System.out.println("[DEBUG] PASS 2e: seq " + srcId + " → " + tgtId);
                }

                for (Map.Entry<String, String[]> entry : successionInfo.entrySet()) {
                    String[] info = entry.getValue();
                    String sType = info[0];
                    String prevId = info[2];
                    if ("explicit".equals(sType) && prevId != null) {
                        String targetId = info[1];
                        if (MainRunner.umlNodes.containsKey(targetId)) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, targetId));
                            System.out.println("[DEBUG] PASS 2e: explicit " + prevId + " → " + targetId);
                        }
                    } else if ("terminal".equals(sType) && prevId != null) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, "END_NODE"));
                        System.out.println("[DEBUG] PASS 2e: terminal " + prevId + " → END_NODE");
                    }
                }

                try {
                    Matcher ifElseM = Pattern.compile("(?:if\\s+(.+?)\\s+then\\b)|(?:else\\s+\\w+)")
                            .matcher(ctx.sysmlText);
                    List<String> allGuards = new ArrayList<>();
                    while (ifElseM.find()) {
                        if (ifElseM.group(1) != null) {
                            allGuards.add(ifElseM.group(1).trim());
                        } else {
                            allGuards.add(null);
                        }
                    }
                    int guardIdx = 0;
                    for (String[] te : transitionEdges) {
                        if (te[2] == null && guardIdx < allGuards.size()) {
                            String g = allGuards.get(guardIdx);
                            if (g != null) {
                                te[2] = g;
                                System.out.println("[DEBUG] PASS 2e: guard fallback [" + guardIdx + "] " + te[0] + "→"
                                        + te[1] + " guard=" + te[2]);
                            } else {
                                System.out.println("[DEBUG] PASS 2e: guard fallback [" + guardIdx + "] " + te[0] + "→"
                                        + te[1] + " (else branch, guard=null)");
                            }
                        }
                        guardIdx++;
                    }
                } catch (Exception ignored) {
                }

                for (String[] te : transitionEdges) {
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(te[0], te[1], te[2]));
                }

                Set<String> processedMergeTargets = new HashSet<>();
                for (int i = 0; i < elementIds.size(); i++) {
                    String dnId = elementIds.get(i);
                    if (!"DecisionNode".equals(elementType.get(dnId)))
                        continue;

                    List<String> dnTargets = new ArrayList<>();
                    for (String[] te : transitionEdges) {
                        if (te[0].equals(dnId) && elementOrder.containsKey(te[1])) {
                            dnTargets.add(te[1]);
                        }
                    }
                    if (dnTargets.isEmpty())
                        continue;

                    String nextId = null;
                    int dnPos = elementIds.indexOf(dnId);
                    for (int j = dnPos + 1; j < elementIds.size(); j++) {
                        String candId = elementIds.get(j);
                        if ("DecisionNode".equals(elementType.get(candId)) && !dnTargets.contains(candId)) {
                            nextId = candId;
                            break;
                        }
                    }
                    if (nextId == null) {
                        int maxTargetOrder = -1;
                        for (String t : dnTargets) {
                            int o = elementOrder.getOrDefault(t, -1);
                            if (o > maxTargetOrder) {
                                maxTargetOrder = o;
                            }
                        }
                        for (String eid : elementIds) {
                            if (elementOrder.getOrDefault(eid, -1) == maxTargetOrder + 1) {
                                nextId = eid;
                                break;
                            }
                        }
                    }
                    if (nextId == null) {
                        continue;
                    }

                    if (dnTargets.contains(nextId))
                        continue;

                    Set<String> dnNodesWithOutgoing = new HashSet<>();
                    for (MainRunner.EdgeData e : MainRunner.logicalEdges) {
                        dnNodesWithOutgoing.add(e.source);
                    }

                    List<String> endpoints = new ArrayList<>();
                    for (String t : dnTargets) {
                        if (!dnNodesWithOutgoing.contains(t)) {
                            endpoints.add(t);
                        }
                    }
                    if (endpoints.isEmpty())
                        continue;

                    if (processedMergeTargets.contains(nextId)) {
                        for (String ep : endpoints) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(ep, nextId));
                            System.out.println("[DEBUG] PASS 2e: merge-ext " + ep + " → " + nextId);
                        }
                        continue;
                    }

                    String mergeId = dnId + "_autoMerge";
                    String mergeName = "Merge_" + (MainRunner.uuidToNameMap.getOrDefault(dnId, dnId));
                    MergeNode mergeNode = (MergeNode) ctx.activity.createOwnedNode(mergeName,
                            UMLPackage.Literals.MERGE_NODE);
                    MainRunner.umlNodes.put(mergeId, mergeNode);
                    System.out.println("[DEBUG] PASS 2e: created MergeNode " + mergeName + " id=" + mergeId);

                    for (String ep : endpoints) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(ep, mergeId));
                        System.out.println("[DEBUG] PASS 2e: merge " + ep + " → " + mergeId);
                    }

                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, nextId));
                    System.out.println("[DEBUG] PASS 2e: merge-next " + mergeId + " → " + nextId);

                    processedMergeTargets.add(nextId);
                }

                Set<String> nodesWithOutgoing = new HashSet<>();
                for (MainRunner.EdgeData e : MainRunner.logicalEdges) {
                    nodesWithOutgoing.add(e.source);
                }
                for (int oi = 0; oi < originalElementIds.size(); oi++) {
                    String elemId = originalElementIds.get(oi);
                    if (!nodesWithOutgoing.contains(elemId)) {
                        String type = elementType.get(elemId);
                        if (type != null && type.contains("ActionUsage")) {
                            boolean hasSubsequentDN = false;
                            for (int j = oi + 1; j < originalElementIds.size(); j++) {
                                if ("DecisionNode".equals(elementType.get(originalElementIds.get(j)))) {
                                    hasSubsequentDN = true;
                                    break;
                                }
                            }
                            if (!hasSubsequentDN) {
                                MainRunner.logicalEdges.add(new MainRunner.EdgeData(elemId, "END_NODE"));
                                System.out.println("[DEBUG] PASS 2e: orphan " + elemId + " → END_NODE");
                            } else {
                                System.out.println("[DEBUG] PASS 2e: skip orphan " + elemId
                                        + " (subsequent DN in original order)");
                            }
                        }
                    }
                }

                System.out.println("[DEBUG] PASS 2e: total logicalEdges = " + MainRunner.logicalEdges.size());
            } else {
                System.out.println("[DEBUG] PASS 2e: no main ActionDef found");
            }
        } catch (Exception e) {
            System.out.println("[ERROR] PASS 2e failed: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===================================================================
    // PASS 2d: .sysml 提取 fork/join 边
    // ===================================================================
    private static void pass2d_ForkJoin(Resource resource, PipelineContext ctx) {
        boolean hasForkJoin = MainRunner.umlNodes.values().stream().anyMatch(n -> n instanceof ForkNode);
        if (!hasForkJoin || ctx.lastDecideDecisionId != null) {
            return;
        }
        try {
            System.out.println("[DEBUG] PASS 2d fork/join: entering");
            String text = ctx.sysmlText;

            List<String> preForkChain = new ArrayList<>();
            String[] lines = text.split("\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.matches("then\\s+fork\\s*;"))
                    break;
                Matcher m = Pattern.compile("^action\\s+(\\w+)\\s*[;:]").matcher(trimmed);
                if (m.find())
                    preForkChain.add(m.group(1));
            }
            System.out.println("[DEBUG] PASS 2d preForkChain: " + preForkChain);

            List<String> forkTargets = new ArrayList<>();
            boolean inFork = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.matches("then\\s+fork\\s*;")) {
                    inFork = true;
                    continue;
                }
                if (inFork) {
                    Matcher m = Pattern.compile("^then\\s+(\\w+)\\s*;").matcher(trimmed);
                    if (m.find()) {
                        forkTargets.add(m.group(1));
                    } else {
                        inFork = false;
                    }
                }
            }
            System.out.println("[DEBUG] PASS 2d forkTargets: " + forkTargets);

            String joinName = null;
            for (String key : MainRunner.nameToIdMap.keySet()) {
                String nid = MainRunner.nameToIdMap.get(key);
                if (MainRunner.umlNodes.containsKey(nid) && MainRunner.umlNodes.get(nid) instanceof JoinNode) {
                    joinName = key;
                    break;
                }
            }
            System.out.println("[DEBUG] PASS 2d joinName: " + joinName);

            List<String> postJoinChain = new ArrayList<>();
            boolean afterJoin = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.matches("join\\s+\\w+\\s*;")) {
                    afterJoin = true;
                    continue;
                }
                if (afterJoin) {
                    Matcher m = Pattern.compile("^then\\s+(\\w+)\\s*;").matcher(trimmed);
                    if (m.find()) {
                        String target = m.group(1);
                        if (!"done".equals(target)) {
                            postJoinChain.add(target);
                        }
                    } else {
                        afterJoin = false;
                    }
                }
            }
            System.out.println("[DEBUG] PASS 2d postJoinChain: " + postJoinChain);

            String forkNodeId = null;
            for (Map.Entry<String, org.eclipse.uml2.uml.ActivityNode> e : MainRunner.umlNodes.entrySet()) {
                if (e.getValue() instanceof ForkNode) {
                    forkNodeId = e.getKey();
                    break;
                }
            }
            String joinNodeId = (joinName != null) ? MainRunner.nameToIdMap.get(joinName) : null;

            String prevId = "START_NODE";
            for (String action : preForkChain) {
                String actionId = MainRunner.nameToIdMap.get(action);
                if (actionId != null && MainRunner.umlNodes.containsKey(actionId)) {
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, actionId));
                    prevId = actionId;
                }
            }
            if (forkNodeId != null) {
                MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, forkNodeId));
            }
            for (String target : forkTargets) {
                String targetId = MainRunner.nameToIdMap.get(target);
                if (targetId != null && forkNodeId != null) {
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(forkNodeId, targetId));
                }
            }
            if (joinNodeId != null) {
                for (String target : forkTargets) {
                    String targetId = MainRunner.nameToIdMap.get(target);
                    if (targetId != null) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(targetId, joinNodeId));
                    }
                }
            }
            prevId = joinNodeId;
            for (String action : postJoinChain) {
                String actionId = MainRunner.nameToIdMap.get(action);
                if (actionId != null && prevId != null) {
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, actionId));
                    prevId = actionId;
                }
            }
            if (postJoinChain.isEmpty() && joinNodeId != null) {
                MainRunner.logicalEdges.add(new MainRunner.EdgeData(joinNodeId, "END_NODE"));
            }

            System.out.println("[DEBUG] PASS 2d edges added: " + MainRunner.logicalEdges.size());
        } catch (Exception e) {
            System.out.println("[ERROR] PASS 2d fork/join failed: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===================================================================
    // PASS 2f: XMI-based succession for fork/join/merge structures
    // ===================================================================
    private static void pass2f_XmiForkJoinMerge(Resource resource, PipelineContext ctx) {
        boolean hasForkJoin = MainRunner.umlNodes.values().stream().anyMatch(n -> n instanceof ForkNode);
        boolean hasMergeJoin = MainRunner.umlNodes.values().stream()
                .anyMatch(n -> n instanceof JoinNode || n instanceof MergeNode);
        boolean hasLoops = !MainRunner.whileLoopCondText.isEmpty() || !MainRunner.loopBodyActions.isEmpty();
        if ((hasForkJoin || hasMergeJoin) && ctx.lastDecideDecisionId == null && !hasLoops) {
            try {
                System.out.println("[DEBUG] PASS 2f: XMI-based fork/join/merge succession");
                List<MainRunner.EdgeData> edgesBefore2f = new ArrayList<>(MainRunner.logicalEdges);

                EObject mainActionDef2f = null;
                TreeIterator<EObject> iterator = resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject obj = iterator.next();
                    String objCn = obj.eClass().getName();
                    if ((objCn.contains("ActionDefinition") || objCn.contains("ActionUsage"))
                            && obj instanceof org.omg.sysml.lang.sysml.Element) {
                        boolean hasFJ = false;
                        for (java.util.Iterator<EObject> it2 = obj.eAllContents(); it2.hasNext();) {
                            String cn = it2.next().eClass().getName();
                            if ("ForkNode".equals(cn) || "JoinNode".equals(cn) || "MergeNode".equals(cn)) {
                                hasFJ = true;
                                break;
                            }
                        }
                        if (hasFJ) {
                            if (mainActionDef2f == null) {
                                mainActionDef2f = obj;
                            } else {
                                int curCount = 0, newCount = 0;
                                for (EObject c : mainActionDef2f.eContents())
                                    if (c.eClass().getName().equals("FeatureMembership"))
                                        curCount++;
                                for (EObject c : obj.eContents())
                                    if (c.eClass().getName().equals("FeatureMembership"))
                                        newCount++;
                                if (newCount > curCount) {
                                    mainActionDef2f = obj;
                                }
                            }
                        }
                    }
                }

                if (mainActionDef2f != null) {
                    MainRunner.logicalEdges.clear();
                    List<String> fm2fIds = new ArrayList<>();
                    Map<String, String> fm2fType = new HashMap<>();
                    List<String[]> successionInfo2f = new ArrayList<>();

                    for (EObject fm : mainActionDef2f.eContents()) {
                        if (!fm.eClass().getName().equals("FeatureMembership")) {
                            continue;
                        }
                        for (EObject inner : fm.eContents()) {
                            String ic = inner.eClass().getName();
                            String iId = getElementIdSafely(inner);
                            if (iId == null) {
                                continue;
                            }

                            if (ic.contains("ActionUsage") || "ForkNode".equals(ic) || "JoinNode".equals(ic)
                                    || "MergeNode".equals(ic)) {
                                fm2fIds.add(iId);
                                fm2fType.put(iId, ic);
                                System.out.println("[DEBUG] PASS 2f: node = " + ic + " id=" + iId);
                            } else if ("SuccessionAsUsage".equals(ic)) {
                                String targetId = null;
                                boolean isDone = false;
                                int endIdx = 0;
                                for (EObject efm : inner.eContents()) {
                                    if (efm.eClass().getName().equals("EndFeatureMembership")) {
                                        if (endIdx == 1) {
                                            for (EObject ref : efm.eContents()) {
                                                if (ref.eClass().getName().contains("ReferenceUsage")) {
                                                    for (EObject rs : ref.eContents()) {
                                                        if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                            try {
                                                                var rfFeat = rs.eClass()
                                                                        .getEStructuralFeature("referencedFeature");
                                                                if (rfFeat != null) {
                                                                    Object rfVal = rs.eGet(rfFeat);
                                                                    if (rfVal instanceof EObject) {
                                                                        EObject resolved = EcoreUtil
                                                                                .resolve((EObject) rfVal, rs);
                                                                        if (resolved instanceof org.omg.sysml.lang.sysml.Element) {
                                                                            targetId = getElementIdSafely(resolved);
                                                                        }
                                                                    }
                                                                    if (rfVal != null && rfVal.toString()
                                                                            .contains("Actions.sysmlx")) {
                                                                        isDone = true;
                                                                    }
                                                                }
                                                            } catch (Exception ignored) {
                                                            }
                                                            for (EObject rsChild : rs.eContents()) {
                                                                if (rsChild.eClass().getName()
                                                                        .equals("ReferenceSubsetting")) {
                                                                    try {
                                                                        var hrefFeat = rsChild.eClass()
                                                                                .getEStructuralFeature(
                                                                                        "referencedFeature");
                                                                        if (hrefFeat != null) {
                                                                            Object hrefVal = rsChild.eGet(hrefFeat);
                                                                            if (hrefVal != null && hrefVal.toString()
                                                                                    .contains("Actions.sysmlx")) {
                                                                                isDone = true;
                                                                            }
                                                                        }
                                                                    } catch (Exception ignored) {
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        endIdx++;
                                    }
                                }
                                if (isDone) {
                                    targetId = "END_NODE";
                                }
                                if (targetId == null) {
                                    boolean foundSelf = false;
                                    for (EObject fm2 : mainActionDef2f.eContents()) {
                                        if (!fm2.eClass().getName().equals("FeatureMembership"))
                                            continue;
                                        if (fm2 == fm) {
                                            foundSelf = true;
                                            continue;
                                        }
                                        if (!foundSelf) {
                                            continue;
                                        }
                                        for (EObject inner2 : fm2.eContents()) {
                                            String ic2 = inner2.eClass().getName();
                                            if (ic2.contains("ActionUsage") || "ForkNode".equals(ic2)
                                                    || "JoinNode".equals(ic2) || "MergeNode".equals(ic2)) {
                                                targetId = getElementIdSafely(inner2);
                                                break;
                                            }
                                        }
                                        if (targetId != null) {
                                            break;
                                        }
                                    }
                                }
                                successionInfo2f.add(new String[]{null, targetId, iId});
                            }
                        }
                    }

                    String currentNodeId = null;
                    int sauIdx = 0;
                    for (EObject fm : mainActionDef2f.eContents()) {
                        if (!fm.eClass().getName().equals("FeatureMembership")) {
                            continue;
                        }
                        for (EObject inner : fm.eContents()) {
                            String ic = inner.eClass().getName();
                            if (ic.contains("ActionUsage") || "ForkNode".equals(ic) || "JoinNode".equals(ic)
                                    || "MergeNode".equals(ic)) {
                                currentNodeId = getElementIdSafely(inner);
                            } else if ("SuccessionAsUsage".equals(ic)) {
                                if (sauIdx < successionInfo2f.size()) {
                                    successionInfo2f.get(sauIdx)[0] = currentNodeId;
                                    sauIdx++;
                                }
                            }
                        }
                    }

                    if (!fm2fIds.isEmpty()) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData("START_NODE", fm2fIds.get(0)));
                        System.out.println("[DEBUG] PASS 2f: START_NODE → " + fm2fIds.get(0));
                    }

                    for (String[] s : successionInfo2f) {
                        String src = s[0];
                        String tgt = s[1];
                        if (src != null && tgt != null) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(src, tgt));
                            System.out.println("[DEBUG] PASS 2f: " + src + " → " + tgt);
                        }
                    }

                    Set<String> nodes2fWithOut = new HashSet<>();
                    for (MainRunner.EdgeData e : MainRunner.logicalEdges) {
                        nodes2fWithOut.add(e.source);
                    }
                    for (String nodeId : fm2fIds) {
                        if (!nodes2fWithOut.contains(nodeId)) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(nodeId, "END_NODE"));
                            System.out.println("[DEBUG] PASS 2f: orphan " + nodeId + " → END_NODE");
                        }
                    }

                    System.out.println("[DEBUG] PASS 2f: total edges = " + MainRunner.logicalEdges.size());
                } else {
                    System.out.println(
                            "[DEBUG] PASS 2f: no main ActionDef/ActionUsage container found, restoring PASS 2d edges");
                    MainRunner.logicalEdges.clear();
                    MainRunner.logicalEdges.addAll(edgesBefore2f);
                }
            } catch (Exception e) {
                System.out.println("[ERROR] PASS 2f failed: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ===================================================================
    // Utility methods
    // ===================================================================

    /** Safely get element ID from an EObject, returning null on failure. */
    private static String getElementIdSafely(EObject obj) {
        try {
            return ((org.omg.sysml.lang.sysml.Element) obj).getElementId();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Safely get declared name from an EObject, returning null on failure. */
    private static String getDeclaredNameSafely(EObject obj) {
        try {
            return ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
        } catch (Exception ignored) {
            return null;
        }
    }
}
