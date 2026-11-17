package dev.opencal.tv;

import android.util.Base64;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Short-lived HTTP server on the TV's LAN address that serves the pairing form.
 * Access needs a random one-time token and closes after 15 minutes or one successful
 * submission. It is plain HTTP, so the submitted URL is visible on the local network.
 */
final class PairingServer implements AutoCloseable {
    interface Listener {
        void onSubmitted(String feedUrl);
        /** The pairing window closed without a submission. */
        void onExpired(String pairingUrl);
    }

    private static final int PORT = 8765;
    private static final long LIFETIME_MS = 15 * 60_000L;
    private static final int ACCEPT_POLL_MS = 1000;

    private static final class Session {
        final ServerSocket socket;
        final byte[] token;
        final String url;
        final long deadline;

        Session(ServerSocket socket, String token, String url, long deadline) {
            this.socket = socket;
            this.token = token.getBytes(StandardCharsets.UTF_8);
            this.url = url;
            this.deadline = deadline;
        }
    }

    private final Listener listener;
    private Session session;

    PairingServer(Listener listener) { this.listener = listener; }

    /** Opens a new pairing window, replacing any open one. Returns the pairing URL, or "" if unavailable. */
    synchronized String start() {
        close();
        String address = findLanAddress();
        if (address == null) return "";
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT), 8);
            socket.setSoTimeout(ACCEPT_POLL_MS);
        } catch (Exception e) {
            closeQuietly(socket);
            return "";
        }
        Session s = new Session(socket, token, "http://" + address + ":" + PORT + "/connect?t=" + token,
                                System.currentTimeMillis() + LIFETIME_MS);
        session = s;
        new Thread(() -> serve(s), "open-cal-pairing").start();
        return s.url;
    }

    @Override public synchronized void close() {
        if (session != null) closeQuietly(session.socket);
        session = null;
    }

    private synchronized boolean isCurrent(Session s) { return session == s; }

    /** Closes the session. Returns false if it had already been replaced or closed. */
    private synchronized boolean end(Session s) {
        closeQuietly(s.socket);
        if (session != s) return false;
        session = null;
        return true;
    }

    private void serve(Session s) {
        while (isCurrent(s) && System.currentTimeMillis() < s.deadline) {
            try (Socket client = s.socket.accept()) {
                client.setSoTimeout(5000);
                handle(s, client);
            } catch (SocketTimeoutException ignored) {
                // accept() polls so the window can expire without waiting for a request.
            } catch (Exception e) {
                if (s.socket.isClosed()) break;
            }
        }
        if (end(s)) listener.onExpired(s.url);
    }

    private void handle(Session s, Socket socket) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        String request = input.readLine();
        if (request == null || request.length() > 4096) return;
        String[] first = request.split(" ");
        if (first.length < 2) return;
        String method = first[0], path = first[1];
        int contentLength = 0;
        String line;
        while ((line = input.readLine()) != null && !line.isEmpty()) {
            if (line.length() > 8192) return;
            int at = line.indexOf(':');
            if (at > 0 && line.substring(0, at).equalsIgnoreCase("Content-Length")) {
                try { contentLength = Integer.parseInt(line.substring(at + 1).trim()); }
                catch (NumberFormatException e) { return; }
            }
        }
        boolean authorized = path.startsWith("/connect") && tokenMatches(s, parseQuery(path).get("t"))
            && System.currentTimeMillis() <= s.deadline;
        if (!authorized) {
            reply(output, 403, page("This pairing link has expired. Select New pairing code on the TV.", false));
            return;
        }
        if ("POST".equals(method)) {
            if (contentLength < 1 || contentLength > 12000) {
                reply(output, 413, page("Invalid request size.", true));
                return;
            }
            char[] chars = new char[contentLength];
            int total = 0;
            while (total < chars.length) {
                int n = input.read(chars, total, chars.length - total);
                if (n < 0) break;
                total += n;
            }
            Map<String, String> values = parseParams(new String(chars, 0, total));
            String url = values.getOrDefault("url", "").trim();
            if (!MainActivity.isValidFeedUrl(url)) {
                reply(output, 400, page("That URL doesn't look like a Google Calendar secret iCal address.", true));
                return;
            }
            if (!end(s)) {
                reply(output, 403, page("This pairing link has expired. Select New pairing code on the TV.", false));
                return;
            }
            reply(output, 200, page("Calendar connected. You can close this page.", false));
            listener.onSubmitted(url);
        } else if ("GET".equals(method)) {
            reply(output, 200, page("", true));
        } else {
            reply(output, 405, page("Method not allowed.", false));
        }
    }

    private static boolean tokenMatches(Session s, String candidate) {
        return candidate != null && MessageDigest.isEqual(s.token, candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static String page(String notice, boolean showForm) {
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<meta name='referrer' content='no-referrer'><title>Open Cal pairing</title>" +
            "<style>body{background:#f7f8fc;color:#202a3b;font-family:system-ui,sans-serif;margin:0;padding:24px}" +
            ".card{background:white;max-width:540px;margin:9vh auto;padding:28px;border-radius:20px;box-shadow:0 18px 50px #172d5115}" +
            "h1{font-size:28px}p{line-height:1.6;color:#556377}input{width:100%;box-sizing:border-box;padding:14px;border:1px solid #ccd4e3;border-radius:9px;font-size:15px}" +
            "button{background:#3878ed;color:white;border:0;border-radius:9px;padding:14px 22px;margin-top:16px;font-size:16px;font-weight:bold}" +
            "small{display:block;color:#7a879a;margin-top:22px}</style></head><body><div class='card'>" +
            "<h1>Connect your Google Calendar</h1>" +
            "<p>In Google Calendar on a computer, open Settings → select your calendar → Integrate calendar → " +
            "<b>Secret address in iCal format</b>. Copy that address and paste it below.</p>";
        if (!notice.isEmpty()) html += "<p><b>" + notice + "</b></p>";
        if (showForm) html += "<form method='POST'><label for='url'>Secret iCal address</label>" +
            "<input id='url' name='url' type='url' inputmode='url' placeholder='https://calendar.google.com/calendar/ical/…' required autocomplete='off'>" +
            "<button type='submit'>Connect calendar</button></form>";
        return html + "<small>This page is served by your TV over your local network. Only pair on a network you trust.</small>" +
            "</div></body></html>";
    }

    private static Map<String, String> parseQuery(String path) {
        int q = path.indexOf('?');
        return parseParams(q >= 0 ? path.substring(q + 1) : "");
    }

    private static Map<String, String> parseParams(String params) {
        Map<String, String> result = new HashMap<>();
        for (String param : params.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0) {
                try {
                    result.put(URLDecoder.decode(param.substring(0, eq), "UTF-8"),
                               URLDecoder.decode(param.substring(eq + 1), "UTF-8"));
                } catch (Exception ignored) { }
            }
        }
        return result;
    }

    private static void reply(BufferedWriter out, int code, String body) throws Exception {
        byte[] utf8 = body.getBytes(StandardCharsets.UTF_8);
        out.write("HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n");
        out.write("Content-Type: text/html; charset=utf-8\r\n");
        out.write("Cache-Control: no-store\r\n");
        out.write("Referrer-Policy: no-referrer\r\n");
        out.write("X-Content-Type-Options: nosniff\r\n");
        out.write("Connection: close\r\n");
        out.write("Content-Length: " + utf8.length + "\r\n\r\n");
        out.write(body);
        out.flush();
    }

    private static String findLanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress() && !a.isLoopbackAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static void closeQuietly(ServerSocket socket) {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }
}
