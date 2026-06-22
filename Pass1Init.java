package sysml2uml;

import java.util.*;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.uml2.uml.*;

/**
 * PASS 1: 画布初始化与变量解析
 * PASS 1.5: 变量初始化扫描
 */
class Pass1Init {

    /**
     * 执行 PASS 1 (查找/创建 Activity) 和 PASS 1.5 (扫描变量初始化)。
     * <p>
     * 设置 {@code ctx.activity}, {@code ctx.isCalcModel}, {@code ctx.calcIdToName},
     * 并填充调用方传入的 {@code calcIdToType} 和 {@code globalIdToName}。
     */
    static void run(Resource resource, PipelineContext ctx,
                    Map<String, String> calcIdToType,
                    Map<String, String> globalIdToName) {

        // ===================================================================
        // PASS 1: 画布初始化与变量解析
        // ===================================================================
        var iterator = resource.getAllContents();
        while (iterator.hasNext()) {
            EObject obj = iterator.next();
            if (obj instanceof org.omg.sysml.lang.sysml.Element) {
                String name = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
                String className = obj.eClass().getName();

                if (ctx.activity == null && name != null && "Main".equals(name)) {
                    ctx.activity = (Activity) ctx.umlModel.createPackagedElement(name, UMLPackage.Literals.ACTIVITY);
                }
                // 变量/参数初始化已移至活动创建后的独立扫描 (PASS 1.5)
                if (name != null && (className.contains("ActionUsage")
                        || className.contains("ForkNode") || className.contains("JoinNode")
                        || className.equals("MergeNode") || className.equals("DecisionNode"))) {
                    ctx.existingNodeNames.add(name);
                }
            }
        }
        // 如果没有 "Main"，退而求其次找包含控制流结构的主 ActionDefinition
        if (ctx.activity == null) {
            iterator = resource.getAllContents();
            EObject bestActionDef = null;
            String bestName = null;
            // 优先级: 复合ActionDefinition(含子ActionUsage) > WhileLoopActionUsage > DecisionNode > MergeNode > 第一个 ActionDefinition
            int bestPriority = 0;
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                if (obj instanceof org.omg.sysml.lang.sysml.Element) {
                    String n = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
                    if (n != null && obj.eClass().getName().contains("ActionDefinition")) {
                        int priority = 1; // 默认优先级
                        int childActionCount = 0;
                        // 检查子节点类型来判断优先级
                        for (java.util.Iterator<EObject> it = obj.eAllContents(); it.hasNext(); ) {
                            EObject child = it.next();
                            String childClass = child.eClass().getName();
                            if (childClass.equals("WhileLoopActionUsage")) {
                                priority = Math.max(priority, 4);
                                break; // 最高优先级, 不用再找了
                            }
                            if (childClass.equals("DecisionNode")) {
                                priority = Math.max(priority, 3);
                            }
                            if (childClass.equals("MergeNode")) {
                                priority = Math.max(priority, 2);
                            }
                            if (childClass.equals("ForkNode") || childClass.equals("JoinNode")) {
                                priority = Math.max(priority, 3);
                            }
                            // 统计子 ActionUsage 数量 (复合动作检测)
                            if (childClass.contains("ActionUsage") && !childClass.equals("ActionDefinition")) {
                                childActionCount++;
                            }
                        }
                        // 复合 ActionDefinition: 包含 2+ 子 ActionUsage → 优先级最高
                        if (childActionCount >= 2) {
                            priority = Math.max(priority, 6);
                        } else if (childActionCount == 1) {
                            priority = Math.max(priority, 5);
                        }
                        if (priority > bestPriority) {
                            bestActionDef = obj;
                            bestName = n;
                            bestPriority = priority;
                        }
                    }
                    // 检测包含 CalculationUsage 的 ActionUsage (calc 模型的活动名)
                    // 以及包含子 ActionUsage 的复合 ActionUsage (action decomposition)
                    if (n != null && "ActionUsage".equals(obj.eClass().getName())) {
                        // CalculationUsage 嵌套在 FeatureMembership 内，需要深度搜索
                        org.eclipse.emf.common.util.TreeIterator<EObject> deepIter = obj.eAllContents();
                        int usageChildActionCount = 0;
                        boolean hasCalcUsage = false;
                        while (deepIter.hasNext()) {
                            EObject desc = deepIter.next();
                            if ("CalculationUsage".equals(desc.eClass().getName())) {
                                hasCalcUsage = true;
                            }
                            // Count child ActionUsages for composite action detection
                            String descCn = desc.eClass().getName();
                            if (descCn.contains("ActionUsage") && !descCn.equals("ActionUsage")) {
                                // Sub-type of ActionUsage (e.g. WhileLoopActionUsage)
                                usageChildActionCount++;
                            } else if ("ActionUsage".equals(descCn)) {
                                usageChildActionCount++;
                            }
                        }
                        if (hasCalcUsage && bestPriority < 5) {
                            bestActionDef = obj;
                            bestName = n;
                            bestPriority = 5;
                        }
                        // Composite ActionUsage: 2+ child ActionUsages → priority 6
                        // 用 < 6 而非 < 7: 防止嵌套 ActionUsage 替换同等优先级的 ActionDefinition
                        if (usageChildActionCount >= 2 && bestPriority < 6) {
                            bestActionDef = obj;
                            bestName = n;
                            bestPriority = Math.max(bestPriority, 6);
                        } else if (usageChildActionCount == 1 && bestPriority < 5) {
                            bestActionDef = obj;
                            bestName = n;
                            bestPriority = Math.max(bestPriority, 5);
                        }
                    }
                }
            }
            if (bestActionDef != null) {
                ctx.activity = (Activity) ctx.umlModel.createPackagedElement(bestName, UMLPackage.Literals.ACTIVITY);
            }
        }

