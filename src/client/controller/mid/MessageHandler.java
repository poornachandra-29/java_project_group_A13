package client.controller.mid;

import common.Frame;
import javafx.application.Platform;
import javafx.scene.layout.HBox;

import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import client.controller.MidController;
import javafx.scene.Node;
import javafx.geometry.Pos;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandler {
    private final MidController controller;

    // === STATE: prevent duplicate CallLog render & duplicate file download ===
    private final Set<String> requestedDownloads =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final String REPLY_TAG = "[REPLY:";
    public boolean markDownloadRequested(String key) {
        return requestedDownloads.add(key);
    }

    public MessageHandler(MidController controller) {
        this.controller = controller;
    }
    
    // ===================== CONVERSATION KEY HELPERS =====================

    /**
     * Key representing the currently active conversation.
     * - DM: peer username
     * - Group: "group:<groupId>"
     */
    private String getCurrentConversationKey() {
        String peer = controller.getCurrentPeer();
        if (peer != null && !peer.isBlank()) {
            return peer; // e.g. "group:11" or peer username
        }
        if (controller.getSelectedUser() != null) {
            return controller.getSelectedUser().getUsername();
        }
        return null;
    }

    // ===================== MAIN DISPATCH =====================

    public void handleServerFrame(Frame f) {
        if (f == null) return;
        String convKey = getCurrentConversationKey();

        switch (f.type) {
            case DM -> handleDmFrame(f, convKey);
            case HISTORY -> handleHistoryFrame(f, convKey);
            case FILE_EVT, AUDIO_EVT -> handleFileEvtFrame(f, convKey);
            case FILE_META -> handleFileMetaFrame(f);
            case FILE_CHUNK -> handleFileChunkFrame(f);
            case DELETE_MSG -> handleDeleteMsgFrame(f);
            case EDIT_MSG -> handleEditMsgFrame(f);
            case ACK -> handleAckFrame(f);
            case FILE_HISTORY -> handleFileHistoryFrame(f);
            case ERROR -> Platform.runLater(() -> controller.showErrorAlert("Error: " + f.body));
            case SMART_REPLY -> handleSmartReplyFrame(f);
            case GROUP_MSG -> handleGroupMsgFrame(f);
            case GROUP_HISTORY -> handleGroupHistoryFrame(f);
        }

    }


    // ===================== PRIVATE HANDLERS =====================
    private static String[] parseReplyPrefix(String body) {
        // return [strippedBody, replyIdOrNull]
        if (body == null) return new String[]{"", null};
        if (body.startsWith(REPLY_TAG)) {
            int end = body.indexOf(']');
            if (end > REPLY_TAG.length()) {
                String num = body.substring(REPLY_TAG.length(), end);
                String rest = body.substring(end + 1);
                return new String[]{rest, num};
            }
        }
        return new String[]{body, null};
    }
    private void handleDmFrame(Frame f, String openPeer) {
        String sender = f.sender;
        String body = f.body == null ? "" : f.body;
        String[] pr = parseReplyPrefix(body);
        String clean = pr[0];
        String replyToId = pr[1];
        final long createdAt = System.currentTimeMillis();
        if (body.startsWith("[CALLLOG]")) {
            if (openPeer != null && openPeer.equals(sender)) {
                CallLogData d = parseCallLog(body);
                renderCallLogOnce(
                        d,
                        sender,                // dmSender
                        createdAt,
                        0L,
                        f.transferId           // msgId if set by server
                );
            }
            return;
        }


        if (openPeer != null && openPeer.equals(sender)) {
        	HBox row = controller.addTextMessage(
                    clean, 
                    true, // incoming
                    f.transferId, 
                    f.sender, 
                    System.currentTimeMillis(), // Received timestamp
                    0L // new message unedited
                );
        	if (replyToId != null) {
        	    row.getProperties().put("replyTo", replyToId);
        	    new UIMessageHandler(controller).attachReplyChipById(row, /*incoming=*/true, replyToId);
        	}
        }
    }

    private void handleHistoryFrame(Frame f, String openPeer) {
        String jsonBody = f.body == null ? "" : f.body.trim();

        // STEP 1: Determine content and timestamp
        String sender;
        String content;
        String recipient;
        long createdAt;
        long updatedAt;
        boolean isIncoming;
        
        // Try JSON parse (Server sends JSON with timestamp/sender)
        String jsonSender = UtilHandler.jsonGet(jsonBody, "sender");
        String jsonContent = UtilHandler.jsonGet(jsonBody, "content");
        String jsonRecipient = UtilHandler.jsonGet(jsonBody, "recipient");
        
        if (jsonSender != null && jsonContent != null) {
            // --- NEW JSON FORMAT ---
            sender = jsonSender;
            content = jsonContent;
            recipient = jsonRecipient;
            createdAt = UtilHandler.parseLongSafe(UtilHandler.jsonGet(jsonBody, "createdAt"), System.currentTimeMillis());
            updatedAt = UtilHandler.parseLongSafe(UtilHandler.jsonGet(jsonBody, "updatedAt"), 0L);
            
            // Determine incoming/outgoing based on JSON sender (Frame.recipient is current user)
            String myName = (controller.getCurrentUser() != null) ? controller.getCurrentUser().getUsername() : "";
            isIncoming = !sender.equals(myName);

        } else {
            // --- LEGACY FORMAT (FALLBACK) ---
            String line = jsonBody;
            createdAt = System.currentTimeMillis(); // Default if missing from payload
            updatedAt = 0L; // Default
            recipient = null; 

            if (line.startsWith("[HIST IN]")) {
                String payload = line.substring(9).trim();
                int p = payload.indexOf(": ");
                if (p > 0) {
                    sender = payload.substring(0, p);
                    content = payload.substring(p + 2);
                    isIncoming = true;
                } else { return; }
            } else if (line.startsWith("[HIST OUT]")) {
                content = line.substring(10).trim();
                sender = controller.getCurrentUser().getUsername(); // Outgoing message sender is self
                isIncoming = false;
            } else {
                return; // Unrecognized history format
            }
        }

        // STEP 2: Render only if conversation matches current active peer
        // DM: openPeer matches sender (incoming) or recipient (outgoing)
        if (openPeer != null) {
            boolean match = false;
            if (openPeer.equals(sender)) match = true;
            if (!match && recipient != null && openPeer.equals(recipient)) match = true;

            if (!match) return;
        }

        handleHistoryContent(content, f.transferId, isIncoming, sender, createdAt, updatedAt);
    }

	 private void handleHistoryContent(String body, Object transferId, boolean incoming, String sender, long createdAt, long updatedAt) {
	     String[] pr = parseReplyPrefix(body);
	     String clean = pr[0];
	     String replyToId = pr[1];
	     
	     if (body.startsWith("[CALLLOG]")) {
	         CallLogData d = parseCallLog(body);
	         // CallLog requires sender and createdAt
	         renderCallLogOnce(
	                 d,
	                 sender,                           // dmSender trong HISTORY
	                 createdAt,
	                 updatedAt,
	                 (transferId == null ? null : String.valueOf(transferId))
	         );
	         return;
	     }
	
	     long msgId = 0L;
	     try { msgId = Long.parseLong(String.valueOf(transferId)); } catch (Exception ignore) {}
	     String msgIdStr = (msgId > 0 ? String.valueOf(msgId) : null);
	     
	     String head = (clean == null) ? "" : clean;
	
	     if (head.startsWith("[FILE]")) {
	         String name = head.substring(6).trim();
	         String meta = "";
	         // Added sender, createdAt, updatedAt
	         HBox row = controller.addFileMessage(name, meta, incoming, msgIdStr, sender, createdAt, updatedAt);
	         if (replyToId != null && row.getProperties().get("replyTo") == null) {
	             row.getProperties().put("replyTo", replyToId);
	             new UIMessageHandler(controller).attachReplyChipById(row, incoming, replyToId);
	         }
	         if (msgIdStr != null) controller.getPendingHistoryFileRows().put(msgIdStr, row);
	         if (controller.getConnection()!=null && controller.getConnection().isAlive() && msgId>0) {
	             String key = String.valueOf(msgId);
	             if (markDownloadRequested(key)) try { controller.getConnection().downloadFileByMsgId(msgId); } catch (IOException ignore) {}
	         }
	
	     } else if (head.startsWith("[AUDIO]")) {
	         String dur = "--:--";
	         // Added sender, createdAt, updatedAt
	         HBox row = controller.addVoiceMessage(dur, incoming, msgIdStr, sender, createdAt, updatedAt);
	         if (replyToId != null && row.getProperties().get("replyTo") == null) {
	             row.getProperties().put("replyTo", replyToId);
	             new UIMessageHandler(controller).attachReplyChipById(row, incoming, replyToId);
	         }
	         if (msgIdStr != null) controller.getPendingHistoryFileRows().put(msgIdStr, row);
	         if (controller.getConnection()!=null && controller.getConnection().isAlive() && msgId>0) {
	             String key = String.valueOf(msgId);
	             if (markDownloadRequested(key)) try { controller.getConnection().downloadFileByMsgId(msgId); } catch (IOException ignore) {}
	         }
	     } else if (head.startsWith("[VIDEO]")) {
	         String name = head.substring(7).trim();
	         String meta = "";
	         // Added sender, createdAt, updatedAt
	         HBox row = controller.addVideoMessage(name, meta, incoming, msgIdStr, sender, createdAt, updatedAt);
	         if (replyToId != null && row.getProperties().get("replyTo") == null) {
	             row.getProperties().put("replyTo", replyToId);
	             new UIMessageHandler(controller).attachReplyChipById(row, incoming, replyToId);
	         }
	         if (msgIdStr != null) controller.getPendingHistoryFileRows().put(msgIdStr, row);
	         if (controller.getConnection()!=null && controller.getConnection().isAlive() && msgId>0) {
	             String key = String.valueOf(msgId);
	             if (markDownloadRequested(key)) try { controller.getConnection().downloadFileByMsgId(msgId); } catch (IOException ignore) {}
	         }
	     } else {
	         // Regular text message
	     	HBox row = controller.addTextMessage(clean, incoming, String.valueOf(transferId), sender, createdAt, updatedAt);
	         if (replyToId != null && row.getProperties().get("replyTo") == null) {
	             row.getProperties().put("replyTo", replyToId);
	             new UIMessageHandler(controller).attachReplyChipById(row, incoming, replyToId);
	         }
	     }
	 }

    private void handleFileEvtFrame(Frame f, String currentConvKey) {
        String json = (f.body == null) ? "" : f.body;

        String from = UtilHandler.jsonGet(json, "from");
        String to   = UtilHandler.jsonGet(json, "to");
        String name = UtilHandler.jsonGet(json, "name");
        String mime = UtilHandler.jsonGet(json, "mime");
        long bytes  = UtilHandler.parseLongSafe(UtilHandler.jsonGet(json, "bytes"), 0);
        int duration = UtilHandler.parseIntSafe(UtilHandler.jsonGet(json, "duration"), 0);
        String uuid   = UtilHandler.jsonGet(json, "uuid");
        String legacy = UtilHandler.jsonGet(json, "id");
        String dbIdStr = UtilHandler.jsonGet(json, "fileId");
        final String sender = (from != null) ? from : "";
        final long createdAt = System.currentTimeMillis();
        final long updatedAt = 0L;
        Long dbId = null;
        if (dbIdStr != null && !dbIdStr.isBlank()) {
            try { dbId = Long.parseLong(dbIdStr); } catch (Exception ignore) {}
        }

        // Map bubbleKey -> name/mime
        String bubbleKey = (legacy != null && !legacy.isBlank())
                ? legacy
                : (uuid != null && !uuid.isBlank() ? uuid : null);

        if (bubbleKey != null && name != null) {
            controller.getFileIdToName().put(bubbleKey, name);
        }
        if (bubbleKey != null && mime != null && !mime.isBlank()) {
            controller.getFileIdToMime().put(bubbleKey, mime);
        }

        if (dbId != null && dbId > 0) {
            String dbKey = String.valueOf(dbId);
            if (name != null) controller.getFileIdToName().put(dbKey, name);
            if (mime != null && !mime.isBlank()) controller.getFileIdToMime().put(dbKey, mime);
        }

        // ===== DETERMINE TARGET CONVERSATION KEY =====
        String me = (controller.getCurrentUser() != null)
                ? controller.getCurrentUser().getUsername()
                : null;

        String targetConvKey;

        if (to != null) {
            to = to.trim();

            // For group: server may send "to":"group:4" OR "to":"4"
            if (to.startsWith("group:")) {
                targetConvKey = to;
            } else if (to.matches("\\d+")) {
                targetConvKey = "group:" + to;
            }
            // Direct message to self: convKey is peer username
            else if (me != null && to.equals(me) && from != null && !from.isBlank()) {
                targetConvKey = from;
            } else {
                // Fallback: preserve backwards compatibility
                targetConvKey = from;
            }
        } else {
            // Missing "to" in JSON -> fallback to legacy handling
            targetConvKey = from;
        }

        if (targetConvKey == null || targetConvKey.isBlank()) {
            System.out.println("[FILE_EVT] Cannot resolve targetConvKey, json=" + json);
            return;
        }

        // ===== ACTIVE CONVERSATION MATCHES -> RENDER IMMEDIATELY =====
        if (currentConvKey != null && currentConvKey.equals(targetConvKey)) {
            String displayKey = (dbId != null && dbId > 0)
                    ? String.valueOf(dbId)
                    : (bubbleKey != null ? bubbleKey : "");

            UtilHandler.MediaKind kind = UtilHandler.classifyMedia(mime, name);
            String sizeOnly = (bytes > 0) ? UtilHandler.humanBytes(bytes) : "";
            boolean incoming = true; // server push

            HBox row;
            switch (kind) {
                case IMAGE -> {
                    Image img = new WritableImage(8, 8);
                    row = controller.addImageMessage(
                            img,
                            name + (sizeOnly.isBlank() ? "" : " • " + sizeOnly),
                            incoming, null, sender, createdAt, updatedAt
                    );
                }
                case AUDIO -> {
                    String dur = (duration > 0)
                            ? UtilHandler.formatDuration(duration)
                            : "--:--";
                    row = controller.addVoiceMessage(dur, incoming, null, sender, createdAt, updatedAt);
                }
                case VIDEO -> {
                    row = controller.addVideoMessage(name, sizeOnly, incoming, null, sender, createdAt, updatedAt);
                }
                default -> {
                    row = controller.addFileMessage(name, sizeOnly, incoming, null, sender, createdAt, updatedAt);
                }
            }

            if (!displayKey.isEmpty()) {
                row.getProperties().put("fid", displayKey);
            }

            String msgIdStr0 = UtilHandler.jsonGet(json, "messageId");
            if (msgIdStr0 != null && !msgIdStr0.isBlank()) {
                controller.getFileIdToMsgId().put(displayKey, msgIdStr0);
                try {
                    Long.parseLong(msgIdStr0);
                    row.setUserData(msgIdStr0);
                } catch (Exception ignore) {}
            }

            // Automatic download logic
            if (controller.getConnection() != null && controller.getConnection().isAlive()) {
                try {
                    Long msgId = null;
                    String msgIdS = UtilHandler.jsonGet(json, "messageId");
                    if (msgIdS != null && !msgIdS.isBlank()) {
                        try { msgId = Long.parseLong(msgIdS); } catch (Exception ignore) {}
                    }

                    if (dbId != null && dbId > 0) {
                        controller.getConnection().downloadFileByFileId(dbId);
                    } else if (msgId != null && msgId > 0) {
                        controller.getConnection().downloadFileByMsgId(msgId);
                    } else if (bubbleKey != null) {
                        controller.getConnection().downloadFileLegacy(bubbleKey);
                    }
                } catch (IOException e) {
                    System.err.println("[DL] request failed: " + e.getMessage());
                }
            }

        } else {
            // ===== CONVERSATION NOT ACTIVE -> SAVE AS PENDING UNDER KEY =====
            controller.getPendingFileEvents()
                    .computeIfAbsent(targetConvKey, k -> new ArrayList<>())
                    .add(f);
        }
    }

    private void handleFileMetaFrame(Frame f) {
        String body = (f.body == null) ? "" : f.body;
        String fid = UtilHandler.jsonGet(body, "fileId");
        String msgIdStr = UtilHandler.jsonGet(body, "messageId");
        String name = UtilHandler.jsonGet(body, "name");
        String mime = UtilHandler.jsonGet(body, "mime");
        String replyTo = UtilHandler.jsonGet(body, "replyTo");
        System.out.println("[CLIENT] FILE_META parsed replyTo=" + replyTo
                + " fid=" + fid + " msgIdStr=" + msgIdStr + " name=" + name + " mime=" + mime);
        long metaSize = UtilHandler.parseLongSafe(UtilHandler.jsonGet(body, "size"), 0);
        if (metaSize <= 0) metaSize = UtilHandler.parseLongSafe(UtilHandler.jsonGet(body, "bytes"), 0);
        final long sizeHint = metaSize;

        if (fid == null || fid.isBlank()) return;

        if (msgIdStr != null && !msgIdStr.isBlank()) {
            controller.getFileIdToMsgId().put(fid, msgIdStr);
            HBox rowByHist = controller.getPendingHistoryFileRows().remove(msgIdStr);
            if (rowByHist != null) {
            	rowByHist.getProperties().put("fid", fid);
            	if (replyTo != null && !replyTo.isBlank() && rowByHist.getProperties().get("replyTo") == null) {
                    rowByHist.getProperties().put("replyTo", replyTo);
                    new UIMessageHandler(controller).attachReplyChipById(
                        rowByHist, rowByHist.getAlignment()==Pos.CENTER_LEFT, replyTo);
                }
            }
            boolean tagged = false;
            try {
                Long.parseLong(msgIdStr);
                HBox row = controller.findRowByFid(fid);
                if (row != null) {
                    Object ud = row.getUserData();
                    boolean hasNumeric = false;
                    if (ud != null) {
                        try {
                            Long.parseLong(String.valueOf(ud));
                            hasNumeric = true;
                        } catch (Exception ignore) {}
                    }
                    if (!hasNumeric) {
                        row.setUserData(msgIdStr);
                        tagged = true;
                    }
                    if (replyTo != null && !replyTo.isBlank() && row.getProperties().get("replyTo") == null) {
                        row.getProperties().put("replyTo", replyTo);
                        new UIMessageHandler(controller).attachReplyChipById(
                            row, row.getAlignment()==Pos.CENTER_LEFT, replyTo);
                    }
                }
            } catch (Exception ignore) {}
            if (!tagged) {
                var mc = controller.getMessageContainer();
                if (mc != null && !mc.getChildren().isEmpty()) {
                    for (int i = mc.getChildren().size() - 1; i >= 0; i--) {
                        var n = mc.getChildren().get(i);
                        if (n instanceof HBox h) {
                            Object ud = h.getUserData();
                            boolean hasNumeric = false;
                            if (ud != null) {
                                try {
                                    Long.parseLong(String.valueOf(ud));
                                    hasNumeric = true;
                                } catch (Exception ignore) {}
                            }
                            if (hasNumeric) continue;
                            Node b = h.getChildren().isEmpty() ? null : h.getChildren().get(h.getChildren().size() - 1);
                            if (b instanceof VBox vb) {
                                String bid = vb.getId();
                                if (bid != null && bid.startsWith("outgoing-")) {
                                    h.setUserData(msgIdStr);
                                    h.getProperties().put("fid", fid);
                                    if (replyTo != null && !replyTo.isBlank() && h.getProperties().get("replyTo") == null) {
                                        h.getProperties().put("replyTo", replyTo);
                                        new UIMessageHandler(controller).attachReplyChipById(
                                            h, h.getAlignment()==Pos.CENTER_LEFT, replyTo);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        try {
            if (msgIdStr != null && !msgIdStr.isBlank()) {
                String midKey = "MID_" + msgIdStr;
                BufferedOutputStream bosOld = controller.getDlOut().remove(midKey);
                File destOld = controller.getDlPath().remove(midKey);
                if (bosOld != null && destOld != null) {
                    controller.getDlOut().put(fid, bosOld);
                    controller.getDlPath().put(fid, destOld);
                    System.out.println("[DLSAVE] REBIND stream from " + midKey + " -> " + fid + " dest=" + destOld.getAbsolutePath());
                    HBox rByMidKey = controller.findRowByFid(midKey);
                    if (rByMidKey != null) {
                        rByMidKey.getProperties().put("fid", fid);
                        System.out.println("[DLSAVE] REBIND row fid property from " + midKey + " -> " + fid);
                    }
                }
            }
        } catch (Exception rebindEx) {
            System.out.println("[DLSAVE] REBIND failed ex=" + rebindEx);
        }
        if (sizeHint > 0) controller.getFileIdToSize().put(fid, sizeHint);
        if (mime == null || mime.isBlank()) mime = "application/octet-stream";
        controller.getFileIdToMime().put(fid, mime);
        if (name != null && !name.isBlank()) controller.getFileIdToName().put(fid, name);
        try {
            BufferedOutputStream existed = controller.getDlOut().get(fid);
            File existedFile = controller.getDlPath().get(fid);
            if (existed != null && existedFile != null) {
                System.out.println("[DLSAVE] FILE_META reuse existing dest=" + existedFile.getAbsolutePath());
            } else {
                String ext = UtilHandler.guessExt(mime, controller.getFileIdToName().get(fid));
                File tmp = File.createTempFile("im_", "_" + fid + (ext == null ? "" : ext));
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tmp));
                controller.getDlPath().put(fid, tmp);
                controller.getDlOut().put(fid, bos);
                System.out.println("[DLSAVE] FILE_META create temp=" + tmp.getAbsolutePath());
            }
        } catch (Exception ex) {
            System.out.println("[DLSAVE] FILE_META prepare dest failed ex=" + ex);
        }
        UtilHandler.MediaKind kind = UtilHandler.classifyMedia(mime, controller.getFileIdToName().get(fid));
        switch (kind) {
            case FILE -> Platform.runLater(() -> {
                HBox row = controller.findRowByFid(fid);
                if (row != null && controller.getMediaHandler() != null) {
                    controller.getMediaHandler().updateGenericFileMeta(row, fid, sizeHint);
                }
            });
            case IMAGE -> refreshImageCaption(fid, sizeHint);
            case VIDEO -> refreshVideoLabels(fid, sizeHint);
            default -> {}
        }
    }


    private void handleFileChunkFrame(Frame f) {
        String fid = f.transferId;
        byte[] data = (f.bin == null) ? new byte[0] : f.bin;
        BufferedOutputStream bos = controller.getDlOut().get(fid);
        
        if (bos != null) {
            try {
                // Write chunk data to output stream
                if (data.length > 0) bos.write(data);
            } catch (IOException e) {
                System.err.println("[DL] write failed: " + e.getMessage());
                // Close stream and remove from map on error
                try { bos.close(); } catch (IOException ignore) {}
                controller.getDlOut().remove(fid);
                return; // Abort processing this chunk
            }
            
            // Check if this is the final chunk
            if (f.last) {
                try {
                    // Close stream
                    bos.flush();
                    bos.close();
                } catch (Exception ignore) {}
                
                // Remove stream from map
                controller.getDlOut().remove(fid);
                
                // Retrieve downloaded file
                File file = controller.getDlPath().remove(fid);
                
                if (file != null) {
                	controller.onDownloadCompleted(fid, file);
                    Platform.runLater(() -> {
                        // Find corresponding message in chat container
                        HBox row = controller.findRowByFid(fid);
                        if (row == null) return;
                        
                        String mime = controller.getFileIdToMime().getOrDefault(fid, "application/octet-stream");
                        UtilHandler.MediaKind kind = UtilHandler.classifyMedia(mime, controller.getFileIdToName().get(fid));
                        String fileUrl = file.toURI().toString();
                        
                        try {
                            // Update message UI based on media type
                        switch (kind) {
                            case AUDIO -> {
                                controller.getMediaHandler().updateVoiceBubbleFromUrl(row, fileUrl);
                                new UIMessageHandler(controller).reattachReplyChipIfAny(row);
                            }
                            case VIDEO -> {
                                controller.getMediaHandler().updateVideoBubbleFromUrl(row, fileUrl);
                                refreshVideoLabels(fid, file.length());
                                new UIMessageHandler(controller).reattachReplyChipIfAny(row);
                            }
                            case IMAGE -> {
                                controller.getMediaHandler().updateImageBubbleFromUrl(row, fileUrl);
                                refreshImageCaption(fid, file.length());
                                new UIMessageHandler(controller).reattachReplyChipIfAny(row);
                            }
                            default -> {
                                controller.getMediaHandler().updateGenericFileMetaByFid(fid);
                                new UIMessageHandler(controller).reattachReplyChipIfAny(row);
                            }
                        }
                        } catch (Exception ex) {
                            System.err.println("[UI] Failed to update bubble after download: " + ex.getMessage());
                        }
                        
                        // Check and open containing folder if needed
                        try {
                            boolean shouldOpen = false;
                            if (row != null) {
                                Object flag = row.getProperties().get("saveToChosen");
                                shouldOpen = (flag instanceof Boolean b && b);
                                if (shouldOpen) row.getProperties().remove("saveToChosen");
                            }
                            if (shouldOpen) {
                                System.out.println("[DLSAVE] open folder after done: " + file.getAbsolutePath());
                                try {
                                    java.awt.Desktop.getDesktop().open(file.getParentFile());
                                } catch (Exception ex2) {
                                    System.out.println("[DLSAVE] open folder failed ex=" + ex2);
                                }
                            }
                        } catch (Exception ignore) {}
                    });
                }
                System.out.println("[CLIENT] FILE_CHUNK done fid=" + fid + " saved=" + (file!=null?file.getAbsolutePath():"<null>"));
            }
        }
    }
    private void handleDeleteMsgFrame(Frame f) {
        String id = f.transferId;
        if (id != null) {
            Platform.runLater(() -> controller.removeMessageById(id));
        }
    }

    private void handleEditMsgFrame(Frame f) {
        String id = f.transferId;
        String newBody = (f.body == null) ? "" : f.body;
        if (id != null) {
            Platform.runLater(() -> controller.updateTextBubbleById(id, newBody));
        }
    }

    private void handleAckFrame(Frame f) {
        String body = (f.body == null) ? "" : f.body;
        if (f.transferId != null && (
                body.startsWith("OK DM")
             || body.startsWith("OK QUEUED")
             || body.startsWith("OK GROUP_MSG_SENT")
        )) {
            controller.tagNextPendingOutgoing(f.transferId);
            return;
        }
        if (body.contains("\"status\"") && body.contains("FILE_SAVED")) {
            String uuid = f.transferId;
            String msgIdStr = UtilHandler.jsonGet(body, "messageId");
            String fileIdStr = UtilHandler.jsonGet(body, "fileId");
            String bytesStr = UtilHandler.jsonGet(body, "bytes");
            String mime = UtilHandler.jsonGet(body, "mime");
            Long msgId = null, fileId = null, bytes = null;
            try {
                if (msgIdStr != null) msgId = Long.parseLong(msgIdStr);
            } catch (Exception ignore) {}
            try {
                if (fileIdStr != null) fileId = Long.parseLong(fileIdStr);
            } catch (Exception ignore) {}
            try {
                if (bytesStr != null) bytes = Long.parseLong(bytesStr);
            } catch (Exception ignore) {}
            HBox row = (uuid != null) ? controller.findRowByFid(uuid) : null;
            if (row == null && uuid != null) {
                row = controller.getOutgoingFileBubbles().get(uuid);
            }
            if (row != null) {
                if (fileId != null) {
                    row.getProperties().put("fid", String.valueOf(fileId));
                }
                if (msgId != null) {
                    row.setUserData(String.valueOf(msgId));
                }
                if (fileId != null && msgId != null) {
                    controller.getFileIdToMsgId().put(String.valueOf(fileId), String.valueOf(msgId));
                }
                if (mime != null && !mime.isBlank()) {
                    controller.getFileIdToMime().put(String.valueOf(fileId), mime);
                }
                if (bytes != null && bytes >= 0) {
                    controller.getFileIdToSize().put(String.valueOf(fileId), bytes);
                }
                if (uuid != null && fileId != null) {
                    controller.getOutgoingFileBubbles().remove(uuid);
                    controller.getOutgoingFileBubbles().put(String.valueOf(fileId), row);
                }
            }
            if (controller.getConnection() != null && controller.getConnection().isAlive() && fileId != null) {
                try {
                    controller.getConnection().downloadFileByFileId(fileId);
                } catch (IOException ignore) {}
            }
        }
    }

    private void handleFileHistoryFrame(Frame f) {
        String body = f.body;
        if (body == null || body.isBlank()) return;

        try {
            long fileId = UtilHandler.parseLongSafe(f.transferId, 0);
            String fileName = UtilHandler.jsonGet(body, "file_name");
            String mimeType = UtilHandler.jsonGet(body, "mime_type");
            long fileSize = UtilHandler.parseLongSafe(UtilHandler.jsonGet(body, "file_size"), 0);
            String filePath = UtilHandler.jsonGet(body, "file_path"); // Extract file path from JSON

            if (fileId <= 0 || fileName == null || filePath == null) return;

            Platform.runLater(() -> {
                UtilHandler.MediaKind kind = UtilHandler.classifyMedia(mimeType, fileName);
                
                switch (kind) {
                    case IMAGE:
                        // Add filePath parameter
                        controller.getRightController().addPhotoThumb(String.valueOf(fileId), fileName, fileSize, filePath);
                        break;
                    case VIDEO:
                        // Add filePath parameter
                        controller.getRightController().addVideoThumb(String.valueOf(fileId), fileName, fileSize, filePath);
                        break;
                    case AUDIO:
                    case FILE:
                    default:
                        // Add mimeType and filePath parameters
                        controller.getRightController().addDocumentItem(String.valueOf(fileId), fileName, fileSize, mimeType, filePath);
                        break;
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to parse file history frame: " + e.getMessage());
        }
    }
    
    // ===================== UI REFRESH HELPERS (IMAGE/VIDEO) =====================

    private void refreshImageCaption(String fid, Long sizeHint) {
        if (fid == null || fid.isBlank()) return;
        Platform.runLater(() -> {
            HBox row = controller.findRowByFid(fid);
            if (row == null) return;
            var children = row.getChildren();
            if (children.isEmpty()) return;
            javafx.scene.Node bubble = (row.getAlignment() == javafx.geometry.Pos.CENTER_LEFT) ? children.get(0) : children.get(children.size() - 1);
            if (!(bubble instanceof javafx.scene.layout.VBox box)) return;
            String id = box.getId();
            if (id == null || !id.endsWith("-image")) return;
            javafx.scene.control.Label cap = null;
            for (javafx.scene.Node n : box.getChildren()) {
                if (n instanceof javafx.scene.control.Label l) {
                    cap = l;
                    break;
                }
            }
            if (cap == null) return;
            String name = controller.getFileIdToName().getOrDefault(fid, "");
            String mime = controller.getFileIdToMime().getOrDefault(fid, "");
            long size = -1L;
            if (sizeHint != null && sizeHint >= 0) size = sizeHint;
            else {
                File f = controller.getDlPath().get(fid);
                if (f != null && f.exists()) size = f.length();
            }
            String meta = "";
            if (mime != null && !mime.isBlank()) meta = mime;
            if (size >= 0) {
                String sizeStr = UtilHandler.humanBytes(size);
                meta = meta.isBlank() ? sizeStr : (meta + " • " + sizeStr);
            }
            String caption = (name == null ? "" : name);
            if (!meta.isBlank()) caption = caption.isBlank() ? meta : (caption + " • " + meta);
            cap.setText(caption);
        });
    }

    private void refreshVideoLabels(String fid, Long sizeHint) {
        if (fid == null || fid.isBlank()) return;
        Platform.runLater(() -> {
            HBox row = controller.findRowByFid(fid);
            if (row == null) return;
            var children = row.getChildren();
            if (children.isEmpty()) return;
            javafx.scene.Node bubble = (row.getAlignment() == javafx.geometry.Pos.CENTER_LEFT) ? children.get(0) : children.get(children.size() - 1);
            if (!(bubble instanceof javafx.scene.layout.VBox box)) return;
            String id = box.getId();
            if (id == null || !id.endsWith("-video")) return;
            javafx.scene.layout.VBox controls = null;
            for (javafx.scene.Node n : box.getChildren()) {
                if (n instanceof javafx.scene.layout.VBox v && "videoControls".equals(v.getId())) {
                    controls = v;
                    break;
                }
            }
            if (controls == null) {
                if (box.getChildren().size() >= 2 && box.getChildren().get(1) instanceof javafx.scene.layout.VBox v) {
                    controls = v;
                }
            }
            if (controls == null) return;
            javafx.scene.control.Label nameLbl = null, metaLbl = null;
            java.util.List<javafx.scene.Node> cs = controls.getChildren();
            for (javafx.scene.Node n : cs) {
                if (n instanceof javafx.scene.control.Label l) {
                    var styles = l.getStyleClass();
                    if (styles != null && styles.contains("file-name")) nameLbl = l;
                    if (styles != null && styles.contains("meta")) metaLbl = l;
                }
            }
            if (nameLbl == null || metaLbl == null) {
                javafx.scene.control.Label firstLabel = null, secondLabel = null;
                for (javafx.scene.Node n : cs) {
                    if (n instanceof javafx.scene.control.Label l) {
                        if (firstLabel == null) firstLabel = l;
                        else {
                            secondLabel = l;
                            break;
                        }
                    }
                }
                if (nameLbl == null) nameLbl = firstLabel;
                if (metaLbl == null) metaLbl = secondLabel;
            }
            if (nameLbl == null && metaLbl == null) return;
            String name = controller.getFileIdToName().getOrDefault(fid, "");
            String mime = controller.getFileIdToMime().getOrDefault(fid, "");
            long size = -1L;
            if (sizeHint != null && sizeHint >= 0) size = sizeHint;
            else {
                File f = controller.getDlPath().get(fid);
                if (f != null && f.exists()) size = f.length();
            }
            if (nameLbl != null) nameLbl.setText(name);
            String meta = "";
            if (mime != null && !mime.isBlank()) meta = mime;
            if (size >= 0) {
                String sizeStr = UtilHandler.humanBytes(size);
                meta = meta.isBlank() ? sizeStr : (meta + " • " + sizeStr);
            }
            if (metaLbl != null) metaLbl.setText(meta);
        });
    }

    // === SEND TEXT ===
    public void onSendMessage() {
        if (controller.getMessageField() == null) return;
        String text = controller.getMessageField().getText().trim();
        if (text.isEmpty() || controller.getSelectedUser() == null) return;
     // Extract replyTo from UI (if in reply mode)
        final String sender = (controller.getCurrentUser() != null ? controller.getCurrentUser().getUsername() : "");
        final long createdAt = System.currentTimeMillis();
        final long updatedAt = 0L;
        Long replyTo = null;
        if (controller.hasReplyContext()) {
            HBox replyingRow = controller.getReplyingRow();
            if (replyingRow != null) {
                Object ud = replyingRow.getUserData(); // Message ID of original message (set by server via ACK / history)
                if (ud != null) {
                    try { replyTo = Long.parseLong(String.valueOf(ud)); } catch (NumberFormatException ignore) {}
                }
                // Fallback: if original bubble is file without userData, check properties
                if (replyTo == null) {
                    Object p = replyingRow.getProperties().get("fid");
                    if (p != null) {
                        try { replyTo = Long.parseLong(String.valueOf(p)); } catch (NumberFormatException ignore) {}
                    }
                }
                // Fallback 2: if reply chip stored "replyTo", prioritize it
                if (replyTo == null) {
                    Object p2 = replyingRow.getProperties().get("replyTo");
                    if (p2 != null) {
                        try { replyTo = Long.parseLong(String.valueOf(p2)); } catch (NumberFormatException ignore) {}
                    }
                }
            }
        }

        // Wire payload: prepend [REPLY:<id>] if replyTo present
        String wireBody = (replyTo != null && replyTo > 0)
                ? "[REPLY:" + replyTo + "]" + text
                : text;

        // Send over socket (if connected)
        if (controller.getConnection() != null && controller.getConnection().isAlive()) {
            try {
                String from = (controller.getCurrentUser() != null ? controller.getCurrentUser().getUsername() : "");
                controller.getConnection().dm(from, controller.getSelectedUser().getUsername(), wireBody);
            } catch (IOException ioe) {
                System.err.println("[NET] DM failed: " + ioe.getMessage());
                Platform.runLater(() -> controller.showErrorAlert("Message failed to send: " + ioe.getMessage()));
            }
        }
     // Render local bubble immediately for responsive UI
        HBox row = controller.addTextMessage(
                text, 
                false, // outgoing
                null,  // messageId (tagged after server ACK)
                sender,
                createdAt,
                updatedAt
            );
     // Attach replyTo metadata so UIMessageHandler displays reply chip
        if (replyTo != null && replyTo > 0) {
            row.getProperties().put("replyTo", String.valueOf(replyTo));
        }
        controller.enqueuePendingOutgoing(row);
        controller.getMessageField().clear();
    }

    // === CALL LOG UTIL ===
    private static final class CallLogData {
        final String icon, title, subtitle, callId, caller, callee;
        CallLogData(String icon, String title, String subtitle, String callId, String caller, String callee) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.callId = callId;
            this.caller = caller;
            this.callee = callee;
        }
    }

    private static CallLogData parseCallLog(String body) {
        try {
            int i = body.indexOf('{');
            if (!body.startsWith("[CALLLOG]") || i < 0) return null;
            String json = body.substring(i);
            String icon = UtilHandler.jsonGet(json, "icon");
            String title = UtilHandler.jsonGet(json, "title");
            String subtitle = UtilHandler.jsonGet(json, "subtitle");
            String callId = UtilHandler.jsonGet(json, "callId");
            String caller = UtilHandler.jsonGet(json, "caller");
            String callee = UtilHandler.jsonGet(json, "callee");
            if (title == null) title = "";
            if (subtitle == null) subtitle = "";
            if (icon == null || icon.isBlank()) icon = "🎥";
            return new CallLogData(icon, title, subtitle, callId, caller, callee);
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isIncomingForThisClient(CallLogData d) {
        String me = (controller.getCurrentUser() != null)
                ? controller.getCurrentUser().getUsername() : null;
        if (me == null) return true;
        if (d == null) return true;

        if (d.caller != null && !d.caller.isBlank()) {
            return !me.equals(d.caller);
        }
        if (d.callee != null && !d.callee.isBlank()) {
            return me.equals(d.callee);
        }
        return true;
    }

    private void renderCallLogOnce(
            CallLogData d,
            String dmSender,      // sender trong DM/HISTORY (username)
            long createdAt,
            long updatedAt,
            String msgIdStr       // transferId if present
    ) {
        if (d == null) return;

        // Prevent duplicate call log rendering based on callId
        if (d.callId != null && !d.callId.isBlank()) {
            if (!controller.markCallLogShownOnce(d.callId)) {
                return;
            }
        }

        String self = (controller.getCurrentUser() != null)
                ? controller.getCurrentUser().getUsername()
                : "";

        // 1. Determine incoming / outgoing based on caller/callee
        boolean incoming;
        if (d.caller != null && !d.caller.isBlank()) {
            // self is caller -> outgoing; otherwise -> incoming
            incoming = !self.equals(d.caller);
        } else if (d.callee != null && !d.callee.isBlank()) {
            // missing caller -> fallback: self is callee -> incoming
            incoming = self.equals(d.callee);
        } else {
            // corrupt data -> fallback to dmSender
            incoming = !self.equals(dmSender);
        }

        // 2. Determine username for avatar: prioritize caller
        String senderForAvatar;
        if (d.caller != null && !d.caller.isBlank()) {
            senderForAvatar = d.caller;
        } else if (d.callee != null && !d.callee.isBlank()) {
            senderForAvatar = d.callee;
        } else {
            senderForAvatar = dmSender;
        }

        // 3️⃣ Render bubble call log
        HBox row = controller.addCallLog(
                d.icon,
                d.title,
                d.subtitle,
                incoming,
                senderForAvatar,
                createdAt,
                updatedAt
        );

        // 4. Attach messageId for EDIT/DELETE actions
        if (msgIdStr != null && !msgIdStr.isBlank()) {
            row.setUserData(msgIdStr);
        }
    }

 // === SMART REPLY ===
    private void handleSmartReplyFrame(Frame f) {
        String body = (f.body == null) ? "" : f.body.trim();
        if (body.isEmpty()) return;

        try {
            // Parse JSON to extract suggestions array
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONArray arr = json.optJSONArray("suggestions");
            if (arr == null || arr.isEmpty()) return;

            java.util.List<String> suggestions = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.getString(i).trim();
                if (!s.isEmpty()) suggestions.add(s);
            }

            if (suggestions.isEmpty()) return;

            // Render suggestions in UI
            Platform.runLater(() -> {
                try {
                    VBox msgBox = controller.getMessageContainer();
                    if (msgBox == null) return;

                    // Remove existing Smart Reply boxes
                    msgBox.getChildren().removeIf(node ->
                            node instanceof HBox h &&
                            "smart-reply-box".equals(h.getId())
                    );

                    // Create new Smart Reply box
                    HBox suggestionBox = new HBox(8);
                    suggestionBox.setId("smart-reply-box");
                    suggestionBox.setStyle("-fx-padding: 6; -fx-alignment: center-left;");

                    for (String sug : suggestions) {
                        javafx.scene.control.Button btn = new javafx.scene.control.Button(sug);
                        btn.setStyle("""
                            -fx-background-color: #f1f1f1;
                            -fx-border-radius: 8;
                            -fx-background-radius: 8;
                            -fx-cursor: hand;
                        """);
                        btn.setOnAction(ev -> {
                            controller.getMessageField().setText(sug);
                            controller.onSendMessage();
                            msgBox.getChildren().remove(suggestionBox);
                        });
                        suggestionBox.getChildren().add(btn);
                    }

                    // Append suggestions after latest message
                    msgBox.getChildren().add(suggestionBox);

                } catch (Exception ex) {
                    System.err.println("[UI] SMART_REPLY render failed: " + ex.getMessage());
                }
            });


        } catch (Exception e) {
            System.err.println("[SMART-REPLY] JSON parse failed: " + e.getMessage());
            System.err.println("[SMART-REPLY] Raw body: " + body);
        }
    }

    private void handleGroupMsgFrame(Frame f) {
        String sender = f.sender;
        String body = (f.body == null) ? "" : f.body;
        String groupId = f.recipient; // e.g. "11"

        Platform.runLater(() -> {
            // Render only if currently viewing target group
            String currentPeer = controller.getCurrentPeer();
            if (currentPeer == null || !currentPeer.equals("group:" + groupId)) {
                System.out.println("[GROUP_MSG] Message for group " + groupId
                        + " (from " + sender + ") but user is in different view.");
                return;
            }

            // Determine incoming / outgoing
            String myName = (controller.getCurrentUser() != null)
                    ? controller.getCurrentUser().getUsername()
                    : "";
            boolean incoming = !sender.equals(myName);

            // Extract messageId from transferId (server sets to DB id)
            long msgId = 0L;
            try {
                msgId = Long.parseLong(String.valueOf(f.transferId));
            } catch (Exception ignore) {}
            String msgIdStr = (msgId > 0) ? String.valueOf(msgId) : null;

            // Extract reply prefix if [REPLY:<id>] present
            String[] pr = parseReplyPrefix(body);
            String clean = pr[0];
            String replyToId = pr[1];
            String head = (clean == null) ? "" : clean;
            final long createdAt = System.currentTimeMillis();
            // Group call log (if present)
            if (head.startsWith("[CALLLOG]")) {
                CallLogData d = parseCallLog(head);
                renderCallLogOnce(
                        d,
                        sender,                 
                        createdAt,
                        0L,
                        msgIdStr
                );
                return;
            }

            HBox row = controller.addTextMessage(
                    clean,          
                    incoming,
                    msgIdStr,
                    sender,
                    createdAt,
                    0L
            );

            // Attach messageId for EDIT_MSG / DELETE_MSG actions
            if (msgIdStr != null && row.getUserData() == null) {
                row.setUserData(msgIdStr);
            }

            // Attach replyTo and render reply chip if applicable
            if (replyToId != null && row.getProperties().get("replyTo") == null) {
                row.getProperties().put("replyTo", replyToId);
                new UIMessageHandler(controller).attachReplyChipById(
                        row,
                        incoming,   // incoming -> bubble aligned left
                        replyToId
                );
            }
        });
    }

    private void handleGroupHistoryFrame(Frame f) {
        String jsonBody = (f.body == null) ? "" : f.body.trim();

        Platform.runLater(() -> {
            if (jsonBody.isEmpty()) return;

            // 1) Parse JSON
            String recipientKey = UtilHandler.jsonGet(jsonBody, "recipient"); // "group:11"
            String sender       = UtilHandler.jsonGet(jsonBody, "sender");
            String content      = UtilHandler.jsonGet(jsonBody, "content");

            if (recipientKey == null || !recipientKey.startsWith("group:")) {
                System.err.println("[GHIST] invalid recipient in json: " + recipientKey);
                return;
            }
            if (content == null || sender == null) {
                System.err.println("[GHIST] Failed to parse JSON: " + jsonBody);
                return;
            }

            // 2. Render only if currently viewing target group
            String currentPeer = controller.getCurrentPeer();   // e.g. "group:11"
            if (currentPeer == null || !currentPeer.equals(recipientKey)) {
                return;
            }

            long createdAt = UtilHandler.parseLongSafe(UtilHandler.jsonGet(jsonBody, "createdAt"), System.currentTimeMillis());
            long updatedAt = UtilHandler.parseLongSafe(UtilHandler.jsonGet(jsonBody, "updatedAt"), 0L);
            long msgId     = UtilHandler.parseLongSafe(f.transferId, 0L);

            String myName = (controller.getCurrentUser() != null)
                    ? controller.getCurrentUser().getUsername()
                    : "";
            boolean incoming = !sender.equals(myName);
            String msgIdStr = (msgId > 0) ? String.valueOf(msgId) : null;

            // 3. Common dispatch logic for FILE / AUDIO / VIDEO / TEXT / REPLY / CALLLOG
            handleHistoryContent(content, msgIdStr, incoming, sender, createdAt, updatedAt);
        });
    }
}