public class RepeatedlyRunProxy {
    public static void main(String[] args) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            ProxyServer.main(args);
            Thread.sleep(1000);
        }
    }
}
