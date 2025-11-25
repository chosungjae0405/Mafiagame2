package client.network;

import java.io.*;
import java.net.Socket;
import java.util.function.Consumer;

public class Client {

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Consumer<String> onMessage;

    public boolean connect(String host, int port, Consumer<String> onMessage) {
        this.onMessage = onMessage;

        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            System.out.println("클라이언트 접속 성공!");

            // 서버 → 클라 수신 스레드
            Thread listener = new Thread(() -> {
                try {
                    String msg;
                    while ((msg = in.readLine()) != null) {
                        Consumer<String> handler = this.onMessage;
                        if (handler != null) {
                            handler.accept(msg);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("서버 연결 끊김");
                }
            });
            listener.setDaemon(true);
            listener.start();

            return true;

        } catch (IOException e) {
            System.out.println("접속 실패: " + e.getMessage());
            return false;
        }
    }

    /** 🔵 메시지 핸들러 교체 (Lobby → GameRoom 전환) */
    public void setMessageHandler(Consumer<String> onMessage) {
        this.onMessage = onMessage;
    }

    public void send(String msg) {
        if (out != null) {
            out.println(msg);
        }
    }

    public void createRoom(String roomName) {
        send("CREATE_ROOM|" + roomName);
    }

    public void requestRoomList() {
        send("GET_ROOMS");
    }

    public void joinRoom(String nickname, String roomId) {
        send("JOIN_ROOM|" + nickname + "|" + roomId);
    }

    public void requestPlayerList(String roomId) {
        send("GET_PLAYERS|" + roomId);
    }
}
