package client.ui;

import client.network.Client;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.util.Duration;
import server.ClientHandler;
import javafx.animation.PauseTransition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GameRoomController {

    @FXML private Label roomTitle;
    @FXML private Label roleLabel;
    @FXML private Label timerLabel;
    @FXML private ListView<String> playerList;
    @FXML private TextArea chatArea;
    @FXML private TextArea ghostChatArea;
    @FXML private TextField inputField;
    @FXML private TextField ghostInput;
    @FXML private Button sendButton;
    @FXML private Button ghostSendButton;
    @FXML private Button startButton;
    @FXML private TextField mafiaInput;
    @FXML private Button mafiaSendButton;


    private static Client client;
    private static String roomId;
    private static String roomName;
    private static String nickname;
    private static String hostNickname;

    private Thread timerThread;

    private boolean isVoteMode   = false;
    private boolean isNightPhase = false;
    private boolean iAmDead      = false;
    private String myRole;

    private Set<String> deadPlayers = new HashSet<>();
    private String myVoteTarget = null;
    private boolean abilityUsed = false;

    /** LobbyController에서 화면 전환 전에 호출됨 */
    public static void init(Client c, String rId, String rName, String nick, String hostNick) {
        client = c;
        roomId = rId;
        roomName = rName;
        nickname = nick;
        hostNickname = hostNick;
    }

    @FXML
    public void initialize() {

        roomTitle.setText(roomName != null ? "방 이름: " + roomName : "방 이름 불러오는 중...");

        // 방장만 게임 시작 버튼 가능
        if (startButton != null) {
            startButton.setDisable(!nickname.equals(hostNickname));
        }

        if (client != null) {
            client.setMessageHandler(this::onMessageReceived);
            client.requestPlayerList(roomId);
        }

        sendButton.setOnAction(e -> sendChat());
        inputField.setOnAction(e -> sendChat());

        // 고스트 채팅 비활성화
        if (ghostInput != null) ghostInput.setDisable(true);
        if (ghostSendButton != null) ghostSendButton.setDisable(true);

        if (ghostSendButton != null)
            ghostSendButton.setOnAction(e -> sendGhostChat());
        if (ghostInput != null)
            ghostInput.setOnAction(e -> sendGhostChat());

        // 마피아 채팅 초기 비활성화 (밤에만 활성화)
        if (mafiaInput != null) mafiaInput.setDisable(true);
        if (mafiaSendButton != null) mafiaSendButton.setDisable(true);

        if (mafiaSendButton != null)
            mafiaSendButton.setOnAction(e -> sendMafiaChat());
        if (mafiaInput != null)
            mafiaInput.setOnAction(e -> sendMafiaChat());

        /** ⭐ 플레이어 클릭 → 투표/능력 처리 */
        playerList.setOnMouseClicked(e -> {
            String target = playerList.getSelectionModel().getSelectedItem();
            if (target == null) return;

            // "(나)" 제거
            String pureTarget = target.replace(" (나)", "");

            // 죽은 사람은 행동 불가
            if (deadPlayers.contains(pureTarget)) {
                chatArea.appendText("❌ 죽은 플레이어에게는 행동할 수 없습니다.\n");
                return;
            }

            // 내가 죽었으면 불가
            if (iAmDead) {
                chatArea.appendText("❌ 사망 상태에서는 행동할 수 없습니다.\n");
                return;
            }

            // ⭐ 자기 자신 선택 방지 (의사만 예외)
            if (isNightPhase && pureTarget.equals(nickname) && !"DOCTOR".equals(myRole)) {
                chatArea.appendText("❌ 자신에게는 능력을 사용할 수 없습니다.\n");
                return;
            }

            // 밤 능력 중복 사용 방지
            if (isNightPhase && abilityUsed) {
                chatArea.appendText("❌ 밤 능력은 한 번만 사용할 수 있습니다.\n");
                return;
            }

            // ⭐ 경찰 능력
            if (isNightPhase && "POLICE".equals(myRole)) {
                client.send("NIGHT_ACTION|" + nickname + "|POLICE|" + pureTarget);
                chatArea.appendText("🔍 [" + pureTarget + "]님을 조사합니다...\n");
                abilityUsed = true;
                return;
            }

            // ⭐ 의사 또는 마피아 능력
            if (isNightPhase && ("MAFIA".equals(myRole) || "DOCTOR".equals(myRole))) {

                // 의사가 자기 자신을 선택한 경우
                if ("DOCTOR".equals(myRole) && pureTarget.equals(nickname)) {
                    chatArea.appendText("💉 자신을 보호합니다!\n");
                } else {
                    chatArea.appendText("🌙 [" + myRole + "] 능력을 [" + pureTarget + "]님에게 사용합니다.\n");
                }

                client.send("NIGHT_ACTION|" + nickname + "|" + myRole + "|" + pureTarget);
                abilityUsed = true;
                return;
            }

            if (isVoteMode && !isNightPhase) {
        myVoteTarget = pureTarget;  // 내 투표 대상 기록
        chatArea.appendText("🗳 [" + pureTarget + "]님에게 투표했습니다.\n");

        // 서버로 투표 메시지 전송
        client.send("VOTE|" + nickname + "|" + pureTarget);

        // UI에 선택 표시를 주고 싶으면 여기서 처리
        refreshPlayerListUI();
        }
        });
    }

    /** 🔵 일반 채팅 */
    private void sendChat() {
        if (iAmDead) {
            chatArea.appendText("❌ 사망 상태에서는 일반 채팅을 사용할 수 없습니다.\n");
            return;
        }
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        client.send("CHAT|" + nickname + "|" + text);
        inputField.clear();
    }

    /** 👻 고스트 채팅 */
    private void sendGhostChat() {
        if (!iAmDead) {
            ghostChatArea.appendText("❌ 살아있는 동안에는 고스트 채팅 불가.\n");
            return;
        }
        String text = ghostInput.getText().trim();
        if (text.isEmpty()) return;

        client.send("GHOST_CHAT|" + nickname + "|" + text);
        ghostInput.clear();
    }

    /** 🔴 마피아 전용 채팅 */
    private void sendMafiaChat() {
        if (!"MAFIA".equals(myRole)) {
            chatArea.appendText("❌ 마피아만 사용 가능합니다.\n");
            return;
        }
        if (!isNightPhase) {
            chatArea.appendText("❌ 마피아 채팅은 밤에만 사용 가능합니다.\n");
            return;
        }
        if (iAmDead) {
            chatArea.appendText("❌ 사망 상태에서는 마피아 채팅을 사용할 수 없습니다.\n");
            return;
        }
        String text = mafiaInput.getText().trim();
        if (text.isEmpty()) return;

        client.send("MAFIA_CHAT|" + nickname + "|" + text);
        mafiaInput.clear();
    }

    /** 🔥 서버 메시지 처리 */
    private void onMessageReceived(String msg) {

        if (msg.startsWith("CHAT|")) {
            String[] p = msg.split("\\|", 3);
            Platform.runLater(() ->
                chatArea.appendText(p[1] + ": " + p[2] + "\n")
            );
        }

        else if (msg.startsWith("PLAYER_LIST|")) {
            String[] players = msg.substring("PLAYER_LIST|".length()).split(",");
            Platform.runLater(() -> updatePlayerList(players));
        }

        else if (msg.startsWith("ROLE|")) {
            String[] p = msg.split("\\|");
            if (p[1].equals(nickname)) {
                myRole = p[2];
                Platform.runLater(() -> {
                    roleLabel.setText("당신의 역할: " + myRole);
                    chatArea.appendText("🎭 역할: [" + myRole + "]\n");
                    
                    // 마피아는 밤에 마피아 채팅 사용 가능
                    if ("MAFIA".equals(myRole)) {
                        chatArea.appendText("🔴 밤 시간에 마피아 전용 채팅을 사용할 수 있습니다.\n");
                    }
                });
            }
        }

        else if (msg.startsWith("DAY_START|")) {
            Platform.runLater(() -> {
                chatArea.appendText("\n🌞 낮이 시작되었습니다!\n");
                if (!iAmDead) {
                    inputField.setDisable(false);
                    sendButton.setDisable(false);
                }
                // 마피아 채팅 비활성화
                mafiaInput.setDisable(true);
                mafiaSendButton.setDisable(true);
                
                myVoteTarget = null;
                refreshPlayerListUI();
            });
            isNightPhase = false;
            isVoteMode = false;
            startTimer(10, "낮");
        }

        else if (msg.equals("VOTE_START")) {
            Platform.runLater(() -> {
                chatArea.appendText("\n🗳 투표 시작! 플레이어를 선택하세요.\n");
                playerList.setStyle("-fx-border-color: #ff6b6b; -fx-border-width: 2px;");
                timerLabel.setText("투표 시간: 30초");
            });
            isVoteMode = true;

            startTimer(10, "투표");
        }

        else if (msg.startsWith("VOTE_RESULT|")) {
            // 서버에서 "VOTE_RESULT|deadPlayer" 형태로 메시지를 보낸다고 가정
            String deadPlayer = msg.substring("VOTE_RESULT|".length());

            Platform.runLater(() -> {
                if (!"NONE".equals(deadPlayer)) {
                    handleDeathResult(deadPlayer, "낮");
                } else {
                    chatArea.appendText("\n⚖ 투표 결과: 아무도 죽지 않았습니다.\n");
                }

                isVoteMode = false;
                playerList.setStyle("");
                myVoteTarget = null;
            });
        }

        else if (msg.startsWith("NIGHT_START|")) {
            Platform.runLater(() -> {
                chatArea.appendText("\n🌙 밤이 되었습니다!\n");
                inputField.setDisable(true);
                sendButton.setDisable(true);
                
                // 마피아만 마피아 채팅 활성화
                if ("MAFIA".equals(myRole) && !iAmDead) {
                    mafiaInput.setDisable(false);
                    mafiaSendButton.setDisable(false);
                    chatArea.appendText("🔴 마피아 전용 채팅이 활성화되었습니다.\n");
                }
            });
            isNightPhase = true;
            isVoteMode = false;
            abilityUsed = false;
            startTimer(30, "밤");
        }

        else if (msg.startsWith("NIGHT_RESULT|")) {
            Platform.runLater(() -> handleDeathResult(msg.substring(13), "밤"));
            isNightPhase = false;
        }

        else if (msg.startsWith("GHOST_CHAT|")) {
            String[] p = msg.split("\\|", 3);
            Platform.runLater(() ->
                ghostChatArea.appendText("👻 " + p[1] + ": " + p[2] + "\n")
            );
        }

        else if (msg.startsWith("MAFIA_CHAT|")) {
            String[] p = msg.split("\\|", 3);
            Platform.runLater(() ->
                chatArea.appendText("🔴 [마피아] " + p[1] + ": " + p[2] + "\n")
            );
        }

        else if (msg.startsWith("GAME_START") || msg.startsWith("GAME_STARTED")
                || msg.startsWith("START_GAME_ACK") || msg.equals("ENTER_GAME")) {
            Platform.runLater(() -> goToGame());
        }

        else if (msg.startsWith("GAME_OVER|")) {
            String data = msg.substring("GAME_OVER|".length());
            Platform.runLater(() -> handleGameOver(data));
        }

        else if (msg.startsWith("POLICE_RESULT|")) {
            String[] parts = msg.split("\\|");
            String targetNickname = parts[1];
            String targetRole = parts[2];

            Platform.runLater(() -> {
                if (myRole.equals("POLICE")) {
                    chatArea.appendText("🔍 조사 결과: " + targetNickname + "님의 직업은 [" + targetRole + "]입니다.\n");
                }
            });
        }

        else if (msg.equals("ENTER_LOBBY")) {
            Platform.runLater(() -> goToLobby());
}
    }

   /** 🔥 사망 UI 처리 */
private void handleDeathResult(String dead, String phase) {
    if (dead.equals("NONE")) {
        chatArea.appendText("\n⚖ [" + phase + "] 아무도 죽지 않았습니다.\n");
        return;
    }

    chatArea.appendText("\n💀 [" + phase + "] " + dead + "님 사망\n");
    deadPlayers.add(dead); // 죽은 플레이어를 목록에 추가
    refreshPlayerListUI(); // 플레이어 목록 업데이트

    // 자신이 죽은 경우 처리
    if (dead.equals(nickname)) {
        iAmDead = true;

        inputField.setDisable(true);
        sendButton.setDisable(true);

        ghostInput.setDisable(false);
        ghostSendButton.setDisable(false);

        ghostChatArea.appendText("⚠ 당신은 사망했습니다 → 고스트 채팅만 가능합니다.\n");
    }
}

    /** 🔵 플레이어 리스트 업데이트 */
    private void updatePlayerList(String[] players) {
        playerList.getItems().clear();
        for (String player : players) {
            if (player.equals(nickname)) {
                playerList.getItems().add(player + " (나)");
            } else {
                playerList.getItems().add(player);
            }
        }
        refreshPlayerListUI();
    }

    /** ⭐ 투표 UI + 사망 UI 업데이트 */
    /** ⭐ 죽은 플레이어 회색 처리 + 선택 불가 */
/** ⭐ 투표 UI + 사망 UI 업데이트 */
private void refreshPlayerListUI() {

    playerList.setCellFactory(list -> new ListCell<>() {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setText(null);
                setDisable(false);
                setStyle("");
                return;
            }

            // 기본 텍스트 표시
            setText(item);

            // "(나)" 제거한 순수 이름
            String pureName = item.replace(" (나)", "");

            // 🔥 죽은 플레이어는 회색 + 클릭 불가
            if (deadPlayers.contains(pureName)) {
                setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
                setDisable(true);
                return;
            }

            // 🔥 살아있는 플레이어는 초기화
            setStyle("-fx-text-fill: black;");
            setDisable(false);

            // 🔥 내가 선택한 투표 대상이면 색 강조
            if (pureName.equals(myVoteTarget)) {
                setStyle("-fx-background-color: #ffeaa7; -fx-text-fill: black;");
            }
        }
    });
}




    /** 🔥 타이머 시작 */
    private void startTimer(int seconds, String phaseName) {
        if (timerThread != null && timerThread.isAlive())
            timerThread.interrupt();

        timerThread = new Thread(() -> {
            int time = seconds;

            while (time >= 0 && !Thread.currentThread().isInterrupted()) {

                int t = time;
                Platform.runLater(() ->
                        timerLabel.setText(phaseName + " - " + t + "초"));

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                time--;
            }

            Platform.runLater(() ->
                timerLabel.setText(phaseName + " 종료!")
            );
        });

        timerThread.setDaemon(true);
        timerThread.start();
    }

    /** 🔵 게임 시작 버튼 */
    @FXML
    private void handleStartGame() {
        client.send("START_GAME|" + nickname);
    }

    /** 🔥 게임 종료 처리 */
