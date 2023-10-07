import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
	private final Socks5Address bindAddress;

	public ProxyServer(int proxyPort, int bindPort) throws IOException {
		this.serverSocketChannel = ServerSocketChannel.open().bind(new InetSocketAddress(proxyPort));
		this.bindAddress = Socks5Address.fromInetSocketAddress(new InetSocketAddress(InetAddress.getLocalHost(), bindPort));
	}

	public ProxyServer() throws IOException {
		this(314, 315);
	}

	@Override
	public void close() throws IOException {
		serverSocketChannel.close();
	}

	protected record Socks5Address(
	    byte command,
	    InetSocketAddress address
	) {
		public static Socks5Address fromInetSocketAddress(InetSocketAddress address) {
			// figure out if its ipv4 or ipv6
			if (address.getAddress().getAddress().length == 4) {
				return new Socks5Address((byte) 0x01, address);
			} else {
				return new Socks5Address((byte) 0x04, address);
			}
		}
	}

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
		// this could be its own method
		outputBuffer.put(addressType); // IPv4 or IPv6
		outputBuffer.put(bindAddress.address().getAddress().getAddress());
		outputBuffer.putShort((short) bindAddress.address().getPort());

		outputBuffer.flip();
		socketChannel.write(outputBuffer);

		InetSocketAddress address = new InetSocketAddress(requestedAddress, requestedPort);
		logger.log(INFO , "opening server connection with {0}", address);
		return new Socks5Address(command, address);
	}

	private static void forwardPackets(ReadableByteChannel socketChannelIn, WritableByteChannel socketChannelOut) throws IOException {
		ByteBuffer inputBuffer = ByteBuffer.allocateDirect(4096); // 1 page

		while (!Thread.interrupted()) {
			inputBuffer.clear();
			int bytesRecieved = socketChannelIn.read(inputBuffer); // gets request from In
			inputBuffer.flip();
			System.out.println("forwarding packets");
			System.out.println("bytes: " + bytesRecieved);

			if (bytesRecieved == -1) {
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
			PersistentTaskExecutor<IOException> executor = new PersistentTaskExecutor<>("forwardPackets", IOException::new, logger);
		) {
			Socks5Address requestedServer = socks5Negotiation(socketChannelClient);

			if (requestedServer.command == 0x01) {
				// establish a TCP stream
				try (SocketChannel socketChannelServer = SocketChannel.open()) {
					socketChannelServer.connect(requestedServer.address);
					executor.submit("Forward Requests", () -> forwardPackets(socketChannelClient, socketChannelServer));
					executor.submit("Forward Responses", () -> forwardPackets(socketChannelServer, socketChannelClient));

					executor.join();
					executor.throwIfFailed();
				}
			} else if (requestedServer.command == 0x02) {
				// establish a TCP binding
				try (ServerSocketChannel socketChannelLocal = ServerSocketChannel.open()) {
					socketChannelLocal.bind(null, bindAddress.address.getPort());
					SocketChannel socketChannelBind = socketChannelLocal.accept();
					executor.submit("Forward Requests", () -> forwardPackets(socketChannelClient, socketChannelBind));
					executor.submit("Forward Responses", () -> forwardPackets(socketChannelBind, socketChannelClient));

					executor.join();
					executor.throwIfFailed();
				}
			} else if (requestedServer.command == 0x03) {
				// establish a UDP stream
				try (DatagramChannel dataChannelServer = DatagramChannel.open()) {
					dataChannelServer.connect(requestedServer.address);
					executor.submit("Forward Requests", () -> forwardPackets(socketChannelClient, dataChannelServer));
					executor.submit("Forward Responses", () -> forwardPackets(dataChannelServer, socketChannelClient));

					executor.join();
					executor.throwIfFailed();
				}
			} else {
				throw new IOException("Unsuppourted command: " + requestedServer.command);
			}
		} finally {
			logger.log(INFO, "closing server connection");
			socketChannelClient.close();
		}
	}
}
