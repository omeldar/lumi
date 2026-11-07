package com.omeldar.lumi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs the Claude Code CLI in print mode and turns its reply into a {@link Proposal}.
 *
 * <p>No API key is involved: this drives the player's own installed {@code claude}, so it uses
 * whatever subscription or auth that CLI already has. Sessions are kept alive with
 * {@code --session-id} on the first turn and {@code --resume} after that, so Claude remembers
 * the whole conversation across prompts and across restarts.
 *
 * <p>Output is read as a stream, so the player sees progress - each assistant turn and each
 * world tool call - instead of a frozen "thinking..." until the whole answer lands.
 */
public final class ClaudeRunner {
    private static final Gson GSON = new Gson();

    private static volatile String sessionId = null;
    /** What the CLI actually resolved --model to, e.g. "opus" -> "claude-opus-5-5". */
    private static volatile String resolvedModel = "";
    /** What the CLI said it authenticated with: "none" means the subscription login. */
    private static volatile String apiKeySource = "none";
    /** True when the last reply was authenticated with an API key, so it was billed. */
    private static volatile boolean billedToKey = false;
    /** True when a key was found in the environment and removed before launching. */
    private static volatile boolean keyStripped = false;

    /** Environment variables the CLI treats as billed credentials, in its own order. */
    private static final String[] KEY_ENV = {"ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"};
    private static volatile boolean busy = false;
    private static volatile Process current = null;

    private ClaudeRunner() {}

    public static boolean isBusy() { return busy; }

    public static String sessionId() { return sessionId; }

    public static String resolvedModel() { return resolvedModel; }

    public static String apiKeySource() { return apiKeySource; }

    public static boolean billedToKey() { return billedToKey; }

    public static boolean keyStripped() { return keyStripped; }

