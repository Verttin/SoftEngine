package sysml2uml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.uml2.uml.*;

class UmlHelper {

    static CallBehaviorAction createCallBehaviorAction(Activity parentActivity, String nodeName) {
        return createCallBehaviorActionWithBody(parentActivity, nodeName, null, null);
    }

    /**
     * 创建 CallBehaviorAction 并关联一个带 body 的 OpaqueBehavior。
     * 等效于 OpaqueAction，但 Moka 的 ExecutionFactoryL2 原生支持 CallBehaviorAction。
     */
    static CallBehaviorAction createCallBehaviorActionWithBody(
            Activity parentActivity, String nodeName, String body, String language) {
        // 1. 在父 Activity 上创建一个 OpaqueBehavior 作为行为定义
        String behaviorName = ExpressionUtils.capitalizeFirst(nodeName) + "Behavior";
        OpaqueBehavior behavior = (OpaqueBehavior) parentActivity.createOwnedBehavior(
                behaviorName, UMLPackage.Literals.OPAQUE_BEHAVIOR);

        // 2. 设置 body（如果有）
        if (body != null && !body.isEmpty()) {
            behavior.getBodies().add(body);
            behavior.getLanguages().add(language != null ? language : "SysMLv2");
        }

        // 3. 创建 CallBehaviorAction 节点
        CallBehaviorAction cba = (CallBehaviorAction) parentActivity.createOwnedNode(
                nodeName, UMLPackage.Literals.CALL_BEHAVIOR_ACTION);

        // 4. 关联 behavior 引用
        cba.setBehavior(behavior);

        return cba;
    }

    /**
     * 创建带 sysml-assign 体的 OpaqueAction 用于赋值语句。
     * 使用 OpaqueAction (而非 CallBehaviorAction)，因为 Moka 的 OpaqueActionFactory
     * 会直接返回 OpaqueActionActivation 来处理 sysml-assign 体:
     *   1. 从 runtimeVariables 获取变量值
     *   2. 解析 sysml-assign 体 (target := expression) 并计算
     *   3. 将结果写回 runtimeVariables
     * 
     * 注意: 不添加 InputPin/OutputPin (OpaqueAction 的 pin 列表是 derived 的，不支持 add)。
     * 数据流完全通过 Moka 的 runtimeVariables (静态 Map) 实现。
     * 
     * @param parentActivity 所属活动图
     * @param nodeName       节点名称
     * @param assignExpr     赋值表达式, 格式 "lhs = rhs" (由 extractAssignmentText 返回)
     * @return 创建的 ActivityNode (OpaqueAction 或 CallBehaviorAction)
     */
    static ActivityNode createOpaqueActionForAssignment(Activity parentActivity, String nodeName, String assignExpr) {
        if (assignExpr != null && !assignExpr.isEmpty()) {
            // 创建 OpaqueAction 并设置 sysml-assign 体
            OpaqueAction oa = (OpaqueAction) parentActivity.createOwnedNode(
                    nodeName, UMLPackage.Literals.OPAQUE_ACTION);
            // 将 "=" 格式转为 ":=" 格式供 Moka sysml-assign 解析
            String sysmlAssignBody = assignExpr.replaceFirst("\\s*=\\s*", " := ");
            oa.getBodies().add(sysmlAssignBody);
            oa.getLanguages().add("sysml-assign");
            return oa;
        }
        return createCallBehaviorActionWithBody(parentActivity, nodeName, null, null);
    }
    
    /**
     * 从表达式文本中提取变量名 (标识符), 排除纯数字。
     * 例如: "i - 1" → ["i"], "i * n" → ["i", "n"], "a + b * c" → ["a", "b", "c"]
     */
    static List<String> extractVariableNames(String expr) {
        List<String> vars = new ArrayList<>();
        if (expr == null || expr.isEmpty()) return vars;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-zA-Z_]\\w*").matcher(expr);
        while (m.find()) {
            String token = m.group();
            // 排除纯数字 (不能匹配到, 但防御性检查)
            if (!token.matches("\\d+")) {
                vars.add(token);
            }
        }
        return vars;
    }
}
