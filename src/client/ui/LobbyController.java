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
    @FXML private TextField passwordField;
    @FXML private ListView<String> roomList;
    @FXML private Label statusLabel;

    @FXML private ComboBox<String> modeBox;
    @FXML private ComboBox<Integer> limitBox;

    private Client client;

    @FXML
    public void initialize() {

        client = new Client();

        // 서버 연결
        if (!client.connect("localhost", 6000, this::onMessageReceived)) {
            statusLabel.setText("❌ 서버 연결 실패");
            return;
        }

        // 기본 모드 설정
        if (modeBox != null) {
            modeBox.getItems().setAll("CLASSIC", "SPECIAL");
        }

        // 기본 인원 설정
        if (limitBox != null) {
            limitBox.getItems().setAll(5, 6, 7, 8, 9, 10);
        }

        // 비밀번호 필드 - 숫자 4자리만 입력 가능
        if (passwordField != null) {
            passwordField.textProperty().addListener((observable, oldValue, newValue) -> {
                if (!newValue.matches("\\d*")) {
                    // 숫자가 아닌 문자 제거
                    passwordField.setText(newValue.replaceAll("[^\\d]", ""));
                } else if (newValue.length() > 4) {
                    // 4자리 초과 입력 방지
                    passwordField.setText(newValue.substring(0, 4));
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

        // 방 생성 완료
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
                String reason;
                if (msg.contains("FULL")) {
                    reason = "방이 꽉 찼습니다.";
                } else if (msg.contains("WRONG_PASSWORD")) {
                    reason = "비밀번호가 틀렸습니다.";
                } else {
                    reason = "방을 찾을 수 없습니다.";
                }
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

        // 비밀번호 검증 (선택사항)
        String password = (passwordField != null) ? passwordField.getText().trim() : "";
        
        if (!password.isEmpty()) {
            if (password.length() != 4) {
                statusLabel.setText("❌ 비밀번호는 정확히 4자리 숫자여야 합니다.");
                return;
            }
            if (!password.matches("\\d{4}")) {
                statusLabel.setText("❌ 비밀번호는 숫자만 가능합니다.");
                return;
            }
        }

        // 서버에 방 생성 요청 (비밀번호 포함)
        client.send("CREATE_ROOM|" + nickname + "|" + roomName + "|" + mode + "|" + limit + "|" + password);

        String pwdInfo = password.isEmpty() ? "비밀번호 없음" : "비밀번호 설정됨";
        statusLabel.setText("방 생성 완료! (방장: " + nickname + ", 모드: " + mode + ", 인원: " + limit + ", " + pwdInfo + ")");
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

        // 비밀번호가 설정된 방인지 확인 (🔒 아이콘 확인)
        boolean hasPassword = selected.startsWith("🔒");
        
        String password = "";
        
        if (hasPassword) {
            // 비밀번호 입력 다이얼로그
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("비밀번호 입력");
            dialog.setHeaderText("이 방은 비밀번호로 보호되어 있습니다.");
            dialog.setContentText("비밀번호 (4자리):");
            
            dialog.getEditor().setPromptText("4자리 숫자 입력");
            
            // 입력 제한 (4자리 숫자만)
            dialog.getEditor().textProperty().addListener((observable, oldValue, newValue) -> {
                if (!newValue.matches("\\d*")) {
                    dialog.getEditor().setText(newValue.replaceAll("[^\\d]", ""));
                } else if (newValue.length() > 4) {
                    dialog.getEditor().setText(newValue.substring(0, 4));
                }
            });
            
            var result = dialog.showAndWait();
            if (result.isPresent()) {
                password = result.get().trim();
                
                if (password.length() != 4) {
                    statusLabel.setText("❌ 비밀번호는 4자리 숫자여야 합니다.");
                    return;
                }
            } else {
                // 사용자가 취소함
                statusLabel.setText("입장이 취소되었습니다.");
                return;
            }
        }

        // 방 ID 추출
        String roomIdPart = hasPassword ? selected.split(" ")[1] : selected.split(" ")[0];
        String roomId = roomIdPart.substring(1).trim();

        // 비밀번호 포함하여 서버에 전송
        client.send("JOIN_ROOM|" + nickname + "|" + roomId + "|" + password);
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