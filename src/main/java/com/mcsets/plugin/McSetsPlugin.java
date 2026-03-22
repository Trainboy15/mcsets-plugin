package com.mcsets.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class for McSets Webhook Plugin.
 *
 * <p>On enable, starts an embedded HTTP server that listens for incoming
 * webhook POST requests.  Each request payload may contain one or more
 * Minecraft console commands that are dispatched on the server's main
 * thread.</p>
 */
public final class McSetsPlugin extends JavaPlugin {

    private WebhookServer webhookServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        restartWebhookServer();
    }

    private void restartWebhookServer() {
        if (webhookServer != null) {
            webhookServer.stop();
        }

        int port = getConfig().getInt("webhook.port", 8080);
        String path = getConfig().getString("webhook.path", "/webhook");
        String secret = getConfig().getString("webhook.secret", "");
        boolean debugMode = getConfig().getBoolean("webhook.debug", false);

        webhookServer = new WebhookServer(this, port, path, secret, debugMode);

        try {
            webhookServer.start();
            getLogger().info("Webhook server started on port " + port + " (path: " + path + ").");
            if (debugMode) {
                getLogger().info("Webhook debug mode is enabled; raw request bodies will be logged.");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to start webhook server: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (webhookServer != null) {
            webhookServer.stop();
            getLogger().info("Webhook server stopped.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName();
        boolean isMcsetsCommand = commandName.equalsIgnoreCase("mcsets");
        boolean isReloadCommand = commandName.equalsIgnoreCase("reload");

        if (!isMcsetsCommand && !isReloadCommand) {
            return false;
        }

        if (!sender.hasPermission("mcsets.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (isReloadCommand) {
            reloadConfig();
            restartWebhookServer();
            sender.sendMessage("§aMcSets webhook config reloaded.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6McSets Webhook Plugin §7- §eUsage: /mcsets <reload|status>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                restartWebhookServer();
                sender.sendMessage("§aWebhook server reloaded.");
            }
            case "status" -> {
                boolean running = webhookServer != null && webhookServer.isRunning();
                sender.sendMessage("§6McSets §7» Webhook server is " +
                        (running ? "§arunning" : "§cstopped") + "§7.");
            }
            default -> sender.sendMessage("§cUnknown sub-command. Usage: /mcsets <reload|status>");
        }

        return true;
    }
}
