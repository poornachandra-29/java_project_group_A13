package common;

public class User {
    private int id;
    private String username;
    private String password;

    // avatar binary + mime
    private byte[] avatar;
    private String avatarMime;

    // ===== Added to stay in sync with DB =====
    // user.online
    private boolean online;

    // user.last_seen (e.g. "2025-11-15 09:30:00")
    private String lastSeenIso;

    // user.avatar_updated_at (if avatar caching is needed)
    private String avatarUpdatedAtIso;

    public User() {}

    public User(int id, String username, String password) {
        this.id = id;
        this.username = username;
        this.password = password;
    }

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }

    // ===== Getters and Setters =====

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public byte[] getAvatar() {
        return avatar;
    }

    public void setAvatar(byte[] avatar) {
        this.avatar = avatar;
    }

    public String getAvatarMime() {
        return avatarMime;
    }

    public void setAvatarMime(String avatarMime) {
        this.avatarMime = avatarMime;
    }

    // ===== Additional Getters and Setters =====

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public String getLastSeenIso() {
        return lastSeenIso;
    }

    public void setLastSeenIso(String lastSeenIso) {
        this.lastSeenIso = lastSeenIso;
    }

    public String getAvatarUpdatedAtIso() {
        return avatarUpdatedAtIso;
    }

    public void setAvatarUpdatedAtIso(String avatarUpdatedAtIso) {
        this.avatarUpdatedAtIso = avatarUpdatedAtIso;
    }
}
