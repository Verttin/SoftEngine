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
 * PASS 3 / 3b / 3-ext / 2c-ext: 控制流关系提取
 * 
 * - PASS 3: SuccessionAsUsage / ReferenceUsage 边提取
 * - PASS 3b: typed action def 顺序边提取
 * - PASS 3-ext: Implicit positional succession reference resolution
 * - PASS 2c-ext: Conditional Succession — synthetic DecisionNode insertion
 */
class Pass3Edges {
    static void run(Resource resource, PipelineContext ctx) {
        TreeIterator<EObject> iterator;
        boolean hasForkJoin = MainRunner.umlNodes.values().stream().anyMatch(n -> n instanceof ForkNode);

        // ===================================================================
        // PASS 3: 提取控制流关系
        // ===================================================================
        // 从 .sysml 源文件提取 Guard 条件文本
        Map<String, Map<String, String>> guardMap = new HashMap<>();
        String sysmlPath = ctx.sysmlBasePath.replace(".sysmlx", ".sysml");
        File sysmlFile = new File(sysmlPath);
        if (sysmlFile.exists()) {
            try {
                String content = Files.readString(Paths.get(sysmlPath));
                Pattern pIf = Pattern.compile("succession\\s+(\\w+)\\s+if\\s+\\(([^)]+)\\)\\s+then\\s+(\\w+)\\s*;");
                Matcher m = pIf.matcher(content);
                while (m.find()) {
                    guardMap.computeIfAbsent(m.group(1), k -> new HashMap<>()).put(m.group(3), m.group(2));
                }
                Pattern pGuard = Pattern.compile("succession\\s+(\\w+)\\s+then\\s+(\\w+)\\s*\\{[^}]*guard\\s+([^;]+)\\s*;");
                Matcher m2 = pGuard.matcher(content);
                while (m2.find()) {
                    guardMap.computeIfAbsent(m2.group(1), k -> new HashMap<>()).put(m2.group(2), m2.group(3).trim());
                }
            } catch (Exception ignored) {}
        }

        // ===================================================================
        // PASS 3: SuccessionAsUsage / ReferenceUsage 边提取
        // 注意: decide 结构也需要提取非 decide 部分的 succession 边
        // ===================================================================
        {
        iterator = resource.getAllContents();
        String pendingSrcName = null;
        String deferredSrcName = null;
        while (iterator.hasNext()) {
            EObject obj = iterator.next();
            String cn = obj.eClass().getName();
            if (cn.contains("SuccessionAsUsage")) {
                String n = obj instanceof org.omg.sysml.lang.sysml.Element
                    ? ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName() : null;
                if (n != null) {
                    pendingSrcName = n; deferredSrcName = null;
                } else {
                    // 尝试从 EndFeatureMembership → ReferenceUsage 提取源和目标
                    // 方法1: 直接获取 declaredName (命名引用)
                    // 方法2: 通过 ReferenceSubsetting → referencedFeature 解析 (匿名引用)
                    String srcName = null;
                    String tgtName = null;
                    int endCount = 0;
                    for (EObject child : obj.eContents()) {
                        if (child.eClass().getName().contains("EndFeatureMembership")) {
                            for (EObject ref : child.eContents()) {
                                if (ref.eClass().getName().contains("ReferenceUsage")) {
                                    String refName = ((org.omg.sysml.lang.sysml.Element) ref).getDeclaredName();
                                    // Fallback: resolve via ReferenceSubsetting → referencedFeature
                                    if (refName == null || refName.isEmpty()) {
                                        for (EObject rs : ref.eContents()) {
                                            if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                try {
                                                    var rfFeat = rs.eClass().getEStructuralFeature("referencedFeature");
                                                    if (rfFeat != null) {
                                                        Object rfVal = rs.eGet(rfFeat);
                                                        if (rfVal instanceof EObject) {
                                                            rfVal = EcoreUtil.resolve((EObject) rfVal, ctx.resourceSet);
                                                            if (rfVal instanceof org.omg.sysml.lang.sysml.Element) {
                                                                refName = ((org.omg.sysml.lang.sysml.Element) rfVal).getDeclaredName();
                                                            }
                                                        }
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                        }
                                    }
                                    if (endCount == 0) srcName = refName;
                                    else tgtName = refName;
                                    endCount++;
                                }
                            }
                        }
                    }
                    if (srcName != null && tgtName != null) {
                        String srcId = MainRunner.nameToIdMap.get(srcName);
                        String tgtId = MainRunner.nameToIdMap.get(tgtName);
                        if (srcId != null && tgtId != null) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, tgtId));
                        }
                    }
                }
            } else if (cn.contains("ReferenceUsage")) {
                String parentCn = obj.eContainer().eClass().getName();
                if (!parentCn.contains("OwningMembership")) continue;
                String tgtName = obj instanceof org.omg.sysml.lang.sysml.Element
                    ? ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName() : null;
                if (tgtName == null) { pendingSrcName = null; continue; }
                if (pendingSrcName != null) {
                    // 正常配对: 检查目标是否为条件变量 (Variable)
                    boolean isCondVar = false;
                    for (Variable v : ctx.activity.getVariables()) {
                        if (tgtName.equals(v.getName())) { isCondVar = true; break; }
                    }
                    if (isCondVar) {
                        deferredSrcName = pendingSrcName; // 条件变量 → 延迟匹配
                    } else {
                        String srcId = MainRunner.nameToIdMap.get(pendingSrcName);
                        String tgtId = MainRunner.nameToIdMap.get(tgtName);
                        if (srcId != null && tgtId != null) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, tgtId));
                        }
                    }
                    pendingSrcName = null;
                } else if (deferredSrcName != null) {
                    // 游离 RU 匹配到延迟的源 (if-guard 的真是目标)
                    String srcId = MainRunner.nameToIdMap.get(deferredSrcName);
                    String tgtId = MainRunner.nameToIdMap.get(tgtName);
                    if (srcId != null && tgtId != null) {
                        String guardText = guardMap.getOrDefault(deferredSrcName, Map.of()).get(tgtName);
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, tgtId, guardText));
                    }
                    deferredSrcName = null;
                }
            }
        }
        if (pendingSrcName != null && ctx.terminateActionId != null) {
            String srcId = MainRunner.nameToIdMap.get(pendingSrcName);
            if (srcId != null) MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, ctx.terminateActionId));
        }
        // 处理 deferred 残留: 若最后一条延迟未能匹配, 则连到 TerminateActionUsage
        if (deferredSrcName != null && ctx.terminateActionId != null) {
            String srcId = MainRunner.nameToIdMap.get(deferredSrcName);
            if (srcId != null) MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, ctx.terminateActionId));
        }
        } // end if (lastDecideDecisionId == null) — PASS 3 跳过 decide
        // ===================================================================
        // PASS 3b: typed action def 顺序边提取
        // ===================================================================
        {
            for (int i = 0; i < ctx.sequentialActionIds.size() - 1; i++) {
                String src = ctx.sequentialActionIds.get(i);
                String tgt = ctx.sequentialActionIds.get(i + 1);
                if (MainRunner.umlNodes.containsKey(src) && MainRunner.umlNodes.containsKey(tgt)) {
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(src, tgt));
                }
            }
        }
        // Fork/Join: 提前创建 END_NODE, 防止 PASS 4 丢弃 "END_NODE" 边
        if (hasForkJoin) {
            if (!MainRunner.umlNodes.containsKey("END_NODE")) {
                ActivityFinalNode earlyEnd = (ActivityFinalNode) ctx.activity.createOwnedNode("End", UMLPackage.Literals.ACTIVITY_FINAL_NODE);
                MainRunner.umlNodes.put("END_NODE", earlyEnd);
            }
        }
        // ===================================================================
        // DEFERRED SUCCESSION FLOW — 提前添加 ControlFlow 边到 logicalEdges
        // 这样 PASS 4 的 inDeg/outDeg 计算能包含 succession 边
        // (ObjectFlow pinBindings 部分在后面的 DEFERRED FLOW RESOLUTION 处理)
        // ===================================================================
        for (String[] dsflow : ctx.deferredSuccessionFlows) {
            String srcId = MainRunner.nameToIdMap.get(dsflow[0]);
            String tgtId = MainRunner.nameToIdMap.get(dsflow[2]);
            if (srcId != null && tgtId != null) {
                MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, tgtId));
            }
        }

        // ===================================================================
        // PASS 3-ext: Implicit positional succession reference resolution
        // ===================================================================
        // When a SuccessionAsUsage has unnamed endpoints without
        // ReferenceSubsetting (implicit positional references), resolve them
        // by looking at the preceding/succeeding FeatureMembership siblings.
        //
        // Example XMI structure:
        //   [FM] WhileLoopActionUsage
        //   [FM] SuccessionAsUsage  ← implicit: source=WhileLoop, target=next
        //   [FM] ActionUsage "endCharging"
        //   [FM] SuccessionAsUsage  ← implicit: source=endCharging, target=done
        // → Creates edges: WhileLoop→endCharging, endCharging→END_NODE
        // ===================================================================
        {
            try {
                // Find all ActionDefinition/ActionUsage containers that have WhileLoop or control structures
                iterator = resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject container = iterator.next();
                    String containerClass = container.eClass().getName();
                    if (!containerClass.contains("ActionDefinition") && !containerClass.contains("ActionUsage")) continue;

                    // Collect FM children in document order
                    List<EObject> fmChildren = new ArrayList<>();
                    for (EObject child : container.eContents()) {
                        if (child.eClass().getName().equals("FeatureMembership")) {
                            // Get the inner element
                            for (EObject inner : child.eContents()) {
                                fmChildren.add(inner);
                                break;
                            }
                        }
                    }
                    if (fmChildren.size() < 2) continue;

                    // Check if this container has any SuccessionAsUsage with implicit refs
                    boolean hasImplicitSucc = false;
                    for (EObject elem : fmChildren) {
                        if (elem.eClass().getName().equals("SuccessionAsUsage")) {
                            hasImplicitSucc = true;
                            break;
                        }
                    }
                    if (!hasImplicitSucc) continue;

                    // Resolve implicit positional references
                    for (int fi = 0; fi < fmChildren.size(); fi++) {
                        EObject elem = fmChildren.get(fi);
                        if (!elem.eClass().getName().equals("SuccessionAsUsage")) continue;

                        // Check if this SAU has already been resolved by PASS 3
                        // (named SAUs or SAUs with ReferenceSubsetting)
                        boolean hasNamedSrc = false, hasNamedTgt = false;
                        int endCount = 0;
                        for (EObject efm : elem.eContents()) {
                            if (efm.eClass().getName().contains("EndFeatureMembership")) {
                                for (EObject ref : efm.eContents()) {
                                    if (ref.eClass().getName().contains("ReferenceUsage")) {
                                        String refName = null;
                                        try { refName = ((org.omg.sysml.lang.sysml.Element) ref).getDeclaredName(); } catch (Exception ignored) {}
                                        if (refName != null && !refName.isEmpty()) {
                                            if (endCount == 0) hasNamedSrc = true;
                                            else hasNamedTgt = true;
                                        }
                                        // Check ReferenceSubsetting
                                        if (refName == null || refName.isEmpty()) {
                                            for (EObject rs : ref.eContents()) {
                                                if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                    if (endCount == 0) hasNamedSrc = true;
                                                    else hasNamedTgt = true;
                                                }
                                            }
                                        }
                                        endCount++;
                                    }
                                }
                            }
                        }

                        // Resolve source (implicit: previous FM's element, 跳过 flow/connection 元素)
                        if (!hasNamedSrc && fi > 0) {
                            EObject prevElem = null;
                            String prevId = null;
                            for (int pi = fi - 1; pi >= 0; pi--) {
                                EObject cand = fmChildren.get(pi);
                                String candCn = cand.eClass().getName();
                                // 仅跳过 Flow/Connection 类型 (不创建 UML 节点的数据流声明)
                                if (candCn.contains("FlowUsage") || candCn.contains("FlowConnectionUsage")
                                    || candCn.contains("ConnectionUsage") || candCn.contains("InterfaceUsage")) continue;
                                prevElem = cand;
                                break;
                            }
                            if (prevElem != null) {
                                try { prevId = ((org.omg.sysml.lang.sysml.Element) prevElem).getElementId(); } catch (Exception ignored) {}
                            }
                            // Map WhileLoop/Loop IDs to their exit nodes
                            if (prevId != null) {
                                String exitId = MainRunner.whileLoopExitMergeIds.get(prevId);
                                if (exitId == null) exitId = MainRunner.whileLoopPureExitMergeIds.get(prevId);
                                if (exitId == null) exitId = MainRunner.whileLoopDecisionIds.get(prevId);
                                if (exitId == null) exitId = MainRunner.loopEndDecision.get(prevId);
                                if (exitId != null) prevId = exitId;
                            }
                            if (prevId != null && MainRunner.umlNodes.containsKey(prevId)) {
                                // Resolve target (implicit: next FM's element, 跳过 flow/connection 元素)
                                String tgtId = null;
                                if (!hasNamedTgt) {
                                    for (int ni = fi + 1; ni < fmChildren.size(); ni++) {
                                        EObject cand = fmChildren.get(ni);
                                        String candCn = cand.eClass().getName();
                                        if (candCn.contains("FlowUsage") || candCn.contains("FlowConnectionUsage")
                                            || candCn.contains("ConnectionUsage") || candCn.contains("InterfaceUsage")) continue;
                                        try { tgtId = ((org.omg.sysml.lang.sysml.Element) cand).getElementId(); } catch (Exception ignored) {}
                                        break;
                                    }
                                    if (tgtId != null && MainRunner.umlNodes.containsKey(tgtId)) {
                                        // 去重: 避免创建重复边
                                        boolean dup = false;
                                        for (MainRunner.EdgeData ed : MainRunner.logicalEdges) {
                                            if (ed.source.equals(prevId) && ed.target.equals(tgtId)) { dup = true; break; }
                                        }
                                        if (!dup) {
                                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, tgtId));
                                            System.out.println("[DEBUG] PASS 3-ext: implicit succession " +
                                                MainRunner.uuidToNameMap.getOrDefault(prevId, prevId) + " → " +
                                                MainRunner.uuidToNameMap.getOrDefault(tgtId, tgtId));
                                        }
                                    }
                                }
                                // Check for "done" target (ReferenceSubsetting to Actions.sysmlx)
                                if (!hasNamedTgt && tgtId == null && fi + 1 < fmChildren.size()) {
                                    EObject nextElem = fmChildren.get(fi + 1);
                                    boolean isDone = false;
                                    for (EObject efm : nextElem.eContents()) {
                                        if (efm.eClass().getName().contains("EndFeatureMembership")) {
                                            for (EObject ref : efm.eContents()) {
                                                for (EObject rs : ref.eContents()) {
                                                    if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                        try {
                                                            var rfFeat = rs.eClass().getEStructuralFeature("referencedFeature");
                                                            if (rfFeat != null) {
                                                                Object rfVal = rs.eGet(rfFeat);
                                                                if (rfVal != null && rfVal.toString().contains("Actions.sysmlx")) {
                                                                    isDone = true;
                                                                }
                                                            }
                                                        } catch (Exception ignored) {}
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Also handle SAU where source is implicit but target has "done" reference
                        if (!hasNamedSrc && fi > 0 && !hasNamedTgt) {
                            // Check if the SAU itself has a "done" target (Actions.sysmlx reference)
                            boolean isDoneTarget = false;
                            int ec = 0;
                            for (EObject efm : elem.eContents()) {
                                if (efm.eClass().getName().contains("EndFeatureMembership")) {
                                    if (ec == 1) { // second end = target
                                        for (EObject ref : efm.eContents()) {
                                            for (EObject rs : ref.eContents()) {
                                                if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                    try {
                                                        var rfFeat = rs.eClass().getEStructuralFeature("referencedFeature");
                                                        if (rfFeat != null) {
                                                            Object rfVal = rs.eGet(rfFeat);
                                                            if (rfVal != null && rfVal.toString().contains("Actions.sysmlx")) {
                                                                isDoneTarget = true;
                                                            }
                                                        }
                                                    } catch (Exception ignored) {}
                                                }
                                            }
                                        }
                                    }
                                    ec++;
                                }
                            }
                            if (isDoneTarget) {
                                EObject prevElem = fmChildren.get(fi - 1);
                                String prevId = null;
                                try { prevId = ((org.omg.sysml.lang.sysml.Element) prevElem).getElementId(); } catch (Exception ignored) {}
                                if (prevId != null && MainRunner.umlNodes.containsKey(prevId)) {
                                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, "END_NODE"));
                                    System.out.println("[DEBUG] PASS 3-ext: implicit succession to done: " +
                                        MainRunner.uuidToNameMap.getOrDefault(prevId, prevId) + " → END_NODE");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[WARN] PASS 3-ext error: " + e.getMessage());
            }
        }

        // ===================================================================
        // PASS 2c-ext: Conditional Succession — synthetic DecisionNode insertion
        // ===================================================================
        // When TransitionUsage source is an ActionUsage (not a DecisionNode),
        // the SysML model uses inline conditional succession (if...then) without
        // an explicit 'decide' keyword. We synthesize a DecisionNode between
        // the source action and the conditional target(s).
        //
        // This pass runs AFTER PASS 2c/3/3b so it can clean up any duplicate
        // edges (e.g., focus→shoot from both PASS 2c and PASS 3b) and replace
        // them with the proper conditional branching structure.
        //
        // Example: action focus; if focus.image.isWellFocused then shoot;
        //   → focus → DecisionNode →[guard] shoot
        //                        →[else]  End
        // ===================================================================
        if (ctx.lastDecideDecisionId == null && !ctx.decideDeferredEdges.isEmpty()) {
            System.out.println("[DEBUG] PASS 2c-ext: checking for conditional succession (no decide keyword)");
            // Re-scan XMI to classify TransitionUsage sources as DecisionNode vs ActionUsage
            Set<String> decisionNodeIds2c = new HashSet<>();
            iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                EObject obj2 = iterator.next();
                if (obj2.eClass().getName().equals("DecisionNode")) {
                    try {
                        decisionNodeIds2c.add(((org.omg.sysml.lang.sysml.Element) obj2).getElementId());
                    } catch (Exception ignored) {}
                }
            }

            // Group deferred edges by source; identify which sources are ActionUsages
            Map<String, List<String[]>> condSuccBySource = new LinkedHashMap<>();
            for (String[] deferred : ctx.decideDeferredEdges) {
                String srcId = deferred[0];
                if (!decisionNodeIds2c.contains(srcId) && MainRunner.umlNodes.containsKey(srcId)) {
                    condSuccBySource.computeIfAbsent(srcId, k -> new ArrayList<>()).add(deferred);
                }
            }

            for (Map.Entry<String, List<String[]>> entry : condSuccBySource.entrySet()) {
                String srcActionId = entry.getKey();
                List<String[]> transitions = entry.getValue();

                // Create synthetic DecisionNode
                String dnId = srcActionId + "_condDecision";
                String srcName = MainRunner.uuidToNameMap.getOrDefault(srcActionId, srcActionId);
                String dnName = srcName + "_decision";
                if (transitions.get(0)[2] != null) {
                    String guard = transitions.get(0)[2];
                    if (guard.length() > 40) guard = guard.substring(0, 40);
                    dnName = guard + "?";
                }
                DecisionNode synthDN = (DecisionNode) ctx.activity.createOwnedNode(dnName, UMLPackage.Literals.DECISION_NODE);
                MainRunner.umlNodes.put(dnId, synthDN);
                System.out.println("[DEBUG] PASS 2c-ext: created synthetic DecisionNode '" + dnName + "' for source " + srcName);

                // Remove ALL existing edges from srcActionId in logicalEdges
                // (PASS 2c added srcAction→target with guard, PASS 3b may have added
                //  sequential srcAction→nextAction — all replaced by srcAction→DN)
                MainRunner.logicalEdges.removeIf(e -> e.source.equals(srcActionId));

                // Edge: source action → synthetic DecisionNode (unconditional)
                MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcActionId, dnId));

                // Edges: synthetic DecisionNode → each conditional target (with guard)
                for (String[] trans : transitions) {
                    String tgtName = trans[1];
                    String guard = trans[2];
                    String tgtId = MainRunner.nameToIdMap.get(tgtName);
                    if (tgtId != null && MainRunner.umlNodes.containsKey(tgtId)) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(dnId, tgtId, guard));
                        System.out.println("[DEBUG] PASS 2c-ext: " + dnName + " → " + tgtName + " [" + guard + "]");
                    }
                }

                // Edge: synthetic DecisionNode → End (else branch)
                MainRunner.logicalEdges.add(new MainRunner.EdgeData(dnId, "END_NODE", "else"));
                System.out.println("[DEBUG] PASS 2c-ext: " + dnName + " → END_NODE [else]");
            }
        }
    }
}
