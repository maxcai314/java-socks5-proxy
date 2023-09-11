import java.io.IOException;
import java.nio.channels.SocketChannel;

public class RepeatedlyRunProxy {
    private static final System.Logger logger = System.getLogger(RepeatedlyRunProxy.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        try (
            ProxyServer proxyServer = new ProxyServer();
            IndependentTaskExecutor<Exception> executor = new IndependentTaskExecutor<>("Proxy Server", logger);
        ) {
            while (!Thread.interrupted()) {
                SocketChannel socketChannelClient = proxyServer.accept();
                executor.submit("SOCKS5 Proxy Session", () -> proxyServer.handleConnection(socketChannel));
            }
        }
    }
}
