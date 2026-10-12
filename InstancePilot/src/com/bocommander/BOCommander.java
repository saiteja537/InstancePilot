package com.bocommander;

import com.crystaldecisions.sdk.framework.CrystalEnterprise;
import com.crystaldecisions.sdk.framework.IEnterpriseSession;
import com.crystaldecisions.sdk.framework.ISessionMgr;
import com.crystaldecisions.sdk.occa.infostore.IInfoObject;
import com.crystaldecisions.sdk.occa.infostore.IInfoObjects;
import com.crystaldecisions.sdk.occa.infostore.IInfoStore;
import com.crystaldecisions.sdk.occa.infostore.ISchedulingInfo;
import com.crystaldecisions.sdk.properties.IProperties;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BO Commander  -  SAP BusinessObjects CMC Instance Cleanup Automation
 * ====================================================================
 * Reads all Running report instances from the CMS, identifies ones running
 * longer than a configurable threshold, and for each:
 *   1. Saves a JSON snapshot of the instance
 *   2. Triggers "Run Now" on the parent report (creates a replacement instance)
 *   3. Verifies the replacement actually appeared
 *   4. Deletes the ORIGINAL instance (never the replacement)
 *
 * SAFETY NOTICE: Test in a non-production environment first. Validate behavior, review destructive actions, and confirm recovery procedures before enabling live mode.
 *
 * Safety guarantees:
 *   - default mode is DRY-RUN; must set live_mode=true to actually act
 *   - never deletes an instance that is not Running at the moment of deletion
 *   - never deletes the original unless the replacement is verified
 *   - hard cap of max_reruns_per_day per report (default 2)
 *   - clean logoff on Ctrl+C
 *   - every action written to an audit log file
 *   - successful cleanup is highlighted in bold red on the console
 */
public class BOCommander {

    private static final int STATUS_RUNNING = 0;

    private static final Map<Integer, String> STATUS_LABELS = new HashMap<>();
    static {
        STATUS_LABELS.put(0, "Running");
        STATUS_LABELS.put(1, "Success");
        STATUS_LABELS.put(3, "Failed");
        STATUS_LABELS.put(8, "Paused");
        STATUS_LABELS.put(9, "Pending");
    }

    private static final DateTimeFormatter TIME_FMT  = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter TS_FMT    = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter STAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // ── Config ───────────────────────────────────────────────────────────
    static class Config {
        String cms = "SAITEJA:6400";
        String auth = "secEnterprise";
        String username = "Administrator";
        String password = "";
        double thresholdMinutes = 60.0;
        double intervalMinutes = 5.0;
        boolean liveMode = false;
        int maxRerunsPerDay = 2;
        String logFile = "bo_commander.log";
        String stateFile = "state.json";
        String snapshotDir = "snapshots";

        static Config load(Path p) throws Exception {
            Config cfg = new Config();
            if (!Files.exists(p)) {
                cfg.save(p);
                System.out.println("Created default config at: " + p);
                System.out.println("Edit it and re-run.");
                return cfg;
            }
            String txt = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            JSONObject j = new JSONObject(txt);
            cfg.cms = j.optString("cms", cfg.cms);
            cfg.auth = j.optString("auth", cfg.auth);
            cfg.username = j.optString("username", cfg.username);
            cfg.password = j.optString("password", cfg.password);
            cfg.thresholdMinutes = j.optDouble("threshold_minutes", cfg.thresholdMinutes);
            cfg.intervalMinutes = j.optDouble("interval_minutes", cfg.intervalMinutes);
            cfg.liveMode = j.optBoolean("live_mode", cfg.liveMode);
            cfg.maxRerunsPerDay = j.optInt("max_reruns_per_day", cfg.maxRerunsPerDay);
            cfg.logFile = j.optString("log_file", cfg.logFile);
            cfg.stateFile = j.optString("state_file", cfg.stateFile);
            cfg.snapshotDir = j.optString("snapshot_dir", cfg.snapshotDir);
            return cfg;
        }

