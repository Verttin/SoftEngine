
package sysml2uml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.uml2.uml.*;

/**
 * 跨 PASS 共享的流水线上下文状态。
 * 持有原本在 executePureXMLPipeline() 方法内声明、但被多个 PASS 共用的局部变量。
 */
class PipelineContext {
    String sysmlBasePath;

    // --- EMF 资源 ---
    ResourceSet resourceSet;
    Resource resource;
    UMLFactory factory;
    Model umlModel;
    Activity activity;
    boolean isCalcModel = false;

    // --- SysML 源文本 (PASS 2/3 用到) ---
    String sysmlText;

    // --- 节点追踪 ---
    Set<String> existingNodeNames = new HashSet<>();
    String firstActionId;
    String terminateActionId;
    Set<String> typedActionIds = new HashSet<>();
    Set<String> placeholderNodeIds = new HashSet<>();
    List<String> sequentialActionIds = new ArrayList<>();

    // --- Pin 和 ObjectFlow (PASS 2 + DataFlow PASS) ---
    Map<String, Map<String, OutputPin>> actionOutputPins = new HashMap<>();
    Map<String, Map<String, InputPin>> actionInputPins = new HashMap<>();
    List<String[]> pinBindings = new ArrayList<>();
    List<String[]> bindConnectorBindings = new ArrayList<>();
    Map<String, String[]> featureIdToPinInfo = new HashMap<>();
    Map<String, EObject> actionSysmlElements = new HashMap<>();

    // --- 结构元素 ---
    List<PackageableElement> structuralElements = new ArrayList<>();
    Set<String> processedStructuralIds = new HashSet<>();

    // --- 隐式 Fork ---
    Map<String, String> implicitForks = new HashMap<>();

    // --- Decide 语法追踪 ---
    String lastDecideDecisionId;
    List<String> allDecideDecisionIds = new ArrayList<>();
    int decideTransitionCount = 0;
    List<String> sysmlIfTargets = new ArrayList<>();
    List<List<String>> decideConditionGroups = new ArrayList<>();
    int decideConditionIndex = 0;
    List<String[]> decideDeferredEdges = new ArrayList<>();
    String sysmlMergeNodeName;

    // --- Flow 延迟解析 ---
    List<String[]> deferredFlows = new ArrayList<>();
    int consumedFlowRegexCount = 0;
    List<String[]> deferredSuccessionFlows = new ArrayList<>();
    int consumedIfRegexCount = 0;

    // --- Guard / ControlFlow ---
    Map<String, String> guardMap = new HashMap<>();
    List<String[]> deferredControlFlows = new ArrayList<>();

    // --- PASS 4 输出 ---
    List<MainRunner.EdgeData> finalRoutedEdges = new ArrayList<>();
    Map<String, MergeNode> dynamicMerges = new HashMap<>();

    // --- Calc Model ---
    Map<String, String> calcIdToName = new HashMap<>();
}
