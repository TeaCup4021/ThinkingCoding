package com.thinkingcoding.mcp;

import com.thinkingcoding.config.AppConfig;

/**
 * 端到端验证脚本：手动运行以验证 GitNexus 过期检测 → analyze → 回调 的完整流程。
 *
 * 运行方式：
 *   mvn exec:java -Dexec.mainClass="com.thinkingcoding.mcp.StalenessPipelineE2ETest"
 *
 * 或编译后在 IDE 中直接运行 main()。
 *
 * 前置条件：当前 GitNexus 索引必须是 stale 状态。
 *           用 npx gitnexus status 确认。
 *
 * 预期输出：
 *   1. [检测] 运行 npx gitnexus status → 检测到 stale
 *   2. [刷新] 运行 npx gitnexus analyze → 刷新知识图谱
 *   3. [回调] analyze 成功后触发 onIndexRefreshed → 打印回调执行日志
 *   4. [结果] ensureFresh() 返回 FRESH
 */
public class StalenessPipelineE2ETest {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("GitNexus 过期检测 + 自动刷新 端到端测试");
        System.out.println("=".repeat(60));

        // 使用默认配置（statusCommand=npx gitnexus status, analyzeCommand=npx gitnexus analyze）
        AppConfig.StalenessCheckConfig config = new AppConfig.StalenessCheckConfig();
        config.setEnabled(true);
        config.setCacheTtlSeconds(1);  // 1 秒缓存，方便测试
        config.setAnalyzeTimeoutSeconds(180);

        String workspace = System.getProperty("user.dir");

        GitNexusStalenessChecker checker = new GitNexusStalenessChecker(config, workspace);

        // 注册回调：analyze 成功后触发
        final boolean[] callbackFired = {false};
        checker.setOnIndexRefreshed(() -> {
            callbackFired[0] = true;
            System.out.println("\n>>> [回调] onIndexRefreshed 触发！");
            System.out.println(">>> 此处应调用 GraphEmbeddingIndexer.incrementalIndex()");
            System.out.println(">>> 在生产代码中由 ThinkingCodingContext 自动注入\n");
        });

        // 先验证当前状态
        System.out.println("\n[步骤 1] 检查当前 GitNexus 状态...");
        String statusOutput = checker.runCommand("npx gitnexus status", 30_000L);
        if (statusOutput != null) {
            System.out.println(statusOutput);
            boolean stale = checker.parseStaleness(statusOutput);
            System.out.println("解析结果: " + (stale ? "⚠️ STALE (将触发 analyze)" : "✅ FRESH"));
        }

        // 执行核心流程
        System.out.println("\n[步骤 2] 执行 ensureFresh()...");
        System.out.println("---------- 日志输出 ----------");
        long t0 = System.currentTimeMillis();

        GitNexusStalenessChecker.StalenessResult result = checker.ensureFresh();

        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("---------- 日志结束 ----------");

        // 验证结果
        System.out.println("\n[步骤 3] 验证结果...");
        System.out.println("ensureFresh() 返回: " + (result.canProceed() ? "✅ FRESH (可以继续)" : "❌ FAILED"));
        if (!result.canProceed()) {
            System.out.println("错误信息: " + result.getErrorMessage());
        }
        System.out.println("耗时: " + (elapsed / 1000.0) + " 秒");
        System.out.println("回调是否触发: " + (callbackFired[0] ? "✅ 是" : "❌ 否"));

        // 再次验证状态
        System.out.println("\n[步骤 4] 验证 analyze 后索引状态...");
        String finalStatus = checker.runCommand("npx gitnexus status", 30_000L);
        if (finalStatus != null) {
            System.out.println(finalStatus);
            boolean stillStale = checker.parseStaleness(finalStatus);
            System.out.println("刷新后状态: " + (stillStale ? "❌ 仍然过期" : "✅ 已更新"));
        }

        // 验证缓存：第二次调用 ensureFresh() 应该直接返回缓存结果（不重复跑 analyze）
        System.out.println("\n[步骤 5] 验证缓存...");
        long t1 = System.currentTimeMillis();
        GitNexusStalenessChecker.StalenessResult result2 = checker.ensureFresh();
        long elapsed2 = System.currentTimeMillis() - t1;
        System.out.println("第二次 ensureFresh() 耗时: " + elapsed2 + "ms" +
                (elapsed2 < 1000 ? " ✅ (缓存命中，几乎零开销)" : " ⚠️ (可能未命中缓存)"));

        System.out.println("\n" + "=".repeat(60));
        boolean allPassed = result.canProceed() && callbackFired[0] && elapsed2 < 1000;
        System.out.println(allPassed ? "✅ 全部测试通过" : "❌ 部分测试失败");
        System.out.println("=".repeat(60));
    }
}