        void save(Path p) throws Exception {
            JSONObject j = new JSONObject();
            j.put("cms", cms);
            j.put("auth", auth);
            j.put("username", username);
            j.put("password", password);
            j.put("threshold_minutes", thresholdMinutes);
            j.put("interval_minutes", intervalMinutes);
            j.put("live_mode", liveMode);
            j.put("max_reruns_per_day", maxRerunsPerDay);
            j.put("log_file", logFile);
            j.put("state_file", stateFile);
            j.put("snapshot_dir", snapshotDir);
            Path parent = p.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(p, j.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    // ── Audit log ────────────────────────────────────────────────────────
    static class AuditLog {
        // ANSI colors. Rendered by Windows Terminal or modern CMD with chcp 65001.
        private static final String RED    = "\u001B[1;31m";
        private static final String GREEN  = "\u001B[1;32m";
        private static final String YELLOW = "\u001B[1;33m";
        private static final String CYAN   = "\u001B[1;36m";
        private static final String BOLD   = "\u001B[1m";
        private static final String RESET  = "\u001B[0m";

        private final BufferedWriter writer;

        AuditLog(Path file) throws IOException {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        synchronized void write(String mode, String event, Integer instanceId, Integer parentId,
                                String report, String action, String result, String detail) {
            String plain = buildPlain(mode, event, instanceId, parentId, report, action, result, detail);
            try { writer.write(plain); writer.newLine(); writer.flush(); } catch (IOException ignored) {}
            System.out.println(colorize(event, plain, report, instanceId, result));
        }

        private String buildPlain(String mode, String event, Integer instanceId, Integer parentId,
                                   String report, String action, String result, String detail) {
            StringBuilder sb = new StringBuilder();
            sb.append(LocalDateTime.now().format(TS_FMT)).append("  ");
            sb.append(String.format("%-8s", mode)).append("  ");
            sb.append(String.format("%-22s", event)).append(" ");
            if (instanceId != null) sb.append("id=").append(instanceId).append(" ");
            if (parentId != null) sb.append("parent=").append(parentId).append(" ");
            if (report != null && !report.isEmpty()) sb.append("report='").append(report).append("' ");
            if (action != null && !action.isEmpty()) sb.append("action=").append(action).append(" ");
            sb.append("result='").append(result == null ? "" : result).append("'");
            if (detail != null && !detail.isEmpty()) {
                sb.append("  detail='").append(detail.replace("\n", " ")).append("'");
            }
            return sb.toString();
        }

        private String colorize(String event, String plain, String report,
                                Integer instanceId, String result) {
            if (event == null) return plain;

            // The success banner: DELETE completed. Bold red on its own line.
            if ("Original instance deleted".equals(event)) {
                String replacement = extractReplacement(result);
                String reportName = (report == null || report.isEmpty()) ? "(unnamed)" : report;
                return RED + BOLD
                        + ">>> AUTOMATED CLEANUP: " + reportName
                        + "  (instance " + instanceId
                        + (replacement.isEmpty() ? "" : " -> replacement " + replacement)
                        + ")"
                        + RESET;
            }

            if ("Failed".equals(event))    return RED    + plain + RESET;
            if ("Review".equals(event))    return YELLOW + plain + RESET;
            if ("Requires manual review".equals(event)) return YELLOW + plain + RESET;
            if ("Qualifies".equals(event)) return CYAN   + plain + RESET;
            if ("Deleted".equals(event))   return GREEN  + plain + RESET;
            return plain;
        }

        private static String extractReplacement(String result) {
            if (result == null) return "";
            int idx = result.lastIndexOf("replacement=");
            if (idx < 0) return "";
            String tail = result.substring(idx + "replacement=".length()).trim();
            int sp = tail.indexOf(' ');
            return sp < 0 ? tail : tail.substring(0, sp);
        }

        synchronized void close() {
            try { writer.close(); } catch (IOException ignored) {}
        }
    }

    // ── State (restart-safe) ─────────────────────────────────────────────
    static class State {
        final Map<String, Map<String, Integer>> counters = new ConcurrentHashMap<>();
        final Map<String, JSONObject> handled = new ConcurrentHashMap<>();
        final Path path;

        State(Path path) throws IOException {
            this.path = path;
            if (Files.exists(path)) {
                try {
                    String txt = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    JSONObject j = new JSONObject(txt);

                    JSONObject c = j.optJSONObject("counters");
                    if (c != null) {
                        Iterator<String> days = c.keys();
                        while (days.hasNext()) {
                            String day = days.next();
                            JSONObject inner = c.optJSONObject(day);
                            if (inner == null) continue;
                            Map<String, Integer> map = new ConcurrentHashMap<>();
                            Iterator<String> pids = inner.keys();
                            while (pids.hasNext()) {
                                String pid = pids.next();
                                map.put(pid, inner.optInt(pid, 0));
                            }
                            counters.put(day, map);
                        }
                    }

                    JSONObject h = j.optJSONObject("handled");
                    if (h != null) {
                        Iterator<String> iids = h.keys();
                        while (iids.hasNext()) {
                            String iid = iids.next();
                            JSONObject o = h.optJSONObject(iid);
                            if (o != null) handled.put(iid, o);
                        }
                    }
                } catch (Exception ignored) { /* start fresh on any parse error */ }
            }
        }

        synchronized void save() throws Exception {
            JSONObject j = new JSONObject();

            JSONObject c = new JSONObject();
            for (Map.Entry<String, Map<String, Integer>> e : counters.entrySet()) {
                JSONObject inner = new JSONObject();
                for (Map.Entry<String, Integer> m : e.getValue().entrySet())
                    inner.put(m.getKey(), m.getValue());
                c.put(e.getKey(), inner);
            }
            j.put("counters", c);

            JSONObject h = new JSONObject();
            for (Map.Entry<String, JSONObject> e : handled.entrySet())
                h.put(e.getKey(), e.getValue());
            j.put("handled", h);

            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(path, j.toString(2).getBytes(StandardCharsets.UTF_8));
        }

        synchronized int rerunCount(String day, int parentId) {
            Map<String, Integer> m = counters.get(day);
            if (m == null) return 0;
            return m.getOrDefault(String.valueOf(parentId), 0);
        }

        synchronized boolean reserveRerun(String day, int parentId, int limit) {
            Map<String, Integer> m = counters.computeIfAbsent(day, k -> new ConcurrentHashMap<>());
            int cur = m.getOrDefault(String.valueOf(parentId), 0);
            if (cur >= limit) return false;
            m.put(String.valueOf(parentId), cur + 1);
            try { save(); } catch (Exception e) { return false; }
            return true;
        }

        synchronized void releaseRerun(String day, int parentId) {
            Map<String, Integer> m = counters.get(day);
            if (m == null) return;
            int cur = m.getOrDefault(String.valueOf(parentId), 0);
            if (cur > 0) {
                m.put(String.valueOf(parentId), cur - 1);
                try { save(); } catch (Exception ignored) {}
            }
        }

        synchronized boolean isHandled(int instanceId) {
            return handled.containsKey(String.valueOf(instanceId));
        }

        synchronized void markHandled(int instanceId, String outcome) {
            try {
                JSONObject o = new JSONObject();
                o.put("ts", LocalDateTime.now().toString());
                o.put("outcome", outcome);
                handled.put(String.valueOf(instanceId), o);
                save();
            } catch (Exception ignored) {
                // Best-effort: state persistence must not abort the workflow.
            }
        }
    }

    // ── Instance holder ──────────────────────────────────────────────────
    static class Inst {
        int id;
        int parentId;
        String name = "";
        int statusCode;
        Instant startTime;
        String startRaw = "";
        double durationMin;
        boolean durationOk;
    }

    // ── Main ─────────────────────────────────────────────────────────────
    private static volatile boolean stopping = false;

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run();
        } catch (Exception ex) {
            System.err.println("Fatal: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            ex.printStackTrace(System.err);
            exitCode = 2;
        }
        System.exit(exitCode);
    }

    private static int run() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Config cfg = Config.load(root.resolve("config.json"));

        if (cfg.password == null || cfg.password.isEmpty()) {
            System.err.println("Password is empty in config.json. Edit it and re-run.");
            return 1;
        }

        System.out.println("+-----------------------------------------------------------+");
        System.out.println("|  BO Commander - SAP BO CMC Instance Cleanup Automation    |");
        System.out.println("+-----------------------------------------------------------+");
        System.out.printf ("|  CMS:        %-44s |%n", cfg.cms);
        System.out.printf ("|  User:       %-44s |%n", cfg.username);
        System.out.printf ("|  Threshold:  %-44s |%n", cfg.thresholdMinutes + " min");
        System.out.printf ("|  Interval:   %-44s |%n", cfg.intervalMinutes + " min");
        System.out.printf ("|  Live mode:  %-44s |%n", cfg.liveMode ? "*** ENABLED ***" : "DISABLED (dry-run)");
        System.out.printf ("|  Daily cap:  %-44s |%n", cfg.maxRerunsPerDay + " per report");
        System.out.println("+-----------------------------------------------------------+");
        System.out.println();

        FounderBanner.display();

        Files.createDirectories(root.resolve(cfg.snapshotDir));
        AuditLog log = new AuditLog(root.resolve(cfg.logFile));
        State state = new State(root.resolve(cfg.stateFile));

        final IEnterpriseSession[] sessionHolder = new IEnterpriseSession[1];
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopping = true;
            System.out.println();
            System.out.println("[shutdown] Stopping...");
            try {
                if (sessionHolder[0] != null) {
                    sessionHolder[0].logoff();
                    System.out.println("[shutdown] Logged off.");
                }
            } catch (Exception ignored) {}
            log.close();
        }));

