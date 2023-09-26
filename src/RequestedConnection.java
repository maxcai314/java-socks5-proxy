import java.io.IOException;
import java.net.InetSocketAddress;

public record RequestedConnection (
    byte command,
    InetSocketAddress address
) {}
