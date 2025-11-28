package server;

import common.Room;

import java.io.*;
import java.net.Socket;
import java.util.*;

/**
 * 마피아 게임용 ClientHandler - 완전 수정 버전
 * 비밀번호 기능 완벽 작동
 */
public class ClientHandler implements Runnable {

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private List<ClientHandler> clients;
    private List<Room> rooms;

    private Room currentRoom;
    private String nickname;

    private static Map<Room, Map<String, String>> roomVotes = new HashMap<>();
    private static Map<Room, String> mafiaTargets  = new HashMap<>();
    private static Map<Room, String> doctorTargets = new HashMap<>();
    private static Map<Room, Map<String, String>> roomRoles = new HashMap<>();
    private static Map<Room, Set<String>> deadPlayers = new HashMap<>();
    private static Set<Room> finishedRooms = Collections.synchronizedSet(new HashSet<>());

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

    /** 🔵 방 목록 전송 */
    private void sendRoomList() {
        StringBuilder builder = new StringBuilder("ROOM_LIST|");

        synchronized (rooms) {
            for (Room r : rooms) {
                String lockIcon = r.hasPassword() ? "🔒 " : "";
                builder.append(lockIcon)
                        .append("#")
                        .append(r.getId()).append(" ")
                        .append(r.getName())
                        .append(" (")
                        .append(r.getPlayers().size())
                        .append("/")
                        .append(r.getLimit())
                        .append("),");
            }
        }

        send(builder.toString());
    }

    /** 🔵 방 생성 */
    private void createRoom(String payload) {
        System.out.println("🔧 [방 생성] payload: " + payload);
        
        String creatorNickname = null;
        String roomName;
        String mode = "CLASSIC";
        int limit = 10;
        String password = "";

        String[] parts = payload.split("\\|", -1);  // -1을 사용하면 빈 문자열도 유지
        
        System.out.println("🔧 parts.length: " + parts.length);
        for (int i = 0; i < parts.length; i++) {
            System.out.println("🔧 parts[" + i + "]: [" + parts[i] + "]");
        }

        if (parts.length >= 2) {
            creatorNickname = parts[0].trim();
            roomName        = parts[1].trim();
            
            if (parts.length >= 3 && !parts[2].isEmpty()) {
                mode = parts[2].trim();
            }
            
            if (parts.length >= 4 && !parts[3].isEmpty()) {
                try {
                    limit = Integer.parseInt(parts[3].trim());
                } catch (NumberFormatException e) {
                    limit = 10;
                }
            }
            
            if (parts.length >= 5 && !parts[4].isEmpty()) {
                password = parts[4].trim();
            }
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
            + " | 비밀번호: [" + newRoom.getPassword() + "] (" 
            + (newRoom.hasPassword() ? "있음" : "없음") + ")");

        Server.broadcastRoomList();
    }

    /** 🔵 방 참가 */
    private void handleJoinRoom(String msg) {
        System.out.println("🔧 [방 입장] msg: " + msg);
        
        String[] parts = msg.split("\\|", -1);  // -1을 사용하면 빈 문자열도 유지
        
        System.out.println("🔧 parts.length: " + parts.length);
        for (int i = 0; i < parts.length; i++) {
            System.out.println("🔧 parts[" + i + "]: [" + parts[i] + "]");
        }
        
        if (parts.length < 3) {
            System.out.println("❌ 잘못된 메시지 형식");
            return;
        }

        this.nickname = parts[1].trim();
        String roomId = parts[2].trim();
        String inputPassword = "";
        
        if (parts.length >= 4) {
            inputPassword = parts[3].trim();
        }

        System.out.println("🔧 닉네임: [" + nickname + "]");
        System.out.println("🔧 방ID: [" + roomId + "]");
        System.out.println("🔧 입력 비밀번호: [" + inputPassword + "]");

        Room target = Server.findRoomById(roomId);
        
        if (target == null) {
            System.out.println("❌ 방을 찾을 수 없음");
            send("JOIN_FAIL|NOT_FOUND");
            return;
        }

        System.out.println("🔧 찾은 방: #" + target.getId() + " " + target.getName());
        System.out.println("🔧 방 비밀번호: [" + target.getPassword() + "]");
        System.out.println("🔧 hasPassword: " + target.hasPassword());

        // 비밀번호 검증
        if (target.hasPassword()) {
            System.out.println("🔐 비밀번호 검증 중...");
            System.out.println("   저장된 비밀번호: [" + target.getPassword() + "] (길이: " + target.getPassword().length() + ")");
            System.out.println("   입력된 비밀번호: [" + inputPassword + "] (길이: " + inputPassword.length() + ")");
            
            if (!target.checkPassword(inputPassword)) {
                System.out.println("❌ 비밀번호 불일치!");
                send("JOIN_FAIL|WRONG_PASSWORD");
                return;
            }
            System.out.println("✅ 비밀번호 일치!");
        } else {
            System.out.println("🔓 비밀번호 없는 방");
        }

        if (target.getPlayers().size() >= target.getLimit()) {
            System.out.println("❌ 방이 가득참");
            send("JOIN_FAIL|FULL");
            return;
        }

        target.getPlayers().add(nickname);
        this.currentRoom = target;

        String host = target.getHostNickname();
        if (host == null || host.isEmpty()) {
            if (!target.getPlayers().isEmpty()) {
                host = target.getPlayers().get(0);
            } else {
                host = nickname;
            }
            target.setHostNickname(host);
        }

        System.out.println("✅ 입장 성공: " + nickname + " → 방 #" + target.getId());
        
        send("JOIN_OK|" + target.getId() + "|" + target.getName() + "|" + host);
        broadcastPlayerList(target);
        Server.broadcastRoomList();
    }

