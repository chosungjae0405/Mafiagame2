package server;

import common.Room;
import java.io.*;
import java.net.Socket;
import java.util.*;

/**
 * ClientHandler - 특수 직업 완전판
 * 마피아팀: 마피아, 위조범, 해커
 * 중립: 광대, 도둑
 * 시민팀: 시민, 경찰, 의사, 시간 관리자, 운명가, 추적자
 */
public class ClientHandler implements Runnable {

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private List<ClientHandler> clients;
    private List<Room> rooms;

    private Room currentRoom;
    private String nickname;

    // 게임 상태
    private static Map<Room, Map<String, String>> roomVotes = new HashMap<>();
    private static Map<Room, String> mafiaTargets = new HashMap<>();
    private static Map<Room, String> doctorTargets = new HashMap<>();
    private static Map<Room, Map<String, String>> roomRoles = new HashMap<>();
    private static Map<Room, Set<String>> deadPlayers = new HashMap<>();
    private static Set<Room> finishedRooms = Collections.synchronizedSet(new HashSet<>());
    
    // 특수 능력
    private static Map<Room, Boolean> forgerUsed = new HashMap<>();
    private static Map<Room, Boolean> hackerUsed = new HashMap<>();
    private static Map<Room, Boolean> timeManagerUsed = new HashMap<>();
    private static Map<Room, String> forgedRole = new HashMap<>();
    private static Map<Room, Map<String, String>> hackerVoteChange = new HashMap<>();
    
    // 도둑 시스템
    private static Map<Room, String> thiefStolenRole = new HashMap<>();
    private static Map<Room, Boolean> thiefAbilityUsed = new HashMap<>();
    
    // 운명가 시스템
    private static Map<Room, List<String>> destinyTargets = new HashMap<>();
    
    // 추적자 시스템
    private static Map<Room, String> trackerTargets = new HashMap<>();
    private static Map<Room, Map<String, String>> nightActions = new HashMap<>();

