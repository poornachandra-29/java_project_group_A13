package client.controller;

import client.ClientConnection;
import client.controller.right.SearchMessageHandler;
import common.Frame;
import common.MessageType;
import common.User;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javax.sound.sampled.*;
import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.ButtonType;

public class HomeController {
	@FXML private StackPane rootStack;
	@FXML private HBox mainRow;
    @FXML private VBox chatList;
    @FXML private TextField searchField;
    @FXML private Label currentChatName;
    @FXML private Label currentChatStatus;
    @FXML private VBox messageContainer;
    @FXML private TextField messageField;
    @FXML private Button settingsBtn;
    @FXML private Label infoName;
    @FXML private Label chatStatus;
    @FXML private StackPane centerStack;
    @FXML private VBox centerContent;
    @FXML private VBox centerEmpty;
    @FXML private StackPane rightStack;
    @FXML private VBox rightContent;
    @FXML private VBox rightEmpty;
    @FXML private VBox sidebar;
    @FXML private Label titleLabel;
    @FXML private Button toggleSidebarBtn;
    @FXML private Button searchIconBtn;
    @FXML private Button toggleRightBtn;
    @FXML private Node rightSearchTrigger;
    @FXML private ImageView midHeaderAvatar;
    @FXML private ImageView rightHeaderAvatar;
    @FXML private VBox inviteBox;
    @FXML private VBox leaveBox;
    @FXML private Label leaveIcon;
    @FXML private Label leaveLabel;

    
    @FXML private StackPane overlayLayer;
    @FXML private StackPane overlayContent;
    @FXML private javafx.scene.shape.Rectangle overlayDim;        
    @FXML private Button btnOverlayClose;
    @FXML private Button btnOverlayDownload;
    @FXML private TabPane mediaTabs;
    @FXML private TilePane photoGrid;
    @FXML private TilePane videoGrid;
    @FXML private VBox     docList;
    @FXML private Accordion rightMenu;
    @FXML private TitledPane mediaPane;
    
    @FXML private HBox leftHeader;
    @FXML private Region leftHeaderSpacer;

    @FXML private HBox replyBar;
    @FXML private ImageView replyThumb;
    @FXML private Label replyFileIcon;
    @FXML private Label replyTitle;
    @FXML private Label replyContent;
    @FXML private Button replyCloseBtn;
    @FXML private Label inviteIcon;
    @FXML private Label inviteLabel;

    private final LeftController leftCtrl = new LeftController();
    private final MidController midCtrl = new MidController();
    private final RightController rightCtrl = new RightController();

    private User currentUser;
    private Image selfAvatar;
    private ClientConnection connection;
    private String currentPeerUsername = null;
    private InviteDialogSession pendingInviteSession;

