package client;

import client.network.Client;

public class TestClient {
    public static void main(String[] args) {

        Client client = new Client();

        // 서버로부터 받은 메시지를 출력하는 콜백
        client.connect("localhost", 6000, msg -> {
            System.out.println("서버 → " + msg);
        });

        // 서버에 메시지 보내기
        client.send("안녕! 나는 다예 클라이언트야!");

        // 프로그램 종료 방지
        try {
            Thread.sleep(5000); // 5초 대기
        } catch (InterruptedException e) {
        }
    }
}