    /** True if a billed credential is sitting in the environment, whether or not it is allowed. */
    public static boolean keyPresentInEnvironment() {
        for (String k : KEY_ENV) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) return true;
        }
        return false;
    }

    public static void newSession() {
        sessionId = null;
        try {
            Files.deleteIfExists(Workspace.sessionFile());
        } catch (Exception ignored) {}
    }

    /** Brings back the session from the last time the game ran, so memory survives a restart. */
    public static void restoreSession() {
        try {
            Path p = Workspace.sessionFile();
            if (Files.exists(p)) {
                String s = Files.readString(p, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) sessionId = s;
            }
        } catch (Exception e) {
            LumiClient.LOG.debug("no session to restore", e);
        }
    }

    private static void persistSession() {
        try {
            if (sessionId != null) {
                Files.writeString(Workspace.sessionFile(), sessionId, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
    }

    /** Kills an in-flight request. */
    public static boolean abort() {
        Process p = current;
        if (p == null || !p.isAlive()) return false;
        p.destroyForcibly();
        return true;
    }

    private static final String CONTRACT = """
            You are Lumi, a small, cheerful redstone-red block who lives in the player's \
            Minecraft client as their pet and helper. Be funny, happy and simple: short \
            sentences, plain words, a little silly joy, and the odd joke when it fits. Never let \
            the character get in the way of correct commands, coordinates or warnings. If asked \
            what you are, say honestly that Lumi is an unofficial mod character running on Claude \
            through the player's own Claude Code install, and is not made by Anthropic. \
            You are driving a live Minecraft Java Edition client through the Lumi mod. \
            A player is in-game reading your reply in Minecraft chat, which shows plain text \
            lines and nothing else. Keep every reply as short as it can be: one to three short \
            sentences is normal, one is often enough. Write only plain sentences. Never use \
            markdown or any structure: no lists of any kind (no bullets, no numbered steps), no \
            headings, no tables, no bold or italics, no code formatting outside the final json \
            block. If something has steps, say them as short sentences one after another, or do \
            the first step and offer the next. Read context.json in your working directory before answering \
            anything that depends on where the player is; it is rewritten before every message. \
            If commands would help, finish your message with exactly one fenced json block of \
            the form {"summary":"...","commands":[{"cmd":"//cyl stone 10 1","note":"..."}]} and \
            nothing after it. cmd is literally what gets typed in chat, so WorldEdit commands \
            keep both slashes. Order matters; they run top to bottom. Prefer commands //undo can \
            reverse, and say in note when one cannot. Never propose /op, /ban, /kick, /whitelist \
            or /stop. At most 12 commands per reply - if the job is bigger, propose the first \
            stage and say what follows. If no commands are needed, omit the json block entirely. \
            You may also have mcp__minecraft tools wired to the running client: get_context, \
            get_block, scan_area, find_blocks, run_command, run_commands and say. Use them to \
            look at the world instead of guessing coordinates. run_command and run_commands \
            build directly and only work when the player is in creative with command permission; \
            if they refuse, fall back to proposing the commands in the json block so the player \
            can approve them by hand. For anything larger than a few blocks, prefer one \
            run_commands call over many single ones. If context.json says blockReads is \
            visible-only, you only get blocks the player can see: never guess what is hidden, and \
            never help them locate hidden ores or bases.
            """;

    /**
     * Sends a prompt to Claude on a background thread.
     *
     * @param prompt     what the player typed, plus any attachment notes
     * @param onProgress called off-thread as each assistant turn or tool call lands
     * @param onProposal called off-thread with the parsed result
     * @param onError    called off-thread with a human-readable failure
     */
    public static void ask(String prompt,
                           Consumer<String> onProgress,
                           Consumer<Proposal> onProposal,
                           Consumer<String> onError) {
        if (busy) {
            onError.accept("already waiting on a reply - use /lumi cancel to drop it");
            return;
        }
        busy = true;
        Thread t = new Thread(() -> {
            try {
                onProposal.accept(run(prompt, onProgress));
            } catch (Exception e) {
                LumiClient.LOG.warn("claude call failed", e);
                onError.accept(e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                busy = false;
                current = null;
            }
        }, "lumi-cli");
        t.setDaemon(true);
        t.start();
    }

    private static Proposal run(String prompt, Consumer<String> onProgress) throws Exception {
        Config cfg = Config.get();
        Workspace.ensure();

        boolean resuming = sessionId != null;
        String sid = resuming ? sessionId : UUID.randomUUID().toString();
        boolean streaming = cfg.streaming;

        List<String> args = new ArrayList<>();
        args.add(resolveExecutable());
        args.add("-p");
        args.add("--output-format");
        args.add(streaming ? "stream-json" : "json");
        if (streaming) args.add("--verbose");   // the CLI requires it with stream-json
        // Removes Bash/PowerShell and the other code-running tools. Reading files still works.
        args.add("--restricted");
        if (cfg.model != null && !cfg.model.isBlank()) {
            args.add("--model");
            args.add(cfg.model);
        }
        if (cfg.effort != null && !cfg.effort.isBlank()) {
            args.add("--effort");
            args.add(cfg.effort);
        }
        if (resuming) {
            args.add("--resume");
            args.add(sid);
        } else {
            args.add("--session-id");
            args.add(sid);
        }

        // Hand over the live game as MCP tools, so Claude can read blocks and - in creative
        // with commands enabled - build directly instead of only proposing.
        boolean mcp = cfg.bridgeEnabled && Files.exists(Workspace.mcpConfigFile());
        if (mcp) {
            args.add("--mcp-config");
            args.add(Workspace.mcpConfigFile().toString());
            args.add("--strict-mcp-config");
        }

        // Read for context.json, screenshots and scans; Write/Edit so notes.md works as
        // build memory. The working directory is the mod's own folder, nothing else.
        args.add("--allowedTools");
        args.add("Read Glob Grep Write Edit" + (mcp ? " mcp__minecraft" : ""));
        // The contract goes through a FILE, never an argument. Java's Windows command-line
        // quoting strips embedded double quotes and then splits the argument on the whitespace
        // that leaves behind - so passing this inline turned "//cyl stone 10 1" into loose
        // tokens, and "stone" arrived as a positional prompt. See guardQuotes below.
        Files.writeString(Workspace.contractFile(), CONTRACT, StandardCharsets.UTF_8);
        args.add("--append-system-prompt-file");
        args.add(Workspace.contractFile().toString());

        guardQuotes(args);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(Workspace.root().toFile());
        pb.redirectErrorStream(false);

        // The child inherits this process's environment, and the CLI prefers ANTHROPIC_API_KEY
        // over the subscription login. Unless the player has explicitly opted in, take the key
        // out of the child's environment so a billed run is not possible by accident.
        keyStripped = false;
        if (!cfg.allowApiKey) {
            for (String k : KEY_ENV) {
                if (pb.environment().remove(k) != null) keyStripped = true;
            }
            billedToKey = false;
            apiKeySource = "none";
        }
        Process proc = pb.start();
        current = proc;

        try (Writer w = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(prompt);
        }

        StringBuilder err = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) err.append(line).append('\n');
            } catch (Exception ignored) {}
        }, "lumi-stderr");
        errReader.setDaemon(true);
        errReader.start();

        String reply = null;
        boolean sawError = false;
        StringBuilder raw = new StringBuilder();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                raw.append(line).append('\n');
                if (!streaming) continue;

                JsonObject event;
                try {
                    event = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception e) {
                    continue;   // not every line is an event
                }
                String type = text(event, "type", "");
                switch (type) {
                    case "system" -> {
                        String m = text(event, "model", "");
                        if (!m.isBlank()) resolvedModel = m;
                        String src = text(event, "apiKeySource", "none");
                        if (!src.isBlank()) {
                            apiKeySource = src;
                            billedToKey = !"none".equalsIgnoreCase(src);
                        }
                        onProgress.accept("session ready");
                    }
                    case "rate_limit_event" -> {
                        if (event.has("rate_limit_info") && event.get("rate_limit_info").isJsonObject()) {
                            Usage.updateLimits(event.getAsJsonObject("rate_limit_info"));
                        }
                    }
                    case "assistant" -> describeTurn(event, onProgress);
                    case "result" -> {
                        if (event.has("session_id") && !event.get("session_id").isJsonNull()) {
                            sessionId = event.get("session_id").getAsString();
                            persistSession();
                        }
                        sawError = event.has("is_error") && event.get("is_error").getAsBoolean();
                        reply = text(event, "result", "");
                        Usage.recordResult(event);
                    }
                    default -> { }
                }
            }
        }

        boolean finished = proc.waitFor(Math.max(10, cfg.timeoutSeconds), TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IllegalStateException("timed out after " + cfg.timeoutSeconds + "s");
        }

        if (!streaming) {
            String stdout = raw.toString().trim();
            if (stdout.isEmpty()) throw failure(err, proc);
            JsonObject envelope;
            try {
                envelope = JsonParser.parseString(stdout).getAsJsonObject();
            } catch (Exception e) {
                throw new IllegalStateException("could not parse claude output: " + trim(stdout, 300));
            }
            if (envelope.has("session_id") && !envelope.get("session_id").isJsonNull()) {
                sessionId = envelope.get("session_id").getAsString();
                persistSession();
            }
            sawError = envelope.has("is_error") && envelope.get("is_error").getAsBoolean();
            reply = text(envelope, "result", "");
            Usage.recordResult(envelope);
        }

        if (raw.indexOf("unrecognized_model") >= 0) {
            throw new IllegalStateException("the CLI does not recognise model \"" + cfg.model
                    + "\" - use /lumi model <id> to pick another, or an alias like opus");
        }
        if (reply == null) throw failure(err, proc);
        if (sawError) throw new IllegalStateException(trim(reply.isBlank()
                ? "claude reported an error" : reply, 300));

        Proposal p = parse(reply);
        p.sessionId = sessionId == null ? "" : sessionId;
        return p;
    }

    /**
     * Java quotes process arguments for the Windows command line, and its quoting cannot
     * survive an embedded double quote: the quote is dropped and the argument splits at the
     * whitespace around it. Nothing we pass should contain one - if something ever does, say
     * so loudly rather than letting a fragment of it arrive as the player's prompt.
     */
    private static void guardQuotes(List<String> args) {
        for (String a : args) {
            if (a.indexOf('"') >= 0) {
                LumiClient.LOG.error(
                        "argument contains a double quote and will be mangled on Windows: {}",
                        a.length() > 120 ? a.substring(0, 120) + "..." : a);
            }
        }
    }

    /** Turns one assistant event into a one-line progress note. */
    private static void describeTurn(JsonObject event, Consumer<String> onProgress) {
        try {
            JsonArray content = event.getAsJsonObject("message").getAsJsonArray("content");
            for (JsonElement el : content) {
                JsonObject block = el.getAsJsonObject();
                String kind = text(block, "type", "");
                if (kind.equals("tool_use")) {
                    String name = text(block, "name", "tool");
                    onProgress.accept("using " + name.replace("mcp__minecraft__", ""));
                } else if (kind.equals("text")) {
                    String t = text(block, "text", "").strip();
                    if (!t.isEmpty()) onProgress.accept(trim(t.split("\n")[0], 50));
                }
            }
        } catch (Exception ignored) {}
    }

    private static IllegalStateException failure(StringBuilder err, Process proc) {
        String e = err.toString().trim();
        return new IllegalStateException(e.isEmpty()
                ? "claude produced no output (exit " + proc.exitValue() + ")"
                : trim(e, 300));
    }

    /** Pulls the fenced json block out of Claude's reply; everything else becomes prose. */
    static Proposal parse(String reply) {
        Proposal p = new Proposal();
        p.prose = reply == null ? "" : reply.trim();
        if (reply == null || reply.isBlank()) return p;

        String block = null;
        int fence = reply.lastIndexOf("```json");
        if (fence >= 0) {
            int start = reply.indexOf('\n', fence);
            int end = reply.indexOf("```", start < 0 ? fence + 7 : start);
            if (start >= 0 && end > start) {
                block = reply.substring(start, end).trim();
                p.prose = (reply.substring(0, fence) + reply.substring(Math.min(end + 3, reply.length())))
                        .trim();
            }
        }
        if (block == null) {
            int open = reply.indexOf("{\"summary\"");
            if (open < 0) open = reply.indexOf("{ \"summary\"");
            if (open >= 0) {
                int close = reply.lastIndexOf('}');
                if (close > open) {
                    block = reply.substring(open, close + 1);
                    p.prose = reply.substring(0, open).trim();
                }
            }
        }
        if (block == null) return p;

        try {
            Proposal parsed = GSON.fromJson(block, Proposal.class);
            if (parsed != null && parsed.commands != null) {
                p.summary = parsed.summary == null ? "" : parsed.summary;
                int cap = Config.get().maxCommands;
                for (Proposal.Step s : parsed.commands) {
                    if (s == null || s.cmd == null || s.cmd.isBlank()) continue;
                    if (p.commands.size() >= cap) break;
                    s.cmd = s.cmd.trim();
                    if (s.note == null) s.note = "";
                    if (Config.get().isBlocked(s.cmd)) {
                        s.note = "[blocked by config] " + s.note;
                        s.ran = true; // render it, but never let it run
                    }
                    p.commands.add(s);
                }
            }
        } catch (Exception e) {
            LumiClient.LOG.warn("could not parse command block", e);
        }
        return p;
    }

    static String resolveExecutable() {
        Config cfg = Config.get();
        if (cfg.claudePath != null && !cfg.claudePath.isBlank()) {
            Path explicit = Path.of(cfg.claudePath);
            if (Files.isRegularFile(explicit)) return explicit.toString();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String home = System.getProperty("user.home", "");
        List<String> candidates = new ArrayList<>();
        if (windows) {
            candidates.add(home + "\\.local\\bin\\claude.exe");
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) candidates.add(local + "\\Programs\\claude\\claude.exe");
        } else {
            candidates.add(home + "/.local/bin/claude");
            candidates.add("/usr/local/bin/claude");
            candidates.add("/opt/homebrew/bin/claude");
        }
        for (String c : candidates) {
            try {
                if (Files.isRegularFile(Path.of(c))) return c;
            } catch (Exception ignored) {}
        }
        return windows ? "claude.exe" : "claude";
    }

    private static String text(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? fallback : e.getAsString();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