    @FXML
    private void initialize() {
        leftCtrl.bind(
            sidebar, chatList, searchField,
            titleLabel, toggleSidebarBtn, searchIconBtn,
            settingsBtn, leftHeader, leftHeaderSpacer
        );
        leftCtrl.setOnOpenGroupConversation(gvm -> {
            toggleCenterEmpty(false);
            toggleRightEmpty(false);
            currentPeerUsername = "group:" + gvm.groupId;
            // TODO: midCtrl.openGroup(gvm)  -> implement this method if needed.
            midCtrl.openGroupConversation(gvm);
            updateInviteButtonLabel();
            // TODO: load file/media history for the group if supported
            // connection.sendFileHistoryRequest(currentUser.getUsername(), "group:"+gvm.groupId, ...)
        });

        rightCtrl.bind(infoName, chatStatus, rightHeaderAvatar, mediaTabs, photoGrid, videoGrid, docList);
        rightCtrl.bindOverlay(overlayLayer, overlayContent, overlayDim, btnOverlayClose, btnOverlayDownload);
        midCtrl.bind(currentChatName, currentChatStatus, messageContainer, messageField, midHeaderAvatar);
        midCtrl.bindReplyBar(replyBar, replyThumb, replyFileIcon, replyTitle, replyContent, replyCloseBtn);
        midCtrl.setLeftController(leftCtrl);
        rightCtrl.setMidController(midCtrl);
        midCtrl.setRightController(rightCtrl);

        // Event handler when opening a conversation
        leftCtrl.setOnOpenConversation(user -> {
            currentPeerUsername = (user != null ? user.getUsername() : null);
            // REMOVE this line: currentPeerChatId = (user != null ? user.getChatId() : null);
            toggleCenterEmpty(false);
            toggleRightEmpty(false);
            midCtrl.openConversation(user);

            if (currentPeerUsername != null && connection != null && connection.isAlive()) {
                loadChatFiles(currentPeerUsername);
            }
            updateInviteButtonLabel();
        });

        Platform.runLater(() -> {
            if (centerStack != null && centerStack.getScene() != null) {
                leftCtrl.setHostStage((Stage) centerStack.getScene().getWindow());
            }

            if (rootStack != null && mainRow != null) {
                var w = rootStack.widthProperty();
                sidebar.prefWidthProperty().bind(w.multiply(0.20));
                centerStack.prefWidthProperty().bind(w.multiply(0.40));
                rightStack.prefWidthProperty().bind(w.multiply(0.40));
            }
        });
        
        VBox.setVgrow(mediaTabs, Priority.ALWAYS);

        if (rightStack != null) {
            if (rightMenu != null && mediaPane != null) {
                rightMenu.setExpandedPane(mediaPane);
            }
            if (mediaTabs != null && rightMenu != null && mediaPane != null) {
                mediaTabs.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
                    rightMenu.setExpandedPane(mediaPane);
                });
            }
        }
        
        toggleCenterEmpty(true);
        toggleRightEmpty(true);
    }

    private void loadChatFiles(String peerUsername) {
        if (connection != null && connection.isAlive()) {
            try {
                // Clear old data from tabs
                rightCtrl.clearMediaTabs();
                
                // Request file history from server
                connection.sendFileHistoryRequest(currentUser.getUsername(), peerUsername, 100, 0);
            } catch (IOException e) {
                System.err.println("[FILE] Failed to request file history: " + e.getMessage());
            }
        }
    }
    
    public User getCurrentUser() {
        return currentUser;
    }
    public String getCurrentPeerUsername() {
        return currentPeerUsername;
    }
    
    @FXML
    private void onRightSearchClick() {
        var owner = (centerStack != null) ? centerStack.getScene().getWindow() : null;
        new SearchMessageHandler(midCtrl).open(owner);
    }


    private void toggleCenterEmpty(boolean showEmpty) {
        centerEmpty.setVisible(showEmpty);
        centerEmpty.setManaged(showEmpty);
        centerContent.setVisible(!showEmpty);
        centerContent.setManaged(!showEmpty);
    }

    private void toggleRightEmpty(boolean showEmpty) {
        rightEmpty.setVisible(showEmpty);
        rightEmpty.setManaged(showEmpty);
        rightContent.setVisible(!showEmpty);
        rightContent.setManaged(!showEmpty);
    }

    public void onServerLine(String line) { /* no longer used */ }
    public void onConnectionError(Exception e) { /* optional */ }

    public void setCurrentUser(User user) {
        this.currentUser = user;
        leftCtrl.setCurrentUser(user);
        midCtrl.setCurrentUser(user);
        leftCtrl.reloadAll();
//        requestGroupListFromServer();
        updateInviteButtonLabel();
        leftCtrl.startPresencePolling();
    }
    
    public void setSelfAvatar(Image img) {
        this.selfAvatar = img;
        if (midCtrl != null) {
        	midCtrl.setSelfAvatar(img);
        }
    }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;

        midCtrl.setConnection(conn);
        leftCtrl.setConnection(conn);

        connection.startListener(
            frame -> {
                onServerFrame(frame);
            },
            err -> {
                onConnectionError(err);
            }
        );
    }



    public void reloadAll() { leftCtrl.reloadAll(); }
    public void searchUsers() { leftCtrl.searchUsers(searchField.getText()); }
    
    @FXML
    private void onToggleRightPanel() {
        boolean currentlyShown = rightStack.isManaged(); 
        boolean nextShown = !currentlyShown;

        rightStack.setVisible(nextShown);
        rightStack.setManaged(nextShown);
    }


    @FXML
    private void onSend() {
        if (midCtrl != null) midCtrl.onSendMessage();
    }

    @FXML
    private void onAttach() {
        if (connection == null || !connection.isAlive()) {
            System.out.println("[ATTACH] Not connected to the server.");
            return;
        }
        if (currentUser == null) {
            System.out.println("[ATTACH] Not logged in.");
            return;
        }
        final String toUser = currentPeerUsername;
        if (toUser == null || toUser.isBlank()) {
            System.out.println("[ATTACH] No chat selected / recipient cannot be determined.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Choose a file to send");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All files", "*.*"),
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
            new FileChooser.ExtensionFilter("Audio", "*.mp3", "*.m4a", "*.aac", "*.wav", "*.ogg"),
            new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.mov", "*.mkv", "*.webm")
        );

        Stage stage = (Stage) centerStack.getScene().getWindow();
        File file = fc.showOpenDialog(stage);
        if (file == null) {
            System.out.println("[ATTACH] User cancelled file selection.");
            return;
        }

        final String fromUser = currentUser.getUsername();
        final String fileId = UUID.randomUUID().toString();
        System.out.println("[ATTACH] Sending file: " + file.getAbsolutePath() + " -> @" + toUser + ", fileId=" + fileId);

        Thread t = new Thread(() -> {
            try {
                String mime = ClientConnection.guessMime(file);
                Frame ackF = connection.sendFileWithAck(fromUser, toUser, file, mime, fileId, 15_000);
                System.out.println("[ATTACH] ACK(tid=" + ackF.transferId + "): " + ackF.body);
            } catch (TimeoutException te) {
                System.out.println("[ATTACH] Timeout waiting for ACK.");
            } catch (Exception e) {
                System.out.println("[ATTACH] Error sending file: " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }, "send-file");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onVoice() {
        if (connection == null || !connection.isAlive()) {
            System.out.println("[VOICE] Not connected to the server.");
            return;
        }
        if (currentUser == null) {
            System.out.println("[VOICE] Not logged in.");
            return;
        }
        if (currentPeerUsername == null || currentPeerUsername.isBlank()) {
            System.out.println("[VOICE] No chat selected / recipient cannot be determined.");
            return;
        }

        File tempDir = new File("temp");
        if (!tempDir.exists()) tempDir.mkdirs();

        String audioId = UUID.randomUUID().toString();
        File audioFile = new File(tempDir, "voice-" + audioId + ".wav");

        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        midCtrl.showVoiceRecordDialog(centerStack.getScene().getWindow(), format, audioFile, (recordedBytes) -> {
            if (recordedBytes == null) {
                if (audioFile.exists()) {
                    audioFile.delete();
                    System.out.println("[VOICE] Deleted temp file due to cancellation: " + audioFile.getAbsolutePath());
                }
                return;
            }

            try (AudioInputStream audioInputStream = new AudioInputStream(
                    new ByteArrayInputStream(recordedBytes), format, recordedBytes.length / format.getFrameSize())) {
                AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, audioFile);
            } catch (Exception e) {
                System.out.println("[VOICE] Error saving audio file: " + e.getMessage());
                e.printStackTrace(System.out);
                if (audioFile.exists()) {
                    audioFile.delete();
                    System.out.println("[VOICE] Deleted temp file due to error: " + audioFile.getAbsolutePath());
                }
                Platform.runLater(() -> midCtrl.showErrorAlert("Could not save the audio file: " + e.getMessage()));
                return;
            }

            String fromUser = currentUser.getUsername();
            String toUser = currentPeerUsername;
            String mime = "audio/wav";

            System.out.println("[VOICE] Sending audio file: " + audioFile.getAbsolutePath() + " -> @" + toUser + ", fileId=" + audioId);

            Thread sendThread = new Thread(() -> {
                try {
                    Frame ack = connection.sendFileWithAck(fromUser, toUser, audioFile, mime, audioId, 15_000);
                    System.out.println("[VOICE] ACK(tid=" + ack.transferId + "): " + ack.body);

                    Platform.runLater(() -> {
                        try {
                            Thread.sleep(1000);
                            if (audioFile.exists()) {
                                audioFile.delete();
                                System.out.println("[VOICE] Deleted temp file: " + audioFile.getAbsolutePath());
                            }
                        } catch (InterruptedException ie) {
                            System.out.println("[VOICE] Error delaying file deletion: " + ie.getMessage());
                        }
                    });
                } catch (TimeoutException te) {
                    System.out.println("[VOICE] Timeout waiting for ACK.");
                    if (audioFile.exists()) {
                        audioFile.delete();
                        System.out.println("[VOICE] Deleted temp file due to timeout: " + audioFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    System.out.println("[VOICE] Error sending audio file: " + e.getMessage());
                    e.printStackTrace(System.out);
                    if (audioFile.exists()) {
                        audioFile.delete();
                        System.out.println("[VOICE] Deleted temp file due to error: " + audioFile.getAbsolutePath());
                    }
                }
            }, "send-voice");
            sendThread.setDaemon(true);
            sendThread.start();
        });
    }

    private void showErrorAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static String humanBytes(long v) {
        if (v <= 0) return "0 B";
        final String[] u = {"B","KB","MB","GB","TB"};
        int i = 0;
        double d = v;
        while (d >= 1024 && i < u.length - 1) { d /= 1024.0; i++; }
        return (d >= 10 ? String.format("%.0f %s", d, u[i]) : String.format("%.1f %s", d, u[i]));
    }
    
    public void setPendingInviteSession(InviteDialogSession s) {
        this.pendingInviteSession = s;
    }
    public void onGroupCreatedFromServer(int groupId) {
        final InviteDialogSession session = this.pendingInviteSession;

        if (session == null) {
            System.out.println("[WARN] Group created (id=" + groupId + ") but pendingInviteSession was null — delaying retry.");
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                    Platform.runLater(() -> {
                        if (this.pendingInviteSession != null) {
                            System.out.println("[INFO] Retry: now found pendingInviteSession, sending members.");
                            this.pendingInviteSession.setGroupId(groupId);  // ✅ FIX
                            this.pendingInviteSession.sendAddMembers();
                            this.pendingInviteSession = null;
                        } else {
                            System.out.println("[WARN] Still null after retry, skipping sendAddMembers.");
                        }
                    });
                } catch (InterruptedException ignored) {}
            }).start();
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(500);
                Platform.runLater(() -> {
                    try {
                        session.setGroupId(groupId);   // ✅ FIX HERE
                        session.sendAddMembers();
                    } catch (Exception e) {
                        System.err.println("[ERROR] sendAddMembers failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                    this.pendingInviteSession = null;
                });
            } catch (InterruptedException ignored) {}
        }).start();
    }



    private Stage getCurrentStage() {
        return (Stage) inviteBox.getScene().getWindow();
    }
    @FXML
    private void onInviteClick() {
        String me = (currentUser != null) ? currentUser.getUsername() : null;
        if (me == null) {
            showErrorAlert("You are not signed in, so you cannot create a group or invite members.");
            return;
        }
        if (connection == null || !connection.isAlive()) {
            showErrorAlert("Not connected to the server. This action cannot be performed.");
            return;
        }

        // Detect current context
        String currentPeer = midCtrl.getCurrentPeer(); // e.g., "group:11" or "hic"
        if (currentPeer != null && currentPeer.startsWith("group:")) {
            // 👉 Inside group chat
            int groupId;
            try {
                groupId = Integer.parseInt(currentPeer.substring("group:".length()));
            } catch (Exception e) {
                showErrorAlert("Could not determine the group ID.");
                return;
            }

            // Request current members from server (server will reply LIST_MEMBERS)
            onGroupInviteClick(groupId, midCtrl.getCurrentGroupChatName());
            return;
        }

        // 👉 Otherwise: 1-1 chat → open InviteDialogSession to CREATE group
        String peer = currentPeerUsername; // already stored when open 1-1 chat
        List<String> knownUsers = (leftCtrl != null) ? leftCtrl.getAllUsernames() : List.of(me);

        InviteDialogSession dialog = new InviteDialogSession(
                this,
                getCurrentStage(),
                me,
                peer,
                connection,
                knownUsers
        );

        dialog.show();

    }

    
    @FXML
    private void onGroupInviteClick(int groupId, String groupName) {
        String me = (currentUser != null) ? currentUser.getUsername() : null;
        if (me == null) {
            showErrorAlert("You are not signed in, so you cannot invite members.");
            return;
        }

        if (connection == null || !connection.isAlive()) {
            showErrorAlert("Not connected to the server. You cannot invite members.");
            return;
        }

        List<String> knownUsers = (leftCtrl != null) ? leftCtrl.getAllUsernames() : List.of(me);

        try {
            // ✅ FIXED: send groupId inside BODY
            Frame req = new Frame(
                MessageType.LIST_MEMBERS,
                currentUser.getUsername(),
                "",  // recipient empty
                String.valueOf(groupId) // body = groupId
            );
            connection.sendFrame(req);

            System.out.println("[INVITE] Sent LIST_MEMBERS for groupId=" + groupId);
            return; // wait for server reply
        } catch (Exception e) {
            showErrorAlert("Could not request the member list from the server.");
            e.printStackTrace();
        }
    }



    public void onServerFrame(Frame f) {

        switch (f.type) {

            // Server sends group list (each frame = 1 group)
            // Called after login in sendGroupListToClient()
            case GROUP_LIST: {
                final String json = f.body; // {"group_id":3,"name":"Group A","owner":"siu"}

                if (json != null && !json.isBlank()) {
                    String gidStr = jsonGet(json, "group_id");
                    String gname  = jsonGet(json, "name");
                    String gowner = jsonGet(json, "owner");

                    int gid = 0;
                    try { gid = Integer.parseInt(gidStr); } catch (Exception ignore) {}

                    final int gidFinal       = gid;
                    final String gnameFinal  = gname;
                    final String gownerFinal = gowner;

                    if (gidFinal > 0 && gnameFinal != null) {
                        Platform.runLater(() -> {
                            LeftController.GroupViewModel gvm =
                                new LeftController.GroupViewModel(
                                    gidFinal, gnameFinal, gownerFinal
                                );
                            leftCtrl.addSingleGroupToSidebar(gvm);
                        });
                    }
                }
                break;
            }
            case USER_LIST: {
                // f.body example: {"id":3,"username":"Long","online":1,"lastSeen":"2025-11-15 09:30:00",...}
                leftCtrl.handleUserListFrame(f);
                break;
            }

            // ACK from server: used for multiple purposes
            // - LOGIN: "OK LOGIN <username>"
            // - OFFLINE messages delivered
            // - OK_GROUP_CREATED {...}  <-- important
            case ACK: {
                final String body = f.body == null ? "" : f.body;

                // 1. Check if this is an ACK for creating a new group
                //    Server sendFrame(Frame.ack(respJson))
                //    respJson contains: "status":"OK_GROUP_CREATED", "group_id":..., "name":..., "owner":...
                String status = jsonGet(body, "status");
                if ("OK_GROUP_CREATED".equals(status)) {

                    String gidStr = jsonGet(body, "group_id");
                    String gname  = jsonGet(body, "name");
                    String gowner = jsonGet(body, "owner");

                    int gid = 0;
                    try { gid = Integer.parseInt(gidStr); } catch (Exception ignore) {}

                    final int gidFinal       = gid;
                    final String gnameFinal  = gname;
                    final String gownerFinal = gowner;

                    Platform.runLater(() -> {
                        // Step 1: send ADD_MEMBER with all selectedUsers from InviteDialogSession
                        if (gidFinal > 0) {
                            onGroupCreatedFromServer(gidFinal);
                        }

                        // Step 2: add the new group immediately into the sidebar for the creator
                        if (gidFinal > 0 && gnameFinal != null) {
                            LeftController.GroupViewModel gvm =
                                new LeftController.GroupViewModel(
                                    gidFinal, gnameFinal, gownerFinal
                                );
                            leftCtrl.addSingleGroupToSidebar(gvm);
                        }
                    });

                    break; // important: break after handling OK_GROUP_CREATED
                }
                if (body.startsWith("🔵 ") || body.startsWith("🔴 ")) {
                    System.out.println("[PRESENCE] broadcast: " + body);
                    Platform.runLater(() -> {
                        // reloadAll() to refresh online/offline + last_seen
                        leftCtrl.reloadAll();
                    });
                }

                // 2. Other ACKs (login, delivered...) keep previous logic if applicable
                //    example: if (body.startsWith("OK LOGIN")) { ... }
                //             if (body.startsWith("Delivered ")) { ... }
                break;
            }
            case ERROR: {
                InviteDialogSession session = pendingInviteSession;
                if (session != null) {
                    pendingInviteSession = null;
                    String reason = f.body == null || f.body.isBlank()
                            ? "The server returned an unknown error."
                            : f.body;
                    Platform.runLater(() -> session.onCreateGroupFailed(reason));
                }
                break;
            }
            case LIST_MEMBERS: {
                String json = f.body; // {"group_id":11,"members":["hic","huudat","Long","siu"]}
                int gid = Integer.parseInt(jsonGet(json, "group_id"));

                // Parse simple JSON array
                String arr = json.substring(json.indexOf('[')+1, json.indexOf(']'));
                List<String> existingMembers = Arrays.stream(arr.replace("\"", "").split(","))
                                                     .map(String::trim)
                                                     .filter(s -> !s.isEmpty())
                                                     .toList();

                String groupName = midCtrl.getCurrentGroupChatName();

                Platform.runLater(() -> {
                    openInviteDialog(gid, groupName, existingMembers);
                });
                break;
            }

            default:
                break;
        }

        // Finally forward any frame to midCtrl to handle chat, file, call, etc.
        if (midCtrl != null && f.type != MessageType.USER_LIST) {
            Platform.runLater(() -> midCtrl.onIncomingFrame(f));
        }
    }



    private void openInviteDialog(int groupId, String groupName, List<String> existingMembers) {
        String me = currentUser.getUsername();
        List<String> knownUsers = (leftCtrl != null) ? leftCtrl.getAllUsernames() : List.of(me);

        InviteDialogSession dialog = new InviteDialogSession(
                this,
                getCurrentStage(),
                me,
                null,
                connection,
                knownUsers
        );

        dialog.setInviteMode(groupId, groupName, existingMembers);
        dialog.show();
    }

    private static String jsonGet(String json, String key) {
        if (json == null) return null;
        String kq = "\"" + key + "\"";
        int i = json.indexOf(kq);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + kq.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;
        char c = json.charAt(j);
        if (c == '"') {
            int end = json.indexOf('"', j + 1);
            if (end < 0) return null;
            return json.substring(j + 1, end);
        } else {
            int end = j;
            while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) end++;
            return json.substring(j, end);
        }
    }
    private java.util.List<LeftController.GroupViewModel> parseGroupsFromJson(String json) {
        // Simple manual JSON parsing for groups
        // assumes json = {"groups":[{"id":5,"name":"abc","owner":"siu"}, {...}]}

        java.util.List<LeftController.GroupViewModel> list = new java.util.ArrayList<>();

        int arrStart = json.indexOf("\"groups\"");
        if (arrStart < 0) return list;

        arrStart = json.indexOf('[', arrStart);
        int arrEnd = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return list;

        String arrBody = json.substring(arrStart + 1, arrEnd); // {...},{...}

        // Split by "},{" since JSON structure is simple
        String[] items = arrBody.split("\\},\\{");

        for (String raw : items) {
            String obj = raw.trim();
            if (!obj.startsWith("{")) obj = "{" + obj;
            if (!obj.endsWith("}")) obj = obj + "}";

            String gidStr = jsonGet(obj, "id");
            String gname  = jsonGet(obj, "name");
            String gowner = jsonGet(obj, "owner");

            int gid = 0;
            try { gid = Integer.parseInt(gidStr); } catch (Exception ignore) {}

            if (gid > 0 && gname != null) {
                list.add(new LeftController.GroupViewModel(gid, gname, gowner));
            }
        }

        return list;
    }

    private void requestGroupListFromServer() {
        if (connection == null || !connection.isAlive()) return;
        if (currentUser == null) return;

        // Request body
        String body = "{\"username\":\"" + currentUser.getUsername() + "\",\"op\":\"list\"}";

        Frame req = new Frame(
                MessageType.GROUP_LIST,        // use GROUP_LIST
                currentUser.getUsername(),     // from
                "",                            // to (not needed)
                body
        );
        try {
            connection.sendFrame(req);
        } catch (IOException e) {
            System.err.println("[GROUP] cannot request group list: " + e.getMessage());
        }
    }
    private void updateInviteButtonLabel() {
        String currentPeer = midCtrl.getCurrentPeer();
        System.out.println("[DEBUG] updateInviteButtonLabel -> currentPeer = " + currentPeer);
        if (inviteLabel != null) {
            if (currentPeer != null && currentPeer.startsWith("group:")) {
                inviteLabel.setText("Invite");
                inviteIcon.setText("👥");
                Tooltip.install(inviteBox, new Tooltip("Invite more members"));
                // Show Leave group button
                if (leaveBox != null) {
                    leaveBox.setVisible(true);
                    leaveBox.setManaged(true);
                }

            } else {
                inviteLabel.setText("Create group");
                inviteIcon.setText("👥");
                Tooltip.install(inviteBox, new Tooltip("Create a new group from this chat"));
                if (leaveBox != null) {
                    leaveBox.setVisible(false);
                    leaveBox.setManaged(false);
                }
            }
        }
    }

    @FXML
    private void onLeaveGroupClick() {
        String currentPeer = midCtrl.getCurrentPeer();
        if (currentPeer == null || !currentPeer.startsWith("group:")) {
            showErrorAlert("You can only leave a group from a group chat.");
            return;
        }

        int groupId;
        try {
            groupId = Integer.parseInt(currentPeer.substring("group:".length()));
        } catch (Exception e) {
            showErrorAlert("Could not determine the group ID.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Leave group");
        confirm.setHeaderText(null);
        confirm.setContentText("Are you sure you want to leave this group?");
        if (confirm.showAndWait().filter(btn -> btn == ButtonType.OK).isEmpty()) return;

        try {
            // Send request to server
            String json = String.format("{\"group_id\":%d,\"username\":\"%s\"}", groupId, currentUser.getUsername());
            Frame frame = new Frame(MessageType.REMOVE_MEMBER, currentUser.getUsername(), "", json);
            connection.sendFrame(frame);
            System.out.println("[GROUP] Sent leave group request: " + json);

            // 1. Update UI immediately
            Platform.runLater(() -> {
                // Remove group from LeftController cache and sidebar
                leftCtrl.removeGroupFromSidebar(groupId);

                // Clear center interface
                toggleCenterEmpty(true);
                toggleRightEmpty(true);

                // Refresh group list
                requestGroupListFromServer();
            });

        } catch (IOException e) {
            showErrorAlert("Could not send the request to leave the group: " + e.getMessage());
        }
    }



}