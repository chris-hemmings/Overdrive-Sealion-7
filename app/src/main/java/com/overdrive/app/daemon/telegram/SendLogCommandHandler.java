package com.overdrive.app.daemon.telegram;

import com.overdrive.app.logging.DaemonLogPaths;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Handles /sendlog — uploads a single daemon's log to the Cloudflare Worker
 * (via IPC UPLOAD_LOG → SurveillanceIpcServer → LogUploader) and replies with
 * the short retrieval code the user reads back to the maintainer.
 *
 * Braveheart-only in practice: on a plain release/alpha build LogUploader has
 * no Worker URL configured, so the server returns "not available in this build"
 * and we surface that cleanly.
 *
 *   /sendlog            → list the daemon keys + tap-to-send buttons
 *   /sendlog <daemon>   → upload that daemon's log, reply with the code
 */
public class SendLogCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19877;
    private static final String DISCORD_URL = "https://discord.gg/PZutk9fg4h";
    private static final String GITHUB_URL =
            "https://github.com/yash-srivastava/Overdrive-release/issues";
    private static final String WHATSAPP_URL =
            "https://chat.whatsapp.com/HChmriCWgr9KwAtE6OEkiM";
    // Upload is network-bound (read log → POST to CF). 35s sits above
    // LogUploader's bounded worst case (proxy 12s + direct-retry 12s = 24s,
    // via its callTimeout) so the IPC read never races a still-running upload.
    private static final int UPLOAD_TIMEOUT_MS = 35_000;

    @Override
    public boolean canHandle(String command) {
        return "/sendlog".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        if (args.length < 2 || args[1].trim().isEmpty()) {
            // No daemon specified — show the list as tap buttons.
            java.util.List<String[][]> rows = new java.util.ArrayList<>();
            for (String key : DaemonLogPaths.keys()) {
                rows.add(new String[][] {{ctx.tr("sendlog.daemon_button", key),
                        "cmd:/sendlog " + key}});
            }
            ctx.sendMessageWithButtons(chatId, ctx.tr("sendlog.choose"),
                    rows.toArray(new String[0][][]));
            return;
        }

        String daemon = args[1].trim().toLowerCase(Locale.ROOT);
        if (DaemonLogPaths.pathFor(daemon) == null) {
            ctx.sendMessage(chatId, ctx.tr("sendlog.unknown_daemon",
                    daemon, DaemonLogPaths.keyList()));
            return;
        }

        ctx.sendMessage(chatId, ctx.tr("sendlog.uploading", daemon));

        JSONObject req = new JSONObject();
        try {
            req.put("command", "UPLOAD_LOG");
            req.put("daemon", daemon);
        } catch (Exception ignored) {}

        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req, UPLOAD_TIMEOUT_MS);
        if (resp == null) {
            ctx.sendMessage(chatId, ctx.tr("sendlog.service_unreachable"));
            return;
        }
        if (!resp.optBoolean("success", false)) {
            ctx.sendMessage(chatId, ctx.tr("sendlog.upload_failed",
                    ctx.technicalDetail(resp.optString("error", ""))));
            return;
        }

        String code = resp.optString("code", "");
        ctx.sendMessage(chatId, ctx.tr("sendlog.upload_success", daemon, code,
                DISCORD_URL, GITHUB_URL, WHATSAPP_URL));
    }
}
