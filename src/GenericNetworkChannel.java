// write a class that takes in RequestedConnection and can either represent a SocketChannel or a DatagramChannel.
// Both of these classes implement ReadableByteChannel and WriteableByteChannel
// use generics
// T channel implements ReadableByteChannel, WriteableByteChannel


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NetworkChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

public class GenericNetworkChannel<T extends ReadableByteChannel & WritableByteChannel> implements ReadableByteChannel, WritableByteChannel {
    public final T channel;

    public GenericNetworkChannel(T channel) {
        this.channel = channel;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return channel.read(dst);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return channel.write(src);
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}