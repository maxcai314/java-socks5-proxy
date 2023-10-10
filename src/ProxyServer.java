import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;

import static java.lang.System.Logger.Level.INFO;

public class ProxyServer implements Closeable{
	private static final System.Logger logger = System.getLogger(ProxyServer.class.getName());

	public static final byte SOCKS_VERSION = 0x05;
	public static final byte RESERVED_BYTE = 0x00;

	private final ServerSocketChannel serverSocketChannel;

	public ProxyServer(int proxyPort) throws IOException {
		this.serverSocketChannel = ServerSocketChannel.open().bind(new InetSocketAddress(proxyPort));
	}

	public ProxyServer() throws IOException {
		this(314);
	}

	@Override
	public void close() throws IOException {
		serverSocketChannel.close();
	}

	protected record Socks5Address(
	    byte command,
	    InetSocketAddress address
	) {}

	private static boolean authMethodPresent(byte[] authMethods, byte authMethod) {
		for (byte b : authMethods) {
			if (b == authMethod) {
				return true;
			}
		}
		return false;
	}

	private static void waitForBytes(ReadableByteChannel socketChannel, ByteBuffer readBuffer, int numBytes) throws IOException {
		readBuffer.limit(readBuffer.position() + numBytes);
		while (readBuffer.hasRemaining()) {
			socketChannel.read(readBuffer);
		}
	}

	private Socks5Address socks5Negotiation(SocketChannel socketChannel) throws IOException {
		ByteBuffer inputBuffer = ByteBuffer.allocateDirect(1024);
		ByteBuffer outputBuffer = ByteBuffer.allocateDirect(1024);


		// client greeting
		inputBuffer.clear();
		waitForBytes(socketChannel, inputBuffer, 2);
		inputBuffer.flip();

		byte socksVersion = inputBuffer.get();
		int numAuthMethods = Byte.toUnsignedInt(inputBuffer.get());

		inputBuffer.clear();
        waitForBytes(socketChannel, inputBuffer, numAuthMethods);
		inputBuffer.flip();

		byte[] authMethods = new byte[numAuthMethods];
		inputBuffer.get(authMethods);

		assert(socksVersion == SOCKS_VERSION);

		byte acceptedAuthMethod = 0x00; // no auth
		if (authMethodPresent(authMethods, acceptedAuthMethod)) {
			// we can proceed
			outputBuffer.clear();
			outputBuffer
					.put(SOCKS_VERSION)
					.put(acceptedAuthMethod);
			outputBuffer.flip();
			socketChannel.write(outputBuffer);
		} else {
			// we can't proceed
			// send auth failed and close server
			outputBuffer.clear();
			outputBuffer
					.put(SOCKS_VERSION)
					.put((byte) 0xFF);
			outputBuffer.flip();
			socketChannel.write(outputBuffer);
			throw new RuntimeException("No acceptable auth method");
		}

		// client connection request
		inputBuffer.clear();
		waitForBytes(socketChannel, inputBuffer, 4);
		inputBuffer.flip();

		socksVersion = inputBuffer.get();
		byte command = inputBuffer.get();
		byte reserved = inputBuffer.get();
		byte addressType = inputBuffer.get();

		System.out.printf("socksVersion: %d, command: %d, reserved: %d, addressType: %d\n", socksVersion, command, reserved, addressType);

		assert(socksVersion == SOCKS_VERSION);
		assert(reserved == RESERVED_BYTE);

		InetAddress requestedAddress;
		if (addressType == 0x01) { // todo: maybe make these enums or static variables
			// IPv4
			inputBuffer.clear();
            waitForBytes(socketChannel, inputBuffer, 4);
			inputBuffer.flip();

			byte[] addressBytes = new byte[4];
			inputBuffer.get(addressBytes);
			requestedAddress = InetAddress.getByAddress(addressBytes);
		} else if (addressType == 0x04) {
			// IPv6
			inputBuffer.clear();
            waitForBytes(socketChannel, inputBuffer, 16);
			inputBuffer.flip();

			byte[] addressBytes = new byte[16];
			inputBuffer.get(addressBytes);
			requestedAddress = InetAddress.getByAddress(addressBytes);
		} else if (addressType == 0x03) {
			// domain name
			inputBuffer.clear();
            waitForBytes(socketChannel, inputBuffer, 1);
			inputBuffer.flip();

			int domainNameLength = Byte.toUnsignedInt(inputBuffer.get());

			inputBuffer.clear();
            waitForBytes(socketChannel, inputBuffer, domainNameLength);
			inputBuffer.flip();

			byte[] domainNameBytes = new byte[domainNameLength];
			inputBuffer.get(domainNameBytes);
			String domainName = new String(domainNameBytes, StandardCharsets.UTF_8);
			requestedAddress = InetAddress.getByName(domainName);
		} else {
			// invalid addressType
			outputBuffer.clear();
			outputBuffer
					.put(SOCKS_VERSION)
					.put((byte) 0x07); // addressType not supported
			outputBuffer.flip();
			socketChannel.write(outputBuffer);
			throw new RuntimeException("Invalid addressType: " + addressType);
		}

		inputBuffer.clear();
		waitForBytes(socketChannel, inputBuffer, 2);
		inputBuffer.flip();

		int requestedPort = Short.toUnsignedInt(inputBuffer.getShort());

		// reply
		outputBuffer.clear();
		outputBuffer
				.put(SOCKS_VERSION)
				.put((byte) 0x00) // request granted
				.put((byte) RESERVED_BYTE); // reserved

		InetSocketAddress address = new InetSocketAddress(requestedAddress, requestedPort);
		logger.log(INFO, "requested server connection with {0}", address);
		return new Socks5Address(command, address);
	}

