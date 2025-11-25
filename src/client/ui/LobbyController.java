package client.ui;

import client.network.Client;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.Parent;
import javafx.stage.Stage;

public class LobbyController {

    @FXML private TextField nicknameField;
    @FXML private TextField roomNameField;
    @FXML private ListView<String> roomList;
    @FXML private Label statusLabel;

    @FXML private ComboBox<String> modeBox;
    @FXML private ComboBox<Integer> limitBox;

    private Client client;

    @FXML
    public void initialize() {

        client = new Client();

        // 서버 연결 (여기서 메시지 핸들러 등록됨)
        if (!client.connect("localhost", 6000, this::onMessageReceived)) {
            statusLabel.setText("❌ 서버 연결 실패");
            return;
        }

        // 기본 모드 / 인원 설정
        if (modeBox != null) {
            modeBox.getItems().setAll("CLASSIC", "SPECIAL");
            
            // ComboBox 스타일 강제 적용
            modeBox.setStyle(
                "-fx-background-color: #222222; " +
                "-fx-border-color: #444444; " +
                "-fx-border-width: 1px; " +
                "-fx-border-radius: 3px; " +
                "-fx-background-radius: 3px;"
            );
            
            // ButtonCell 스타일 (선택된 항목 표시)
            modeBox.setButtonCell(new javafx.scene.control.ListCell<String>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText("Select mode");
                        setStyle("-fx-text-fill: #777777;");
                    } else {
                        setText(item);
                        setStyle("-fx-text-fill: #ffffff; -fx-font-size: 14px;");
                    }
                    setStyle(getStyle() + "-fx-background-color: transparent;");
                }
            });
        }

        if (limitBox != null) {
            limitBox.getItems().setAll(5, 6, 7, 8, 9, 10);
            
            // ComboBox 스타일 강제 적용
            limitBox.setStyle(
                "-fx-background-color: #222222; " +
                "-fx-border-color: #444444; " +
                "-fx-border-width: 1px; " +
                "-fx-border-radius: 3px; " +
                "-fx-background-radius: 3px;"
            );
            
            // ButtonCell 스타일 (선택된 항목 표시)
            limitBox.setButtonCell(new javafx.scene.control.ListCell<Integer>() {
                @Override
                protected void updateItem(Integer item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText("5~10 players");
                        setStyle("-fx-text-fill: #777777;");
                    } else {
                        setText(item + " players");
                        setStyle("-fx-text-fill: #ffffff; -fx-font-size: 14px;");
                    }
                    setStyle(getStyle() + "-fx-background-color: transparent;");
                }
            });
        }

        // 방 목록 요청
        client.send("GET_ROOMS");
    }

    /** 서버에서 오는 메시지 처리 */
    private void onMessageReceived(String msg) {

        // 방 리스트 업데이트
        if (msg.startsWith("ROOM_LIST|")) {
            Platform.runLater(() -> updateRoomList(msg));
        }

        // 방 생성 완료 → 새 목록 자동 반영됨 (서버 broadcast)
        else if (msg.startsWith("ROOM_CREATED")) {
            client.send("GET_ROOMS");
        }

        // 방 입장 성공
        else if (msg.startsWith("JOIN_OK|")) {
            String[] p = msg.split("\\|");
            String roomId = p[1];
            String roomName = p[2];
            String hostNickname = (p.length >= 4) ? p[3] : null;

            String myNickname = nicknameField.getText().trim();

            Platform.runLater(() -> enterGameRoom(roomId, roomName, hostNickname, myNickname));
        }

        // 방 입장 실패
        else if (msg.startsWith("JOIN_FAIL|")) {
            Platform.runLater(() -> {
                String reason = msg.contains("FULL") ? "방이 꽉 찼습니다." : "방을 찾을 수 없습니다.";
                statusLabel.setText("❌ 입장 실패: " + reason);
            });
        }
    }

    /** 방 리스트 업데이트 */
    private void updateRoomList(String msg) {
        roomList.getItems().clear();

        String data = msg.substring("ROOM_LIST|".length());
        String[] rooms = data.split(",");

        for (String r : rooms) {
            if (!r.trim().isEmpty()) {
                roomList.getItems().add(r.trim());
            }
        }
    }

    /** 방 생성 버튼 클릭 */
    @FXML
    private void handleCreateRoom() {

        String nickname = nicknameField.getText().trim();
        String roomName = roomNameField.getText().trim();

        if (nickname.isEmpty()) {
            statusLabel.setText("❌ 닉네임을 입력하세요.");
            return;
        }
        if (roomName.isEmpty()) {
            statusLabel.setText("❌ 방 이름을 입력하세요.");
            return;
        }

        String mode = (modeBox != null && modeBox.getValue() != null)
                ? modeBox.getValue()
                : "CLASSIC";

        Integer limitValue = (limitBox != null) ? limitBox.getValue() : null;
        int limit = (limitValue != null) ? limitValue : 10;

        // 서버에 방 생성 요청 (방장 닉네임 + 모드 + 인원수 함께 전송)
        client.send("CREATE_ROOM|" + nickname + "|" + roomName + "|" + mode + "|" + limit);

        statusLabel.setText("방 생성 완료! (방장: " + nickname + ", 모드: " + mode + ", 인원: " + limit + ")");
    }

    /** 방 입장 버튼 클릭 */
    @FXML
    private void handleJoinRoom() {

        String nickname = nicknameField.getText().trim();
        if (nickname.isEmpty()) {
            statusLabel.setText("❌ 닉네임을 입력하세요.");
            return;
        }

        String selected = roomList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("❌ 입장할 방을 선택하세요.");
            return;
        }

        // "#0 TestRoom [CLASSIC] (1/10)" → #0 → id = 0
        String roomId = selected.split(" ")[0].substring(1).trim();

        client.send("JOIN_ROOM|" + nickname + "|" + roomId);
        statusLabel.setText("입장 시도 중...");
    }

    /** GameRoom으로 화면 전환 */
    private void enterGameRoom(String roomId, String roomName, String hostNickname, String myNickname) {
        try {
            GameRoomController.init(client, roomId, roomName, myNickname, hostNickname);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/ui/GameRoom.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root);
            Stage stage = (Stage) nicknameField.getScene().getWindow();
            stage.setScene(scene);

            statusLabel.setText(""); // 입장 상태 초기화

        } catch (Exception e) {
            e.printStackTrace();
            statusLabel.setText("❌ 게임방 화면 로딩 실패");
        }
    }
}