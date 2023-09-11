import java.io.IOException;

public class RepeatedlyRunProxy {
    private static final System.Logger logger = System.getLogger(RepeatedlyRunProxy.class.getName());

    public static void main(String[] args) throws IOException {
        try (
            ProxyServer proxyServer = new ProxyServer();
            IndependentTaskExecutor<Exception> executor = new IndependentTaskExecutor<>("Proxy Server", logger);
        ) {
            for (int i = 0; i < 100; i++) {
                // todo: add a count of open connections, and use while loop to always fill
                executor.submit("SOCKS5 Session", proxyServer::acceptSocketConnection);
            }
        }
    }
}
