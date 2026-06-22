
package sysml2uml;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.uml2.uml.Activity;
import org.eclipse.uml2.uml.ActivityNode;
import org.eclipse.uml2.uml.Model;
import org.eclipse.uml2.uml.UMLFactory;
import org.eclipse.uml2.uml.UMLPackage;
import org.eclipse.uml2.uml.resource.UMLResource;

import org.junit.Test;
import org.omg.kerml.xtext.KerMLStandaloneSetup;
import org.omg.sysml.lang.sysml.SysMLPackage;
import org.omg.sysml.xtext.SysMLStandaloneSetup;
import org.omg.sysml.xtext.xmi.SysMLxStandaloneSetup;

/**
 * SysML v2 XMI → UML Activity 模型转换主控器。
 * <p>
 * 本类仅负责：
 * <ol>
 * <li>维护跨 PASS 共享的静态状态 (umlNodes / logicalEdges / loop maps 等)</li>
 * <li>编排各 PASS 的执行顺序</li>
 * <li>提供 JUnit 测试入口和 CLI 入口</li>
 * </ol>
 * <p>
 * 各 PASS 的具体逻辑已拆分到独立的类文件中:
 * <ul>
 * <li>{@link Pass1Init} — PASS 1 + 1.5: 画布初始化 + 变量扫描</li>
 * <li>{@link Pass2Nodes} — PASS 2: 节点实例化</li>
 * <li>{@link Pass2Edges} — PASS 2b~2f: 边构建</li>
 * <li>{@link Pass3Edges} — PASS 3 / 3b / 3-ext / 2c-ext: 控制流关系提取</li>
 * <li>{@link Pass4Routing} — PASS 4: 控制流拓扑路由</li>
 * <li>{@link CalcModel} — Calc 模型 PASS C1/C2/C3</li>
 * <li>{@link Pass5Assembly} — PASS 5: 数据流 + 控制流装配 + 输出</li>
 * </ul>
 */
public class MainRunner {

    // =====================================================================
    // 跨 PASS 共享的静态状态 (由 PipelineContext 中的方法读写)
    // =====================================================================
    static Map<String, ActivityNode> umlNodes;
    static Map<String, String> uuidToNameMap;
    static Map<String, String> nameToIdMap;
    static List<EdgeData> logicalEdges;
    static Set<String> loopBodyNodeIds;
    static Map<String, String> loopStartMerge;
    static Map<String, String> loopEndDecision;
    static Map<String, String> loopConditionText;
    static Map<String, List<String>> loopBodyActions;
    static Map<String, String> whileLoopMergeIds;
    static Map<String, String> whileLoopDecisionIds;
    static Map<String, String> whileLoopCondText;
    static Map<String, Boolean> whileLoopIsUntil;
    static Map<String, String> whileLoopPostLoopActions;
    static Map<String, Boolean> whileLoopIsWhileUntil;
    static Map<String, String> whileLoopEntryIds;
    static Map<String, String> whileLoopExitMergeIds;
    static Map<String, String> whileLoopPureExitMergeIds;
    static Map<String, String> topLevelIfMergeIds;
    static Set<String> loopIsUntilIds;

    // =====================================================================
    // EdgeData — 逻辑边数据 (package-private, 供所有 Pass 类使用)
    // =====================================================================
    static class EdgeData {
        String source;
        String target;
        String guard;
        EdgeData(String s, String t) {
            source = s;
            target = t;
            this.guard = null;
        }

        EdgeData(String s, String t, String g) {
            source = s;
            target = t;
            this.guard = g;
        }
    }

