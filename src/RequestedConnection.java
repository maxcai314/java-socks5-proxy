import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;

public class RequestedConnection {
    public byte command;
    public InetSocketAddress address;

    public RequestedConnection(byte command, InetSocketAddress address) {
        this.command = command;
        this.address = address;
    }

    public GenericNetworkChannel connect() throws IOException {
        if (command == 0x01) {
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.connect(address);
            return new GenericNetworkChannel<>(socketChannel);
        } else if (command == 0x03) {
            DatagramChannel datagramChannel = DatagramChannel.open();
            datagramChannel.connect(address);
            return new GenericNetworkChannel<>(datagramChannel);
        } else {
            throw new IOException("Unsupported command: " + command);
        }
    }
}
