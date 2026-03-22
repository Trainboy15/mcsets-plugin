package com.mcsets.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @BeforeEach
    void setUp() {
        McSetsPlugin mockPlugin = Mockito.mock(McSetsPlugin.class);
        Mockito.when(mockPlugin.getLogger()).thenReturn(Logger.getLogger("test"));
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
}
