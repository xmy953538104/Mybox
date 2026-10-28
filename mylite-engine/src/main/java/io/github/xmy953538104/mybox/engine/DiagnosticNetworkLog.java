package io.github.xmy953538104.mybox.engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional own-UID log snapshot. Only known route labels/status numbers leave the parser. */
final class DiagnosticNetworkLog {
    private static final String ROUTES = "(getItems|search|delivery|purchase|details|bulkDetails)";
    private static final Pattern PREFIX = Pattern.compile("^[VDIWEF]/(?:Volley|Finsky)\\s*\\(\\s*([0-9]{1,9})\\):.*");
    private static final Pattern UNEXPECTED = Pattern.compile("Unexpected response code ([1-5][0-9]{2}) for https://play-fe\\.googleapis\\.com/fdfe/" + ROUTES + "(?:[?\\s>]|$)");
    private static final Pattern RESPONSE = Pattern.compile("HTTP response for request=<https://play-fe\\.googleapis\\.com/fdfe/" + ROUTES + "(?:[?][^>]{0,2048})?>.*?\\[rc=([1-5][0-9]{2})\\]");

    static final class Event {
        final String route; final int status, pid;
        Event(String route, int status, int pid) { this.route = route; this.status = status; this.pid = pid; }
    }
    static Event parse(String line) {
        if (line == null || line.length() > 4096) return null;
        Matcher prefix = PREFIX.matcher(line);
        if (!prefix.matches()) return null;
        int pid = Integer.parseInt(prefix.group(1));
        Matcher error = UNEXPECTED.matcher(line);
        if (error.find()) return new Event(error.group(2), Integer.parseInt(error.group(1)), pid);
        Matcher response = RESPONSE.matcher(line);
        if (response.find()) return new Event(response.group(1), Integer.parseInt(response.group(2)), pid);
        return null;
    }

    static void capture(int uid) {
        if (!EngineDiagnostics.enabled()) return;
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-d", "-t", "1000", "-v", "brief", "-b", "main", "--uid=" + uid)
                    .redirectErrorStream(true).start();
            final java.lang.Process source = process;
            FutureTask<Integer> collect = new FutureTask<>(() -> {
                int count = 0;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder line = new StringBuilder(4096); int ch, rows = 0; boolean oversized = false;
                    while ((ch = reader.read()) != -1 && rows < 1000 && !Thread.currentThread().isInterrupted()) {
                        if (ch == '\n') {
                            Event event = oversized ? null : parse(line.toString());
                            if (event != null) { EngineDiagnostics.event("http", event.route, event.pid, event.status); count++; }
                            line.setLength(0); oversized = false; rows++;
                        } else if (line.length() < 4096) line.append((char) ch);
                        else oversized = true;
                    }
                }
                return count;
            });
            Thread reader = new Thread(collect, "mylite-network-diagnostic"); reader.setDaemon(true); reader.start();
            try { EngineDiagnostics.event("http", "snapshot_matches", 0, collect.get(3, TimeUnit.SECONDS)); }
            finally { collect.cancel(true); }
            if (process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() != 0)
                EngineDiagnostics.event("http", "logcat_unavailable", 0, process.exitValue());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); EngineDiagnostics.failure("http", "snapshot", 0, interrupted);
        } catch (Exception failure) { EngineDiagnostics.failure("http", "snapshot", 0, failure); }
        finally { if (process != null) process.destroy(); }
    }
    private DiagnosticNetworkLog() { }
}
