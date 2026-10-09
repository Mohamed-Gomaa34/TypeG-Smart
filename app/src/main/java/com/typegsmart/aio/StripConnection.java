package com.typegsmart.aio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** اتصال TCP واحد مع مشترك متصل بالموبايل. */
public class StripConnection implements Runnable {
    public interface Listener { void onLine(StripConnection conn, String line); void onClose(StripConnection conn); }

    private final Socket socket;
    private final Listener listener;
    private volatile boolean open = true;
    public String mac;            // يتحدد من bootinfo
    public String label;

    public StripConnection(Socket s, Listener l) { this.socket = s; this.listener = l; }

    @Override public void run() {
        try {
            socket.setSoTimeout(0);
            socket.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (open && (line = in.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) listener.onLine(this, line);
            }
        } catch (Exception ignore) {
        } finally { close(); listener.onClose(this); }
    }

    public synchronized void send(String cmd) {
        if (!open) return;
        try {
            OutputStream os = socket.getOutputStream();
            String out = cmd.endsWith(Protocol.EOL) ? cmd : (cmd + Protocol.EOL);
            os.write(out.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception ignore) {}
    }

    public boolean isOpen() { return open && !socket.isClosed(); }

    public void close() {
        open = false;
        try { socket.close(); } catch (Exception ignore) {}
    }
}
