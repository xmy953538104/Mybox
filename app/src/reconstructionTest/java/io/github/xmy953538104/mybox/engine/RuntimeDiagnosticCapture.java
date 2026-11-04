package io.github.xmy953538104.mybox.engine;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Retains the real failed operation before restoring the previous debug setting. */
final class RuntimeDiagnosticCapture implements AutoCloseable {
    private final Context context;
    private final boolean previous;
    private final File file;
    RuntimeDiagnosticCapture(Context context, String name) throws IOException {
        this.context = context; file = new File(context.getFilesDir(), name);
        previous = EngineDiagnostics.enabled(); EngineDiagnostics.setEnabled(true);
    }
    @Override public void close() throws IOException {
        IOException primary = null;
        try (FileOutputStream output = new FileOutputStream(file)) { EngineDiagnostics.exportJournalSnapshot(context, output); }
        catch (IOException failure) { primary = failure; throw failure; }
        finally {
            try { EngineDiagnostics.setEnabled(previous); }
            catch (IOException failure) { if (primary != null) primary.addSuppressed(failure); else throw failure; }
        }
    }
    boolean contains(String area, String operation, long since) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(file))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (!entry.getName().endsWith(".jsonl")) continue;
                BufferedReader reader = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                for (String line; (line = reader.readLine()) != null;) {
                    JSONObject row = new JSONObject(line);
                    if (area.equals(row.getString("area")) && operation.equals(row.getString("operation")) && row.getLong("wall_ms") >= since) return true;
                }
            }
        }
        return false;
    }
}
