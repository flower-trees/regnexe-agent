/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.salt.regnexe.agent.core.example;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.TestApplication;
import org.salt.regnexe.agent.core.common.enums.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.market.SimpleMarketplace;
import org.salt.regnexe.agent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexe.agent.core.market.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Example 08: code workspace component.
 *
 * <p>This example shows how workspace operations can be wrapped as controlled
 * tools and then exposed through a single SubAgent capability. The direct tool
 * tests do not call an LLM. The final SubAgent test requires DASHSCOPE_API_KEY,
 * like the other full agent examples.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class Example08CodeWorkspaceComponentTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void workspaceToolsShouldSearchAndReadProjectFiles() {
        WorkspaceTools workspaceTools = new WorkspaceTools(workspaceRoot());
        Tool searchCode = workspaceTools.searchCodeTool();
        Tool readFile = workspaceTools.readFileTool();

        String searchResult = searchCode.invoke("CapabilityDescriptor.java").toString();
        String fileResult = readFile.invoke("src/main/java/org/salt/regnexe/agent/core/market/plugin/CapabilityDescriptor.java").toString();

        Assert.assertTrue(searchResult.contains("CapabilityDescriptor.java"));
        Assert.assertTrue(fileResult.contains("public class CapabilityDescriptor"));
    }

    @Test
    public void workspaceToolsShouldRejectUnsafePath() {
        WorkspaceTools workspaceTools = new WorkspaceTools(workspaceRoot());
        Tool readFile = workspaceTools.readFileTool();

        String relativeEscape = readFile.invoke("../pom.xml").toString();
        String absoluteEscape = readFile.invoke("/etc/passwd").toString();

        Assert.assertTrue(relativeEscape.contains("ERROR: path outside workspace"));
        Assert.assertTrue(absoluteEscape.contains("ERROR: path outside workspace"));
    }

    @Test
    public void workspaceToolsShouldRunWhitelistedCommand() {
        WorkspaceTools workspaceTools = new WorkspaceTools(workspaceRoot());
        Tool runCommand = workspaceTools.runCommandTool();

        String pwdResult = runCommand.invoke("pwd").toString();
        String rejected = runCommand.invoke("rm -rf target").toString();

        Assert.assertTrue(pwdResult.contains("exitCode=0"));
        Assert.assertTrue(pwdResult.contains(workspaceRoot().toString()));
        Assert.assertTrue(rejected.contains("ERROR: command is not allowed"));
    }

    @Test
    public void workspaceToolsShouldAcceptStructuredArguments() {
        WorkspaceTools workspaceTools = new WorkspaceTools(workspaceRoot());
        Tool searchCode = workspaceTools.searchCodeTool();
        Tool readFile = workspaceTools.readFileTool();
        Tool runCommand = workspaceTools.runCommandTool();

        String searchResult = searchCode.invoke(Map.of("query", "public class CapabilityDescriptor")).toString();
        String readResult = readFile.invoke(Map.of("path", "pom.xml")).toString();
        String commandResult = runCommand.invoke("{commandKey=pwd}").toString();

        Assert.assertTrue(searchResult.contains("CapabilityDescriptor.java"));
        Assert.assertTrue(readResult.contains("<artifactId>regnexe-agent</artifactId>"));
        Assert.assertTrue(commandResult.contains("exitCode=0"));
    }

    @Test
    public void codeWorkspaceSubAgentShouldInspectKnownFileWithOwnTools() {
        WorkspaceTools workspaceTools = new WorkspaceTools(workspaceRoot());
        List<Tool> ownTools = workspaceTools.allTools();

        SubAgentConfig codeAgentConfig = SubAgentConfig.builder()
                .name("code_workspace_agent")
                .description("Code workspace assistant. Can search, read, and validate this Java project. " +
                             "TRIGGER: Use when the user asks to inspect project code or run a safe workspace check.")
                .systemPrompt("""
                        You are a code workspace assistant for a Java project.
                        You may use the provided tools to search code, read project files, and run whitelisted checks.
                        For this example:
                        1. First call read_file with the exact path provided by the user.
                        2. Then call run_command with commandKey "pwd".
                        3. Do not call search_code unless read_file fails.
                        4. After these calls, always return a final answer.
                        Do not modify files.
                        For this example, keep the final answer concise and include:
                        1. The file where CapabilityDescriptor metadata fallback is implemented.
                        2. The method that fills missing name and description metadata.
                        3. The result of the pwd command.
                        """)
                .ownTools(ownTools)
                .build();

        CapabilityDescriptor codeAgentCap = CapabilityDescriptor.builder()
                .capabilityId("code_workspace_agent")
                .pluginId("code-workspace-plugin")
                .type(CapabilityType.SUB_AGENT)
                .tags(List.of("code", "workspace", "validation"))
                .subAgentConfig(codeAgentConfig)
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(PluginDescriptor.builder()
                .pluginId("code-workspace-plugin")
                .version("1.0")
                .name("Code Workspace Plugin")
                .description("Controlled code workspace tools exposed through a sub-agent")
                .capabilities(List.of(codeAgentCap))
                .build());

        Assert.assertNotNull(marketplace.resolveDescriptor("code_workspace_agent"));
        Assert.assertNull("ownTools must not be exposed as top-level marketplace capabilities",
                marketplace.resolveDescriptor("search_code"));

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .withMaxAgentIterations(16)
                .build();

        AgentResult result = agent.execute(
                "Use the code workspace agent to read " +
                "src/main/java/org/salt/regnexe/agent/core/market/plugin/CapabilityDescriptor.java, " +
                "identify the method that fills missing name and description metadata, and run the whitelisted command key pwd. " +
                "Do not modify files.");

        System.out.println("\n========== CodeWorkspace Result ==========");
        System.out.println("Status : " + result.getStatus());
        System.out.println("Rounds : " + result.getState().getCurrentRound());
        System.out.println("Answer :\n" + result.getFinalText());
        System.out.println("==========================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
        Assert.assertTrue(result.getFinalText().contains("CapabilityDescriptor"));
        Assert.assertTrue(result.getFinalText().contains("fillNameAndDescription"));
    }

    private Path workspaceRoot() {
        return Path.of("").toAbsolutePath().normalize();
    }

    private static class WorkspaceTools {
        private static final int MAX_OUTPUT_CHARS = 4000;
        private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

        private final Path root;
        private final Map<String, List<String>> allowedCommands;

        private WorkspaceTools(Path root) {
            this.root = root.toAbsolutePath().normalize();
            this.allowedCommands = Map.of(
                    "pwd", List.of("pwd"),
                    "list_core_files", List.of("rg", "--files", "src/main/java/org/salt/regnexe/agent/core")
            );
        }

        private List<Tool> allTools() {
            return List.of(searchCodeTool(), readFileTool(), runCommandTool());
        }

        private Tool searchCodeTool() {
            return Tool.builder()
                    .name("search_code")
                    .description("Search project code for a text query. Returns matching file paths and line numbers.")
                    .params("query: String -- text to search inside the workspace")
                    .func(query -> searchCode(extractStringArg(query, "query")))
                    .build();
        }

        private Tool readFileTool() {
            return Tool.builder()
                    .name("read_file")
                    .description("Read a UTF-8 text file from inside the workspace. Rejects paths outside the workspace. " +
                                  "Optional 'offset' (0-based start line, default 0) and 'limit' (max lines) page through " +
                                  "large files. If the result is truncated, it tells you which offset to continue from.")
                    .params("path: String -- relative project path to read, " +
                            "offset: int -- optional 0-based start line, limit: int -- optional max lines")
                    .func(this::readFile)
                    .build();
        }

        private Tool runCommandTool() {
            return Tool.builder()
                    .name("run_command")
                    .description("Run a whitelisted workspace command by key. Allowed keys: pwd, list_core_files.")
                    .params("commandKey: String -- one of: pwd, list_core_files")
                    .func(commandKey -> runAllowedCommand(extractStringArg(commandKey, "commandKey")))
                    .build();
        }

        private String searchCode(String query) {
            String normalized = query == null ? "" : query.trim();
            if (normalized.isBlank()) {
                return "ERROR: query is blank";
            }
            String commandResult = runProcess(List.of("rg", "-n", normalized, "src/main/java", "src/test/java"));
            if (commandResult.contains("exitCode=0")) {
                return commandResult;
            }
            return fallbackSearch(normalized);
        }

        private String readFile(Object args) {
            String pathText = extractStringArg(args, "path");
            Integer offset = extractIntArg(args, "offset");
            Integer limit = extractIntArg(args, "limit");
            Path path;
            try {
                path = resolveInsideWorkspace(pathText);
            } catch (IllegalArgumentException e) {
                return "ERROR: " + e.getMessage();
            }
            if (!Files.isRegularFile(path)) {
                return "ERROR: file not found: " + root.relativize(path);
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "ERROR: failed to read file: " + e.getMessage();
            }
            if (lines.isEmpty()) {
                return "";
            }
            int start = offset != null ? Math.max(0, offset) : 0;
            if (start >= lines.size()) {
                return "ERROR: offset " + start + " is past end of file (" + lines.size() + " lines)";
            }
            int requestedEnd = limit != null ? Math.min(lines.size(), start + Math.max(0, limit)) : lines.size();

            StringBuilder content = new StringBuilder();
            int end = start;
            while (end < requestedEnd) {
                String line = lines.get(end);
                if (content.length() > 0 && content.length() + 1 + line.length() > MAX_OUTPUT_CHARS) {
                    break;
                }
                if (content.length() > 0) {
                    content.append('\n');
                }
                content.append(line);
                end++;
            }
            if (end == start) {
                // a single line alone exceeds the budget; include a truncated slice rather than nothing
                String firstLine = lines.get(start);
                content.append(firstLine, 0, Math.min(MAX_OUTPUT_CHARS, firstLine.length()));
                end = start + 1;
            }
            String suffix = end < lines.size()
                    ? "\n...(showing lines " + start + "-" + (end - 1) + " of " + lines.size()
                      + " total; call read_file again with offset=" + end + " to continue)"
                    : "";
            return content + suffix;
        }

        private String runAllowedCommand(String commandKeyText) {
            String commandKey = normalizeCommandKey(commandKeyText);
            List<String> command = allowedCommands.get(commandKey);
            if (command == null) {
                return "ERROR: command is not allowed: " + commandKeyText;
            }
            return runProcess(command);
        }

        private Path resolveInsideWorkspace(String pathText) {
            if (pathText == null || pathText.isBlank()) {
                throw new IllegalArgumentException("path is blank");
            }
            Path input = Path.of(pathText.trim());
            Path resolved = input.isAbsolute() ? input.normalize() : root.resolve(input).normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException("path outside workspace: " + pathText);
            }
            return resolved;
        }

        private String normalizeCommandKey(String commandKeyText) {
            String text = commandKeyText == null ? "" : commandKeyText.trim();
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                text = text.substring(0, newline).trim();
            }
            return text.toLowerCase(Locale.ROOT);
        }

        private Integer extractIntArg(Object input, String key) {
            if (!(input instanceof Map<?, ?> map)) {
                return null;
            }
            Object value = map.get(key);
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private String extractStringArg(Object input, String key) {
            if (input == null) {
                return "";
            }
            if (input instanceof Map<?, ?> map) {
                Object value = map.get(key);
                return value != null ? value.toString() : input.toString();
            }
            String text = input.toString().trim();
            String mapValue = extractMapToStringValue(text, key);
            if (mapValue != null) {
                return mapValue;
            }
            String marker = "\"" + key + "\"";
            int keyIndex = text.indexOf(marker);
            if (keyIndex < 0) {
                return text;
            }
            int colon = text.indexOf(':', keyIndex + marker.length());
            if (colon < 0) {
                return text;
            }
            int startQuote = text.indexOf('"', colon + 1);
            if (startQuote < 0) {
                return text.substring(colon + 1).replace("}", "").trim();
            }
            StringBuilder value = new StringBuilder();
            boolean escaped = false;
            for (int i = startQuote + 1; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (escaped) {
                    value.append(ch);
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    return value.toString();
                }
                value.append(ch);
            }
            return text;
        }

        private String extractMapToStringValue(String text, String key) {
            Pattern pattern = Pattern.compile("(^|[\\{,]\\s*)" + Pattern.quote(key) + "=([^,}]+)");
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            return matcher.group(2).trim();
        }

        private String runProcess(List<String> command) {
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(root.toFile());
                builder.redirectErrorStream(true);
                process = builder.start();
                boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return "ERROR: command timed out: " + String.join(" ", command);
                }
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return "command=" + String.join(" ", command) + "\n" +
                       "exitCode=" + process.exitValue() + "\n" +
                       truncate(output);
            } catch (Exception e) {
                return "ERROR: command failed: " + e.getMessage();
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        private String fallbackSearch(String query) {
            List<String> matches = new ArrayList<>();
            try (Stream<Path> files = Files.walk(root.resolve("src"))) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> collectMatches(path, query, matches));
            } catch (IOException e) {
                return "ERROR: fallback search failed: " + e.getMessage();
            }
            if (matches.isEmpty()) {
                return "No matches found.";
            }
            return truncate(String.join("\n", matches));
        }

        private void collectMatches(Path path, String query, List<String> matches) {
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(query)) {
                        matches.add(root.relativize(path) + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            } catch (IOException ignored) {
                // Ignore unreadable files in fallback search.
            }
        }

        private String truncate(String text) {
            if (text == null) {
                return "";
            }
            if (text.length() <= MAX_OUTPUT_CHARS) {
                return text;
            }
            return text.substring(0, MAX_OUTPUT_CHARS) + "\n...TRUNCATED...";
        }
    }
}
