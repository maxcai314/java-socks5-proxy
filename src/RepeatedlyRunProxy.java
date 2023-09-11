import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RepeatedlyRunProxy {
    public static void main(String[] args) throws IOException {
        ProxyServer proxyServer = new ProxyServer();
        ExecutorService executorService = Executors.newCachedThreadPool();
        for (int i = 0; i < 10; i++) {
            executorService.submit(() -> {
                try {
                    proxyServer.acceptSocketConnection();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
    }
}