	private SocketChannel sendResponse(Socks5Address requestedAddress) {
		SocketChannel bindChannel = null;

		if (requestedAddress.command == 0x02) {
			// bind command
			bindChannel = socketChannel.bind(new InetSocketAddress(0));
			InetSocketAddress bindAddress = (InetSocketAddress) bindChannel.getLocalAddress();

			byte versionTag = switch (bindAddress.getAddress()) {
				case Inet4Address _ -> 0x01;
				case Inet6Address _ -> 0x04;
				default -> throw new AssertionError("OS-assigned bind address is not an InetAddress");
			};

			outputBuffer
					.put(versionTag)
					.put(bindAddress.getAddress().getAddress())
					.putShort((short) bindAddress.getPort());
		} else {
			// send IPv4 address 0.0.0.0:0000
			outputBuffer
					.put((byte) 0x01) // IPv4
					.put(new byte[]{0, 0, 0, 0}) // 0.0.0.0
					.putShort((short) 0); // port 0
		}

		outputBuffer.flip();
		socketChannel.write(outputBuffer);

		InetSocketAddress address = new InetSocketAddress(requestedAddress, requestedPort);
		logger.log(INFO, "opening server connection with {0}", address);
		return bindChannel;
	}

	private static void forwardPackets(ReadableByteChannel socketChannelIn, WritableByteChannel socketChannelOut) throws IOException {
		ByteBuffer inputBuffer = ByteBuffer.allocateDirect(4096); // 1 page

		while (!Thread.interrupted()) {
			inputBuffer.clear();
			int bytesReceived = socketChannelIn.read(inputBuffer); // gets request from In
			inputBuffer.flip();
			System.out.println("forwarding packets");
			System.out.println("bytes: " + bytesReceived);

			if (bytesReceived == -1) {
				throw new IOException("End of Stream");
			}

			socketChannelOut.write(inputBuffer); // sends request to Out
		}
	}

	public SocketChannel accept() throws IOException {
		return serverSocketChannel.accept();
	}

	public void handleSocketConnection(SocketChannel socketChannelClient) throws IOException, InterruptedException {
		try (
			socketChannelClient; // used to auto close socketChannelClient after try block
			PersistentTaskExecutor<IOException> executor = new PersistentTaskExecutor<>("forwardPackets", IOException::new, logger);
		) {
			Socks5Address requestedConnection = socks5Negotiation(socketChannelClient);
			SocketChannel bindChannel = sendResponse(requestedConnection);

			if (requestedConnection.command == 0x01) {
				// establish a TCP stream
				try (SocketChannel socketChannelServer = SocketChannel.open()) {
					socketChannelServer.connect(requestedConnection.address);
					executor.submit("Forward Requests", () -> forwardPackets(socketChannelClient, socketChannelServer));
					executor.submit("Forward Responses", () -> forwardPackets(socketChannelServer, socketChannelClient));

					executor.join();
					executor.throwIfFailed();
				}
			} else if (requestedConnection.command == 0x02) {
				// establish a TCP binding
				try (
						bindChannel; // used to auto close bindChannel
						ServerSocketChannel serverSocketLocal = ServerSocketChannel.open()
								.bind(requestedConnection.bindChannel.getLocalAddress());
						SocketChannel socketChannelLocal = serverSocketLocal.accept();
					) {
					executor.submit("Forward Requests", () -> forwardPackets(socketChannelClient, socketChannelLocal));
					executor.submit("Forward Responses", () -> forwardPackets(socketChannelLocal, socketChannelClient));

					executor.join();
					executor.throwIfFailed();
				}
			} else if (requestedConnection.command == 0x03) {
				// establish a UDP stream
				try (DatagramChannel dataChannelServer = DatagramChannel.open()) {
					dataChannelServer.connect(requestedConnection.address);
					executor.submit("Forward Requests", () -> forwardPackets(socketChannelClient, dataChannelServer));
					executor.submit("Forward Responses", () -> forwardPackets(dataChannelServer, socketChannelClient));

					executor.join();
					executor.throwIfFailed();
				}
			} else {
				throw new IOException("Unsupported command: " + requestedConnection.command);
			}
		} finally {
			logger.log(INFO, "closing server connection");
		}
	}
}
