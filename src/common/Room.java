package common;

import java.util.ArrayList;
import java.util.List;

/**
 * 게임 방 정보 클래스.
 * - id: 방 고유 번호
 * - name: 방 이름
 * - players: 방에 속한 플레이어 닉네임 목록
 * - limit: 최대 인원 수
 * - hostNickname: 방장 닉네임 (게임 시작 권한자)
 * - mode: 게임 모드 (CLASSIC / SPECIAL 등)
 */
public class Room {

    private int id;
    private String name;
    private List<String> players;   // 방에 속한 플레이어 이름들
    private int limit = 10;         // 최대 인원
    private String hostNickname;    // 방장 닉네임
    private String mode = "CLASSIC"; // 기본 모드
    private String password = "";    // 방 비밀번호 (빈 문자열 = 비밀번호 없음)

    public Room(int id, String name) {
        this.id = id;
        this.name = name;
        this.players = new ArrayList<>();
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getPlayers() {
        return players;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    /** 방장 닉네임 반환 (없으면 null) */
    public String getHostNickname() {
        return hostNickname;
    }

    /** 방장 닉네임 설정 */
    public void setHostNickname(String hostNickname) {
        this.hostNickname = hostNickname;
    }

    /** 게임 모드 반환 */
    public String getMode() {
        return mode;
    }

    /** 게임 모드 설정 */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /** 비밀번호 반환 */
    public String getPassword() {
        return password;
    }

    /** 비밀번호 설정 */
    public void setPassword(String password) {
        this.password = password != null ? password : "";
    }

    /** 비밀번호가 설정되어 있는지 확인 */
    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    /** 비밀번호 검증 */
    public boolean checkPassword(String inputPassword) {
        if (!hasPassword()) return true; // 비밀번호 없으면 항상 통과
        return password.equals(inputPassword);
    }

    /** 현재 인원 수 */
    public int getCurrentPlayers() {
        return players.size();
    }

    @Override
    public String toString() {
        String lockIcon = hasPassword() ? "🔒 " : "";
        return lockIcon + "#" + id + " " + name + " [" + mode + "] (" + getCurrentPlayers() + "/" + limit + ")";
    }
}