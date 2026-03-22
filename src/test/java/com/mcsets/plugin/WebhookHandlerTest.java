package com.mcsets.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.FileConfiguration;
import org.mockito.Mockito;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WebhookHandler#parseCommands(String)}.
 *
 * <p>These tests exercise JSON parsing in isolation without starting a real
 * Bukkit server or HTTP server.</p>
 */
class WebhookHandlerTest {

    private WebhookHandler handler;
    private McSetsPlugin mockPlugin;
    private FileConfiguration mockConfig;

    @BeforeEach
    void setUp() {
        mockPlugin = Mockito.mock(McSetsPlugin.class);
        mockConfig = Mockito.mock(FileConfiguration.class);
        Mockito.when(mockPlugin.getLogger()).thenReturn(Logger.getLogger("test"));
        Mockito.when(mockPlugin.getConfig()).thenReturn(mockConfig);
        Mockito.when(mockConfig.getStringList("webhook.textPayload.commands")).thenReturn(List.of());
        Mockito.when(mockConfig.getString("webhook.textPayload.command", "")).thenReturn("");
        Mockito.when(mockConfig.getMapList("webhook.textPayload.rules")).thenReturn(List.of());
        handler = new WebhookHandler(mockPlugin, "");
    }

    // ── single-command payloads ──────────────────────────────────────────────

    @Test
    void parseSingleCommand() {
        List<String> cmds = handler.parseCommands("{\"command\":\"say hello\"}");
        assertEquals(1, cmds.size());
        assertEquals("say hello", cmds.get(0));
    }

    @Test
    void parseSingleCommandTrimsWhitespace() {
        List<String> cmds = handler.parseCommands("{\"command\":\"  give Steve diamond 1  \"}");
        assertEquals(1, cmds.size());
        assertEquals("give Steve diamond 1", cmds.get(0));
    }

    // ── multi-command payloads ───────────────────────────────────────────────

    @Test
    void parseMultipleCommands() {
        List<String> cmds = handler.parseCommands(
                "{\"commands\":[\"say hello\",\"time set day\"]}");
        assertEquals(2, cmds.size());
        assertEquals("say hello", cmds.get(0));
        assertEquals("time set day", cmds.get(1));
    }

    @Test
    void parseEmptyCommandsArray() {
        List<String> cmds = handler.parseCommands("{\"commands\":[]}");
        assertTrue(cmds.isEmpty());
    }

    // ── "commands" takes precedence over "command" when both are present ─────

    @Test
    void commandsKeyTakesPrecedenceOverCommandKey() {
        List<String> cmds = handler.parseCommands(
                "{\"command\":\"ignored\",\"commands\":[\"say hi\"]}");
        assertEquals(1, cmds.size());
        assertEquals("say hi", cmds.get(0));
    }

    // ── error cases ──────────────────────────────────────────────────────────

    @Test
    void throwsOnInvalidJson() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.parseCommands("not-json"));
    }

    @Test
    void throwsWhenNeitherKeyPresent() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> handler.parseCommands("{\"other\":\"value\"}"));
        assertTrue(ex.getMessage().contains("command"));
    }

    @Test
    void throwsWhenCommandIsNotAString() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.parseCommands("{\"command\":123}"));
    }

    @Test
    void throwsWhenCommandsIsNotAnArray() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.parseCommands("{\"commands\":\"say hello\"}"));
    }

    @Test
    void throwsWhenCommandsContainsNonString() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.parseCommands("{\"commands\":[1,2,3]}"));
    }

    @Test
    void throwsWhenRootIsNotObject() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.parseCommands("[\"say hello\"]"));
    }

    // ── plain-text purchase payload support ─────────────────────────────────

    @Test
    void parsePlainTextPayloadWithDefaultTemplate() {
        String body = "SkyFrameSMP - Purchase Completed\n"
                + "Player: TrainBoy888\n"
                + "Package(s): Bronze Rank\n"
                + "Amount: £0.00\n"
                + "Currency: GBP\n"
                + "Payment ID: free_123";

        List<String> cmds = handler.parseCommands(body);
        assertEquals(1, cmds.size());
        assertEquals("say Purchase completed for TrainBoy888: Bronze Rank (£0.00 GBP)", cmds.get(0));
    }

    @Test
    void parsePlainTextPayloadWithConfiguredTemplate() {
        Mockito.when(mockConfig.getStringList("webhook.textPayload.commands"))
                .thenReturn(List.of("say {player}", "say {packages}", "say {payment_id}"));

        String body = "Store - Purchase Completed\n"
                + "Player: TrainBoy888\n"
                + "Package(s): Bronze Rank\n"
                + "Payment ID: free_350b0ffb";

        List<String> cmds = handler.parseCommands(body);
        assertEquals(3, cmds.size());
        assertEquals("say TrainBoy888", cmds.get(0));
        assertEquals("say Bronze Rank", cmds.get(1));
        assertEquals("say free_350b0ffb", cmds.get(2));
    }

        @Test
        void parsePlainTextPayloadUsesMatchingRules() {
        Mockito.when(mockConfig.getMapList("webhook.textPayload.rules")).thenReturn(List.of(
            java.util.Map.of(
                "when", java.util.Map.of("packages_contains", "Bronze"),
                "commands", List.of("say matched {player}", "say {packages}")
            )
        ));

        String body = "Store - Purchase Completed\n"
            + "Player: TrainBoy888\n"
            + "Package(s): Bronze Rank\n"
            + "Payment ID: free_350b0ffb";

        List<String> cmds = handler.parseCommands(body);
        assertEquals(2, cmds.size());
        assertEquals("say matched TrainBoy888", cmds.get(0));
        assertEquals("say Bronze Rank", cmds.get(1));
        }

        @Test
        void parsePlainTextPayloadFallsBackWhenRulesDoNotMatch() {
        Mockito.when(mockConfig.getMapList("webhook.textPayload.rules")).thenReturn(List.of(
            java.util.Map.of(
                "when", java.util.Map.of("packages_contains", "Gold"),
                "commands", List.of("say should not run")
            )
        ));

        String body = "Store - Purchase Completed\n"
            + "Player: TrainBoy888\n"
            + "Package(s): Bronze Rank\n"
            + "Amount: £0.00\n"
            + "Currency: GBP";

        List<String> cmds = handler.parseCommands(body);
        assertEquals(1, cmds.size());
        assertEquals("say Purchase completed for TrainBoy888: Bronze Rank (£0.00 GBP)", cmds.get(0));
        }
}
