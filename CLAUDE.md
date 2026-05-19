# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
mvn clean package          # Build uber-jar (target/thinkingcoding.jar)
mvn test                   # Run all tests
mvn test -Dtest=ClassName  # Run a single test class
mvn exec:java -Dexec.mainClass="com.thinkingcoding.ThinkingCodingCLI"  # Run from source
```

Java 17+ required. Project uses Lombok — enable annotation processing in your IDE.

## Architecture

Entry: `ThinkingCodingCLI.main()` creates `ThinkingCodingContext` (the DI container / service locator), then routes to Picocli subcommands (`session`, `config`, `skill`) or the main `ThinkingCodingCommand` which starts the interactive loop.

### Agent Loop (V2)

The V2 agent loop (`agentloop/v2/orchestrator/`) is the current implementation, replacing legacy `core/AgentLoop`. It runs a three-phase pipeline per turn:

1. **Plan** — `LangChainPlanner` calls the AI model via `LangChainService`, returning a `PlanResult` with assistant text and tool calls
2. **Execute + ReAct** — `ReActDriver` iterates tool calls through `ToolExecutionEngine`, writes observations back to history, and re-plans on failure or when the queue empties. It detects duplicate tool signatures to prevent infinite loops
3. **Steering** — `InteractiveToolConfirmationPolicy` wraps legacy `ToolExecutionConfirmation` for per-tool user confirmation, with `/auto-approve-on|off`, `/stop`, `/cancel` commands

The `AgentOrchestrator` wires all components and `onUserInput()` drives the full turn. Default config: 20 max ReAct steps, 10 max tool calls per plan, auto-approve off.

### Skills

Skills (`skill/`) are workflow modules implementing the `Skill` interface. `LazySkillToolAdapter` wraps them as `BaseTool` instances so the AI can discover and invoke them via tool calling. `SkillRegistry` holds configured skills; `SkillFactory` creates instances. The built-in `AutoTestSkill` generates and auto-fixes unit tests. Skills are configured in `config.yaml` and registered during `ThinkingCodingContext.initializeSkills()`.

### MCP Integration

MCP (Model Context Protocol) connects to external tool servers over JSON-RPC on stdio. `MCPService` manages multiple `MCPClient` instances (one per server). Each MCP tool is wrapped by `MCPToolAdapter` (extends `BaseTool`) and registered in `ToolRegistry`. Tools can be pre-connected via `config.yaml` or dynamically via `/mcp connect` commands.

### Key Relationships

- `ToolRegistry` is the single registry for all tools — built-in tools (`tools/`), MCP-adapted tools (`mcp/MCPToolAdapter`), and skill-adapted tools (`skill/LazySkillToolAdapter`)
- `LangChainService` builds LangChain4j tool specifications from `ToolRegistry` before each AI call
- `ContextManager` manages the conversation token window (sliding window) and injects available skills into the system prompt
- `SessionService` persists conversation history to `sessions/` as JSON files
- `ProjectContext` auto-detects Maven/Gradle/NPM projects in the working directory

## Before Editing Code (GitNexus)

This project is indexed by GitNexus. Before modifying any function, class, or method:

1. Run impact analysis: `gitnexus_impact({target: "symbolName", direction: "upstream"})` — report blast radius and risk level
2. Before committing, run `gitnexus_detect_changes()` to verify changes only affect expected symbols/flows
3. Warn the user if impact analysis returns HIGH or CRITICAL risk before proceeding with edits
4. Never rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph

## Config

`config.yaml` at the project root (gitignored) holds model configs, MCP server definitions, tool settings, and skill registrations. `ConfigLoader` reads it on startup; `ConfigManager` (singleton) provides runtime access.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **ThinkingCoding** (4293 symbols, 10726 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/ThinkingCoding/context` | Codebase overview, check index freshness |
| `gitnexus://repo/ThinkingCoding/clusters` | All functional areas |
| `gitnexus://repo/ThinkingCoding/processes` | All execution flows |
| `gitnexus://repo/ThinkingCoding/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
