package aio.app.mdmclient.dpc.scout;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import javax.crypto.Cipher;

/**
 * A minimal adb <em>host</em> over plain TCP: what {@code adb connect host:5555} speaks,
 * written out by hand because this is a system app in the AOSP tree with no libraries.
 * Used by {@link NetScout} to reach other devices on the restaurant's Wi-Fi whose image
 * trusts the fleet adb key (our own {@code /adb_keys}, or a vendor's built to the same
 * contract).
 *
 * <p>Only the transport-level protocol (CNXN / AUTH / OPEN / WRTE / OKAY / CLSE), one
 * stream at a time, and two kinds of stream: {@code shell:} (read everything it prints)
 * and {@code exec:} with a body (the way {@code adb install} streams an APK into
 * {@code cmd package install -S}). No TLS, no pairing, no shell_v2: the plain :5555
 * transport is the whole point of the vendor contract, and the Wireless-debugging path
 * stays with the Enroll apps.
 *
 * <p>Authentication never answers a key prompt. adbd asks with AUTH(TOKEN); we sign it
 * with the fleet key (AUTH(SIGNATURE)). If adbd asks again, the key is not trusted and we
 * stop — sending AUTH(RSAPUBLICKEY) would pop "Allow USB debugging?" on a stranger's
 * screen, which a scout must never do.
 */
public final class AdbClient implements Closeable {
    private static final String TAG = "AdbClient";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;
    private static final int A_STLS = 0x534c5453;

    private static final int VERSION = 0x01000001;   // checksums optional from this version on
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2;

    /** PKCS#1 v1.5 DigestInfo prefix for SHA-1: adb signs the raw 20-byte token with RSA_sign(NID_sha1). */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    /** The device answered but did not accept our key (or wants TLS). Not an error of ours. */
    public static final class Refused extends IOException {
        public Refused(String why) { super(why); }
    }

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private final String address;
    private int remoteMax = MAX_PAYLOAD;
    private String banner = "";
    private int nextLocalId = 1;

    private AdbClient(Socket s, String address) throws IOException {
        this.sock = s;
        this.in = new DataInputStream(s.getInputStream());
        this.out = s.getOutputStream();
        this.address = address;
    }

