package sysml2uml;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.uml2.uml.*;

/**
 * PASS 2: 控制流与数据操作节点实例化
 * 
 * 扫描 XMI 资源中的所有元素, 创建对应的 UML ActivityNode:
 * - ActionUsage -> CallBehaviorAction
 * - IfActionUsage -> DecisionNode + MergeNode
 * - WhileLoopActionUsage / ForLoopActionUsage / LoopActionUsage -> Merge+Decision+body
 * - ReferenceUsage (赋值) -> OpaqueAction
 * - ForkNode / JoinNode / MergeNode / DecisionNode
 * - TerminateActionUsage
 * - TransitionUsage / FlowUsage / SuccessionFlowUsage -> deferred edges
 * - Structural elements (Part/Item/Port/Connection/...)
 */
class Pass2Nodes {
    static void run(Resource resource, PipelineContext ctx) {

        // 用于顺序化边提取的容器 id 列表 (ActionDefinition / WhileLoopActionUsage 等)
        List<String> sequentialContainers = new ArrayList<>();
        // 用于记录顺序化容器内的 FM 顺序列表 (containerId -> [elementId列表])
        Map<String, List<String>> containerOrder = new HashMap<>();

        // 预填充 decideConditionGroups, 确保 TransitionUsage handler 可用
        try {
            String preText = Files.readString(Paths.get(ctx.sysmlBasePath.replace(".sysmlx", ".sysml")));
            ctx.sysmlText = preText;
            Matcher preSplitM = Pattern.compile("then\\s+decide\\s*(?:\\s+\\w+\\s*)?;").matcher(preText);
            List<Integer> preDecideEnds = new ArrayList<>();
            List<Integer> preDecideStarts = new ArrayList<>();
            while (preSplitM.find()) {
                preDecideStarts.add(preSplitM.start());
                preDecideEnds.add(preSplitM.end());
            }
            for (int di = 0; di < preDecideStarts.size(); di++) {
                int blockStart = preDecideEnds.get(di);
                int blockEnd = (di + 1 < preDecideStarts.size()) ? preDecideStarts.get(di + 1) : preText.length();
                String block = preText.substring(blockStart, blockEnd);
                List<String> group = new ArrayList<>();
                Matcher cm = Pattern.compile("if\\s+(.+?)\\s+then\\b").matcher(block);
                while (cm.find()) {
                    group.add(cm.group(1).trim());
                }
                if (!group.isEmpty()) {
                    ctx.decideConditionGroups.add(group);
                }
            }
            if (!ctx.decideConditionGroups.isEmpty()) {
                System.out.println("[DEBUG] Pre-populated decideConditionGroups: " + ctx.decideConditionGroups);
            }
        } catch (Exception ignored) {
            // ignored
        }

        TreeIterator<EObject> iterator = resource.getAllContents();
        String lastCheckIfUuid = null;
        
        while (iterator.hasNext()) {
            EObject obj = iterator.next();
            if (obj instanceof org.omg.sysml.lang.sysml.Element) {
                String id = ((org.omg.sysml.lang.sysml.Element) obj).getElementId();
                String name = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
                String className = obj.eClass().getName();
                
                if (id != null) {
                    // === 通用嵌套循环跳过: WhileLoop/ForLoop/Loop 被包含在另一个循环中时跳过 ===
                    // (由外层循环的 body 处理代码负责展开)
                    if (className.equals("WhileLoopActionUsage") || className.equals("ForLoopActionUsage") || className.equals("LoopActionUsage")) {
                        EObject nlParent = obj.eContainer();
                        boolean skipNestedLoop = false;
                        while (nlParent != null) {
                            String nlpc = nlParent.eClass().getName();
                            if (nlpc.equals("WhileLoopActionUsage") || nlpc.equals("ForLoopActionUsage") || nlpc.equals("LoopActionUsage")) {
                                skipNestedLoop = true;
                                break;
                            }
                            if (nlpc.contains("ActionDefinition") || nlpc.contains("Package")) {
                                break;
                            }
                            nlParent = nlParent.eContainer();
                        }
                        if (skipNestedLoop) {
                            // 注册 name->id 映射 (供外层循环 body 处理引用)
                            if (name != null) {
                                MainRunner.uuidToNameMap.put(id, name);
                                MainRunner.nameToIdMap.put(name, id);
                            }
                            continue;
                        }
                    }
                    
                    // 解析 IfActionUsage 及 Guard 条件
                    if (className.contains("IfActionUsage")) {
                        // 跳过 WhileLoop 内部的 IfActionUsage (由 WhileLoop 处理代码创建)
                        EObject ifParent = obj.eContainer();
                        boolean skipForWhileLoop = false;
                        while (ifParent != null) {
                            if (ifParent.eClass().getName().equals("WhileLoopActionUsage")) {
                                skipForWhileLoop = true;
                                break;
                            }
                            ifParent = ifParent.eContainer();
                        }
                        if (skipForWhileLoop) {
                            if (name != null) {
                                MainRunner.uuidToNameMap.put(id, name);
                                MainRunner.nameToIdMap.put(name, id);
                            }
                            continue;
                        }
                        
                        // === 提取条件: 优先从 ParameterMembership -> OperatorExpression ===
                        String conditionText = null;
                        StringBuilder debugInfo = new StringBuilder();
                        
                        // 方法0 (优先): 从直接子元素 ParameterMembership 提取 OperatorExpression
                        for (EObject dc : obj.eContents()) {
                            if (dc.eClass().getName().contains("ParameterMembership")) {
                                for (EObject gc : dc.eContents()) {
                                    if (gc.eClass().getName().contains("OperatorExpression")) {
                                        String exprText = ExpressionUtils.buildExpressionText(gc);
                                        if (exprText != null && !exprText.isEmpty() && !exprText.contains(" . ") && ExpressionUtils.isValidExpression(exprText)) {
                                            conditionText = exprText;
                                        }
                                        break; // 只取第一个 ParameterMembership 中的条件
                                    }
                                }
                                if (conditionText != null) {
                                    break;
                                }
                            }
                        }
                        
                        // 方法1 (fallback): .sysml 正则提取 (位置感知)
                        if (conditionText == null || conditionText.isEmpty()) {
                            try {
                                String text = Files.readString(Paths.get(ctx.sysmlBasePath.replace(".sysmlx", ".sysml")));
                                Matcher m = Pattern.compile("if\\s+([^\\{]+)\\s*\\{").matcher(text);
                                int matchIdx = 0;
                                while (m.find()) {
                                    if (matchIdx == ctx.consumedIfRegexCount) {
                                        conditionText = m.group(1).trim();
                                        debugInfo.append("Used .sysml regex (pos=").append(ctx.consumedIfRegexCount).append("): ").append(conditionText).append("\n");
                                        break;
                                    }
                                    matchIdx++;
                                }
                                ctx.consumedIfRegexCount++;
                            } catch (Exception ignored) {
                                // ignored
                            }
                        }
                        
                        if (conditionText == null || conditionText.isEmpty()) {
                            conditionText = "true";
                            debugInfo.append("WARNING: Could not extract guard condition, using default 'true'\n");
                        }
                        
                        if (debugInfo.length() > 0) {
                            System.out.println("[DEBUG] IfActionUsage " + (name != null ? name : id) + ":\n" + debugInfo.toString());
                        }
                        
                        // === 创建 DecisionNode ===
                        String decisionName = conditionText + "?";
                        DecisionNode decision = (DecisionNode) ctx.activity.createOwnedNode(decisionName, UMLPackage.Literals.DECISION_NODE);
                        MainRunner.umlNodes.put(id, decision);
                        if (name != null) { MainRunner.uuidToNameMap.put(id, name); MainRunner.nameToIdMap.put(name, id); }
                        
                        if (lastCheckIfUuid != null) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(lastCheckIfUuid, id));
                        }
                        
                        // === 从 ParameterMembership 子元素提取分支并创建节点 ===
                        List<String> branchBodies = new ArrayList<>(); // 分支体 UUID
                        List<String> thenBranchIds = new ArrayList<>(); // 兼容后续 PASS
                        List<EObject> paramMemberships = new ArrayList<>();
                        for (EObject dc : obj.eContents()) {
                            if (dc.eClass().getName().contains("ParameterMembership")) {
                                paramMemberships.add(dc);
                            }
                        }
                        
                        // 第一个 PM 是条件, 后续 PM 是分支体
                        int branchCount = 0;
                        boolean hasNestedIf = false;
                        for (int pmIdx = 1; pmIdx < paramMemberships.size(); pmIdx++) {
                            EObject pm = paramMemberships.get(pmIdx);
                            for (EObject gc : pm.eContents()) {
                                if (!(gc instanceof org.omg.sysml.lang.sysml.Element)) continue;
                                String gcClass = gc.eClass().getName();
                                String gcId = ((org.omg.sysml.lang.sysml.Element) gc).getElementId();
                                
                                if (gcClass.contains("IfActionUsage")) {
                                    // 嵌套 else-if: 连接到嵌套 DecisionNode (会在后续迭代中创建)
                                    hasNestedIf = true;
                                    MainRunner.logicalEdges.add(new MainRunner.EdgeData(id, gcId, "else"));
                                    branchBodies.add(null); // 占位: merge node 连接由嵌套 if 自己处理
                                    branchCount++;
                                } else if (gcClass.contains("ActionUsage") || gcClass.contains("AssignmentActionUsage")) {
                                    // === 检查是否包含嵌套 IfActionUsage (if-in-if-then) ===
                                    EObject nestedIfInBranch = null;
                                    for (EObject deepGc : gc.eContents()) {
                                        if (deepGc.eClass().getName().contains("IfActionUsage") && !deepGc.eClass().getName().contains("While")) {
                                            nestedIfInBranch = deepGc;
                                            break;
                                        }
                                    }
                                    
                                    if (nestedIfInBranch != null) {
                                        // 分支体是嵌套 if: 连接到嵌套 if 的 DecisionNode
                                        String nestedIfId = ((org.omg.sysml.lang.sysml.Element) nestedIfInBranch).getElementId();
                                        String nestedIfMergeId = nestedIfId + "_ifMerge";
                                        String guardStr = (branchCount == 0) ? conditionText : "else";
                                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(id, nestedIfId, guardStr));
                                        branchBodies.add(nestedIfMergeId); // 嵌套 if 的 MergeNode 连到外层 merge
                                        hasNestedIf = true;
                                        branchCount++;
                                    } else {
                                        // 普通分支体: 创建 CallBehaviorAction
                                        String gcName = ((org.omg.sysml.lang.sysml.Element) gc).getDeclaredName();
                                        String expr = ExpressionUtils.extractAssignmentText(gc);
                                        String nodeName;
                                        String bodyText;
                                        if (gcName != null && !gcName.isEmpty()) {
                                            nodeName = gcName;
                                            bodyText = null;
                                        } else if (!expr.isEmpty()) {
                                            nodeName = expr.replaceAll("[^a-zA-Z0-9_]", "_");
                                            bodyText = expr;
                                        } else {
                                            nodeName = ExpressionUtils.sanitizeName(gcClass) + "_" + gcId.substring(0, 8);
                                            bodyText = null;
                                        }
                                        
                                        ActivityNode branchNode;
                                        if (bodyText != null && !bodyText.isEmpty() && bodyText.contains("=")) {
                                            branchNode = UmlHelper.createOpaqueActionForAssignment(ctx.activity, nodeName, bodyText);
                                        } else {
                                            branchNode = UmlHelper.createCallBehaviorActionWithBody(ctx.activity, nodeName, bodyText, "SysMLv2");
                                        }
                                        MainRunner.umlNodes.put(gcId, branchNode);
                                        MainRunner.uuidToNameMap.put(gcId, nodeName);
                                        MainRunner.nameToIdMap.put(nodeName, gcId);
                                        
                                        String guardStr = (branchCount == 0) ? conditionText : "else";
                                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(id, gcId, guardStr));
                                        branchBodies.add(gcId);
                                        branchCount++;
                                    }
                                }
                            }
                        }
                        
                        // === 创建 MergeNode 汇聚所有分支出口 ===
                        String mergeId = id + "_ifMerge";
                        String mergeName = (name != null ? name : decisionName.replace("?", "")) + "_Merge";
                        MergeNode mergeNode = (MergeNode) ctx.activity.createOwnedNode(mergeName, UMLPackage.Literals.MERGE_NODE);
                        MainRunner.umlNodes.put(mergeId, mergeNode);
                        MainRunner.topLevelIfMergeIds.put(id, mergeId);
                        
                        // 连接每个分支体到 MergeNode
                        for (String bodyId : branchBodies) {
                            if (bodyId != null) {
                                MainRunner.logicalEdges.add(new MainRunner.EdgeData(bodyId, mergeId));
                            }
                        }
                        
                        // 如果没有 else 分支且没有嵌套 else-if, 直接连 Decision -> MergeNode (guard="else")
                        if (branchCount <= 1 && !hasNestedIf) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(id, mergeId, "else"));
                        }
                        
                        // 如果有嵌套 else-if, 连接嵌套 if 的 MergeNode 到本级 MergeNode
                        if (hasNestedIf) {
                            for (int pmIdx2 = 1; pmIdx2 < paramMemberships.size(); pmIdx2++) {
                                EObject pm2 = paramMemberships.get(pmIdx2);
                                for (EObject gc2 : pm2.eContents()) {
                                    if (!(gc2 instanceof org.omg.sysml.lang.sysml.Element)) continue;
                                    if (gc2.eClass().getName().contains("IfActionUsage")) {
                                        String nestedMergeId = ((org.omg.sysml.lang.sysml.Element) gc2).getElementId() + "_ifMerge";
                                        MainRunner.logicalEdges.add(new MainRunner.EdgeData(nestedMergeId, mergeId));
                                    }
                                }
                            }
                        }
                        
                        // 记录到 thenBranchIds 以兼容后续 PASS 2c/2e 处理
                        for (String bodyId : branchBodies) {
                            if (bodyId != null) {
                                thenBranchIds.add(bodyId);
                            }
                        }
                    }
                    // 解析循环结构 (LoopActionUsage) -- 用 equals 避免匹配 WhileLoopActionUsage
                    else if (className.equals("LoopActionUsage")) {
                        String mergeId = id + "_loopMerge";
                        String decisionId = id + "_loopDecision";
                        MergeNode merge = (MergeNode) ctx.activity.createOwnedNode(
                            (name != null ? name : "Loop") + "_Merge", UMLPackage.Literals.MERGE_NODE);
                        DecisionNode decision = (DecisionNode) ctx.activity.createOwnedNode(
                            (name != null ? name : "Loop") + "_Decision", UMLPackage.Literals.DECISION_NODE);
                        MainRunner.umlNodes.put(mergeId, merge);
                        MainRunner.umlNodes.put(decisionId, decision);
                        MainRunner.loopStartMerge.put(id, mergeId);
                        MainRunner.loopEndDecision.put(id, decisionId);

                        // 提取循环条件 - 按 ParameterMembership 位置提取
                        // PM 1 = body, PM 2 (若存在) = until 条件
                        String condText = null;
                        StringBuilder debugInfo = new StringBuilder();
                        
                        // 从第 2 个 PM (until 条件) 提取
                        {
                            int pmIdx = 0;
                            for (EObject child : obj.eContents()) {
                                if (!child.eClass().getName().equals("ParameterMembership")) continue;
                                pmIdx++;
                                if (pmIdx == 2) {
                                    // 递归搜索条件表达式 (Expression 或 ReferenceUsage)
                                    for (java.util.Iterator<EObject> deepIt = child.eAllContents(); deepIt.hasNext(); ) {
                                        EObject deep = deepIt.next();
                                        String deepCn = deep.eClass().getName();
                                        if (deepCn.contains("Expression") && condText == null) {
                                            String exprText = ExpressionUtils.buildExpressionText(deep);
                                            if (exprText != null && !exprText.isEmpty() && !exprText.contains(" . ") && ExpressionUtils.isValidExpression(exprText)) {
                                                condText = exprText;
                                            }
                                        } else if (deepCn.equals("ReferenceUsage") && condText == null) {
                                            if (deep instanceof org.omg.sysml.lang.sysml.Element) {
                                                String refName = ((org.omg.sysml.lang.sysml.Element) deep).getDeclaredName();
                                                if (refName != null && !refName.isEmpty()) {
                                                    condText = refName;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Fallback 已移除: 不再从 obj.eAllContents 全局搜索,
                        // 避免从 body 内部错误提取表达式作为 until 条件。
                        // 如果 PM2 不存在或提取失败, 直接默认 "true" (无条件循环)。
                        
                        if (condText == null || condText.isEmpty()) {
                            condText = "true";
                            debugInfo.append("WARNING: Could not extract loop condition, using default 'true'\n");
                        }
                        
                        MainRunner.loopConditionText.put(id, condText);
                        MainRunner.loopIsUntilIds.add(id);  // LoopActionUsage is always an until loop
                        if (debugInfo.length() > 0) {
                            System.out.println("[DEBUG] LoopActionUsage " + (name != null ? name : id) + ":\n" + debugInfo.toString());
                        }
                        
                        // 收集 body 中的子 Action ID (含嵌套循环展开)
                        List<String> bodyActions = new ArrayList<>();
                        // 先检查 PM1 中的嵌套循环 (loop { while... } until ...)
                        {
                            int pmI = 0;
                            for (EObject child : obj.eContents()) {
                                if (!child.eClass().getName().equals("ParameterMembership")) continue;
                                pmI++;
                                if (pmI == 1) {
                                    for (EObject ore : child.eContents()) {
                                        String oreCn = ore.eClass().getName();
                                        if (oreCn.equals("ForLoopActionUsage") || oreCn.equals("WhileLoopActionUsage") || oreCn.equals("LoopActionUsage")) {
                                            String[] res;
                                            if (oreCn.equals("ForLoopActionUsage")) res = LoopExpander.expandNestedForLoop(ctx.activity, ore, ctx.sysmlBasePath);
                                            else if (oreCn.equals("WhileLoopActionUsage")) res = LoopExpander.expandNestedWhileLoop(ctx.activity, ore, ctx.sysmlBasePath);
                                            else res = LoopExpander.expandNestedLoopAction(ctx.activity, ore, ctx.sysmlBasePath);
                                            bodyActions.add(res[0]);
                                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, res[0]));
                                            for (java.util.Iterator<EObject> it = ore.eAllContents(); it.hasNext(); ) {
                                                EObject d = it.next();
                                                if (d instanceof org.omg.sysml.lang.sysml.Element) {
                                                    String dId = ((org.omg.sysml.lang.sysml.Element) d).getElementId();
                                                    if (dId != null) {
                                                        MainRunner.loopBodyNodeIds.add(dId);
                                                    }
                                                }
                                            }
                                        } else if (oreCn.contains("ActionUsage") || oreCn.contains("ReferenceUsage")) {
                                            // 检查 ActionUsage wrapper 内部的嵌套循环
                                            EObject nestedLoop = null;
                                            for (EObject inner : ore.eContents()) {
                                                String innerCn = inner.eClass().getName();
                                                if (innerCn.equals("ForLoopActionUsage") || innerCn.equals("WhileLoopActionUsage") || innerCn.equals("LoopActionUsage")) {
                                                    nestedLoop = inner; break;
                                                }
                                            }
                                            if (nestedLoop != null) {
                                                String[] res;
                                                String nc = nestedLoop.eClass().getName();
                                                if (nc.equals("ForLoopActionUsage")) res = LoopExpander.expandNestedForLoop(ctx.activity, nestedLoop, ctx.sysmlBasePath);
                                                else if (nc.equals("WhileLoopActionUsage")) res = LoopExpander.expandNestedWhileLoop(ctx.activity, nestedLoop, ctx.sysmlBasePath);
                                                else res = LoopExpander.expandNestedLoopAction(ctx.activity, nestedLoop, ctx.sysmlBasePath);
                                                bodyActions.add(res[0]);
                                                MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, res[0]));
                                                for (java.util.Iterator<EObject> it = nestedLoop.eAllContents(); it.hasNext(); ) {
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
                                                if (cId != null) {
                                                    bodyActions.add(cId);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // fallback: 如果 PM1 没找到内容, 搜索直接子元素
                        if (bodyActions.isEmpty()) {
                            for (EObject child : obj.eContents()) {
                                if (child.eClass().getName().contains("ActionUsage") || child.eClass().getName().contains("ReferenceUsage")) {
                                    String cId = ((org.omg.sysml.lang.sysml.Element) child).getElementId();
                                    if (cId != null) {
                                        bodyActions.add(cId);
                                    }
                                }
                            }
                        }
                        MainRunner.loopBodyActions.put(id, bodyActions);

                        // 连接 MergeNode -> 第一个 body action (作为 loop entry)
                        if (!bodyActions.isEmpty()) {
                            if (!MainRunner.logicalEdges.stream().anyMatch(e -> e.source.equals(mergeId))) {
                                MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, bodyActions.get(0)));
                            }
                        }
                    }
                    // 解析 ForLoopActionUsage -> 展开为循环结构 (Merge->Body->Decision)
                    else if (className.equals("ForLoopActionUsage")) {
                        String mergeId = id + "_forLoopMerge";
                        String decisionId = id + "_forLoopDecision";
                        String loopName = (name != null ? name : "ForLoop");
                        MergeNode merge = (MergeNode) ctx.activity.createOwnedNode(
                            loopName + "_Merge", UMLPackage.Literals.MERGE_NODE);
                        DecisionNode decision = (DecisionNode) ctx.activity.createOwnedNode(
                            loopName + "_Decision", UMLPackage.Literals.DECISION_NODE);
                        MainRunner.umlNodes.put(mergeId, merge);
                        MainRunner.umlNodes.put(decisionId, decision);
                        MainRunner.loopStartMerge.put(id, mergeId);
                        MainRunner.loopEndDecision.put(id, decisionId);

                        // 提取循环变量名 (FeatureMembership -> ReferenceUsage)
                        String loopVarName = null;
                        for (EObject dc : obj.eContents()) {
                            if (dc.eClass().getName().contains("FeatureMembership")) {
                                for (EObject gc : dc.eContents()) {
                                    if (gc.eClass().getName().contains("ReferenceUsage")) {
                                        loopVarName = ((org.omg.sysml.lang.sysml.Element) gc).getDeclaredName();
                                        break;
                                    }
                                }
                                if (loopVarName != null) {
                                    break;
                                }
                            }
                        }

                        // 提取范围表达式 (第1个 ParameterMembership -> ReferenceUsage -> FeatureValue -> OperatorExpression)
                        String rangeText = null;
                        for (EObject dc : obj.eContents()) {
                            if (dc.eClass().getName().contains("ParameterMembership")) {
                                for (EObject gc : dc.eContents()) {
                                    if (gc.eClass().getName().contains("ReferenceUsage")) {
                                        for (EObject gg : gc.eContents()) {
                                            if (gg.eClass().getName().contains("FeatureValue")) {
                                                for (EObject expr : gg.eContents()) {
                                                    if (expr.eClass().getName().contains("OperatorExpression")) {
                                                        String op = null;
                                                        try {
                                                            var opFeat = expr.eClass().getEStructuralFeature("operator");
                                                            if (opFeat != null) {
                                                                op = (String) expr.eGet(opFeat);
                                                            }
                                                        } catch (Exception ignored) {
                                                            // ignored
                                                        }
                                                        // 收集 LiteralInteger 值
                                                        List<String> literals = new ArrayList<>();
                                                        for (java.util.Iterator<EObject> lit = expr.eAllContents(); lit.hasNext(); ) {
                                                            EObject litObj = lit.next();
                                                            if (litObj.eClass().getName().contains("LiteralInteger")) {
                                                                try {
                                                                    var valFeat = litObj.eClass().getEStructuralFeature("value");
                                                                    if (valFeat != null) {
                                                                        Object val = litObj.eGet(valFeat);
                                                                        if (val != null) {
                                                                            literals.add(val.toString());
                                                                        }
                                                                    }
                                                                } catch (Exception ignored) {
                                                                    // ignored
                                                                }
                                                            }
                                                        }
                                                        if (!literals.isEmpty()) {
                                                            rangeText = String.join(", ", literals);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                break; // 只取第一个 ParameterMembership
                            }
                        }

                        // 条件文本
                        String condText;
                        if (loopVarName != null && rangeText != null) {
                            condText = loopVarName + " in (" + rangeText + ")";
                        } else if (loopVarName != null) {
                            condText = "hasNext(" + loopVarName + ")";
                        } else {
                            condText = "hasNext";
                        }
                        MainRunner.loopConditionText.put(id, condText);

                        // 提取循环体 (第2个 ParameterMembership -> ActionUsage -> 可能含嵌套循环)
                        int pmCount = 0;
                        String bodyActionId = null;
                        for (EObject dc : obj.eContents()) {
                            if (dc.eClass().getName().contains("ParameterMembership")) {
                                pmCount++;
                                if (pmCount == 2) { // 第2个 PM 是循环体
                                    for (EObject gc : dc.eContents()) {
                                        String gcClass = gc.eClass().getName();
                                        if (!(gc instanceof org.omg.sysml.lang.sysml.Element)) continue;
                                        String gcId = ((org.omg.sysml.lang.sysml.Element) gc).getElementId();
                                        
                                        // 检查 ActionUsage 内部是否包含嵌套循环
                                        if (gcClass.contains("ActionUsage") || gcClass.contains("AssignmentActionUsage")) {
                                            EObject nestedLoop = null;
                                            for (EObject inner : gc.eContents()) {
                                                String innerCn = inner.eClass().getName();
                                                if (innerCn.equals("ForLoopActionUsage") || innerCn.equals("WhileLoopActionUsage") || innerCn.equals("LoopActionUsage")) {
                                                    nestedLoop = inner;
                                                    break;
                                                }
                                            }
                                            if (nestedLoop != null) {
                                                // 嵌套循环: 展开并连接到外层 for
                                                String nc = nestedLoop.eClass().getName();
                                                String[] res;
                                                if (nc.equals("ForLoopActionUsage")) res = LoopExpander.expandNestedForLoop(ctx.activity, nestedLoop, ctx.sysmlBasePath);
                                                else if (nc.equals("WhileLoopActionUsage")) res = LoopExpander.expandNestedWhileLoop(ctx.activity, nestedLoop, ctx.sysmlBasePath);
                                                else res = LoopExpander.expandNestedLoopAction(ctx.activity, nestedLoop, ctx.sysmlBasePath);
                                                bodyActionId = res[0]; // 用内层入口作为 body 代理
                                                // 连接: 外层 merge -> 内层入口,  内层出口 -> 外层 merge (回边)
                                                MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, res[0]));
                                                MainRunner.logicalEdges.add(new MainRunner.EdgeData(res[1], mergeId));
                                                // 将嵌套循环的所有节点加入 loopBodyNodeIds
                                                for (java.util.Iterator<EObject> it = nestedLoop.eAllContents(); it.hasNext(); ) {
                                                    EObject d = it.next();
                                                    if (d instanceof org.omg.sysml.lang.sysml.Element) {
                                                        String dId = ((org.omg.sysml.lang.sysml.Element) d).getElementId();
                                                        if (dId != null) {
                                                            MainRunner.loopBodyNodeIds.add(dId);
                                                        }
                                                    }
                                                }
                                                break;
                                            }
                                            // 普通动作 (无嵌套循环)
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
                                                bodyNode = UmlHelper.createOpaqueActionForAssignment(ctx.activity, nodeName, expr);
                                            } else {
                                                bodyNode = UmlHelper.createCallBehaviorActionWithBody(ctx.activity, nodeName, bodyText, "SysMLv2");
                                            }
                                            MainRunner.umlNodes.put(gcId, bodyNode);
                                            MainRunner.uuidToNameMap.put(gcId, nodeName);
                                            bodyActionId = gcId;
                                            break;
                                        }
                                    }
                                    break;
                                }
                            }
                        }

                        // 连接: Merge -> Body -> Decision (仅当 body 是普通动作时)
                        if (bodyActionId != null && !MainRunner.logicalEdges.stream().anyMatch(e -> e.source.equals(mergeId))) {
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(mergeId, bodyActionId));
                            MainRunner.logicalEdges.add(new MainRunner.EdgeData(bodyActionId, decisionId));
                        }
                        {
                            List<String> bodyActions = new ArrayList<>();
                            if (bodyActionId != null) {
                                bodyActions.add(bodyActionId);
                            }
                            MainRunner.loopBodyActions.put(id, bodyActions);
                        }

                        // 将 for 循环体内所有 ActionUsage/AssignmentActionUsage 加入 loopBodyNodeIds
                        // 防止 PASS 4 endpoint fallback 将这些内部节点误连 Start/End
                        for (java.util.Iterator<EObject> deepIt = obj.eAllContents(); deepIt.hasNext(); ) {
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
                                        // ignored
                                    }
                                }
                            }
                        }
                        // 也将 bodyActionId 自身加入
                        if (bodyActionId != null) {
                            MainRunner.loopBodyNodeIds.add(bodyActionId);
                        }

                        System.out.println("[FOR-LOOP] id=" + id.substring(0, Math.min(8, id.length()))
                            + " var=" + loopVarName + " range=" + rangeText
                            + " body=" + (bodyActionId != null ? bodyActionId.substring(0, Math.min(8, bodyActionId.length())) : "null"));
                    }
                    // 解析 WhileLoopActionUsage -> 展开
                    else if (className.equals("WhileLoopActionUsage")) {
                        // ===== 提取条件: 优先从 ParameterMembership 子元素按位置提取 =====
                        // 第1个 PM = while 条件, 第3个 PM (若存在) = until 条件
                        String whileCondText = null;
                        String untilCondText = null;
                        int pmIdx = 0;
                        for (EObject child : obj.eContents()) {
                            if (!child.eClass().getName().equals("ParameterMembership")) continue;
                            pmIdx++;
                            if (pmIdx == 1 || pmIdx == 3) {
                                // 在 PM 中搜索条件表达式 (递归搜索, 处理 FeatureMembership 嵌套)
                                String condFromPM = null;
                                for (java.util.Iterator<EObject> deepIt = child.eAllContents(); deepIt.hasNext(); ) {
                                    EObject deep = deepIt.next();
                                    if (condFromPM != null) {
                                        break; // 已找到, 不再搜索
                                    }
                                    String deepCn = deep.eClass().getName();
                                    if (deepCn.contains("Expression")) {
                                        // OperatorExpression, FeatureReferenceExpression, etc.
                                        String astText = ExpressionUtils.buildExpressionText(deep);
                                        if (astText != null && !astText.isEmpty() && !astText.contains(" . ") && ExpressionUtils.isValidExpression(astText)) {
                                            condFromPM = astText;
                                        }
                                    } else if (deepCn.equals("ReferenceUsage")) {
                                        // 简单变量引用 (如 until b 中的 b)
                                        if (deep instanceof org.omg.sysml.lang.sysml.Element) {
                                            String refName = ((org.omg.sysml.lang.sysml.Element) deep).getDeclaredName();
                                            if (refName != null && !refName.isEmpty()) {
                                                condFromPM = refName;
                                            }
                                        }
                                    }
                                }
                                if (condFromPM != null && !condFromPM.isEmpty()) {
                                    if (pmIdx == 1) {
                                        whileCondText = condFromPM;
                                    }
                                    else untilCondText = condFromPM;
                                }
                            }
                        }
                        // 全局正则 fallback 已移除: 正则会搜索整个文件, 导致跨循环条件串扰
                        // (例: 纯 while 误获 until b, 第2个 while 变成 while+until)
                        // AST 按位置提取 (PM1=while, PM3=until) 已足够可靠
                        
                        boolean hasWhile = whileCondText != null && !whileCondText.isEmpty();
                        boolean hasUntil = untilCondText != null && !untilCondText.isEmpty();
                        boolean isUntil = hasUntil && !hasWhile;
                        
                        // condText 用于 loopConditionText (PASS 4 回边守卫)
                        String condText;
                        if (hasUntil) {
                            condText = untilCondText;
                        } else if (hasWhile) {
                            condText = whileCondText;
                        }
                        else condText = "";
                        
                        MainRunner.whileLoopCondText.put(id, condText);
                        MainRunner.whileLoopIsUntil.put(id, isUntil);
                        MainRunner.whileLoopIsWhileUntil.put(id, hasWhile && hasUntil);
                        // postLoopAction: 全局正则已移除 (跨循环串扰风险), 暂置 null
                        String postLoopAction = null;
                        MainRunner.whileLoopPostLoopActions.put(id, postLoopAction);
                        
                        // ===== 收集 body 内所有元素 (包括 IfActionUsage) =====
                        List<EObject> bodyElements = new ArrayList<>();
                        for (EObject child : obj.eContents()) {
                            if (!child.eClass().getName().equals("ParameterMembership")) continue;
                            for (EObject ore : child.eContents()) {
                                if (!ore.eClass().getName().equals("ActionUsage")) continue;
                                for (EObject fm : ore.eContents()) {
                                    if (!fm.eClass().getName().equals("FeatureMembership")) continue;
                                    for (EObject inner : fm.eContents()) {
                                        String ic = inner.eClass().getName();
                                        if (ic.equals("SuccessionAsUsage") || ic.equals("ParameterMembership")) {
                                            continue;
                                        }
                                        bodyElements.add(inner);
                                    }
                                }
                            }
                        }
                        
                        // ===== 创建循环入口 MergeNode =====
                        String mergeId = id + "_merge";
                        String untilDecId = id + "_untilDecision";
                        String mergeName = name != null ? name + "_Merge" : "Loop_Merge";
                        MergeNode merge = (MergeNode) ctx.activity.createOwnedNode(mergeName, UMLPackage.Literals.MERGE_NODE);
                        MainRunner.umlNodes.put(mergeId, merge);
                        MainRunner.whileLoopMergeIds.put(id, mergeId);
                        
                        // ===== 创建 until DecisionNode (循环末尾的条件判断) =====
                        String untilDecName = condText.isEmpty() ? "cond?" : condText + "?";
                        DecisionNode untilDecision = (DecisionNode) ctx.activity.createOwnedNode(untilDecName, UMLPackage.Literals.DECISION_NODE);
                        MainRunner.umlNodes.put(untilDecId, untilDecision);
                        MainRunner.whileLoopDecisionIds.put(id, untilDecId);
                        
                        // ===== 创建 body 节点 (按文档顺序) =====
                        List<String> bodyNodeIds = new ArrayList<>();
                        Map<String, String> ifConditionTexts = new HashMap<>();
                        Map<String, List<String>> ifThenBranchIds = new HashMap<>();
                        Map<String, String> ifDecisionIds = new HashMap<>();
                        Map<String, String> ifMergeIds = new HashMap<>();
                        
                        int bodyIdx = 0;
                        for (EObject ba : bodyElements) {
                            String baClass = ba.eClass().getName();
                            String baId;
                            String baName = null;
                            if (ba instanceof org.omg.sysml.lang.sysml.Element) {
                                baId = ((org.omg.sysml.lang.sysml.Element) ba).getElementId();
                                baName = ((org.omg.sysml.lang.sysml.Element) ba).getDeclaredName();
                            } else {
                                baId = id + "_body" + bodyIdx;
                            }
                            
                            // 处理 IfActionUsage: 创建 DecisionNode (不需要单独的 ifMerge)
                            // 在 while loop 中, if 的两个分支最终都会回到 loop 的 MergeNode
                            if (baClass.equals("IfActionUsage")) {
                                // 优先使用 .sysml regex 提取 if 条件 (比 AST 更可靠, 因为 EMF 代理对象无法直接获取名称)
                                String ifCondText = null;
                                try {
                                    String text = Files.readString(Paths.get(ctx.sysmlBasePath.replace(".sysmlx", ".sysml")));
                                    Matcher m = Pattern.compile("if\\s+([^\\{]+)\\s*\\{").matcher(text);
                                    if (m.find()) ifCondText = m.group(1).trim();
                                } catch (Exception ignored) {
                                    // ignored
                                }
                                
                                // AST 提取作为 fallback
                                if (ifCondText == null || ifCondText.isEmpty()) {
                                    for (java.util.Iterator<EObject> it = ba.eAllContents(); it.hasNext(); ) {
                                        EObject ifChild = it.next();
                                        if (ifChild.eClass().getName().contains("OperatorExpression")) {
                                            String exprText = ExpressionUtils.buildExpressionText(ifChild);
                                            // 验证: 表达式必须包含至少两个操作数 (形如 "a < b"), 不能只是 "100 <" 这种不完整形式
                                            if (exprText != null && !exprText.isEmpty() && !exprText.contains(" . ") && ExpressionUtils.isValidExpression(exprText)) {
                                                ifCondText = exprText;
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (ifCondText == null || ifCondText.isEmpty()) ifCondText = "true";
                                
                                String ifDecId = baId + "_ifDecision";
                                String ifDecName = ifCondText + "?";
                                DecisionNode ifDecision = (DecisionNode) ctx.activity.createOwnedNode(ifDecName, UMLPackage.Literals.DECISION_NODE);
                                MainRunner.umlNodes.put(ifDecId, ifDecision);
                                ifDecisionIds.put(baId, ifDecId);
                                ifConditionTexts.put(baId, ifCondText);
                                
                                // 不再创建 ifMerge 节点 -- if 的两个分支都直接回到 loop 的 MergeNode
                                // ifMergeIds 保持为空，在 buildBodyInternalFlows 中不再需要
                                
                                List<String> thenBranchIds = new ArrayList<>();
                                for (java.util.Iterator<EObject> it = ba.eAllContents(); it.hasNext(); ) {
                                    EObject ifChild = it.next();
                                    if (ifChild instanceof org.omg.sysml.lang.sysml.Element) {
                                        String ic2 = ifChild.eClass().getName();
                                        String in = ((org.omg.sysml.lang.sysml.Element) ifChild).getDeclaredName();
                                        String iid = ((org.omg.sysml.lang.sysml.Element) ifChild).getElementId();
                                        if (in != null && !in.isEmpty() && ic2.contains("ActionUsage") && !ic2.equals("IfActionUsage")) {
                                            thenBranchIds.add(iid);
                                        }
                                    }
                                }
                                ifThenBranchIds.put(baId, thenBranchIds);
                                
                                bodyNodeIds.add(ifDecId);
                                // 不再添加 ifMergeId 到 bodyNodeIds
                                MainRunner.loopBodyNodeIds.add(ifDecId);
                                // 不再添加 ifMergeId 到 loopBodyNodeIds
                                continue;
                            }
                            
                            // === 嵌套循环处理: WhileLoopActionUsage / ForLoopActionUsage / LoopActionUsage ===
                            if (baClass.equals("WhileLoopActionUsage") || baClass.equals("ForLoopActionUsage") || baClass.equals("LoopActionUsage")) {
                                String[] res;
                                if (baClass.equals("WhileLoopActionUsage"))
                                    res = LoopExpander.expandNestedWhileLoop(ctx.activity, ba, ctx.sysmlBasePath);
                                else if (baClass.equals("ForLoopActionUsage"))
                                    res = LoopExpander.expandNestedForLoop(ctx.activity, ba, ctx.sysmlBasePath);
                                else
                                    res = LoopExpander.expandNestedLoopAction(ctx.activity, ba, ctx.sysmlBasePath);
                                bodyNodeIds.add(res[0]);
                                MainRunner.loopBodyNodeIds.add(res[0]);
                                // 将嵌套循环的所有内部节点加入 loopBodyNodeIds
                                for (java.util.Iterator<EObject> deepIt = ba.eAllContents(); deepIt.hasNext(); ) {
                                    EObject deep = deepIt.next();
                                    if (deep instanceof org.omg.sysml.lang.sysml.Element) {
                                        String deepId = ((org.omg.sysml.lang.sysml.Element) deep).getElementId();
                                        if (deepId != null) {
                                            MainRunner.loopBodyNodeIds.add(deepId);
                                        }
                                    }
                                }
                                bodyIdx++;
                                continue;
                            }
                            
                            // 常规 ActionUsage
                            ActivityNode bodyNode = null;
                            if (baClass.contains("ActionUsage") && baName != null && !baName.isEmpty()) {
                                java.util.List<?> typedList = null;
                                try {
                                    var feat = ba.eClass().getEStructuralFeature("type");
                                    if (feat == null) feat = ba.eClass().getEStructuralFeature("typed");
                                    if (feat == null) feat = ba.eClass().getEStructuralFeature("referencedType");
                                    if (feat != null) {
                                        Object val = ba.eGet(feat);
                                        if (val instanceof java.util.List) typedList = (java.util.List<?>) val;
                                        else if (val instanceof EObject) typedList = java.util.Collections.singletonList(val);
                                    }
                                } catch (Exception ignored) {
                                    // ignored
                                }
                                if (typedList != null && !typedList.isEmpty() && typedList.get(0) instanceof EObject) {
                                    String typedClassName = ((EObject) typedList.get(0)).eClass().getName();
                                    if (typedClassName.contains("ActionDefinition")) {
                                        bodyNode = UmlHelper.createCallBehaviorAction(ctx.activity, baName);
                                    }
                                }
                                if (bodyNode == null) {
                                    bodyNode = UmlHelper.createCallBehaviorAction(ctx.activity, baName);
                                }
                                MainRunner.uuidToNameMap.put(baId, baName);
                                MainRunner.nameToIdMap.put(baName, baId);
                            }
                            if (bodyNode == null) {
                                String expr = ExpressionUtils.extractAssignmentText(ba);
                                String nodeName = expr.isEmpty() ? (baName != null ? baName : ExpressionUtils.sanitizeName(baClass)) : expr.replaceAll("[^a-zA-Z0-9_]", "_");
                                String bodyText = expr.isEmpty() ? null : expr;
                                if (bodyText != null && !bodyText.isEmpty() && bodyText.contains("=")) {
                                    bodyNode = UmlHelper.createOpaqueActionForAssignment(ctx.activity, nodeName, bodyText);
                                } else {
                                    bodyNode = UmlHelper.createCallBehaviorActionWithBody(ctx.activity, nodeName, bodyText, "SysMLv2");
                                }
                            }
                            MainRunner.umlNodes.put(baId, bodyNode);
                            bodyNodeIds.add(baId);
                            MainRunner.loopBodyNodeIds.add(baId);
                            bodyIdx++;
                        }
                        
                        // ===== 创建 then branch 子动作节点 =====
                        for (String ifId : ifThenBranchIds.keySet()) {
                            for (String thenId : ifThenBranchIds.get(ifId)) {
                                if (MainRunner.umlNodes.containsKey(thenId)) continue;
                                String thenName = null;
                                var it2 = resource.getAllContents();
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
                                    ActivityNode thenNode = UmlHelper.createCallBehaviorAction(ctx.activity, thenName);
                                    MainRunner.umlNodes.put(thenId, thenNode);
                                    MainRunner.uuidToNameMap.put(thenId, thenName);
                                    MainRunner.nameToIdMap.put(thenName, thenId);
                                    MainRunner.loopBodyNodeIds.add(thenId);
                                }
                            }
                        }
                        
                        // ===== 构建内部控制流 =====
                        if (isUntil) {
                            // until 循环: Merge -> body -> untilDecision -> (cond: exit, else: Merge)
                            if (!bodyNodeIds.isEmpty()) {
                                ControlFlow mergeToBody = UMLFactory.eINSTANCE.createControlFlow();
                                mergeToBody.setSource(merge);
                                mergeToBody.setTarget(MainRunner.umlNodes.get(bodyNodeIds.get(0)));
                                ctx.activity.getEdges().add(mergeToBody);
                                
                                // body 内部顺序流 (含 if 结构)
                                LoopExpander.buildBodyInternalFlows(ctx.activity, bodyNodeIds, ifDecisionIds, ifMergeIds, ifConditionTexts, ifThenBranchIds, MainRunner.umlNodes, merge, untilDecision);
                                
                                // 判断 body 最后一个节点是否是 ifDecision
                                // 如果是, ifDecision 的 else 分支已由 buildBodyInternalFlows 连到 untilDecision
                                // 如果不是 (普通动作), 需要手动连到 untilDecision
                                String lastBodyId = bodyNodeIds.get(bodyNodeIds.size() - 1);
                                boolean lastIsIfDecision = false;
                                for (String ifDecId : ifDecisionIds.values()) {
                                    if (lastBodyId.equals(ifDecId)) {
                                        lastIsIfDecision = true;
                                        break;
                                    }
                                }
                                if (!lastIsIfDecision) {
                                    // 普通动作 -> untilDecision
                                    ControlFlow toUntilDec = UMLFactory.eINSTANCE.createControlFlow();
                                    toUntilDec.setSource(MainRunner.umlNodes.get(lastBodyId));
                                    toUntilDec.setTarget(untilDecision);
                                    ctx.activity.getEdges().add(toUntilDec);
                                }
                                // 如果 body 最后是 ifDecision, 其 else 分支已经连到 untilDecision
                            }
                            
                            // 回边: untilDecision -> Merge (继续循环, guard=else)
                            ControlFlow backEdge = UMLFactory.eINSTANCE.createControlFlow();
                            backEdge.setSource(untilDecision);
                            backEdge.setTarget(merge);
                            OpaqueExpression backGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                            backGuard.getBodies().add("else");
                            backEdge.setGuard(backGuard);
                            ctx.activity.getEdges().add(backEdge);
                        } else if (hasWhile && hasUntil) {
                            // ===== while+until 双条件循环 =====
                            // Merge -> whileDecision(while_cond) -> body -> untilDecision(until_cond)
                            // 出口边由 PASS 4 统一处理 (endNode 在此作用域不可用)
                            
                            // 1. 创建入口 DecisionNode (while 条件)
                            String whileDecId = id + "_whileEntryDecision";
                            String whileDecName = whileCondText + "?";
                            DecisionNode whileDecision = (DecisionNode) ctx.activity.createOwnedNode(whileDecName, UMLPackage.Literals.DECISION_NODE);
                            MainRunner.umlNodes.put(whileDecId, whileDecision);
                            MainRunner.whileLoopEntryIds.put(id, whileDecId);
                            
                            // 2. Merge -> whileDecision
                            ControlFlow mergeToWhileDec = UMLFactory.eINSTANCE.createControlFlow();
                            mergeToWhileDec.setSource(merge);
                            mergeToWhileDec.setTarget(whileDecision);
                            ctx.activity.getEdges().add(mergeToWhileDec);
                            
                            // 3. whileDecision(true/whileCond) -> body[0]
                            if (!bodyNodeIds.isEmpty()) {
                                ControlFlow trueBranch = UMLFactory.eINSTANCE.createControlFlow();
                                trueBranch.setSource(whileDecision);
                                trueBranch.setTarget(MainRunner.umlNodes.get(bodyNodeIds.get(0)));
                                OpaqueExpression whileGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                                whileGuard.getBodies().add(whileCondText);
                                trueBranch.setGuard(whileGuard);
                                ctx.activity.getEdges().add(trueBranch);
                            }
                            
                            // 4. whileDecision(false/else) -> 由 PASS 4 添加 (endNode 在此不可用)
                            
                            // 5. body -> untilDecision (内部流)
                            if (!bodyNodeIds.isEmpty()) {
                                LoopExpander.buildBodyInternalFlows(ctx.activity, bodyNodeIds, ifDecisionIds, ifMergeIds, ifConditionTexts, ifThenBranchIds, MainRunner.umlNodes, merge, untilDecision);
                                
                                // 连接 body 最后一个节点 -> untilDecision (如果不是 ifDecision)
                                String lastBodyId = bodyNodeIds.get(bodyNodeIds.size() - 1);
                                boolean lastIsIfDecision = false;
                                for (String ifDecId : ifDecisionIds.values()) {
                                    if (lastBodyId.equals(ifDecId)) {
                                        lastIsIfDecision = true;
                                        break;
                                    }
                                }
                                if (!lastIsIfDecision) {
                                    ControlFlow toUntilDec = UMLFactory.eINSTANCE.createControlFlow();
                                    toUntilDec.setSource(MainRunner.umlNodes.get(lastBodyId));
                                    toUntilDec.setTarget(untilDecision);
                                    ctx.activity.getEdges().add(toUntilDec);
                                }
                            }
                            
                            // 6. 创建出口 MergeNode (while+until 统一退出点)
                            String exitMergeId = id + "_whileUntilExitMerge";
                            String exitMergeName = (name != null ? name : "Loop") + "_ExitMerge";
                            MergeNode exitMerge = (MergeNode) ctx.activity.createOwnedNode(exitMergeName, UMLPackage.Literals.MERGE_NODE);
                            MainRunner.umlNodes.put(exitMergeId, exitMerge);
                            MainRunner.whileLoopExitMergeIds.put(id, exitMergeId);
                            
                            // 7. whileDecision(false/else) -> exitMerge (while条件不满足,跳过整个循环)
                            ControlFlow whileFalseToExit = UMLFactory.eINSTANCE.createControlFlow();
                            whileFalseToExit.setSource(whileDecision);
                            whileFalseToExit.setTarget(exitMerge);
                            OpaqueExpression whileFalseGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                            whileFalseGuard.getBodies().add("else");
                            whileFalseToExit.setGuard(whileFalseGuard);
                            ctx.activity.getEdges().add(whileFalseToExit);
                            
                            // 8. untilDecision(untilCond) -> exitMerge (until条件满足,退出循环)
                            ControlFlow untilToExit = UMLFactory.eINSTANCE.createControlFlow();
                            untilToExit.setSource(untilDecision);
                            untilToExit.setTarget(exitMerge);
                            OpaqueExpression untilGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                            untilGuard.getBodies().add(untilCondText);
                            untilToExit.setGuard(untilGuard);
                            ctx.activity.getEdges().add(untilToExit);
                            
                            // 9. untilDecision(else) -> Merge (until条件不满足,继续循环)
                            ControlFlow backEdge2 = UMLFactory.eINSTANCE.createControlFlow();
                            backEdge2.setSource(untilDecision);
                            backEdge2.setTarget(merge);
                            OpaqueExpression backGuard2 = UMLFactory.eINSTANCE.createOpaqueExpression();
                            backGuard2.getBodies().add("else");
                            backEdge2.setGuard(backGuard2);
                            ctx.activity.getEdges().add(backEdge2);
                            
                            System.out.println("[WHILE-UNTIL] id=" + id.substring(0, Math.min(8, id.length()))
                                + " while=" + whileCondText + " until=" + untilCondText);
                        } else {
                            // while 循环: Merge -> whileDecision -> body -> Merge (回边)
                            //                whileDecision(else) -> ExitMerge (独立出口)
                            ControlFlow mergeToDec = UMLFactory.eINSTANCE.createControlFlow();
                            mergeToDec.setSource(merge);
                            mergeToDec.setTarget(untilDecision);
                            ctx.activity.getEdges().add(mergeToDec);
                            
                            // 创建纯 while 的独立 ExitMerge (分离出口和回边)
                            String pureExitMergeId = id + "_pureWhileExitMerge";
                            String pureExitMergeName = (name != null ? name : "Loop") + "_ExitMerge";
                            MergeNode pureExitMerge = (MergeNode) ctx.activity.createOwnedNode(pureExitMergeName, UMLPackage.Literals.MERGE_NODE);
                            MainRunner.umlNodes.put(pureExitMergeId, pureExitMerge);
                            MainRunner.whileLoopPureExitMergeIds.put(id, pureExitMergeId);
                            
                            // Decision(else) -> ExitMerge (循环退出路径)
                            ControlFlow exitToMerge = UMLFactory.eINSTANCE.createControlFlow();
                            exitToMerge.setSource(untilDecision);
                            exitToMerge.setTarget(pureExitMerge);
                            OpaqueExpression exitGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                            exitGuard.getBodies().add("else");
                            exitToMerge.setGuard(exitGuard);
                            ctx.activity.getEdges().add(exitToMerge);
                            
                            if (!bodyNodeIds.isEmpty()) {
                                ControlFlow trueBranch = UMLFactory.eINSTANCE.createControlFlow();
                                trueBranch.setSource(untilDecision);
                                trueBranch.setTarget(MainRunner.umlNodes.get(bodyNodeIds.get(0)));
                                OpaqueExpression trueGuard = UMLFactory.eINSTANCE.createOpaqueExpression();
                                trueGuard.getBodies().add(condText);
                                trueBranch.setGuard(trueGuard);
                                ctx.activity.getEdges().add(trueBranch);
                                
                                LoopExpander.buildBodyInternalFlows(ctx.activity, bodyNodeIds, ifDecisionIds, ifMergeIds, ifConditionTexts, ifThenBranchIds, MainRunner.umlNodes, merge, null);
                                
                                // while 循环: last body node -> Merge (回边, 循环继续)
                                String lastBodyId = bodyNodeIds.get(bodyNodeIds.size() - 1);
                                boolean lastIsIfDecision = false;
                                for (String ifDecId : ifDecisionIds.values()) {
                                    if (lastBodyId.equals(ifDecId)) {
                                        lastIsIfDecision = true;
                                        break;
                                    }
                                }
                                if (!lastIsIfDecision) {
                                    ControlFlow backEdge = UMLFactory.eINSTANCE.createControlFlow();
                                    backEdge.setSource(MainRunner.umlNodes.get(lastBodyId));
                                    backEdge.setTarget(merge);
                                    ctx.activity.getEdges().add(backEdge);
                                }
                            }
                        }
                    }
                    // 解析赋值操作 (OperatorExpression)
                    else if (className.contains("ReferenceUsage")) {
                        boolean hasAssignment = false;
                        for (EObject child : obj.eContents()) {
                            if (child.eClass().getName().contains("FeatureValue")) {
                                for (EObject val : child.eContents()) {
                                    if (val.eClass().getName().contains("OperatorExpression")) hasAssignment = true;
                                }
                            }
                        }
                        if (hasAssignment && name != null) {
                            CallBehaviorAction assignAct = UmlHelper.createCallBehaviorAction(ctx.activity, "Assign_" + name);
                            MainRunner.umlNodes.put(id, assignAct);
                        }
                        // 非赋值 ReferenceUsage -> 判断是否为 decision/merge 占位符
                        else if (name != null && !name.isEmpty() && !ctx.existingNodeNames.contains(name) && !ExpressionUtils.isControlKeyword(name)) {
                            boolean isParam = false;
                            // 检测参数: 直接检查 direction 属性
                            try {
                                var dirFeat = obj.eClass().getEStructuralFeature("direction");
                                if (dirFeat != null) {
                                    Object dirVal = obj.eGet(dirFeat);
                                    if (dirVal instanceof String && !((String) dirVal).isEmpty()) isParam = true;
                                }
                            } catch (Exception ignored) {
                                // ignored
                            }
                            // 检测参数: eContainer 链回溯是否为 FeatureMembership
                            if (!isParam) {
                                EObject parent = obj.eContainer();
                                if (parent != null) {
                                    String parentCn = parent.eClass().getName();
                                    if (parentCn.contains("FeatureMembership")) isParam = true;
                                }
                            }
                            if (!isParam) {
                                CallBehaviorAction placeholder = UmlHelper.createCallBehaviorAction(ctx.activity, name);
                                MainRunner.umlNodes.put(id, placeholder);
                                MainRunner.nameToIdMap.put(name, id);
                                ctx.existingNodeNames.add(name);
                                ctx.placeholderNodeIds.add(id);
                            }
                        }
                    }
                    // 解析常规 Action (根据 typed 决定类型)
                    else if (className.contains("ActionUsage") && name != null && !name.isEmpty() && !name.equals(ctx.activity.getName())) {
                        // 跳过 loop body wrapper (WhileLoopActionUsage 的 ParameterMembership 中的 ActionUsage)
                        EObject parent = obj.eContainer();
                        if (parent != null && parent.eClass().getName().equals("ParameterMembership")) {
                            EObject grandparent = parent.eContainer();
                            if (grandparent != null && grandparent.eClass().getName().equals("WhileLoopActionUsage")) {
                                continue;
                            }
                        }
                        // 跳过已在 umlNodes 中的节点 (由 WhileLoopActionUsage 等处理代码创建)
                        if (MainRunner.umlNodes.containsKey(id)) {
                            MainRunner.uuidToNameMap.put(id, name);
                            MainRunner.nameToIdMap.put(name, id);
                            if (name.startsWith("checkIf")) lastCheckIfUuid = id;
                            continue;
                        }
                        // 跳过 Package 级别的 action 定义 (OwningMembership -> Package, 且无 FeatureMembership 子元素)
                        // 以及跳过嵌套在另一个命名 ActionUsage 内的 actions
                        // 只创建属于活动结构的顶层 actions
                        {
                            EObject anc = obj.eContainer();
                            boolean skipThis = false;
                            // 检测1: Package 级别的空 action 定义 (无子 FM = 纯定义, 非用法)
                            if (anc != null && anc.eClass().getName().equals("OwningMembership")) {
                                EObject grandAnc = anc.eContainer();
                                if (grandAnc != null && grandAnc.eClass().getName().contains("Package")) {
                                    // 检查是否有 FeatureMembership 子元素 (有子元素 = 真正的 usage, 不应跳过)
                                    boolean hasFM = false;
                                    for (EObject child : obj.eContents()) {
                                        if (child.eClass().getName().equals("FeatureMembership")) {
                                            hasFM = true;
                                            break;
                                        }
                                    }
                                    if (!hasFM) {
                                        skipThis = true;
                                    }
                                }
                            }
                            // 检测2: 嵌套在 ActionUsage 内的 actions, 且该 ActionUsage 又嵌套在 ActionDefinition 中
                            // 例: monitorCriticalActivity -> performCriticalActivity(ActionUsage) -> MonitoredActivity(ActionDef) -> 跳过
                            // 反例: focus -> takePicture(ActionUsage) -> Package -> 不跳过 (takePicture 是主活动容器)
                            if (!skipThis) {
                                EObject anc2 = anc;
                                while (anc2 != null) {
                                    String anc2Cn = anc2.eClass().getName();
                                    if (anc2Cn.contains("ActionUsage") && !anc2Cn.equals("ActionDefinition")) {
                                        if (anc2 instanceof org.omg.sysml.lang.sysml.Element) {
                                            String anc2Name = ((org.omg.sysml.lang.sysml.Element) anc2).getDeclaredName();
                                            if (anc2Name != null && !anc2Name.isEmpty()) {
                                                // 检查该 ActionUsage 祖先是否嵌套在 ActionDefinition 中
                                                EObject anc3 = anc2.eContainer();
                                                while (anc3 != null) {
                                                    String anc3Cn = anc3.eClass().getName();
                                                    if (anc3Cn.contains("ActionDefinition")) {
                                                        skipThis = true;
                                                        break;
                                                    }
                                                    if (anc3Cn.contains("Package")) break;
                                                    anc3 = anc3.eContainer();
                                                }
                                                if (skipThis) {
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    if (anc2Cn.contains("ActionDefinition")) break;
                                    if (anc2Cn.contains("Package")) break;
                                    if (anc2Cn.contains("Definition")) { skipThis = true; break; }
                                    anc2 = anc2.eContainer();
                                }
                            }
                            if (skipThis) {
                                continue;
                            }
                        }
                        MainRunner.uuidToNameMap.put(id, name);
                        MainRunner.nameToIdMap.put(name, id);
                        ActivityNode actionNode = null;
                        java.util.List<?> typedList = null;
                        try {
                            // check "type" feature for typed reference (may be single or multi-valued)
                            var feat = obj.eClass().getEStructuralFeature("type");
                            if (feat == null) feat = obj.eClass().getEStructuralFeature("typed");
                            if (feat == null) feat = obj.eClass().getEStructuralFeature("referencedType");
                            if (feat != null) {
                                Object val = obj.eGet(feat);
                                if (val instanceof java.util.List) {
                                    typedList = (java.util.List<?>) val;
                                } else if (val instanceof EObject) {
                                    typedList = java.util.Collections.singletonList(val);
                                }
                            }
                        } catch (Exception ignored) {
                            // ignored
                        }
                        if (ctx.firstActionId == null) ctx.firstActionId = id;
                        if (typedList != null && !typedList.isEmpty() && typedList.get(0) instanceof EObject) {
                            String typedClassName = ((EObject) typedList.get(0)).eClass().getName();
                            ctx.typedActionIds.add(id);
                            if (typedClassName.contains("ActionDefinition")) {
                                actionNode = UmlHelper.createCallBehaviorAction(ctx.activity, name);
                            } else if (typedClassName.contains("Operation")) {
                                actionNode = ctx.activity.createOwnedNode(name, UMLPackage.Literals.CALL_OPERATION_ACTION);
                            }
                        }
                        if (actionNode == null) {
                            actionNode = UmlHelper.createCallBehaviorAction(ctx.activity, name);
                        }
                        MainRunner.umlNodes.put(id, actionNode);
                        if (name.startsWith("checkIf")) lastCheckIfUuid = id;

                        // ===== 提取 in/out 参数, 创建 Pin =====
                        ctx.actionSysmlElements.put(id, obj);
                        if (actionNode instanceof CallBehaviorAction) {
                            CallBehaviorAction cba = (CallBehaviorAction) actionNode;
                            OpaqueBehavior beh = (cba.getBehavior() instanceof OpaqueBehavior) ? (OpaqueBehavior) cba.getBehavior() : null;
                            Map<String, OutputPin> outPins = new HashMap<>();
                            Map<String, InputPin> inPins = new HashMap<>();
                            for (EObject child : obj.eContents()) {
                                String childCn = child.eClass().getName();
                                if (!childCn.equals("FeatureMembership")) continue;
                                for (EObject param : child.eContents()) {
                                    if (!(param instanceof org.omg.sysml.lang.sysml.Element)) continue;
                                    String paramCn = param.eClass().getName();
                                    if (!paramCn.contains("ReferenceUsage") && !paramCn.contains("ItemUsage")) continue;
                                    String paramName = ((org.omg.sysml.lang.sysml.Element) param).getDeclaredName();
                                    String direction = ExpressionUtils.getFeatureString(param, "direction");
                                    if (paramName == null || paramName.isEmpty() || direction == null || direction.isEmpty()) continue;

                                    // 记录 feature elementId -> (actionId, paramName, direction) 供 BindingConnector 解析
                                    try {
                                        String paramEid = ((org.omg.sysml.lang.sysml.Element) param).getElementId();
                                        if (paramEid != null) {
                                            ctx.featureIdToPinInfo.put(paramEid, new String[]{id, paramName, direction});
                                        }
                                    } catch (Exception ignored) {
                                        // ignored
                                    }

                                    if (direction.equals("out")) {
                                        // 手动创建 OutputPin, 通过 "result" containment 添加到 CBA
                                        OutputPin pin = UMLFactory.eINSTANCE.createOutputPin();
                                        pin.setName(paramName);
                                        EStructuralFeature resultFeat = cba.eClass().getEStructuralFeature("result");
                                        if (resultFeat != null) {
                                            @SuppressWarnings("unchecked")
                                            java.util.List<Object> resultList = (java.util.List<Object>) cba.eGet(resultFeat);
                                            resultList.add(pin);
                                        }
                                        outPins.put(paramName, pin);
                                        if (beh != null) {
                                            org.eclipse.uml2.uml.Parameter bp = UMLFactory.eINSTANCE.createParameter();
                                            bp.setName(paramName);
                                            bp.setDirection(org.eclipse.uml2.uml.ParameterDirectionKind.OUT_LITERAL);
                                            beh.getOwnedParameters().add(bp);
                                        }
                                    } else if (direction.equals("in")) {
                                        // 手动创建 InputPin, 通过 "argument" containment 添加到 CBA
                                        InputPin pin = UMLFactory.eINSTANCE.createInputPin();
                                        pin.setName(paramName);
                                        EStructuralFeature argFeat = cba.eClass().getEStructuralFeature("argument");
                                        if (argFeat != null) {
                                            @SuppressWarnings("unchecked")
                                            java.util.List<Object> argList = (java.util.List<Object>) cba.eGet(argFeat);
                                            argList.add(pin);
                                        }
                                        inPins.put(paramName, pin);
                                        if (beh != null) {
                                            org.eclipse.uml2.uml.Parameter bp = UMLFactory.eINSTANCE.createParameter();
                                            bp.setName(paramName);
                                            bp.setDirection(org.eclipse.uml2.uml.ParameterDirectionKind.IN_LITERAL);
                                            beh.getOwnedParameters().add(bp);
                                        }
                                        for (EObject pc : param.eContents()) {
                                            if (pc.eClass().getName().equals("FeatureValue")) {
                                                String[] srcRef = CalcModel.resolveFeatureChainSource(pc);
                                                if (srcRef != null && srcRef.length == 2) {
                                                    ctx.pinBindings.add(new String[]{id, paramName, srcRef[0], srcRef[1]});
                                                }
                                            }
                                        }
                                    }

                                    // --- 扩展 featureIdToPinInfo: 注册默认值中的 memberElement 引用 ---
                                    // 使得 BindingConnector 中的 "幻影" 引用 (如 bind x = b.y 中的 x) 可被解析
                                    // 注意: 仅当该 ID 未被注册时才写入, 防止覆盖直接参数注册
                                    for (EObject pc2 : param.eContents()) {
                                        if (pc2.eClass().getName().equals("FeatureValue")) {
                                            java.util.Iterator<EObject> fvi = pc2.eAllContents();
                                            while (fvi.hasNext()) {
                                                EObject fvc = fvi.next();
                                                if (fvc.eClass().getName().equals("Membership")) {
                                                    try {
                                                        EStructuralFeature meFeat = fvc.eClass().getEStructuralFeature("memberElement");
                                                        if (meFeat != null) {
                                                            Object meVal = fvc.eGet(meFeat);
                                                            if (meVal instanceof EObject) {
                                                                EObject resolved = EcoreUtil.resolve((EObject) meVal, fvc);
                                                                if (resolved instanceof org.omg.sysml.lang.sysml.Element) {
                                                                    String meId = ((org.omg.sysml.lang.sysml.Element) resolved).getElementId();
                                                                    if (meId != null && !ctx.featureIdToPinInfo.containsKey(meId)) {
                                                                        ctx.featureIdToPinInfo.put(meId, new String[]{id, paramName, direction});
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch (Exception ignored) {
                                                        // ignored
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    // --- 注册 ReferenceSubsetting 中 FeatureChaining 的最后一个 chainingFeature ---
                                    // 使得 bind x = b.y 中 b.y 的链式引用可被解析
                                    for (EObject pc3 : param.eContents()) {
                                        if (pc3.eClass().getName().equals("ReferenceSubsetting")) {
                                            String lastChainingId = null;
                                            for (EObject fc : pc3.eContents()) {
                                                if (fc.eClass().getName().equals("Feature")) {
                                                    for (EObject fcc : fc.eContents()) {
                                                        if (fcc.eClass().getName().equals("FeatureChaining")) {
                                                            try {
                                                                EStructuralFeature cfFeat = fcc.eClass().getEStructuralFeature("chainingFeature");
                                                                if (cfFeat != null) {
                                                                    Object cfVal = fcc.eGet(cfFeat);
                                                                    if (cfVal instanceof EObject) {
                                                                        EObject cfResolved = EcoreUtil.resolve((EObject) cfVal, fcc);
                                                                        if (cfResolved instanceof org.omg.sysml.lang.sysml.Element) {
                                                                            lastChainingId = ((org.omg.sysml.lang.sysml.Element) cfResolved).getElementId();
                                                                        }
                                                                    }
                                                                }
                                                            } catch (Exception ignored) {
                                                                // ignored
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            if (lastChainingId != null) {
                                                ctx.featureIdToPinInfo.put(lastChainingId, new String[]{id, paramName, direction});
                                            }
                                        }
                                    }
                                }
                            }
                            if (!outPins.isEmpty()) ctx.actionOutputPins.put(id, outPins);
                            if (!inPins.isEmpty()) ctx.actionInputPins.put(id, inPins);

                            // --- 继承参数: 从 ActionDefinition 获取父类型定义的参数 ---
                            if (typedList != null && !typedList.isEmpty() && typedList.get(0) instanceof EObject) {
                                EObject typeDef = (EObject) typedList.get(0);
                                if (typeDef.eClass().getName().contains("ActionDefinition")) {
                                    String typeDefId = (typeDef instanceof org.omg.sysml.lang.sysml.Element)
                                        ? ((org.omg.sysml.lang.sysml.Element) typeDef).getElementId() : null;
                                    if (typeDefId != null && ctx.actionSysmlElements.containsKey(typeDefId)) {
                                        EObject defObj = ctx.actionSysmlElements.get(typeDefId);
                                        for (EObject defChild : defObj.eContents()) {
                                            if (!defChild.eClass().getName().equals("FeatureMembership")) continue;
                                            for (EObject defParam : defChild.eContents()) {
                                                if (!(defParam instanceof org.omg.sysml.lang.sysml.Element)) continue;
                                                String defParamCn = defParam.eClass().getName();
                                                if (!defParamCn.contains("ReferenceUsage") && !defParamCn.contains("ItemUsage")) continue;
                                                String defParamName = ((org.omg.sysml.lang.sysml.Element) defParam).getDeclaredName();
                                                String defDirection = ExpressionUtils.getFeatureString(defParam, "direction");
                                                if (defParamName == null || defParamName.isEmpty() || defDirection == null || defDirection.isEmpty()) continue;
                                                // 如果已有同名 pin, 跳过
                                                if (inPins.containsKey(defParamName) || outPins.containsKey(defParamName)) continue;
                                                // 为继承参数创建 Pin
                                                if (defDirection.equals("in")) {
                                                    InputPin pin = UMLFactory.eINSTANCE.createInputPin();
                                                    pin.setName(defParamName);
                                                    EStructuralFeature argFeat = cba.eClass().getEStructuralFeature("argument");
                                                    if (argFeat != null) {
                                                        @SuppressWarnings("unchecked")
                                                        java.util.List<Object> argList = (java.util.List<Object>) cba.eGet(argFeat);
                                                        argList.add(pin);
                                                    }
                                                    inPins.put(defParamName, pin);
                                                    if (beh != null) {
                                                        org.eclipse.uml2.uml.Parameter bp = UMLFactory.eINSTANCE.createParameter();
                                                        bp.setName(defParamName);
                                                        bp.setDirection(org.eclipse.uml2.uml.ParameterDirectionKind.IN_LITERAL);
                                                        beh.getOwnedParameters().add(bp);
                                                    }
                                                    // 注册继承参数的 elementId
                                                    try {
                                                        String defParamEid = ((org.omg.sysml.lang.sysml.Element) defParam).getElementId();
                                                        if (defParamEid != null) {
                                                            ctx.featureIdToPinInfo.put(defParamEid, new String[]{id, defParamName, defDirection});
                                                        }
                                                    } catch (Exception ignored) {
                                                        // ignored
                                                    }
                                                } else if (defDirection.equals("out")) {
                                                    OutputPin pin = UMLFactory.eINSTANCE.createOutputPin();
                                                    pin.setName(defParamName);
                                                    EStructuralFeature resultFeat = cba.eClass().getEStructuralFeature("result");
                                                    if (resultFeat != null) {
                                                        @SuppressWarnings("unchecked")
                                                        java.util.List<Object> resultList = (java.util.List<Object>) cba.eGet(resultFeat);
                                                        resultList.add(pin);
                                                    }
                                                    outPins.put(defParamName, pin);
                                                    if (beh != null) {
                                                        org.eclipse.uml2.uml.Parameter bp = UMLFactory.eINSTANCE.createParameter();
                                                        bp.setName(defParamName);
                                                        bp.setDirection(org.eclipse.uml2.uml.ParameterDirectionKind.OUT_LITERAL);
                                                        beh.getOwnedParameters().add(bp);
                                                    }
                                                    try {
                                                        String defParamEid = ((org.omg.sysml.lang.sysml.Element) defParam).getElementId();
                                                        if (defParamEid != null) {
                                                            ctx.featureIdToPinInfo.put(defParamEid, new String[]{id, defParamName, defDirection});
                                                        }
                                                    } catch (Exception ignored) {
                                                        // ignored
                                                    }
                                                }
                                            }
                                        }
                                        // 更新 pin maps (可能在继承参数后有新增)
                                        if (!outPins.isEmpty()) ctx.actionOutputPins.put(id, outPins);
                                        if (!inPins.isEmpty()) ctx.actionInputPins.put(id, inPins);
                                    }
                                }
                            }
                        }
                    }
                    // ActionDefinition: 注册到 nameToIdMap 供 Pass 2b 遍历 FM 链
                    else if (className.contains("ActionDefinition") && name != null && !name.isEmpty()) {
                        MainRunner.nameToIdMap.put(name, id);
                        ctx.actionSysmlElements.put(id, obj);
                    }
                    // 显式 ForkNode 检测 (支持无名 ForkNode)
                    else if (className.contains("ForkNode")) {
                        String forkName = (name != null && !name.isEmpty()) ? name : "Fork";
                        ForkNode forkNode = (ForkNode) ctx.activity.createOwnedNode(forkName, UMLPackage.Literals.FORK_NODE);
                        MainRunner.umlNodes.put(id, forkNode);
                        MainRunner.nameToIdMap.put(forkName, id);
                    }
                    // 显式 JoinNode 检测 (支持无名 JoinNode)
                    else if (className.contains("JoinNode")) {
                        String joinName = (name != null && !name.isEmpty()) ? name : "Join";
                        JoinNode joinNode = (JoinNode) ctx.activity.createOwnedNode(joinName, UMLPackage.Literals.JOIN_NODE);
                        MainRunner.umlNodes.put(id, joinNode);
                        MainRunner.nameToIdMap.put(joinName, id);
                    }
                    // TerminateActionUsage 检测 (无 declaredName, 映射为普通 CallBehaviorAction)
                    else if (className.contains("TerminateActionUsage")) {
                        // 跳过 Package 级别(无FM子元素)或嵌套在 ActionUsage 内的 TerminateActionUsage
                        boolean skipTerm = false;
                        {
                            EObject tAnc = obj.eContainer();
                            if (tAnc != null && tAnc.eClass().getName().equals("OwningMembership")) {
                                EObject tGrand = tAnc.eContainer();
                                if (tGrand != null && tGrand.eClass().getName().contains("Package")) {
                                    boolean hasFM = false;
                                    for (EObject child : obj.eContents()) {
                                        if (child.eClass().getName().equals("FeatureMembership")) {
                                            hasFM = true;
                                            break;
                                        }
                                    }
                                    if (!hasFM) skipTerm = true;
                                }
                            }
                            if (!skipTerm) {
                                EObject tAnc2 = tAnc;
                                while (tAnc2 != null) {
                                    String tAnc2Cn = tAnc2.eClass().getName();
                                    if (tAnc2Cn.contains("ActionUsage") && !tAnc2Cn.equals("ActionDefinition")) {
                                        if (tAnc2 instanceof org.omg.sysml.lang.sysml.Element) {
                                            String tAnc2Name = ((org.omg.sysml.lang.sysml.Element) tAnc2).getDeclaredName();
                                            if (tAnc2Name != null && !tAnc2Name.isEmpty()) {
                                                // 检查该 ActionUsage 祖先是否嵌套在 ActionDefinition 中
                                                EObject tAnc3 = tAnc2.eContainer();
                                                while (tAnc3 != null) {
                                                    String tAnc3Cn = tAnc3.eClass().getName();
                                                    if (tAnc3Cn.contains("ActionDefinition")) {
                                                        skipTerm = true;
                                                        break;
                                                    }
                                                    if (tAnc3Cn.contains("Package")) break;
                                                    tAnc3 = tAnc3.eContainer();
                                                }
                                                if (skipTerm) {
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    if (tAnc2Cn.contains("ActionDefinition")) break;
                                    if (tAnc2Cn.contains("Package")) break;
                                    if (tAnc2Cn.contains("Definition")) { skipTerm = true; break; }
                                    tAnc2 = tAnc2.eContainer();
                                }
                            }
                        }
                        if (!skipTerm) {
                            CallBehaviorAction termNode = UmlHelper.createCallBehaviorAction(ctx.activity, "terminate");
                            MainRunner.umlNodes.put(id, termNode);
                            MainRunner.nameToIdMap.put("terminate", id);
                            ctx.terminateActionId = id;
                        }
                    }
                    // sysml:MergeNode 检测 (decide 语法中的显式 MergeNode, 如 "merge continueCharging;")
                    else if (className.equals("MergeNode")) {
                        if (name != null && !name.isEmpty()) {
                            MergeNode mergeNode = (MergeNode) ctx.activity.createOwnedNode(name, UMLPackage.Literals.MERGE_NODE);
                            MainRunner.umlNodes.put(id, mergeNode);
                            MainRunner.nameToIdMap.put(name, id);
                            ctx.existingNodeNames.add(name);
                        }
                    }
                    // sysml:DecisionNode 检测 (decide 语法中的 DecisionNode, 无 declaredName)
                    else if (className.equals("DecisionNode")) {
                        // 按 decide 块分组提取 if 条件, 每个 DecisionNode 取对应块的条件
                        String decName = null;
                        if (ctx.decideConditionGroups.isEmpty()) {
                            try {
                                String text = Files.readString(Paths.get(ctx.sysmlBasePath.replace(".sysmlx", ".sysml")));
                                // 按 "then decide;" 位置分割文本, 每个 decide 块单独提取条件
                                Matcher splitM = Pattern.compile("then\\s+decide\\s*(?:\\s+\\w+\\s*)?;").matcher(text);
                                List<Integer> decidePositions = new ArrayList<>();
                                List<Integer> decideEndPositions = new ArrayList<>();
                                while (splitM.find()) {
                                    decidePositions.add(splitM.start());
                                    decideEndPositions.add(splitM.end());
                                }
                                for (int di = 0; di < decidePositions.size(); di++) {
                                    int blockStart = decideEndPositions.get(di);
                                    int blockEnd = (di + 1 < decidePositions.size()) ? decidePositions.get(di + 1) : text.length();
                                    String block = text.substring(blockStart, blockEnd);
                                    List<String> group = new ArrayList<>();
                                    Matcher cm = Pattern.compile("if\\s+(.+?)\\s+then\\b").matcher(block);
                                    while (cm.find()) {
                                        group.add(cm.group(1).trim());
                                    }
                                    if (!group.isEmpty()) {
                                        ctx.decideConditionGroups.add(group);
                                    }
                                }
                                System.out.println("[DEBUG] DecidedConditionGroups: " + ctx.decideConditionGroups);
                            } catch (Exception ignored) {
                                // ignored
                            }
                        }
                        if (ctx.decideConditionIndex < ctx.decideConditionGroups.size()) {
                            List<String> group = ctx.decideConditionGroups.get(ctx.decideConditionIndex);
                            decName = group.get(0) + "?";
                            ctx.decideConditionIndex++;
                        }
                        if (decName == null || decName.isEmpty()) {
                            decName = "decision_" + id.substring(0, Math.min(8, id.length()));
                        }
                        DecisionNode decisionNode = (DecisionNode) ctx.activity.createOwnedNode(decName, UMLPackage.Literals.DECISION_NODE);
                        MainRunner.umlNodes.put(id, decisionNode);
                        MainRunner.nameToIdMap.put(decName, id);
                        // 记录为 decide 的 DecisionNode, 供 TransitionUsage 使用
                        ctx.lastDecideDecisionId = id;
                        ctx.allDecideDecisionIds.add(id);
                        // 预提取 .sysml 中所有 if...then 的目标动作名 (仅首次)
                        if (ctx.sysmlIfTargets.isEmpty()) {
                            try {
                                String text = Files.readString(Paths.get(ctx.sysmlBasePath.replace(".sysmlx", ".sysml")));
                                Matcher m = Pattern.compile("if\\s+.+?\\s+then\\s+(\\w+)").matcher(text);
                                while (m.find()) {
                                    ctx.sysmlIfTargets.add(m.group(1));
                                }
                                // 提取 "then merge <name>;" 中的 MergeNode 名称
                                Matcher mMerge = Pattern.compile("then\\s+merge\\s+(\\w+)\\s*;").matcher(text);
                                if (mMerge.find()) {
                                    ctx.sysmlMergeNodeName = mMerge.group(1);
                                    System.out.println("[DEBUG] .sysml merge node name: " + ctx.sysmlMergeNodeName);
                                }
                            } catch (Exception ignored) {
                                // ignored
                            }
                        }
                    }
                    // sysml:TransitionUsage 检测 (decide 语法中带 guard 的分支边)
                    // 结构: TransitionUsage -> TransitionFeatureMembership(kind=guard) -> OperatorExpression
                    //       TransitionUsage -> OwningMembership -> SuccessionAsUsage (目标引用)
                    // 注意: TransitionUsage 在文档顺序中位于目标 ActionUsage 之前,
                    //       因此无法立即解析目标 ID, 需要延迟到 PASS 2c 解析
                    else if (className.equals("TransitionUsage")) {
                        // 1. 从 TransitionFeatureMembership(kind=guard) 提取 guard 条件
                        String guardText = null;
                        for (EObject child : obj.eContents()) {
                            if (child.eClass().getName().equals("TransitionFeatureMembership")) {
                                String kind = ExpressionUtils.getFeatureString(child, "kind");
                                if ("guard".equals(kind)) {
                                    for (EObject expr : child.eContents()) {
                                        if (expr.eClass().getName().contains("OperatorExpression")) {
                                            guardText = ExpressionUtils.buildExpressionText(expr);
                                            if (guardText != null && !guardText.isEmpty() && ExpressionUtils.isValidExpression(guardText)) {
                                                break;
                                            }
                                            guardText = null;
                                        }
                                    }
                                }
                            }
                            if (guardText != null) {
                                break;
                            }
                        }
                        // Fallback: .sysml regex 按序提取条件
                        if (guardText == null || guardText.isEmpty()) {
                            try {
                                String text = Files.readString(Paths.get(ctx.sysmlBasePath.replace(".sysmlx", ".sysml")));
                                Matcher m = Pattern.compile("if\\s+(.+?)\\s+then\\b").matcher(text);
                                int count = 0;
                                while (m.find()) {
                                    if (count == ctx.decideDeferredEdges.size()) {
                                        guardText = m.group(1).trim();
                                        break;
                                    }
                                    count++;
                                }
                            } catch (Exception ignored) {
                                // ignored
                            }
                        }
                        // 2. 确定目标动作名: 按文档顺序, 第 N 个 TransitionUsage 对应第 N 个 if...then 目标
                        String targetName = null;
                        if (!ctx.sysmlIfTargets.isEmpty()) {
                            int currentIdx = ctx.decideDeferredEdges.size();
                            if (currentIdx < ctx.sysmlIfTargets.size()) {
                                targetName = ctx.sysmlIfTargets.get(currentIdx);
                            }
                        }
                        // 2b. Fallback: extract target directly from XMI
                        //     TransitionUsage -> OwningMembership -> SuccessionAsUsage ->
                        //     EndFeatureMembership -> ReferenceUsage -> ReferenceSubsetting -> referencedFeature
                        String condSuccTgtId = null; // direct XMI target ID
                        if (targetName == null) {
                            for (EObject mChild : obj.eContents()) {
                                if (mChild.eClass().getName().equals("OwningMembership")) {
                                    for (EObject succ : mChild.eContents()) {
                                        if (succ.eClass().getName().equals("SuccessionAsUsage")) {
                                            int endIdx = 0;
                                            for (EObject efm : succ.eContents()) {
                                                if (efm.eClass().getName().equals("EndFeatureMembership")) {
                                                    if (endIdx == 1) { // second end = target
                                                        for (EObject ref : efm.eContents()) {
                                                            if (ref.eClass().getName().contains("ReferenceUsage")) {
                                                                for (EObject rs : ref.eContents()) {
                                                                    if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                                        try {
                                                                            var rfFeat = rs.eClass().getEStructuralFeature("referencedFeature");
                                                                            if (rfFeat != null) {
                                                                                Object rfVal = rs.eGet(rfFeat);
                                                                                if (rfVal instanceof EObject) {
                                                                                    rfVal = EcoreUtil.resolve((EObject) rfVal, ctx.resourceSet);
                                                                                    if (rfVal instanceof org.omg.sysml.lang.sysml.Element) {
                                                                                        condSuccTgtId = ((org.omg.sysml.lang.sysml.Element) rfVal).getElementId();
                                                                                        targetName = ((org.omg.sysml.lang.sysml.Element) rfVal).getDeclaredName();
                                                                                        System.out.println("[DEBUG] TransitionUsage: XMI target = " + targetName + " (id=" + condSuccTgtId + ")");
                                                                                    }
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
                                                    endIdx++;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 3. 验证 guard 文本: 检测 Java toString() 垃圾 (EMF 代理对象)
                        // 如果 AST 提取产生了含 "org.omg" 或 "@" 的垃圾文本, 丢弃并用 .sysml regex fallback 替代
                        if (guardText != null && (guardText.contains("org.omg") || guardText.contains("@")
                            || guardText.contains("FeatureReference") || guardText.contains("Impl@"))) {
                            guardText = null;
                            try {
                                String text = Files.readString(Paths.get(ctx.sysmlBasePath.replace(".sysmlx", ".sysml")));
                                Matcher m2 = Pattern.compile("if\\s+(.+?)\\s+then\\b").matcher(text);
                                int count = 0;
                                while (m2.find()) {
                                    if (count == ctx.decideDeferredEdges.size()) {
                                        guardText = m2.group(1).trim();
                                        break;
                                    }
                                    count++;
                                }
                            } catch (Exception ignored) {
                                // ignored
                            }
                        }
                        // 4. 存储延迟边 (目标 ID 在 PASS 2c 解析)
                        // 优先从 Membership 子元素提取源 DecisionNode (比 lastDecideDecisionId 更准确)
                        String transSrcId = null;
                        for (EObject mChild : obj.eContents()) {
                            String mcn = mChild.eClass().getName();
                            if (mcn.equals("Membership") && !mcn.contains("EndFeature") && !mcn.contains("Parameter") && !mcn.contains("Owning") && !mcn.contains("Transition") && !mcn.contains("Return")) {
                                try {
                                    var meFeat = mChild.eClass().getEStructuralFeature("memberElement");
                                    if (meFeat != null) {
                                        Object meVal = mChild.eGet(meFeat);
                                        if (meVal instanceof EObject) {
                                            String meId = null;
                                            try {
                                                meId = ((org.omg.sysml.lang.sysml.Element) meVal).getElementId();
                                            } catch (Exception ignored) {
                                                // ignored
                                            }
                                            if (meId != null) transSrcId = meId;
                                        }
                                    }
                                } catch (Exception ignored) {
                                    // ignored
                                }
                            }
                        }
                        if (transSrcId == null) {
                            // 位置匹配: 根据 TransitionUsage 序号和 decideConditionGroups 找到对应的 DecisionNode
                            if (!ctx.allDecideDecisionIds.isEmpty() && !ctx.decideConditionGroups.isEmpty()) {
                                int count = 0;
                                for (int gi = 0; gi < ctx.decideConditionGroups.size(); gi++) {
                                    int groupSize = ctx.decideConditionGroups.get(gi).size();
                                    if (ctx.decideTransitionCount < count + groupSize && gi < ctx.allDecideDecisionIds.size()) {
                                        transSrcId = ctx.allDecideDecisionIds.get(gi);
                                        break;
                                    }
                                    count += groupSize;
                                }
                            }
                            if (transSrcId == null) transSrcId = ctx.lastDecideDecisionId;
                        }
                        ctx.decideTransitionCount++;
                        if (transSrcId != null && targetName != null) {
                            // Register XMI-extracted target ID in nameToIdMap for PASS 2c resolution
                            if (condSuccTgtId != null && !MainRunner.nameToIdMap.containsKey(targetName)) {
                                MainRunner.nameToIdMap.put(targetName, condSuccTgtId);
                                System.out.println("[DEBUG] TransitionUsage: registered nameToIdMap[" + targetName + "] = " + condSuccTgtId);
                            }
                            ctx.decideDeferredEdges.add(new String[]{transSrcId, targetName, guardText});
                        }
                    }
                    // ========================================================
                    // FlowUsage: flow from X.port to Y.port -> ObjectFlow
                    // Structure: FlowUsage -> EndFeatureMembership -> FlowEnd ->
                    //   ReferenceSubsetting -> Feature -> FeatureChaining(actionPart) + FeatureChaining(pin)
                    // ========================================================
                    else if (className.equals("FlowUsage")) {
                        // Try to extract source/target from FeatureChaining
                        String srcActionName = null, srcPinName = null;
                        String tgtActionName = null, tgtPinName = null;
                        int endCount = 0;
                        for (EObject fmChild : obj.eContents()) {
                            if (fmChild.eClass().getName().equals("EndFeatureMembership")) {
                                for (EObject fe : fmChild.eContents()) {
                                    if (fe.eClass().getName().equals("FlowEnd")) {
                                        for (EObject rs : fe.eContents()) {
                                            if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                // Try to resolve FeatureChaining
                                                for (EObject rsChild : rs.eContents()) {
                                                    if (rsChild.eClass().getName().equals("Feature")) {
                                                        List<String> chainNames = new ArrayList<>();
                                                        for (EObject fc : rsChild.eContents()) {
                                                            if (fc.eClass().getName().equals("FeatureChaining")) {
                                                                try {
                                                                    var cfFeat = fc.eClass().getEStructuralFeature("chainingFeature");
                                                                    if (cfFeat != null) {
                                                                        Object cfVal = fc.eGet(cfFeat);
                                                                        if (cfVal instanceof EObject) {
                                                                            cfVal = EcoreUtil.resolve((EObject) cfVal, ctx.resourceSet);
                                                                            String cfName = null;
                                                                            if (cfVal instanceof org.omg.sysml.lang.sysml.Element) {
                                                                                cfName = ((org.omg.sysml.lang.sysml.Element) cfVal).getDeclaredName();
                                                                            }
                                                                            if (cfName != null && !cfName.isEmpty()) {
                                                                                chainNames.add(cfName);
                                                                            }
                                                                        }
                                                                    }
                                                                } catch (Exception ignored) {
                                                                    // ignored
                                                                }
                                                            }
                                                        }
                                                        if (chainNames.size() >= 2) {
                                                            if (endCount == 0) {
                                                                srcActionName = chainNames.get(0);
                                                                srcPinName = chainNames.get(chainNames.size() - 1);
                                                            } else {
                                                                tgtActionName = chainNames.get(0);
                                                                tgtPinName = chainNames.get(chainNames.size() - 1);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                endCount++;
                            }
                        }
                        // Fallback: .sysml regex extraction (按序消费, 避免多个 FlowUsage 匹配同一条)
                        if (srcActionName == null || tgtActionName == null) {
                            try {
                                String text = Files.readString(Paths.get(ctx.sysmlBasePath.replace(".sysmlx", ".sysml")));
                                Matcher mFlow = Pattern.compile("flow\\s+(?:of\\s+\\w+\\s+)?from\\s+(\\w+)\\.(\\w+)(?:\\.\\w+)*\\s+to\\s+(\\w+)\\.(\\w+)(?:\\.\\w+)*").matcher(text);
                                int matchIdx = 0;
                                while (mFlow.find()) {
                                    if (matchIdx == ctx.consumedFlowRegexCount) {
                                        srcActionName = mFlow.group(1);
                                        srcPinName = mFlow.group(2);
                                        tgtActionName = mFlow.group(3);
                                        tgtPinName = mFlow.group(4);
                                        ctx.consumedFlowRegexCount++;
                                        break;
                                    }
                                    matchIdx++;
                                }
                            } catch (Exception ignored) {
                                // ignored
                            }
                        }
                        // Record flow binding for DATA FLOW PASS (deferred - names may not be in nameToIdMap yet)
                        if (srcActionName != null && srcPinName != null && tgtActionName != null && tgtPinName != null) {
                            ctx.deferredFlows.add(new String[]{srcActionName, srcPinName, tgtActionName, tgtPinName});
                        }
                        ctx.processedStructuralIds.add(id);
                    }
                    // ========================================================
                    // SuccessionFlowUsage: succession flow from X.pin to Y.pin
                    // Creates BOTH ObjectFlow (data) AND ControlFlow (succession)
                    // Structure: SuccessionFlowUsage -> EndFeatureMembership -> FlowEnd ->
                    //   ReferenceSubsetting -> referencedFeature (ActionUsage directly)
                    //   FeatureMembership -> ReferenceUsage -> Redefinition -> redefinedFeature (pin)
                    // ========================================================
                    else if (className.equals("SuccessionFlowUsage")) {
                        String srcActionName = null, srcPinName = null;
                        String tgtActionName = null, tgtPinName = null;
                        int endCount = 0;
                        for (EObject fmChild : obj.eContents()) {
                            if (fmChild.eClass().getName().equals("EndFeatureMembership")) {
                                for (EObject fe : fmChild.eContents()) {
                                    if (fe.eClass().getName().equals("FlowEnd")) {
                                        // 1. Get action name from ReferenceSubsetting -> referencedFeature (direct ActionUsage)
                                        for (EObject rs : fe.eContents()) {
                                            if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                try {
                                                    var rfFeat = rs.eClass().getEStructuralFeature("referencedFeature");
                                                    if (rfFeat != null) {
                                                        Object rfVal = rs.eGet(rfFeat);
                                                        if (rfVal instanceof EObject) {
                                                            rfVal = EcoreUtil.resolve((EObject) rfVal, ctx.resourceSet);
                                                            if (rfVal instanceof org.omg.sysml.lang.sysml.Element) {
                                                                String actionName = ((org.omg.sysml.lang.sysml.Element) rfVal).getDeclaredName();
                                                                if (endCount == 0) srcActionName = actionName;
                                                                else tgtActionName = actionName;
                                                            }
                                                        }
                                                    }
                                                } catch (Exception ignored) {
                                                    // ignored
                                                }
                                            }
                                        }
                                        // 2. Get pin name from FeatureMembership -> ReferenceUsage -> Redefinition -> redefinedFeature
                                        for (EObject feChild : fe.eContents()) {
                                            if (feChild.eClass().getName().equals("FeatureMembership")) {
                                                for (EObject ru : feChild.eContents()) {
                                                    if (ru.eClass().getName().equals("ReferenceUsage")) {
                                                        for (EObject rd : ru.eContents()) {
                                                            if (rd.eClass().getName().equals("Redefinition")) {
                                                                try {
                                                                    var rdFeat = rd.eClass().getEStructuralFeature("redefinedFeature");
                                                                    if (rdFeat != null) {
                                                                        Object rdVal = rd.eGet(rdFeat);
                                                                        if (rdVal instanceof EObject) {
                                                                            rdVal = EcoreUtil.resolve((EObject) rdVal, ctx.resourceSet);
                                                                            if (rdVal instanceof org.omg.sysml.lang.sysml.Element) {
                                                                                String pinName = ((org.omg.sysml.lang.sysml.Element) rdVal).getDeclaredName();
                                                                                if (endCount == 0) srcPinName = pinName;
                                                                                else tgtPinName = pinName;
                                                                            }
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
                                endCount++;
                            }
                        }
                        // Fallback: .sysml regex for "succession flow from X.pin to Y.pin"
                        if (srcActionName == null || tgtActionName == null) {
                            try {
                                String text = Files.readString(Paths.get(ctx.sysmlBasePath.replace(".sysmlx", ".sysml")));
                                Matcher mSF = Pattern.compile("succession\\s+flow\\s+(?:of\\s+\\w+\\s+)?from\\s+(\\w+)\\.(\\w+)(?:\\.\\w+)*\\s+to\\s+(\\w+)\\.(\\w+)(?:\\.\\w+)*").matcher(text);
                                while (mSF.find()) {
                                    if (srcActionName == null) {
                                        srcActionName = mSF.group(1);
                                        srcPinName = mSF.group(2);
                                        tgtActionName = mSF.group(3);
                                        tgtPinName = mSF.group(4);
                                    }
                                }
                            } catch (Exception ignored) {
                                // ignored
                            }
                        }
                        // Record for deferred resolution (both ObjectFlow + ControlFlow)
                        if (srcActionName != null && srcPinName != null && tgtActionName != null && tgtPinName != null) {
                            ctx.deferredSuccessionFlows.add(new String[]{srcActionName, srcPinName, tgtActionName, tgtPinName});
                        }
                        ctx.processedStructuralIds.add(id);
                        System.out.println("[DEBUG] SuccessionFlowUsage: " + srcActionName + "." + srcPinName + " -> " + tgtActionName + "." + tgtPinName);
                    }
                    // ========================================================
                    // 结构概念: PartDefinition, ItemDefinition, PortDefinition,
                    // AttributeDefinition, ConnectionDefinition, InterfaceDefinition,
                    // AllocationDefinition, ConstraintDefinition, RequirementDefinition,
                    // StateDefinition, FlowDefinition, UseCaseDefinition,
                    // CalculationDefinition, ConcernDefinition, ViewDefinition, etc.
                    // ========================================================
                    else if (!ctx.processedStructuralIds.contains(id)) {
                        String structName = (name != null && !name.isEmpty()) ? name : className + "_" + id.substring(0, Math.min(6, id.length()));
                        boolean isDefinition = className.endsWith("Definition");
                        boolean isUsage = className.endsWith("Usage");

                        // 跳过已在其他分支处理的类型
                        if (className.contains("Action") || className.contains("Control")
                            || className.contains("ForkNode") || className.contains("JoinNode")
                            || className.contains("MergeNode") || className.contains("DecisionNode")
                            || className.contains("Transition") || className.contains("Terminate")
                            || className.contains("If") || className.contains("Loop")
                            || className.contains("Accept") || className.contains("Send")
                            || className.contains("Assign") || className.contains("Perform")
                            || className.contains("Exhibit") || className.contains("Include")
                            || className.contains("Satisfy") || className.contains("Assert")
                            || className.contains("Expression") || className.contains("Feature")
                            || className.contains("Membership") || className.contains("Import")
                            || className.contains("Expose") || className.contains("Succession")
                            || className.contains("Comment") || className.contains("Doc")
                            || className.contains("Namespace") || className.contains("Package")
                            || className.contains("Dependency") || className.contains("Alias")
                            || className.contains("Element") || className.contains("Operator")
                            || className.contains("Literal") || className.contains("Invocation")
                            || className.contains("FeatureValue") || className.contains("FeatureChain")
                            || className.contains("Flow")
                            || className.contains("EventOccurrence") || className.contains("Trigger")) {
                            // 由其他分支处理或不需要 UML 映射
                        }
                        // ===================================================================
                        // BindingConnectorAsUsage -> 记录 bind 两端 pin 信息, 后续创建 ObjectFlow
                        // ===================================================================
                        else if (className.contains("BindingConnector")) {
                            List<String> endFeatureIds = new ArrayList<>();
                            for (EObject rel : obj.eContents()) {
                                if (rel.eClass().getName().equals("EndFeatureMembership")) {
                                    for (EObject refUsage : rel.eContents()) {
                                        String refCn = refUsage.eClass().getName();
                                        if (refCn.contains("ReferenceUsage") || refCn.contains("Feature")) {
                                            for (EObject sub : refUsage.eContents()) {
                                                if (sub.eClass().getName().equals("ReferenceSubsetting")) {
                                                    // 1. 尝试直接 referencedFeature 引用
                                                    EStructuralFeature rfFeat = sub.eClass().getEStructuralFeature("referencedFeature");
                                                    if (rfFeat != null) {
                                                        Object refObj = sub.eGet(rfFeat);
                                                        if (refObj instanceof EObject) {
                                                            EObject resolved = EcoreUtil.resolve((EObject) refObj, sub);
                                                            if (resolved instanceof org.omg.sysml.lang.sysml.Element) {
                                                                String refId = ((org.omg.sysml.lang.sysml.Element) resolved).getElementId();
                                                                if (refId != null) endFeatureIds.add(refId);
                                                            }
                                                        }
                                                    }
                                                    // 2. FeatureChaining: 取最后一个 chainingFeature 作为实际参数 ID
                                                    String lastChainId = null;
                                                    for (EObject fc : sub.eContents()) {
                                                        if (fc.eClass().getName().equals("Feature")) {
                                                            for (EObject fcc : fc.eContents()) {
                                                                if (fcc.eClass().getName().equals("FeatureChaining")) {
                                                                    try {
                                                                        EStructuralFeature cfFeat = fcc.eClass().getEStructuralFeature("chainingFeature");
                                                                        if (cfFeat != null) {
                                                                            Object cfVal = fcc.eGet(cfFeat);
                                                                            if (cfVal instanceof EObject) {
                                                                                EObject cfResolved = EcoreUtil.resolve((EObject) cfVal, fcc);
                                                                                if (cfResolved instanceof org.omg.sysml.lang.sysml.Element) {
                                                                                    lastChainId = ((org.omg.sysml.lang.sysml.Element) cfResolved).getElementId();
                                                                                }
                                                                            }
                                                                        }
                                                                    } catch (Exception ignored) {
                                                                        // ignored
                                                                    }
                                                                }
                                                            }
                                                            // Feature 自身的 elementId 也作为备选
                                                            try {
                                                                String fcId = ((org.omg.sysml.lang.sysml.Element) fc).getElementId();
                                                                if (fcId != null && lastChainId == null) lastChainId = fcId;
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
                                // 如果有多个候选 ID, 尝试匹配
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
                                    System.out.println("[BIND] " + end1[1] + " <-> " + end2[1]
                                        + " (action1=" + end1[0].substring(0, Math.min(8, end1[0].length()))
                                        + " action2=" + end2[0].substring(0, Math.min(8, end2[0].length())) + ")");
                                } else {
                                    if (end2 != null && end1 == null) {
                                        System.out.println("[BIND WARN] End1 unresolved: " + endFeatureIds.get(0));
                                    }
                                    if (end1 != null && end2 == null) {
                                        System.out.println("[BIND WARN] End2 unresolved: " + endFeatureIds.get(1));
                                    }
                                    if (end1 == null && end2 == null) {
                                        System.out.println("[BIND WARN] Both ends unresolved: " + endFeatureIds.get(0) + ", " + endFeatureIds.get(1));
                                    }
                                }
                            }
                        }
                        // PartDefinition -> UML Class
                        else if (className.equals("PartDefinition") || className.equals("ItemDefinition")
                            || className.equals("OccurrenceDefinition") || className.equals("ConnectionDefinition")
                            || className.equals("InterfaceDefinition") || className.equals("AllocationDefinition")
                            || className.equals("FlowDefinition") || className.equals("RequirementDefinition")
                            || className.equals("ConcernDefinition") || className.equals("ViewDefinition")
                            || className.equals("RenderingDefinition") || className.equals("UseCaseDefinition")
                            || className.equals("VerificationCaseDefinition") || className.equals("AnalysisCaseDefinition")
                            || className.equals("CaseDefinition") || className.equals("MetadataDefinition")
                            || className.equals("PortDefinition") || className.equals("ConjugatedPortDefinition")) {

                            org.eclipse.uml2.uml.Class umlClass = (org.eclipse.uml2.uml.Class)
                                ctx.umlModel.createPackagedElement(structName, UMLPackage.Literals.CLASS);
                            // 添加 SysML 类型注释
                            org.eclipse.uml2.uml.Comment comment = ctx.factory.createComment();
                            comment.setBody("SysML: " + className);
                            umlClass.getOwnedComments().add(comment);
                            ctx.structuralElements.add(umlClass);
                            ctx.processedStructuralIds.add(id);
                            MainRunner.nameToIdMap.put(structName, id);

                            // 提取属性 (FeatureMembership -> AttributeUsage/ReferenceUsage/PartUsage/PortUsage/ItemUsage)
                            for (EObject child : obj.eContents()) {
                                if (child.eClass().getName().equals("FeatureMembership")) {
                                    for (EObject attr : child.eContents()) {
                                        String attrCn = attr.eClass().getName();
                                        if (attrCn.contains("AttributeUsage") || attrCn.contains("ReferenceUsage")
                                            || attrCn.contains("PartUsage") || attrCn.contains("PortUsage")
                                            || attrCn.contains("ItemUsage") || attrCn.contains("ConnectionUsage")
                                            || attrCn.contains("FlowUsage") || attrCn.contains("AllocationUsage")) {
                                            String attrName = null;
                                            if (attr instanceof org.omg.sysml.lang.sysml.Element) {
                                                attrName = ((org.omg.sysml.lang.sysml.Element) attr).getDeclaredName();
                                            }
                                            if (attrName != null && !attrName.isEmpty()) {
                                                org.eclipse.uml2.uml.Property prop = ctx.factory.createProperty();
                                                prop.setName(attrName);
                                                umlClass.getOwnedAttributes().add(prop);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // EnumerationDefinition -> UML Enumeration (DataType with literals)
                        else if (className.equals("EnumerationDefinition")) {
                            org.eclipse.uml2.uml.Enumeration enumDt = (org.eclipse.uml2.uml.Enumeration)
                                ctx.umlModel.createPackagedElement(structName, UMLPackage.Literals.ENUMERATION);
                            // Extract enum literals from VariantMembership -> EnumerationUsage
                            for (EObject child : obj.eContents()) {
                                String childCn = child.eClass().getName();
                                if (childCn.equals("VariantMembership")) {
                                    for (EObject lit : child.eContents()) {
                                        if (lit.eClass().getName().equals("EnumerationUsage") || lit.eClass().getName().contains("EnumerationUsage")) {
                                            String litName = null;
                                            if (lit instanceof org.omg.sysml.lang.sysml.Element) {
                                                litName = ((org.omg.sysml.lang.sysml.Element) lit).getDeclaredName();
                                            }
                                            if (litName != null && !litName.isEmpty()) {
                                                org.eclipse.uml2.uml.EnumerationLiteral enumLit = ctx.factory.createEnumerationLiteral();
                                                enumLit.setName(litName);
                                                enumDt.getOwnedLiterals().add(enumLit);
                                            }
                                        }
                                    }
                                }
                                // Also check direct FeatureMembership for named enum values (e.g., A = 4.0)
                                if (childCn.equals("FeatureMembership")) {
                                    for (EObject lit : child.eContents()) {
                                        String litCn = lit.eClass().getName();
                                        if (litCn.contains("EnumerationUsage") || litCn.contains("AttributeUsage")) {
                                            String litName = null;
                                            if (lit instanceof org.omg.sysml.lang.sysml.Element) {
                                                litName = ((org.omg.sysml.lang.sysml.Element) lit).getDeclaredName();
                                            }
                                            if (litName != null && !litName.isEmpty()) {
                                                org.eclipse.uml2.uml.EnumerationLiteral enumLit = ctx.factory.createEnumerationLiteral();
                                                enumLit.setName(litName);
                                                enumDt.getOwnedLiterals().add(enumLit);
                                            }
                                        }
                                    }
                                }
                            }
                            ctx.structuralElements.add(enumDt);
                            ctx.processedStructuralIds.add(id);
                            MainRunner.nameToIdMap.put(structName, id);
                        }
                        // AttributeDefinition -> UML DataType with attributes
                        else if (className.equals("AttributeDefinition")) {
                            org.eclipse.uml2.uml.DataType dt = (org.eclipse.uml2.uml.DataType)
                                ctx.umlModel.createPackagedElement(structName, UMLPackage.Literals.DATA_TYPE);
                            // Extract attributes from FeatureMembership -> AttributeUsage/ReferenceUsage
                            for (EObject child : obj.eContents()) {
                                if (child.eClass().getName().equals("FeatureMembership")) {
                                    for (EObject attr : child.eContents()) {
                                        String attrCn = attr.eClass().getName();
                                        if (attrCn.contains("AttributeUsage") || attrCn.contains("ReferenceUsage")) {
                                            String attrName = null;
                                            if (attr instanceof org.omg.sysml.lang.sysml.Element) {
                                                attrName = ((org.omg.sysml.lang.sysml.Element) attr).getDeclaredName();
                                            }
                                            if (attrName != null && !attrName.isEmpty()) {
                                                org.eclipse.uml2.uml.Property prop = ctx.factory.createProperty();
                                                prop.setName(attrName);
                                                dt.getOwnedAttributes().add(prop);
                                            }
                                        }
                                    }
                                }
                            }
                            ctx.structuralElements.add(dt);
                            ctx.processedStructuralIds.add(id);
                            MainRunner.nameToIdMap.put(structName, id);
                        }
                        // ConstraintDefinition -> UML Class (with constraint stereotype)
                        else if (className.equals("ConstraintDefinition")) {
                            org.eclipse.uml2.uml.Class umlClass = (org.eclipse.uml2.uml.Class)
                                ctx.umlModel.createPackagedElement(structName, UMLPackage.Literals.CLASS);
                            org.eclipse.uml2.uml.Comment comment = ctx.factory.createComment();
                            comment.setBody("SysML: ConstraintDefinition");
                            umlClass.getOwnedComments().add(comment);
                            ctx.structuralElements.add(umlClass);
                            ctx.processedStructuralIds.add(id);
                            MainRunner.nameToIdMap.put(structName, id);
                        }
                        // StateDefinition -> UML StateMachine with States and Transitions
                        else if (className.equals("StateDefinition")) {
                            org.eclipse.uml2.uml.StateMachine sm = (org.eclipse.uml2.uml.StateMachine)
                                ctx.umlModel.createPackagedElement(structName, UMLPackage.Literals.STATE_MACHINE);
                            ctx.structuralElements.add(sm);
                            ctx.processedStructuralIds.add(id);
                            MainRunner.nameToIdMap.put(structName, id);

                            // Create a Region to hold the states
                            org.eclipse.uml2.uml.Region region = ctx.factory.createRegion();
                            sm.getRegions().add(region);

                            // Collect StateUsage children: stateId -> stateName
                            Map<String, String> stateIdToName = new HashMap<>();
                            Map<String, org.eclipse.uml2.uml.State> stateNameToUml = new HashMap<>();
                            // TransitionUsage data: [srcStateId, tgtStateId, triggerTypeName]
                            List<String[]> stateTransitions = new ArrayList<>();
                            // Initial succession target: stateId
                            String initialStateId = null;

                            for (EObject child : obj.eContents()) {
                                String childCn = child.eClass().getName();
                                if (childCn.equals("FeatureMembership")) {
                                    for (EObject inner : child.eContents()) {
                                        String innerCn = inner.eClass().getName();

                                        // StateUsage -> record state
                                        if (innerCn.equals("StateUsage")) {
                                            String sName = null;
                                            String sId = null;
                                            if (inner instanceof org.omg.sysml.lang.sysml.Element) {
                                                sName = ((org.omg.sysml.lang.sysml.Element) inner).getDeclaredName();
                                                sId = ((org.omg.sysml.lang.sysml.Element) inner).getElementId();
                                            }
                                            if (sName != null && sId != null) {
                                                stateIdToName.put(sId, sName);
                                            }
                                        }
                                        // SuccessionAsUsage -> initial transition ("first start then X")
                                        else if (innerCn.equals("SuccessionAsUsage")) {
                                            // Extract target from second EndFeatureMembership
                                            int endIdx = 0;
                                            for (EObject efm : inner.eContents()) {
                                                if (efm.eClass().getName().equals("EndFeatureMembership")) {
                                                    if (endIdx == 1) { // second end = target
                                                        for (EObject ref : efm.eContents()) {
                                                            if (ref.eClass().getName().contains("ReferenceUsage")) {
                                                                for (EObject rs : ref.eContents()) {
                                                                    if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                                        try {
                                                                            var rfFeat = rs.eClass().getEStructuralFeature("referencedFeature");
                                                                            if (rfFeat != null) {
                                                                                Object rfVal = rs.eGet(rfFeat);
                                                                                if (rfVal instanceof EObject) {
                                                                                    initialStateId = ((org.omg.sysml.lang.sysml.Element) rfVal).getElementId();
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
                                                    endIdx++;
                                                }
                                            }
                                        }
                                        // TransitionUsage -> record transition data
                                        else if (innerCn.equals("TransitionUsage")) {
                                            String srcStateId = null;
                                            String tgtStateId = null;
                                            String triggerTypeName = null;

                                            for (EObject tc : inner.eContents()) {
                                                String tcCn = tc.eClass().getName();
                                                // Source: Membership.memberElement -> StateUsage
                                                if (tcCn.equals("Membership") && !tcCn.contains("End") && !tcCn.contains("Parameter") && !tcCn.contains("Owning")) {
                                                    try {
                                                        var meFeat = tc.eClass().getEStructuralFeature("memberElement");
                                                        if (meFeat != null) {
                                                            Object meVal = tc.eGet(meFeat);
                                                            if (meVal instanceof EObject) {
                                                                srcStateId = ((org.omg.sysml.lang.sysml.Element) meVal).getElementId();
                                                            }
                                                        }
                                                    } catch (Exception ignored) {
                                                        // ignored
                                                    }
                                                }
                                                // Trigger: TransitionFeatureMembership(kind=trigger) -> AcceptActionUsage -> ParameterMembership -> ReferenceUsage -> FeatureTyping
                                                if (tcCn.equals("TransitionFeatureMembership")) {
                                                    String kind = ExpressionUtils.getFeatureString(tc, "kind");
                                                    if ("trigger".equals(kind)) {
                                                        for (EObject acu : tc.eContents()) {
                                                            if (acu.eClass().getName().equals("AcceptActionUsage")) {
                                                                for (EObject pm : acu.eContents()) {
                                                                    if (pm.eClass().getName().equals("ParameterMembership")) {
                                                                        for (EObject ru : pm.eContents()) {
                                                                            if (ru.eClass().getName().contains("ReferenceUsage")) {
                                                                                for (EObject ft : ru.eContents()) {
                                                                                    if (ft.eClass().getName().equals("FeatureTyping")) {
                                                                                        try {
                                                                                            var typeFeat = ft.eClass().getEStructuralFeature("type");
                                                                                            if (typeFeat != null) {
                                                                                                Object typeVal = ft.eGet(typeFeat);
                                                                                                if (typeVal instanceof EObject) {
                                                                                                    triggerTypeName = ((org.omg.sysml.lang.sysml.Element) typeVal).getDeclaredName();
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
                                                // Target: OwningMembership -> SuccessionAsUsage -> second EndFeatureMembership -> ReferenceSubsetting
                                                if (tcCn.equals("OwningMembership")) {
                                                    for (EObject succ : tc.eContents()) {
                                                        if (succ.eClass().getName().equals("SuccessionAsUsage")) {
                                                            int tEndIdx = 0;
                                                            for (EObject efm : succ.eContents()) {
                                                                if (efm.eClass().getName().equals("EndFeatureMembership")) {
                                                                    if (tEndIdx == 1) { // second end = target
                                                                        for (EObject ref : efm.eContents()) {
                                                                            if (ref.eClass().getName().contains("ReferenceUsage")) {
                                                                                for (EObject rs : ref.eContents()) {
                                                                                    if (rs.eClass().getName().equals("ReferenceSubsetting")) {
                                                                                        try {
                                                                                            var rfFeat = rs.eClass().getEStructuralFeature("referencedFeature");
                                                                                            if (rfFeat != null) {
                                                                                                Object rfVal = rs.eGet(rfFeat);
                                                                                                if (rfVal instanceof EObject) {
                                                                                                    tgtStateId = ((org.omg.sysml.lang.sysml.Element) rfVal).getElementId();
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
                                                                    tEndIdx++;
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            if (srcStateId != null && tgtStateId != null) {
                                                stateTransitions.add(new String[]{srcStateId, tgtStateId, triggerTypeName});
                                            }
                                        }
                                    }
                                }
                            }

                            // Create UML States
                            for (Map.Entry<String, String> entry : stateIdToName.entrySet()) {
                                org.eclipse.uml2.uml.State state = ctx.factory.createState();
                                state.setName(entry.getValue());
                                region.getSubvertices().add(state);
                                stateNameToUml.put(entry.getValue(), state);
                            }

                            // Create Initial Pseudostate and transition to initial state
                            if (initialStateId != null && stateIdToName.containsKey(initialStateId)) {
                                org.eclipse.uml2.uml.Pseudostate ps = ctx.factory.createPseudostate();
                                ps.setName("Initial");
                                ps.setKind(org.eclipse.uml2.uml.PseudostateKind.INITIAL_LITERAL);
                                region.getSubvertices().add(ps);
                                String initName = stateIdToName.get(initialStateId);
                                org.eclipse.uml2.uml.State initState = stateNameToUml.get(initName);
                                if (initState != null) {
                                    org.eclipse.uml2.uml.Transition initTrans = ctx.factory.createTransition();
                                    initTrans.setSource(ps);
                                    initTrans.setTarget(initState);
                                    region.getTransitions().add(initTrans);
                                }
                            }

                            // Create Transitions with Triggers
                            Map<String, org.eclipse.uml2.uml.Signal> signalMap = new HashMap<>();
                            for (String[] trans : stateTransitions) {
                                String srcName = stateIdToName.get(trans[0]);
                                String tgtName = stateIdToName.get(trans[1]);
                                String triggerName = trans[2];
                                org.eclipse.uml2.uml.State srcState = srcName != null ? stateNameToUml.get(srcName) : null;
                                org.eclipse.uml2.uml.State tgtState = tgtName != null ? stateNameToUml.get(tgtName) : null;
                                if (srcState != null && tgtState != null) {
                                    org.eclipse.uml2.uml.Transition umlTrans = ctx.factory.createTransition();
                                    umlTrans.setSource(srcState);
                                    umlTrans.setTarget(tgtState);
                                    // Create trigger with SignalEvent
                                    if (triggerName != null && !triggerName.isEmpty()) {
                                        org.eclipse.uml2.uml.Signal sig = signalMap.get(triggerName);
                                        if (sig == null) {
                                            sig = (org.eclipse.uml2.uml.Signal)
                                                ctx.umlModel.createPackagedElement(triggerName, UMLPackage.Literals.SIGNAL);
                                            signalMap.put(triggerName, sig);
                                        }
                                        org.eclipse.uml2.uml.SignalEvent sigEvent = ctx.factory.createSignalEvent();
                                        sigEvent.setName(triggerName + "_Event");
                                        sigEvent.setSignal(sig);
                                        ctx.umlModel.getPackagedElements().add(sigEvent);
                                        org.eclipse.uml2.uml.Trigger trigger = ctx.factory.createTrigger();
                                        trigger.setName(triggerName + "_Trigger");
                                        trigger.setEvent(sigEvent);
                                        umlTrans.getTriggers().add(trigger);
                                    }
                                    region.getTransitions().add(umlTrans);
                                }
                            }

                        }
                        // CalculationDefinition (non-action calc) -> UML Class
                        else if (className.equals("CalculationDefinition")) {
                            org.eclipse.uml2.uml.Class umlClass = (org.eclipse.uml2.uml.Class)
                                ctx.umlModel.createPackagedElement(structName, UMLPackage.Literals.CLASS);
                            org.eclipse.uml2.uml.Comment comment = ctx.factory.createComment();
                            comment.setBody("SysML: CalculationDefinition");
                            umlClass.getOwnedComments().add(comment);
                            ctx.structuralElements.add(umlClass);
                            ctx.processedStructuralIds.add(id);
                            MainRunner.nameToIdMap.put(structName, id);
                        }
                    }
                }
            }
        }
    }
}
