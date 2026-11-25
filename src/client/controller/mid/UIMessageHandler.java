package client.controller.mid;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import client.controller.MidController;
import client.controller.mid.UtilHandler.MediaKind;

public class UIMessageHandler {
    private final MidController controller;

    public UIMessageHandler(MidController controller) {
        this.controller = controller;
    }
    
    /** Extract content VBox (contains header + bubble) from row */
    private VBox findContentVBoxInRow(HBox row) {
        if (row == null || row.getChildren().isEmpty()) return null;

        // Identify side with messageVBoxWrapper
        Node side = (row.getAlignment() == Pos.CENTER_LEFT)
                ? row.getChildren().get(0)
                : row.getChildren().get(row.getChildren().size() - 1);

        if (!(side instanceof VBox wrapper)) return null;

        // wrapper.children = [contentHBox]
        for (Node n : wrapper.getChildren()) {
            if (n instanceof HBox h) {
                for (Node c : h.getChildren()) {
                    if (c instanceof VBox contentVBox) {
                        return contentVBox;
                    }
                }
            }
        }
        return null;
    }

    /** Extract bubble Region (VBox/HBox with id incoming-text, incoming-file, ...) */
    private Region findBubbleRegion(HBox row) {
        VBox content = findContentVBoxInRow(row);
        if (content == null || content.getChildren().isEmpty()) return null;

        // Bubble is always the LAST element in contentVBox (after header and reply-chip)
        Node last = content.getChildren().get(content.getChildren().size() - 1);
        return (last instanceof Region r) ? r : null;
    }

    /** Check if row already has a reply-chip */
    private boolean hasReplyChipOnRow(HBox row) {
        VBox content = findContentVBoxInRow(row);
        if (content == null) return false;
        for (Node n : content.getChildren()) {
            if (n instanceof VBox vb && vb.getStyleClass().contains("reply-chip")) return true;
        }
        return false;
    }

    /** Attach reply-chip to row: placed between header and bubble */
    private void attachReplyChipToRow(HBox row, VBox chip) {
        if (row == null || chip == null) return;
        VBox content = findContentVBoxInRow(row);
        if (content == null) return;

        // Avoid duplicate attachments
        for (Node n : content.getChildren()) {
            if (n == chip) return;
            if (n instanceof VBox vb && vb.getStyleClass().contains("reply-chip")) return;
        }

        int idx = 0;
        if (!content.getChildren().isEmpty()) {
            Node first = content.getChildren().get(0);
            if (first instanceof HBox && first.getStyleClass().contains("message-header")) {
                idx = 1; // header at index 0 -> chip at index 1
            }
        }
        content.getChildren().add(idx, chip);
    }


    /* Keep size only if meta contains both MIME and SIZE */
    private static String normalizeSizeOnly(String meta) {
        if (meta == null) return "";
        String m = meta;
        int bullet = m.lastIndexOf('•');
        if (bullet >= 0) m = m.substring(bullet + 1);
        m = m.trim();
        if (m.matches("(?i)\\d+(?:\\.\\d+)?\\s*(B|KB|MB|GB|TB)")) return m;
        if (m.matches("(?i)\\d+(?:\\.\\d+)?(B|KB|MB|GB|TB)")) return m;
        return "";
    }
    
    private void attachSideMenu(HBox row, Region spacer, boolean incoming, String messageId) {
        System.out.println("[MENU] attachSideMenu incoming=" + incoming + " paramMsgId=" + messageId);

        HBox spacerBox = new HBox();
        HBox.setHgrow(spacerBox, Priority.ALWAYS);
        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);

