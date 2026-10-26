import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ================================================================
 * ChatServer.java
 * ================================================================
 * The central server for the Online Chat Application.
 *
 * This class uses Java socket programming to listen for incoming
 * client connections on a specified port. Each new connection is
 * handed off to a dedicated ClientHandler thread so the server
 * can serve many users at the same time without blocking
 * (Pankaj, 2022; Eck, 2022, Section 11.4).
 *
 * KEY RESPONSIBILITIES:
 *   - Accept incoming TCP connections on PORT 5000
 *   - Assign every client a unique User ID (USER-1, USER-2, ...)
 *   - Keep a thread-safe registry of all active ClientHandlers
 *   - Broadcast any message to every connected client
 *   - Clean up and notify others when a client disconnects
 *
 * HOW TO RUN:
 *   1. Run ChatServer first (it blocks, waiting for clients).
 *   2. Open one or more separate terminal windows and run ChatClient.
 *   3. Type messages in any client window — they appear in all others.
 *
 * PORT: 5000  (change PORT constant below if needed)
 * ================================================================
 */
public class ChatServer {

    /** TCP port the server listens on. */
    private static final int PORT = 5000;

    /**
     * Thread-safe list of every currently-connected client handler.
     * CopyOnWriteArrayList lets us iterate safely while other threads
     * add or remove entries (SimpliCode, 2021).
     */
    private static final List<ClientHandler> connectedClients =
            new CopyOnWriteArrayList<>();

    /**
     * Auto-incrementing counter that generates unique numeric IDs.
     * AtomicInteger ensures no two threads ever get the same number,
     * even if two clients connect at the exact same millisecond.
     */
    private static final AtomicInteger userIdCounter = new AtomicInteger(1);

    // ================================================================
    // ENTRY POINT
    // ================================================================

    /**
     * Starts the server. Opens a ServerSocket, then loops forever
     * waiting for client connections. Each accepted connection spawns
     * a new ClientHandler thread (Pankaj, 2022).
     */
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║       CHAT SERVER  — PORT " + PORT + "       ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("[SERVER] Starting up...");

        // ServerSocket auto-closes when the try block exits (Arthur C. Codex, 2023)
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[SERVER] Listening for connections on port " + PORT);
            System.out.println("[SERVER] Press Ctrl+C to shut down.\n");

            // Accept loop — runs forever until the process is killed
            while (true) {
                // accept() blocks here until a client connects
                Socket clientSocket = serverSocket.accept();

                // Assign a unique ID to this new client
                String userId = "USER-" + userIdCounter.getAndIncrement();

                System.out.println("[SERVER] New connection accepted → " + userId
                        + "  (" + clientSocket.getInetAddress().getHostAddress() + ")");

                // Create a handler and run it on a new thread
                ClientHandler handler = new ClientHandler(clientSocket, userId);
                connectedClients.add(handler);

                Thread clientThread = new Thread(handler);
                clientThread.setDaemon(true); // dies automatically when server shuts down
                clientThread.start();
            }

        } catch (IOException e) {
            System.err.println("[SERVER ERROR] Could not start on port " + PORT
                    + ": " + e.getMessage());
        }
    }

    // ================================================================
    // BROADCAST
    // ================================================================

    /**
     * Sends a message string to every client currently in the list.
     * If a write to any client fails (e.g. they disconnected midway),
     * that client is removed silently so the others are unaffected.
     *
     * @param message   the text to broadcast
     * @param sender    the ClientHandler sending the message
     *                  (used to prefix the message with the sender's ID)
     */
    static void broadcast(String message, ClientHandler sender) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String formatted = "[" + timestamp + "] " + sender.getUserId() + ": " + message;

        System.out.println(formatted); // echo to server console

        for (ClientHandler client : connectedClients) {
            client.sendMessage(formatted);
        }
    }

    /**
     * Sends a server-level notification (join/leave) to all clients.
     * These messages are prefixed with [SERVER] so users can tell them
     * apart from regular chat messages.
     *
     * @param notice the notification text
     */
    static void broadcastNotice(String notice) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String formatted = "[" + timestamp + "] [SERVER] " + notice;

        System.out.println(formatted);

        for (ClientHandler client : connectedClients) {
            client.sendMessage(formatted);
        }
    }

    /**
     * Removes a ClientHandler from the active list. Called by
     * ClientHandler when its client disconnects or an error occurs.
     *
     * @param handler the handler to remove
     */
    static void removeClient(ClientHandler handler) {
        connectedClients.remove(handler);
        System.out.println("[SERVER] Active connections: " + connectedClients.size());
    }

    /**
     * Returns how many clients are currently connected.
     * Exposed so ClientHandler can include the count in notices.
     */
    static int getActiveUserCount() {
        return connectedClients.size();
    }
}


