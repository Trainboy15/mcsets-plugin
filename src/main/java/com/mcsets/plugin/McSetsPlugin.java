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

        int port = getConfig().getInt("webhook.port", 8080);
        String path = getConfig().getString("webhook.path", "/webhook");
        String secret = getConfig().getString("webhook.secret", "");

        webhookServer = new WebhookServer(this, port, path, secret);

        try {
            webhookServer.start();
            getLogger().info("Webhook server started on port " + port + " (path: " + path + ").");
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
        if (!command.getName().equalsIgnoreCase("mcsets")) {
            return false;
        }

        if (!sender.hasPermission("mcsets.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6McSets Webhook Plugin §7- §eUsage: /mcsets <reload|status>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (webhookServer != null) {
                    webhookServer.stop();
                }
                reloadConfig();
                int port = getConfig().getInt("webhook.port", 8080);
                String path = getConfig().getString("webhook.path", "/webhook");
                String secret = getConfig().getString("webhook.secret", "");
                webhookServer = new WebhookServer(this, port, path, secret);
                try {
                    webhookServer.start();
                    sender.sendMessage("§aWebhook server reloaded and listening on port " + port + ".");
                } catch (Exception e) {
                    sender.sendMessage("§cFailed to restart webhook server: " + e.getMessage());
                    getLogger().severe("Failed to restart webhook server: " + e.getMessage());
                }
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