        StackPane holder = new StackPane();
        holder.setPickOnBounds(false);
        StackPane.setAlignment(holder, incoming ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        Button menuBtn = new Button("⋮");
        menuBtn.setFocusTraversable(false);
        menuBtn.getStyleClass().add("msg-menu");
        menuBtn.setOpacity(0);
        holder.getChildren().add(menuBtn);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(120), menuBtn);
        fadeIn.setToValue(1.0);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(120), menuBtn);
        fadeOut.setToValue(0.0);

        ContextMenu cm = new ContextMenu();
        cm.getStyleClass().add("msg-context");
        MenuItem miEdit = new MenuItem("Edit");
        miEdit.getStyleClass().add("msg-context-item");
        MenuItem miDelete = new MenuItem("Delete");
        miDelete.getStyleClass().add("msg-context-item-danger");
        cm.getItems().addAll(miEdit, miDelete);

        MenuItem miDownload = new MenuItem("Download…");
        miDownload.getStyleClass().add("msg-context-item");
        cm.getItems().add(new SeparatorMenuItem());
        cm.getItems().add(miDownload);

        MenuItem miReply = new MenuItem("Reply");
        miReply.getStyleClass().add("msg-context-item");
        cm.getItems().add(miReply);

        // Get actual bubble node
        Region bubble = findBubbleRegion(row);
        String bubbleId = (bubble != null) ? bubble.getId() : null;

        // Text label used for Edit action
        final Label labelRef = findTextLabelInRow(row);

        final boolean canEdit = (labelRef != null) && "outgoing-text".equals(bubbleId);
        miEdit.setDisable(!canEdit);

        miEdit.setOnAction(e -> {
            if (!canEdit) return;
            final Object msgId = (messageId != null) ? messageId : row.getUserData();
            if (msgId == null) return;
            final String current = labelRef.getText();

            TextInputDialog dialog = new TextInputDialog(current);
            dialog.setTitle("Edit message");
            dialog.setHeaderText(null);
            dialog.setContentText("New message:");
            dialog.getDialogPane().getStyleClass().add("msg-edit-dialog");

            dialog.showAndWait().ifPresent(newText -> {
                String trimmed = (newText == null) ? "" : newText.trim();
                if (trimmed.isEmpty() || trimmed.equals(current)) return;

                labelRef.setText(trimmed);
                try {
                    long id = Long.parseLong(String.valueOf(msgId));
                    if (controller.getConnection() != null && controller.getConnection().isAlive()) {
                        System.out.println("[MENU] EDIT send id=" + id);
                        controller.getConnection().editMessage(id, trimmed);
                    } else {
                        System.out.println("[MENU] EDIT skipped, no connection");
                    }
                } catch (Exception ex) {
                    System.out.println("[MENU] EDIT failed ex=" + ex);
                }
            });
        });

        miDelete.setOnAction(e -> {
            Object ud = (messageId != null) ? messageId : row.getUserData();
            String midStr = (ud == null) ? null : String.valueOf(ud);

            boolean numeric = false;
            if (midStr != null) {
                try { Long.parseLong(midStr); numeric = true; } catch (Exception ignore) {}
            }
            if (!numeric) {
                Object fidObj = row.getProperties().get("fid");
                String fid = (fidObj == null) ? null : String.valueOf(fidObj);
                if (fid != null) {
                    String mapped = controller.getFileIdToMsgId().get(fid);
                    if (mapped != null && !mapped.isBlank()) {
                        try { Long.parseLong(mapped); numeric = true; midStr = mapped; } catch (Exception ignore) {}
                    }
                }
                if (numeric) {
                    row.setUserData(midStr);
                }
            }

            if (!numeric) {
                System.out.println("[MENU] DELETE blocked: missing numeric messageId (waiting for FILE_META to map fid->mid)");
                controller.showErrorAlert("The message ID has not synced yet. Please try again in a moment.");
                return;
            }

            try {
                long id = Long.parseLong(midStr);
                System.out.println("[MENU] DELETE send id=" + id);
                if (controller.getConnection() != null && controller.getConnection().isAlive()) {
                    controller.getConnection().deleteMessage(id);
                }
                controller.getMessageContainer().getChildren().remove(row);
            } catch (Exception ex) {
                System.out.println("[MENU] DELETE failed ex=" + ex);
            }
        });

        miDownload.setOnAction(e -> {
            System.out.println("[DLSAVE] Download clicked");
            try {
                Object fidObj = row.getProperties().get("fid");
                String fid = (fidObj == null) ? null : String.valueOf(fidObj);

                String midStr = null;
                Object ud = row.getUserData();
                if (ud != null && isNumeric(String.valueOf(ud))) {
                    midStr = String.valueOf(ud);
                } else if (fid != null) {
                    String mapped = controller.getFileIdToMsgId().get(fid);
                    if (isNumeric(mapped)) midStr = mapped;
                }

                String suggestedName = null;
                if (fid != null) suggestedName = controller.getFileIdToName().get(fid);
                if (suggestedName == null || suggestedName.isBlank()) suggestedName = "download";

                FileChooser fc = new FileChooser();
                String suggested = suggestedName;
                if (!suggested.contains(".")) {
                    String ext = UtilHandler.guessExt(
                            (fid != null ? controller.getFileIdToMime().get(fid) : null),
                            suggestedName
                    );
                    if (ext != null && !ext.isBlank()) suggested += ext;
                }
                fc.setInitialFileName(suggested);
                Window owner = (controller.getMessageContainer()!=null && controller.getMessageContainer().getScene()!=null)
                        ? controller.getMessageContainer().getScene().getWindow() : null;
                File dest = fc.showSaveDialog(owner);
                if (dest == null) {
                    System.out.println("[DLSAVE] user canceled save dialog");
                    return;
                }
                System.out.println("[DLSAVE] user choose dest=" + dest.getAbsolutePath());

                if (fid == null) {
                    fid = (midStr != null) ? ("MID_" + midStr) : null;
                    if (fid != null) row.getProperties().put("fid", fid);
                }
                if (fid == null) {
                    controller.showErrorAlert("Could not identify the file to download.");
                    return;
                }

                BufferedOutputStream existedBos = controller.getDlOut().get(fid);
                File existedPath = controller.getDlPath().get(fid);

                if (existedBos != null && existedPath != null) {
                    row.getProperties().put("moveToChosen", dest);
                    row.getProperties().put("saveToChosen", Boolean.TRUE);
                    System.out.println("[DLSAVE] mark move after done. temp=" + existedPath.getAbsolutePath()
                            + " -> chosen=" + dest.getAbsolutePath());
                } else {
                    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dest));
                    controller.getDlOut().put(fid, bos);
                    controller.getDlPath().put(fid, dest);
                    row.getProperties().put("saveToChosen", Boolean.TRUE);
                }

                if (controller.getConnection()!=null && controller.getConnection().isAlive()) {
                    if (midStr != null && isNumeric(midStr)) {
                        System.out.println("[DLSAVE] request download by messageId=" + midStr);
                        controller.getConnection().downloadFileByMsgId(Long.parseLong(midStr));
                    } else if (isNumeric(fid)) {
                        System.out.println("[DLSAVE] request download by fileId=" + fid);
                        controller.getConnection().downloadFileByFileId(Long.parseLong(fid));
                    } else {
                        System.out.println("[DLSAVE] request download legacy by fid=" + fid);
                        controller.getConnection().downloadFileLegacy(fid);
                    }
                } else {
                    controller.showErrorAlert("No connection to the server.");
                }
            } catch (Exception ex) {
                System.out.println("[DLSAVE] failed ex=" + ex);
                controller.showErrorAlert("Download failed: " + ex.getMessage());
            }
        });
        miReply.setOnAction(e -> {
            controller.showReplyPreview(row, incoming);
        });
        cm.setOnShowing(e -> {
        	Region b = findBubbleRegion(row);
            String bid = (b != null) ? b.getId() : null;
            boolean isOutgoing = bid != null && bid.startsWith("outgoing-");
            System.out.println("[MENU] onShowing bubbleId=" + bid + " isOutgoing=" + isOutgoing);

            Object ud = row.getUserData();
            boolean hasNumeric = false;
            if (ud != null) {
                try { Long.parseLong(String.valueOf(ud)); hasNumeric = true; } catch (Exception ignore2) {}
            }
            System.out.println("[MENU] onShowing userData=" + ud + " hasNumeric=" + hasNumeric);

            if (!hasNumeric) {
                Object fid = row.getProperties().get("fid");
                System.out.println("[MENU] onShowing tryResolve from fid=" + fid);
                if (fid != null) {
                    String mid = controller.getFileIdToMsgId().get(String.valueOf(fid));
                    System.out.println("[MENU] onShowing map(fid->mid)=" + mid);
                    if (mid != null && !mid.isBlank()) {
                        try {
                            Long.parseLong(mid);
                            row.setUserData(mid);
                            hasNumeric = true;
                            System.out.println("[MENU] onShowing resolved mid set userData=" + mid);
                        } catch (Exception ignore3) {
                            System.out.println("[MENU] onShowing mid not numeric=" + mid);
                        }
                    }
                }
            }

            boolean hasFid = (row.getProperties().get("fid") != null);
            boolean enableDelete = isOutgoing && (hasNumeric || hasFid);
            miDelete.setDisable(!enableDelete);
            System.out.println("[MENU] onShowing enableDelete=" + enableDelete);

            boolean isFileLike = false;
            if (bid != null) {
                isFileLike = bid.endsWith("-file") || bid.endsWith("-image")
                        || bid.endsWith("-video") || bid.endsWith("-voice");
            }
            miDownload.setDisable(!isFileLike);

            Object fidObj2 = row.getProperties().get("fid");
            System.out.println("[DLSAVE] onShowing bubbleId=" + bid
                    + " isFileLike=" + isFileLike
                    + " fid=" + (fidObj2==null?null:String.valueOf(fidObj2)));

            menuBtn.setOpacity(1.0);
        });

        menuBtn.setOnAction(e -> {
            System.out.println("[MENU] open clicked");
            cm.show(menuBtn, Side.BOTTOM, 0, 0);
        });
        cm.setOnHiding(e -> {
            System.out.println("[MENU] menu hiding");
            fadeOut.playFromStart();
        });

        row.setOnMouseEntered(e -> {
            fadeIn.playFromStart();
        });
        row.setOnMouseExited(e -> {
            if (!cm.isShowing()) fadeOut.playFromStart();
        });

        if (incoming) spacerBox.getChildren().addAll(holder, filler);
        else spacerBox.getChildren().addAll(filler, holder);

        int idx = row.getChildren().indexOf(spacer);
        if (idx >= 0) row.getChildren().set(idx, spacerBox);

        if (messageId != null) {
            row.setUserData(messageId);
            System.out.println("[MENU] preset userData from param=" + messageId);
        }
    }
    /*REPLY*/
    private VBox buildReplyChipForRowToRow(HBox srcRow, HBox newMsgRow, boolean newMsgIncoming) {
        Region srcBubble = findBubbleRegion(srcRow);
        String srcId = (srcBubble instanceof Region r) ? r.getId() : null;

        String title = buildReplyTitleForRows(srcRow, newMsgRow);

        String snippet = "Message";
        if (srcId != null && srcId.endsWith("-text")) {
            if (srcBubble instanceof VBox vb) {
                for (Node n : vb.getChildren()) if (n instanceof Label l) { snippet = l.getText(); break; }
            }
        } else if (srcId != null && srcId.endsWith("-image"))  {
            snippet = "Photo";
        } else if (srcId != null && srcId.endsWith("-video")) {
            snippet = "Video";
        } else if (srcId != null && srcId.endsWith("-voice")) {
            snippet = "Voice message";
        } else if (srcId != null && srcId.endsWith("-file")) {
            snippet = buildFileReplySnippet(srcBubble, srcRow);
        }
        else {
            snippet = "Message";
        }

        if (snippet != null && snippet.length() > 140) snippet = snippet.substring(0,140) + "…";

        VBox chip = new VBox(2);
        chip.getStyleClass().add("reply-chip");
        Label lbTitle = new Label("↪ " + title); lbTitle.getStyleClass().add("reply-chip-title");
        Label lbText  = new Label(snippet == null ? "" : snippet);
        lbText.setWrapText(true); lbText.getStyleClass().add("reply-chip-text");
        chip.getChildren().addAll(lbTitle, lbText);

        // Click -> scroll to source message
        chip.setOnMouseClicked(ev -> controller.scrollToRow(srcRow));

        return chip;
    }

    public void attachReplyChipById(HBox newMsgRow, boolean newMsgIncoming, String replyToId) {
        if (replyToId == null || replyToId.isBlank() || newMsgRow == null) return;

        HBox srcRow = controller.findRowByUserData(replyToId);
        if (srcRow == null) {
            controller.getPendingReplyLinks()
                    .computeIfAbsent(replyToId, k -> new java.util.ArrayList<>())
                    .add(newMsgRow);
            return;
        }

        VBox chip = buildReplyChipForRowToRow(srcRow, newMsgRow, newMsgIncoming);
        attachReplyChipToRow(newMsgRow, chip);

        newMsgRow.getProperties().putIfAbsent("replyTo", replyToId);
    }

    
    // ===== Reply title helpers =====
    private String getRowSender(HBox row) {
        if (row == null) return null;
        Object s = row.getProperties().get("sender");
        return (s == null) ? null : String.valueOf(s);
    }

    /** Generate unified snippet for file bubble (based on mime / filename) */
    private String buildFileReplySnippet(Region srcBubble, HBox srcRow) {
        String display = "Attachment";

        // Priority: lookup by fid if available (mapped in MidController)
        Object fidObj = srcRow.getProperties().get("fid");
        String fid = (fidObj != null) ? String.valueOf(fidObj) : null;

        String fileName = null;
        String mime     = null;

        if (fid != null) {
            fileName = controller.getFileIdToName().get(fid);
            mime     = controller.getFileIdToMime().get(fid);
        }

        // Fallback: retrieve from label #fileNamePrimary in bubble
        if ((fileName == null || fileName.isBlank()) && srcBubble instanceof VBox vb) {
            for (Node c : vb.lookupAll("#fileNamePrimary")) {
                if (c instanceof Label l) {
                    fileName = l.getText();
                    break;
                }
            }
        }

        MediaKind kind = MediaKind.FILE;
        if (mime != null || fileName != null) {
            kind = UtilHandler.classifyMedia(mime, fileName);
        }

        switch (kind) {
            case IMAGE -> display = "Photo";
            case VIDEO -> display = "Video";
            case AUDIO -> display = "Voice message";
            case FILE -> {
                if (fileName != null && !fileName.isBlank()) {
                    display = fileName;         // pdf, zip, docx... keep file name
                } else {
                    display = "Attachment";
                }
            }
        }

        if (display != null && display.length() > 140) {
            display = display.substring(0, 140) + "…";
        }
        return display;
    }

    // Find VBox bubble (or wrapper) to attach chip for all bubble types
    private VBox findBubbleBox(HBox row) {
        if (row == null || row.getChildren().isEmpty()) return null;

        Node bubble = (row.getAlignment()==Pos.CENTER_LEFT)
                ? row.getChildren().get(0)
                : row.getChildren().get(row.getChildren().size()-1);

        // If already a VBox, verify id
        if (bubble instanceof VBox box) {
            String id = box.getId();
            if (id == null) return null;
            if (id.endsWith("-text") || id.endsWith("-file") ||
                id.endsWith("-image") || id.endsWith("-video") ||
                id.endsWith("-voice")) {
                return box;
            }
            // Wrapper without id is still usable
            return box;
        }

        // For voice or video initially formatted as HBox -> no wrapper yet
        if (bubble instanceof HBox hb) {
            // Create wrapper to hold chip + original HBox
            VBox wrapper = new VBox(6);
            // Preserve id so CSS styles remain consistent
            if (hb.getId() != null) { 
                wrapper.setId(hb.getId()); 
                hb.setId(null);
            }
            int idx = (row.getAlignment()==Pos.CENTER_LEFT) ? 0 : row.getChildren().size()-1;
            wrapper.getChildren().add(hb);
            row.getChildren().set(idx, wrapper);
            return wrapper;
        }

        return null;
    }

    // Verify if chip already exists to avoid duplicates
    private boolean hasReplyChip(VBox bubbleBox) {
        if (bubbleBox == null) return false;
        for (Node n : bubbleBox.getChildren()) {
            if (n instanceof VBox vb && vb.getStyleClass().contains("reply-chip")) return true;
        }
        return false;
    }

    private String peerName() {
        return (controller.getSelectedUser() != null && controller.getSelectedUser().getUsername() != null)
                ? controller.getSelectedUser().getUsername()
                : "the other person";
    }

    /** 
     * Generate title from viewer perspective:
     * - actorIsViewer: whether replier is viewer
     * - sourceIsViewer: whether replied message belongs to viewer
     */
    private String buildReplyTitle(boolean actorIsViewer, boolean sourceIsViewer) {
        String actorPhrase  = actorIsViewer  ? "You" : peerName();
        String sourcePhrase;
        if (actorIsViewer == sourceIsViewer) {
            // Same side -> replying to self
            sourcePhrase = actorIsViewer ? "your own message" : "their own message";
        } else if (sourceIsViewer) {
            sourcePhrase = "your message";
        } else {
            sourcePhrase = peerName() + "'s message";
        }
        return actorPhrase + " replied to " + sourcePhrase;
    }

    /** Determine viewer perspective based on row alignments */
    private String buildReplyTitleForRows(HBox srcRow, HBox newMsgRow) {
        String viewer = (controller.getCurrentUser() != null)
                ? controller.getCurrentUser().getUsername()
                : null;

        String actorName  = getRowSender(newMsgRow);
        String sourceName = getRowSender(srcRow);

        boolean actorIsViewer  = (viewer != null && viewer.equals(actorName));
        boolean sourceIsViewer = (viewer != null && viewer.equals(sourceName));

        String actorPhrase = actorIsViewer
                ? "You"
                : (actorName != null && !actorName.isBlank() ? actorName : "User");

        String sourcePhrase;
        if (actorIsViewer && sourceIsViewer) {
            sourcePhrase = actorIsViewer ? "your own message" : "their own message";
        } else if (sourceIsViewer) {
            sourcePhrase = "your message";
        } else if (sourceName != null && !sourceName.isBlank()) {
            sourcePhrase = sourceName + "'s message";
        } else {
            sourcePhrase = "someone else's message";
        }

        return actorPhrase + " replied to " + sourcePhrase;
    }

    public void reattachReplyChipIfAny(HBox row) {
        if (row == null) return;
        Object rt = row.getProperties().get("replyTo");
        if (rt == null) return;
        if (hasReplyChipOnRow(row)) return;
        attachReplyChipById(row, row.getAlignment()==Pos.CENTER_LEFT, String.valueOf(rt));
    }

    /**/
    private void scrollToBottom() {
        var n = controller.getMessageContainer();
        var p = n.getParent();
        while (p != null && !(p instanceof ScrollPane)) p = p.getParent();
        if (p instanceof ScrollPane sp) {
            Platform.runLater(() -> {
                sp.layout();
                sp.setVvalue(1.0);
            });
        }
    }
    
    private static boolean isNumeric(String s){
        if (s == null) return false;
        try { Long.parseLong(s); return true; } catch (Exception ignore){ return false; }
    }

    private HBox addRowWithBubble(Node bubble, boolean incoming, String messageId, String sender, long createdAt, long updatedAt) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }

        // 1. Parent VBox (Wrapper) - Align message container left/right
        VBox messageVBoxWrapper = new VBox();
        messageVBoxWrapper.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        messageVBoxWrapper.getStyleClass().add("message-vbox-wrapper");

        // 2. Content HBox (Avatar - Message Content)
        HBox contentHBox = new HBox(6); // Spacing between Avatar and content
        contentHBox.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        contentHBox.getStyleClass().add("message-content-hbox");

        // 3. Message Content VBox (Header + original Bubble)
        VBox contentVBox = new VBox(2); // Spacing between Header and Bubble
        contentVBox.getStyleClass().add("message-content-vbox");

        // 4. Header (Sender, Time, Edited)
        HBox header = buildMessageHeader(sender, createdAt, updatedAt, incoming);

        // 5. Attach Header and Bubble to Content VBox
        contentVBox.getChildren().addAll(header, bubble);

        // 6. Avatar
        ImageView avatarView = getAvatarView(sender);
        
        // 6. Arrange Avatar and Content
        if (incoming) {
            contentHBox.getChildren().addAll(avatarView, contentVBox);
        } else {
            contentHBox.getChildren().addAll(contentVBox, avatarView);
        }
        
        // Add ContentHBox to Parent VBox
        messageVBoxWrapper.getChildren().add(contentHBox);

        // Spacer to align Parent VBox left/right
        HBox row = new HBox();
        row.getStyleClass().add("message-row-container"); // Main container HBox
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT); // Align left/right
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(messageVBoxWrapper, spacer);
        else          row.getChildren().addAll(spacer, messageVBoxWrapper);

        // Attach Side Menu
        attachSideMenu(row, spacer, incoming, messageId);

        controller.getMessageContainer().getChildren().add(row);
        if (sender != null && !sender.isBlank()) {
            row.getProperties().put("sender", sender);
        }
        scrollToBottom();
        // Reply link logic
        Object ud = row.getUserData();
        if (ud != null) {
            String id = String.valueOf(ud);
            var waiters = controller.getPendingReplyLinks().remove(id);
            if (waiters != null) {
                for (HBox waiter : waiters) {
                    boolean incomingWaiter = (waiter.getAlignment() == Pos.CENTER_LEFT);
                    attachReplyChipById(waiter, incomingWaiter, id);
                }
            }
        }
        return row;
    }
    
    private HBox buildMessageHeader(String sender, long createdAt, long updatedAt, boolean incoming) {
        // 1. Data
        String senderDisplay = incoming ? sender : "You";
        String timeDisplay = UtilHandler.formatTime(createdAt); // Formatted timestamp (HH:mm)
        boolean isEdited = updatedAt > 0;
        String editedDisplay = isEdited ? " · Edited" : "";

        // 2. Create UI
        HBox header = new HBox(8); // Label spacing
        header.getStyleClass().add("message-header");
        header.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);

        Label senderLbl = new Label(senderDisplay);
        senderLbl.getStyleClass().add("header-sender");

        Label timeLbl = new Label(timeDisplay);
        timeLbl.getStyleClass().add("header-time");

        Label editedLbl = new Label(editedDisplay);
        editedLbl.getStyleClass().add("header-edited");
        editedLbl.managedProperty().bind(editedLbl.visibleProperty());
        editedLbl.setVisible(isEdited);

        // 3. Ordering (Outgoing reverse: Edited -> Time -> Sender)
        if (incoming) {
            header.getChildren().addAll(senderLbl, timeLbl, editedLbl);
        } else {
            // Right alignment spacer
            Region spacer = new Region();
            // Order labels sequentially with HBox alignment
            // Right-align labels sequentially using HBox alignment
            // Align labels properly using Alignment properties
            header.getChildren().addAll(editedLbl, timeLbl, senderLbl);
        }
        return header;
    }

    // Avatar helper
    private ImageView getAvatarView(String sender) {
        Image avatar = controller.loadAvatarImageForUser(sender); // Lookup avatar by username
        ImageView iv = new ImageView(avatar);
        iv.setFitWidth(28);
        iv.setFitHeight(28);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);

        // Circular clip
        Circle clip = new Circle();
        clip.radiusProperty().bind(iv.fitWidthProperty().divide(2));
        clip.centerXProperty().bind(iv.fitWidthProperty().divide(2));
        clip.centerYProperty().bind(iv.fitHeightProperty().divide(2));
        iv.setClip(clip);

        iv.getStyleClass().add("message-avatar");
        return iv;
    }

    public HBox addTextMessage(String text, boolean incoming, String messageId, String sender, long createdAt, long updatedAt) {
        VBox bubble = new VBox();
        bubble.setMaxWidth(420);
        bubble.setId(incoming ? "incoming-text" : "outgoing-text");

        Label lbl = new Label(text);
        lbl.setWrapText(true);
        bubble.getChildren().add(lbl);
        
        // Pass all required parameters
        HBox row = addRowWithBubble(bubble, incoming, messageId, sender, createdAt, updatedAt);
        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null) {
                attachReplyChipToRow(row, chip);
            }
            controller.clearReplyPreview();
        }
        return row;
    }

    // Corresponding setup for addImageMessage:
    public HBox addImageMessage(Image img, String caption, boolean incoming, String messageId, String sender, long createdAt, long updatedAt) {
        VBox box = new VBox(4);
        box.setId(incoming ? "incoming-image" : "outgoing-image");

        ImageView iv = new ImageView(img);
        iv.setFitWidth(260);
        iv.setPreserveRatio(true);

        box.getChildren().add(iv);
        // Pass all required parameters
        HBox row = addRowWithBubble(box, incoming, messageId, sender, createdAt, updatedAt);
        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null) {
                attachReplyChipToRow(row, chip);
            }
            controller.clearReplyPreview();
        }
        return row;
    }
    
    /* FILE: name + size only */
    public HBox addFileMessage(String filename, String meta, boolean incoming, String messageId, String sender, long createdAt, long updatedAt) {
        // Create bubble
        VBox box = new VBox();
        box.setId(incoming ? "incoming-file" : "outgoing-file");
        box.setPadding(new Insets(8, 12, 8, 12));

        HBox content = new HBox(10);
        content.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size:20px;");

        Label nameLbl = new Label(filename == null ? "" : filename);
        nameLbl.setId("fileNamePrimary");
        nameLbl.getStyleClass().add("file-name");

        // Calculate size only when messageId is a temporary UUID fid
        String sizeOnly = normalizeSizeOnly(meta);
        boolean messageIdIsNumeric = false;
        try { if (messageId != null) Long.parseLong(messageId); messageIdIsNumeric = true; } catch (Exception ignore) {}

        if ((sizeOnly == null || sizeOnly.isBlank()) && messageId != null && !messageIdIsNumeric) {
            // dlPath keyed by UUID fid during upload
            var f = controller.getDlPath().get(messageId);
            if (f != null && f.exists()) sizeOnly = UtilHandler.humanBytes(f.length());
        }

        Label metaLbl = new Label(sizeOnly == null ? "" : sizeOnly);
        metaLbl.setId("fileMeta");
        metaLbl.getStyleClass().add("meta");

        VBox info = new VBox(2);
        info.setId("fileInfoBox");
        info.getChildren().addAll(nameLbl, metaLbl);

        Region innerSpacer = new Region();
        HBox.setHgrow(innerSpacer, Priority.ALWAYS);

        content.getChildren().addAll(icon, info, innerSpacer);
        box.getChildren().add(content);

        // Add bubble to row and attach side menu
        HBox row = addRowWithBubble(box, incoming, messageId, sender, createdAt, updatedAt);

        // Bind userData/fid for resolution
        if (messageId != null) {
            row.setUserData(messageId);
            boolean numeric = false;
            try { Long.parseLong(messageId); numeric = true; } catch (Exception ignore) {}
            if (!numeric) {
                // Initial messageId is UUID fid
                row.getProperties().put("fid", messageId);
                if (!incoming) controller.getOutgoingFileBubbles().put(messageId, row);
            }
        }

        // Update meta by fid when size is unpopulated
        if ((sizeOnly == null || sizeOnly.isBlank())
                && messageId != null
                && !messageIdIsNumeric
                && controller.getMediaHandler() != null) {
            Platform.runLater(() -> controller.getMediaHandler().updateGenericFileMetaByFid(messageId));
        }
        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null) {
                attachReplyChipToRow(row, chip);
            }
            controller.clearReplyPreview();
        }
        return row;
    }

    public HBox addVoiceMessage(String duration, boolean incoming, String messageId, String sender, long createdAt, long updatedAt) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }

        // 1. Bubble voice
        HBox voiceBox = new HBox(10);
        voiceBox.setId(incoming ? "incoming-voice" : "outgoing-voice");
        voiceBox.setAlignment(Pos.CENTER_LEFT);

        Button playBtn = new Button("▶");
        playBtn.getStyleClass().add("audio-btn");
        playBtn.setId("voicePlay");

        Slider slider = new Slider();
        slider.setPrefWidth(200);
        slider.setId("voiceSlider");

        Label dur = new Label(duration == null || duration.isBlank() ? "--:--" : duration);
        dur.setId("voiceDuration");

        voiceBox.getChildren().addAll(playBtn, slider, dur);

        // 2. Wrap with addRowWithBubble to attach avatar + header
        HBox row = addRowWithBubble(voiceBox, incoming, messageId, sender, createdAt, updatedAt);

        // 3. Attach metadata (messageId may be numeric mid or UUID fid)
        if (messageId != null) {
            row.setUserData(messageId);
            boolean numeric = false;
            try { Long.parseLong(messageId); numeric = true; } catch (Exception ignore) {}

            if (!numeric) { // temporary UUID fid
                row.getProperties().put("fid", messageId);
                if (!incoming) {
                    controller.getOutgoingFileBubbles().put(messageId, row);
                }
            }
        }

        // 4. Reply chip (if reply state active)
        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null) {
                attachReplyChipToRow(row, chip);
            }
            controller.clearReplyPreview();
        }

        return row;
    }

    /* VIDEO: media area + Play button + Slider, no label */
    /* VIDEO: media area + Play button + Slider, no label in bubble (info in header) */
    public HBox addVideoMessage(String filename, String meta, boolean incoming, String messageId, String sender, long createdAt, long updatedAt) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }

        VBox box = new VBox(6);
        box.setId(incoming ? "incoming-video" : "outgoing-video");
        box.setAlignment(Pos.CENTER_LEFT);

        Region videoArea = new Region();
        videoArea.setPrefSize(320, 180);
        videoArea.setStyle("-fx-background-color: #111111; -fx-background-radius: 8;");
        videoArea.setId("videoArea");

        VBox controls = new VBox(4);
        controls.setId("videoControls");

        Button playBtn = new Button("▶");
        playBtn.setId("videoPlay");

        Slider slider = new Slider();
        slider.setPrefWidth(220);
        slider.setId("videoSlider");

        controls.getChildren().addAll(playBtn, slider);
        box.getChildren().addAll(videoArea, controls);

        // Wrap with addRowWithBubble to attach avatar + header
        HBox row = addRowWithBubble(box, incoming, messageId, sender, createdAt, updatedAt);

        // Attach metadata (messageId may be numeric mid or UUID fid)
        if (messageId != null) {
            row.setUserData(messageId);
            boolean numeric = false;
            try { Long.parseLong(messageId); numeric = true; } catch (Exception ignore) {}

            if (!numeric) { // temporary UUID fid
                row.getProperties().put("fid", messageId);
                if (!incoming) {
                    controller.getOutgoingFileBubbles().put(messageId, row);
                }
            }
        }

        // Attach reply chip if replying
        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null) {
                attachReplyChipToRow(row, chip);
            }
            controller.clearReplyPreview();
        }

        return row;
    }

    public void applyStatusLabel(Label lbl, boolean online, String lastSeenIso) {
        if (lbl == null) return;
        lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
        if (online) {
            lbl.setText("Online");
            lbl.getStyleClass().add("chat-status-online");
        } else {
            lbl.setText("Offline" + controller.humanize(lastSeenIso, true));
            lbl.getStyleClass().add("chat-status-offline");
        }
    }

    private Label findTextLabelInRow(HBox row) {
        Region bubble = findBubbleRegion(row);
        if (!(bubble instanceof VBox vb)) return null;

        for (Node n : vb.getChildren()) {
            // Skip reply-chip if already contained in bubble
            if (n instanceof VBox sub && sub.getStyleClass().contains("reply-chip")) continue;
            if (n instanceof Label lbl) return lbl;
        }
        return null;
    }

    private VBox buildReplyChipForCurrentContext(HBox newMsgRow, boolean newMsgIncoming) {
        HBox srcRow = controller.getReplyingRow();
        if (srcRow == null) return null;
        
        boolean srcIncoming = controller.isReplyingIncoming();

        Region srcBubble = findBubbleRegion(srcRow);
        String srcId = (srcBubble instanceof Region r) ? r.getId() : null;

        String title = buildReplyTitleForRows(srcRow, newMsgRow);

        String snippet;
        if (srcId != null && srcId.endsWith("-text")) {
            snippet = "Message";
            if (srcBubble instanceof VBox vb) {
                for (Node n : vb.getChildren()) if (n instanceof Label l) { snippet = l.getText(); break; }
            }
        } else if (srcId != null && srcId.endsWith("-image")) {
            snippet = "Photo";
        } else if (srcId != null && srcId.endsWith("-file")) {
            snippet = buildFileReplySnippet(srcBubble, srcRow);
        } else if (srcId != null && srcId.endsWith("-video")) {
            snippet = "Video";
        } else if (srcId != null && srcId.endsWith("-voice")) {
            snippet = "Voice message";
        } else {
            snippet = "Message";
        }

        if (snippet != null && snippet.length() > 140) snippet = snippet.substring(0, 140) + "…";

        VBox chip = new VBox(2);
        chip.getStyleClass().add("reply-chip");

        Label lbTitle = new Label("↪ " + title);
        lbTitle.getStyleClass().add("reply-chip-title");

        Label lbText = new Label(snippet == null ? "" : snippet);
        lbText.setWrapText(true);
        lbText.getStyleClass().add("reply-chip-text");

        chip.getChildren().addAll(lbTitle, lbText);

        chip.setOnMouseClicked(ev -> controller.scrollToRow(srcRow));

        Object srcMsgId = srcRow.getUserData();
        if (srcMsgId != null) newMsgRow.getProperties().put("replyTo", String.valueOf(srcMsgId));

        return chip;
    }

}