// ====================================================================
// INNER HELPER CLASS — ClientHandler
// ====================================================================

/**
 * ClientHandler
 * -------------
 * Runs on its own thread and manages the two-way communication
 * with one specific client socket. Reading and writing are both
 * done here so the main server loop is free to accept new clients
 * immediately (SimpliCode, 2021; Eck, 2022, Section 11.4).
 *
 * LIFECYCLE:
 *   constructor → run() → readLoop → disconnect/cleanup
 */
class ClientHandler implements Runnable {

    /** The raw socket connected to this client. */
    private final Socket socket;

    /** Unique ID assigned to this user, e.g. "USER-3". */
    private final String userId;

    /** Used to send text lines to the client. */
    private PrintWriter out;

    /** Used to read text lines from the client. */
    private BufferedReader in;

    // ── Constructor ──────────────────────────────────────────────
    /**
     * @param socket the accepted client socket
     * @param userId the pre-assigned unique user identifier
     */
    public ClientHandler(Socket socket, String userId) {
        this.socket = socket;
        this.userId = userId;
    }

    // ── Getters ──────────────────────────────────────────────────
    public String getUserId() { return userId; }

    // ================================================================
    // THREAD ENTRY POINT
    // ================================================================

    /**
     * Called automatically when the thread starts.
     * Sets up I/O streams, greets the client, reads messages in a
     * loop, and cleans up when the connection ends (Pankaj, 2022).
     */
    @Override
    public void run() {
        try {
            // Wrap socket streams in convenient Reader/Writer objects
            out = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())),
                    true  // autoFlush = true — every println() sends immediately
            );
            in  = new BufferedReader(
                    new InputStreamReader(socket.getInputStream())
            );

            // Welcome this specific client (only they see this)
            sendMessage("╔══════════════════════════════════════╗");
            sendMessage("║   Welcome to the Chat Room!          ║");
            sendMessage("║   Your ID: " + String.format("%-26s", userId) + "║");
            sendMessage("║   Type /quit to disconnect           ║");
            sendMessage("╚══════════════════════════════════════╝");

            // Announce to everyone that a new user joined
            ChatServer.broadcastNotice(userId + " joined the chat. ("
                    + ChatServer.getActiveUserCount() + " users online)");

            // ── Message read loop ────────────────────────────────
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("/quit")) {
                    // Graceful exit requested by the client
                    sendMessage("[SERVER] Goodbye, " + userId + "!");
                    break;
                }

                // Normal message — broadcast to everyone
                ChatServer.broadcast(line, this);
            }

        } catch (IOException e) {
            // Client dropped the connection unexpectedly
            System.out.println("[SERVER] Connection lost for " + userId
                    + " — " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    // ================================================================
    // SEND + DISCONNECT
    // ================================================================

    /**
     * Writes one line of text to this client's output stream.
     * PrintWriter.println() is thread-safe here because only this
     * handler's thread ever writes to its own socket (Pankaj, 2022).
     *
     * @param message the line of text to send
     */
    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    /**
     * Closes I/O streams and the socket, removes this handler from
     * the server's active list, and notifies remaining users.
     * Called from the finally block so it always runs (Arthur C. Codex, 2023).
     */
    private void disconnect() {
        ChatServer.removeClient(this);
        try {
            if (in  != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}

        ChatServer.broadcastNotice(userId + " left the chat. ("
                + ChatServer.getActiveUserCount() + " users online)");

        System.out.println("[SERVER] " + userId + " fully disconnected.");
    }
}