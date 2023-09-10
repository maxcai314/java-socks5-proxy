import java.io.*;
import java.net.*;

public class ProxyServer {

    // later on, use enum for auth methods
    public static boolean authMethodPresent(byte[] buffer, byte authMethod) {
        assert(buffer[0] == 0x05); // version
        System.out.println("client greeting: ");
        int i = buffer[1]; // number of methods
        System.out.printf("number of methods: %d\n", i);
        for (int j = 0; j < i; j++) {
            System.out.printf("%02x ", buffer[j + 2]);
            if (buffer[j + 2] == authMethod) {
                return true;
            }
        }
        return false;
    }

    public static boolean authUserPassword(byte[] buffer) {
        assert(buffer[0] == 0x01); // password version
        int usernameLength = buffer[1];
        String username = new String(buffer, 2, usernameLength);
        int passwordLength = buffer[2 + usernameLength];
        String password = new String(buffer, 3 + usernameLength, passwordLength);
        return username.equals("user") && password.equals("password");
    }

    // get the address requested by the client
    public static String getAddress(byte[] buffer) {
        byte addressType = buffer[3];
        if (addressType == 0x01) {
            // IPv4
            return String.format("%d.%d.%d.%d", buffer[4], buffer[5], buffer[6], buffer[7]);
        } else if (addressType == 0x03) {
            // domain name
            int domainLength = buffer[4];
            return new String(buffer, 5, domainLength);
        } else if (addressType == 0x04) {
            // IPv6
            // convert 16 bytes to 8 2-byte hex strings
            String[] hexStrings = new String[8];
            for (int i = 0; i < 8; i++) {
                hexStrings[i] = String.format("%02x%02x", buffer[2 * i + 4], buffer[2 * i + 5]);
            }
            return String.join(":", hexStrings);
        }
        return null;
    }

    public static int getPort(byte[] buffer) {
        // big endian
        byte addressType = buffer[3];
        if (addressType == 0x01) {
            // IPv4
            return (buffer[9] << 8) | buffer[8];
        } else if (addressType == 0x03) {
            // domain name
            int domainLength = buffer[4];
            System.out.println(buffer[5 + domainLength] + " " + buffer[6 + domainLength]);
            return (((int) buffer[5 + domainLength]) << 8) | -((int) buffer[6 + domainLength]);
        } else if (addressType == 0x04) {
            // IPv6
            return (buffer[21] << 8) | buffer[20];
        }
        return -1;
    }

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(6789)) {
            Socket socket = serverSocket.accept();

            System.out.println("client connected");

            byte[] buffer = new byte[2048];

            int bytesRead;

            // handshake

            // greeting
            bytesRead = socket.getInputStream().read(buffer);

            byte chosenAuthMethod = 0x00; // username / password

            if (authMethodPresent(buffer, chosenAuthMethod)) {
                // send response
                byte[] response = {0x05, chosenAuthMethod};
                socket.getOutputStream().write(response);
            } else {
                // send response
                byte[] response = {0x05, (byte) 0xFF};
                socket.getOutputStream().write(response);
                socket.close();
                return;
            }

//            System.out.println("choosing username / password auth");
            System.out.println("choosing no auth");

            // client authentication request
//            bytesRead = socket.getInputStream().read(buffer);

//            if (authUserPassword(buffer)) {
//                // send response
//                byte[] response = {0x01, 0x00};
//                socket.getOutputStream().write(response);
//            } else {
//                // send response
//                byte[] response = {0x01, 0x01};
//                socket.getOutputStream().write(response);
//                socket.close();
//                return;
//            }

            // send response
//            socket.getOutputStream().write(new byte[] {0x01, 0x00});

            System.out.println("auth successful");

            // client connection request
            bytesRead = socket.getInputStream().read(buffer);
            assert(buffer[0] == 0x05); // version
            assert(buffer[2] == 0x00); // reserved
            String address = getAddress(buffer);
            assert(address != null);
            // port number in network byte order
            int port = getPort(buffer);
            assert(port != -1);

            // send response
            byte[] response = new byte[bytesRead];
            System.arraycopy(buffer, 0, response, 0, bytesRead);
            response[1] = 0x00; // success
            socket.getOutputStream().write(response);

            System.out.println("client requested " + address + ":" + port);

            // do the proxying

            while (socket.getInputStream().available() != 0) {
                bytesRead = socket.getInputStream().read(buffer);
                System.out.println("read " + bytesRead + " bytes");
                System.out.println(new String(buffer, 0, bytesRead));
                Socket targetSocket = new Socket(address, port);
                targetSocket.getOutputStream().write(buffer, 0, bytesRead);
                targetSocket.getOutputStream().flush();
                int targetBytesRead;
                while (targetSocket.getInputStream().available() != 0) {
                    targetBytesRead = targetSocket.getInputStream().read(buffer);
                    System.out.println("read " + targetBytesRead + " bytes");
                    System.out.println(new String(buffer, 0, targetBytesRead));
                    socket.getOutputStream().write(buffer, 0, targetBytesRead);
                    socket.getOutputStream().flush();
                }
                targetSocket.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