    /**
     * Connects and authenticates with {@code key}. Throws {@link Refused} when adbd does not
     * trust it, {@link IOException} for anything network-shaped. {@code ioTimeoutMs} bounds
     * every later read as well: a device that accepts the socket and then falls asleep
     * must not hold a scan thread forever.
     */
    public static AdbClient connect(String host, int port, PrivateKey key,
                                    int connectTimeoutMs, int ioTimeoutMs) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(ioTimeoutMs);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            throw e;
        }
        AdbClient c = new AdbClient(s, host + ":" + port);
        try {
            c.handshake(key);
        } catch (IOException e) {
            c.close();
            throw e;
        }
        return c;
    }

    public String address() { return address; }

    /**
     * Changes how long a read may wait from now on. A scan keeps the short default so a
     * dead host never holds the thread; an install raises it, because a slow device (an
     * Android TV box on poor Wi-Fi) can take well over the default to open the install
     * session or acknowledge a chunk of the APK.
     */
    public void setIoTimeout(int ms) throws IOException { sock.setSoTimeout(ms); }

    /** adbd's CNXN banner, e.g. {@code device::ro.product.name=…;ro.product.model=…;features=…}. */
    public String banner() { return banner; }

    private void handshake(PrivateKey key) throws IOException {
        send(A_CNXN, VERSION, MAX_PAYLOAD, ("host::features=cmd\0").getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        for (int i = 0; i < 6; i++) {
            Msg m = read();
            switch (m.cmd) {
                case A_AUTH:
                    if (m.arg0 != AUTH_TOKEN) throw new Refused("unexpected AUTH type " + m.arg0);
                    if (signed) throw new Refused("key not trusted");
                    send(A_AUTH, AUTH_SIGNATURE, 0, sign(key, m.data));
                    signed = true;
                    break;
                case A_CNXN:
                    remoteMax = Math.max(4096, Math.min(m.arg1, MAX_PAYLOAD));
                    banner = new String(m.data, StandardCharsets.UTF_8).replace("\0", "");
                    return;
                case A_STLS:
                    throw new Refused("device wants TLS (Wireless debugging), not plain adb");
                default:
                    // Nothing else is legal before CNXN; a stray OKAY/CLSE from a confused
                    // adbd is ignored, up to the loop bound.
                    break;
            }
        }
        throw new IOException("no CNXN after handshake");
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] msg = new byte[SHA1_DIGEST_INFO.length + token.length];
            System.arraycopy(SHA1_DIGEST_INFO, 0, msg, 0, SHA1_DIGEST_INFO.length);
            System.arraycopy(token, 0, msg, SHA1_DIGEST_INFO.length, token.length);
            return c.doFinal(msg);
        } catch (Exception e) {
            throw new IOException("sign: " + e);
        }
    }

    // ── streams ────────────────────────────────────────────────────────────────────

    /** Runs {@code cmd} through {@code shell:} and returns everything it printed. */
    public String shell(String cmd) throws IOException {
        return run("shell:" + cmd, null, 0);
    }

    /**
     * Opens {@code exec:cmd}, streams {@code body} ({@code len} bytes) into it, then returns
     * what the command printed. {@code adb install} is exactly this with
     * {@code cmd package install -r -S <len>}.
     */
    public String exec(String cmd, InputStream body, long len) throws IOException {
        return run("exec:" + cmd, body, len);
    }

    private String run(String dest, InputStream body, long len) throws IOException {
        int local = nextLocalId++;
        send(A_OPEN, local, 0, (dest + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = -1;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        // Wait for the stream to open.
        while (remote < 0) {
            Msg m;
            try {
                m = read();
            } catch (java.net.SocketTimeoutException e) {
                throw new IOException("timed out waiting for the device to start " + dest.replaceAll("^(shell|exec):", "").split(" ")[0]);
            }
            if (m.cmd == A_OKAY && m.arg1 == local) remote = m.arg0;
            else if (m.cmd == A_CLSE && m.arg1 == local) throw new IOException("stream refused: " + dest);
            else if (m.cmd == A_WRTE && m.arg1 == local) { // adbd may write before we see OKAY
                remote = m.arg0; outBuf.write(m.data); send(A_OKAY, local, remote, null);
            }
        }
        if (body != null) {
            byte[] chunk = new byte[remoteMax];
            long sent = 0;
            while (sent < len) {
                int want = (int) Math.min(chunk.length, len - sent);
                int n = body.read(chunk, 0, want);
                if (n < 0) throw new IOException("body ended early at " + sent + " of " + len);
                send(A_WRTE, local, remote, n == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, n));
                sent += n;
                // Flow control: one WRTE in flight. Output can arrive meanwhile.
                boolean acked = false;
                while (!acked) {
                    Msg m;
                    try {
                        m = read();
                    } catch (java.net.SocketTimeoutException e) {
                        throw new IOException("timed out sending to the device after " + sent + " of " + len + " bytes");
                    }
                    if (m.cmd == A_OKAY && m.arg1 == local) acked = true;
                    else if (m.cmd == A_WRTE && m.arg1 == local) { outBuf.write(m.data); send(A_OKAY, local, remote, null); }
                    else if (m.cmd == A_CLSE && m.arg1 == local) throw new IOException("stream closed by device after " + sent + " of " + len + " bytes: " + outBuf.toString("UTF-8").trim());
                }
            }
        }
        // Drain until the device closes the stream.
        while (true) {
            Msg m;
            try {
                m = read();
            } catch (java.net.SocketTimeoutException e) {
                send(A_CLSE, local, remote, null);
                throw new IOException("timed out waiting for " + dest.replaceAll("^(shell|exec):", "").split(" ")[0]);
            }
            if (m.arg1 != local && m.cmd != A_CNXN) continue;
            if (m.cmd == A_WRTE) { outBuf.write(m.data); send(A_OKAY, local, remote, null); }
            else if (m.cmd == A_CLSE) { send(A_CLSE, local, remote, null); break; }
        }
        return outBuf.toString("UTF-8");
    }

    // ── wire ──────────────────────────────────────────────────────────────────────

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        synchronized (out) {
            out.write(h.array());
            if (len > 0) out.write(data);
            out.flush();
        }
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt(); m.arg0 = h.getInt(); m.arg1 = h.getInt();
        int len = h.getInt(); h.getInt(); int magic = h.getInt();
        if (magic != ~m.cmd) throw new IOException("bad adb magic from " + address);
        if (len < 0 || len > MAX_PAYLOAD * 4) throw new IOException("bad adb length " + len);
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    @Override public void close() {
        try { sock.close(); } catch (IOException ignored) {}
    }

    // ── keys ──────────────────────────────────────────────────────────────────────

    /**
     * Parses an RSA private key in PEM: PKCS#8 ({@code PRIVATE KEY}) or adb's own
     * PKCS#1 ({@code RSA PRIVATE KEY}, wrapped into PKCS#8 here since Android's
     * KeyFactory only takes that).
     */
    public static PrivateKey parsePem(String pem) throws IOException {
        String p = pem.trim();
        boolean pkcs1 = p.contains("RSA PRIVATE KEY");
        StringBuilder b64 = new StringBuilder();
        for (String line : p.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("-----")) continue;
            b64.append(line);
        }
        byte[] der;
        try {
            der = Base64.decode(b64.toString(), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IOException("key is not base64");
        }
        if (pkcs1) der = wrapPkcs1(der);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IOException("key: " + e.getMessage());
        }
    }

    private static final byte[] RSA_ALG_ID = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00 };

    /** PKCS#8 = SEQUENCE { INTEGER 0, AlgorithmIdentifier rsaEncryption, OCTET STRING pkcs1 }. */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] octet = der(0x04, pkcs1);
        byte[] version = {0x02, 0x01, 0x00};
        byte[] inner = new byte[version.length + RSA_ALG_ID.length + octet.length];
        System.arraycopy(version, 0, inner, 0, version.length);
        System.arraycopy(RSA_ALG_ID, 0, inner, version.length, RSA_ALG_ID.length);
        System.arraycopy(octet, 0, inner, version.length + RSA_ALG_ID.length, octet.length);
        return der(0x30, inner);
    }

    private static byte[] der(int tag, byte[] body) {
        byte[] len;
        int n = body.length;
        if (n < 0x80) len = new byte[]{(byte) n};
        else if (n < 0x100) len = new byte[]{(byte) 0x81, (byte) n};
        else if (n < 0x10000) len = new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        else len = new byte[]{(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
        byte[] out = new byte[1 + len.length + n];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(body, 0, out, 1 + len.length, n);
        return out;
    }
}
