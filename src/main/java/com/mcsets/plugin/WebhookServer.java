package com.mcsets.plugin;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages the embedded HTTP server that receives incoming webhook requests.
 */
public class WebhookServer {

    private final McSetsPlugin plugin;
    private final int port;
    private final String path;
    private final String secret;

    private HttpServer httpServer;
    private ExecutorService executor;
    private boolean running;

    public WebhookServer(McSetsPlugin plugin, int port, String path, String secret) {
        this.plugin = plugin;
        this.port = port;
        this.path = path.startsWith("/") ? path : "/" + path;
        this.secret = secret == null ? "" : secret;
    }

    /**
     * Starts the HTTP server and registers the webhook handler.
     *
     * @throws IOException if the server cannot bind to the configured port.
     */
    public void start() throws IOException {
        executor = Executors.newCachedThreadPool();
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext(this.path, new WebhookHandler(plugin, secret));
        httpServer.setExecutor(executor);
        httpServer.start();
        running = true;
    }

    /**
     * Stops the HTTP server gracefully and shuts down the thread pool.
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        running = false;
    }

    /**
     * Returns {@code true} when the server is currently running.
     */
    public boolean isRunning() {
        return running;
    }
}
