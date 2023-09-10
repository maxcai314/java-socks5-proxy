package TCPEchoServer;

import java.io.*;
import java.net.*;
//import java.util.Scanner;

public class Server {

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(6666)) {
            Socket socket = serverSocket.accept();

            PrintWriter socketOut = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String input;
            while ((input = socketIn.readLine()) != null) {
                if (input.equals("quit")) {
                    socketOut.println("stopping server");
                    System.out.println("stopping server");
                    break;
                }
                socketOut.println(input); // echo back to client
                System.out.println(input); // print to own console
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}