private void handleGameOver(String data) {
    // data 형식: "CIVIL|player1:CIVILIAN,player2:POLICE,player3:DOCTOR,"
    // 또는: "MAFIA|player1:MAFIA,player2:MAFIA,"
    
    String[] parts = data.split("\\|", 2);
    String winner = parts[0];
    
    String winnerTeam = winner.equals("CIVIL") ? "시민팀" : "마피아 팀";
    String emoji = winner.equals("CIVIL") ? "🎉" : "💀";
    
    // 승리팀 플레이어 정보 파싱
    StringBuilder winnerList = new StringBuilder();
    
    if (parts.length > 1 && !parts[1].isEmpty()) {
        String[] players = parts[1].split(",");
        
        for (String playerInfo : players) {
            if (playerInfo.trim().isEmpty()) continue;
            
            String[] info = playerInfo.split(":");
            if (info.length == 2) {
                String playerName = info[0];
                String role = info[1];
                
                // 역할을 한글로 변환
                String roleKorean = getRoleKorean(role);
                
                winnerList.append("\n• ")
                         .append(playerName)
                         .append(" - ")
                         .append(roleKorean);
            }
        }
    }
    
    String message = emoji + " " + winnerTeam + " 승리!\n" +
                    "\n【승리팀 구성원】" + winnerList.toString();

    Platform.runLater(() -> {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("게임 종료");
        alert.setHeaderText(winnerTeam + " 승리!");
        alert.setContentText(message + "\n\n확인을 누르면 로비로 이동합니다.");
        
        // Alert 크기 조정
        alert.getDialogPane().setMinWidth(400);

        // ❗ 확인 버튼 누르면 로비 이동
        alert.showAndWait().ifPresent(btn -> {
            goToLobby();
        });

        // 게임 종료 후 입력/기능 비활성화
        if (timerThread != null && timerThread.isAlive()) {
            timerThread.interrupt();
        }

        inputField.setDisable(true);
        sendButton.setDisable(true);
        playerList.setDisable(true);
        ghostInput.setDisable(true);
        ghostSendButton.setDisable(true);
    });
}

