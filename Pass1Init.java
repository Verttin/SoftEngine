
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
    static void run(Resource resource, PipelineContext ctx, Map<String, String> calcIdToType,
            Map<String, String> globalIdToName) {

        // PASS 1: 画布初始化 - 扫描 "Main" 并收集节点名
        var iterator = resource.getAllContents();
        while (iterator.hasNext()) {
            EObject obj = iterator.next();
            if (obj instanceof org.omg.sysml.lang.sysml.Element) {
                String name = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
                String className = obj.eClass().getName();

                if (ctx.activity == null && name != null && "Main".equals(name)) {
                    ctx.activity = (Activity) ctx.umlModel.createPackagedElement(name, UMLPackage.Literals.ACTIVITY);
                }
                if (name != null && (className.contains("ActionUsage") || className.contains("ForkNode")
                        || className.contains("JoinNode") || "MergeNode".equals(className)
                        || "DecisionNode".equals(className))) {
                    ctx.existingNodeNames.add(name);
                }
            }
        }

        // 如果没有 "Main"，退而求其次找包含控制流结构的主 ActionDefinition
        if (ctx.activity == null) {
            createActivityFromBestDefinition(resource, ctx);
        }

        // Calc Model Detection
        boolean isCalcModel = detectCalcModel(resource, ctx, calcIdToType, globalIdToName);

        if (isCalcModel) {
            System.out.println("[DEBUG] Calc model detected. Calculations: " + ctx.calcIdToName.values());
            if (ctx.activity == null) {
                ctx.activity = (Activity) ctx.umlModel.createPackagedElement("CalcActivity",
                        UMLPackage.Literals.ACTIVITY);
            }
        } else {
            if (ctx.activity == null) {
                ctx.activity = (Activity) ctx.umlModel.createPackagedElement("DefaultActivity",
                        UMLPackage.Literals.ACTIVITY);
            }
        }

        ctx.isCalcModel = isCalcModel;

        // PASS 1.5: 变量初始化扫描
        if (!isCalcModel && ctx.activity != null) {
            initializeVariables(resource, ctx);
        }
    }

    /** 查找最佳 ActionDefinition/ActionUsage 并创建 Activity */
    private static void createActivityFromBestDefinition(Resource resource, PipelineContext ctx) {
        var iterator = resource.getAllContents();
        EObject bestActionDef = null;
        String bestName = null;
        int bestPriority = 0;
        while (iterator.hasNext()) {
            EObject obj = iterator.next();
            if (!(obj instanceof org.omg.sysml.lang.sysml.Element))
                continue;
            String n = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
            if (n == null)
                continue;
            String cn = obj.eClass().getName();

            if (cn.contains("ActionDefinition")) {
                int priority = computeActionDefPriority(obj);
                if (priority > bestPriority) {
                    bestActionDef = obj;
                    bestName = n;
                    bestPriority = priority;
                }
            } else if ("ActionUsage".equals(cn)) {
                int priority = computeActionUsagePriority(obj);
                if (priority > bestPriority) {
                    bestActionDef = obj;
                    bestName = n;
                    bestPriority = priority;
                }
            }
        }
        if (bestActionDef != null) {
            ctx.activity = (Activity) ctx.umlModel.createPackagedElement(bestName, UMLPackage.Literals.ACTIVITY);
        }
    }

    /** 计算 ActionDefinition 的优先级 */
    private static int computeActionDefPriority(EObject obj) {
        int priority = 1;
        int childActionCount = 0;
        for (java.util.Iterator<EObject> it = obj.eAllContents(); it.hasNext();) {
            EObject child = it.next();
            String childClass = child.eClass().getName();
            if ("WhileLoopActionUsage".equals(childClass)) {
                priority = Math.max(priority, 4);
                break;
            }
            if ("DecisionNode".equals(childClass))
                priority = Math.max(priority, 3);
            if ("MergeNode".equals(childClass))
                priority = Math.max(priority, 2);
            if ("ForkNode".equals(childClass) || "JoinNode".equals(childClass))
                priority = Math.max(priority, 3);
            if (childClass.contains("ActionUsage") && !"ActionDefinition".equals(childClass))
                childActionCount++;
        }
        if (childActionCount >= 2)
            priority = Math.max(priority, 6);
        else if (childActionCount == 1)
            priority = Math.max(priority, 5);
        return priority;
    }

    /** 计算 ActionUsage 的优先级 (calc 模型 / 复合动作检测) */
    private static int computeActionUsagePriority(EObject obj) {
        org.eclipse.emf.common.util.TreeIterator<EObject> deepIter = obj.eAllContents();
        int usageChildActionCount = 0;
        boolean hasCalcUsage = false;
        while (deepIter.hasNext()) {
            EObject desc = deepIter.next();
            String descCn = desc.eClass().getName();
            if ("CalculationUsage".equals(descCn))
                hasCalcUsage = true;
            if (descCn.contains("ActionUsage"))
                usageChildActionCount++;
        }
        if (hasCalcUsage)
            return 5;
        if (usageChildActionCount >= 2)
            return 6;
        if (usageChildActionCount == 1)
            return 5;
        return 0;
    }

    /** 检测 Calc Model 并填充 calcIdToName / calcIdToType / globalIdToName */
    private static boolean detectCalcModel(Resource resource, PipelineContext ctx, Map<String, String> calcIdToType,
            Map<String, String> globalIdToName) {
        boolean isCalcModel = false;
        String calcActivityId = null;
        var iterator = resource.getAllContents();
        while (iterator.hasNext()) {
            EObject obj = iterator.next();
            if (!(obj instanceof org.omg.sysml.lang.sysml.Element))
                continue;
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
                if (eid != null) {
                    for (EObject child : obj.eContents()) {
                        if ("FeatureTyping".equals(child.eClass().getName())) {
                            String typeName = CalcModel.resolveCalcTypeName(child, ctx.sysmlBasePath, dn);
                            if (!typeName.isEmpty())
                                calcIdToType.put(eid, typeName);
                        }
                    }
                }
            }
            if ("ActionUsage".equals(cn) && eid != null && dn != null) {
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
        if (isCalcModel && ctx.activity == null && calcActivityId != null) {
            String calcActName = globalIdToName.get(calcActivityId);
            if (calcActName != null) {
                ctx.activity = (Activity) ctx.umlModel.createPackagedElement(calcActName, UMLPackage.Literals.ACTIVITY);
                System.out.println("[DEBUG] Calc activity: " + calcActName);
            }
        }
        return isCalcModel;
    }

    /** PASS 1.5: 变量初始化扫描 */
    private static void initializeVariables(Resource resource, PipelineContext ctx) {
        var iterator = resource.getAllContents();
        while (iterator.hasNext()) {
            EObject obj = iterator.next();
            if (!(obj instanceof org.omg.sysml.lang.sysml.Element))
                continue;
            String varName = ((org.omg.sysml.lang.sysml.Element) obj).getDeclaredName();
            String varCn = obj.eClass().getName();
            if (varName == null)
                continue;

            if (varCn.contains("AttributeUsage")) {
                initAttributeVariable(ctx, obj, varName);
            } else if (varCn.contains("ReferenceUsage")) {
                initReferenceParam(ctx, obj, varName);
            }
        }
        System.out.println("[VAR-INIT-SCAN] Total variables: " + ctx.activity.getVariables().size()
                + ", ownedAttributes: " + ctx.activity.getOwnedAttributes().size());
    }

    /** 初始化 AttributeUsage 变量 */
    private static void initAttributeVariable(PipelineContext ctx, EObject obj, String varName) {
        System.out.println("[VAR-INIT-SCAN] AttributeUsage: " + varName);
        Variable var = UMLFactory.eINSTANCE.createVariable();
        var.setName(varName);
        ctx.activity.getVariables().add(var);

        String initValue = extractInitValue(obj);
        org.eclipse.uml2.uml.Property prop = UMLFactory.eINSTANCE.createProperty();
        prop.setName(varName + "=" + (initValue != null ? initValue : "0"));
        ctx.activity.getOwnedAttributes().add(prop);
        System.out.println("[VAR-INIT] " + varName + " = " + (initValue != null ? initValue : "0 (default)"));
    }

    /** 从 EObject 提取变量初始值 */
    private static String extractInitValue(EObject obj) {
        for (EObject attrChild : obj.eContents()) {
            if (!"FeatureValue".equals(attrChild.eClass().getName()))
                continue;
            for (EObject fvChild : attrChild.eContents()) {
                String fvCn = fvChild.eClass().getName();
                if ("LiteralInteger".equals(fvCn) || "LiteralReal".equals(fvCn) || "LiteralString".equals(fvCn)) {
                    var valFeat = fvChild.eClass().getEStructuralFeature("value");
                    if (valFeat != null) {
                        Object val = fvChild.eGet(valFeat);
                        return val != null ? val.toString() : "0";
                    }
                } else if ("LiteralBoolean".equals(fvCn)) {
                    var valFeat = fvChild.eClass().getEStructuralFeature("value");
                    if (valFeat != null) {
                        Object val = fvChild.eGet(valFeat);
                        return (val != null && Boolean.TRUE.equals(val)) ? "1" : "0";
                    }
                    return "0";
                }
            }
        }
        return null;
    }

    /** 初始化 ReferenceUsage 参数 */
    private static void initReferenceParam(PipelineContext ctx, EObject obj, String varName) {
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
                            if (val instanceof String && !((String) val).isEmpty())
                                isParam = true;
                        } catch (Exception ignored) {
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
