package com.overdrive.app.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TelegramCommandLocalizationTest {

    private static final long CHAT_ID = 123456L;

    @Test
    public void unknownCommandUsesLocalizedRouterMessage() {
        FakeCommandContext context = new FakeCommandContext();

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/not-a-command"));

        assertEquals(Arrays.asList("router.unknown_command"), context.translationKeys());
        assertEquals(1, context.textMessages.size());
        assertEquals(CHAT_ID, context.textMessages.get(0).chatId);
        assertEquals("tr:router.unknown_command", context.textMessages.get(0).text);
    }

    @Test
    public void helpUsesLocalizedTextAndEveryLocalizedButtonLabel() {
        FakeCommandContext context = new FakeCommandContext();

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/help"));

        assertEquals(Arrays.asList(
                "help.text",
                "buttons.status",
                "buttons.events",
                "buttons.start_surveillance",
                "buttons.stop_surveillance",
                "buttons.daemons",
                "buttons.tunnel_url",
                "buttons.check_update",
                "buttons.backup"
        ), context.translationKeys());

        TranslationCall help = context.translationCalls.get(0);
        assertEquals("help.text", help.key);
        assertEquals(1, help.args.length);
        assertNotNull(help.args[0]);

        assertEquals(1, context.buttonMessages.size());
        ButtonMessage sent = context.buttonMessages.get(0);
        assertEquals(CHAT_ID, sent.chatId);
        assertEquals("tr:help.text", sent.text);
        assertButton(sent.buttons, 0, 0, "buttons.status", "cmd:/status");
        assertButton(sent.buttons, 0, 1, "buttons.events", "cmd:/events");
        assertButton(sent.buttons, 1, 0, "buttons.start_surveillance", "cmd:/start");
        assertButton(sent.buttons, 1, 1, "buttons.stop_surveillance", "cmd:/stop");
        assertButton(sent.buttons, 2, 0, "buttons.daemons", "cmd:/daemons");
        assertButton(sent.buttons, 2, 1, "buttons.tunnel_url", "cmd:/url");
        assertButton(sent.buttons, 3, 0, "buttons.check_update", "cmd:/update");
        assertButton(sent.buttons, 3, 1, "buttons.backup", "cmd:/backup");
    }

    @Test
    public void startSuccessUsesLocalizedMessageAndButtons() {
        FakeCommandContext context = new FakeCommandContext();
        context.ipcResponse = jsonResponse(true);

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/start"));

        assertEquals(Arrays.asList(
                "buttons.stop",
                "buttons.status",
                "surveillance.started"
        ), context.translationKeys());
        assertEquals(0, context.textMessages.size());
        assertEquals(1, context.buttonMessages.size());

        ButtonMessage sent = context.buttonMessages.get(0);
        assertEquals("tr:surveillance.started", sent.text);
        assertButton(sent.buttons, 0, 0, "buttons.stop", "cmd:/stop");
        assertButton(sent.buttons, 0, 1, "buttons.status", "cmd:/status");
    }

    @Test
    public void startFailureUsesLocalizedFailureMessage() {
        FakeCommandContext context = new FakeCommandContext();
        context.ipcResponse = jsonResponse(false);

        assertTrue(new CommandRouter(context).route(CHAT_ID, "/start"));

        assertEquals(Arrays.asList("surveillance.start_failed"), context.translationKeys());
        assertEquals(1, context.textMessages.size());
        assertEquals("tr:surveillance.start_failed", context.textMessages.get(0).text);
        assertEquals(0, context.buttonMessages.size());
    }

    private static JSONObject jsonResponse(boolean success) {
        try {
            return new JSONObject().put("success", success);
        } catch (Exception e) {
            throw new AssertionError("Could not build test IPC response", e);
        }
    }

    private static void assertButton(String[][][] buttons, int row, int column,
                                     String translationKey, String callbackData) {
        assertEquals("tr:" + translationKey, buttons[row][column][0]);
        assertEquals(callbackData, buttons[row][column][1]);
    }

    private static final class FakeCommandContext implements CommandContext {
        final List<TranslationCall> translationCalls = new ArrayList<>();
        final List<TextMessage> textMessages = new ArrayList<>();
        final List<ButtonMessage> buttonMessages = new ArrayList<>();
        JSONObject ipcResponse;

        @Override
        public String tr(String key, Object... args) {
            Object[] capturedArgs = args == null ? new Object[0] : args.clone();
            translationCalls.add(new TranslationCall(key, capturedArgs));
            return "tr:" + key;
        }

        List<String> translationKeys() {
            List<String> keys = new ArrayList<>();
            for (TranslationCall call : translationCalls) keys.add(call.key);
            return keys;
        }

        @Override
        public boolean sendMessage(long chatId, String text) {
            textMessages.add(new TextMessage(chatId, text));
            return true;
        }

        @Override
        public boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) {
            buttonMessages.add(new ButtonMessage(chatId, text, buttons));
            return true;
        }

        @Override
        public boolean sendVideo(long chatId, String videoPath, String caption) {
            return true;
        }

        @Override
        public JSONObject sendIpcCommand(int port, JSONObject command) {
            return ipcResponse;
        }

        @Override
        public String execShell(String command) {
            return "";
        }

        @Override
        public void log(String message) {
            // No-op for pure JVM tests.
        }
    }

    private static final class TranslationCall {
        final String key;
        final Object[] args;

        TranslationCall(String key, Object[] args) {
            this.key = key;
            this.args = args;
        }
    }

    private static final class TextMessage {
        final long chatId;
        final String text;

        TextMessage(long chatId, String text) {
            this.chatId = chatId;
            this.text = text;
        }
    }

    private static final class ButtonMessage {
        final long chatId;
        final String text;
        final String[][][] buttons;

        ButtonMessage(long chatId, String text, String[][][] buttons) {
            this.chatId = chatId;
            this.text = text;
            this.buttons = buttons;
        }
    }
}