/** 역할을 한글로 변환 */
private String getRoleKorean(String role) {
    switch (role) {
        case "MAFIA":
            return "마피아 🔴";
        case "POLICE":
            return "경찰 🔍";
        case "DOCTOR":
            return "의사 💉";
        case "CIVILIAN":
            return "시민 👤";
        default:
            return role;
    }
}


    /** 🔵 게임 화면으로 이동 */
    private void goToGame() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/ui/GameScene.fxml"));
            Parent root = loader.load();

            if (timerThread != null && timerThread.isAlive()) {
                timerThread.interrupt();
            }

            roomTitle.getScene().setRoot(root);

        } catch (Exception e) {
            e.printStackTrace();
            chatArea.appendText("❌ 게임 화면으로 이동 실패\n");
        }
    }

    /** 🔵 로비로 이동 */
    private void goToLobby() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/ui/Lobby.fxml"));
            Parent root = loader.load();

            // 현재 씬의 루트를 로비 화면으로 교체
            roomTitle.getScene().setRoot(root);

        } catch (Exception e) {
            e.printStackTrace();
            chatArea.appendText("❌ 로비로 이동 실패\n");
        }
    }

    private void broadcastGameOver(String winner) {
        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom == this.currentRoom) {
                    ch.send("GAME_OVER|" + winner);
                }
            }
        }
    }

    private void checkGameOver() {
        int mafiaCount = 0;
        int civilCount = 0;

        for (String player : roomRoles.get(currentRoom).keySet()) {
            String role = roomRoles.get(currentRoom).get(player);
            if (!isDead(currentRoom, player)) {
                if ("MAFIA".equals(role)) mafiaCount++;
                else civilCount++;
            }
        }

        if (mafiaCount == 0) {
            broadcastGameOver("CIVIL");
        } else if (civilCount == 0) {
            broadcastGameOver("MAFIA");
        } 

    }

    private void calculateVoteResult() {
        Map<String, Integer> voteCounts = new HashMap<>();
        String mostVotedPlayer = null;
        int maxVotes = 0;

        // 투표 결과 계산
        for (String voter : votes.keySet()) {
            String target = votes.get(voter);
            if (target == null) continue;

            voteCounts.put(target, voteCounts.getOrDefault(target, 0) + 1);
            if (voteCounts.get(target) > maxVotes) {
                maxVotes = voteCounts.get(target);
                mostVotedPlayer = target;
            }
        }

        // 동률 처리: 아무도 죽지 않음
        long maxVoteCount = voteCounts.values().stream().filter(v -> v == maxVotes).count();
        if (maxVoteCount > 1) {
            mostVotedPlayer = "NONE";
        }

        // 죽은 플레이어 처리
        if (!"NONE".equals(mostVotedPlayer)) {
            markPlayerAsDead(mostVotedPlayer);
        }

        // 투표 결과 브로드캐스트
        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom == this.currentRoom) {
                    ch.send("VOTE_RESULT|" + mostVotedPlayer);
                }
            }
        }
    }

    private void markPlayerAsDead(String player) {
        if (roomRoles.containsKey(currentRoom)) {
            deadPlayers.get(currentRoom).add(player); // 죽은 플레이어 목록에 추가
            System.out.println("💀 " + player + "님이 사망했습니다.");
        }
    }

    

}