    private void handleGetPlayers(String msg) {
        String roomId = msg.substring("GET_PLAYERS|".length());
        Room target = Server.findRoomById(roomId);
        if (target == null) return;

        StringBuilder sb = new StringBuilder("PLAYER_LIST|");
        for (String p : target.getPlayers())
            sb.append(p).append(",");

        send(sb.toString());
    }

    private void broadcastPlayerList(Room room) {
        StringBuilder sb = new StringBuilder("PLAYER_LIST|");

        for (String p : room.getPlayers())
            sb.append(p).append(",");

        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom == room) {
                    ch.send(sb.toString());
                }
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
    }

    private void handleChat(String msg) {
        if (currentRoom == null) return;

        String[] p = msg.split("\\|", 3);
        if (p.length < 3) return;

        String sender = p[1];
        String text   = p[2];

        boolean senderDead = isDead(currentRoom, sender);

        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom != currentRoom) continue;

                if (senderDead) {
                    if (isDead(ch.currentRoom, ch.nickname)) {
                        ch.send("GHOST_CHAT|" + sender + "|" + text);
                    }
                }
                else {
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
        String text   = p[2];

        Map<String, String> roles = roomRoles.get(currentRoom);
        if (roles == null || !"MAFIA".equals(roles.get(sender))) {
            return;
        }

        if (isDead(currentRoom, sender)) {
            return;
        }

        synchronized (clients) {
            for (ClientHandler ch : clients) {
                if (ch.currentRoom != currentRoom) continue;
                
                String targetRole = roles.get(ch.nickname);
                if ("MAFIA".equals(targetRole) && !isDead(ch.currentRoom, ch.nickname)) {
                    ch.send("MAFIA_CHAT|" + sender + "|" + text);
                }
            }
        }
    }

    private void handleStartGame(String msg) {
        if (currentRoom == null) return;
        if (finishedRooms.contains(currentRoom)) return;

        String requester = null;
        String[] parts = msg != null ? msg.split("\\|") : new String[0];
        if (parts.length >= 2) {
            requester = parts[1].trim();
        }

        String host = currentRoom.getHostNickname();

        if (host == null || host.isEmpty()) {
            if (!currentRoom.getPlayers().isEmpty()) {
                host = currentRoom.getPlayers().get(0);
                currentRoom.setHostNickname(host);
            }
        }

        if (host != null && requester != null && !host.equals(requester)) {
            send("ERROR|NOT_HOST");
            System.out.println("⛔ 비방장 게임 시작 거절");
            return;
        }

        System.out.println("🎮 게임 시작!");

        Map<String, String> roles = assignRoles(currentRoom);
        roomRoles.put(currentRoom, roles);

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

    private Map<String, String> assignRoles(Room room) {
        List<String> players = room.getPlayers();
        int count = players.size();

        int mafiaCount;
        if (count <= 6) mafiaCount = 1;
        else if (count <= 8) mafiaCount = 2;
        else mafiaCount = 3;

        int doctorCount = 1;
        int policeCount = 1;
        int civilianCount = count - (mafiaCount + doctorCount + policeCount);

        List<String> rolesPool = new ArrayList<>();

        for (int i = 0; i < mafiaCount; i++) rolesPool.add("MAFIA");
        for (int i = 0; i < doctorCount; i++) rolesPool.add("DOCTOR");
        for (int i = 0; i < policeCount; i++) rolesPool.add("POLICE");
        for (int i = 0; i < civilianCount; i++) rolesPool.add("CIVILIAN");

        Collections.shuffle(rolesPool);

        Map<String, String> assigned = new HashMap<>();
        for (int i = 0; i < players.size(); i++) {
            assigned.put(players.get(i), rolesPool.get(i));
        }

        System.out.println("🧩 역할 배정: " + assigned);
        return assigned;
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

    private void startDayPhase() {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

        broadcastToRoom("DAY_START|discussion");

        new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (Exception ignored) {}

            if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

            broadcastToRoom("VOTE_START");

            try {
                Thread.sleep(10000);
            } catch (Exception ignored) {}

            if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

            finishVotePhase();
        }).start();
    }

    private void handleVote(String msg) {
        String[] p = msg.split("\\|");
        if (p.length < 3 || currentRoom == null) return;

        String voter  = p[1];
        String target = p[2];

        if (isDead(currentRoom, voter)) {
            return;
        }

        synchronized (roomVotes) {
            Map<String, String> voteMap = roomVotes.computeIfAbsent(currentRoom, r -> new HashMap<>());
            voteMap.put(voter, target);
        }

        System.out.println("🗳 투표: " + voter + " → " + target);
    }

    private void finishVotePhase() {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

        Map<String, String> voteMap;
        synchronized (roomVotes) {
            voteMap = roomVotes.get(currentRoom);
        }

        if (voteMap == null || voteMap.isEmpty()) {
            broadcastToRoom("VOTE_RESULT|NONE");
            if (checkGameOver()) return;
            startNightPhase();
            return;
        }

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
            broadcastToRoom("VOTE_RESULT|NONE");
        } else {
            markDead(currentRoom, dead);
            broadcastToRoom("VOTE_RESULT|" + dead);
        }

        broadcastPlayerList(currentRoom);

        synchronized (roomVotes) {
            roomVotes.remove(currentRoom);
        }

        if (checkGameOver()) {
            return;
        }
        mafiaTargets.remove(currentRoom);
        doctorTargets.remove(currentRoom);

        startNightPhase();
    }

    private void startNightPhase() {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

        broadcastToRoom("NIGHT_START|power");

        new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (Exception ignored) {}

            if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

            resolveNightActions();

            if (checkGameOver()) {
                return;
            }

            startDayPhase();
        }).start();
    }

    private void handleNightAction(String msg) {
        String[] p = msg.split("\\|");
        if (p.length < 4 || currentRoom == null) return;

        String actor  = p[1];
        String role   = p[2];
        String target = p[3];

        if (isDead(currentRoom, actor)) {
            return;
        }

        if ("MAFIA".equals(role) && actor.equals(target)) {
            System.out.println("❌ 마피아 자기 선택 무시");
            synchronized (clients) {
                for (ClientHandler ch : clients) {
                    if (ch.currentRoom == currentRoom && actor.equals(ch.nickname)) {
                        ch.send("CHAT|SERVER|❌ 마피아는 자신을 선택할 수 없습니다.");
                    }
                }
            }
            return;
        }

        System.out.println("🌙 야간 행동: " + actor + " (" + role + ") → " + target);

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
                String team = (targetRole != null && targetRole.equals("MAFIA")) ? "MAFIA" : targetRole;

                synchronized (clients) {
                    for (ClientHandler ch : clients) {
                        if (ch.currentRoom == currentRoom && actor.equals(ch.nickname)) {
                            ch.send("POLICE_RESULT|" + target + "|" + team);
                        }
                    }
                }
                break;
        }
    }

    private void resolveNightActions() {
        if (currentRoom == null || finishedRooms.contains(currentRoom)) return;

        String mafiaTarget  = mafiaTargets.get(currentRoom);
        String doctorTarget = doctorTargets.get(currentRoom);

        String dead = null;

        if (mafiaTarget != null) {
            if (mafiaTarget.equals(doctorTarget)) {
                dead = null;
            } else {
                dead = mafiaTarget;
                markDead(currentRoom, dead);
            }
        }

        mafiaTargets.remove(currentRoom);
        doctorTargets.remove(currentRoom);

        if (dead == null) {
            broadcastToRoom("NIGHT_RESULT|NONE");
        } else {
            broadcastToRoom("NIGHT_RESULT|" + dead);
        }

        broadcastPlayerList(currentRoom);
    }

    private boolean checkGameOver() {
        if (currentRoom == null) return false;
        if (finishedRooms.contains(currentRoom)) return true;

        Map<String, String> roles = roomRoles.get(currentRoom);
        if (roles == null) return false;

        Set<String> dead = deadPlayers.getOrDefault(currentRoom, Collections.emptySet());

        int mafia = 0;
        int others = 0;

        for (String p : currentRoom.getPlayers()) {
            if (dead.contains(p)) continue;
            String role = roles.get(p);
            if ("MAFIA".equals(role)) mafia++;
            else others++;
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
                
                if (winner.equals("CIVIL") && !"MAFIA".equals(role)) {
                    winnerInfo.append(player).append(":").append(role).append(",");
                } else if (winner.equals("MAFIA") && "MAFIA".equals(role)) {
                    winnerInfo.append(player).append(":").append(role).append(",");
                }
            }
            
            broadcastToRoom(winnerInfo.toString());

            System.out.println("🏁 게임 종료! 승자: " + winner);

            roomVotes.remove(currentRoom);
            deadPlayers.remove(currentRoom);
            mafiaTargets.remove(currentRoom);
            doctorTargets.remove(currentRoom);
            roomRoles.remove(currentRoom);

            return true;
        }

        return false;
    }

    public void send(String msg) {
        out.println(msg);
    }
}