
package sysml2uml;

import java.util.*;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.uml2.uml.*;

public class LoopExpander {

    static void buildBodyInternalFlows(Activity activity, List<String> bodyNodeIds, Map<String, String> ifDecisionIds,
            Map<String, String> ifMergeIds, Map<String, String> ifConditionTexts,
            Map<String, List<String>> ifThenBranchIds, Map<String, ActivityNode> umlNodes, ActivityNode loopMergeNode,
            ActivityNode untilDecisionNode) {

        // 遍历 bodyNodeIds, 构建顺序流
        int i = 0;
        while (i < bodyNodeIds.size()) {
            String currentId = bodyNodeIds.get(i);
            ActivityNode currentNode = umlNodes.get(currentId);

            // 检查当前是否是 if DecisionNode
            String ifBaId = null;
            for (String key : ifDecisionIds.keySet()) {
                if (ifDecisionIds.get(key).equals(currentId)) {
                    ifBaId = key;
                    break;
                }
            }

            if (ifBaId != null) {
                // 这是 if DecisionNode, 需要处理 if 分支
                String ifCondText = ifConditionTexts.get(ifBaId);
                List<String> thenBranchIds = ifThenBranchIds.get(ifBaId);
                ActivityNode ifDecisionNode = currentNode;

                // ifDecision → thenBranch 动作 (guard = condition)
                if (thenBranchIds != null && !thenBranchIds.isEmpty()) {
                    for (String thenId : thenBranchIds) {
                        ActivityNode thenNode = umlNodes.get(thenId);
                        if (thenNode != null) {
                            ControlFlow thenFlow = UMLFactory.eINSTANCE.createControlFlow();
                            thenFlow.setSource(ifDecisionNode);
                            thenFlow.setTarget(thenNode);
                            OpaqueExpression thenGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                            thenGuard.getBodies().add(ifCondText);
                            thenFlow.setGuard(thenGuard);
                            activity.getEdges().add(thenFlow);

                            // thenBranch 动作 → 后续节点 (下一个 bodyNode 或直到末尾)
                            // then 分支执行完后应该连到后续的节点
                            // 这里先不创建边, 由后续顺序流逻辑处理
                        }
                    }
                }

                // ifDecision[else] → 下一个节点 (跳过 if then 分支)
                // else 分支直接连到 bodyNodeIds 中 thenBranchIds 之后的节点
                // 找到 thenBranchIds 之后的位置
                int nextIdx = i + 1;
                // thenBranchIds 中的节点在 bodyNodeIds 中排在 ifDecision 之后
                // 需要找到 thenBranchIds 之后的第一个非 thenBranch 节点
                while (nextIdx < bodyNodeIds.size()) {
                    String nextId = bodyNodeIds.get(nextIdx);
                    boolean isThenBranch = false;
                    if (thenBranchIds != null) {
                        for (String tid : thenBranchIds) {
                            if (nextId.equals(tid)) {
                                isThenBranch = true;
                                break;
                            }
                        }
                    }
                    if (!isThenBranch)
                        break;
                    nextIdx++;
                }

                if (nextIdx < bodyNodeIds.size()) {
                    // else 分支连到后续节点
                    String elseTargetId = bodyNodeIds.get(nextIdx);
                    ActivityNode elseTargetNode = umlNodes.get(elseTargetId);
                    if (elseTargetNode != null) {
                        ControlFlow elseFlow = UMLFactory.eINSTANCE.createControlFlow();
                        elseFlow.setSource(ifDecisionNode);
                        elseFlow.setTarget(elseTargetNode);
                        OpaqueExpression elseGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                        elseGuard.getBodies().add("else");
                        elseFlow.setGuard(elseGuard);
                        activity.getEdges().add(elseFlow);
                    }
                } else if (untilDecisionNode != null) {
                    // until 循环中, if 是 body 的最后一个节点, else 分支连到 untilDecision
                    ControlFlow elseFlow = UMLFactory.eINSTANCE.createControlFlow();
                    elseFlow.setSource(ifDecisionNode);
                    elseFlow.setTarget(untilDecisionNode);
                    OpaqueExpression elseGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                    elseGuard.getBodies().add("else");
                    elseFlow.setGuard(elseGuard);
                    activity.getEdges().add(elseFlow);
                }

                // 处理 thenBranch 之间的顺序流，以及 thenBranch 到后续节点的连接
                if (thenBranchIds != null && !thenBranchIds.isEmpty()) {
                    for (int t = 0; t < thenBranchIds.size(); t++) {
                        ActivityNode thenNode = umlNodes.get(thenBranchIds.get(t));
                        if (thenNode != null) {
                            if (t + 1 < thenBranchIds.size()) {
                                // thenBranch[t] → thenBranch[t+1]
                                ActivityNode nextThenNode = umlNodes.get(thenBranchIds.get(t + 1));
                                if (nextThenNode != null) {
                                    ControlFlow seqFlow = UMLFactory.eINSTANCE.createControlFlow();
                                    seqFlow.setSource(thenNode);
                                    seqFlow.setTarget(nextThenNode);
                                    activity.getEdges().add(seqFlow);
                                }
                            } else {
                                // 最后一个 thenBranch → 后续节点
                                if (nextIdx < bodyNodeIds.size()) {
                                    ActivityNode nextNode = umlNodes.get(bodyNodeIds.get(nextIdx));
                                    if (nextNode != null) {
                                        ControlFlow thenToNext = UMLFactory.eINSTANCE.createControlFlow();
                                        thenToNext.setSource(thenNode);
                                        thenToNext.setTarget(nextNode);
                                        activity.getEdges().add(thenToNext);
                                    }
                                } else if (loopMergeNode != null) {
                                    // until 循环中, thenBranch 是 body 最后节点, 连回 Merge (循环回路)
                                    ControlFlow thenToMerge = UMLFactory.eINSTANCE.createControlFlow();
                                    thenToMerge.setSource(thenNode);
                                    thenToMerge.setTarget(loopMergeNode);
                                    activity.getEdges().add(thenToMerge);
                                }
                            }
                        }
                    }
                }

                // 跳过 ifDecision 和 thenBranchIds，继续处理后续节点
                i = nextIdx;
                continue;
            }

            // 常规顺序流: current → next
            if (i + 1 < bodyNodeIds.size()) {
                String nextId = bodyNodeIds.get(i + 1);
                ActivityNode nextNode = umlNodes.get(nextId);
                if (currentNode != null && nextNode != null) {
                    ControlFlow seqFlow = UMLFactory.eINSTANCE.createControlFlow();
                    seqFlow.setSource(currentNode);
                    seqFlow.setTarget(nextNode);
                    activity.getEdges().add(seqFlow);
                }
            }
            i++;
        }
    }

