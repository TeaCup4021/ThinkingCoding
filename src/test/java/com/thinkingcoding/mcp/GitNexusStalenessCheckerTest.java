package com.thinkingcoding.mcp;

import com.thinkingcoding.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class GitNexusStalenessCheckerTest {

    private AppConfig.StalenessCheckConfig config;

    @BeforeEach
    public void setUp() {
        config = new AppConfig.StalenessCheckConfig();
        config.setEnabled(true);
        config.setCacheTtlSeconds(60);
        config.setAnalyzeTimeoutSeconds(180);
    }

    @Test
    public void testParseStalenessFresh() {
        GitNexusStalenessChecker checker = new GitNexusStalenessChecker(config, ".");
        String output = "Repository: D:\\ThinkingCoding\n"
                + "Indexed: 2026/5/18 21:22:32\n"
                + "Indexed commit: cf27be4\n"
                + "Current commit: cf27be4\n"
                + "Status: ✅ up-to-date";
        assertFalse(checker.parseStaleness(output));
    }

    @Test
    public void testParseStalenessStale() {
        GitNexusStalenessChecker checker = new GitNexusStalenessChecker(config, ".");
        String output = "Repository: D:\\ThinkingCoding\n"
                + "Indexed: 2026/5/18 21:22:32\n"
                + "Indexed commit: cf27be4\n"
                + "Current commit: 4f0c024\n"
                + "Status: ⚠️ stale (re-run gitnexus analyze)";
        assertTrue(checker.parseStaleness(output));
    }

    @Test
    public void testParseStalenessFallbackByHash() {
        GitNexusStalenessChecker checker = new GitNexusStalenessChecker(config, ".");
        // No "Status:" line with "stale", but commits differ
        String output = "Repository: D:\\ThinkingCoding\n"
                + "Indexed commit: aaaaa\n"
                + "Current commit: bbbbb";
        assertTrue(checker.parseStaleness(output));
    }

    @Test
    public void testParseStalenessFallbackSameHash() {
        GitNexusStalenessChecker checker = new GitNexusStalenessChecker(config, ".");
        String output = "Repository: D:\\ThinkingCoding\n"
                + "Indexed commit: cf27be4\n"
                + "Current commit: cf27be4";
        assertFalse(checker.parseStaleness(output));
    }

    @Test
    public void testParseStalenessUnparseable() {
        GitNexusStalenessChecker checker = new GitNexusStalenessChecker(config, ".");
        assertFalse(checker.parseStaleness("some random output"));
        assertFalse(checker.parseStaleness(""));
    }

    @Test
    public void testEnsureFreshWhenIndexFresh() {
        String statusOutput = "Repository: D:\\ThinkingCoding\n"
                + "Indexed commit: cf27be4\n"
                + "Current commit: cf27be4\n"
                + "Status: ✅ up-to-date";

        GitNexusStalenessChecker checker = spy(new GitNexusStalenessChecker(config, "."));
        doReturn(statusOutput).when(checker).runCommand(eq("npx gitnexus status"), anyLong());

        GitNexusStalenessChecker.StalenessResult result = checker.ensureFresh();
        assertTrue(result.canProceed());
        verify(checker, times(1)).runCommand(eq("npx gitnexus status"), anyLong());
        verify(checker, never()).runCommand(eq("npx gitnexus analyze"), anyLong());
    }

    @Test
    public void testEnsureFreshWhenIndexStaleAndAnalyzeSucceeds() {
        String staleStatus = "Indexed commit: aaaaa\n"
                + "Current commit: bbbbb\n"
                + "Status: ⚠️ stale (re-run gitnexus analyze)";
        String analyzeOutput = "Indexing complete: 4108 symbols";

        GitNexusStalenessChecker checker = spy(new GitNexusStalenessChecker(config, "."));
        doReturn(staleStatus).when(checker).runCommand(eq("npx gitnexus status"), anyLong());
        doReturn(analyzeOutput).when(checker).runCommand(eq("npx gitnexus analyze"), anyLong());

        GitNexusStalenessChecker.StalenessResult result = checker.ensureFresh();
        assertTrue(result.canProceed());
        verify(checker, times(1)).runCommand(eq("npx gitnexus status"), anyLong());
        verify(checker, times(1)).runCommand(eq("npx gitnexus analyze"), anyLong());
    }

    @Test
    public void testEnsureFreshWhenAnalyzeFails() {
        String staleStatus = "Status: ⚠️ stale (re-run gitnexus analyze)";

        GitNexusStalenessChecker checker = spy(new GitNexusStalenessChecker(config, "."));
        doReturn(staleStatus).when(checker).runCommand(eq("npx gitnexus status"), anyLong());
        doReturn(null).when(checker).runCommand(eq("npx gitnexus analyze"), anyLong());

        GitNexusStalenessChecker.StalenessResult result = checker.ensureFresh();
        assertFalse(result.canProceed());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("stale"));
    }

    @Test
    public void testCacheTtlRespected() {
        String statusOutput = "Status: ✅ up-to-date";

        GitNexusStalenessChecker checker = spy(new GitNexusStalenessChecker(config, "."));
        doReturn(statusOutput).when(checker).runCommand(eq("npx gitnexus status"), anyLong());

        // First call: runs status
        checker.ensureFresh();
        verify(checker, times(1)).runCommand(eq("npx gitnexus status"), anyLong());

        // Second call: cached, no additional status run
        checker.ensureFresh();
        verify(checker, times(1)).runCommand(eq("npx gitnexus status"), anyLong());
    }

    @Test
    public void testInvalidateCacheForcesRecheck() {
        String statusOutput = "Status: ✅ up-to-date";

        GitNexusStalenessChecker checker = spy(new GitNexusStalenessChecker(config, "."));
        doReturn(statusOutput).when(checker).runCommand(eq("npx gitnexus status"), anyLong());

        checker.ensureFresh();
        verify(checker, times(1)).runCommand(eq("npx gitnexus status"), anyLong());

        checker.invalidateCache();
        checker.ensureFresh();
        verify(checker, times(2)).runCommand(eq("npx gitnexus status"), anyLong());
    }

    @Test
    public void testDisabledCheckerSkipsAll() {
        config.setEnabled(false);
        GitNexusStalenessChecker checker = spy(new GitNexusStalenessChecker(config, "."));

        GitNexusStalenessChecker.StalenessResult result = checker.ensureFresh();
        assertTrue(result.canProceed());
        verify(checker, never()).runCommand(anyString(), anyLong());
    }

    @Test
    public void testStatusCommandFailureDegradesGracefully() {
        GitNexusStalenessChecker checker = spy(new GitNexusStalenessChecker(config, "."));
        doReturn(null).when(checker).runCommand(eq("npx gitnexus status"), anyLong());

        GitNexusStalenessChecker.StalenessResult result = checker.ensureFresh();
        // Should proceed despite status failure (graceful degradation)
        assertTrue(result.canProceed());
        verify(checker, never()).runCommand(eq("npx gitnexus analyze"), anyLong());
    }

    @Test
    public void testNullConfigDefaults() {
        GitNexusStalenessChecker checker = new GitNexusStalenessChecker(null, null);
        // With null config, enabled defaults to false (via config.isEnabled()=false)
        // Actually StalenessCheckConfig default is enabled=true
        // Let's test with null: the checker checks `config != null && config.isEnabled()`
        // null config → enabled=false
        GitNexusStalenessChecker.StalenessResult result = checker.ensureFresh();
        assertTrue(result.canProceed());
    }
}
