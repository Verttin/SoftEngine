
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
        TreeIterator<EObject> iterator;

        // ===================================================================
        // PASS 2b: typed action def 顺序动作提取 (ActionDefinition FM 链)
        // ===================================================================
        for (String actDefId : new ArrayList<>(MainRunner.nameToIdMap.keySet())) {
            String actDefNodeId = MainRunner.nameToIdMap.get(actDefId);
            // ActionDefinition 可能无 UML 节点 (如 ActionWithLoop2 是定义而非 usage)
            // 查找 ActionDefinition EObject
            iterator = resource.getAllContents();
            EObject actionDefObj = null;
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                if (obj instanceof org.omg.sysml.lang.sysml.Element) {
                    String objId = ((org.omg.sysml.lang.sysml.Element) obj).getElementId();
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
            // 扫描 ActionDefinition 的 FeatureMembership 子节点
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
                        String whileId = ((org.omg.sysml.lang.sysml.Element) inner).getElementId();
                        String mergeId = MainRunner.whileLoopMergeIds.get(whileId);
                        if (mergeId != null) {
                            ctx.sequentialActionIds.add(mergeId);
                        }
                        continue;
                    }
                    String innerId = null;
                    try {
                        innerId = ((org.omg.sysml.lang.sysml.Element) inner).getElementId();
                    } catch (Exception ignored) {
                        // ignored
                    }
                    if (innerId == null) {
                        continue;
                    }
                    // 跳过已按名称处理过的节点 (flat 模型)
                    String innerName = null;
                    try {
                        innerName = ((org.omg.sysml.lang.sysml.Element) inner).getDeclaredName();
                    } catch (Exception ignored) {
                        // ignored
                    }
                    if (innerName != null && !innerName.isEmpty()) {
                        continue;
                    }
                    if (MainRunner.umlNodes.containsKey(innerId)) {
                        continue;
                    }
                    // 提取表达式 (仅针对 AssignmentActionUsage)
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

        // decide 结构: 提前创建 Start/End 节点, 防止 PASS 4 丢弃 "END_NODE" 边
        boolean decideEarlyStartEnd = (ctx.lastDecideDecisionId != null);
        if (decideEarlyStartEnd) {
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

        // ===================================================================
        // PASS 2c: decide 语法 TransitionUsage 延迟边解析
        // ===================================================================
        // TransitionUsage 在文档遍历顺序中先于目标 ActionUsage 出现,
        // 因此边被延迟存储到 decideDeferredEdges, 现在所有节点已创建, 可以解析
        System.out.println("[DEBUG] decideDeferredEdges (" + ctx.decideDeferredEdges.size() + " entries):");
        for (int di = 0; di < ctx.decideDeferredEdges.size(); di++) {
            String[] d = ctx.decideDeferredEdges.get(di);
            System.out.println("[DEBUG]   [" + di + "] src=" + d[0] + " tgt=" + d[1] + " guard=" + d[2]);
        }
        System.out.println("[DEBUG] allDecideDecisionIds: " + ctx.allDecideDecisionIds);
        for (String[] deferred : ctx.decideDeferredEdges) {
            String srcId = deferred[0]; // DecisionNode ID
            String tgtName = deferred[1]; // 目标动作名 (如 "addCharge")
            String guard = deferred[2]; // guard 条件文本
            String tgtId = MainRunner.nameToIdMap.get(tgtName);
            if (tgtId != null && MainRunner.umlNodes.containsKey(tgtId)) {
                MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, tgtId, guard));
            } else {
                System.out.println("[DEBUG] TransitionUsage 延迟边未解析: " + tgtName + " (src=" + srcId + ")");
            }
        }

        // ===================================================================
        // PASS 2c (续): .sysml 提取 succession 边 (decide 结构)
        // ===================================================================
        // decide 结构的控制流是固定的:
        // Start → MergeNode → [body actions] → DecisionNode
        // DecisionNode → [if-targets with guards] (由 TransitionUsage handler 处理)
        // if-targets 中的循环目标 → MergeNode (回边)
        // 终止目标 → End
        if (ctx.lastDecideDecisionId != null) {
            try {
                System.out.println("[DEBUG] PASS 2c succession: entering");
                String text = ctx.sysmlText;

                // 1. 提取主体 succession 链 (不含 if-then 内的目标)
                // 用多个简单正则分别匹配, 按位置排序
                System.out.println("[DEBUG] PASS 2c: text length=" + text.length());
                List<int[]> matchPositions = new ArrayList<>(); // [start, type, 0, start, end]
                // type: 0=merge, 1=action, 2=decide, 3=plain

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
                // plain "then X ...;" — 排除已匹配的 merge/action/decide 行
                // 支持 "then accept S;", "then send X to Y;", "then accept sig after 10[SI::s];"
                // 等
                Matcher mPlain = Pattern.compile("then\\s+(\\w+)\\b[^;]*;", Pattern.MULTILINE).matcher(text);
                while (mPlain.find()) {
                    String target = mPlain.group(1);
                    if ("merge".equals(target) || "action".equals(target) || "decide".equals(target)) {
                        continue;
                    }
                    // 检查是否与 merge/action/decide 匹配重叠
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
                // 按文档位置排序
                matchPositions.sort((a, b) -> Integer.compare(a[0], b[0]));

                // 重新提取匹配的目标名
                List<String> mainChain = new ArrayList<>();
                for (int[] mp : matchPositions) {
                    int pos = mp[0];
                    int type = mp[1];
                    int endPos = Math.min(pos + 200, text.length());
                    String sub = text.substring(pos, endPos);
                    if (type == 0) { // merge
                        Matcher m = Pattern.compile("then\\s+merge\\s+(\\w+)\\s*;").matcher(sub);
                        if (m.find())
                            mainChain.add("merge:" + m.group(1));
                    } else if (type == 1) { // action
                        Matcher m = Pattern.compile("then\\s+action\\s+(\\w+)\\s*:").matcher(sub);
                        if (m.find())
                            mainChain.add(m.group(1));
                    } else if (type == 2) { // decide
                        mainChain.add("decide");
                    } else { // plain
                        Matcher m = Pattern.compile("then\\s+(\\w+)\\b").matcher(sub);
                        if (m.find())
                            mainChain.add(m.group(1));
                    }
                }
                System.out.println("[DEBUG] PASS 2c mainChain (before filter): " + mainChain);

                // 过滤: decide 之后的 if-targets / merge node 名 / done 不属于主链
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

                // 2. 构建主体链边: 依次连接
                String prevId = "START_NODE";
                String mergeNodeId = null;
                for (String item : mainChain) {
                    String resolvedName = item;
                    if (item.startsWith("merge:")) {
                        resolvedName = item.substring(6); // 提取 merge 后的名称
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
                // prevId 现在应该是 DecisionNode ID

                // 3. 连接 if-targets 中的非循环目标 → End
                // sysmlIfTargets 由 TransitionUsage handler 提取: [addCharge, endCharging]
                // 循环目标 (回到 MergeNode) 由 addCharge → continueCharging 处理
                // 终止目标 → End
                if (ctx.sysmlIfTargets.size() >= 2) {
                    // 最后一个 if-target 通常为终止动作 → End
                    String lastTarget = ctx.sysmlIfTargets.get(ctx.sysmlIfTargets.size() - 1);
                    String lastTargetId = MainRunner.nameToIdMap.get(lastTarget);
                    if (lastTargetId != null) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(lastTargetId, "END_NODE"));
                    }
                    // 其他 if-targets (除第一个外) 如果有循环回边:
                    // 循环目标 → MergeNode 由 .sysml 中 "then <mergeTarget>;" 检测
                    // 查找 "then <ifTarget>;" 之后的 "then <name>;" 如果 name 是 merge node 名
                    for (int i = 0; i < ctx.sysmlIfTargets.size() - 1; i++) {
                        String ifTarget = ctx.sysmlIfTargets.get(i);
                        // 检查这个 if-target 后面是否有 "then <mergeNodeName>;"
                        // 在 .sysml 中: action addCharge ... then continueCharging;
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
                // G.ERR.02: Narrow catch not feasible due to EMF reflection API
                System.out.println(
                        "[ERROR] PASS 2c succession failed: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // ===================================================================
        // PASS 2e: XMI-based succession edge building (nested decide 结构补全)
        // ===================================================================
        // 遍历主 ActionDefinition 的 FeatureMembership 子节点 (按文档顺序),
        // 为隐式 SuccessionAsUsage (无 ReferenceSubsetting) 创建顺序流边,
        // 为显式 SuccessionAsUsage (有 ReferenceSubsetting) 创建显式边,
        // 为终端节点创建 → End 边.
        if (ctx.lastDecideDecisionId != null) {
            // 清除 PASS 2c 可能产生的不完整边 (PASS 2e 会重新构建全部边)
            MainRunner.logicalEdges.clear();
            ctx.decideDeferredEdges.clear(); // PASS 2e 从 XMI 重建, 旧延迟边不再需要
            try {
                System.out.println("[DEBUG] PASS 2e: XMI-based succession edge building");
                // 查找主 ActionDefinition (包含 DecisionNode 的那个)
                EObject mainActionDef = null;
                iterator = resource.getAllContents();
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
                    String mainDefName = ((org.omg.sysml.lang.sysml.Element) mainActionDef).getDeclaredName();
                    System.out.println("[DEBUG] PASS 2e: main ActionDef = " + mainDefName);

                    // 按文档顺序收集 FM 子元素
                    List<String> elementIds = new ArrayList<>();
                    Map<String, String> elementType = new HashMap<>();
                    // successionInfo: id → [type: "explicit"/"implicit"/"terminal", targetId or
                    // null]
                    Map<String, String[]> successionInfo = new HashMap<>();
                    // transitionEdges: list of [srcDecisionId, targetElementId, guardText]
                    List<String[]> transitionEdges = new ArrayList<>();
                    Map<String, Integer> elementOrder = new HashMap<>();
                    int order = 0;

                    for (EObject fm : mainActionDef.eContents()) {
                        if (!fm.eClass().getName().equals("FeatureMembership")) {
                            continue;
                        }
                        for (EObject inner : fm.eContents()) {
                            String ic = inner.eClass().getName();
                            String iId = null;
                            try {
                                iId = ((org.omg.sysml.lang.sysml.Element) inner).getElementId();
                            } catch (Exception ignored) {
                                // ignored
                            }
                            if (iId == null) {
                                continue;
                            }

                            if (ic.contains("ActionUsage") || "MergeNode".equals(ic) || "DecisionNode".equals(ic)) {
                                elementIds.add(iId);
                                elementType.put(iId, ic);
                                elementOrder.put(iId, order++);
                                System.out.println("[DEBUG] PASS 2e: node[" + (order - 1) + "] = " + ic + " id=" + iId);
                            } else if ("SuccessionAsUsage".equals(ic)) {
                                // 检查是否有 ReferenceSubsetting (显式目标)
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
                                                                    targetId = ((org.omg.sysml.lang.sysml.Element) rfVal)
                                                                            .getElementId();
                                                                }
                                                            }
                                                        } catch (Exception ignored) {
                                                            // ignored
                                                        }
                                                        // 检查是否为 Done (外部库引用)
                                                        for (EObject rsChild : rs.eContents()) {
                                                            if (rsChild.eClass().getName()
                                                                    .equals("ReferenceSubsetting")) {
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
                                                                    // ignored
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // 记录 succession 关联的前一个节点
                                String prevElemId = elementIds.isEmpty() ? null : elementIds.get(elementIds.size() - 1);
                                if (targetId != null) {
                                    successionInfo.put(iId, new String[]{"explicit", targetId, prevElemId});
                                } else if (isDone) {
                                    successionInfo.put(iId, new String[]{"terminal", null, prevElemId});
                                } else {
                                    successionInfo.put(iId, new String[]{"implicit", null, prevElemId});
                                }
                            } else if ("TransitionUsage".equals(ic)) {
                                // 提取 source (Membership.memberElement → DecisionNode)
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
                                                    srcId = ((org.omg.sysml.lang.sysml.Element) meVal).getElementId();
                                                }
                                            }
                                        } catch (Exception ignored) {
                                            // ignored
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
                                                                            var rfFeat = rs.eClass()
                                                                                    .getEStructuralFeature(
                                                                                            "referencedFeature");
                                                                            if (rfFeat != null) {
                                                                                Object rfVal = rs.eGet(rfFeat);
                                                                                if (rfVal instanceof EObject) {
                                                                                    tgtId = ((org.omg.sysml.lang.sysml.Element) rfVal)
                                                                                            .getElementId();
                                                                                }
                                                                            }
                                                                        } catch (Exception ignored) {
                                                                            // ignored
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

                    // ---- 重排: DecisionNode 在前, 非 DecisionNode 在后 ----
                    // XMI 文档顺序中, decide 结构的分支目标 (ActionUsage) 可能穿插在
                    // 多个 DecisionNode 之间 (如 D1, D2, A1, A2, A3).
                    // 重排后 (D1, D2, A1, A2, A3) 确保顺序边正确连接 D1→D2→MergeNode.
                    List<String> originalElementIds = new ArrayList<>(elementIds); // 保存原始顺序 (用于 orphan 检测)
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
                    // 更新 elementOrder 以反映新顺序
                    for (int oi = 0; oi < elementIds.size(); oi++) {
                        elementOrder.put(elementIds.get(oi), oi);
                    }
                    System.out.println("[DEBUG] PASS 2e: reordered elementIds (DN first)");

                    // ---- 创建边 ----
                    // 1. Start → 第一个节点
                    if (!elementIds.isEmpty()) {
                        String firstId = elementIds.get(0);
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData("START_NODE", firstId));
                        System.out.println("[DEBUG] PASS 2e: START_NODE → " + firstId);
                    }

                    // 收集 TransitionUsage 目标 (用于跳过不正确的顺序边)
                    Set<String> transitionTargets = new HashSet<>();
                    for (String[] te : transitionEdges) {
                        transitionTargets.add(te[1]);
                    }

                    // 2. 顺序边: 连接连续的非 succession 元素
                    for (int i = 0; i < elementIds.size() - 1; i++) {
                        String srcId = elementIds.get(i);
                        String tgtId = elementIds.get(i + 1);
                        System.out.println("[DEBUG] PASS 2e: seq-check [" + i + "] src=" + srcId + "("
                                + elementType.get(srcId) + ") tgt=" + tgtId + "(" + elementType.get(tgtId) + ")");
                        // 不创建从 DecisionNode 出发的顺序边 (由 TransitionUsage 处理)
                        if ("DecisionNode".equals(elementType.get(srcId)))
                            continue;
                        // 如果下一个元素是 DecisionNode, 不创建顺序边 (PASS 3-ext 隐式接续会处理)
                        if ("DecisionNode".equals(elementType.get(tgtId)))
                            continue;
                        // 不创建两个 TransitionUsage 目标之间的顺序边 (防止并行分支间的错误边)
                        if (transitionTargets.contains(srcId) && transitionTargets.contains(tgtId)) {
                            continue;
                        }
                        // 如果目标节点是某个 TransitionUsage 的分支目标, 不创建顺序边
                        // (防止 reorder 后前驱节点直接连到分支目标, 产生多余出度 → 多余 ForkNode)
                        if (transitionTargets.contains(tgtId))
                            continue;
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(srcId, tgtId));
                        System.out.println("[DEBUG] PASS 2e: seq " + srcId + " → " + tgtId);
                    }

                    // 3. 显式 SuccessionAsUsage 边
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

                    // 4. TransitionUsage 边 (带 guard)
                    // 4a. Guard 修复: XMI 仅提取 OperatorExpression, 但 true/false (LiteralBoolean)
                    // 和简单变量引用等不会被提取. 用 .sysml 文本按文档顺序补充 null guards.
                    // 同时处理 else 分支 (guard 保持 null).
                    try {
                        Matcher ifElseM = Pattern.compile("(?:if\\s+(.+?)\\s+then\\b)|(?:else\\s+\\w+)")
                                .matcher(ctx.sysmlText);
                        List<String> allGuards = new ArrayList<>(); // null = else branch
                        while (ifElseM.find()) {
                            if (ifElseM.group(1) != null) {
                                allGuards.add(ifElseM.group(1).trim());
                            } else {
                                allGuards.add(null); // else branch → guard stays null
                            }
                        }
                        int guardIdx = 0;
                        for (String[] te : transitionEdges) {
                            if (te[2] == null && guardIdx < allGuards.size()) {
                                String g = allGuards.get(guardIdx);
                                if (g != null) {
                                    te[2] = g;
                                    System.out.println("[DEBUG] PASS 2e: guard fallback [" + guardIdx + "] " + te[0]
                                            + "→" + te[1] + " guard=" + te[2]);
                                } else {
                                    System.out.println("[DEBUG] PASS 2e: guard fallback [" + guardIdx + "] " + te[0]
                                            + "→" + te[1] + " (else branch, guard=null)");
                                }
                            }
                            guardIdx++;
                        }
                    } catch (Exception ignored) {
                        // ignored
                    }

                    for (String[] te : transitionEdges) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(te[0], te[1], te[2]));
                    }

                    // 4b. MergeNode 插入: 当 DecisionNode 的分支需要汇聚后再流向下一个元素
                    // 场景: decide 'test x'; if...; else...; then decide D;
                    // 第一个 decide 的分支 (A1, A2, A3) 应先汇聚到 MergeNode, 再流向 D
                    Set<String> processedMergeTargets = new HashSet<>();
                    for (int i = 0; i < elementIds.size(); i++) {
                        String dnId = elementIds.get(i);
                        if (!"DecisionNode".equals(elementType.get(dnId)))
                            continue;

                        // 收集此 DecisionNode 在 elementIds 中的所有 transition targets
                        List<String> dnTargets = new ArrayList<>();
                        for (String[] te : transitionEdges) {
                            if (te[0].equals(dnId) && elementOrder.containsKey(te[1])) {
                                dnTargets.add(te[1]);
                            }
                        }
                        if (dnTargets.isEmpty())
                            continue;

                        // 找到紧随此 DecisionNode 之后的 "下一个元素":
                        // 重排后 DecisionNodes 在前, 优先找下一个 DecisionNode (如 D2)
                        String nextId = null;
                        int dnPos = elementIds.indexOf(dnId);
                        for (int j = dnPos + 1; j < elementIds.size(); j++) {
                            String candId = elementIds.get(j);
                            if ("DecisionNode".equals(elementType.get(candId)) && !dnTargets.contains(candId)) {
                                nextId = candId;
                                break;
                            }
                        }
                        // 如果后面没有 DecisionNode, 找最后一个 target 之后的元素
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

                        // 如果 nextId 已经是此 DecisionNode 的 transition target, 无需 MergeNode
                        if (dnTargets.contains(nextId))
                            continue;

                        // 计算已有出边的节点 (在添加 MergeNode 边之前)
                        Set<String> dnNodesWithOutgoing = new HashSet<>();
                        for (MainRunner.EdgeData e : MainRunner.logicalEdges) {
                            dnNodesWithOutgoing.add(e.source);
                        }

                        // 分支终点 = transition targets 中没有出边的节点
                        List<String> endpoints = new ArrayList<>();
                        for (String t : dnTargets) {
                            if (!dnNodesWithOutgoing.contains(t)) {
                                endpoints.add(t);
                            }
                        }
                        if (endpoints.isEmpty())
                            continue;

                        // 如果已经为这个 nextId 创建过 MergeNode, 只添加新边
                        if (processedMergeTargets.contains(nextId)) {
                            for (String ep : endpoints) {
                                MainRunner.logicalEdges.add(new MainRunner.EdgeData(ep, nextId));
                                System.out.println("[DEBUG] PASS 2e: merge-ext " + ep + " → " + nextId);
                            }
                            continue;
                        }

                        // 创建 MergeNode
                        String mergeId = dnId + "_autoMerge";
                        String mergeName = "Merge_" + (MainRunner.uuidToNameMap.getOrDefault(dnId, dnId));
                        MergeNode mergeNode = (MergeNode) ctx.activity.createOwnedNode(mergeName,
                                UMLPackage.Literals.MERGE_NODE);
                        MainRunner.umlNodes.put(mergeId, mergeNode);
                        System.out.println("[DEBUG] PASS 2e: created MergeNode " + mergeName + " id=" + mergeId);

                        // 每个分支终点 → MergeNode
                        for (String ep : endpoints) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(ep, mergeId));
                            System.out.println("[DEBUG] PASS 2e: merge " + ep + " → " + mergeId);
                        }

                        // MergeNode → 下一个元素
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, nextId));
                        System.out.println("[DEBUG] PASS 2e: merge-next " + mergeId + " → " + nextId);

                        processedMergeTargets.add(nextId);
                    }

                    // 5. 孤儿检测: 找出没有出边的 ActionUsage 节点 → 连接 END_NODE
                    // (包括 "then done;" 的终端节点, 以及因 transitionTargets 过滤而丢失出边的节点)
                    // 但如果原始 XMI 顺序中后续有 DecisionNode (PASS 3-ext 会接上), 不视为 orphan.
                    Set<String> nodesWithOutgoing = new HashSet<>();
                    for (MainRunner.EdgeData e : MainRunner.logicalEdges) {
                        nodesWithOutgoing.add(e.source);
                    }
                    for (int oi = 0; oi < originalElementIds.size(); oi++) {
                        String elemId = originalElementIds.get(oi);
                        if (!nodesWithOutgoing.contains(elemId)) {
                            String type = elementType.get(elemId);
                            if (type != null && type.contains("ActionUsage")) {
                                // 检查原始顺序中后续是否有 DecisionNode
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
        // 检测是否存在 ForkNode (表示 fork/join 结构)
        boolean hasForkJoin = MainRunner.umlNodes.values().stream().anyMatch(n -> n instanceof ForkNode);
        boolean pass2dProducedEdges = false;
        if (hasForkJoin && ctx.lastDecideDecisionId == null) {
            try {
                System.out.println("[DEBUG] PASS 2d fork/join: entering");
                String text = ctx.sysmlText;

                // 1. 提取 fork 前的主链: 按序的 "action X;" 和 "then Y;" 直到 "then fork;"
                List<String> preForkChain = new ArrayList<>();
                Matcher mAction = Pattern.compile("^\\s*action\\s+(\\w+)\\s*[;:]", Pattern.MULTILINE).matcher(text);
                while (mAction.find()) {
                    // 只取 fork 之前的顶层 action
                    if (text.substring(mAction.end()).trim().startsWith("then fork") || preForkChain.isEmpty()) {
                        preForkChain.add(mAction.group(1));
                    }
                }
                // 修正: 只取 "then fork;" 之前的 action
                preForkChain.clear();
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

                // 2. 提取 fork 目标: "then fork;" 之后缩进的 "then X;" 行
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
                            inFork = false; // fork 块结束
                        }
                    }
                }
                System.out.println("[DEBUG] PASS 2d forkTargets: " + forkTargets);

                // 3. 找到 JoinNode 名称
                String joinName = null;
                for (String key : MainRunner.nameToIdMap.keySet()) {
                    String nid = MainRunner.nameToIdMap.get(key);
                    if (MainRunner.umlNodes.containsKey(nid) && MainRunner.umlNodes.get(nid) instanceof JoinNode) {
                        joinName = key;
                        break;
                    }
                }
                System.out.println("[DEBUG] PASS 2d joinName: " + joinName);

                // 4. 提取 join 后的链: "join X;" 之后 "then Y;"
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

                // 5. 找到 ForkNode ID
                String forkNodeId = null;
                for (Map.Entry<String, org.eclipse.uml2.uml.ActivityNode> e : MainRunner.umlNodes.entrySet()) {
                    if (e.getValue() instanceof ForkNode) {
                        forkNodeId = e.getKey();
                        break;
                    }
                }
                String joinNodeId = (joinName != null) ? MainRunner.nameToIdMap.get(joinName) : null;

                // 6. 构建边: Start → preForkChain → ForkNode → forkTargets → JoinNode →
                // postJoinChain → End
                String prevId = "START_NODE";
                for (String action : preForkChain) {
                    String actionId = MainRunner.nameToIdMap.get(action);
                    if (actionId != null && MainRunner.umlNodes.containsKey(actionId)) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, actionId));
                        prevId = actionId;
                    }
                }
                // preForkChain 最后一个 → ForkNode
                if (forkNodeId != null) {
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, forkNodeId));
                }
                // ForkNode → 每个 fork target
                for (String target : forkTargets) {
                    String targetId = MainRunner.nameToIdMap.get(target);
                    if (targetId != null && forkNodeId != null) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(forkNodeId, targetId));
                    }
                }
                // 每个 fork target → JoinNode
                if (joinNodeId != null) {
                    for (String target : forkTargets) {
                        String targetId = MainRunner.nameToIdMap.get(target);
                        if (targetId != null) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(targetId, joinNodeId));
                        }
                    }
                }
                // JoinNode → postJoinChain
                prevId = joinNodeId;
                for (String action : postJoinChain) {
                    String actionId = MainRunner.nameToIdMap.get(action);
                    if (actionId != null && prevId != null) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(prevId, actionId));
                        prevId = actionId;
                    }
                }
                // If postJoinChain is empty (only "done" after join), connect JoinNode → End
                if (postJoinChain.isEmpty() && joinNodeId != null) {
                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(joinNodeId, "END_NODE"));
                }

                System.out.println("[DEBUG] PASS 2d edges added: " + MainRunner.logicalEdges.size());
                pass2dProducedEdges = !MainRunner.logicalEdges.isEmpty();
            } catch (Exception e) {
                System.out
                        .println("[ERROR] PASS 2d fork/join failed: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // ===================================================================
        // PASS 2f: XMI-based succession for fork/join/merge structures
        // ===================================================================
        // 当模型含 ForkNode/JoinNode/MergeNode 但无 decide 且无循环结构时,
        // 使用 XMI SuccessionAsUsage 提取正确的 succession 边,
        // 替代 regex-based PASS 2d (regex 无法处理 "then fork F { ... }" 等复杂语法).
        // 排除: 有 decide (PASS 2e 处理), 有 while/for loop (PASS 2b/2c + PASS 4 处理)
        boolean hasMergeJoin = MainRunner.umlNodes.values().stream()
                .anyMatch(n -> n instanceof JoinNode || n instanceof MergeNode);
        boolean hasLoops = !MainRunner.whileLoopCondText.isEmpty() || !MainRunner.loopBodyActions.isEmpty();
        if ((hasForkJoin || hasMergeJoin) && ctx.lastDecideDecisionId == null && !hasLoops) {
            try {
                System.out.println("[DEBUG] PASS 2f: XMI-based fork/join/merge succession");
                // 保存 PASS 2d 的边作为 fallback (若 PASS 2f 找不到容器则恢复)
                List<MainRunner.EdgeData> edgesBefore2f = new ArrayList<>(MainRunner.logicalEdges);

                // 查找主容器 (ActionDefinition 或 ActionUsage) 包含 ForkNode/JoinNode/MergeNode
                EObject mainActionDef2f = null;
                iterator = resource.getAllContents();
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
                            // 优先选择包含最多 FM 子元素的容器 (最外层)
                            if (mainActionDef2f == null) {
                                mainActionDef2f = obj;
                                // 不 break, 继续查找是否有更大的容器
                            } else {
                                // 比较 FM 子元素数量, 保留更大的
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
                    MainRunner.logicalEdges.clear(); // 仅在找到容器时清除, 避免丢失前序 PASS 的正确边
                    // 按文档顺序收集 FM 子元素
                    List<String> fm2fIds = new ArrayList<>();
                    Map<String, String> fm2fType = new HashMap<>();
                    // successionInfo2f: [sourceId, targetId] (source 从上下文推断)
                    List<String[]> successionInfo2f = new ArrayList<>();

                    for (EObject fm : mainActionDef2f.eContents()) {
                        if (!fm.eClass().getName().equals("FeatureMembership")) {
                            continue;
                        }
                        for (EObject inner : fm.eContents()) {
                            String ic = inner.eClass().getName();
                            String iId = null;
                            try {
                                iId = ((org.omg.sysml.lang.sysml.Element) inner).getElementId();
                            } catch (Exception ignored) {
                                // ignored
                            }
                            if (iId == null) {
                                continue;
                            }

                            if (ic.contains("ActionUsage") || "ForkNode".equals(ic) || "JoinNode".equals(ic)
                                    || "MergeNode".equals(ic)) {
                                fm2fIds.add(iId);
                                fm2fType.put(iId, ic);
                                System.out.println("[DEBUG] PASS 2f: node = " + ic + " id=" + iId);
                            } else if ("SuccessionAsUsage".equals(ic)) {
                                // 提取 target (End[1] 的 ReferenceSubsetting → referencedFeature)
                                String targetId = null;
                                boolean isDone = false;
                                int endIdx = 0;
                                for (EObject efm : inner.eContents()) {
                                    if (efm.eClass().getName().equals("EndFeatureMembership")) {
                                        if (endIdx == 1) { // End[1] = target
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
                                                                            targetId = ((org.omg.sysml.lang.sysml.Element) resolved)
                                                                                    .getElementId();
                                                                        }
                                                                    }
                                                                    // 检测 "done" 引用 (外部库 Actions.sysmlx)
                                                                    if (rfVal != null && rfVal.toString()
                                                                            .contains("Actions.sysmlx")) {
                                                                        isDone = true;
                                                                    }
                                                                }
                                                            } catch (Exception ignored) {
                                                                // ignored
                                                            }
                                                            // 嵌套 ReferenceSubsetting (也可能指向 done)
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
                                                                        // ignored
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
                                // "done" 引用 → 目标为 END_NODE
                                if (isDone) {
                                    targetId = "END_NODE";
                                }
                                // 隐式 target: 下一个非 SAU/FlowUsage 的 FM
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
                                                try {
                                                    targetId = ((org.omg.sysml.lang.sysml.Element) inner2)
                                                            .getElementId();
                                                } catch (Exception ignored) {
                                                    // ignored
                                                }
                                                break;
                                            }
                                        }
                                        if (targetId != null) {
                                            break;
                                        }
                                    }
                                }
                                successionInfo2f.add(new String[]{null, targetId, iId}); // [source=null, target, sauId]
                            }
                            // FlowUsage 和其他类型被跳过 (不参与 succession 链)
                        }
                    }

                    // 推断每个 succession 的 source (End[0] 总是隐式的)
                    // 算法: 遍历 FM 列表, 跟踪 "当前节点", SAU 的 source 是它之前的最近节点
                    // (FlowUsage 不更新 currentNodeId, 只有 ActionUsage/ForkNode/JoinNode/MergeNode 更新)
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
                                try {
                                    currentNodeId = ((org.omg.sysml.lang.sysml.Element) inner).getElementId();
                                } catch (Exception ignored) {
                                    // ignored
                                }
                            } else if ("SuccessionAsUsage".equals(ic)) {
                                if (sauIdx < successionInfo2f.size()) {
                                    successionInfo2f.get(sauIdx)[0] = currentNodeId;
                                    sauIdx++;
                                }
                            }
                            // FlowUsage 不更新 currentNodeId
                        }
                    }

                    // ---- 创建边 ----
                    // 1. Start → 第一个节点
                    if (!fm2fIds.isEmpty()) {
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData("START_NODE", fm2fIds.get(0)));
                        System.out.println("[DEBUG] PASS 2f: START_NODE → " + fm2fIds.get(0));
                    }

                    // 2. Succession 边
                    for (String[] s : successionInfo2f) {
                        String src = s[0];
                        String tgt = s[1];
                        if (src != null && tgt != null) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(src, tgt));
                            System.out.println("[DEBUG] PASS 2f: " + src + " → " + tgt);
                        }
                    }

                    // 3. 孤儿检测: 没有出边的节点 → END_NODE
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
}
