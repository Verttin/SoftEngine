package sysml2uml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.uml2.uml.Activity;
import org.eclipse.uml2.uml.ActivityFinalNode;
import org.eclipse.uml2.uml.CallBehaviorAction;
import org.eclipse.uml2.uml.ControlFlow;
import org.eclipse.uml2.uml.InitialNode;
import org.eclipse.uml2.uml.ObjectFlow;
import org.eclipse.uml2.uml.OpaqueBehavior;
import org.eclipse.uml2.uml.OpaqueExpression;
import org.eclipse.uml2.uml.UMLPackage;

class CalcModel {

    /**
     * PASS C1 + C2 + C3: Calculation Model 流水线。
     * 从 MainRunner.executePureXMLPipeline() 中提取。
     *
     * @param activity      目标 UML Activity
     * @param ctx           流水线上下文 (持有 factory, resource 等共享状态)
     * @param sysmlBasePath .sysml 文件基础路径
     * @param calcIdToName  CalculationUsage ID → declaredName
     * @param calcIdToType  CalculationUsage ID → 定义的类型名
     * @param globalIdToName 全局 Element ID → declaredName 映射
     */
    static void runCalcPipeline(Activity activity, PipelineContext ctx,
                                String sysmlBasePath,
                                Map<String, String> calcIdToName,
                                Map<String, String> calcIdToType,
                                Map<String, String> globalIdToName) {
        try {

            // --- PASS C1: CalculationUsage 结构提取 ---
            System.out.println("[DEBUG] === PASS C1: CalculationUsage 结构提取 ===");

            // 收集上下文 features (AttributeUsage + ReferenceUsage)
            Map<String, String[]> contextFeatures = new HashMap<>(); // id → [name, typeName, direction, isAttr]
            Iterator<EObject> iterator = ctx.resource.getAllContents();
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                if (!(obj instanceof org.omg.sysml.lang.sysml.Element)) {
                    continue;
                }
                String eid = ((org.omg.sysml.lang.sysml.Element) obj).getElementId();
                String dn = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
                String cn = obj.eClass().getName();
                if (eid == null || dn == null) {
                    continue;
                }

                if ("AttributeUsage".equals(cn)) {
                    String typeName = getFeatureTypeName(obj);
                    contextFeatures.put(eid, new String[]{dn, typeName, "", "true"});
                } else if ("ReferenceUsage".equals(cn)) {
                    String dir = getFeatureValue(obj, "direction");
                    // 只收集 ActionUsage 的直接子参数 (排除 calc 内部的参数)
                    EObject container = obj.eContainer();
                    boolean isCalcParam = false;
                    while (container != null) {
                        if ("CalculationUsage".equals(container.eClass().getName())) {
                            isCalcParam = true;
                            break;
                        }
                        if ("ActionUsage".equals(container.eClass().getName())) {
                            break;
                        }
                        container = container.eContainer();
                    }
                    if (!isCalcParam && !dir.isEmpty()) {
                        String typeName = getFeatureTypeName(obj);
                        contextFeatures.put(eid, new String[]{dn, typeName, dir, "false"});
                    }
                }
            }
            System.out.println("[DEBUG] Context features: " + contextFeatures.size());

            // Phase 1: 注册 calc output reference IDs (递归搜索 ReturnParameterMembership)
            Map<String, String> calcOutputToCalcId = new HashMap<>();
            Map<String, String> calcOutputName = new HashMap<>();
            List<String> calcIds = new ArrayList<>(calcIdToName.keySet());

            for (String calcId : calcIds) {
                iterator = ctx.resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject obj = iterator.next();
                    if (!(obj instanceof org.omg.sysml.lang.sysml.Element)) {
                        continue;
                    }
                    String eid = ((org.omg.sysml.lang.sysml.Element) obj).getElementId();
                    if (!calcId.equals(eid)) {
                        continue;
                    }
                    // 找到 calc, 递归搜索 ReturnParameterMembership
                    findAndRegisterOutputs(obj, calcId, calcOutputToCalcId, calcOutputName);
                    break;
                }
            }
            System.out.println("[DEBUG] Calc outputs registered: " + calcOutputToCalcId.size());
            for (Map.Entry<String, String> e : calcOutputToCalcId.entrySet()) {
                System.out.println("[DEBUG]   output " + calcOutputName.get(e.getKey()) + " (id:" + e.getKey().substring(0,8) + "...) → calc " + calcIdToName.get(e.getValue()));
            }

