package com.omeldar.lumi;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omeldar.lumi.mixin.ChatScreenAccessor;
import com.omeldar.lumi.ui.ApprovalScreen;
import com.omeldar.lumi.ui.PromptScreen;
import com.omeldar.lumi.ui.SettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

public class LumiClient implements ClientModInitializer {
    public static final String MOD_ID = "lumipet";
    public static final Logger LOG = LoggerFactory.getLogger("Lumi");

    private static Proposal lastProposal = null;

    public static Proposal lastProposal() {
        return lastProposal;
    }

    @Override
    public void onInitializeClient() {
        try {
            if (LegacyMigration.run(FabricLoader.getInstance().getConfigDir(),
                    FabricLoader.getInstance().getGameDir())) {
                LOG.info("moved settings and notes over from ClaudeMC");
            }
        } catch (Exception e) {
            LOG.warn("could not move settings and notes over from ClaudeMC", e);
        }
        Workspace.ensure();
        Config.get();
        ClaudeRunner.restoreSession();
        BridgeServer.start();
        Keybinds.register();

        ClientTickEvents.END_CLIENT_TICK.register(CommandQueue::tick);
        ClientTickEvents.END_CLIENT_TICK.register(LumiClient::stretchChatBox);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            LiteralCommandNode<FabricClientCommandSource> node = dispatcher.register(tree("lumi"));
            dispatcher.register(ClientCommandManager.literal("ai").redirect(node));
        });

        LOG.info("Lumi ready - workspace at {}", Workspace.root());
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> tree(String name) {
        return ClientCommandManager.literal(name)

            .then(ClientCommandManager.literal("new")
                .then(ClientCommandManager.argument("prompt", StringArgumentType.greedyString())
                    .executes(c -> {
                        ask(StringArgumentType.getString(c, "prompt"), true, false, 0);
                        return 1;
                    })))

            // Attachments, in either order, so a screenshot and a scan can go together.
            .then(ClientCommandManager.literal("shot")
                .then(ClientCommandManager.literal("scan")
                    .then(ClientCommandManager.argument("radius", IntegerArgumentType.integer(1, WorldScan.MAX_RADIUS))
                        .then(ClientCommandManager.argument("prompt", StringArgumentType.greedyString())
                            .executes(c -> {
                                ask(StringArgumentType.getString(c, "prompt"), false, true,
                                        IntegerArgumentType.getInteger(c, "radius"));
                                return 1;
                            }))))
                .then(ClientCommandManager.argument("prompt", StringArgumentType.greedyString())
                    .executes(c -> {
                        ask(StringArgumentType.getString(c, "prompt"), false, true, 0);
                        return 1;
                    })))

            .then(ClientCommandManager.literal("scan")
                .then(ClientCommandManager.argument("radius", IntegerArgumentType.integer(1, WorldScan.MAX_RADIUS))
                    .then(ClientCommandManager.literal("shot")
                        .then(ClientCommandManager.argument("prompt", StringArgumentType.greedyString())
                            .executes(c -> {
                                ask(StringArgumentType.getString(c, "prompt"), false, true,
                                        IntegerArgumentType.getInteger(c, "radius"));
                                return 1;
                            })))
                    .then(ClientCommandManager.argument("prompt", StringArgumentType.greedyString())
                        .executes(c -> {
                            ask(StringArgumentType.getString(c, "prompt"), false, false,
                                    IntegerArgumentType.getInteger(c, "radius"));
                            return 1;
                        }))))

            .then(ClientCommandManager.literal("model")
                .then(ClientCommandManager.argument("model", StringArgumentType.word())
                    .executes(c -> {
                        Config cfg = Config.get();
                        cfg.model = StringArgumentType.getString(c, "model");
                        cfg.save();
                        cfg.rememberModel(cfg.model);
                        cfg.save();
                        Chat.info("model set to " + cfg.model
                                + " (takes effect on the next message)");
                        return 1;
                    }))
                .executes(c -> {
                    Chat.dim("model is " + Config.get().model
                            + (ClaudeRunner.resolvedModel().isBlank() ? ""
                               : "  (last run as " + ClaudeRunner.resolvedModel() + ")"));
                    Chat.dim("known: " + String.join(", ", Config.get().models));
                    Chat.dim("any exact id works too, e.g. /lumi model claude-opus-5-5");
                    return 1;
                }))

            .then(ClientCommandManager.literal("effort")
                .then(ClientCommandManager.argument("level", StringArgumentType.word())
                    .executes(c -> {
                        String level = StringArgumentType.getString(c, "level")
                                .toLowerCase(Locale.ROOT);
                        if (!level.equals("low") && !level.equals("medium")
                                && !level.equals("high") && !level.equals("default")) {
                            Chat.warn("effort must be low, medium, high or default");
                            return 0;
                        }
                        Config cfg = Config.get();
                        cfg.effort = level.equals("default") ? "" : level;
                        cfg.save();
                        Chat.info("effort set to " + (cfg.effort.isBlank() ? "default" : cfg.effort));
                        return 1;
                    }))
                .executes(c -> {
                    String e = Config.get().effort;
                    Chat.dim("effort is " + (e == null || e.isBlank() ? "default" : e)
                            + "   try: low, medium, high, default");
                    return 1;
                }))

            .then(ClientCommandManager.literal("keys").executes(c -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                Keybinds.reportConflicts(mc, true);
                mc.execute(() -> Keybinds.openKeybindScreen(mc));
                return 1;
            }))

            .then(ClientCommandManager.literal("notes").executes(c -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                WorldIdentity.ensureNotes(mc);
                Chat.dim("notes for " + WorldIdentity.label(mc) + ":");
                Chat.dim("  " + WorldIdentity.notesFile(mc));
                return 1;
            }))

            .then(ClientCommandManager.literal("usage").executes(c -> {
                usage();
                return 1;
            }))

            .then(ClientCommandManager.literal("settings").executes(c -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> mc.setScreen(new SettingsScreen(mc.currentScreen)));
                return 1;
            }))

            .then(ClientCommandManager.literal("open").executes(c -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> mc.setScreen(new PromptScreen()));
                return 1;
            }))

            // Targets of the clickable [Run] / [Run all] links printed in chat.
            .then(ClientCommandManager.literal("run")
                .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(1, WorldScan.MAX_RADIUS))
                    .executes(c -> {
                        runOne(IntegerArgumentType.getInteger(c, "index"));
                        return 1;
                    })))

            .then(ClientCommandManager.literal("runall").executes(c -> {
                runAll();
                return 1;
            }))

            .then(ClientCommandManager.literal("last").executes(c -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (lastProposal == null) Chat.dim("no proposal yet");
                else mc.execute(() -> mc.setScreen(new ApprovalScreen(lastProposal)));
                return 1;
            }))

            .then(ClientCommandManager.literal("session").executes(c -> {
                String sid = ClaudeRunner.sessionId();
                Chat.dim(sid == null ? "no session yet - the next prompt starts one"
                                     : "session " + sid);
                return 1;
            }))

            .then(ClientCommandManager.literal("status").executes(c -> {
                status();
                return 1;
            }))

            .then(ClientCommandManager.literal("companions").executes(c -> {
                companions();
                return 1;
            }))

            .then(ClientCommandManager.literal("cancel").executes(c -> {
                CommandQueue.clear();
                boolean killed = ClaudeRunner.abort();
                Chat.dim("queue cleared" + (killed ? ", request aborted" : ""));
                return 1;
            }))

            .then(ClientCommandManager.literal("undo").executes(c -> {
                CommandQueue.enqueue("//undo");
                return 1;
            }))

            .then(ClientCommandManager.argument("prompt", StringArgumentType.greedyString())
                .executes(c -> {
                    ask(StringArgumentType.getString(c, "prompt"), false, false, 0);
                    return 1;
                }))

            .executes(c -> {
                help();
                return 1;
            });
    }

    /**
     * Vanilla caps the chat box at 256 characters, which cuts detailed prompts short. While the
     * line being typed is one of ours the cap is lifted - these are client commands, so nothing
     * oversized is ever sent to a server. The moment the text stops being ours it goes back.
     */
    private static void stretchChatBox(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof ChatScreen chat)) return;
        try {
            TextFieldWidget field = ((ChatScreenAccessor) chat).lumipet$chatField();
            if (field == null) return;
            String text = field.getText();
            boolean ours = text.startsWith("/lumi") || text.startsWith("/ai");
            field.setMaxLength(ours ? Math.max(256, Config.get().chatPromptLimit) : 256);
        } catch (Exception e) {
            // Never let a cosmetic tweak break chat.
            LumiClient.LOG.debug("could not adjust chat box length", e);
        }
    }

    private static void runOne(int oneBased) {
        if (lastProposal == null || oneBased > lastProposal.size()) {
            Chat.warn("no such command in the last proposal");
            return;
        }
        Proposal.Step step = lastProposal.commands.get(oneBased - 1);
        if (step.ran) {
            Chat.dim("already sent: " + step.cmd);
            return;
        }
        CommandQueue.enqueue(step.cmd);
        step.ran = true;
    }

    private static void runAll() {
        if (lastProposal == null) {
            Chat.warn("no proposal to run");
            return;
        }
        int queued = 0;
        for (Proposal.Step s : lastProposal.commands) {
            if (s.ran || Config.get().isBlocked(s.cmd)) continue;
            CommandQueue.enqueue(s.cmd);
            s.ran = true;
            queued++;
        }
        Chat.dim("queued " + queued + " command" + (queued == 1 ? "" : "s"));
    }

    private static void help() {
        Chat.info(Text.literal("Lumi").formatted(Formatting.GOLD, Formatting.BOLD));
        Chat.dim("/lumi <prompt>               ask, keeping the session");
        Chat.dim("/lumi new <prompt>           start a fresh session");
        Chat.dim("/lumi shot <prompt>          attach your newest screenshot (F2 first)");
        Chat.dim("/lumi scan <r> <prompt>      attach a block scan of radius r");
        Chat.dim("/lumi shot scan <r> <p>      attach both (either order works)");
        Chat.dim("/lumi model <name>           opus, sonnet, haiku, fable, or a full id");
        Chat.dim("/lumi effort <level>         low, medium, high, default");
        Chat.dim("/lumi open                   the prompt screen, with toggles");
        Chat.dim("/lumi settings               model, effort and the rest");
        Chat.dim("/lumi usage                  quota windows and what this session cost");
        Chat.dim("/lumi keys                   bindings, conflicts, and rebinding");
        Chat.dim("/lumi notes                  where this world's notes live");
        Chat.dim("/lumi last                   reopen the approval menu");
        Chat.dim("/lumi companions             other mods answering through this bridge");
        Chat.dim("/lumi undo | cancel | status");
        Chat.dim("Keys: Options - Controls - Key Binds - Lumi, or the cog in Mod Menu.");
    }

    /**
     * What else in the pack is reachable through Lumi's bridge. Worth a command of its own:
     * when a session says it cannot start a farm, the first question is whether the mod that
     * does farms is actually answering.
     */
    private static void companions() {
        Chat.info(Text.literal("Lumi Link").formatted(Formatting.GOLD));
        var found = CompanionRegistry.known();
        if (found.isEmpty()) {
            Chat.dim("no companion mods found in " + CompanionRegistry.dir());
            Chat.dim("a companion writes its own descriptor there on startup");
            return;
        }
        for (var c : found) {
            Chat.dim((c.name == null || c.name.isBlank() ? c.prefix : c.name)
                    + "  " + c.version + "  tools: " + c.prefix + "_*");
            Chat.dim("  " + c.mcpUrl + (c.alive ? "  ok" : "  not answering"
                    + (c.lastError == null || c.lastError.isBlank() ? "" : " - " + c.lastError)));
        }
    }

    private static void status() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Config cfg = Config.get();
        Chat.info(Text.literal("Lumi status").formatted(Formatting.GOLD));
        Chat.dim("model        " + cfg.model
                + (ClaudeRunner.resolvedModel().isBlank() ? ""
                   : " -> " + ClaudeRunner.resolvedModel())
                + (cfg.effort == null || cfg.effort.isBlank() ? "" : "   effort " + cfg.effort));
        Chat.dim("session      " + Optional.ofNullable(ClaudeRunner.sessionId()).orElse("none yet"));
        Chat.dim("claude       " + ClaudeRunner.resolveExecutable());
        Chat.dim("streaming    " + (cfg.streaming ? "on" : "off"));
        Chat.dim("queue        " + CommandQueue.pending() + " pending");
        Chat.dim("bridge       " + (cfg.bridgeEnabled ? "127.0.0.1:" + cfg.bridgePort : "off"));
        Chat.dim("workspace    " + Workspace.root());
        if (ClaudeRunner.billedToKey()) {
            Chat.info(Text.empty().append(Chat.warnBadge())
                    .append(Text.literal(" billing      API KEY (" + ClaudeRunner.apiKeySource()
                            + ") - prompts are charged per token").formatted(Formatting.RED)));
        } else if (cfg.allowApiKey) {
            Chat.dim("billing      API key allowed, but none used yet");
        } else if (ClaudeRunner.keyPresentInEnvironment()) {
            Chat.dim("billing      subscription (an API key is set but is being ignored)");
        } else {
            Chat.dim("billing      subscription");
        }
        if (Usage.hasLimits()) Chat.dim("quota        " + Usage.compact());
        if (CommandPermissions.allowed(mc)) {
            Chat.info(Text.literal("commands     allowed (" + CommandPermissions.authority(mc) + ")")
                    .formatted(Formatting.GREEN));
        } else {
            Chat.info(Text.literal("commands     READ-ONLY (" + CommandPermissions.authority(mc) + ")")
                    .formatted(Formatting.RED));
            Chat.dim(CommandPermissions.explain(mc));
        }
    }

    private static void usage() {
        if (!Usage.hasLimits() && !Usage.hasSpend()) {
            Chat.dim("nothing recorded yet - ask something first");
            return;
        }
        Chat.info(Text.literal("Usage").formatted(Formatting.GOLD));

        if (Usage.hasLimits()) {
            for (Usage.Window w : Usage.windows().values()) {
                Formatting colour = !w.isHealthy() ? Formatting.RED
                        : w.utilization() >= 0.8 ? Formatting.RED
                        : w.utilization() >= 0.5 ? Formatting.YELLOW
                        : Formatting.GREEN;
                String resets = w.resetsIn();
                Chat.info(Text.literal(String.format("%-11s", w.shortLabel()))
                                .formatted(Formatting.GRAY)
                        .append(Text.literal(Usage.bar(w.utilization())).formatted(colour))
                        .append(Text.literal(String.format(" %3d%%", w.percent()))
                                .formatted(colour))
                        .append(Text.literal(resets.isEmpty() ? "" : "  resets in " + resets)
                                .formatted(Formatting.DARK_GRAY)));
            }
            if (Usage.usingOverage()) Chat.warn("currently drawing on overage");
        } else {
            Chat.dim("no quota information yet - it arrives with the next reply");
        }

        if (Usage.hasSpend()) {
            Chat.dim("this session  " + Usage.messages() + " message"
                    + (Usage.messages() == 1 ? "" : "s")
                    + ", " + Usage.turns() + " turns, "
                    + String.format("%.1fs", Usage.totalMillis() / 1000.0));
            Chat.dim("tokens        in " + Usage.tokens(Usage.inputTokens())
                    + "  out " + Usage.tokens(Usage.outputTokens())
                    + "  cache read " + Usage.tokens(Usage.cacheReadTokens())
                    + "  written " + Usage.tokens(Usage.cacheWriteTokens()));
            Chat.dim(String.format("equivalent    $%.3f at list price"
                    + " (subscription use comes out of the windows above)", Usage.costUsd()));
        }
    }

    /** Builds the prompt with whatever context was asked for, then hands it to the CLI. */
    public static void ask(String prompt, boolean freshSession, boolean withShot, int scanRadius) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (ClaudeRunner.isBusy()) {
            Chat.warn("still waiting on the last reply - /lumi cancel to drop it");
            return;
        }

        Workspace.ensure();
        ContextSnapshot.write(mc);

        StringBuilder full = new StringBuilder(prompt.trim());
        full.append("\n\n---\nLive game context is in context.json in your working directory; "
                  + "it was just rewritten for this message.");

        if (withShot) {
            Path copied = attachNewestScreenshot();
            if (copied == null) {
                Chat.warn("no recent screenshot found - press F2 first, then ask again");
                return;
            }
            full.append("\nThe player attached a screenshot: shots/")
                .append(copied.getFileName())
                .append(" - read it, it shows what they are looking at.");
            Chat.dim("attached " + copied.getFileName());
        }

        if (scanRadius > 0) {
            boolean limited = BlockSight.limited(mc);
            if (limited) {
                Chat.dim("on this server, scans only include blocks you can see, up to "
                        + BlockSight.LIMITED_SCAN_RADIUS + " blocks away");
            } else if (scanRadius > 96) {
                Chat.dim("scanning r=" + scanRadius + ", this takes a moment");
            }
            Path scan = WorldScan.dump(mc, scanRadius);
            if (scan != null) {
                full.append("\nA block scan of the area is at scans/")
                    .append(scan.getFileName())
                    .append(" - read it for the blocks actually around the player.");
                Chat.dim("attached " + scan.getFileName() + " (r=" + (limited ? Math.min(scanRadius, BlockSight.LIMITED_SCAN_RADIUS) : scanRadius) + ")");
            }
        }

        String notice = CommandPermissions.promptNotice(mc);
        if (!notice.isEmpty()) full.append('\n').append(notice);

        if (freshSession) {
            ClaudeRunner.newSession();
            Chat.dim("starting a new session");
        }
        if (!CommandPermissions.allowed(mc)) {
            Chat.dim("read-only here - answering with advice only");
        }

        Chat.overlay("thinking...");
        ClaudeRunner.ask(full.toString(),
            progress -> {
                Chat.overlay(progress);
                if (Config.get().showProgressInChat) Chat.dim(progress);
            },
            proposal -> mc.execute(() -> deliver(mc, proposal)),
            error -> {
                Chat.warn(error);
                Chat.overlay("failed");
            });
    }

    /** Said once per game session, so the player knows their key is deliberately ignored. */
    private static boolean toldAboutIgnoredKey = false;

    private static void deliver(MinecraftClient mc, Proposal proposal) {
        lastProposal = proposal;

        if (ClaudeRunner.billedToKey()) {
            Chat.info(Text.empty().append(Chat.warnBadge())
                    .append(Text.literal(" that reply ran on an API key and was billed ("
                            + ClaudeRunner.apiKeySource() + "). Turn it off in /lumi settings.")
                            .formatted(Formatting.RED)));
        } else if (ClaudeRunner.keyStripped() && !toldAboutIgnoredKey) {
            toldAboutIgnoredKey = true;
            Chat.dim("an API key is set in your environment; it was ignored and your "
                   + "subscription was used. Enable it in /lumi settings if you want billing.");
        }

        if (proposal.prose != null && !proposal.prose.isBlank()) {
            for (String line : proposal.prose.split("\n")) {
                String plain = PlainText.line(line);
                if (!plain.isBlank()) Chat.info(Text.literal(plain));
            }
        }

        if (proposal.isEmpty()) {
            Chat.overlay("answered" + (Usage.hasLimits() ? "   " + Usage.compact() : ""));
            warnIfTight();
            return;
        }

        if (!CommandPermissions.allowed(mc)) {
            Chat.warn("Lumi proposed " + proposal.size()
                    + " command(s) but none can run here. " + CommandPermissions.explain(mc));
            Chat.overlay("read-only");
            return;
        }

        Chat.proposal(proposal);
        Chat.overlay(proposal.size() + " command" + (proposal.size() == 1 ? "" : "s") + " ready"
                + (Usage.hasLimits() ? "   " + Usage.compact() : ""));
        warnIfTight();
    }

    /** One nudge per threshold crossing, not one per message. */
    private static double lastWarnedAt = 0;

    private static void warnIfTight() {
        double peak = Usage.peakUtilization();
        if (peak >= 0.9 && lastWarnedAt < 0.9) {
            Chat.warn("quota is at " + Math.round(peak * 100) + "% - " + Usage.compact()
                    + ". /lumi usage for the detail.");
            lastWarnedAt = 0.9;
        } else if (peak >= 0.8 && lastWarnedAt < 0.8) {
            Chat.dim("heads up: quota at " + Math.round(peak * 100) + "% - " + Usage.compact());
            lastWarnedAt = 0.8;
        } else if (peak < 0.75) {
            lastWarnedAt = 0;   // a window rolled over
        }
    }

    /** Copies the newest file from the screenshots folder into the workspace. */
    private static Path attachNewestScreenshot() {
        try {
            File dir = FabricLoader.getInstance().getGameDir().resolve("screenshots").toFile();
            File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
            if (files == null || files.length == 0) return null;
            File newest = Arrays.stream(files)
                    .max(Comparator.comparingLong(File::lastModified))
                    .orElse(null);
            if (newest == null) return null;
            if (System.currentTimeMillis() - newest.lastModified() > 10 * 60 * 1000L) return null;

            String name = "shot-" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
            Path target = Workspace.shots().resolve(name);
            Files.copy(newest.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (Exception e) {
            LOG.warn("could not attach screenshot", e);
            return null;
        }
    }
}
