package client.ui;

import client.network.Client;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.VBox;

import java.util.*;

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

    private boolean isVoteMode = false;
    private boolean isNightPhase = false;
    private boolean iAmDead = false;
    private String myRole;

    private Set<String> deadPlayers = new HashSet<>();
    private String myVoteTarget = null;
    private boolean abilityUsed = false;
    
    // 특수 직업 변수
    private boolean forgerUsed = false;
    private boolean hackerUsed = false;
    private boolean timeManagerUsed = false;
    private Map<String, String> currentVotes = new HashMap<>();
    private List<String> destinyTargets = new ArrayList<>();
    private String stolenRole = null;
    private boolean stolenAbilityUsed = false;

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

        if (startButton != null) {
            startButton.setDisable(!nickname.equals(hostNickname));
        }

        if (client != null) {
            client.setMessageHandler(this::onMessageReceived);
            client.requestPlayerList(roomId);
        }

        sendButton.setOnAction(e -> sendChat());
        inputField.setOnAction(e -> sendChat());

        if (ghostInput != null) ghostInput.setDisable(true);
        if (ghostSendButton != null) ghostSendButton.setDisable(true);

        if (ghostSendButton != null)
            ghostSendButton.setOnAction(e -> sendGhostChat());
        if (ghostInput != null)
            ghostInput.setOnAction(e -> sendGhostChat());

        if (mafiaInput != null) mafiaInput.setDisable(true);
        if (mafiaSendButton != null) mafiaSendButton.setDisable(true);

        if (mafiaSendButton != null)
            mafiaSendButton.setOnAction(e -> sendMafiaChat());
        if (mafiaInput != null)
            mafiaInput.setOnAction(e -> sendMafiaChat());

        playerList.setOnMouseClicked(e -> handlePlayerClick());
    }

    /** 플레이어 클릭 처리 */
    private void handlePlayerClick() {
        String target = playerList.getSelectionModel().getSelectedItem();
        if (target == null) return;

        String pureTarget = target.replace(" (나)", "");

        if (deadPlayers.contains(pureTarget)) {
            chatArea.appendText("❌ 죽은 플레이어에게는 행동할 수 없습니다.\n");
            return;
        }

        if (iAmDead) {
            chatArea.appendText("❌ 사망 상태에서는 행동할 수 없습니다.\n");
            return;
        }

        // 밤 행동
        if (isNightPhase) {
            handleNightAction(pureTarget);
            return;
        }

        // 투표
        if (isVoteMode) {
            myVoteTarget = pureTarget;
            chatArea.appendText("🗳 [" + pureTarget + "]님에게 투표했습니다.\n");
            client.send("VOTE|" + nickname + "|" + pureTarget);
            refreshPlayerListUI();
        }
    }

    /** 밤 행동 처리 */
    private void handleNightAction(String target) {
        if (target.equals(nickname) && !"DOCTOR".equals(myRole)) {
            chatArea.appendText("❌ 자신에게는 능력을 사용할 수 없습니다.\n");
            return;
        }

        if (abilityUsed) {
            chatArea.appendText("❌ 밤 능력은 한 번만 사용할 수 있습니다.\n");
            return;
        }

        String roleToUse = myRole;
        
        // 🎭 도둑이 능력을 훔친 경우
        if ("THIEF".equals(myRole) && stolenRole != null) {
            if (stolenAbilityUsed) {
                chatArea.appendText("❌ 훔친 능력은 이미 사용되었습니다.\n");
                return;
            }
            roleToUse = stolenRole;
        }

        switch (roleToUse) {
            case "POLICE":
                client.send("NIGHT_ACTION|" + nickname + "|POLICE|" + target);
                chatArea.appendText("🔍 [" + target + "]님을 조사합니다...\n");
                abilityUsed = true;
                break;

            case "MAFIA":
                if (target.equals(nickname)) {
                    chatArea.appendText("❌ 마피아는 자신을 선택할 수 없습니다.\n");
                    return;
                }
                client.send("NIGHT_ACTION|" + nickname + "|MAFIA|" + target);
                chatArea.appendText("🔪 [" + target + "]님을 공격합니다...\n");
                abilityUsed = true;
                break;

            case "DOCTOR":
                if (target.equals(nickname)) {
                    chatArea.appendText("💉 자신을 보호합니다!\n");
                } else {
                    chatArea.appendText("💉 [" + target + "]님을 보호합니다...\n");
                }
                client.send("NIGHT_ACTION|" + nickname + "|DOCTOR|" + target);
                abilityUsed = true;
                break;

            case "TRACKER":
                client.send("TRACKER_TARGET|" + target);
                chatArea.appendText("🔍 [" + target + "]님을 추적합니다...\n");
                abilityUsed = true;
                break;

            default:
                chatArea.appendText("⚠ 밤에 사용할 수 있는 능력이 없습니다.\n");
                break;
        }
    }

    /** 일반 채팅 */
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

    /** 고스트 채팅 */
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

    /** 마피아 채팅 */
    private void sendMafiaChat() {
        if (!"MAFIA".equals(myRole) && !"FORGER".equals(myRole) && !"HACKER".equals(myRole)) {
            chatArea.appendText("❌ 마피아팀만 사용 가능합니다.\n");
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

    /** 서버 메시지 처리 */
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
            handleRoleAssignment(msg);
        }

        else if (msg.startsWith("DAY_START|")) {
            Platform.runLater(() -> {
                chatArea.appendText("\n🌞 낮이 시작되었습니다!\n");
                if (!iAmDead) {
                    inputField.setDisable(false);
                    sendButton.setDisable(false);
                }
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
                timerLabel.setText("투표 시간: 10초");
            });
            isVoteMode = true;
            startTimer(10, "투표");
        }

        // 🔧 해커 메시지
        else if (msg.startsWith("HACKER_VOTE_INFO|")) {
            Platform.runLater(() -> handleHackerVoteInfo(msg));
        }
        else if (msg.startsWith("HACKER_PROMPT|")) {
            Platform.runLater(() -> {
                String prompt = msg.substring("HACKER_PROMPT|".length());
                chatArea.appendText("\n🔧 " + prompt + "\n");
                showHackerDialog();
            });
        }

        // 🎭 위조범 메시지
        else if (msg.startsWith("FORGER_PROMPT|")) {
            Platform.runLater(() -> handleForgerPrompt(msg));
        }

        // ⏰ 시간 관리자 메시지
        else if (msg.startsWith("TIME_MANAGER_PROMPT|")) {
            Platform.runLater(() -> showTimeManagerDialog(msg));
        }
        else if (msg.startsWith("TIME_MANAGER_SKIP|")) {
            Platform.runLater(() -> {
                String message = msg.substring("TIME_MANAGER_SKIP|".length());
                chatArea.appendText("\n⏰ " + message + "\n");
            });
        }

        // 🔮 운명가 메시지
        else if (msg.startsWith("DESTINY_TARGETS|")) {
            Platform.runLater(() -> handleDestinyTargets(msg));
        }

        // 🎭 도둑 메시지
        else if (msg.startsWith("THIEF_STOLEN|")) {
            Platform.runLater(() -> handleThiefStolen(msg));
        }

        // 🔍 추적자 메시지
        else if (msg.startsWith("TRACKER_RESULT|")) {
            Platform.runLater(() -> {
                String result = msg.substring("TRACKER_RESULT|".length());
                chatArea.appendText("\n🔍 추적 결과: " + result + "\n");
            });
        }

        else if (msg.startsWith("VOTE_RESULT|")) {
            handleVoteResult(msg);
        }

        else if (msg.startsWith("NIGHT_START|")) {
            Platform.runLater(() -> {
                chatArea.appendText("\n🌙 밤이 되었습니다!\n");
                inputField.setDisable(true);
                sendButton.setDisable(true);
                
                if (("MAFIA".equals(myRole) || "FORGER".equals(myRole) || "HACKER".equals(myRole)) && !iAmDead) {
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
            String deadPlayer = msg.substring(13);
            Platform.runLater(() -> handleDeathResult(deadPlayer, "밤", null));
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

        else if (msg.startsWith("POLICE_RESULT|")) {
            handlePoliceResult(msg);
        }

        else if (msg.startsWith("JESTER_WIN|")) {
            handleJesterWin(msg);
        }

        else if (msg.startsWith("GAME_OVER|")) {
            String data = msg.substring("GAME_OVER|".length());
            Platform.runLater(() -> handleGameOver(data));
        }

        else if (msg.equals("ENTER_LOBBY")) {
            Platform.runLater(() -> goToLobby());
        }
    }

    /** 역할 배정 처리 */
    private void handleRoleAssignment(String msg) {
        String[] p = msg.split("\\|");
        if (p[1].equals(nickname)) {
            myRole = p[2];
            Platform.runLater(() -> {
                String roleDisplay = getRoleDisplay(myRole);
                roleLabel.setText("당신의 역할: " + roleDisplay);
                chatArea.appendText("🎭 역할: [" + roleDisplay + "]\n");
                
                // 역할별 안내
                if ("MAFIA".equals(myRole) || "FORGER".equals(myRole) || "HACKER".equals(myRole)) {
                    chatArea.appendText("🔴 밤 시간에 마피아 전용 채팅을 사용할 수 있습니다.\n");
                }
                
                if ("FORGER".equals(myRole)) {
                    chatArea.appendText("🎭 위조범: 투표로 죽은 사람의 직업을 1회 위조할 수 있습니다.\n");
                } else if ("HACKER".equals(myRole)) {
                    chatArea.appendText("🔧 해커: 투표 결과를 먼저 받고 1회 조작할 수 있습니다.\n");
                } else if ("JESTER".equals(myRole)) {
                    chatArea.appendText("🎭 광대: 낮 투표로 처형되면 승리합니다!\n");
                } else if ("THIEF".equals(myRole)) {
                    chatArea.appendText("🎭 도둑: 첫 사망자의 직업을 훔칠 수 있습니다.\n");
                } else if ("TIME_MANAGER".equals(myRole)) {
                    chatArea.appendText("⏰ 시간 관리자: 투표 시 밤을 건너뛸 수 있습니다 (1회).\n");
                } else if ("DESTINY".equals(myRole)) {
                    chatArea.appendText("🔮 운명가: 첫 날 3명의 이름을 받습니다 (1명은 반드시 마피아).\n");
                } else if ("TRACKER".equals(myRole)) {
                    chatArea.appendText("🔍 추적자: 밤마다 한 명을 지정해 누구에게 행동했는지 알 수 있습니다.\n");
                }
            });
        }
    }

    /** 직업 한글 표시 */
    private String getRoleDisplay(String role) {
        switch (role) {
            case "MAFIA": return "마피아 🔴";
            case "FORGER": return "위조범 🎭";
            case "HACKER": return "해커 🔧";
            case "JESTER": return "광대 🎭";
            case "THIEF": return "도둑 🎭";
            case "POLICE": return "경찰 🔍";
            case "DOCTOR": return "의사 💉";
            case "TIME_MANAGER": return "시간 관리자 ⏰";
            case "DESTINY": return "운명가 🔮";
            case "TRACKER": return "추적자 🔍";
            case "CIVILIAN": return "시민 👤";
            default: return role;
        }
    }

    // Part 2로 계속...
    // GameRoomController.java 계속 (Part 2)

    /** 🔧 해커: 투표 결과 정보 처리 */
    private void handleHackerVoteInfo(String msg) {
        if (hackerUsed) {
            chatArea.appendText("⚠ 해커 능력은 이미 사용했습니다.\n");
            return;
        }
        
        String voteData = msg.substring("HACKER_VOTE_INFO|".length());
        currentVotes.clear();
        
        String[] votes = voteData.split(",");
        for (String vote : votes) {
            if (vote.trim().isEmpty()) continue;
            String[] parts = vote.split(":");
            if (parts.length == 2) {
                currentVotes.put(parts[0], parts[1]);
            }
        }
        
        // 투표 결과 표시
        chatArea.appendText("\n🔧 === 투표 결과 (해커 전용) ===\n");
        Map<String, Integer> counts = new HashMap<>();
        for (String target : currentVotes.values()) {
            counts.put(target, counts.getOrDefault(target, 0) + 1);
        }
        
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            chatArea.appendText("  " + entry.getKey() + ": " + entry.getValue() + "표\n");
        }
        chatArea.appendText("============================\n\n");
    }
    
    /** 🔧 해커: 투표 조작 다이얼로그 */
    private void showHackerDialog() {
        if (hackerUsed || currentVotes.isEmpty()) {
            return;
        }
        
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("해커 능력");
        dialog.setHeaderText("누구의 투표를 조작하시겠습니까?");
        
        VBox content = new VBox(10);
        
        ComboBox<String> voterBox = new ComboBox<>();
        voterBox.setPromptText("투표자 선택");
        voterBox.getItems().addAll(currentVotes.keySet());
        
        ComboBox<String> targetBox = new ComboBox<>();
        targetBox.setPromptText("변경할 대상 선택");
        
        // 플레이어 목록 가져오기
        List<String> allPlayers = new ArrayList<>();
        for (int i = 0; i < playerList.getItems().size(); i++) {
            String player = playerList.getItems().get(i).replace(" (나)", "");
            if (!deadPlayers.contains(player)) {
                allPlayers.add(player);
            }
        }
        targetBox.getItems().addAll(allPlayers);
        
        content.getChildren().addAll(
            new Label("투표를 조작할 사람:"), voterBox,
            new Label("변경할 투표 대상:"), targetBox
        );
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        dialog.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK && voterBox.getValue() != null && targetBox.getValue() != null) {
                String voter = voterBox.getValue();
                String newTarget = targetBox.getValue();
                
                client.send("HACKER_CHANGE|" + voter + "|" + newTarget);
                hackerUsed = true;
                chatArea.appendText("✅ " + voter + "의 투표를 " + newTarget + "으로 변경했습니다!\n");
            } else {
                chatArea.appendText("⏭ 해커 능력을 사용하지 않았습니다.\n");
            }
        });
    }
    
    /** 🎭 위조범: 직업 위조 프롬프트 */
    private void handleForgerPrompt(String msg) {
        if (forgerUsed) {
            return;
        }
        
        // FORGER_PROMPT|deadPlayer|deadRole
        String[] parts = msg.split("\\|");
        String deadPlayer = parts[1];
        String deadRole = parts[2];
        
        chatArea.appendText("\n🎭 === 위조범 능력 ===\n");
        chatArea.appendText("  사망자: " + deadPlayer + "\n");
        chatArea.appendText("  실제 직업: " + getRoleDisplay(deadRole) + "\n");
        chatArea.appendText("========================\n\n");
        
        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle("위조범 능력");
        alert.setHeaderText(deadPlayer + "님의 직업을 위조하시겠습니까?");
        alert.setContentText("실제 직업: " + getRoleDisplay(deadRole));
        
        ButtonType btnForge = new ButtonType("위조하기");
        ButtonType btnSkip = new ButtonType("건너뛰기", ButtonBar.ButtonData.CANCEL_CLOSE);
        
        alert.getButtonTypes().setAll(btnForge, btnSkip);
        
        alert.showAndWait().ifPresent(btn -> {
            if (btn == btnForge) {
                showForgerRoleSelection(deadRole);
            } else {
                chatArea.appendText("⏭ 위조를 건너뛰었습니다.\n");
            }
        });
    }
    
    /** 🎭 위조범: 직업 선택 다이얼로그 */
    private void showForgerRoleSelection(String realRole) {
        List<String> roles = Arrays.asList("MAFIA", "FORGER", "HACKER", "JESTER", "THIEF", 
                                           "POLICE", "DOCTOR", "TIME_MANAGER", "DESTINY", "TRACKER", "CIVILIAN");
        
        ChoiceDialog<String> dialog = new ChoiceDialog<>(realRole, roles);
        dialog.setTitle("위조범 - 직업 선택");
        dialog.setHeaderText("어떤 직업으로 발표하시겠습니까?");
        dialog.setContentText("직업 선택:");
        
        dialog.showAndWait().ifPresent(selectedRole -> {
            client.send("FORGER_CHANGE|" + selectedRole);
            forgerUsed = true;
            chatArea.appendText("✅ 직업을 [" + getRoleDisplay(selectedRole) + "]로 위조했습니다!\n");
        });
    }
    
    /** ⏰ 시간 관리자: 밤 건너뛰기 다이얼로그 */
    private void showTimeManagerDialog(String msg) {
        if (timeManagerUsed) {
            return;
        }
        
        String prompt = msg.substring("TIME_MANAGER_PROMPT|".length());
        
        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle("시간 관리자 능력");
        alert.setHeaderText(prompt);
        alert.setContentText("밤을 건너뛰면 아무도 죽지 않고 바로 다음 낮이 됩니다.");
        
        ButtonType btnYes = new ButtonType("Yes - 밤 건너뛰기");
        ButtonType btnNo = new ButtonType("No - 정상 진행", ButtonBar.ButtonData.CANCEL_CLOSE);
        
        alert.getButtonTypes().setAll(btnYes, btnNo);
        
        alert.showAndWait().ifPresent(btn -> {
            if (btn == btnYes) {
                client.send("TIME_MANAGER_CHOICE|YES");
                timeManagerUsed = true;
                chatArea.appendText("⏰ 밤을 건너뛰기로 결정했습니다!\n");
            } else {
                client.send("TIME_MANAGER_CHOICE|NO");
                chatArea.appendText("⏰ 정상적으로 게임을 진행합니다.\n");
            }
        });
    }
    
    /** 🔮 운명가: 3명 이름 표시 */
    private void handleDestinyTargets(String msg) {
        String data = msg.substring("DESTINY_TARGETS|".length());
        String[] targets = data.split(",");
        
        destinyTargets.clear();
        for (String t : targets) {
            if (!t.trim().isEmpty()) {
                destinyTargets.add(t.trim());
            }
        }
        
        chatArea.appendText("\n🔮 === 운명가 정보 ===\n");
        chatArea.appendText("  다음 3명 중 최소 1명은 마피아입니다:\n");
        for (String t : destinyTargets) {
            chatArea.appendText("  • " + t + "\n");
        }
        chatArea.appendText("========================\n\n");
        
        // Alert로도 표시
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle("운명가 - 특별 정보");
        alert.setHeaderText("다음 3명 중 최소 1명은 마피아입니다!");
        
        StringBuilder content = new StringBuilder();
        for (String t : destinyTargets) {
            content.append("• ").append(t).append("\n");
        }
        alert.setContentText(content.toString());
        alert.show();
    }
    
    /** 🎭 도둑: 직업 훔치기 */
    private void handleThiefStolen(String msg) {
        // THIEF_STOLEN|role|USED or AVAILABLE
        String[] parts = msg.split("\\|");
        stolenRole = parts[1];
        stolenAbilityUsed = "USED".equals(parts[2]);
        
        chatArea.appendText("\n🎭 === 도둑 능력 발동 ===\n");
        chatArea.appendText("  훔친 직업: " + getRoleDisplay(stolenRole) + "\n");
        
        if (stolenAbilityUsed) {
            chatArea.appendText("  ⚠ 이 직업의 능력은 이미 사용되었습니다.\n");
        } else {
            chatArea.appendText("  ✅ 이 직업의 능력을 사용할 수 있습니다!\n");
        }
        chatArea.appendText("========================\n\n");
        
        // Alert로도 표시
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle("도둑 - 직업 훔치기 성공!");
        alert.setHeaderText("첫 사망자의 직업을 훔쳤습니다!");
        
        String abilityStatus = stolenAbilityUsed ? "⚠ 능력 이미 사용됨" : "✅ 능력 사용 가능";
        alert.setContentText("훔친 직업: " + getRoleDisplay(stolenRole) + "\n\n" + abilityStatus);
        alert.show();
    }
    
    /** 🔍 경찰: 조사 결과 */
    private void handlePoliceResult(String msg) {
        String[] parts = msg.split("\\|");
        String targetNickname = parts[1];
        String targetRole = parts[2];

        Platform.runLater(() -> {
            if (myRole.equals("POLICE")) {
                String roleDisplay = getRoleDisplay(targetRole);
                chatArea.appendText("🔍 조사 결과: " + targetNickname + "님의 직업은 [" + roleDisplay + "]입니다.\n");
            }
        });
    }
    
    /** 투표 결과 처리 */
    private void handleVoteResult(String msg) {
        String[] parts = msg.split("\\|");
        String deadPlayer = parts[1];
        String revealedRole = parts.length > 2 ? parts[2] : "알 수 없음";

        Platform.runLater(() -> {
            if (!"NONE".equals(deadPlayer)) {
                handleDeathResult(deadPlayer, "낮", revealedRole);
            } else {
                chatArea.appendText("\n⚖ 투표 결과: 아무도 죽지 않았습니다.\n");
            }

            isVoteMode = false;
            playerList.setStyle("");
            myVoteTarget = null;
        });
    }
    
    /** 🎭 광대 승리 */
    private void handleJesterWin(String msg) {
        String jesterName = msg.substring("JESTER_WIN|".length());
        
        Platform.runLater(() -> {
            Alert alert = new Alert(AlertType.INFORMATION);
            alert.setTitle("게임 종료");
            alert.setHeaderText("🎭 광대 승리!");
            alert.setContentText(jesterName + "님(광대)이 투표로 처형되어 승리했습니다!\n\n" +
                               "광대의 목적은 낮 투표로 처형되는 것이었습니다.\n\n" +
                               "확인을 누르면 로비로 이동합니다.");
            
            alert.getDialogPane().setMinWidth(400);

            alert.showAndWait().ifPresent(btn -> {
                goToLobby();
            });

            if (timerThread != null && timerThread.isAlive()) {
                timerThread.interrupt();
            }

            inputField.setDisable(true);
            sendButton.setDisable(true);
            playerList.setDisable(true);
        });
    }

    /** 사망 처리 */
    private void handleDeathResult(String dead, String phase, String revealedRole) {
        if (dead.equals("NONE")) {
            chatArea.appendText("\n⚖ [" + phase + "] 아무도 죽지 않았습니다.\n");
            return;
        }

        String roleInfo = "";
        if (revealedRole != null && !revealedRole.equals("알 수 없음")) {
            roleInfo = " - 직업: " + getRoleDisplay(revealedRole);
        }
        
        chatArea.appendText("\n💀 [" + phase + "] " + dead + "님 사망" + roleInfo + "\n");
        deadPlayers.add(dead);
        refreshPlayerListUI();

        if (dead.equals(nickname)) {
            iAmDead = true;

            inputField.setDisable(true);
            sendButton.setDisable(true);

            if (ghostInput != null) ghostInput.setDisable(false);
            if (ghostSendButton != null) ghostSendButton.setDisable(false);

            if (ghostChatArea != null) {
                ghostChatArea.appendText("⚠ 당신은 사망했습니다 → 고스트 채팅만 가능합니다.\n");
            }
        }
    }

    /** 플레이어 목록 업데이트 */
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

    /** 플레이어 목록 UI 새로고침 */
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

                setText(item);
                String pureName = item.replace(" (나)", "");

                if (deadPlayers.contains(pureName)) {
                    setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
                    setDisable(true);
                    return;
                }

                setStyle("-fx-text-fill: black;");
                setDisable(false);

                if (pureName.equals(myVoteTarget)) {
                    setStyle("-fx-background-color: #ffeaa7; -fx-text-fill: black;");
                }
            }
        });
    }

    /** 타이머 시작 */
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

    @FXML
    private void handleStartGame() {
        client.send("START_GAME|" + nickname);
    }

    /** 게임 종료 처리 */
    private void handleGameOver(String data) {
        String[] parts = data.split("\\|", 2);
        String winner = parts[0];
        
        String winnerTeam = winner.equals("CIVIL") ? "시민팀" : "마피아 팀";
        String emoji = winner.equals("CIVIL") ? "🎉" : "💀";
        
        StringBuilder winnerList = new StringBuilder();
        
        if (parts.length > 1 && !parts[1].isEmpty()) {
            String[] players = parts[1].split(",");
            
            for (String playerInfo : players) {
                if (playerInfo.trim().isEmpty()) continue;
                
                String[] info = playerInfo.split(":");
                if (info.length == 2) {
                    String playerName = info[0];
                    String role = info[1];
                    
                    String roleDisplay = getRoleDisplay(role);
                    
                    winnerList.append("\n• ")
                             .append(playerName)
                             .append(" - ")
                             .append(roleDisplay);
                }
            }
        }
        
        String message = emoji + " " + winnerTeam + " 승리!\n" +
                        "\n【승리팀 구성원】" + winnerList.toString();

        Platform.runLater(() -> {
            Alert alert = new Alert(AlertType.INFORMATION);
            alert.setTitle("게임 종료");
            alert.setHeaderText(winnerTeam + " 승리!");
            alert.setContentText(message + "\n\n확인을 누르면 로비로 이동합니다.");
            
            alert.getDialogPane().setMinWidth(400);

            alert.showAndWait().ifPresent(btn -> {
                goToLobby();
            });

            if (timerThread != null && timerThread.isAlive()) {
                timerThread.interrupt();
            }

            inputField.setDisable(true);
            sendButton.setDisable(true);
            playerList.setDisable(true);
            if (ghostInput != null) ghostInput.setDisable(true);
            if (ghostSendButton != null) ghostSendButton.setDisable(true);
        });
    }

    /** 로비로 이동 */
    private void goToLobby() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/ui/Lobby.fxml"));
            Parent root = loader.load();

            roomTitle.getScene().setRoot(root);

        } catch (Exception e) {
            e.printStackTrace();
            chatArea.appendText("❌ 로비로 이동 실패\n");
        }
    }

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
}
