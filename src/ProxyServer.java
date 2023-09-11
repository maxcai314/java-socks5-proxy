import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class ProxyServer {
	public static final byte SOCKS_VERSION = 0x05;
    public static final byte RESERVED_BYTE = 0x00;

	public static boolean authMethodPresent(byte[] authMethods, byte authMethod) {
		for (byte b : authMethods) {
			if (b == authMethod) {
				return true;
			}
		}
		return false;
	}



	public static void main(String[] args) {
		try (
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open().bind(new InetSocketAddress(6789));
            SocketChannel socketChannel = serverSocketChannel.accept();
        ) {
			ByteBuffer inputBuffer = ByteBuffer.allocateDirect(1024);
			ByteBuffer outputBuffer = ByteBuffer.allocateDirect(1024);


			// client greeting
			socketChannel.read(inputBuffer);
			byte socksVersion = inputBuffer.get();
			byte numAuthMethods = inputBuffer.get();
			byte[] authMethods = new byte[numAuthMethods];
			inputBuffer.get(authMethods);

			assert(socksVersion == SOCKS_VERSION);

			byte acceptedAuthMethod = 0x00; // no auth
			if (authMethodPresent(authMethods, acceptedAuthMethod)) {
				// we can proceed
				outputBuffer
					.put(SOCKS_VERSION)
					.put(acceptedAuthMethod);
                outputBuffer.flip();
				socketChannel.write(outputBuffer);
                outputBuffer.clear();
			} else {
				// we can't proceed
				// send auth failed and close server
                outputBuffer
                    .put(SOCKS_VERSION)
                    .put((byte) 0xFF);
                outputBuffer.flip();
                socketChannel.write(outputBuffer);
                outputBuffer.clear();
                return;
			}

            // client connection request
            socketChannel.read(inputBuffer);
            socksVersion = inputBuffer.get();
            byte command = inputBuffer.get();
            byte reserved = inputBuffer.get();
            byte addressType = inputBuffer.get();

            assert(socksVersion == SOCKS_VERSION);
            assert(reserved == RESERVED_BYTE);

            InetAddress requestedAddress;
            if (command == 0x01) { // todo: maybe make these enums or static variables
                // IPv4
                byte[] addressBytes = new byte[4];
                inputBuffer.get(addressBytes);
                requestedAddress = InetAddress.getByAddress(addressBytes);
            } else if (command == 0x04) {
                // IPv6
                byte[] addressBytes = new byte[16];
                inputBuffer.get(addressBytes);
                requestedAddress = InetAddress.getByAddress(addressBytes);
            } else if (command == 0x03) {
                // domain name
                int domainNameLength = Byte.toUnsignedInt(inputBuffer.get());
                byte[] domainNameBytes = new byte[domainNameLength];
                inputBuffer.get(domainNameBytes);
                String domainName = new String(domainNameBytes, StandardCharsets.UTF_8);
                requestedAddress = InetAddress.getByName(domainName);
            } else {
                // invalid command
                outputBuffer
                    .put(SOCKS_VERSION)
                    .put((byte) 0x07); // command not supported
                return;
            }

            int requestedPort = Short.toUnsignedInt(inputBuffer.getShort());

            // reply
            outputBuffer
                .put(SOCKS_VERSION)
                .put((byte) 0x00) // request granted
                .put((byte) RESERVED_BYTE); // reserved
            // this could be its own method
            outputBuffer.put(addressType); // IPv4
            if (addressType == 0x01 || addressType == 0x04) {
                // IPv4 or IPv6
                outputBuffer.put(requestedAddress.getAddress());
            } else if (addressType == 0x03) {
                // domain name
                byte[] domainNameBytes = requestedAddress.getHostName().getBytes(StandardCharsets.UTF_8);
                outputBuffer
                    .put((byte) domainNameBytes.length) // IPv4
                    .put(domainNameBytes);
            }
            outputBuffer.putShort((short) requestedPort);
            outputBuffer.flip();
            socketChannel.write(outputBuffer);
            outputBuffer.clear();

            // connect to requested address



		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