    /**
     * 展开嵌套的 WhileLoopActionUsage (在另一个循环的 body 内部).
     * 创建完整的 Merge→Decision→body→backEdge 结构.
     * 
     * @return [entryId, exitId] — entry=Merge, exit=PureExitMerge/ExitMerge
     */
    public static String[] expandNestedWhileLoop(Activity activity, EObject loopObj, String sysmlBasePath) {
        String id = ((org.omg.sysml.lang.sysml.Element) loopObj).getElementId();
        String name = ((org.omg.sysml.lang.sysml.Element) loopObj).getDeclaredName();

        // --- 提取条件 (PM1=while, PM3=until) ---
        String whileCondText = null, untilCondText = null;
        int pmIdx = 0;
        for (EObject child : loopObj.eContents()) {
            if (!child.eClass().getName().equals("ParameterMembership"))
                continue;
            pmIdx++;
            if (pmIdx == 1 || pmIdx == 3) {
                String condFromPM = null;
                for (java.util.Iterator<EObject> deepIt = child.eAllContents(); deepIt.hasNext();) {
                    EObject deep = deepIt.next();
                    if (condFromPM != null)
                        break;
                    String deepCn = deep.eClass().getName();
                    if (deepCn.contains("Expression")) {
                        String astText = ExpressionUtils.buildExpressionText(deep);
                        if (astText != null && !astText.isEmpty() && !astText.contains(" . ")
                                && ExpressionUtils.isValidExpression(astText))
                            condFromPM = astText;
                    } else if ("ReferenceUsage".equals(deepCn)) {
                        if (deep instanceof org.omg.sysml.lang.sysml.Element) {
                            String refName = ((org.omg.sysml.lang.sysml.Element) deep).getDeclaredName();
                            if (refName != null && !refName.isEmpty())
                                condFromPM = refName;
                        }
                    }
                }
                if (condFromPM != null && !condFromPM.isEmpty()) {
                    if (pmIdx == 1)
                        whileCondText = condFromPM;
                    else
                        untilCondText = condFromPM;
                }
            }
        }

        boolean hasWhile = whileCondText != null && !whileCondText.isEmpty();
        boolean hasUntil = untilCondText != null && !untilCondText.isEmpty();
        boolean isUntil = hasUntil && !hasWhile;
        String condText;
        if (hasUntil)
            condText = untilCondText;
        else if (hasWhile)
            condText = whileCondText;
        else
            condText = "";

        MainRunner.whileLoopCondText.put(id, condText);
        MainRunner.whileLoopIsUntil.put(id, isUntil);
        MainRunner.whileLoopIsWhileUntil.put(id, hasWhile && hasUntil);
        MainRunner.whileLoopPostLoopActions.put(id, null);

        // --- Merge + Decision ---
        String mergeId = id + "_merge";
        String decId = id + "_untilDecision";
        String mergeName = name != null ? name + "_Merge" : "Loop_Merge";
        MergeNode merge = (MergeNode) activity.createOwnedNode(mergeName, UMLPackage.Literals.MERGE_NODE);
        MainRunner.umlNodes.put(mergeId, merge);
        MainRunner.whileLoopMergeIds.put(id, mergeId);

        String decName = condText.isEmpty() ? "cond?" : condText + "?";
        DecisionNode decision = (DecisionNode) activity.createOwnedNode(decName, UMLPackage.Literals.DECISION_NODE);
        MainRunner.umlNodes.put(decId, decision);
        MainRunner.whileLoopDecisionIds.put(id, decId);

        // --- 收集 body 元素 ---
        List<EObject> bodyElements = new ArrayList<>();
        for (EObject child : loopObj.eContents()) {
            if (!child.eClass().getName().equals("ParameterMembership"))
                continue;
            for (EObject ore : child.eContents()) {
                if (!ore.eClass().getName().equals("ActionUsage"))
                    continue;
                for (EObject fm : ore.eContents()) {
                    if (!fm.eClass().getName().equals("FeatureMembership"))
                        continue;
                    for (EObject inner : fm.eContents()) {
                        String ic = inner.eClass().getName();
                        if ("SuccessionAsUsage".equals(ic) || "ParameterMembership".equals(ic))
                            continue;
                        bodyElements.add(inner);
                    }
                }
            }
        }

        // --- 处理 body 节点 (递归处理更深层嵌套) ---
        List<String> bodyNodeIds = new ArrayList<>();
        Map<String, String> ifCondTexts = new HashMap<>();
        Map<String, List<String>> ifThenBranchIds = new HashMap<>();
        Map<String, String> ifDecIds = new HashMap<>();
        Map<String, String> ifMrgIds = new HashMap<>();

        for (EObject ba : bodyElements) {
            String baClass = ba.eClass().getName();
            if (!(ba instanceof org.omg.sysml.lang.sysml.Element))
                continue;
            String baId = ((org.omg.sysml.lang.sysml.Element) ba).getElementId();
            String baName = ((org.omg.sysml.lang.sysml.Element) ba).getDeclaredName();

            if ("WhileLoopActionUsage".equals(baClass)) {
                String[] res = expandNestedWhileLoop(activity, ba, sysmlBasePath);
                bodyNodeIds.add(res[0]);
                MainRunner.loopBodyNodeIds.add(res[0]);
                for (java.util.Iterator<EObject> it = ba.eAllContents(); it.hasNext();) {
                    EObject d = it.next();
                    if (d instanceof org.omg.sysml.lang.sysml.Element) {
                        String dId = ((org.omg.sysml.lang.sysml.Element) d).getElementId();
                        if (dId != null) {
                            MainRunner.loopBodyNodeIds.add(dId);
                        }
                    }
                }
            } else if ("ForLoopActionUsage".equals(baClass)) {
                String[] res = expandNestedForLoop(activity, ba, sysmlBasePath);
                bodyNodeIds.add(res[0]);
                MainRunner.loopBodyNodeIds.add(res[0]);
                for (java.util.Iterator<EObject> it = ba.eAllContents(); it.hasNext();) {
                    EObject d = it.next();
                    if (d instanceof org.omg.sysml.lang.sysml.Element) {
                        String dId = ((org.omg.sysml.lang.sysml.Element) d).getElementId();
                        if (dId != null) {
                            MainRunner.loopBodyNodeIds.add(dId);
                        }
                    }
                }
            } else if ("LoopActionUsage".equals(baClass)) {
                String[] res = expandNestedLoopAction(activity, ba, sysmlBasePath);
                bodyNodeIds.add(res[0]);
                MainRunner.loopBodyNodeIds.add(res[0]);
                for (java.util.Iterator<EObject> it = ba.eAllContents(); it.hasNext();) {
                    EObject d = it.next();
                    if (d instanceof org.omg.sysml.lang.sysml.Element) {
                        String dId = ((org.omg.sysml.lang.sysml.Element) d).getElementId();
                        if (dId != null) {
                            MainRunner.loopBodyNodeIds.add(dId);
                        }
                    }
                }
            } else if ("IfActionUsage".equals(baClass)) {
                // IfActionUsage inside nested while body — same logic as main iterator's while
                // body
                String ifCondText = null;
                for (java.util.Iterator<EObject> it = ba.eAllContents(); it.hasNext();) {
                    EObject ifChild = it.next();
                    if (ifChild.eClass().getName().contains("OperatorExpression")) {
                        String exprText = ExpressionUtils.buildExpressionText(ifChild);
                        if (exprText != null && !exprText.isEmpty() && !exprText.contains(" . ")
                                && ExpressionUtils.isValidExpression(exprText)) {
                            ifCondText = exprText;
                            break;
                        }
                    }
                }
                if (ifCondText == null || ifCondText.isEmpty())
                    ifCondText = "true";
                String ifDecId = baId + "_ifDecision";
                DecisionNode ifDec = (DecisionNode) activity.createOwnedNode(ifCondText + "?",
                        UMLPackage.Literals.DECISION_NODE);
                MainRunner.umlNodes.put(ifDecId, ifDec);
                ifDecIds.put(baId, ifDecId);
                ifCondTexts.put(baId, ifCondText);
                List<String> tBranchIds = new ArrayList<>();
                for (java.util.Iterator<EObject> it = ba.eAllContents(); it.hasNext();) {
                    EObject ifChild = it.next();
                    if (ifChild instanceof org.omg.sysml.lang.sysml.Element) {
                        String ic2 = ifChild.eClass().getName();
                        String in = ((org.omg.sysml.lang.sysml.Element) ifChild).getDeclaredName();
                        String iid = ((org.omg.sysml.lang.sysml.Element) ifChild).getElementId();
                        if (in != null && !in.isEmpty() && ic2.contains("ActionUsage")
                                && !"IfActionUsage".equals(ic2)) {
                            tBranchIds.add(iid);
                        }
                    }
                }
                ifThenBranchIds.put(baId, tBranchIds);
                bodyNodeIds.add(ifDecId);
                MainRunner.loopBodyNodeIds.add(ifDecId);
            } else {
                // Regular ActionUsage / AssignmentActionUsage
                String expr = ExpressionUtils.extractAssignmentText(ba);
                String nodeName = expr.isEmpty()
                        ? (baName != null ? baName : ExpressionUtils.sanitizeName(baClass))
                        : expr.replaceAll("[^a-zA-Z0-9_]", "_");
                String bodyText = expr.isEmpty() ? null : expr;
                ActivityNode bodyNode;
                if (bodyText != null && !bodyText.isEmpty() && bodyText.contains("=")) {
                    bodyNode = UmlHelper.createOpaqueActionForAssignment(activity, nodeName, bodyText);
                } else {
                    bodyNode = UmlHelper.createCallBehaviorActionWithBody(activity, nodeName, bodyText, "SysMLv2");
                }
                MainRunner.umlNodes.put(baId, bodyNode);
                if (baName != null) {
                    MainRunner.uuidToNameMap.put(baId, baName);
                    MainRunner.nameToIdMap.put(baName, baId);
                }
                bodyNodeIds.add(baId);
                MainRunner.loopBodyNodeIds.add(baId);
            }
        }

        // 创建 then-branch 子动作节点 (for IfActionUsage inside body)
        for (String ifId : ifThenBranchIds.keySet()) {
            for (String thenId : ifThenBranchIds.get(ifId)) {
                if (MainRunner.umlNodes.containsKey(thenId))
                    continue;
                String thenName = null;
                var it2 = loopObj.eAllContents();
                while (it2.hasNext()) {
                    EObject e = it2.next();
                    if (e instanceof org.omg.sysml.lang.sysml.Element) {
                        if (thenId.equals(((org.omg.sysml.lang.sysml.Element) e).getElementId())) {
                            thenName = ((org.omg.sysml.lang.sysml.Element) e).getDeclaredName();
                            break;
                        }
                    }
                }
                if (thenName != null && !thenName.isEmpty()) {
                    ActivityNode thenNode = UmlHelper.createCallBehaviorAction(activity, thenName);
                    MainRunner.umlNodes.put(thenId, thenNode);
                    MainRunner.uuidToNameMap.put(thenId, thenName);
                    MainRunner.nameToIdMap.put(thenName, thenId);
                    MainRunner.loopBodyNodeIds.add(thenId);
                }
            }
        }

        // --- 内部布线 ---
        String exitMergeId = null;

        if (isUntil) {
            // until: Merge → body → untilDecision → (cond: exit, else: Merge)
            if (!bodyNodeIds.isEmpty()) {
                ControlFlow m2b = UMLFactory.eINSTANCE.createControlFlow();
                m2b.setSource(merge);
                m2b.setTarget(MainRunner.umlNodes.get(bodyNodeIds.get(0)));
                activity.getEdges().add(m2b);
                buildBodyInternalFlows(activity, bodyNodeIds, ifDecIds, ifMrgIds, ifCondTexts, ifThenBranchIds,
                        MainRunner.umlNodes, merge, decision);
                String lastId = bodyNodeIds.get(bodyNodeIds.size() - 1);
                boolean lastIsIf = ifDecIds.containsValue(lastId);
                if (!lastIsIf) {
                    ControlFlow toD = UMLFactory.eINSTANCE.createControlFlow();
                    toD.setSource(MainRunner.umlNodes.get(lastId));
                    toD.setTarget(decision);
                    activity.getEdges().add(toD);
                }
            }
            ControlFlow back = UMLFactory.eINSTANCE.createControlFlow();
            back.setSource(decision);
            back.setTarget(merge);
            OpaqueExpression bg = UMLFactory.eINSTANCE.createOpaqueExpression();
            bg.getBodies().add("else");
            back.setGuard(bg);
            activity.getEdges().add(back);
            exitMergeId = decId; // until: DecisionNode 是出口
        } else if (hasWhile && hasUntil) {
            // while+until dual condition
            String whileDecId = id + "_whileEntryDecision";
            DecisionNode whileDec = (DecisionNode) activity.createOwnedNode(whileCondText + "?",
                    UMLPackage.Literals.DECISION_NODE);
            MainRunner.umlNodes.put(whileDecId, whileDec);
            MainRunner.whileLoopEntryIds.put(id, whileDecId);

            ControlFlow m2w = UMLFactory.eINSTANCE.createControlFlow();
            m2w.setSource(merge);
            m2w.setTarget(whileDec);
            activity.getEdges().add(m2w);

            if (!bodyNodeIds.isEmpty()) {
                ControlFlow tb = UMLFactory.eINSTANCE.createControlFlow();
                tb.setSource(whileDec);
                tb.setTarget(MainRunner.umlNodes.get(bodyNodeIds.get(0)));
                OpaqueExpression wg = UMLFactory.eINSTANCE.createOpaqueExpression();
                wg.getBodies().add(whileCondText);
                tb.setGuard(wg);
                activity.getEdges().add(tb);
            }

            if (!bodyNodeIds.isEmpty()) {
                buildBodyInternalFlows(activity, bodyNodeIds, ifDecIds, ifMrgIds, ifCondTexts, ifThenBranchIds,
                        MainRunner.umlNodes, merge, decision);
                String lastId = bodyNodeIds.get(bodyNodeIds.size() - 1);
                if (!ifDecIds.containsValue(lastId)) {
                    ControlFlow toD = UMLFactory.eINSTANCE.createControlFlow();
                    toD.setSource(MainRunner.umlNodes.get(lastId));
                    toD.setTarget(decision);
                    activity.getEdges().add(toD);
                }
            }

            exitMergeId = id + "_whileUntilExitMerge";
            MergeNode exitMerge = (MergeNode) activity.createOwnedNode((name != null ? name : "Loop") + "_ExitMerge",
                    UMLPackage.Literals.MERGE_NODE);
            MainRunner.umlNodes.put(exitMergeId, exitMerge);
            MainRunner.whileLoopExitMergeIds.put(id, exitMergeId);

            ControlFlow w2e = UMLFactory.eINSTANCE.createControlFlow();
            w2e.setSource(whileDec);
            w2e.setTarget(exitMerge);
            OpaqueExpression wfg = UMLFactory.eINSTANCE.createOpaqueExpression();
            wfg.getBodies().add("else");
            w2e.setGuard(wfg);
            activity.getEdges().add(w2e);

            ControlFlow u2e = UMLFactory.eINSTANCE.createControlFlow();
            u2e.setSource(decision);
            u2e.setTarget(exitMerge);
            OpaqueExpression ug = UMLFactory.eINSTANCE.createOpaqueExpression();
            ug.getBodies().add(untilCondText);
            u2e.setGuard(ug);
            activity.getEdges().add(u2e);

            ControlFlow back2 = UMLFactory.eINSTANCE.createControlFlow();
            back2.setSource(decision);
            back2.setTarget(merge);
            OpaqueExpression bg2 = UMLFactory.eINSTANCE.createOpaqueExpression();
            bg2.getBodies().add("else");
            back2.setGuard(bg2);
            activity.getEdges().add(back2);
        } else {
            // Pure while: Merge → Decision → (cond: body → Merge, else: PureExitMerge)
            ControlFlow m2d = UMLFactory.eINSTANCE.createControlFlow();
            m2d.setSource(merge);
            m2d.setTarget(decision);
            activity.getEdges().add(m2d);

            exitMergeId = id + "_pureWhileExitMerge";
            MergeNode pureExit = (MergeNode) activity.createOwnedNode((name != null ? name : "Loop") + "_ExitMerge",
                    UMLPackage.Literals.MERGE_NODE);
            MainRunner.umlNodes.put(exitMergeId, pureExit);
            MainRunner.whileLoopPureExitMergeIds.put(id, exitMergeId);

            ControlFlow e2x = UMLFactory.eINSTANCE.createControlFlow();
            e2x.setSource(decision);
            e2x.setTarget(pureExit);
            OpaqueExpression eg = UMLFactory.eINSTANCE.createOpaqueExpression();
            eg.getBodies().add("else");
            e2x.setGuard(eg);
            activity.getEdges().add(e2x);

            if (!bodyNodeIds.isEmpty()) {
                ControlFlow tb = UMLFactory.eINSTANCE.createControlFlow();
                tb.setSource(decision);
                tb.setTarget(MainRunner.umlNodes.get(bodyNodeIds.get(0)));
                OpaqueExpression tg = UMLFactory.eINSTANCE.createOpaqueExpression();
                tg.getBodies().add(condText);
                tb.setGuard(tg);
                activity.getEdges().add(tb);

                buildBodyInternalFlows(activity, bodyNodeIds, ifDecIds, ifMrgIds, ifCondTexts, ifThenBranchIds,
                        MainRunner.umlNodes, merge, null);

                String lastId = bodyNodeIds.get(bodyNodeIds.size() - 1);
                if (!ifDecIds.containsValue(lastId)) {
                    ControlFlow back = UMLFactory.eINSTANCE.createControlFlow();
                    back.setSource(MainRunner.umlNodes.get(lastId));
                    back.setTarget(merge);
                    activity.getEdges().add(back);
                }
            }
        }

        System.out.println("[NESTED-WHILE] id=" + id.substring(0, Math.min(8, id.length())) + " cond=" + condText
                + " isUntil=" + isUntil);
        return new String[]{mergeId, exitMergeId};
    }