        System.out.println("Logging on to CMS...");
        ISessionMgr mgr = CrystalEnterprise.getSessionMgr();
        IEnterpriseSession session;
        try {
            session = mgr.logon(cfg.username, cfg.password, cfg.cms, cfg.auth);
        } catch (Exception ex) {
            System.err.println("Logon FAILED: " + ex.getMessage());
            log.write(cfg.liveMode ? "live" : "dry-run", "Failed", null, null, null,
                    "logon", "Logon failed: " + ex.getMessage(), "");
            log.close();
            return 3;
        }
        sessionHolder[0] = session;
        IInfoStore store = (IInfoStore) session.getService("InfoStore");
        System.out.println("Logged on successfully.");
        System.out.println();

        String mode = cfg.liveMode ? "live" : "dry-run";
        log.write(mode, "Info", null, null, null, "start",
                "Automation started, threshold=" + cfg.thresholdMinutes + "m, interval=" + cfg.intervalMinutes + "m", "");

        while (!stopping) {
            long cycleStart = System.currentTimeMillis();
            try {
                runCycle(store, cfg, state, log, root.resolve(cfg.snapshotDir));
            } catch (Exception ex) {
                log.write(mode, "Failed", null, null, null, "cycle",
                        ex.getClass().getSimpleName() + ": " + ex.getMessage(), "");
            }
            long elapsed = System.currentTimeMillis() - cycleStart;
            long sleepMs = Math.max(1000L, (long) (cfg.intervalMinutes * 60_000) - elapsed);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                break;
            }
        }

        try { session.logoff(); } catch (Exception ignored) {}
        log.write(mode, "Info", null, null, null, "stop", "Automation stopped", "");
        log.close();
        return 0;
    }

    // ── One scan cycle ───────────────────────────────────────────────────
    private static void runCycle(IInfoStore store, Config cfg, State state,
                                 AuditLog log, Path snapDir) throws Exception {
        String mode = cfg.liveMode ? "live" : "dry-run";

        List<Inst> candidates = new ArrayList<>();
        Instant now = Instant.now();

        String q = "SELECT SI_ID, SI_NAME, SI_PARENTID, SI_KIND, SI_OWNER, " +
                   "SI_STARTTIME, SI_SCHEDULE_STATUS " +
                   "FROM CI_INFOOBJECTS WHERE SI_INSTANCE=1 AND SI_SCHEDULE_STATUS=0";

        IInfoObjects results = store.query(q);
        int n = results.size();

        for (int i = 0; i < n; i++) {
            Inst inst = toInst((IInfoObject) results.get(i));
            if (inst == null) continue;
            if (inst.statusCode != STATUS_RUNNING) continue;
            if (inst.parentId <= 0) continue;

            if (inst.startTime != null) {
                long sec = now.getEpochSecond() - inst.startTime.getEpochSecond();
                if (sec >= -120) {
                    inst.durationMin = Math.max(0.0, sec / 60.0);
                    inst.durationOk = true;
                }
            }

            System.out.println("  [seen] id=" + inst.id
                    + "  parent=" + inst.parentId
                    + "  name='" + inst.name + "'"
                    + "  startRaw='" + inst.startRaw + "'"
                    + "  parsed=" + (inst.startTime != null ? inst.startTime.toString() : "NULL")
                    + "  duration=" + (inst.durationOk ? String.format("%.2f min", inst.durationMin) : "N/A"));

            if (!inst.durationOk) continue;
            if (inst.durationMin > cfg.thresholdMinutes) candidates.add(inst);
        }
        System.out.println("[" + LocalDateTime.now().format(TIME_FMT) + "] Cycle: "
                + n + " running instances fetched, "
                + candidates.size() + " over threshold (" + cfg.thresholdMinutes + " min)");

        for (Inst inst : candidates) {
            if (stopping) return;
            try {
                processOne(store, cfg, state, log, snapDir, inst);
            } catch (Exception ex) {
                log.write(mode, "Failed", inst.id, inst.parentId, inst.name, "process",
                        ex.getClass().getSimpleName() + ": " + ex.getMessage(), "");
            }
        }
    }

    // ── Per-instance workflow ────────────────────────────────────────────
    private static void processOne(IInfoStore store, Config cfg, State state,
                                   AuditLog log, Path snapDir, Inst inst) throws Exception {
        String mode = cfg.liveMode ? "live" : "dry-run";
        int iid = inst.id, pid = inst.parentId;
        String name = inst.name;
        String day = LocalDate.now().toString();

        if (state.isHandled(iid)) return;

        log.write(mode, "Qualifies", iid, pid, name, "qualify",
                String.format("Running %.1f min > %.1f min", inst.durationMin, cfg.thresholdMinutes), "");

        if (!cfg.liveMode) {
            log.write(mode, "Dry-run", iid, pid, name, "plan",
                    "Would Run Now -> verify replacement -> delete original", "");
            return;
        }

        if (state.rerunCount(day, pid) >= cfg.maxRerunsPerDay) {
            log.write(mode, "Skipped", iid, pid, name, "limit",
                    "Daily limit reached (" + cfg.maxRerunsPerDay + " for report " + pid + ")", "");
            return;
        }
        if (!state.reserveRerun(day, pid, cfg.maxRerunsPerDay)) {
            log.write(mode, "Skipped", iid, pid, name, "limit",
                    "Could not reserve daily rerun slot", "");
            return;
        }

        // 1. Snapshot
        try {
            saveSnapshot(store, iid, snapDir);
            log.write(mode, "Info", iid, pid, name, "snapshot", "Snapshot saved", "");
        } catch (Exception ex) {
            state.releaseRerun(day, pid);
            log.write(mode, "Failed", iid, pid, name, "snapshot",
                    "Snapshot failed: " + ex.getMessage(), "No destructive action taken.");
            return;
        }

        // 2. Run Now on the parent report
        Set<Integer> before = listChildIds(store, pid);
        try {
            runNowOnParent(store, pid);
            log.write(mode, "Rerun requested", iid, pid, name, "rerun",
                    "Submitted parent report for immediate run", "");
        } catch (Exception ex) {
            state.releaseRerun(day, pid);
            log.write(mode, "Failed", iid, pid, name, "rerun",
                    "Run Now failed: " + ex.getMessage(), "Original kept.");
            return;
        }

        // 3. Verify new instance appeared (fast poll, max 10s)
        Integer newId = null;
        for (int attempt = 0; attempt < 10 && !stopping; attempt++) {
            Thread.sleep(1000);
            Set<Integer> after = listChildIds(store, pid);
            Set<Integer> fresh = new HashSet<>(after);
            fresh.removeAll(before);
            fresh.remove(iid);
            if (!fresh.isEmpty()) {
                newId = fresh.iterator().next();
                break;
            }
        }

        if (newId == null) {
            state.markHandled(iid, "Manual review: replacement not identified in 10 s");
            log.write(mode, "Review", iid, pid, name, "verify-rerun",
                    "Replacement not identified within 10 s", "Original NOT deleted.");
            return;
        }
        log.write(mode, "Rerun verified", iid, pid, name, "verify-rerun",
                "New instance " + newId + " confirmed", "");

        // 4. Confirm original is still Running before deletion
        IInfoObject check = getInstance(store, iid);
        if (check == null) {
            log.write(mode, "Skipped", iid, pid, name, "delete",
                    "Original no longer exists", "");
            return;
        }
        int currentStatus = check.properties().getInt("SI_SCHEDULE_STATUS");
        if (currentStatus != STATUS_RUNNING) {
            state.markHandled(iid, "Not deleted: original finished before deletion");
            log.write(mode, "Skipped", iid, pid, name, "delete",
                    "Original status is " + STATUS_LABELS.getOrDefault(currentStatus, "?"), "");
            return;
        }

        // 5. Delete the original
        try {
            deleteInstance(store, iid);
        } catch (Exception ex) {
            state.markHandled(iid, "Manual review: delete failed");
            log.write(mode, "Failed", iid, pid, name, "delete",
                    "Delete failed: " + ex.getMessage(), "Original kept.");
            return;
        }

        state.markHandled(iid, "Remediated; replacement=" + newId);
        log.write(mode, "Original instance deleted", iid, pid, name, "delete",
                "replacement=" + newId, "");
    }

    // ── SDK helpers ──────────────────────────────────────────────────────
    private static Inst toInst(IInfoObject obj) {
        try {
            Inst inst = new Inst();
            inst.id = obj.getID();
            inst.parentId = obj.getParentID();
            IProperties props = obj.properties();

            Object nm = props.get("SI_NAME");
            inst.name = (nm == null) ? "(unnamed)" : nm.toString();
            inst.statusCode = props.getInt("SI_SCHEDULE_STATUS");

            Object rawStart = props.get("SI_STARTTIME");
            inst.startRaw = (rawStart == null) ? "" : rawStart.toString();
            inst.startTime = parseStartTime(rawStart);

            return inst;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * SI_STARTTIME comes back as one of three shapes depending on the CMS build:
     *   1. java.util.Date  - the well-behaved case
     *   2. OLE Automation date (a floating-point number, e.g. 46306.3564992477)
     *      - this is what most SAP BO 4.3 builds actually return
     *      - stored in UTC
     *   3. Localised string like "Oct 11, 2026, 1:44 PM"  - used by some CMU builds
     */
    private static Instant parseStartTime(Object raw) {
        if (raw == null) return null;

        if (raw instanceof Date) return ((Date) raw).toInstant();

        if (raw instanceof Number) {
            double v = ((Number) raw).doubleValue();
            if (v > 1.0) return oleDateToInstant(v);
            return null;
        }

        String s = raw.toString().trim();
        if (s.isEmpty()) return null;

        try {
            double v = Double.parseDouble(s);
            if (v > 1.0) return oleDateToInstant(v);
        } catch (NumberFormatException ignored) {
            // fall through to date patterns
        }

        String[] patterns = new String[] {
            "MMM d, yyyy, h:mm:ss a",
            "MMM d, yyyy, h:mm a",
            "MMM dd, yyyy, h:mm:ss a",
            "MMM dd, yyyy, h:mm a",
            "d MMM yyyy, HH:mm:ss",
            "d MMM yyyy, HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
        };
        for (String p : patterns) {
            try {
                java.time.format.DateTimeFormatter fmt =
                    java.time.format.DateTimeFormatter.ofPattern(p, java.util.Locale.ENGLISH);
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(s, fmt);
                return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
            } catch (Exception ignored) {
                // try next pattern
            }
        }
        return null;
    }

    /**
     * Convert an OLE Automation date (days since 1899-12-30, fraction = time of day,
     * stored in UTC) into a Java Instant.
     */
    private static Instant oleDateToInstant(double oleDate) {
        long days = (long) Math.floor(oleDate);
        double frac = oleDate - days;

        long totalSeconds = Math.round(frac * 86400.0);
        long extraDays = totalSeconds / 86400;
        long secOfDay = totalSeconds % 86400;

        LocalDate base = LocalDate.of(1899, 12, 30);
        LocalDate target = base.plusDays(days + extraDays);
        LocalTime time = LocalTime.ofSecondOfDay(secOfDay);

        return LocalDateTime.of(target, time).toInstant(ZoneOffset.UTC);
    }

    private static String safeString(IProperties p, String key) {
        try {
            Object v = p.get(key);
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void saveSnapshot(IInfoStore store, int instanceId, Path snapDir) throws Exception {
        IInfoObjects objs = store.query(
                "SELECT * FROM CI_INFOOBJECTS WHERE SI_ID=" + instanceId);
        if (objs.size() == 0) throw new Exception("Instance " + instanceId + " not found");
        IInfoObject obj = (IInfoObject) objs.get(0);
        IProperties props = obj.properties();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"SI_ID\": ").append(obj.getID()).append(",\n");
        sb.append("  \"SI_NAME\": \"").append(jsonEscape(safeString(props, "SI_NAME"))).append("\",\n");
        sb.append("  \"SI_PARENTID\": ").append(obj.getParentID()).append(",\n");
        sb.append("  \"SI_OWNER\": \"").append(jsonEscape(safeString(props, "SI_OWNER"))).append("\",\n");
        sb.append("  \"SI_KIND\": \"").append(jsonEscape(safeString(props, "SI_KIND"))).append("\",\n");
        sb.append("  \"SI_STARTTIME\": \"").append(jsonEscape(safeString(props, "SI_STARTTIME"))).append("\",\n");
        sb.append("  \"SI_SCHEDULE_STATUS\": ").append(props.getInt("SI_SCHEDULE_STATUS")).append("\n");
        sb.append("}\n");
        String stamp = LocalDateTime.now().format(STAMP_FMT);
        Path file = snapDir.resolve("instance_" + instanceId + "_" + stamp + ".json");
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Set<Integer> listChildIds(IInfoStore store, int parentId) throws Exception {
        IInfoObjects objs = store.query(
                "SELECT SI_ID FROM CI_INFOOBJECTS WHERE SI_INSTANCE=1 AND SI_PARENTID=" + parentId);
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i < objs.size(); i++) out.add(((IInfoObject) objs.get(i)).getID());
        return out;
    }

    private static IInfoObject getInstance(IInfoStore store, int instanceId) throws Exception {
        IInfoObjects objs = store.query(
                "SELECT SI_ID, SI_SCHEDULE_STATUS FROM CI_INFOOBJECTS WHERE SI_ID=" + instanceId);
        if (objs.size() == 0) return null;
        return (IInfoObject) objs.get(0);
    }

    private static void runNowOnParent(IInfoStore store, int parentId) throws Exception {
        IInfoObjects parents = store.query(
                "SELECT SI_ID, SI_NAME FROM CI_INFOOBJECTS " +
                "WHERE SI_ID=" + parentId + " AND SI_INSTANCE=0");
        if (parents.size() == 0) {
            throw new Exception("Parent report " + parentId + " not found");
        }
        IInfoObject parent = (IInfoObject) parents.get(0);
        ISchedulingInfo sched = parent.getSchedulingInfo();
        sched.setRightNow(true);
        store.schedule(parents);
    }

    private static void deleteInstance(IInfoStore store, int instanceId) throws Exception {
        // deleteNow() removes the object immediately and bypasses store.commit()
        // and its Webi plugin event handlers. That avoids the FWM 02021
        // "SI_CUID does not exist" error that fires from Webi$3.processEvent
        // during commit on BO 4.3.
        IInfoObjects objs = store.query(
                "SELECT SI_CUID, SI_ID, SI_NAME, SI_PARENTID, SI_KIND " +
                "FROM CI_INFOOBJECTS WHERE SI_ID=" + instanceId);
        if (objs.size() == 0) throw new Exception("Instance " + instanceId + " not found");
        IInfoObject obj = (IInfoObject) objs.get(0);
        obj.deleteNow();
    }
}