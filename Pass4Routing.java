package sysml2uml;

import java.util.*;
import java.util.stream.*;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.uml2.uml.*;

/**
 * PASS 4: 控制流拓扑路由重构
 * 
 * - Degree computation + edge filtering
 * - Placeholder conversion (CallBehaviorAction → MergeNode/DecisionNode)
 * - Implicit Fork insertion (outDeg > 1)
 * - Loop wiring (LoopActionUsage)
 * - PASS 5 start: Start/End creation
 * - Loop decision exits (while/until routing)
 * - Sequential chain building
 * - Dynamic merge + endpoint fallback
 */
class Pass4Routing {
    static void run(Resource resource, PipelineContext ctx) {
    // ===================================================================
    // PASS 4: 控制流拓扑路由重构
    // ===================================================================
    Map<String, Integer> inDeg = new HashMap<>();
    Map<String, Integer> outDeg = new HashMap<>();

    for (MainRunner.EdgeData edge : MainRunner.logicalEdges) {
        if (MainRunner.umlNodes.containsKey(edge.target) && !edge.source.equals(edge.target)) {
            ctx.finalRoutedEdges.add(new MainRunner.EdgeData(edge.source, edge.target, edge.guard));
            outDeg.put(edge.source, outDeg.getOrDefault(edge.source, 0) + 1);
            inDeg.put(edge.target, inDeg.getOrDefault(edge.target, 0) + 1);
        }
    }

    // [DEBUG] PASS 4: 打印 MainRunner.logicalEdges 和 outDeg (Fork 诊断)
    System.out.println("[DEBUG] PASS 4: MainRunner.logicalEdges count = " + MainRunner.logicalEdges.size());
    for (MainRunner.EdgeData e : MainRunner.logicalEdges) {
        String srcName = MainRunner.umlNodes.containsKey(e.source) ? MainRunner.umlNodes.get(e.source).getName() : e.source;
        String tgtName = MainRunner.umlNodes.containsKey(e.target) ? MainRunner.umlNodes.get(e.target).getName() : e.target;
        System.out.println("[DEBUG] PASS 4: edge " + srcName + " → " + tgtName + " (guard=" + e.guard + ")");
    }
    System.out.println("[DEBUG] PASS 4: outDeg = " + outDeg);

    // decide 结构也运行 PASS 4: 隐式 Fork 插入、占位符转换、循环布线
    // 对于已有的 Merge/Decision 节点, 占位符转换是 no-op
    {

    // 占位符转换: 根据拓扑将 CallBehaviorAction 占位符转为正确控制节点
    for (String nodeId : new ArrayList<>(ctx.placeholderNodeIds)) {
        ActivityNode node = MainRunner.umlNodes.get(nodeId);
        if (node instanceof CallBehaviorAction && node.getName() != null) {
            int indeg = inDeg.getOrDefault(nodeId, 0);
            int outdeg = outDeg.getOrDefault(nodeId, 0);
            if (indeg > 1 && outdeg == 1) {
                ctx.activity.getOwnedNodes().remove(node);
                MergeNode m = (MergeNode) ctx.activity.createOwnedNode(node.getName(), UMLPackage.Literals.MERGE_NODE);
                MainRunner.umlNodes.put(nodeId, m);
            } else if (indeg <= 1 && outdeg > 1) {
                ctx.activity.getOwnedNodes().remove(node);
                DecisionNode d = (DecisionNode) ctx.activity.createOwnedNode(node.getName(), UMLPackage.Literals.DECISION_NODE);
                MainRunner.umlNodes.put(nodeId, d);
            } else if (indeg > 0 && outdeg == 0 && node.getName().toLowerCase().contains("decision")) {
                ctx.activity.getOwnedNodes().remove(node);
                DecisionNode d = (DecisionNode) ctx.activity.createOwnedNode(node.getName(), UMLPackage.Literals.DECISION_NODE);
                MainRunner.umlNodes.put(nodeId, d);
            }
        }
    }

    // 隐式 Fork: 任何非控制节点出度 > 1 时自动插入 ForkNode
    Map<String, String> forkInserted = new HashMap<>();
    List<MainRunner.EdgeData> edgesWithFork = new ArrayList<>();
    for (MainRunner.EdgeData edge : ctx.finalRoutedEdges) {
        int srcOutDeg = outDeg.getOrDefault(edge.source, 0);
        ActivityNode srcUmlNode = MainRunner.umlNodes.get(edge.source);
        if ((srcOutDeg > 1 || forkInserted.containsKey(edge.source)) && !(srcUmlNode instanceof DecisionNode) && !(srcUmlNode instanceof ForkNode) && !(srcUmlNode instanceof JoinNode)) {
            String forkId = forkInserted.get(edge.source);
            if (forkId == null) {
                forkId = edge.source + "_implicitFork";
                ForkNode fork = (ForkNode) ctx.activity.createOwnedNode("Fork_" + edge.source, UMLPackage.Literals.FORK_NODE);
                MainRunner.umlNodes.put(forkId, fork);
                forkInserted.put(edge.source, forkId);
                ctx.implicitForks.put(edge.source, forkId);
                // 源节点 → ForkNode
                edgesWithFork.add(new MainRunner.EdgeData(edge.source, forkId));
                inDeg.put(forkId, 1);
                outDeg.put(forkId, 0);
            }
            // ForkNode → 原目标
            edgesWithFork.add(new MainRunner.EdgeData(forkId, edge.target, edge.guard));
            outDeg.put(forkId, outDeg.getOrDefault(forkId, 0) + 1);
        } else {
            edgesWithFork.add(edge);
        }
    }
    ctx.finalRoutedEdges = edgesWithFork;

    // 循环结构接线 (flat 模型 LoopActionUsage): body 最后一个 action → Decision, Decision(true) → Merge
    for (String loopId : MainRunner.loopStartMerge.keySet()) {
        String mergeId = MainRunner.loopStartMerge.get(loopId);
        String decisionId = MainRunner.loopEndDecision.get(loopId);
        String condition = MainRunner.loopConditionText.get(loopId);
        List<String> bodyActions = MainRunner.loopBodyActions.get(loopId);
        if (bodyActions == null) continue;

        for (String actionId : bodyActions) {
            boolean hasOutToBody = false;
            for (MainRunner.EdgeData e : ctx.finalRoutedEdges) {
                if (e.source.equals(actionId) && bodyActions.contains(e.target)) {
                    hasOutToBody = true;
                    break;
                }
            }
            if (!hasOutToBody && MainRunner.umlNodes.containsKey(actionId)) {
                ctx.finalRoutedEdges.add(new MainRunner.EdgeData(actionId, decisionId));
                outDeg.put(actionId, outDeg.getOrDefault(actionId, 0) + 1);
                inDeg.put(decisionId, inDeg.getOrDefault(decisionId, 0) + 1);
            }
        }
        // DecisionNode(true) → MergeNode (循环回边)
        String backGuard = MainRunner.loopIsUntilIds.contains(loopId) ? "else" : condition;
        ctx.finalRoutedEdges.add(new MainRunner.EdgeData(decisionId, mergeId, backGuard));
        outDeg.put(decisionId, outDeg.getOrDefault(decisionId, 0) + 1);
        inDeg.put(mergeId, inDeg.getOrDefault(mergeId, 0) + 1);
    }

    } // end if (lastDecideDecisionId == null) — PASS 4 跳过 decide

    // PASS 5: 创建 Start/End (如果未在 PASS 2c 前提前创建)
    InitialNode startNode;
    ActivityFinalNode endNode;
    if (MainRunner.umlNodes.containsKey("START_NODE")) {
        startNode = (InitialNode) MainRunner.umlNodes.get("START_NODE");
    } else {
        startNode = (InitialNode) ctx.activity.createOwnedNode("Start", UMLPackage.Literals.INITIAL_NODE);
        MainRunner.umlNodes.put("START_NODE", startNode);
    }
    if (MainRunner.umlNodes.containsKey("END_NODE")) {
        endNode = (ActivityFinalNode) MainRunner.umlNodes.get("END_NODE");
    } else {
        endNode = (ActivityFinalNode) ctx.activity.createOwnedNode("End", UMLPackage.Literals.ACTIVITY_FINAL_NODE);
        MainRunner.umlNodes.put("END_NODE", endNode);
    }

    // 循环决策出口: DecisionNode(false) → End, Start → MergeNode (入口边)
    for (String loopId : MainRunner.loopStartMerge.keySet()) {
        String mergeId = MainRunner.loopStartMerge.get(loopId);
        String decisionId = MainRunner.loopEndDecision.get(loopId);
        if (decisionId != null && MainRunner.umlNodes.containsKey(decisionId)) {
            String exitGuard = MainRunner.loopIsUntilIds.contains(loopId) ? MainRunner.loopConditionText.get(loopId) : "else";
            ctx.finalRoutedEdges.add(new MainRunner.EdgeData(decisionId, "END_NODE", exitGuard));
            outDeg.put(decisionId, outDeg.getOrDefault(decisionId, 0) + 1);
            inDeg.put("END_NODE", inDeg.getOrDefault("END_NODE", 0) + 1);
        }
        if (mergeId != null && MainRunner.umlNodes.containsKey(mergeId)) {
            ctx.finalRoutedEdges.add(new MainRunner.EdgeData("START_NODE", mergeId));
            outDeg.put("START_NODE", outDeg.getOrDefault("START_NODE", 0) + 1);
            inDeg.put(mergeId, inDeg.getOrDefault(mergeId, 0) + 1);
        }
    }

    // WhileLoop 决策节点 false 出口 → End 或后续动作
    for (String whileId : MainRunner.whileLoopDecisionIds.keySet()) {
        String decId = MainRunner.whileLoopDecisionIds.get(whileId);
        String mergeId = MainRunner.whileLoopMergeIds.get(whileId);
        ActivityNode decNode = MainRunner.umlNodes.get(decId);
        if (decNode == null) continue;
        
        // 添加 Start → WhileLoop MergeNode 连接 (关键修复!)
        // 跳过 while+until 双条件循环 (顺序链连接入口 DecisionNode, 而非 MergeNode)
        boolean isWhileUntil = MainRunner.whileLoopIsWhileUntil.getOrDefault(whileId, false);
        if (!isWhileUntil && mergeId != null && MainRunner.umlNodes.containsKey(mergeId)) {
            ctx.finalRoutedEdges.add(new MainRunner.EdgeData("START_NODE", mergeId));
            outDeg.put("START_NODE", outDeg.getOrDefault("START_NODE", 0) + 1);
            inDeg.put(mergeId, inDeg.getOrDefault(mergeId, 0) + 1);
        }
        
        // while+until: 只需添加 exitMerge → End (独立情况下; 顺序链会覆盖)
        if (isWhileUntil) {
            String exitMergeId = MainRunner.whileLoopExitMergeIds.get(whileId);
            if (exitMergeId != null && MainRunner.umlNodes.containsKey(exitMergeId)) {
                ActivityNode exitMergeNode = MainRunner.umlNodes.get(exitMergeId);
                ControlFlow toEnd = UMLFactory.eINSTANCE.createControlFlow();
                toEnd.setSource(exitMergeNode);
                toEnd.setTarget(endNode);
                ctx.activity.getEdges().add(toEnd);
                outDeg.put(exitMergeId, outDeg.getOrDefault(exitMergeId, 0) + 1);
                inDeg.put("END_NODE", inDeg.getOrDefault("END_NODE", 0) + 1);
            }
            continue;
        }
        
        // 确定循环后的目标节点
        // 对于 until 循环: 条件为真时退出 → 连到 postLoopAction 或 End
        // 对于 while 循环: 条件为假时退出 → 连到 End
        String postLoopAction = MainRunner.whileLoopPostLoopActions.get(whileId);
        String condText = MainRunner.whileLoopCondText.get(whileId);
        boolean isUntil = MainRunner.whileLoopIsUntil.getOrDefault(whileId, false);
        
        if (isUntil && postLoopAction != null) {
            // until 循环: 条件为真时退出到 postLoopAction
            String postLoopId = MainRunner.nameToIdMap.get(postLoopAction);
            ActivityNode exitTarget = endNode;
            String exitTargetKey = "END_NODE";
            if (postLoopId != null && MainRunner.umlNodes.containsKey(postLoopId)) {
                exitTarget = MainRunner.umlNodes.get(postLoopId);
                exitTargetKey = postLoopId;
            }
            if (condText != null && !condText.isEmpty()) {
                ControlFlow exitEdge = UMLFactory.eINSTANCE.createControlFlow();
                exitEdge.setSource(decNode);
                exitEdge.setTarget(exitTarget);
                OpaqueExpression condGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                condGuard.getBodies().add(condText);
                exitEdge.setGuard(condGuard);
                ctx.activity.getEdges().add(exitEdge);
                outDeg.put(decId, outDeg.getOrDefault(decId, 0) + 1);
                inDeg.put(exitTargetKey, inDeg.getOrDefault(exitTargetKey, 0) + 1);
            }
        } else {
            // 纯 while 循环: ExitMerge → End (else 出口已在内部接线中: Decision→ExitMerge)
            // Check if PASS 3-ext already added a post-loop succession from decId
            boolean hasPostLoopFromPass3ext = false;
            for (MainRunner.EdgeData fe : ctx.finalRoutedEdges) {
                if (fe.source.equals(decId) && !fe.target.equals("END_NODE") && !fe.target.equals("START_NODE")) {
                    ActivityNode tgtNode = MainRunner.umlNodes.get(fe.target);
                    if (tgtNode != null && !(tgtNode instanceof MergeNode) && !(tgtNode instanceof DecisionNode)
                        && !fe.target.equals(mergeId)) {
                        hasPostLoopFromPass3ext = true;
                        break;
                    }
                }
            }
            if (!hasPostLoopFromPass3ext) {
            String pureExitMergeId = MainRunner.whileLoopPureExitMergeIds.get(whileId);
            if (pureExitMergeId != null && MainRunner.umlNodes.containsKey(pureExitMergeId)) {
                ActivityNode pureExitMergeNode = MainRunner.umlNodes.get(pureExitMergeId);
                ControlFlow toEnd = UMLFactory.eINSTANCE.createControlFlow();
                toEnd.setSource(pureExitMergeNode);
                toEnd.setTarget(endNode);
                ctx.activity.getEdges().add(toEnd);
                outDeg.put(pureExitMergeId, outDeg.getOrDefault(pureExitMergeId, 0) + 1);
                inDeg.put("END_NODE", inDeg.getOrDefault("END_NODE", 0) + 1);
            } else {
                // 旧逻辑: 非 until 循环或无 postLoopAction: else 出口 → End
                boolean hasElseOut = false;
                for (org.eclipse.uml2.uml.ActivityEdge ae : ctx.activity.getEdges()) {
                    if (ae.getSource() == decNode && ae.getGuard() != null) {
                        String guardText = String.join("", ((OpaqueExpression) ae.getGuard()).getBodies());
                        if (guardText.contains("else")) { hasElseOut = true; break; }
                    }
                }
                if (!hasElseOut) {
                    ControlFlow falseExit = UMLFactory.eINSTANCE.createControlFlow();
                    falseExit.setSource(decNode);
                    falseExit.setTarget(endNode);
                    OpaqueExpression elseGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                    elseGuard.getBodies().add("else");
                    falseExit.setGuard(elseGuard);
                    ctx.activity.getEdges().add(falseExit);
                    outDeg.put(decId, outDeg.getOrDefault(decId, 0) + 1);
                    inDeg.put("END_NODE", inDeg.getOrDefault("END_NODE", 0) + 1);
                }
            }
            } // end if (!hasPostLoopFromPass3ext)
        }
    }

    // 同步 MainRunner.whileLoopIsUntil 信息到 MainRunner.loopIsUntilIds / MainRunner.loopConditionText
    // SEQUENTIAL CHAIN 代码使用这些 map 来添加 guard 和识别 until 循环
    for (String wid : MainRunner.whileLoopIsUntil.keySet()) {
        if (MainRunner.whileLoopIsUntil.get(wid)) {
            MainRunner.loopIsUntilIds.add(wid);
            String wCond = MainRunner.whileLoopCondText.get(wid);
            if (wCond != null && !wCond.isEmpty()) {
                MainRunner.loopConditionText.put(wid, wCond);
            }
        }
    }
    System.out.println("[SYNC] MainRunner.loopIsUntilIds now has " + MainRunner.loopIsUntilIds.size() + " entries (after whileLoop sync)");

    // ===================================================================
    // SEQUENTIAL CHAIN: 连接顶层控制结构 (if/while/loop) 为顺序流
    // ===================================================================
    {
        // 扫描 XMI 找到顶层控制流结构 (按文档顺序)
        List<String[]> chainConstructs = new ArrayList<>(); // [id, entry, exit]
        {
            java.util.Iterator<EObject> scanIt = resource.getAllContents();
            while (scanIt.hasNext()) {
                EObject scanObj = scanIt.next();
                if (!(scanObj instanceof org.omg.sysml.lang.sysml.Element)) continue;
                String scanClass = scanObj.eClass().getName();
                if (!scanClass.contains("ActionUsage") && !scanClass.contains("ActionDefinition")) continue;
                // Look at direct ownedRelationship children (top-level of the action)
                try {
                    var orFeat = scanObj.eClass().getEStructuralFeature("ownedRelationship");
                    if (orFeat == null) continue;
                    Object orVal = scanObj.eGet(orFeat);
                    if (!(orVal instanceof List)) continue;
                    List<?> children = (List<?>) orVal;
                    // 收集所有顶层控制结构 (可能在 FeatureMembership 内部)
                    for (Object child : children) {
                        if (!(child instanceof EObject)) continue;
                        EObject childE = (EObject) child;
                        // 如果是 FeatureMembership, 查看其内部元素
                        List<EObject> toCheck = new ArrayList<>();
                        if (childE.eClass().getName().contains("FeatureMembership") || 
                            childE.eClass().getName().contains("Ownership")) {
                            for (EObject gc : childE.eContents()) {
                                toCheck.add(gc);
                            }
                        } else {
                            toCheck.add(childE);
                        }
                        for (EObject target : toCheck) {
                            if (!(target instanceof org.omg.sysml.lang.sysml.Element)) continue;
                            String cc = target.eClass().getName();
                            String cId = ((org.omg.sysml.lang.sysml.Element) target).getElementId();
                            if (cc.contains("IfActionUsage") && !cc.contains("While")) {
                                String entry = cId;
                                String exit = MainRunner.topLevelIfMergeIds.get(cId);
                                if (exit != null && MainRunner.umlNodes.containsKey(entry) && MainRunner.umlNodes.containsKey(exit)) {
                                    chainConstructs.add(new String[]{cId, entry, exit});
                                }
                            } else if (cc.equals("LoopActionUsage")) {
                                String entry = MainRunner.loopStartMerge.get(cId);
                                String exit = MainRunner.loopEndDecision.get(cId);
                                if (entry != null && exit != null && MainRunner.umlNodes.containsKey(entry) && MainRunner.umlNodes.containsKey(exit)) {
                                    chainConstructs.add(new String[]{cId, entry, exit});
                                }
                            } else if (cc.contains("WhileLoopActionUsage")) {
                                // while+until: 入口是 whileEntryDecision; 纯 while: 入口是 MergeNode
                                String entry = MainRunner.whileLoopEntryIds.get(cId);
                                if (entry == null) entry = MainRunner.whileLoopMergeIds.get(cId);
                                // while+until: 出口是 exitMerge; 纯 while: 出口是 pureExitMerge; fallback: DecisionNode
                                String exit = MainRunner.whileLoopExitMergeIds.get(cId);
                                if (exit == null) exit = MainRunner.whileLoopPureExitMergeIds.get(cId);
                                if (exit == null) exit = MainRunner.whileLoopDecisionIds.get(cId);
                                if (entry != null && exit != null && MainRunner.umlNodes.containsKey(entry) && MainRunner.umlNodes.containsKey(exit)) {
                                    chainConstructs.add(new String[]{cId, entry, exit});
                                }
                            } else if (cc.contains("ForLoopActionUsage")) {
                                String entry = MainRunner.loopStartMerge.get(cId);
                                String exit = MainRunner.loopEndDecision.get(cId);
                                if (entry != null && exit != null && MainRunner.umlNodes.containsKey(entry) && MainRunner.umlNodes.containsKey(exit)) {
                                    chainConstructs.add(new String[]{cId, entry, exit});
                                }
                            }
                        }
                    }
                    // 只在第一个有控制结构的 ActionUsage 上停 (顶层 action 块)
                    if (!chainConstructs.isEmpty()) break;
                } catch (Exception e) {
                    System.out.println("[CHAIN-DEBUG] Exception: " + e.getMessage());
                }
            }
        }
        
        System.out.println("[CHAIN] Found " + chainConstructs.size() + " top-level constructs");
        System.out.println("[CHAIN] MainRunner.loopIsUntilIds = " + MainRunner.loopIsUntilIds.size() + " entries");
        for (String uid : MainRunner.loopIsUntilIds) System.out.println("  [CHAIN]   until: " + uid.substring(0, Math.min(8, uid.length())));
        
        if (chainConstructs.size() > 0) {
            // 收集 chain 中的所有 entry/exit 节点
            java.util.Set<String> chainEntries = new java.util.HashSet<>();
            java.util.Set<String> chainExits = new java.util.HashSet<>();
            for (String[] c : chainConstructs) {
                chainEntries.add(c[1]);
                chainExits.add(c[2]);
            }
            
            // 1. 移除被 chain 取代的 Start→Merge 边 (从 ctx.finalRoutedEdges)
            ctx.finalRoutedEdges.removeIf(e -> 
                e.source.equals("START_NODE") && chainEntries.contains(e.target));
            
            // 2. 移除 chain exit→END_NODE 的冲突边 (从 ctx.finalRoutedEdges 和 ctx.activity.getEdges())
            for (String[] c : chainConstructs) {
                String exitId = c[2];
                ctx.finalRoutedEdges.removeIf(e -> 
                    e.source.equals(exitId) && e.target.equals("END_NODE"));
                // 从 ctx.activity.getEdges() 中移除对应的 ControlFlow
                for (java.util.Iterator<org.eclipse.uml2.uml.ActivityEdge> aeIt = ctx.activity.getEdges().iterator(); aeIt.hasNext(); ) {
                    org.eclipse.uml2.uml.ActivityEdge ae = aeIt.next();
                    if (ae.getSource() == MainRunner.umlNodes.get(exitId) && 
                        (ae.getTarget() == endNode || (ae.getTarget() != null && "End".equals(ae.getTarget().getName())))) {
                        ae.setSource(null);
                        ae.setTarget(null);
                        aeIt.remove();
                        outDeg.put(exitId, Math.max(0, outDeg.getOrDefault(exitId, 0) - 1));
                        inDeg.put("END_NODE", Math.max(0, inDeg.getOrDefault("END_NODE", 0) - 1));
                        break;
                    }
                }
            }
            
            // 3. 构建顺序链: Start → first.entry
            String firstEntry = chainConstructs.get(0)[1];
            ctx.finalRoutedEdges.add(new MainRunner.EdgeData("START_NODE", firstEntry));
            outDeg.put("START_NODE", outDeg.getOrDefault("START_NODE", 0) + 1);
            inDeg.put(firstEntry, inDeg.getOrDefault(firstEntry, 0) + 1);
            System.out.println("[CHAIN] Start → " + firstEntry);
            
            // 4. 连接每个 construct 的 exit → 下一个 construct 的 entry
            for (int ci = 0; ci < chainConstructs.size() - 1; ci++) {
                String cId = chainConstructs.get(ci)[0];
                String exitId = chainConstructs.get(ci)[2];
                String nextEntryId = chainConstructs.get(ci + 1)[1];
                ActivityNode exitNode = MainRunner.umlNodes.get(exitId);
                ActivityNode entryNode = MainRunner.umlNodes.get(nextEntryId);
                if (exitNode != null && entryNode != null) {
                    ControlFlow cf = UMLFactory.eINSTANCE.createControlFlow();
                    ctx.activity.getEdges().add(cf);
                    cf.setSource(exitNode);
                    cf.setTarget(entryNode);
                    // until 循环出口需要 guard (条件满足时退出)
                    if (MainRunner.loopIsUntilIds.contains(cId)) {
                        OpaqueExpression guard = UMLFactory.eINSTANCE.createOpaqueExpression();
                        guard.getBodies().add(MainRunner.loopConditionText.get(cId));
                        cf.setGuard(guard);
                        System.out.println("[CHAIN-GUARD] Added guard '" + MainRunner.loopConditionText.get(cId) + "' to exit edge of until loop " + cId.substring(0, Math.min(8, cId.length())));
                    }
                    outDeg.put(exitId, outDeg.getOrDefault(exitId, 0) + 1);
                    inDeg.put(nextEntryId, inDeg.getOrDefault(nextEntryId, 0) + 1);
                    System.out.println("[CHAIN] " + exitId + " → " + nextEntryId);
                }
            }
            
            // 5. 最后一个 construct 的 exit → End (如果还没连上)
            //    跳过: 如果 exit 节点已有出边到非循环/非End节点 (如 post-loop action)
            String lastExit = chainConstructs.get(chainConstructs.size() - 1)[2];
            ActivityNode lastExitNode = MainRunner.umlNodes.get(lastExit);
            boolean hasExitToEnd = false;
            // Check if exit already has outgoing edge to a post-loop target
            boolean hasPostLoopTarget = false;
            for (MainRunner.EdgeData fe : ctx.finalRoutedEdges) {
                if (fe.source.equals(lastExit) && !fe.target.equals("END_NODE") && !fe.target.equals("START_NODE")) {
                    // Check if target is a loop-internal node (merge) or a post-loop action
                    ActivityNode tgtNode = MainRunner.umlNodes.get(fe.target);
                    if (tgtNode != null && !(tgtNode instanceof MergeNode)) {
                        hasPostLoopTarget = true;
                    }
                }
            }
            if (hasPostLoopTarget) {
                hasExitToEnd = true; // Skip adding exit→End; post-loop action will connect to End
            }
            for (org.eclipse.uml2.uml.ActivityEdge ae : ctx.activity.getEdges()) {
                if (ae.getSource() == lastExitNode && ae.getTarget() == endNode) {
                    hasExitToEnd = true;
                    break;
                }
            }
            if (!hasExitToEnd) {
                for (MainRunner.EdgeData fe : ctx.finalRoutedEdges) {
                    if (fe.source.equals(lastExit) && fe.target.equals("END_NODE")) {
                        hasExitToEnd = true;
                        break;
                    }
                }
            }
            if (!hasExitToEnd && lastExitNode != null) {
                ControlFlow toEnd = UMLFactory.eINSTANCE.createControlFlow();
                ctx.activity.getEdges().add(toEnd);
                toEnd.setSource(lastExitNode);
                toEnd.setTarget(endNode);
                // until 循环出口需要 guard
                String lastConstructId = chainConstructs.get(chainConstructs.size() - 1)[0];
                if (MainRunner.loopIsUntilIds.contains(lastConstructId)) {
                    OpaqueExpression guard = UMLFactory.eINSTANCE.createOpaqueExpression();
                    guard.getBodies().add(MainRunner.loopConditionText.get(lastConstructId));
                    toEnd.setGuard(guard);
                }
                outDeg.put(lastExit, outDeg.getOrDefault(lastExit, 0) + 1);
                inDeg.put("END_NODE", inDeg.getOrDefault("END_NODE", 0) + 1);
                System.out.println("[CHAIN] " + lastExit + " → End");
            }
        }
    }

    // dynamic merge 和 endpoint fallback 对所有结构生效 (包括 decide)
    {
    for (String nodeId : inDeg.keySet()) {
        if (inDeg.get(nodeId) > 1 && MainRunner.umlNodes.get(nodeId) instanceof Action) {
            MergeNode m = (MergeNode) ctx.activity.createOwnedNode(MainRunner.uuidToNameMap.getOrDefault(nodeId, "Node") + "_Merge", UMLPackage.Literals.MERGE_NODE);
            ctx.dynamicMerges.put(nodeId, m);
            
            ControlFlow bridge = ctx.factory.createControlFlow();
            ctx.activity.getEdges().add(bridge);
            bridge.setSource(m);
            bridge.setTarget(MainRunner.umlNodes.get(nodeId));
        }
    }

    // 端点兜底连线 (跳过控制节点: Initial/Final/Fork/Join/Merge/Decision)
    // Start: 跳过 typed 动作 (其前驱 succession 可能被转换器丢弃, 导致假 inDeg=0)
    // End:   不跳过 typed (无法区分真正终端和被断开的 typed 节点)
    // 跳过 WhileLoop body 节点 (已由内部边连接, 不应被 Start/End 牵连)
    boolean startConnected = false;
    boolean endConnected = false;
    for (String nodeId : MainRunner.umlNodes.keySet()) {
        ActivityNode node = MainRunner.umlNodes.get(nodeId);
        if (node == startNode || node == endNode) continue;
        if (node instanceof ForkNode || node instanceof JoinNode || node instanceof MergeNode || node instanceof DecisionNode || node instanceof InitialNode || node instanceof ActivityFinalNode) continue;
        // 跳过 LoopNode/StructuredActivityNode 内部节点
        if (node.eContainer() instanceof org.eclipse.uml2.uml.StructuredActivityNode) continue;
        // 跳过 WhileLoop 展开的 body 节点 (_body0, _body1 等)
        if (nodeId.matches(".*_body\\d+$")) continue;
        // 跳过 loop body 节点 (monitor, addCharge 等)
        if (MainRunner.loopBodyNodeIds.contains(nodeId)) continue;
        boolean hasIn = inDeg.containsKey(nodeId) && inDeg.get(nodeId) > 0;
        if (!hasIn && !ctx.typedActionIds.contains(nodeId)) {
            ControlFlow f = ctx.factory.createControlFlow(); ctx.activity.getEdges().add(f);
            f.setSource(startNode); f.setTarget(node);
            startConnected = true;
        }
        boolean hasOut = outDeg.containsKey(nodeId) && outDeg.get(nodeId) > 0;
        if (!hasOut) {
            ControlFlow f = ctx.factory.createControlFlow(); ctx.activity.getEdges().add(f);
            f.setSource(node); f.setTarget(endNode);
            endConnected = true;
        }
    }
    // 如果 typed 过滤后无 Start/End 连接, 回退到全部 inDeg=0 / outDeg=0 节点
    if (!startConnected) {
        for (String nodeId : MainRunner.umlNodes.keySet()) {
            ActivityNode node = MainRunner.umlNodes.get(nodeId);
            if (node == startNode || node == endNode) continue;
            if (node instanceof ForkNode || node instanceof JoinNode || node instanceof MergeNode || node instanceof DecisionNode || node instanceof InitialNode || node instanceof ActivityFinalNode) continue;
            if (node.eContainer() instanceof org.eclipse.uml2.uml.StructuredActivityNode) continue;
            if (nodeId.matches(".*_body\\d+$")) continue;
            if (MainRunner.loopBodyNodeIds.contains(nodeId)) continue;
            boolean hasIn = inDeg.containsKey(nodeId) && inDeg.get(nodeId) > 0;
            if (!hasIn) {
                ControlFlow f = ctx.factory.createControlFlow(); ctx.activity.getEdges().add(f);
                f.setSource(startNode); f.setTarget(node);
            }
        }
    }
    if (!endConnected) {
        for (String nodeId : MainRunner.umlNodes.keySet()) {
            ActivityNode node = MainRunner.umlNodes.get(nodeId);
            if (node == startNode || node == endNode) continue;
            if (node instanceof ForkNode || node instanceof JoinNode || node instanceof MergeNode || node instanceof DecisionNode || node instanceof InitialNode || node instanceof ActivityFinalNode) continue;
            if (node.eContainer() instanceof org.eclipse.uml2.uml.StructuredActivityNode) continue;
            if (nodeId.matches(".*_body\\d+$")) continue;
            if (MainRunner.loopBodyNodeIds.contains(nodeId)) continue;
            boolean hasOut = outDeg.containsKey(nodeId) && outDeg.get(nodeId) > 0;
            if (!hasOut) {
                ControlFlow f = ctx.factory.createControlFlow(); ctx.activity.getEdges().add(f);
                f.setSource(node); f.setTarget(endNode);
            }
        }
    }
    } // end if (lastDecideDecisionId == null) — dynamic merge + endpoint fallback 跳过 decide

    }
}