    /**
     * 展开嵌套的 ForLoopActionUsage (在另一个循环的 body 内部).
     * 
     * @return [entryId, exitId] — entry=Merge, exit=Decision
     */
    public static String[] expandNestedForLoop(Activity activity, EObject loopObj, String sysmlBasePath) {
        String id = ((org.omg.sysml.lang.sysml.Element) loopObj).getElementId();
        String name = ((org.omg.sysml.lang.sysml.Element) loopObj).getDeclaredName();

        String mergeId = id + "_forLoopMerge";
        String decisionId = id + "_forLoopDecision";
        String loopName = (name != null ? name : "ForLoop");
        MergeNode merge = (MergeNode) activity.createOwnedNode(loopName + "_Merge", UMLPackage.Literals.MERGE_NODE);
        DecisionNode decision = (DecisionNode) activity.createOwnedNode(loopName + "_Decision",
                UMLPackage.Literals.DECISION_NODE);
        MainRunner.umlNodes.put(mergeId, merge);
        MainRunner.umlNodes.put(decisionId, decision);
        MainRunner.loopStartMerge.put(id, mergeId);
        MainRunner.loopEndDecision.put(id, decisionId);

        // 提取循环变量名
        String loopVarName = null;
        for (EObject dc : loopObj.eContents()) {
            if (dc.eClass().getName().contains("FeatureMembership")) {
                for (EObject gc : dc.eContents()) {
                    if (gc.eClass().getName().contains("ReferenceUsage")) {
                        loopVarName = ((org.omg.sysml.lang.sysml.Element) gc).getDeclaredName();
                        break;
                    }
                }
                if (loopVarName != null)
                    break;
            }
        }

        // 提取范围表达式
        String rangeText = null;
        for (EObject dc : loopObj.eContents()) {
            if (dc.eClass().getName().contains("ParameterMembership")) {
                for (EObject gc : dc.eContents()) {
                    if (gc.eClass().getName().contains("ReferenceUsage")) {
                        for (EObject gg : gc.eContents()) {
                            if (gg.eClass().getName().contains("FeatureValue")) {
                                for (EObject expr : gg.eContents()) {
                                    if (expr.eClass().getName().contains("OperatorExpression")) {
                                        List<String> literals = new ArrayList<>();
                                        for (java.util.Iterator<EObject> lit = expr.eAllContents(); lit.hasNext();) {
                                            EObject litObj = lit.next();
                                            if (litObj.eClass().getName().contains("LiteralInteger")) {
                                                try {
                                                    var valFeat = litObj.eClass().getEStructuralFeature("value");
                                                    if (valFeat != null) {
                                                        Object val = litObj.eGet(valFeat);
                                                        if (val != null)
                                                            literals.add(val.toString());
                                                    }
                                                } catch (Exception ignored) {
                                                    // safeproperty value extraction failed, continue
                                                }
                                            }
                                        }
                                        if (!literals.isEmpty())
                                            rangeText = String.join(", ", literals);
                                    }
                                }
                            }
                        }
                    }
                }
                break;
            }
        }

        String condText;
        if (loopVarName != null && rangeText != null)
            condText = loopVarName + " in (" + rangeText + ")";
        else if (loopVarName != null)
            condText = "hasNext(" + loopVarName + ")";
        else
            condText = "hasNext";
        MainRunner.loopConditionText.put(id, condText);

        // 提取循环体 — 处理嵌套循环或普通动作
        int pmCount = 0;
        String bodyActionId = null;
        for (EObject dc : loopObj.eContents()) {
            if (dc.eClass().getName().contains("ParameterMembership")) {
                pmCount++;
                if (pmCount == 2) {
                    for (EObject gc : dc.eContents()) {
                        String gcClass = gc.eClass().getName();
                        if (!(gc instanceof org.omg.sysml.lang.sysml.Element))
                            continue;
                        String gcId = ((org.omg.sysml.lang.sysml.Element) gc).getElementId();

                        if ("ForLoopActionUsage".equals(gcClass)) {
                            String[] res = expandNestedForLoop(activity, gc, sysmlBasePath);
                            bodyActionId = res[0]; // 用内层 merge 作为 body 代理
                            // 连接: 外层 merge → 内层 merge, 内层 decision → 外层 merge (回边)
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, res[0]));
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(res[1], mergeId));
                            for (java.util.Iterator<EObject> it = gc.eAllContents(); it.hasNext();) {
                                EObject d = it.next();
                                if (d instanceof org.omg.sysml.lang.sysml.Element) {
                                    String dId = ((org.omg.sysml.lang.sysml.Element) d).getElementId();
                                    if (dId != null) {
                                        MainRunner.loopBodyNodeIds.add(dId);
                                    }
                                }
                            }
                            break;
                        } else if (gcClass.contains("ActionUsage") || gcClass.contains("AssignmentActionUsage")) {
                            // 查找嵌套循环
                            EObject nestedLoop = null;
                            for (EObject inner : gc.eContents()) {
                                String innerCn = inner.eClass().getName();
                                if ("ForLoopActionUsage".equals(innerCn) || "WhileLoopActionUsage".equals(innerCn)
                                        || "LoopActionUsage".equals(innerCn)) {
                                    nestedLoop = inner;
                                    break;
                                }
                            }
                            if (nestedLoop != null) {
                                String[] res;
                                String nc = nestedLoop.eClass().getName();
                                if ("ForLoopActionUsage".equals(nc)) {
                                    res = expandNestedForLoop(activity, nestedLoop, sysmlBasePath);
                                } else if ("WhileLoopActionUsage".equals(nc)) {
                                    res = expandNestedWhileLoop(activity, nestedLoop, sysmlBasePath);
                                } else {
                                    res = expandNestedLoopAction(activity, nestedLoop, sysmlBasePath);
                                }
                                bodyActionId = res[0];
                                MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, res[0]));
                                MainRunner.logicalEdges.add(new MainRunner.EdgeData(res[1], mergeId));
                                for (java.util.Iterator<EObject> it = nestedLoop.eAllContents(); it.hasNext();) {
                                    EObject d = it.next();
                                    if (d instanceof org.omg.sysml.lang.sysml.Element) {
                                        String dId = ((org.omg.sysml.lang.sysml.Element) d).getElementId();
                                        if (dId != null) {
                                            MainRunner.loopBodyNodeIds.add(dId);
                                        }
                                    }
                                }
                            } else {
                                // 普通动作
                                EObject bodyObj = gc;
                                for (EObject inner : gc.eContents()) {
                                    if (inner.eClass().getName().contains("AssignmentActionUsage")) {
                                        bodyObj = inner;
                                        break;
                                    }
                                }
                                String expr = ExpressionUtils.extractAssignmentText(bodyObj);
                                String nodeName;
                                String bodyText;
                                if (!expr.isEmpty()) {
                                    nodeName = expr.replaceAll("[^a-zA-Z0-9_]", "_");
                                    bodyText = expr;
                                } else {
                                    nodeName = "ForLoopBody_" + gcId.substring(0, Math.min(8, gcId.length()));
                                    bodyText = "forLoopBody";
                                }
                                ActivityNode bodyNode;
                                if (!expr.isEmpty() && expr.contains("=")) {
                                    bodyNode = UmlHelper.createOpaqueActionForAssignment(activity, nodeName, expr);
                                } else {
                                    bodyNode = UmlHelper.createCallBehaviorActionWithBody(activity, nodeName, bodyText,
                                            "SysMLv2");
                                }
                                MainRunner.umlNodes.put(gcId, bodyNode);
                                MainRunner.uuidToNameMap.put(gcId, nodeName);
                                bodyActionId = gcId;
                            }
                            break;
                        }
                    }
                    break;
                }
            }
        }

        if (bodyActionId != null && !MainRunner.logicalEdges.stream().anyMatch(e -> e.source.equals(mergeId))) {
            MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, bodyActionId));
            MainRunner.logicalEdges.add(new MainRunner.EdgeData(bodyActionId, decisionId));
        }
        List<String> bodyActions = new ArrayList<>();
        if (bodyActionId != null)
            bodyActions.add(bodyActionId);
        MainRunner.loopBodyActions.put(id, bodyActions);

        // 防止 PASS 4 误连
        for (java.util.Iterator<EObject> deepIt = loopObj.eAllContents(); deepIt.hasNext();) {
            EObject deep = deepIt.next();
            if (deep instanceof org.omg.sysml.lang.sysml.Element) {
                String deepCn = deep.eClass().getName();
                if (deepCn.contains("ActionUsage") || deepCn.contains("AssignmentActionUsage")) {
                    try {
                        String deepId = ((org.omg.sysml.lang.sysml.Element) deep).getElementId();
                        if (deepId != null) {
                            MainRunner.loopBodyNodeIds.add(deepId);
                        }
                    } catch (Exception ignored) {
                        // element ID extraction failed, skip
                    }
                }
            }
        }
        if (bodyActionId != null)
            MainRunner.loopBodyNodeIds.add(bodyActionId);

        System.out.println("[NESTED-FOR] id=" + id.substring(0, Math.min(8, id.length())) + " var=" + loopVarName
                + " range=" + rangeText);
        return new String[]{mergeId, decisionId};
    }

    /**
     * 展开嵌套的 LoopActionUsage (在另一个循环的 body 内部).
     * 
     * @return [entryId, exitId] — entry=Merge, exit=Decision
     */
    public static String[] expandNestedLoopAction(Activity activity, EObject loopObj, String sysmlBasePath) {
        String id = ((org.omg.sysml.lang.sysml.Element) loopObj).getElementId();
        String name = ((org.omg.sysml.lang.sysml.Element) loopObj).getDeclaredName();

        String mergeId = id + "_loopMerge";
        String decisionId = id + "_loopDecision";
        MergeNode merge = (MergeNode) activity.createOwnedNode((name != null ? name : "Loop") + "_Merge",
                UMLPackage.Literals.MERGE_NODE);
        DecisionNode decision = (DecisionNode) activity.createOwnedNode((name != null ? name : "Loop") + "_Decision",
                UMLPackage.Literals.DECISION_NODE);
        MainRunner.umlNodes.put(mergeId, merge);
        MainRunner.umlNodes.put(decisionId, decision);
        MainRunner.loopStartMerge.put(id, mergeId);
        MainRunner.loopEndDecision.put(id, decisionId);

        // 提取 until 条件 (PM2)
        String condText = null;
        {
            int pmI = 0;
            for (EObject child : loopObj.eContents()) {
                if (!child.eClass().getName().equals("ParameterMembership"))
                    continue;
                pmI++;
                if (pmI == 2) {
                    for (java.util.Iterator<EObject> deepIt = child.eAllContents(); deepIt.hasNext();) {
                        EObject deep = deepIt.next();
                        String deepCn = deep.eClass().getName();
                        if (deepCn.contains("Expression") && condText == null) {
                            String exprText = ExpressionUtils.buildExpressionText(deep);
                            if (exprText != null && !exprText.isEmpty() && !exprText.contains(" . ")
                                    && ExpressionUtils.isValidExpression(exprText))
                                condText = exprText;
                        } else if ("ReferenceUsage".equals(deepCn) && condText == null) {
                            if (deep instanceof org.omg.sysml.lang.sysml.Element) {
                                String refName = ((org.omg.sysml.lang.sysml.Element) deep).getDeclaredName();
                                if (refName != null && !refName.isEmpty())
                                    condText = refName;
                            }
                        }
                    }
                }
            }
        }
        if (condText == null || condText.isEmpty())
            condText = "true";
        MainRunner.loopConditionText.put(id, condText);

        // 收集 body actions (PM1 中的子元素)
        List<String> bodyActions = new ArrayList<>();
        int pmI2 = 0;
        for (EObject child : loopObj.eContents()) {
            if (!child.eClass().getName().equals("ParameterMembership"))
                continue;
            pmI2++;
            if (pmI2 == 1) {
                for (EObject ore : child.eContents()) {
                    String oreCn = ore.eClass().getName();
                    if ("ForLoopActionUsage".equals(oreCn) || "WhileLoopActionUsage".equals(oreCn)
                            || "LoopActionUsage".equals(oreCn)) {
                        // 嵌套循环 — 递归展开
                        String[] res;
                        if ("ForLoopActionUsage".equals(oreCn))
                            res = expandNestedForLoop(activity, ore, sysmlBasePath);
                        else if ("WhileLoopActionUsage".equals(oreCn))
                            res = expandNestedWhileLoop(activity, ore, sysmlBasePath);
                        else
                            res = expandNestedLoopAction(activity, ore, sysmlBasePath);
                        bodyActions.add(res[0]);
                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, res[0]));
                        for (java.util.Iterator<EObject> it = ore.eAllContents(); it.hasNext();) {
                            EObject d = it.next();
                            if (d instanceof org.omg.sysml.lang.sysml.Element) {
                                String dId = ((org.omg.sysml.lang.sysml.Element) d).getElementId();
                                if (dId != null) {
                                    MainRunner.loopBodyNodeIds.add(dId);
                                }
                            }
                        }
                    } else if (oreCn.contains("ActionUsage") || oreCn.contains("ReferenceUsage")) {
                        // 检查是否包含嵌套循环
                        EObject nestedLoop = null;
                        for (EObject inner : ore.eContents()) {
                            String innerCn = inner.eClass().getName();
                            if ("ForLoopActionUsage".equals(innerCn) || "WhileLoopActionUsage".equals(innerCn)
                                    || "LoopActionUsage".equals(innerCn)) {
                                nestedLoop = inner;
                                break;
                            }
                        }
                        if (nestedLoop != null) {
                            String[] res;
                            String nc = nestedLoop.eClass().getName();
                            if ("ForLoopActionUsage".equals(nc))
                                res = expandNestedForLoop(activity, nestedLoop, sysmlBasePath);
                            else if ("WhileLoopActionUsage".equals(nc))
                                res = expandNestedWhileLoop(activity, nestedLoop, sysmlBasePath);
                            else
                                res = expandNestedLoopAction(activity, nestedLoop, sysmlBasePath);
                            bodyActions.add(res[0]);
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, res[0]));
                            for (java.util.Iterator<EObject> it = nestedLoop.eAllContents(); it.hasNext();) {
                                EObject d = it.next();
                                if (d instanceof org.omg.sysml.lang.sysml.Element) {
                                    String dId = ((org.omg.sysml.lang.sysml.Element) d).getElementId();
                                    if (dId != null) {
                                        MainRunner.loopBodyNodeIds.add(dId);
                                    }
                                }
                            }
                        } else {
                            String cId = ((org.omg.sysml.lang.sysml.Element) ore).getElementId();
                            if (cId != null)
                                bodyActions.add(cId);
                        }
                    }
                }
            }
        }
        MainRunner.loopBodyActions.put(id, bodyActions);

        if (!bodyActions.isEmpty()) {
            if (!MainRunner.logicalEdges.stream().anyMatch(e -> e.source.equals(mergeId))) {
                MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, bodyActions.get(0)));
            }
        }

        System.out.println("[NESTED-LOOP] id=" + id.substring(0, Math.min(8, id.length())) + " until=" + condText);
        return new String[]{mergeId, decisionId};
    }
}
