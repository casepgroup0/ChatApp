import java.io.*;
import java.net.*;
import java.util.Scanner;

/**
 * ================================================================
 * ChatClient.java
 * ================================================================
 * The client side of the Online Chat Application.
 *
 * This class connects to ChatServer using a TCP socket and then
 * runs two concurrent tasks:
 *   1. A background thread that continuously listens for incoming
 *      messages from the server and prints them to the console.
 *   2. The main thread that reads the user's keyboard input and
 *      sends each line to the server.
 *
 * This split design means the user can type and receive messages
 * simultaneously without one blocking the other — a classic pattern
 * in socket programming (SimpliCode, 2021; Eck, 2022, Section 11.4).
 *
 * HOW TO RUN:
 *   1. Start ChatServer first in one terminal window.
 *   2. Open a NEW terminal window and run: java ChatClient
 *   3. Repeat step 2 for each additional user you want to simulate.
 *   4. Type messages and press Enter to send.
 *   5. Type /quit and press Enter to disconnect gracefully.
 *
 * SERVER ADDRESS: localhost (127.0.0.1)
 * PORT:           5000
 * ================================================================
 */
public class ChatClient {

    /** Address of the server to connect to. */
    private static final String SERVER_HOST = "localhost";

    /** Must match the port ChatServer is listening on. */
    private static final int SERVER_PORT = 5000;

    /** Tracks whether this client is still connected and running. */
    private volatile boolean running = true;

    /** Socket connected to the server. */
    private Socket socket;

    /** Sends text lines to the server. */
    private PrintWriter out;

    /** Reads text lines from the server. */
    private BufferedReader in;

    // ================================================================
    // ENTRY POINT
    // ================================================================

    /**
     * Creates a ChatClient instance and starts it. Any uncaught
     * exception at this level exits cleanly with an error message.
     */
    public static void main(String[] args) {
        new ChatClient().start();
    }

    // ================================================================
    // START — connect and launch threads
    // ================================================================

    /**
     * Opens a socket connection to the server, sets up I/O streams,
     * launches the receiver thread, and then enters the send loop.
     * (Pankaj, 2022; Arthur C. Codex, 2023)
     */
    private void start() {
        printClientBanner();

        try {
            // ── Step 1: Connect to server ────────────────────────
            System.out.println("[CLIENT] Connecting to " + SERVER_HOST
                    + ":" + SERVER_PORT + " ...");
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            System.out.println("[CLIENT] Connected! Waiting for server welcome...\n");

            // ── Step 2: Set up streams ───────────────────────────
            out = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())),
                    true  // autoFlush — every println() is sent immediately
            );
            in  = new BufferedReader(
                    new InputStreamReader(socket.getInputStream())
            );

            // ── Step 3: Start the receiver thread ────────────────
            Thread receiverThread = new Thread(this::receiveLoop);
            receiverThread.setDaemon(true); // auto-kills when main thread exits
            receiverThread.start();

            // ── Step 4: Enter the send loop (main thread) ────────
            sendLoop();

        } catch (ConnectException e) {
            System.err.println("[CLIENT ERROR] Could not connect to "
                    + SERVER_HOST + ":" + SERVER_PORT);
            System.err.println("  Make sure ChatServer is running first.");
        } catch (IOException e) {
            if (running) {
                System.err.println("[CLIENT ERROR] " + e.getMessage());
            }
        } finally {
            shutdown();
        }
    }

    // ================================================================
    // SEND LOOP — runs on the main thread
    // ================================================================

    /**
     * Reads lines from System.in (keyboard) and sends each one to
     * the server. Stops when the user types /quit or presses Ctrl+C.
     *
     * The Scanner reads from stdin. Because receiverThread handles all
     * incoming text, the main thread is free to block on nextLine()
     * without missing any messages (SimpliCode, 2021).
     */
    private void sendLoop() {
        Scanner keyboard = new Scanner(System.in);

        printUI();  // show the text-based UI header

        while (running) {
            System.out.print("You > ");
            if (!keyboard.hasNextLine()) break; // EOF / Ctrl+D

            String userInput = keyboard.nextLine().trim();
            if (userInput.isEmpty()) continue;

            // Send the raw text to the server — the server will
            // prepend the user ID and timestamp before broadcasting
            out.println(userInput);

            // /quit — tell the server we're leaving, then stop the loop
            if (userInput.equalsIgnoreCase("/quit")) {
                running = false;
            }
        }
        keyboard.close();
    }

    // ================================================================
    // RECEIVE LOOP — runs on the background thread
    // ================================================================

    /**
     * Continuously reads lines arriving from the server and prints
     * them to the console. Runs until the connection closes or
     * 'running' is set to false (Eck, 2022, Section 11.4).
     *
     * The '\r' trick clears the "You > " prompt before printing
     * the incoming message, then re-prints the prompt below it —
     * keeping the UI tidy when messages arrive mid-typing.
     */
    private void receiveLoop() {
        try {
            String serverMessage;
            while (running && (serverMessage = in.readLine()) != null) {
                // Clear the current input line, print the message, reprint prompt
                System.out.print("\r");                    // go to start of line
                System.out.println(serverMessage);        // received message
                System.out.print("You > ");               // re-print the prompt
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("\n[CLIENT] Disconnected from server.");
            }
        }
        running = false; // signal the send loop to stop too
    }

    // ================================================================
    // SHUTDOWN
    // ================================================================

    /**
     * Closes all I/O resources cleanly. Called from the finally block
     * so it always runs, even if an exception was thrown
     * (Arthur C. Codex, 2023).
     */
    private void shutdown() {
        running = false;
        try {
            if (in     != null) in.close();
            if (out    != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        System.out.println("[CLIENT] Session ended. Goodbye!");
    }

    // ================================================================
    // UI HELPERS
    // ================================================================

    /** Prints the application banner when the client first starts. */
    private void printClientBanner() {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║      JAVA ONLINE CHAT APPLICATION    ║");
        System.out.println("║      Client v1.0                     ║");
        System.out.println("╚══════════════════════════════════════╝");
    }

    /**
     * Prints the text-based UI header after connection is established.
     * Provides clear instructions so users know how to interact
     * with the application.
     */
    private void printUI() {
        System.out.println();
        System.out.println("======================================");
        System.out.println("  CHAT ROOM — Connected");
        System.out.println("======================================");
        System.out.println("  Commands:");
        System.out.println("    /quit  — disconnect and exit");
        System.out.println("  Messages from others appear above.");
        System.out.println("  Type your message and press Enter.");
        System.out.println("======================================");
        System.out.println();
    }
}