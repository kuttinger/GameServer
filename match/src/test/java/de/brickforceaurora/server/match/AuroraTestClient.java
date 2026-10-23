package de.brickforceaurora.server.match;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import de.brickforceaurora.server.util.Encryption;

/**
 * Minimal client speaking the Aurora protocol, used to test the server end to end.
 *
 * This is also the reference for client implementations (e.g. the C# game client):
 *
 * <pre>
 * Frame:  uint32 LE body length | uint16 LE msg id | uint8 crc (xor of body) | 8 bytes 0xFF | body
 * Types:  int = int32 LE, string = int32 LE byte length + UTF-16LE, bytes = int32 LE length + raw
 *
 * C -> S 1004 HandshakeInitialize  bytes modulus, bytes exponent        (client RSA-1024 public key, big endian)
 * S -> C 1005 HandshakeChallenge   bytes modulus, bytes exponent, bytes challenge (encrypted with client key)
 * C -> S 1006 HandshakeComplete    bytes challenge                      (plain challenge re-encrypted with server key)
 * S -> C 1007 RequestLogin         [encrypted with client key] int supported login type mask (0x8 = account ticket)
 * C -> S 1008 Login                [encrypted with server key] int major, minor, patch, revision, int login type,
 *                                  string username, string ticket
 * S -> C 1010 LoggedIn, then the original login packets (channels, player info, 464 item list, ...)
 *
 * Encryption: RSA/PKCS#1 v1.5, plaintext split into chunks of (keyBytes - 11), each chunk becomes one keyBytes block.
 * </pre>
 */
final class AuroraTestClient implements AutoCloseable {

    record Packet(int id, byte[] body) {

        ByteBuffer buffer() {
            return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        }

    }

    private final Socket socket;
    private final DataInputStream input;
    private final OutputStream output;
    private final KeyPair keyPair;
    private PublicKey serverKey;

    AuroraTestClient(final String host, final int port) throws Exception {
        this.socket = new Socket(host, port);
        this.socket.setSoTimeout(10_000);
        this.input = new DataInputStream(socket.getInputStream());
        this.output = socket.getOutputStream();
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        this.keyPair = generator.generateKeyPair();
    }

    /**
     * Runs the handshake and returns the supported login type mask.
     */
    int handshake() throws IOException {
        final RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        final Body init = new Body();
        init.bytes(unsigned(publicKey.getModulus()));
        init.bytes(unsigned(publicKey.getPublicExponent()));
        send(1004, init.toArray());

        final ByteBuffer challengePacket = expect(1005).buffer();
        final BigInteger modulus = new BigInteger(1, readBytes(challengePacket));
        final BigInteger exponent = new BigInteger(1, readBytes(challengePacket));
        serverKey = Encryption.createPublicKey(modulus, exponent);
        final String challenge = new String(Encryption.decrypt(readBytes(challengePacket), (RSAPrivateKey) keyPair.getPrivate()),
            StandardCharsets.UTF_8);

        final Body complete = new Body();
        complete.bytes(Encryption.encrypt(challenge.getBytes(StandardCharsets.UTF_8), serverKey));
        send(1006, complete.toArray());

        final byte[] requestLogin = Encryption.decrypt(expect(1007).body(), (RSAPrivateKey) keyPair.getPrivate());
        return ByteBuffer.wrap(requestLogin).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    void loginWithTicket(final String ticket) throws IOException {
        final Body login = new Body();
        login.integer(2).integer(3).integer(0).integer(0); // client version
        login.integer(0x8); // LoginType.ACCOUNT_TICKET
        login.string(""); // the server takes the name from the account
        login.string(ticket);
        send(1008, Encryption.encrypt(login.toArray(), serverKey));
    }

    Packet expect(final int id) throws IOException {
        for (int i = 0; i < 64; i++) {
            final Packet packet = read();
            if (packet.id() == 1003) {
                final ByteBuffer buffer = packet.buffer();
                throw new IOException("Disconnected by server: " + readString(buffer));
            }
            if (packet.id() == id) {
                return packet;
            }
        }
        throw new IOException("Packet " + id + " not received");
    }

    void send(final int id, final byte[] body) throws IOException {
        byte crc = 0;
        for (final byte b : body) {
            crc ^= b;
        }
        final ByteBuffer header = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(body.length).putShort((short) id).put(crc).putInt(0xFFFFFFFF).putInt(0xFFFFFFFF);
        output.write(header.array());
        output.write(body);
        output.flush();
    }

    Packet read() throws IOException {
        final byte[] header = new byte[15];
        input.readFully(header);
        final ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        final int length = buffer.getInt();
        final int id = Short.toUnsignedInt(buffer.getShort());
        final byte[] body = new byte[length];
        input.readFully(body);
        return new Packet(id, body);
    }

    static byte[] readBytes(final ByteBuffer buffer) {
        final byte[] bytes = new byte[buffer.getInt()];
        buffer.get(bytes);
        return bytes;
    }

    static String readString(final ByteBuffer buffer) {
        return new String(readBytes(buffer), StandardCharsets.UTF_16LE);
    }

    private static byte[] unsigned(final BigInteger value) {
        final byte[] bytes = value.toByteArray();
        return bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    static final class Body {

        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

        Body integer(final int value) {
            stream.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
            return this;
        }

        Body bytes(final byte[] value) {
            integer(value.length);
            stream.writeBytes(value);
            return this;
        }

        Body string(final String value) {
            return bytes(value.getBytes(StandardCharsets.UTF_16LE));
        }

        byte[] toArray() {
            return stream.toByteArray();
        }

    }

}