            // Phase 2: 解析每个 calc 的 input 表达式
            // 结果: calcDeps[calcId] = [依赖的 calcId 列表]
            //       calcInputDetails[calcId] = [{paramName, kind, detail}]
            Map<String, List<String>> calcDeps = new HashMap<>();
            Map<String, List<String[]>> calcInputDetails = new HashMap<>();
            for (String cid : calcIds) {
                calcDeps.put(cid, new ArrayList<>());
                calcInputDetails.put(cid, new ArrayList<>());
            }

            for (String calcId : calcIds) {
                iterator = ctx.resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject obj = iterator.next();
                    if (!(obj instanceof org.omg.sysml.lang.sysml.Element)) {
                        continue;
                    }
                    if (!calcId.equals(((org.omg.sysml.lang.sysml.Element) obj).getElementId())) {
                        continue;
                    }
                    // 遍历 FeatureMembership 子元素找 input ReferenceUsage
                    for (EObject fm : obj.eContents()) {
                        if (!"FeatureMembership".equals(fm.eClass().getName())) {
                            continue;
                        }
                        for (EObject ru : fm.eContents()) {
                            if (!"ReferenceUsage".equals(ru.eClass().getName())) {
                                continue;
                            }
                            String paramName = ((org.omg.sysml.lang.sysml.Element) ru).getDeclaredName();
                            String dir = getFeatureValue(ru, "direction");
                            if (!"in".equals(dir)) {
                                continue;
                            }
                            // 找到 FeatureValue → 表达式
                            for (EObject fv : ru.eContents()) {
                                if (!"FeatureValue".equals(fv.eClass().getName())) {
                                    continue;
                                }
                                for (EObject expr : fv.eContents()) {
                                    String exprType = expr.eClass().getName();
                                    if ("FeatureReferenceExpression".equals(exprType)) {
                                        String memberId = getMemberElementId(expr);
                                        String memberName = memberId != null ? globalIdToName.get(memberId) : null;
                                        calcInputDetails.get(calcId).add(
                                            new String[]{paramName, "DIRECT_REF", memberId, memberName});
                                        // 检查是否是另一个 calc 的 output
                                        if (memberId != null && calcOutputToCalcId.containsKey(memberId)) {
                                            String srcCalc = calcOutputToCalcId.get(memberId);
                                            if (!calcDeps.get(calcId).contains(srcCalc)) {
                                                calcDeps.get(calcId).add(srcCalc);
                                            }
                                        }
                                    } else if ("FeatureChainExpression".equals(exprType)) {
                                        String baseCalcId = findChainBaseRef(expr);
                                        String chainMemberId = findChainMember(expr);
                                        String baseCalcName = baseCalcId != null ? calcIdToName.get(baseCalcId) : null;
                                        String chainName = chainMemberId != null ? calcOutputName.getOrDefault(chainMemberId, globalIdToName.get(chainMemberId)) : null;
                                        calcInputDetails.get(calcId).add(new String[]{paramName, "CHAIN_REF",
                                            baseCalcId, baseCalcName, chainMemberId, chainName});
                                        if (baseCalcId != null && calcIdToName.containsKey(baseCalcId)) {
                                            if (!calcDeps.get(calcId).contains(baseCalcId)) {
                                                calcDeps.get(calcId).add(baseCalcId);
                                            }
                                        }
                                    } else if ("InvocationExpression".equals(exprType)) {
                                        String funcName = findInvocationFunction(expr);
                                        List<String> argNames = findInvocationArgs(expr, globalIdToName);
                                        calcInputDetails.get(calcId).add(new String[]{paramName, "INVOCATION",
                                            funcName, String.join(",", argNames)});
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
            }

            // 打印 C1 结果
            for (String calcId : calcIds) {
                System.out.println("[DEBUG] Calc '" + calcIdToName.get(calcId)
                    + "' (" + calcIdToType.getOrDefault(calcId, "?") + "):");
                System.out.println("  inputs: " + calcInputDetails.get(calcId).size()
                    + ", deps: " + calcDeps.get(calcId).stream()
                        .map(d -> calcIdToName.get(d)).toList());
                    for (String[] detail : calcInputDetails.get(calcId)) {
                        System.out.println("    " + detail[0] + " = " + detail[1]
                            + "(" + (detail.length > 2 ? detail[2] : "") + ")");
                    }
            }

            // --- PASS C2: 依赖图构建 + 拓扑排序 ---
            System.out.println("\n[DEBUG] === PASS C2: 拓扑排序 ===");
            List<String> sorted = new ArrayList<>();
            Set<String> remaining = new HashSet<>(calcIds);
            Set<String> resolved = new HashSet<>();
            int maxIter = calcIds.size() + 1;
            while (!remaining.isEmpty() && maxIter-- > 0) {
                boolean progress = false;
                for (String cid : new ArrayList<>(remaining)) {
                    boolean allDepsResolved = true;
                    for (String dep : calcDeps.get(cid)) {
                        if (!resolved.contains(dep)) {
                            allDepsResolved = false;
                            break;
                        }
                    }
                    if (allDepsResolved) {
                        sorted.add(cid);
                        resolved.add(cid);
                        remaining.remove(cid);
                        progress = true;
                    }
                }
                    if (!progress) {
                        // 环检测: 把剩余的按原顺序加入
                        for (String cid : calcIds) {
                            if (remaining.contains(cid)) {
                                sorted.add(cid);
                                remaining.remove(cid);
                            }
                        }
                        System.out.println("[WARN] 依赖环检测, 按原序追加剩余 calc");
                    }
            }
            List<String> topoOrder = sorted;
            System.out.println("[DEBUG] 拓扑序: " + topoOrder.stream()
                .map(id -> calcIdToName.get(id)).toList());

            // --- PASS C3: UML 节点和边创建 ---
            System.out.println("\n[DEBUG] === PASS C3: UML 节点/边创建 ===");
            Map<String, CallBehaviorAction> calcActions = new HashMap<>();

            for (String calcId : topoOrder) {
                String name = calcIdToName.get(calcId);
                String typeName = calcIdToType.getOrDefault(calcId, "");
                String bodyText = typeName + "(" + calcId + ")";
                CallBehaviorAction action = UmlHelper.createCallBehaviorActionWithBody(
                    activity, name, bodyText, "SysML");
                MainRunner.umlNodes.put(calcId, action);
                calcActions.put(calcId, action);
                MainRunner.nameToIdMap.put(name, calcId);
                System.out.println("[DEBUG] Created CallBehaviorAction: " + name
                    + " [" + typeName + "]");
            }

            // 创建依赖边 (ObjectFlow)
            int edgeCount = 0;
            for (String calcId : topoOrder) {
                CallBehaviorAction target = calcActions.get(calcId);
                for (String depId : calcDeps.get(calcId)) {
                    CallBehaviorAction source = calcActions.get(depId);
                    if (source != null) {
                        ObjectFlow flow = ctx.factory.createObjectFlow();
                        activity.getEdges().add(flow);
                        flow.setSource(source);
                        flow.setTarget(target);
                        // 添加 guard 标注数据依赖信息
                        String depOutputName = "";
                        for (Map.Entry<String, String> e : calcOutputToCalcId.entrySet()) {
                            if (depId.equals(e.getValue())) {
                                depOutputName = calcOutputName.getOrDefault(e.getKey(), "");
                                break;
                            }
                        }
                        OpaqueExpression guardExpr = ctx.factory.createOpaqueExpression();
                        guardExpr.getBodies().add(depOutputName);
                        flow.setGuard(guardExpr);
                        edgeCount++;
                        System.out.println("[DEBUG] ObjectFlow: " + calcIdToName.get(depId)
                            + "." + depOutputName + " → " + calcIdToName.get(calcId));
                    }
                }
            }

            // 添加 Start/End 节点
            InitialNode startNode = (InitialNode) activity.createOwnedNode(
                "Start", UMLPackage.Literals.INITIAL_NODE);
            ActivityFinalNode endNode = (ActivityFinalNode) activity.createOwnedNode(
                "End", UMLPackage.Literals.ACTIVITY_FINAL_NODE);
            MainRunner.umlNodes.put("START_NODE", startNode);
            MainRunner.umlNodes.put("END_NODE", endNode);

            // Start → 第一个 calc (ControlFlow)
            if (!topoOrder.isEmpty()) {
                ControlFlow startFlow = ctx.factory.createControlFlow();
                activity.getEdges().add(startFlow);
                startFlow.setSource(startNode);
                startFlow.setTarget(calcActions.get(topoOrder.get(0)));
                edgeCount++;
            }
            // 最后一个 calc → End (ControlFlow)
            if (!topoOrder.isEmpty()) {
                ControlFlow endFlow = ctx.factory.createControlFlow();
                activity.getEdges().add(endFlow);
                endFlow.setSource(calcActions.get(topoOrder.get(topoOrder.size() - 1)));
                endFlow.setTarget(endNode);
                edgeCount++;
            }

            System.out.println("[DEBUG] PASS C3 完成: " + calcActions.size()
                + " 个节点, " + edgeCount + " 条边");

        } catch (Exception e) {
            System.out.println("[ERROR] Calc branch failed: "
                + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===== Calc Model 辅助方法 =====

    /** 获取 feature 的 type name (从 FeatureTyping → type href，优先尝试代理解析) */
    static String getFeatureTypeName(EObject feature) {
        for (EObject child : feature.eContents()) {
            if ("FeatureTyping".equals(child.eClass().getName())) {
                for (EStructuralFeature f : child.eClass().getEAllStructuralFeatures()) {
                    if ("type".equals(f.getName())) {
                        try {
                            Object v = child.eGet(f);
                            if (v == null) {
                                continue;
                            }

                            // 优先尝试解析 EMF 代理
                            if (v instanceof EObject) {
                                EObject resolved = EcoreUtil.resolve((EObject) v, child);
                                if (resolved != null && !resolved.eIsProxy()) {
                                    if (resolved instanceof org.omg.sysml.lang.sysml.Element) {
                                        String dn = ((org.omg.sysml.lang.sysml.Element) resolved).getDeclaredName();
                        if (dn != null && !dn.isEmpty()) {
                            return dn;
                        }
                                    }
                                }
                            }

                            // 回退: 从 href 提取最后一段作为 type name
                            String href = v.toString();
                            int hashIdx = href.indexOf('#');
                            if (hashIdx > 0) {
                                href = href.substring(0, hashIdx);
                            }
                            int lastSlash = href.lastIndexOf('/');
                            if (lastSlash >= 0) {
                                href = href.substring(lastSlash + 1);
                            }
                            // 去除文件扩展名
                            if (href.endsWith(".kermlx") || href.endsWith(".sysmlx")) {
                                href = href.substring(0, href.lastIndexOf('.'));
                            }
                            return href;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return "";
    }

    /** 获取 EObject 上指定 structural feature 的 String 值 */
    static String getFeatureValue(EObject obj, String featureName) {
        for (EStructuralFeature f : obj.eClass().getEAllStructuralFeatures()) {
            if (featureName.equals(f.getName())) {
                try {
                    Object val = obj.eGet(f);
                    if (val instanceof String) {
                        return (String) val;
                    }
                    if (val != null) {
                        return val.toString();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    /** 获取表达式的 memberElement ID
     *  memberElement 可能在对象自身上 (Membership)，或在子 Membership 上 (FeatureReferenceExpression)
     */
    static String getMemberElementId(EObject expr) {
        // 1) 先尝试直接从对象自身获取 memberElement
        String directResult = extractMemberElement(expr);
        if (directResult != null) {
            return directResult;
        }

        // 2) 如果对象自身没有 memberElement，搜索子 Membership
        for (EObject child : expr.eContents()) {
            String cn = child.eClass().getName();
            if ("Membership".equals(cn)
                && !"ReturnParameterMembership".equals(cn)
                && !"ParameterMembership".equals(cn)) {
                String result = extractMemberElement(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /** 从对象上提取 memberElement 引用 ID (含代理解析) */
    static String extractMemberElement(EObject obj) {
        for (EStructuralFeature f : obj.eClass().getEAllStructuralFeatures()) {
            if ("memberElement".equals(f.getName())) {
                try {
                    Object val = obj.eGet(f);
                    if (val instanceof EObject) {
                        EObject eObj = (EObject) val;
                        // 代理解析
                        if (eObj.eIsProxy()) {
                            EObject resolved = EcoreUtil.resolve(eObj, obj);
                            if (resolved != null && !resolved.eIsProxy()) {
                                eObj = resolved;
                            }
                        }
                        if (eObj instanceof org.omg.sysml.lang.sysml.Element) {
                            String eid = ((org.omg.sysml.lang.sysml.Element) eObj).getElementId();
                            if (eid != null) {
                                return eid;
                            }
                        }
                        // 回退: 代理 URI fragment
                        if (eObj.eIsProxy()) {
                            try {
                                URI uri = ((org.eclipse.emf.ecore.InternalEObject) eObj).eProxyURI();
                                if (uri != null && uri.fragment() != null) {
                                    return uri.fragment();
                                }
                            } catch (Exception ignored) {
                            }
                            }
                        }
                    }
                    if (val != null && !(val instanceof EObject)) {
                        return val.toString();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /** FeatureChainExpression: 找到 base FeatureReferenceExpression 的 memberElement (指向 calc) */
    static String findChainBaseRef(EObject chainExpr) {
        for (EObject child : chainExpr.eContents()) {
            if ("ParameterMembership".equals(child.eClass().getName())) {
                for (EObject inner : child.eContents()) {
                    for (EObject fv : inner.eContents()) {
                        if ("FeatureValue".equals(fv.eClass().getName())) {
                            for (EObject expr : fv.eContents()) {
                                if ("FeatureReferenceExpression".equals(expr.eClass().getName())) {
                                    return getMemberElementId(expr);
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** FeatureChainExpression: 找到 chain target 的 memberElement (指向 output feature) */
    static String findChainMember(EObject chainExpr) {
        for (EObject child : chainExpr.eContents()) {
            String cn = child.eClass().getName();
            if ("Membership".equals(cn) && !"ReturnParameterMembership".equals(cn) && !"ParameterMembership".equals(cn)) {
                // 复用 getMemberElementId (已包含代理解析逻辑)
                String memberId = getMemberElementId(child);
                if (memberId != null) return memberId;
            }
        }
        return null;
    }

    /** InvocationExpression: 找到函数名 (从 SelectExpression href) */
    static String findInvocationFunction(EObject invExpr) {
        for (EObject child : invExpr.eContents()) {
            if ("Membership".equals(child.eClass().getName())) {
                for (EObject se : child.eContents()) {
                    if ("SelectExpression".equals(se.eClass().getName())) {
                        String href = getFeatureValue(se, "href");
                        if (href.isEmpty()) {
                            // 尝试 eGet
                            for (EStructuralFeature f : se.eClass().getEAllStructuralFeatures()) {
                                if ("href".equals(f.getName())) {
                                    try {
                                        Object v = se.eGet(f);
                                        if (v != null) {
                                            href = v.toString();
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                        if (!href.isEmpty()) {
                            int hashIdx = href.indexOf('#');
                            if (hashIdx > 0) {
                                href = href.substring(0, hashIdx);
                            }
                            int lastSlash = href.lastIndexOf('/');
                            if (lastSlash >= 0) {
                                href = href.substring(lastSlash + 1);
                            }
                            return href;
                        }
                    }
                }
            }
        }
        return "?";
    }

    /** InvocationExpression: 提取参数名列表 */
    static List<String> findInvocationArgs(EObject invExpr, Map<String, String> idToName) {
        List<String> args = new ArrayList<>();
        for (EObject child : invExpr.eContents()) {
            if ("ParameterMembership".equals(child.eClass().getName())) {
                String dir = getFeatureValue(child, "visibility");
                if ("private".equals(dir) || dir.isEmpty()) {
                    // 递归搜索 FeatureReferenceExpression
                    String argName = findNestedRefName(child, idToName);
                    if (argName != null) {
                        args.add(argName);
                    }
                }
            }
        }
        return args;
    }

    /** 递归搜索子树中的第一个 FeatureReferenceExpression 的 memberElement name */
    static String findNestedRefName(EObject root, Map<String, String> idToName) {
        if ("FeatureReferenceExpression".equals(root.eClass().getName())) {
            String memberId = getMemberElementId(root);
            return memberId != null ? idToName.get(memberId) : null;
        }
        for (EObject child : root.eContents()) {
            String result = findNestedRefName(child, idToName);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 递归搜索 CalculationUsage 的 ReturnParameterMembership，注册 output ReferenceUsage
     * XMI 结构: CalculationUsage > ReturnParameterMembership > ReferenceUsage(declaredName="a", direction="out")
     */
    static void findAndRegisterOutputs(EObject calcObj, String calcId,
            Map<String, String> calcOutputToCalcId, Map<String, String> calcOutputName) {
        for (EObject child : calcObj.eContents()) {
            if ("ReturnParameterMembership".equals(child.eClass().getName())) {
                for (EObject ru : child.eContents()) {
                    if (ru instanceof org.omg.sysml.lang.sysml.Element) {
                        String outputName = ((org.omg.sysml.lang.sysml.Element) ru).getDeclaredName();
                        String outputId = ((org.omg.sysml.lang.sysml.Element) ru).getElementId();
                        if (outputName != null && outputId != null) {
                            calcOutputToCalcId.put(outputId, calcId);
                            calcOutputName.put(outputId, outputName);
                        }
                    }
                }
            }
            // 递归搜索更深层的 ReturnParameterMembership
            findAndRegisterOutputs(child, calcId, calcOutputToCalcId, calcOutputName);
        }
    }

    /**
     * 从 FeatureTyping 元素解析 calc 定义的类型名称
     * 1. 尝试通过 EcoreUtil.resolve() 解析 EMF 代理
     * 2. 回退: 读取 .sysml 源文件查找 "calc calcName : TypeName" 声明
     */
    static String resolveCalcTypeName(EObject featureTyping, String sysmlBasePath, String calcDeclaredName) {
        for (EStructuralFeature f : featureTyping.eClass().getEAllStructuralFeatures()) {
            if ("type".equals(f.getName())) {
                try {
                    Object v = featureTyping.eGet(f);
                    if (v == null) {
                        continue;
                    }

                    if (v instanceof EObject) {
                        EObject eObj = (EObject) v;

                        // 1) 尝试解析 EMF 代理
                        EObject resolved = EcoreUtil.resolve(eObj, featureTyping);
                        if (resolved != null && !resolved.eIsProxy()) {
                            if (resolved instanceof org.omg.sysml.lang.sysml.Element) {
                                String dn = ((org.omg.sysml.lang.sysml.Element) resolved).getDeclaredName();
                                if (dn != null && !dn.isEmpty()) {
                                    return dn;
                                }
                            }
                        }

                        // 2) 回退: 从 proxy URI 提取文件路径，读取 .sysml 源文件
                        String proxyUriStr = null;
                        if (eObj.eIsProxy()) {
                            try {
                                URI uri = ((org.eclipse.emf.ecore.InternalEObject) eObj).eProxyURI();
                                if (uri != null) {
                                    proxyUriStr = uri.toString();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        if (proxyUriStr == null) {
                            proxyUriStr = eObj.toString();
                        }

                        String sysmlPath = resolveSysmlFilePath(proxyUriStr, sysmlBasePath);
                        if (sysmlPath != null && calcDeclaredName != null) {
                            String typeName = extractCalcTypeFromSysml(sysmlPath, calcDeclaredName);
                            if (!typeName.isEmpty()) {
                                return typeName;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    /**
     * 从代理 URI 解析出 .sysml 文件路径
     * 代理 URI 格式:
     *   - "file:/D:/.../Calculation Usages-1.sysml#|16" (eProxyURI 绝对路径)
     *   - "Calculation%20Usages-1.sysml#|16" (XMI href 相对路径)
     */
    static String resolveSysmlFilePath(String proxyUri, String sysmlBasePath) {
        // 去掉 fragment
        String fileRef = proxyUri;
        int hashIdx = fileRef.indexOf('#');
        if (hashIdx >= 0) {
            fileRef = fileRef.substring(0, hashIdx);
        }

        // 处理 file: URI scheme
        if (fileRef.startsWith("file:/")) {
            fileRef = fileRef.substring(6); // 去掉 "file:/"
            // URL decode (处理 %20 等编码字符)
            try {
                fileRef = java.net.URLDecoder.decode(fileRef, "UTF-8");
            } catch (Exception ignored) {
            }
            // Windows: "D:/path/..." → 直接使用
            // Unix: "/path/..." → 直接使用 (前面被去掉了一个 /)
            if (!fileRef.startsWith("/") && !fileRef.matches("^[A-Za-z]:.*")) {
                fileRef = "/" + fileRef; // Unix 路径补回 /
            }
            return fileRef;
        }
        if (fileRef.startsWith("file:")) {
            fileRef = fileRef.substring(5);
            try {
                fileRef = java.net.URLDecoder.decode(fileRef, "UTF-8");
            } catch (Exception ignored) {
            }
            return fileRef;
        }

        // URL decode (处理 %20 等)
        try {
            fileRef = java.net.URLDecoder.decode(fileRef, "UTF-8");
        } catch (Exception ignored) {
        }

        // 如果包含路径分隔符，直接使用
        if (fileRef.contains("/") || fileRef.contains("\\")) {
            return fileRef;
        }

        // 相对于 .sysmlx 文件所在目录
        if (sysmlBasePath != null) {
            int lastSlash = sysmlBasePath.replace('\\', '/').lastIndexOf('/');
            if (lastSlash >= 0) {
                return sysmlBasePath.substring(0, lastSlash + 1) + fileRef;
            }
        }
        return fileRef;
    }

    /**
     * 从 .sysml 源文件中查找 "calc calcName : TypeName" 声明，提取 TypeName
     * 用正则匹配: calc\s+calcName\s*:\s*(\w+)
     */
    static String extractCalcTypeFromSysml(String sysmlFilePath, String calcDeclaredName) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(sysmlFilePath))) {
            String line;
            // 匹配 "calc acc : Acceleration" 模式
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "calc\\s+" + java.util.regex.Pattern.quote(calcDeclaredName) + "\\s*:\\s*(\\w+)");
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] 无法读取 .sysml 文件: " + sysmlFilePath + " (" + e.getMessage() + ")");
        }
        return "";
    }

    /**
     * 解析 FeatureValue 中的 FeatureChainExpression, 提取源 Action 和输出参数名。
     * 例如 in charge = monitor.batteryCharge → 返回 ["monitor_id", "batteryCharge"]
     * 使用 eContents() 遍历 (标准 EMF API, 非字符串匹配)
     */
    static String[] resolveFeatureChainSource(EObject featureValue) {
        String sourceActionId = null;
        String sourceOutputName = null;

        // 递归搜索: 收集所有 Membership.memberElement 引用
        // 第一个是源 Action, 最后一个是输出参数 (链: action.output)
        java.util.List<String> membershipIds = new java.util.ArrayList<>();
        java.util.List<String> membershipNames = new java.util.ArrayList<>();
        java.util.Iterator<EObject> it = featureValue.eAllContents();
        while (it.hasNext()) {
            EObject child = it.next();
            String cn = child.eClass().getName();

            if (cn.equals("Membership")) {
                try {
                    var meFeat = child.eClass().getEStructuralFeature("memberElement");
                    if (meFeat != null) {
                        Object val = child.eGet(meFeat);
                        if (val instanceof EObject) {
                            EObject resolved = EcoreUtil.resolve((EObject) val, child);
                            if (resolved instanceof org.omg.sysml.lang.sysml.Element) {
                                String refId = ((org.omg.sysml.lang.sysml.Element) resolved).getElementId();
                                String refName = ((org.omg.sysml.lang.sysml.Element) resolved).getDeclaredName();
                                membershipIds.add(refId);
                                membershipNames.add(refName != null ? refName : "");
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // Feature/ReferenceUsage with declaredName: 链路径节点
            if (child instanceof org.omg.sysml.lang.sysml.Element) {
                String dn = ((org.omg.sysml.lang.sysml.Element) child).getDeclaredName();
                if (dn != null && !dn.isEmpty()) {
                    String childClass = child.eClass().getName();
                    if ((childClass.contains("Usage") || childClass.contains("Feature"))
                        && !childClass.contains("Expression") && !childClass.contains("Membership")) {
                        sourceOutputName = dn;
                    }
                }
            }
        }

        // 从 membershipIds 确定: 第一个 = 源 Action, 最后一个 = 输出参数
        if (!membershipIds.isEmpty()) {
            sourceActionId = membershipIds.get(0);
            if (membershipIds.size() >= 2) {
                // 最后一个 memberElement 是输出参数引用
                String lastName = membershipNames.get(membershipNames.size() - 1);
                if (!lastName.isEmpty()) {
                    sourceOutputName = lastName;
                }
            }
        }

        // 如果 sourceOutputName 没找到, 尝试从 ReturnParameterMembership → Feature(direction=out) 获取
        if (sourceOutputName == null) {
            it = featureValue.eAllContents();
            while (it.hasNext()) {
                EObject child = it.next();
                if (child.eClass().getName().equals("ReturnParameterMembership")) {
                    for (EObject gc : child.eContents()) {
                        if (gc instanceof org.omg.sysml.lang.sysml.Element) {
                            String dir = MainRunner.getFeatureString(gc, "direction");
                            if ("out".equals(dir)) {
                                // 这个 out Feature 的 container 链中的 Feature 有 declaredName
                                EObject parent = gc.eContainer();
                                while (parent != null && parent != featureValue) {
                                    if (parent instanceof org.omg.sysml.lang.sysml.Element) {
                                        String pn = ((org.omg.sysml.lang.sysml.Element) parent).getDeclaredName();
                                        if (pn != null && !pn.isEmpty()) {
                                            sourceOutputName = pn;
                                            break;
                                        }
                                    }
                                    parent = parent.eContainer();
                                }
                            }
                        }
                    }
                }
            }
        }

        if (sourceActionId != null) {
            return new String[]{sourceActionId, sourceOutputName != null ? sourceOutputName : ""};
        }
        return null;
    }
}
