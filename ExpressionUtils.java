package sysml2uml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

class ExpressionUtils {

    static String mapOperator(String opName) {
        if (opName == null) return "";
        switch (opName) {
            case "Add": return "+";
            case "Mul": return "*";
            case "Sub": return "-";
            case "Div": return "/";
            case "LessThan": return "<";
            case "GreaterThan": return ">";
            case "Equals": case "Equal": return "==";
            case "Assign": return "=";
            case "Not": return "!";
            default: return opName;
        }
    }

    static String extractName(EObject obj) {
        try {
            String n = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        } catch (Exception ignored) {
        }
        try {
            var litFeat = obj.eClass().getEStructuralFeature("literalExpression");
            if (litFeat != null) {
                Object val = obj.eGet(litFeat);
                if (val instanceof String) {
                    return (String) val;
                }
                if (val != null) {
                    return val.toString();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            var litFeat2 = obj.eClass().getEStructuralFeature("literal");
            if (litFeat2 != null) {
                Object val = obj.eGet(litFeat2);
                if (val != null) {
                    return val.toString();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            var ivFeat = obj.eClass().getEStructuralFeature("integerValue");
            if (ivFeat != null) {
                Object val = obj.eGet(ivFeat);
                if (val != null) {
                    return val.toString();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            var valFeat = obj.eClass().getEStructuralFeature("value");
            if (valFeat != null) {
                Object val = obj.eGet(valFeat);
                if (val != null) {
                    return val.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static String buildExpressionText(EObject opExpr) {
        // 处理 null 情况
        if (opExpr == null) return "";
        
        String op = mapOperator(getFeatureString(opExpr, "operator"));
        
        // FeatureReferenceExpression: 直接返回 referent 名称
        if (op.isEmpty()) {
            try {
                var refFeat = opExpr.eClass().getEStructuralFeature("referent");
                if (refFeat != null) {
                    Object ref = opExpr.eGet(refFeat);
                    if (ref instanceof EObject) {
                        String n = extractName((EObject) ref);
                        if (!n.isEmpty()) {
                            return n;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        
        // FeatureChainExpression: 构建特征链 (如 battery.charge)
        String className = opExpr.eClass().getName();
        if (className.contains("FeatureChain")) {
            return extractFeatureChain(opExpr);
        }
        
        // Membership fallback for FeatureReferenceExpression: 从子 Membership 的 memberElement 解析引用名称
        // 当 EMF referent 无法解析时使用 (XMI 中 FeatureReferenceExpression 使用 Membership(memberElement=...) 而非直接 referent)
        if (className.contains("FeatureReference")) {
            for (EObject child : opExpr.eContents()) {
                String childCn = child.eClass().getName();
                if (childCn.equals("Membership")) {
                    String name = resolveMemberElementName(child);
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
            // Fallback: 在 resource 中按 ID 查找 memberElement 引用的元素
            try {
                for (EObject child : opExpr.eContents()) {
                    if (child.eClass().getName().equals("Membership")) {
                        var meFeat = child.eClass().getEStructuralFeature("memberElement");
                        if (meFeat != null) {
                            Object meVal = child.eGet(meFeat);
                            if (meVal instanceof EObject) {
                                EObject resolved = (EObject) meVal;
                                if (resolved.eIsProxy()) {
                                    resolved = EcoreUtil.resolve(resolved, child.eResource());
                                }
                                String n = extractName(resolved);
                                if (!n.isEmpty()) {
                                    return n;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        
        // ===== 关键修复: 按 ParameterMembership 的文档顺序收集操作数 =====
        // SysML XMI 中, OperatorExpression 的 ownedRelationship 列表中的
        // ParameterMembership 子元素按文档顺序排列: 第1个是左操作数, 第2个是右操作数
        List<String> parts = new ArrayList<>();
        
        // 方法1 (优先): 通过 ownedRelationship → ParameterMembership 按顺序提取
        // 必须保持 ParameterMembership 在 ownedRelationship 列表中的原始顺序
        java.util.List<EObject> orderedParamMemberships = new java.util.ArrayList<>();
        try {
            var orFeat = opExpr.eClass().getEStructuralFeature("ownedRelationship");
            if (orFeat != null) {
                Object orVal = opExpr.eGet(orFeat);
                if (orVal instanceof List) {
                    for (Object item : (List<?>) orVal) {
                        if (!(item instanceof EObject)) {
                            continue;
                        }
                        String cn = ((EObject) item).eClass().getName();
                        if (cn.contains("ParameterMembership") && !cn.contains("Return")) {
                            orderedParamMemberships.add((EObject) item);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        
        // 按文档顺序从 ParameterMembership 中提取操作数
        for (EObject pm : orderedParamMemberships) {
            String operandText = extractOperandFromParameterMembership(pm);
            if (operandText != null && !operandText.isEmpty()) {
                parts.add(operandText);
            }
        }
        
        // 方法2 (fallback): 通过 eContents 直接查找 operand 子节点
        if (parts.isEmpty()) {
            for (EObject child : opExpr.eContents()) {
                String cn = child.eClass().getName();
                if (cn.contains("ParameterMembership") && !cn.contains("Return")) {
                    // 递归处理 ParameterMembership
                    for (EObject gc : child.eContents()) {
                        String cn2 = gc.eClass().getName();
                        if (cn2.contains("FeatureReference")) {
                            try {
                                var refFeat = gc.eClass().getEStructuralFeature("referent");
                                if (refFeat != null) {
                                    Object ref = gc.eGet(refFeat);
                                    if (ref instanceof EObject) {
                                        String n = extractName((EObject) ref);
                                        if (!n.isEmpty()) {
                                            parts.add(n);
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        } else if (cn2.contains("FeatureChain")) {
                            String chain = extractFeatureChain(gc);
                            if (!chain.isEmpty()) {
                                parts.add(chain);
                                break;
                            }
                        } else if (cn2.contains("OperatorExpression")) {
                            String inner = buildExpressionText(gc);
                            if (!inner.isEmpty()) {
                                parts.add(inner);
                                break;
                            }
                        } else {
                            String n = extractName(gc);
                            if (!n.isEmpty()) {
                                parts.add(n);
                                break;
                            }
                        }
                    }
                } else if (cn.contains("FeatureReference")) {
                    try {
                        var refFeat = child.eClass().getEStructuralFeature("referent");
                        if (refFeat != null) {
                            Object ref = child.eGet(refFeat);
                            if (ref instanceof EObject) {
                                String n = extractName((EObject) ref);
                                if (!n.isEmpty()) {
                                    parts.add(n);
                                    continue;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    for (EObject gc : child.eContents()) {
                        String n = extractName(gc);
                        if (!n.isEmpty()) {
                            parts.add(n);
                            break;
                        }
                    }
                } else if (cn.contains("FeatureChain")) {
                    String chain = extractFeatureChain(child);
                    if (!chain.isEmpty()) {
                        parts.add(chain);
                    }
                } else if (cn.contains("Literal")) {
                    String lit = extractName(child);
                    if (!lit.isEmpty()) {
                        parts.add(lit);
                    }
                } else if (cn.contains("OperatorExpression")) {
                    String innerText = buildExpressionText(child);
                    if (!innerText.isEmpty()) {
                        parts.add(innerText);
                    }
                }
            }
        }
        
        if (parts.isEmpty()) return "";
        if (op.isEmpty()) return String.join(" ", parts);
        
        // 中缀操作符: parts[0] op parts[1]
        if (parts.size() >= 2) {
            return parts.get(0) + " " + op + " " + parts.get(1);
        }
        return parts.get(0) + " " + op;
    }
    
    /**
     * 从 ParameterMembership 中提取操作数文本
     * 
     * XML 结构:
     * ParameterMembership
     *   → ownedRelatedElement (Feature)
     *     → ownedRelationship (FeatureValue)
     *       → ownedRelatedElement (FeatureChainExpression / LiteralInteger / FeatureReferenceExpression / OperatorExpression)
     * 
     * 也可以通过 EMF 反射:
     * ParameterMembership
     *   → ownedRelatedElement (Feature)
     *     → ownedRelationship (FeatureValue)
     *       → ownedRelatedElement (表达式)
     */
    static String extractOperandFromParameterMembership(EObject pm) {
        // 方法1: 按照实际 XML 结构递归查找
        // PM → Feature → FeatureValue → 表达式
        try {
            for (EObject feature : pm.eContents()) {
                String featureClass = feature.eClass().getName();
                // 可能是 Feature (ownedRelatedElement)
                for (EObject fv : feature.eContents()) {
                    String fvClass = fv.eClass().getName();
                    // 可能是 FeatureValue (ownedRelationship)
                    if (fvClass.contains("FeatureValue") || fvClass.contains("Value")) {
                        for (EObject expr : fv.eContents()) {
                            String exprResult = tryExtractExpressionText(expr);
                            if (exprResult != null && !exprResult.isEmpty()) {
                                return exprResult;
                            }
                        }
                    }
                    // 也可能是直接嵌套的表达式
                    if (!fvClass.contains("ParameterMembership") && !fvClass.contains("Return")) {
                        String exprResult = tryExtractExpressionText(fv);
                        if (exprResult != null && !exprResult.isEmpty()) {
                            return exprResult;
                        }
                    }
                }
                // Feature 也可能直接包含表达式（不经过 FeatureValue）
                if (!featureClass.contains("ParameterMembership") && !featureClass.contains("Return")) {
                    String exprResult = tryExtractExpressionText(feature);
                    if (exprResult != null && !exprResult.isEmpty()) {
                        return exprResult;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        
        // 方法2: 旧路径 ownedMemberFeature → member
        try {
            var omfFeat = pm.eClass().getEStructuralFeature("ownedMemberFeature");
            if (omfFeat != null) {
                Object omf = pm.eGet(omfFeat);
                if (omf instanceof EObject) {
                    EObject omfEo = (EObject) omf;
                    var mFeat = omfEo.eClass().getEStructuralFeature("member");
                    if (mFeat != null) {
                        Object mem = omfEo.eGet(mFeat);
                        if (mem instanceof List) {
                            for (Object m : (List<?>) mem) {
                                if (m instanceof EObject) {
                                    String txt = buildExpressionText((EObject) m);
                                    if (txt.isEmpty()) txt = extractName((EObject) m);
                                    if (!txt.isEmpty()) return txt;
                                }
                            }
                        } else if (mem instanceof EObject) {
                            String txt = buildExpressionText((EObject) mem);
                            if (txt.isEmpty()) txt = extractName((EObject) mem);
                            if (!txt.isEmpty()) return txt;
                        }
                    }
                    // 也尝试 eContents
                    for (EObject c : omfEo.eContents()) {
                        String exprResult = tryExtractExpressionText(c);
                        if (exprResult != null && !exprResult.isEmpty()) return exprResult;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        
        // 方法3: 递归深度搜索 PM 下所有子元素，找第一个可识别的表达式
        for (java.util.Iterator<EObject> it = pm.eAllContents(); it.hasNext(); ) {
            EObject child = it.next();
            String cn = child.eClass().getName();
            if (cn.contains("FeatureChain")) {
                String chain = extractFeatureChain(child);
                if (!chain.isEmpty()) return chain;
            } else if (cn.contains("FeatureReference")) {
                try {
                    var refFeat = child.eClass().getEStructuralFeature("referent");
                    if (refFeat != null) {
                        Object ref = child.eGet(refFeat);
                        if (ref instanceof EObject) {
                            String n = extractName((EObject) ref);
                            if (!n.isEmpty()) return n;
                        }
                    }
                } catch (Exception ignored) {
                }
            } else if (cn.contains("Literal")) {
                String lit = extractName(child);
                if (!lit.isEmpty()) return lit;
                // 也尝试 value 属性
                try {
                    var valFeat = child.eClass().getEStructuralFeature("value");
                    if (valFeat != null) {
                        Object val = child.eGet(valFeat);
                        if (val != null && !val.toString().isEmpty()) return val.toString();
                    }
                } catch (Exception ignored) {
                }
            } else if (cn.contains("OperatorExpression")) {
                String inner = buildExpressionText(child);
                if (!inner.isEmpty()) return inner;
            }
        }
        
        return null;
    }
    
    /**
     * 尝试从一个 EObject 中提取表达式文本
     */
    static String tryExtractExpressionText(EObject obj) {
        String cn = obj.eClass().getName();
        if (cn.contains("FeatureChain")) {
            return extractFeatureChain(obj);
        } else if (cn.contains("FeatureReference")) {
            try {
                var refFeat = obj.eClass().getEStructuralFeature("referent");
                if (refFeat != null) {
                    Object ref = obj.eGet(refFeat);
                    if (ref instanceof EObject) {
                        return extractName((EObject) ref);
                    }
                }
            } catch (Exception ignored) {
            }
        } else if (cn.contains("Literal")) {
            String lit = extractName(obj);
            if (!lit.isEmpty()) {
                return lit;
            }
            try {
                var valFeat = obj.eClass().getEStructuralFeature("value");
                if (valFeat != null) {
                    Object val = obj.eGet(valFeat);
                    if (val != null && !val.toString().isEmpty()) {
                        return val.toString();
                    }
                }
            } catch (Exception ignored) {
            }
        } else if (cn.contains("OperatorExpression")) {
            return buildExpressionText(obj);
        } else if (cn.contains("Feature") && !cn.contains("Membership") && !cn.contains("Value")) {
            // 可能是 Feature 容器，递归找表达式
            for (EObject child : obj.eContents()) {
                String result = tryExtractExpressionText(child);
                if (result != null && !result.isEmpty()) return result;
            }
        }
        return null;
    }

    // 新增辅助方法: 提取特征链表达式 (如 monitor.charge)
    static String extractFeatureChain(EObject chainExpr) {
        StringBuilder sb = new StringBuilder();
        
        // 方法0 (最可靠): 从 Membership.memberElement 提取 (直接解析 XMI 交叉引用)
        // FeatureChainExpression 结构:
        //   → ParameterMembership → Feature → FeatureValue → FeatureReferenceExpression
        //     → Membership(memberElement=<base feature>)    ← 链的起点 (如 monitor)
        //   → Membership(memberElement=<chained feature>)   ← 链的后续 (如 batteryCharge)
        try {
            List<String> chainNames = new ArrayList<>();
            // 先找内层 FeatureReferenceExpression 的 Membership (链的第一个元素)
            for (java.util.Iterator<EObject> it = chainExpr.eAllContents(); it.hasNext(); ) {
                EObject desc = it.next();
                if ("FeatureReferenceExpression".equals(desc.eClass().getName())) {
                    for (EObject freChild : desc.eContents()) {
                        if ("Membership".equals(freChild.eClass().getName())) {
                            String name = resolveMemberElementName(freChild);
                            if (!name.isEmpty() && !chainNames.contains(name)) {
                                chainNames.add(name);
                            }
                        }
                    }
                    break; // 只处理第一个 FeatureReferenceExpression
                }
            }
            // 再找 FeatureChainExpression 直接的 Membership (链的后续元素)
            for (EObject child : chainExpr.eContents()) {
                if ("Membership".equals(child.eClass().getName())
                    && !"ReturnParameterMembership".equals(child.eClass().getName())) {
                    String name = resolveMemberElementName(child);
                    if (!name.isEmpty() && !chainNames.contains(name)) {
                        chainNames.add(name);
                    }
                }
            }
            if (!chainNames.isEmpty()) {
                return String.join(".", chainNames);
            }
        } catch (Exception ignored) {
        }
        
        // 方法1: 通过 "feature" 属性提取 (EMF 解析后可能是 FeatureChaining 列表)
        try {
            var featFeat = chainExpr.eClass().getEStructuralFeature("feature");
            if (featFeat != null) {
                Object featVal = chainExpr.eGet(featFeat);
                if (featVal instanceof List) {
                    for (Object f : (List<?>) featVal) {
                        if (f instanceof EObject) {
                            String n = extractName((EObject) f);
                            if (!n.isEmpty()) {
                                if (sb.length() > 0) sb.append(".");
                                sb.append(n);
                            }
                        }
                    }
                } else if (featVal instanceof EObject) {
                    String n = extractName((EObject) featVal);
                    if (!n.isEmpty()) sb.append(n);
                }
            }
        } catch (Exception ignored) {
        }
        if (sb.length() > 0) return sb.toString();
        
        // 方法2: 通过 "chainingFeature" 属性提取 (FeatureChainExpression 的 XMI 特有属性)
        // XMI 中 chainingFeature 元素引用了链中每个 feature
        try {
            var chainFeat = chainExpr.eClass().getEStructuralFeature("chainingFeature");
            if (chainFeat != null) {
                Object chainVal = chainExpr.eGet(chainFeat);
                List<String> chainNames = new ArrayList<>();
                if (chainVal instanceof List) {
                    for (Object cf : (List<?>) chainVal) {
                        if (cf instanceof EObject) {
                            String n = extractName((EObject) cf);
                            if (!n.isEmpty()) chainNames.add(n);
                        }
                    }
                } else if (chainVal instanceof EObject) {
                    String n = extractName((EObject) chainVal);
                    if (!n.isEmpty()) chainNames.add(n);
                }
                if (!chainNames.isEmpty()) {
                    return String.join(".", chainNames);
                }
            }
        } catch (Exception ignored) {
        }
        
        // 方法3: 通过 ownedRelationship → chainingFeature 递归提取
        // XMI 结构: FeatureChainExpression → ownedRelationship → ownedRelatedElement → ownedRelationship → chainingFeature
        try {
            List<String> chainNames = new ArrayList<>();
            for (EObject child : chainExpr.eContents()) {
                // 查找 memberElement (链的第一个 feature)
                for (EObject gc : child.eContents()) {
                    String gcClass = gc.eClass().getName();
                    if (gcClass.contains("memberElement") || gcClass.contains("MemberElement")) {
                        // memberElement 通过 href 引用 feature, EMF 解析后可能可直接提取名称
                        String n = extractName(gc);
                        if (!n.isEmpty() && !chainNames.contains(n)) chainNames.add(n);
                    }
                }
                // 查找 chainingFeature (链的后续 features)
                for (EObject gc : child.eContents()) {
                    // 递归查找 chainingFeature
                    List<String> subNames = extractChainingFeatureNames(gc);
                    for (String sn : subNames) {
                        if (!chainNames.contains(sn)) chainNames.add(sn);
                    }
                }
            }
            if (!chainNames.isEmpty()) {
                return String.join(".", chainNames);
            }
        } catch (Exception ignored) {
        }
        
        // 方法4: 遍历 eContents 深度搜索所有可命名的 Feature 引用
        try {
            List<String> chainNames = new ArrayList<>();
            for (java.util.Iterator<EObject> it = chainExpr.eAllContents(); it.hasNext(); ) {
                EObject desc = it.next();
                String cn = desc.eClass().getName();
                // chainingFeature 是链中的 feature 引用
                if (cn.contains("chainingFeature") || cn.contains("ChainingFeature")) {
                    String n = extractName(desc);
                    if (!n.isEmpty() && !chainNames.contains(n)) chainNames.add(n);
                }
                // memberElement 是链的第一个 feature 引用
                if (cn.contains("memberElement") || cn.contains("MemberElement")) {
                    String n = extractName(desc);
                    if (!n.isEmpty() && !chainNames.contains(n)) chainNames.add(n);
                }
            }
            if (!chainNames.isEmpty()) {
                return String.join(".", chainNames);
            }
        } catch (Exception ignored) {
        }
        
        // 方法5: 旧 fallback - 遍历 eContents 提取名称
        for (EObject child : chainExpr.eContents()) {
            String n = extractName(child);
            if (!n.isEmpty()) {
                if (sb.length() > 0) sb.append(".");
                sb.append(n);
            }
        }
        if (sb.length() > 0) return sb.toString();
        
        // 方法6: 最终 fallback - 从 eContents 的 eContents 递归查找
        for (java.util.Iterator<EObject> it = chainExpr.eAllContents(); it.hasNext(); ) {
            EObject desc = it.next();
            String n = extractName(desc);
            if (!n.isEmpty()) return n;
        }
        
        return "";
    }
    
    /**
     * 从 ownedRelatedElement 递归提取 chainingFeature 名称
     */
    static List<String> extractChainingFeatureNames(EObject elem) {
        List<String> names = new ArrayList<>();
        String cn = elem.eClass().getName();
        // 如果是 chainingFeature, 尝试提取名称
        if (cn.contains("chainingFeature") || cn.contains("ChainingFeature")) {
            String n = extractName(elem);
            if (!n.isEmpty()) names.add(n);
        }
        // 递归子元素
        for (EObject child : elem.eContents()) {
            names.addAll(extractChainingFeatureNames(child));
        }
        return names;
    }

    /**
     * 从 Membership 元素的 memberElement 属性解析引用对象的名称
     * 支持 EMF 代理对象解析 (EcoreUtil.resolve)
     */
    static String resolveMemberElementName(EObject membershipObj) {
        try {
            var meFeat = membershipObj.eClass().getEStructuralFeature("memberElement");
            if (meFeat != null) {
                Object meVal = membershipObj.eGet(meFeat);
                if (meVal instanceof EObject) {
                    EObject resolved = (EObject) meVal;
                    if (resolved.eIsProxy()) {
                        resolved = EcoreUtil.resolve(resolved, membershipObj.eResource());
                    }
                    if (resolved instanceof org.omg.sysml.lang.sysml.Element) {
                        String n = ((org.omg.sysml.lang.sysml.Element) resolved).getDeclaredName();
                        if (n != null && !n.isEmpty()) return n;
                    }
                    // Fallback: 尝试 extractName
                    String n = extractName(resolved);
                    if (!n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static String extractAssignmentText(EObject assignAction) {
        // 如果传入的是普通 ActionUsage，搜索其内部嵌套的 AssignmentActionUsage 并委托
        String assignActionClass = assignAction.eClass().getName();
        if (!assignActionClass.contains("AssignmentActionUsage")) {
            for (EObject child : assignAction.eContents()) {
                if (child.eClass().getName().contains("AssignmentActionUsage")) {
                    return extractAssignmentText(child);
                }
                // FeatureMembership → AssignmentActionUsage (common nesting pattern)
                for (EObject grandchild : child.eContents()) {
                    if (grandchild.eClass().getName().contains("AssignmentActionUsage")) {
                        return extractAssignmentText(grandchild);
                    }
                }
            }
        }

        // LHS: endFeature 引用
        String lhs = "";
        StringBuilder debugInfo = new StringBuilder();
        debugInfo.append("extractAssignmentText() for ").append(assignAction.eClass().getName()).append("\n");
        
        try {
            var feat = assignAction.eClass().getEStructuralFeature("endFeature");
            if (feat != null) {
                Object val = assignAction.eGet(feat);
                if (val instanceof EObject) {
                    lhs = extractName((EObject) val);
                    debugInfo.append("  LHS from endFeature: ").append(lhs).append("\n");
                } else if (val instanceof List) {
                    for (Object e : (List<?>) val) {
                        if (e instanceof EObject) {
                            String n = extractName((EObject) e);
                            if (!n.isEmpty()) {
                                lhs = n;
                                break;
                            }
                        }
                    }
                    debugInfo.append("  LHS from endFeature (List): ").append(lhs).append("\n");
                }
            }
        } catch (Exception ignored) {
        }
        
        if (lhs.isEmpty()) {
            // fallback: 直接使用 referent (AssignmentActionUsage)
            try {
                var refFeat = assignAction.eClass().getEStructuralFeature("referent");
                if (refFeat != null) {
                    Object ref = assignAction.eGet(refFeat);
                    if (ref instanceof EObject) {
                        lhs = extractName((EObject) ref);
                        debugInfo.append("  LHS from referent: ").append(lhs).append("\n");
                    }
                }
            } catch (Exception ignored) {
            }
        }
        
        if (lhs.isEmpty()) {
            // fallback: 从 ReferenceSubsetting 获取
            try {
                for (EObject child : assignAction.eContents()) {
                    if (child.eClass().getName().contains("ReferenceSubsetting")) {
                        var refFeat = child.eClass().getEStructuralFeature("referencedFeature");
                        if (refFeat != null) {
                            Object ref = child.eGet(refFeat);
                            if (ref instanceof EObject) {
                                lhs = extractName((EObject) ref);
                                if (!lhs.isEmpty()) {
                                    debugInfo.append("  LHS from ReferenceSubsetting: ").append(lhs).append("\n");
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        
        // RHS: 优先使用 valueExpression (直接指向 OperatorExpression)
        String rhs = "";
        try {
            var veFeat = assignAction.eClass().getEStructuralFeature("valueExpression");
            if (veFeat != null) {
                Object ve = assignAction.eGet(veFeat);
                if (ve instanceof EObject) {
                    String veText = buildExpressionText((EObject) ve);
                    if (!veText.isEmpty()) {
                        rhs = veText;
                        debugInfo.append("  RHS from valueExpression: ").append(rhs).append("\n");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        
        if (rhs.isEmpty()) {
            // fallback: FeatureValue → OperatorExpression/Literal
            for (EObject child : assignAction.eContents()) {
                if (!child.eClass().getName().contains("FeatureValue")) continue;
                
                // 尝试从 FeatureValue 的子元素提取
                for (EObject val : child.eContents()) {
                    String cn = val.eClass().getName();
                    if (cn.contains("OperatorExpression")) {
                        rhs = buildExpressionText(val);
                        if (rhs.isEmpty()) rhs = extractName(val);
                    } else if (cn.contains("FeatureChain")) {
                        rhs = extractFeatureChain(val);
                    } else if (cn.contains("Literal")) {
                        rhs = extractName(val);
                    } else if (cn.contains("FeatureReference")) {
                        // 获取 referent 的名称
                        try {
                            var refFeat = val.eClass().getEStructuralFeature("referent");
                            if (refFeat != null) {
                                Object ref = val.eGet(refFeat);
                                if (ref instanceof EObject) {
                                    rhs = extractName((EObject) ref);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    
                    if (!rhs.isEmpty()) {
                        debugInfo.append("  RHS from FeatureValue child: ").append(rhs).append("\n");
                        break;
                    }
                }
                
                if (!rhs.isEmpty()) {
                    break;
                }
            }
        }
        
        if (rhs.isEmpty()) {
            // fallback: ParameterMembership → ownedMemberFeature → FeatureValue → Literal
            for (EObject pm : assignAction.eContents()) {
                if (!pm.eClass().getName().contains("ParameterMembership")) continue;
                
                var omfFeat = pm.eClass().getEStructuralFeature("ownedMemberFeature");
                if (omfFeat == null) continue;
                
                Object omf = pm.eGet(omfFeat);
                if (!(omf instanceof EObject)) continue;
                
                for (EObject fv : ((EObject) omf).eContents()) {
                    if (!fv.eClass().getName().contains("FeatureValue")) continue;
                    
                    var valFeat = fv.eClass().getEStructuralFeature("value");
                    if (valFeat == null) continue;
                    
                    Object val = fv.eGet(valFeat);
                    if (val instanceof EObject) {
                        String t = buildExpressionText((EObject) val);
                        if (t.isEmpty()) t = extractName((EObject) val);
                        if (!t.isEmpty()) { 
                            rhs = t; 
                            debugInfo.append("  RHS from ParameterMembership: ").append(rhs).append("\n");
                            break; 
                        }
                    }
                }
                
                if (!rhs.isEmpty()) break;
            }
        }
        
        // 输出调试信息
        if (lhs.isEmpty() || rhs.isEmpty()) {
            debugInfo.append("  WARNING: lhs=").append(lhs).append(", rhs=").append(rhs).append("\n");
            System.out.println("[DEBUG] " + debugInfo.toString());
        }
        
        if (!lhs.isEmpty() && !rhs.isEmpty()) return lhs + " = " + rhs;
        if (!lhs.isEmpty()) return lhs + " = ?";
        if (!rhs.isEmpty()) return "? = " + rhs;
        return "";
    }

    static String getFeatureString(EObject obj, String featureName) {
        try {
            var feat = obj.eClass().getEStructuralFeature(featureName);
            if (feat != null) {
                Object val = obj.eGet(feat);
                if (val instanceof String) return (String) val;
                if (val != null) return val.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 验证表达式文本是否完整 (不是 "100 <" 这种缺少左操作数的不完整形式)
     * 完整的中缀表达式应形如 "a < b", "a >= b" 等
     * 不完整的表达式形如 "100 <", "100 >=" (以操作符结尾)
     */
    static boolean isValidExpression(String expr) {
        if (expr == null || expr.isEmpty()) return false;
        // 中缀操作符: <, >, <=, >=, ==, =, +, -, *, /
        // 如果表达式以操作符结尾, 说明缺少操作数
        String trimmed = expr.trim();
        if (trimmed.endsWith("<") || trimmed.endsWith(">") || trimmed.endsWith("=") ||
            trimmed.endsWith("+") || trimmed.endsWith("-") || trimmed.endsWith("*") || trimmed.endsWith("/")) {
            return false;
        }
        // 如果表达式以操作符开头 (不是负号), 也可能不完整
        // 但允许 "!" 前缀 (逻辑非)
        // 如果只有一个操作数和一个操作符 (形如 "100 <"), 说明不完整
        // 检查: 中缀表达式应该至少有 3 个 token (operand operator operand)
        String[] tokens = trimmed.split("\\s+");
        boolean hasOperator = false;
        for (String t : tokens) {
            if (t.matches("[<>]=?|==|(?<!=)=(?!=)|[+\\-*/]|!=")) {
                hasOperator = true;
                break;
            }
        }
        if (hasOperator && tokens.length < 3) return false;
        return true;
    }

    static String sanitizeName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    static boolean isControlKeyword(String name) {
        return "decision".equals(name) || "merge".equals(name)
            || "fork".equals(name) || "join".equals(name);
    }

    static boolean isContainmentTree(EObject parent, Object child) {
        if (!(child instanceof EObject)) return false;
        EObject current = (EObject) child;
        while (current != null) {
            if (current == parent) return true;
            current = current.eContainer();
        }
        return false;
    }

    /** 首字母大写 */
    static String capitalizeFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