        // ===== Calc Model Detection (在 DefaultActivity fallback 之前执行) =====
        boolean isCalcModel = false;
        String calcActivityId = null;

        {
            iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                if (obj instanceof org.omg.sysml.lang.sysml.Element) {
                    String eid = ((org.omg.sysml.lang.sysml.Element) obj).getElementId();
                    String dn = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
                    String cn = obj.eClass().getName();
                    if (eid != null && dn != null) {
                        globalIdToName.put(eid, dn);
                    }
                    if ("CalculationUsage".equals(cn)) {
                        isCalcModel = true;
                        if (eid != null && dn != null) {
                            ctx.calcIdToName.put(eid, dn);
                        }
                        // extract calc def type name from FeatureTyping href
                        if (eid != null) {
                            for (EObject child : obj.eContents()) {
                                if ("FeatureTyping".equals(child.eClass().getName())) {
                                    String typeName = CalcModel.resolveCalcTypeName(child, ctx.sysmlBasePath, dn);
                                    if (!typeName.isEmpty()) {
                                        calcIdToType.put(eid, typeName);
                                    }
                                }
                            }
                        }
                    }
                    // Detect ActionUsage containing CalculationUsage as the calc activity
                    if ("ActionUsage".equals(cn) && eid != null && dn != null) {
                        // CalculationUsage 嵌套在 FeatureMembership 内，需要深度搜索
                        org.eclipse.emf.common.util.TreeIterator<EObject> deepIter = obj.eAllContents();
                        while (deepIter.hasNext()) {
                            EObject desc = deepIter.next();
                            if ("CalculationUsage".equals(desc.eClass().getName())) {
                                calcActivityId = eid;
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (isCalcModel) {
            System.out.println("[DEBUG] Calc model detected. Calculations: " + ctx.calcIdToName.values());
            if (ctx.activity == null && calcActivityId != null) {
                String calcActName = globalIdToName.get(calcActivityId);
                if (calcActName != null) {
                    ctx.activity = (Activity) ctx.umlModel.createPackagedElement(calcActName, UMLPackage.Literals.ACTIVITY);
                    System.out.println("[DEBUG] Calc activity: " + calcActName);
                }
            }
            if (ctx.activity == null) {
                ctx.activity = (Activity) ctx.umlModel.createPackagedElement("CalcActivity", UMLPackage.Literals.ACTIVITY);
            }
        } else {
            if (ctx.activity == null) {
                ctx.activity = (Activity) ctx.umlModel.createPackagedElement("DefaultActivity", UMLPackage.Literals.ACTIVITY);
            }
        }

        ctx.isCalcModel = isCalcModel;

        // ===================================================================
        // PASS 1.5: 变量初始化扫描 (活动已创建, 安全处理 AttributeUsage / ReferenceUsage)
        // ===================================================================
        if (!isCalcModel && ctx.activity != null) {
            iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                if (obj instanceof org.omg.sysml.lang.sysml.Element) {
                    String varName = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
                    String varCn = obj.eClass().getName();
                    if (varName != null && varCn.contains("AttributeUsage")) {
                        System.out.println("[VAR-INIT-SCAN] AttributeUsage: " + varName);
                        Variable var = UMLFactory.eINSTANCE.createVariable();
                        var.setName(varName);
                        ctx.activity.getVariables().add(var);

                        // 提取初始值 → ownedAttribute (格式 "name=value") 供 Moka runtimeVariables
                        String initValue = null;
                        for (EObject attrChild : obj.eContents()) {
                            String attrChildCn = attrChild.eClass().getName();
                            if (attrChildCn.equals("FeatureValue")) {
                                for (EObject fvChild : attrChild.eContents()) {
                                    String fvCn = fvChild.eClass().getName();
                                    if (fvCn.equals("LiteralInteger")) {
                                        var valFeat = fvChild.eClass().getEStructuralFeature("value");
                                        if (valFeat != null) {
                                            Object val = fvChild.eGet(valFeat);
                                            initValue = val != null ? val.toString() : "0";
                                        }
                                    } else if (fvCn.equals("LiteralBoolean")) {
                                        var valFeat = fvChild.eClass().getEStructuralFeature("value");
                                        if (valFeat != null) {
                                            Object val = fvChild.eGet(valFeat);
                                            initValue = (val != null && Boolean.TRUE.equals(val)) ? "1" : "0";
                                        } else {
                                            initValue = "0";
                                        }
                                    } else if (fvCn.equals("LiteralReal") || fvCn.equals("LiteralString")) {
                                        var valFeat = fvChild.eClass().getEStructuralFeature("value");
                                        if (valFeat != null) {
                                            Object val = fvChild.eGet(valFeat);
                                            initValue = val != null ? val.toString() : "0";
                                        }
                                    }
                                }
                            }
                        }
                        if (initValue != null) {
                            org.eclipse.uml2.uml.Property prop = UMLFactory.eINSTANCE.createProperty();
                            prop.setName(varName + "=" + initValue);
                            ctx.activity.getOwnedAttributes().add(prop);
                            System.out.println("[VAR-INIT] " + varName + " = " + initValue);
                        } else {
                            // 无初始值的属性也需要作为变量注册
                            org.eclipse.uml2.uml.Property prop = UMLFactory.eINSTANCE.createProperty();
                            prop.setName(varName + "=0");
                            ctx.activity.getOwnedAttributes().add(prop);
                            System.out.println("[VAR-INIT] " + varName + " = 0 (default)");
                        }
                    }
                    // ReferenceUsage 参数检测
                    else if (varName != null && varCn.contains("ReferenceUsage")) {
                        boolean isParam = false;
                        EObject container = obj.eContainer();
                        if (container != null) {
                            String containerCn = container.eClass().getName();
                            if (containerCn.contains("FeatureMembership")) {
                                isParam = true;
                            } else {
                                for (EStructuralFeature feat : obj.eClass().getEAllStructuralFeatures()) {
                                    if (feat.getName().contains("direction") || feat.getName().contains("Direction")) {
                            try {
                                Object val = obj.eGet(feat);
                                if (val instanceof String && !((String) val).isEmpty()) {
                                    isParam = true;
                                }
                            } catch (Exception ignored) {
                                // ignored
                            }
                                    }
                                }
                            }
                        }
                        if (isParam) {
                            Variable var = UMLFactory.eINSTANCE.createVariable();
                            var.setName(varName);
                            ctx.activity.getVariables().add(var);
                            ctx.existingNodeNames.add(varName);
                            System.out.println("[VAR-INIT-SCAN] ReferenceUsage param: " + varName);
                        }
                    }
                }
            }
            System.out.println("[VAR-INIT-SCAN] Total variables: " + ctx.activity.getVariables().size()
                    + ", ownedAttributes: " + ctx.activity.getOwnedAttributes().size());
        }
    }
}