    public ClientHandler(Socket socket, List<ClientHandler> clients, List<Room> rooms) {
        this.socket = socket;
        this.clients = clients;
        this.rooms = rooms;

        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
        } catch (IOException e) {
            System.out.println("스트림 생성 실패");
        }
    }

    @Override
    public void run() {
        try {
            String message;
            while ((message = in.readLine()) != null) {
                System.out.println("📨 [" + nickname + "] 받은 메시지: " + message);

                if ("GET_ROOMS".equals(message)) {
                    sendRoomList();
                } else if (message.startsWith("CREATE_ROOM|")) {
                    createRoom(message.substring("CREATE_ROOM|".length()));
                } else if (message.startsWith("JOIN_ROOM|")) {
                    handleJoinRoom(message);
                } else if (message.startsWith("CHAT|")) {
                    handleChat(message);
                } else if (message.startsWith("MAFIA_CHAT|")) {
                    handleMafiaChat(message);
                } else if (message.startsWith("GET_PLAYERS|")) {
                    handleGetPlayers(message);
                } else if (message.startsWith("START_GAME|")) {
                    handleStartGame(message);
                } else if (message.startsWith("VOTE|")) {
                    handleVote(message);
                } else if (message.startsWith("NIGHT_ACTION|")) {
                    handleNightAction(message);
                } else if (message.startsWith("HACKER_CHANGE|")) {
                    handleHackerChange(message);
                } else if (message.startsWith("FORGER_CHANGE|")) {
                    handleForgerChange(message);
                } else if (message.startsWith("TIME_MANAGER_CHOICE|")) {
                    handleTimeManagerChoice(message);
                } else if (message.startsWith("TRACKER_TARGET|")) {
                    handleTrackerTarget(message);
                }
            }
        } catch (IOException e) {
            System.out.println("❗ 클라이언트 연결 종료됨");
        } finally {
            try {
                clients.remove(this);
                socket.close();
            } catch (IOException ex) {}
        }
    }

    private void sendRoomList() {
        StringBuilder builder = new StringBuilder("ROOM_LIST|");
        synchronized (rooms) {
            for (Room r : rooms) {
                String lockIcon = r.hasPassword() ? "🔒 " : "";
                builder.append(lockIcon).append("#").append(r.getId()).append(" ")
                       .append(r.getName()).append(" (").append(r.getPlayers().size())
                       .append("/").append(r.getLimit()).append("),");
            }
        }
        send(builder.toString());
    }

    private void createRoom(String payload) {
        String creatorNickname = null;
        String roomName;
        String mode = "CLASSIC";
        int limit = 10;
        String password = "";

        String[] parts = payload.split("\\|", -1);
        
        if (parts.length >= 2) {
            creatorNickname = parts[0].trim();
            roomName = parts[1].trim();
            if (parts.length >= 3 && !parts[2].isEmpty()) mode = parts[2].trim();
            if (parts.length >= 4 && !parts[3].isEmpty()) {
                try {
                    limit = Integer.parseInt(parts[3].trim());
                } catch (NumberFormatException e) {
                    limit = 10;
                }
            }
            if (parts.length >= 5 && !parts[4].isEmpty()) password = parts[4].trim();
        } else {
            roomName = payload.trim();
        }

        if (limit < 5) limit = 5;
        if (limit > 10) limit = 10;

        Room newRoom = new Room(Server.roomIdCounter++, roomName);
        newRoom.setMode(mode);
        newRoom.setLimit(limit);
        newRoom.setPassword(password);
        if (creatorNickname != null && !creatorNickname.isEmpty()) {
            newRoom.setHostNickname(creatorNickname);
        }

        synchronized (rooms) {
            rooms.add(newRoom);
        }

        System.out.println("✅ 방 생성: #" + newRoom.getId() + " " + newRoom.getName() 
            + " [" + mode + "] | 비밀번호: " + (newRoom.hasPassword() ? "있음" : "없음"));

        Server.broadcastRoomList();
    }

    private void handleJoinRoom(String msg) {
        String[] parts = msg.split("\\|", -1);
        if (parts.length < 3) return;

        this.nickname = parts[1].trim();
        String roomId = parts[2].trim();
        String inputPassword = parts.length >= 4 ? parts[3].trim() : "";

        Room target = Server.findRoomById(roomId);
        if (target == null) {
            send("JOIN_FAIL|NOT_FOUND");
            return;
        }

        if (target.hasPassword() && !target.checkPassword(inputPassword)) {
            send("JOIN_FAIL|WRONG_PASSWORD");
            return;
        }

        if (target.getPlayers().size() >= target.getLimit()) {
            send("JOIN_FAIL|FULL");
            return;
        }

        target.getPlayers().add(nickname);
        this.currentRoom = target;

        String host = target.getHostNickname();
        if (host == null || host.isEmpty()) {
            host = !target.getPlayers().isEmpty() ? target.getPlayers().get(0) : nickname;
            target.setHostNickname(host);
        }

        send("JOIN_OK|" + target.getId() + "|" + target.getName() + "|" + host);
        broadcastPlayerList(target);
        Server.broadcastRoomList();
    }

    private void handleGetPlayers(String msg) {
        String roomId = msg.substring("GET_PLAYERS|".length());
        Room target = Server.findRoomById(roomId);
        if (target == null) return;

        StringBuilder sb = new StringBuilder("PLAYER_LIST|");
        for (String p : target.getPlayers()) sb.append(p).append(",");
        send(sb.toString());
    }

    private void broadcastPlayerList(Room room) {
        StringBuilder sb = new StringBuilder("PLAYER_LIST|");
        for (String p : room.getPlayers()) sb.append(p).append(",");

        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom == room) ch.send(sb.toString());
            }
        }
    }

    private boolean isDead(Room room, String nick) {
        if (room == null || nick == null) return false;
        Set<String> set = deadPlayers.get(room);
        return set != null && set.contains(nick);
    }

    private void markDead(Room room, String nick) {
        if (room == null || nick == null) return;
        deadPlayers.computeIfAbsent(room, r -> new HashSet<>()).add(nick);
        System.out.println("💀 사망 처리: " + nick);
        
        // 🎭 도둑 시스템: 첫 사망자의 직업 훔치기
        if (thiefStolenRole.get(room) == null) {
            String thiefNick = findPlayerByRole(room, "THIEF");
            if (thiefNick != null && !isDead(room, thiefNick)) {
                Map<String, String> roles = roomRoles.get(room);
                String deadRole = roles.get(nick);
                
                // 도둑은 도둑 직업을 훔칠 수 없음
                if (!"THIEF".equals(deadRole)) {
                    thiefStolenRole.put(room, deadRole);
                    
                    // 그 직업이 이미 능력을 사용했는지 확인
                    boolean alreadyUsed = checkIfAbilityUsed(room, nick, deadRole);
                    thiefAbilityUsed.put(room, alreadyUsed);
                    
                    sendToPlayer(room, thiefNick, "THIEF_STOLEN|" + deadRole + "|" + (alreadyUsed ? "USED" : "AVAILABLE"));
                    System.out.println("🎭 도둑이 " + nick + "의 직업 [" + deadRole + "] 훔침");
                }
            }
        }
    }

    private boolean checkIfAbilityUsed(Room room, String nick, String role) {
        // 각 직업별로 능력 사용 여부 확인
        switch (role) {
            case "FORGER":
                return forgerUsed.getOrDefault(room, false);
            case "HACKER":
                return hackerUsed.getOrDefault(room, false);
            case "TIME_MANAGER":
                return timeManagerUsed.getOrDefault(room, false);
            // 다른 직업들은 여러 번 사용 가능하므로 false
            default:
                return false;
        }
    }

    private void handleChat(String msg) {
        if (currentRoom == null) return;
        String[] p = msg.split("\\|", 3);
        if (p.length < 3) return;

        String sender = p[1];
        String text = p[2];
        boolean senderDead = isDead(currentRoom, sender);

        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom != currentRoom) continue;
                if (senderDead) {
                    if (isDead(ch.currentRoom, ch.nickname)) {
                        ch.send("GHOST_CHAT|" + sender + "|" + text);
                    }
                } else {
                    if (!isDead(ch.currentRoom, ch.nickname)) {
                        ch.send(msg);
                    }
                }
            }
        }
    }

    private void handleMafiaChat(String msg) {
        if (currentRoom == null) return;
        String[] p = msg.split("\\|", 3);
        if (p.length < 3) return;

        String sender = p[1];
        String text = p[2];

        Map<String, String> roles = roomRoles.get(currentRoom);
        if (roles == null) return;
        
        String senderRole = roles.get(sender);
        if (!"MAFIA".equals(senderRole) && !"FORGER".equals(senderRole) && !"HACKER".equals(senderRole)) {
            return;
        }
        if (isDead(currentRoom, sender)) return;

        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom != currentRoom) continue;
                String targetRole = roles.get(ch.nickname);
                if (("MAFIA".equals(targetRole) || "FORGER".equals(targetRole) || "HACKER".equals(targetRole)) 
                    && !isDead(ch.currentRoom, ch.nickname)) {
                    ch.send("MAFIA_CHAT|" + sender + "|" + text);
                }
            }
        }
    }

    private void handleStartGame(String msg) {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

        String requester = null;
        String[] parts = msg.split("\\|");
        if (parts.length >= 2) requester = parts[1].trim();

        String host = currentRoom.getHostNickname();
        if (host == null || host.isEmpty()) {
            host = !currentRoom.getPlayers().isEmpty() ? currentRoom.getPlayers().get(0) : "";
            currentRoom.setHostNickname(host);
        }

        if (host != null && requester != null && !host.equals(requester)) {
            send("ERROR|NOT_HOST");
            return;
        }

        System.out.println("🎮 게임 시작!");

        Map<String, String> roles = assignRoles(currentRoom);
        roomRoles.put(currentRoom, roles);
        
        // 능력 초기화
        forgerUsed.put(currentRoom, false);
        hackerUsed.put(currentRoom, false);
        timeManagerUsed.put(currentRoom, false);
        thiefStolenRole.remove(currentRoom);
        thiefAbilityUsed.remove(currentRoom);
        
        // 운명가에게 3명 알려주기
        String destinyNick = findPlayerByRole(currentRoom, "DESTINY");
        if (destinyNick != null) {
            List<String> targets = selectDestinyTargets(currentRoom, destinyNick);
            destinyTargets.put(currentRoom, targets);
        }

        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom == currentRoom) {
                    String playerNickname = ch.nickname;
                    String role = roles.get(playerNickname);
                    ch.send("ROLE|" + playerNickname + "|" + role);
                }
            }
        }

        startDayPhase();
    }

    /** 🎲 역할 배정 시스템 */
    private Map<String, String> assignRoles(Room room) {
        List<String> players = new ArrayList<>(room.getPlayers());
        int count = players.size();
        String mode = room.getMode();

        Map<String, String> assigned = new HashMap<>();

        // CLASSIC 모드: 기본 직업만
        if ("CLASSIC".equals(mode)) {
            int mafiaCount = (count <= 6) ? 1 : (count <= 8) ? 2 : 3;
            List<String> roles = new ArrayList<>();
            
            for (int i = 0; i < mafiaCount; i++) roles.add("MAFIA");
            roles.add("DOCTOR");
            roles.add("POLICE");
            while (roles.size() < count) roles.add("CIVILIAN");
            
            Collections.shuffle(roles);
            for (int i = 0; i < players.size(); i++) {
                assigned.put(players.get(i), roles.get(i));
            }
            
            System.out.println("🧩 역할 배정 [CLASSIC]: " + assigned);
            return assigned;
        }

        // SPECIAL 모드: 8~10명만 가능
        if (count < 8) {
            // 8명 미만이면 CLASSIC 로직 사용
            return assignRoles(room);
        }

        // 팀 구성
        int mafiaTeamCount, neutralCount, civilTeamCount;
        
        if (count == 8) {
            mafiaTeamCount = 2;
            neutralCount = 2;
            civilTeamCount = 4;
        } else if (count == 9) {
            mafiaTeamCount = 2;
            neutralCount = 2;
            civilTeamCount = 5;
        } else { // 10명
            mafiaTeamCount = 3;
            neutralCount = 2;
            civilTeamCount = 5;
        }

        // 고정 직업
        List<String> mafiaTeam = new ArrayList<>(Arrays.asList("MAFIA"));
        List<String> neutral = new ArrayList<>();
        List<String> civilTeam = new ArrayList<>(Arrays.asList("POLICE", "DOCTOR", "CIVILIAN"));

        // 특수 직업 풀
        List<String> specialMafia = Arrays.asList("FORGER", "HACKER");
        List<String> specialNeutral = Arrays.asList("JESTER", "THIEF");
        List<String> specialCivil = Arrays.asList("TIME_MANAGER", "DESTINY", "TRACKER");

        // 마피아팀 채우기
        Collections.shuffle(specialMafia);
        for (String role : specialMafia) {
            if (mafiaTeam.size() < mafiaTeamCount) {
                mafiaTeam.add(role);
            }
        }

        // 중립 채우기
        Collections.shuffle(specialNeutral);
        for (String role : specialNeutral) {
            if (neutral.size() < neutralCount) {
                neutral.add(role);
            }
        }

        // 시민팀 채우기
        Collections.shuffle(specialCivil);
        for (String role : specialCivil) {
            if (civilTeam.size() < civilTeamCount) {
                civilTeam.add(role);
            }
        }
        while (civilTeam.size() < civilTeamCount) {
            civilTeam.add("CIVILIAN");
        }

        // 모든 역할 합치기
        List<String> allRoles = new ArrayList<>();
        allRoles.addAll(mafiaTeam);
        allRoles.addAll(neutral);
        allRoles.addAll(civilTeam);

        Collections.shuffle(allRoles);
        Collections.shuffle(players);

        for (int i = 0; i < players.size(); i++) {
            assigned.put(players.get(i), allRoles.get(i));
        }

        System.out.println("🧩 역할 배정 [SPECIAL " + count + "명]: " + assigned);
        return assigned;
    }

    /** 🔮 운명가: 3명 선택 (1명은 반드시 마피아) */
    private List<String> selectDestinyTargets(Room room, String destinyNick) {
        Map<String, String> roles = roomRoles.get(room);
        List<String> players = new ArrayList<>(room.getPlayers());
        players.remove(destinyNick);

        List<String> mafiasTeam = new ArrayList<>();
        List<String> others = new ArrayList<>();

        for (String p : players) {
            String role = roles.get(p);
            if ("MAFIA".equals(role) || "FORGER".equals(role) || "HACKER".equals(role)) {
                mafiasTeam.add(p);
            } else {
                others.add(p);
            }
        }

        List<String> selected = new ArrayList<>();
        
        // 1명은 반드시 마피아팀
        if (!mafiasTeam.isEmpty()) {
            Collections.shuffle(mafiasTeam);
            selected.add(mafiasTeam.get(0));
        }

        // 나머지 2명은 랜덤
        Collections.shuffle(others);
        int needed = 3 - selected.size();
        for (int i = 0; i < needed && i < others.size(); i++) {
            selected.add(others.get(i));
        }

        // 부족하면 마피아팀에서 더 추가
        while (selected.size() < 3 && mafiasTeam.size() > selected.size()) {
            for (String m : mafiasTeam) {
                if (!selected.contains(m)) {
                    selected.add(m);
                    break;
                }
            }
        }

        Collections.shuffle(selected);
        
        StringBuilder targets = new StringBuilder("DESTINY_TARGETS|");
        for (String t : selected) {
            targets.append(t).append(",");
        }
        sendToPlayer(room, destinyNick, targets.toString());

        System.out.println("🔮 운명가 " + destinyNick + "에게 제시된 이름: " + selected);
        return selected;
    }

    private String findPlayerByRole(Room room, String role) {
        Map<String, String> roles = roomRoles.get(room);
        if (roles == null) return null;
        
        for (Map.Entry<String, String> entry : roles.entrySet()) {
            if (role.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void sendToPlayer(Room room, String nickname, String msg) {
        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom == room && nickname.equals(ch.nickname)) {
                    ch.send(msg);
                    return;
                }
            }
        }
    }

    private void broadcastToRoom(String msg) {
        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom == currentRoom) {
                    ch.send(msg);
                }
            }
        }
    }

    public void send(String msg) {
        out.println(msg);
    }
    
    // 다음 파트에서 계속 (투표, 밤, 특수능력 처리)
    // ClientHandler.java 계속 (Part 2)

    /** 낮 페이즈 시작 */
    private void startDayPhase() {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

        broadcastToRoom("DAY_START|discussion");

        new Thread(() -> {
            try {
                Thread.sleep(10000); // 낮 토론 10초
            } catch (Exception ignored) {}

            if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

            broadcastToRoom("VOTE_START");
            
            // ⏰ 시간 관리자에게 선택권 주기
            String timeManagerNick = findPlayerByRole(currentRoom, "TIME_MANAGER");
            if (timeManagerNick != null && !isDead(currentRoom, timeManagerNick) 
                && !timeManagerUsed.getOrDefault(currentRoom, false)) {
                sendToPlayer(currentRoom, timeManagerNick, "TIME_MANAGER_PROMPT|밤을 건너뛰고 다음 낮으로 이동하시겠습니까?");
            }

            try {
                Thread.sleep(10000); // 투표 시간 10초
            } catch (Exception ignored) {}

            if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

            finishVotePhase();
        }).start();
    }

    /** 투표 처리 */
    private void handleVote(String msg) {
        String[] p = msg.split("\\|");
        if (p.length < 3 || currentRoom == null) return;

        String voter = p[1];
        String target = p[2];

        if (isDead(currentRoom, voter)) return;

        synchronized (roomVotes) {
            Map<String, String> voteMap = roomVotes.computeIfAbsent(currentRoom, r -> new HashMap<>());
            voteMap.put(voter, target);
        }

        System.out.println("🗳 투표: " + voter + " → " + target);
    }

    /** 투표 종료 및 결과 처리 */
    private void finishVotePhase() {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

        Map<String, String> voteMap;
        synchronized (roomVotes) {
            voteMap = new HashMap<>(roomVotes.getOrDefault(currentRoom, new HashMap<>()));
        }

        if (voteMap == null || voteMap.isEmpty()) {
            broadcastToRoom("VOTE_RESULT|NONE|NONE");
            synchronized (roomVotes) {
                roomVotes.remove(currentRoom);
            }
            if (checkGameOver()) return;
            
            // 시간 관리자 능력 확인
            if (checkTimeManagerSkipNight()) {
                startDayPhase();
            } else {
                startNightPhase();
            }
            return;
        }

        // 🔧 1단계: 해커에게 투표 결과 먼저 전송
        Map<String, String> roles = roomRoles.get(currentRoom);
        String hackerNick = findPlayerByRole(currentRoom, "HACKER");
        
        if (hackerNick != null && !isDead(currentRoom, hackerNick) 
            && !hackerUsed.getOrDefault(currentRoom, false)) {
            
            StringBuilder voteInfo = new StringBuilder("HACKER_VOTE_INFO|");
            for (Map.Entry<String, String> entry : voteMap.entrySet()) {
                voteInfo.append(entry.getKey()).append(":").append(entry.getValue()).append(",");
            }
            
            sendToPlayer(currentRoom, hackerNick, voteInfo.toString());
            sendToPlayer(currentRoom, hackerNick, "HACKER_PROMPT|해커님, 15초 안에 한 사람의 투표를 조작할 수 있습니다.");
            
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {}
            
            // 해커 투표 조작 확인
            Map<String, String> change = hackerVoteChange.get(currentRoom);
            if (change != null) {
                String voter = change.get("voter");
                String newTarget = change.get("target");
                voteMap.put(voter, newTarget);
                hackerUsed.put(currentRoom, true);
                System.out.println("🔧 해커가 " + voter + "의 투표를 " + newTarget + "으로 변경");
            }
            hackerVoteChange.remove(currentRoom);
        }

        // 투표 집계
        Map<String, Integer> counter = new HashMap<>();
        for (String target : voteMap.values()) {
            counter.put(target, counter.getOrDefault(target, 0) + 1);
        }

        String dead = null;
        int max = 0;

        for (Map.Entry<String, Integer> entry : counter.entrySet()) {
            String user = entry.getKey();
            int c = entry.getValue();
            if (c > max) {
                max = c;
                dead = user;
            }
        }

        if (dead == null) {
            broadcastToRoom("VOTE_RESULT|NONE|NONE");
            synchronized (roomVotes) {
                roomVotes.remove(currentRoom);
            }
            if (checkGameOver()) return;
            
            if (checkTimeManagerSkipNight()) {
                startDayPhase();
            } else {
                startNightPhase();
            }
            return;
        }

        // 🎭 광대 승리 체크
        String deadRole = roles.get(dead);
        if ("JESTER".equals(deadRole)) {
            broadcastToRoom("JESTER_WIN|" + dead);
            finishedRooms.add(currentRoom);
            cleanupRoom(currentRoom);
            System.out.println("🎭 광대 " + dead + " 승리!");
            return;
        }

        // 🎭 2단계: 위조범에게 사망자 직업 전송
        String forgerNick = findPlayerByRole(currentRoom, "FORGER");
        
        if (forgerNick != null && !isDead(currentRoom, forgerNick) 
            && !forgerUsed.getOrDefault(currentRoom, false)) {
            
            sendToPlayer(currentRoom, forgerNick, "FORGER_PROMPT|" + dead + "|" + deadRole);
            
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {}
            
            // 위조범이 직업 변경했는지 확인
            String forged = forgedRole.get(currentRoom);
            if (forged != null && !forged.isEmpty()) {
                deadRole = forged;
                forgerUsed.put(currentRoom, true);
                System.out.println("🎭 위조범이 직업을 [" + forged + "]로 변경");
            }
            forgedRole.remove(currentRoom);
        }

        // 사망 처리
        markDead(currentRoom, dead);
        
        // 3단계: 전체에게 결과 공개
        broadcastToRoom("VOTE_RESULT|" + dead + "|" + deadRole);
        broadcastPlayerList(currentRoom);

        synchronized (roomVotes) {
            roomVotes.remove(currentRoom);
        }

        if (checkGameOver()) return;
        
        mafiaTargets.remove(currentRoom);
        doctorTargets.remove(currentRoom);

        // 시간 관리자 능력 확인
        if (checkTimeManagerSkipNight()) {
            startDayPhase();
        } else {
            startNightPhase();
        }
    }

    /** 🔧 해커 투표 조작 */
    private void handleHackerChange(String msg) {
        String[] parts = msg.split("\\|");
        if (parts.length < 3 || currentRoom == null) return;
        
        String voter = parts[1];
        String newTarget = parts[2];
        
        Map<String, String> change = new HashMap<>();
        change.put("voter", voter);
        change.put("target", newTarget);
        
        hackerVoteChange.put(currentRoom, change);
        System.out.println("🔧 해커가 투표 조작: " + voter + " → " + newTarget);
    }

    /** 🎭 위조범 직업 변경 */
    private void handleForgerChange(String msg) {
        String[] parts = msg.split("\\|");
        if (parts.length < 2 || currentRoom == null) return;
        
        String newRole = parts[1];
        forgedRole.put(currentRoom, newRole);
        System.out.println("🎭 위조범이 직업 변경: " + newRole);
    }

    /** ⏰ 시간 관리자 능력 사용 */
    private void handleTimeManagerChoice(String msg) {
        String[] parts = msg.split("\\|");
        if (parts.length < 2 || currentRoom == null) return;
        
        String choice = parts[1]; // YES or NO
        
        if ("YES".equals(choice)) {
            timeManagerUsed.put(currentRoom, true);
            broadcastToRoom("TIME_MANAGER_SKIP|밤을 건너뛰고 다음 낮으로 이동합니다!");
            System.out.println("⏰ 시간 관리자가 밤을 건너뜀");
        }
    }

    /** ⏰ 시간 관리자가 밤을 건너뛰었는지 확인 */
    private boolean checkTimeManagerSkipNight() {
        Boolean skipNight = timeManagerUsed.get(currentRoom);
        if (skipNight != null && skipNight) {
            timeManagerUsed.put(currentRoom, false); // 1회용이므로 리셋
            return true;
        }
        return false;
    }

    /** 밤 페이즈 시작 */
    private void startNightPhase() {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

        broadcastToRoom("NIGHT_START|power");
        
        // 야간 행동 기록 초기화
        nightActions.put(currentRoom, new HashMap<>());

        new Thread(() -> {
            try {
                Thread.sleep(30000); // 밤 30초
            } catch (Exception ignored) {}

            if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

            resolveNightActions();

            if (checkGameOver()) return;

            startDayPhase();
        }).start();
    }

    /** 야간 행동 처리 */
    private void handleNightAction(String msg) {
        String[] p = msg.split("\\|");
        if (p.length < 4 || currentRoom == null) return;

        String actor = p[1];
        String role = p[2];
        String target = p[3];

        if (isDead(currentRoom, actor)) return;

        // 🎭 도둑이 능력을 훔쳤는지 확인
        String stolenRole = thiefStolenRole.get(currentRoom);
        if (stolenRole != null && actor.equals(findPlayerByRole(currentRoom, "THIEF"))) {
            // 도둑이 훔친 능력을 사용하려는 경우
            if (thiefAbilityUsed.getOrDefault(currentRoom, false)) {
                sendToPlayer(currentRoom, actor, "CHAT|SERVER|❌ 이미 사용된 능력입니다.");
                return;
            }
            role = stolenRole; // 훔친 직업의 능력 사용
        }

        if ("MAFIA".equals(role) && actor.equals(target)) {
            sendToPlayer(currentRoom, actor, "CHAT|SERVER|❌ 마피아는 자신을 선택할 수 없습니다.");
            return;
        }

        System.out.println("🌙 야간 행동: " + actor + " (" + role + ") → " + target);
        
        // 야간 행동 기록
        Map<String, String> actions = nightActions.computeIfAbsent(currentRoom, r -> new HashMap<>());
        actions.put(actor, target);

        switch (role) {
            case "MAFIA":
                mafiaTargets.put(currentRoom, target);
                break;

            case "DOCTOR":
                doctorTargets.put(currentRoom, target);
                break;

            case "POLICE":
                Map<String, String> roles = roomRoles.get(currentRoom);
                String targetRole = roles != null ? roles.get(target) : null;
                String team = (targetRole != null && ("MAFIA".equals(targetRole) 
                    || "FORGER".equals(targetRole) || "HACKER".equals(targetRole))) ? "MAFIA" : targetRole;

                sendToPlayer(currentRoom, actor, "POLICE_RESULT|" + target + "|" + team);
                break;
        }
    }

    /** 🔍 추적자 대상 지정 */
    private void handleTrackerTarget(String msg) {
        String[] parts = msg.split("\\|");
        if (parts.length < 2 || currentRoom == null) return;
        
        String target = parts[1];
        String trackerNick = findPlayerByRole(currentRoom, "TRACKER");
        
        if (trackerNick != null && nickname.equals(trackerNick)) {
            trackerTargets.put(currentRoom, target);
            System.out.println("🔍 추적자가 " + target + " 추적 중");
        }
    }

    /** 야간 행동 해결 */
    private void resolveNightActions() {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

        String mafiaTarget = mafiaTargets.get(currentRoom);
        String doctorTarget = doctorTargets.get(currentRoom);

        String dead = null;

        if (mafiaTarget != null) {
            if (mafiaTarget.equals(doctorTarget)) {
                dead = null; // 의사가 구함
            } else {
                dead = mafiaTarget;
                markDead(currentRoom, dead);
            }
        }
        
        // 🔍 추적자 결과 알림
        String trackerNick = findPlayerByRole(currentRoom, "TRACKER");
        String trackerTarget = trackerTargets.get(currentRoom);
        
        if (trackerNick != null && !isDead(currentRoom, trackerNick) && trackerTarget != null) {
            Map<String, String> actions = nightActions.get(currentRoom);
            
            if (actions != null && actions.containsKey(trackerTarget)) {
                String targetAction = actions.get(trackerTarget);
                sendToPlayer(currentRoom, trackerNick, "TRACKER_RESULT|" + trackerTarget + "님이 " + targetAction + "님에게 행동했습니다.");
            } else {
                sendToPlayer(currentRoom, trackerNick, "TRACKER_RESULT|" + trackerTarget + "님은 아무 행동도 하지 않았습니다.");
            }
        }

        mafiaTargets.remove(currentRoom);
        doctorTargets.remove(currentRoom);
        trackerTargets.remove(currentRoom);
        nightActions.remove(currentRoom);

        if (dead == null) {
            broadcastToRoom("NIGHT_RESULT|NONE");
        } else {
            broadcastToRoom("NIGHT_RESULT|" + dead);
        }

        broadcastPlayerList(currentRoom);
    }

    /** 게임 종료 체크 */
    private boolean checkGameOver() {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return true;

        Map<String, String> roles = roomRoles.get(currentRoom);
        if (roles == null) return false;

        Set<String> dead = deadPlayers.getOrDefault(currentRoom, Collections.emptySet());

        int mafia = 0;
        int others = 0;
        boolean jesterAlive = false;
        boolean thiefAlive = false;

        for (String p : currentRoom.getPlayers()) {
            if (dead.contains(p)) continue;
            
            String role = roles.get(p);
            
            if ("JESTER".equals(role)) {
                jesterAlive = true;
                others++;
            } else if ("THIEF".equals(role)) {
                thiefAlive = true;
                // 도둑이 마피아 능력을 훔쳤다면 마피아팀으로 간주
                String stolenRole = thiefStolenRole.get(currentRoom);
                if (stolenRole != null && ("MAFIA".equals(stolenRole) || "FORGER".equals(stolenRole) || "HACKER".equals(stolenRole))) {
                    mafia++;
                } else {
                    others++;
                }
            } else if ("MAFIA".equals(role) || "FORGER".equals(role) || "HACKER".equals(role)) {
                mafia++;
            } else {
                others++;
            }
        }

        String winner = null;
        if (mafia == 0 && (mafia + others) > 0) {
            winner = "CIVIL";
        } else if (mafia >= others && mafia > 0) {
            winner = "MAFIA";
        }

        if (winner != null) {
            finishedRooms.add(currentRoom);

            StringBuilder winnerInfo = new StringBuilder("GAME_OVER|" + winner + "|");
            
            for (String player : currentRoom.getPlayers()) {
                String role = roles.get(player);
                
                if (winner.equals("CIVIL")) {
                    if (!"MAFIA".equals(role) && !"FORGER".equals(role) && !"HACKER".equals(role)) {
                        winnerInfo.append(player).append(":").append(role).append(",");
                    }
                } else if (winner.equals("MAFIA")) {
                    if ("MAFIA".equals(role) || "FORGER".equals(role) || "HACKER".equals(role)) {
                        winnerInfo.append(player).append(":").append(role).append(",");
                    }
                }
            }
            
            broadcastToRoom(winnerInfo.toString());
            System.out.println("🏁 게임 종료! 승자: " + winner);

            cleanupRoom(currentRoom);
            return true;
        }

        return false;
    }

    /** 방 정리 */
    private void cleanupRoom(Room room) {
        roomVotes.remove(room);
        deadPlayers.remove(room);
        mafiaTargets.remove(room);
        doctorTargets.remove(room);
        roomRoles.remove(room);
        forgerUsed.remove(room);
        hackerUsed.remove(room);
        timeManagerUsed.remove(room);
        forgedRole.remove(room);
        hackerVoteChange.remove(room);
        thiefStolenRole.remove(room);
        thiefAbilityUsed.remove(room);
        destinyTargets.remove(room);
        trackerTargets.remove(room);
        nightActions.remove(room);
    }
}