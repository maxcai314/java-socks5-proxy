package TCPEchoServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
//import java.util.Scanner;

public class Client {

    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 6666)) {
            PrintWriter socketOut = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String response;

            socketOut.println("Hello!");
            response = socketIn.readLine();
            System.out.println(response);

            socketOut.println("Ça va?");
            response = socketIn.readLine();
            System.out.println(response);

            socketOut.println("Génial!");
            response = socketIn.readLine();
            System.out.println(response);

            socketOut.println("quit");
            response = socketIn.readLine();
            System.out.println(response);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}