    // =====================================================================
    // getFeatureString — 被 CalcModel 引用的工具方法
    // =====================================================================
    static String getFeatureString(EObject obj, String featureName) {
        if (obj == null || featureName == null) {
            return null;
        }
        for (EStructuralFeature feat : obj.eClass().getEAllStructuralFeatures()) {
            if (feat.getName().equals(featureName)) {
                try {
                    Object val = obj.eGet(feat);
                    return val != null ? val.toString() : null;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    // =====================================================================
    // JUnit 测试入口 (保留原始完整流水线: Phase 0 编译 → Phase 1 转换 → Phase 2 仿真)
    // =====================================================================
    @Test
    public void executePureXMLPipeline() {

        // 1. 你的输入文本文件 (.sysml)
        String sysmlPath = "D:\\about_computer\\software_engineering\\sysml-master\\git\\"
                + "SysML-v2-Pilot-Implementation\\org.omg.sysml.xpect.tests\\src\\sysml2uml\\StructuredControlTest.sysml";

        // 2. 路径自动推导
        int dotIndex = sysmlPath.lastIndexOf(".");
        String sysmlxPath = sysmlPath.substring(0, dotIndex) + ".sysmlx";
        String generatedUmlPath = sysmlPath.substring(0, dotIndex) + ".uml";

        // 3. Moka 引擎指定的目录与运行脚本
        String mokaTargetUmlPath = "D:\\about_computer\\software_engineering\\moka\\uml\\models\\Branch_nesting.uml";
        String mokaWorkDir = "D:\\about_computer\\software_engineering\\moka\\uml";
        String runBatPath = "D:\\about_computer\\software_engineering\\moka\\uml\\run.bat";

        System.out.println("====== 启动 [IDE原生编译 -> 解析 -> 拷贝 -> 仿真] 直通流水线 ======\n");

        try {
            // ==========================================
            // [阶段 0] 调用官方命令行生成原生 sysmlx
            // ==========================================
            System.out.println(">>> 阶段 0: 挂载官方 IDE 命令行环境编译 SysML...");

            // 从 runner.properties 读取 java.exe 路径和 phase0 classpath
            java.util.Properties props = new java.util.Properties();
            java.nio.file.Path propsPath = java.nio.file.Paths
                    .get("D:\\about_computer\\software_engineering\\sysml-master\\git\\"
                            + "SysML-v2-Pilot-Implementation\\org.omg.sysml.xpect.tests\\src\\sysml2uml\\runner.properties");
            try (java.io.InputStream is = java.nio.file.Files.newInputStream(propsPath)) {
                props.load(is);
            }
            String javaExe = props.getProperty("java.exe").replace("\\\\", "\\");
            String classPath = props.getProperty("phase0.cp").replace("\\\\", "\\");
            String libBase = props.getProperty("lib.base").replace("\\\\", "\\");

            ProcessBuilder pbConvert = new ProcessBuilder(javaExe, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                    "-Dstderr.encoding=UTF-8", "-classpath", classPath, "org.omg.sysml.xtext.util.SysML2XMI", sysmlPath,
                    libBase + "/Kernel Libraries", libBase + "/Systems Library", libBase + "/Domain Libraries");

            pbConvert.redirectErrorStream(true);
            Process processConvert = pbConvert.start();

            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(processConvert.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[IDE原生转换器] " + line);
                }
            }

            int convertExitCode = processConvert.waitFor();
            if (convertExitCode == 0 && new File(sysmlxPath).exists()) {
                System.out.println("阶段 0 成功: IDE 原生 sysmlx 生成完毕\n");
            } else {
                System.err.println("阶段 0 失败: IDE 原生转换工具执行异常。退出码: " + convertExitCode);
                return;
            }

            // ==========================================
            // [阶段 1] 传入 sysmlx 生成 UML
            // ==========================================
            System.out.println(">>> 阶段 1: 调用已有解析器，将 sysmlx 转换为 UML...");
            this.executePureXMLPipeline(sysmlxPath);

            File sourceUml = new File(generatedUmlPath);
            if (!sourceUml.exists()) {
                System.err.println("阶段 1 失败: 找不到解析器生成的 UML 文件: " + generatedUmlPath);
                return;
            }
            System.out.println("阶段 1 成功: UML 解析完毕\n");

            // ==========================================
            // [阶段 2] 复制 UML 到 Moka 仿真目录 (覆盖)
            // ==========================================
            System.out.println(">>> 阶段 2: 将生成的 UML 部署到 Moka 引擎...");
            File targetUml = new File(mokaTargetUmlPath);
            if (targetUml.getParentFile() != null && !targetUml.getParentFile().exists()) {
                targetUml.getParentFile().mkdirs();
            }
            Files.copy(sourceUml.toPath(), targetUml.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("阶段 2 成功: 成功覆盖文件至 " + mokaTargetUmlPath + "\n");

            // ==========================================
            // [阶段 3] 调用 run.bat 启动仿真
            // ==========================================
            System.out.println(">>> 阶段 3: 执行 run.bat 启动 Moka 仿真...");
            ProcessBuilder pbMoka = new ProcessBuilder("cmd.exe", "/c", runBatPath);
            pbMoka.directory(new File(mokaWorkDir));
            pbMoka.redirectErrorStream(true);

            Process processMoka = pbMoka.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(processMoka.getInputStream(), "GBK"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int mokaExitCode = processMoka.waitFor();
            if (mokaExitCode == 0) {
                System.out.println("流水线竣工! Moka 仿真正常结束。");
            } else {
                System.err.println("仿真进程退出，状态码: " + mokaExitCode);
            }

        } catch (Exception e) {
            System.err.println("流水线执行期间发生异常!");
            e.printStackTrace();
        }
    }

    // =====================================================================
    // 主流水线: XMI → UML Activity 模型 (编排各 PASS)
    // =====================================================================
    public void executePureXMLPipeline(String xmiPath) {
        System.out.println("[DEBUG] Running FIXED MainRunner version");

        // --- 路径处理 ---
        String sysmlBasePath = xmiPath;
        try {
            sysmlBasePath = java.net.URLDecoder.decode(xmiPath, "UTF-8");
        } catch (Exception ignored) {
            // ignored
        }
        File xmiFile = new File(xmiPath);
        if (!xmiFile.exists()) {
            System.err.println("XMI file not found: " + xmiPath);
            return;
        }

        // --- EMF 资源注册 ---
        EPackage.Registry.INSTANCE.put(SysMLPackage.eNS_URI, SysMLPackage.eINSTANCE);
        KerMLStandaloneSetup.doSetup();
        SysMLxStandaloneSetup.doSetup();
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put(UMLResource.FILE_EXTENSION,
                UMLResource.Factory.INSTANCE);

        ResourceSet resourceSet = new ResourceSetImpl();
        resourceSet.getPackageRegistry().put(UMLPackage.eNS_URI, UMLPackage.eINSTANCE);

        try {
            // G.FIO.01: 使用getCanonicalPath()规范化文件路径，避免路径遍历漏洞
            String canonicalPath = xmiFile.getCanonicalPath();
            if (!canonicalPath.startsWith(xmiFile.getParentFile().getCanonicalPath())) {
                throw new IllegalArgumentException("Invalid file path: " + canonicalPath);
            }
            Resource resource = resourceSet.getResource(URI.createFileURI(canonicalPath), true);
            UMLFactory factory = UMLFactory.eINSTANCE;
            Model umlModel = factory.createModel();
            umlModel.setName("TransformedUMLGraph");

            // --- 初始化静态状态 ---
            umlNodes = new HashMap<>();
            uuidToNameMap = new HashMap<>();
            nameToIdMap = new HashMap<>();
            logicalEdges = new ArrayList<>();
            loopStartMerge = new HashMap<>();
            loopEndDecision = new HashMap<>();
            loopConditionText = new HashMap<>();
            loopBodyActions = new HashMap<>();
            loopIsUntilIds = new HashSet<>();
            loopBodyNodeIds = new HashSet<>();
            whileLoopMergeIds = new HashMap<>();
            whileLoopDecisionIds = new HashMap<>();
            whileLoopCondText = new HashMap<>();
            whileLoopIsUntil = new HashMap<>();
            whileLoopPostLoopActions = new HashMap<>();
            whileLoopIsWhileUntil = new HashMap<>();
            whileLoopEntryIds = new HashMap<>();
            whileLoopExitMergeIds = new HashMap<>();
            whileLoopPureExitMergeIds = new HashMap<>();
            topLevelIfMergeIds = new HashMap<>();

            // --- 构建流水线上下文 ---
            PipelineContext ctx = new PipelineContext();
            ctx.sysmlBasePath = sysmlBasePath;
            ctx.resourceSet = resourceSet;
            ctx.resource = resource;
            ctx.factory = factory;
            ctx.umlModel = umlModel;
            ctx.activity = null;

            // Calc 模型辅助映射 (仅在 calc 分支使用)
            Map<String, String> calcIdToType = new HashMap<>();
            Map<String, String> globalIdToName = new HashMap<>();

            // ===================================================================
            // PASS 1 + 1.5: 画布初始化 + 变量扫描
            // ===================================================================
            Pass1Init.run(resource, ctx, calcIdToType, globalIdToName);

            // ===================================================================
            // 非 Calc 模型: PASS 2 ~ 4
            // ===================================================================
            if (!ctx.isCalcModel) {
                Pass2Nodes.run(resource, ctx);
                Pass2Edges.run(resource, ctx);
                Pass3Edges.run(resource, ctx);
                Pass4Routing.run(resource, ctx);
            }

            // ===================================================================
            // Calc 模型: PASS C1 + C2 + C3
            // ===================================================================
            if (ctx.isCalcModel) {
                CalcModel.runCalcPipeline(ctx.activity, ctx, ctx.sysmlBasePath, ctx.calcIdToName, calcIdToType,
                        globalIdToName);
            }

            // ===================================================================
            // PASS 5: 数据流 + 控制流装配 + UML 输出
            // ===================================================================
            Pass5Assembly.run(resource, ctx, xmiPath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =====================================================================
    // CLI 入口
    // =====================================================================
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java sysml2uml.MainRunner <path-to-sysmlx>");
            return;
        }
        new MainRunner().executePureXMLPipeline(args[0]);
    }
}
