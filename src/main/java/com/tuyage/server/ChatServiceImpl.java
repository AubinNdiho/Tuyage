package com.tuyage.server;

import com.tuyage.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.security.SecureRandom;

final class ChatServiceImpl extends TuyageServiceGrpc.TuyageServiceImplBase {
    private static final int MAX_MEDIA_BYTES = 20 * 1024 * 1024;
    private final Database db = new Database();
    private final SessionRegistry sessions = new SessionRegistry();
    private final CallRegistry calls = new CallRegistry();
    private final SecureRandom resetRandom = new SecureRandom();
    private final ConcurrentHashMap<String, ResetCode> resetCodes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<Long>> groupCallParticipants = new ConcurrentHashMap<>();
    private record ResetCode(String code, long expiresAt, int attempts) {}

    ChatServiceImpl() {
        sessions.setPresenceListener((userId, isOnline) -> {
            try {
                if (!isOnline) db.touchLastSeen(userId);
                presence(userId);
            } catch (Exception ignored) { }
        });
    }

    private User online(User u) {
        if (u == null) return null;
        return u.toBuilder().setOnline(sessions.isOnline(u.getId())).build();
    }

    private User visibleUser(long viewer, User u) throws SQLException {
        User out=online(u);
        if(out!=null && out.getId()!=viewer && db.isBlockedEither(viewer,out.getId())) return out.toBuilder().setOnline(false).setLastSeen(0).build();
        return out;
    }

    private long uid(String token) { return sessions.require(token); }

    private void fail(StreamObserver<?> o, Throwable t) {
        if (t instanceof SecurityException) o.onError(Status.PERMISSION_DENIED.withDescription(t.getMessage()).asRuntimeException());
        else if (t instanceof IllegalArgumentException) o.onError(Status.INVALID_ARGUMENT.withDescription(t.getMessage()).asRuntimeException());
        else o.onError(Status.INTERNAL.withDescription(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()).asRuntimeException());
    }

    private void validateMedia(int size) {
        if (size > MAX_MEDIA_BYTES) throw new IllegalArgumentException("Fichier trop volumineux (maximum 20 Mo pour ce TP).");
    }

    private String normalizePhone(String raw) {
        String phone = raw == null ? "" : raw.trim().replaceAll("[\\s().-]", "");
        if (phone.startsWith("00")) phone = "+" + phone.substring(2);
        if (!phone.matches("\\+?[0-9]{8,15}"))
            throw new IllegalArgumentException("Numéro de téléphone invalide. Utilise 8 à 15 chiffres, avec + indicatif si nécessaire.");
        return phone;
    }

    private User contactTarget(ContactRequest r,long me) throws SQLException {
        User target = r.getUserId() > 0 ? db.getUser(r.getUserId())
                : (r.getPhone().isBlank() ? null : db.getUserByPhone(normalizePhone(r.getPhone())));
        if (target == null) throw new IllegalArgumentException("Aucun compte Tuyage trouvé avec ce numéro.");
        if (target.getId() == me) throw new IllegalArgumentException("Cette action ne peut pas viser ton propre compte.");
        return target;
    }

    private void presence(long userId) throws SQLException {
        User u = online(db.getUser(userId));
        if (u != null) sessions.broadcast(Event.newBuilder().setType(EventType.EVENT_PRESENCE).setUser(u).setActorId(userId).setActorName(u.getDisplayName()).build());
    }

    private void emitMessage(ChatMessage m, EventType type) throws SQLException {
        Event event = Event.newBuilder().setType(type).setMessage(m).setActorId(m.getSenderId()).setActorName(m.getSenderName()).setGroupId(m.getGroupId()).setTargetUserId(m.getRecipientUserId()).build();
        Set<Long> targets = new LinkedHashSet<>();
        if (m.getGroupId() > 0) targets.addAll(db.groupMemberIds(m.getGroupId()));
        else { targets.add(m.getSenderId()); targets.add(m.getRecipientUserId()); }
        sessions.emitToMany(targets, event);
    }

    private void emitToConversation(ChatMessage m, EventType type) throws SQLException { emitMessage(m,type); }

    // ---------------- AUTH ----------------

    @Override public void register(RegisterRequest r, StreamObserver<AuthResponse> o) {
        try {
            String phone = normalizePhone(r.getUsername()); String display = r.getDisplayName().trim();
            if (display.length() < 2) throw new IllegalArgumentException("Nom affiché trop court.");
            if (r.getPassword().length() < 6) throw new IllegalArgumentException("Mot de passe: minimum 6 caractères.");
            User user = db.createUser(phone, display, r.getPassword());
            String token = sessions.create(user.getId()); user = online(user); presence(user.getId());
            o.onNext(AuthResponse.newBuilder().setToken(token).setUser(user).setMessage("Compte créé avec succès").build()); o.onCompleted();
        } catch (SQLException e) {
            if (e.getMessage()!=null && e.getMessage().toLowerCase().contains("unique")) o.onError(Status.ALREADY_EXISTS.withDescription("Ce numéro de téléphone possède déjà un compte.").asRuntimeException());
            else fail(o,e);
        } catch (Throwable t) { fail(o,t); }
    }

    @Override public void login(LoginRequest r, StreamObserver<AuthResponse> o) {
        try {
            User user=db.authenticate(normalizePhone(r.getUsername()),r.getPassword());
            if(user==null){o.onError(Status.UNAUTHENTICATED.withDescription("Numéro de téléphone ou mot de passe incorrect.").asRuntimeException());return;}
            String token=sessions.create(user.getId()); user=online(user); presence(user.getId());
            o.onNext(AuthResponse.newBuilder().setToken(token).setUser(user).setMessage("Bienvenue "+user.getDisplayName()).build());o.onCompleted();
        }catch(Throwable t){fail(o,t);}
    }


    @Override public void requestPasswordReset(PasswordResetRequest r, StreamObserver<Ack> o) {
        try {
            String phone = normalizePhone(r.getPhone());
            User user = db.getUserByPhone(phone);
            // Réponse volontairement générique pour ne pas révéler les comptes existants.
            String message = "Si ce numéro existe, un code de récupération a été généré.";
            if (user != null) {
                String code = String.format("%06d", resetRandom.nextInt(1_000_000));
                resetCodes.put(phone, new ResetCode(code, System.currentTimeMillis() + 5 * 60_000L, 0));
                System.out.println("[Tuyage OTP] " + phone + " -> " + code + " (valable 5 minutes)");
                if (Boolean.parseBoolean(System.getProperty("tuyage.demoOtp", "true"))) {
                    message += " Mode TP : code " + code + " (en production, il serait envoyé par SMS).";
                } else {
                    message += " Consulte le SMS envoyé sur ton téléphone.";
                }
            }
            o.onNext(Ack.newBuilder().setOk(true).setMessage(message).build());
            o.onCompleted();
        } catch (Throwable t) { fail(o,t); }
    }

    @Override public void resetPassword(ResetPasswordRequest r, StreamObserver<Ack> o) {
        try {
            String phone = normalizePhone(r.getPhone());
            if (r.getNewPassword().length() < 6) throw new IllegalArgumentException("Nouveau mot de passe: minimum 6 caractères.");
            User user = db.getUserByPhone(phone);
            if (user == null) throw new SecurityException("Code de récupération invalide ou expiré.");
            ResetCode state = resetCodes.get(phone);
            if (state == null || state.expiresAt() < System.currentTimeMillis()) {
                resetCodes.remove(phone);
                throw new SecurityException("Code de récupération invalide ou expiré.");
            }
            if (state.attempts() >= 5) {
                resetCodes.remove(phone);
                throw new SecurityException("Trop de tentatives. Demande un nouveau code.");
            }
            if (!state.code().equals(r.getCode().trim())) {
                resetCodes.put(phone, new ResetCode(state.code(), state.expiresAt(), state.attempts() + 1));
                throw new SecurityException("Code de récupération incorrect.");
            }
            db.changePassword(user.getId(), r.getNewPassword());
            resetCodes.remove(phone);
            sessions.invalidateUser(user.getId());
            o.onNext(Ack.newBuilder().setOk(true).setMessage("Mot de passe réinitialisé. Reconnecte-toi avec le nouveau mot de passe.").build());
            o.onCompleted();
        } catch (Throwable t) { fail(o,t); }
    }

    @Override public void logout(AuthRequest r, StreamObserver<Ack> o) {
        try { Long id=sessions.remove(r.getToken()); if(id!=null){db.touchLastSeen(id);presence(id);} o.onNext(Ack.newBuilder().setOk(true).setMessage("Déconnecté").build());o.onCompleted(); } catch(Throwable t){fail(o,t);}
    }

    @Override public void getMe(AuthRequest r, StreamObserver<User> o){try{o.onNext(online(db.getUser(uid(r.getToken()))));o.onCompleted();}catch(Throwable t){fail(o,t);}}

    @Override public void updateProfile(UpdateProfileRequest r, StreamObserver<User> o){try{
        long id=uid(r.getToken()); validateMedia(r.getAvatar().size());
        if(r.getDisplayName().trim().length()<2)throw new IllegalArgumentException("Nom affiché trop court.");
        User u=online(db.updateProfile(id,r.getDisplayName().trim(),r.getBio().trim(),r.getAvatar().toByteArray(),r.getReplaceAvatar()));presence(id);o.onNext(u);o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void changePassword(ChangePasswordRequest r, StreamObserver<Ack> o){try{
        long id=uid(r.getToken());
        if(!db.verifyPassword(id,r.getCurrentPassword()))throw new SecurityException("Mot de passe actuel incorrect.");
        if(r.getNewPassword().length()<6)throw new IllegalArgumentException("Nouveau mot de passe: minimum 6 caractères.");
        db.changePassword(id,r.getNewPassword());
        o.onNext(Ack.newBuilder().setOk(true).setMessage("Mot de passe modifié").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void deleteAccount(DeleteAccountRequest r, StreamObserver<Ack> o){try{
        long id=uid(r.getToken());
        if(!db.verifyPassword(id,r.getPassword()))throw new SecurityException("Mot de passe incorrect.");
        User before=db.getUser(id);db.deleteAccount(id);calls.unregisterUser(id);sessions.invalidateUser(id);
        if(before!=null)sessions.broadcast(Event.newBuilder().setType(EventType.EVENT_PRESENCE).setActorId(id).setActorName(before.getDisplayName()).setText("ACCOUNT_DELETED").build());
        o.onNext(Ack.newBuilder().setOk(true).setMessage("Compte supprimé définitivement").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void listUsers(AuthRequest r, StreamObserver<UsersResponse> o){try{
        long me=uid(r.getToken()); UsersResponse.Builder b=UsersResponse.newBuilder(); for(User u:db.listUsers())if(u.getId()!=me)b.addUsers(visibleUser(me,u)); o.onNext(b.build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void getUnreadCounts(AuthRequest r, StreamObserver<UnreadCountsResponse> o){try{
        long me=uid(r.getToken());o.onNext(UnreadCountsResponse.newBuilder().addAllCounts(db.unreadCounts(me)).build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    // ---------------- CONTACTS & BLOCKS ----------------

    @Override public void addContact(ContactRequest r,StreamObserver<User> o){try{
        long me=uid(r.getToken());User target=contactTarget(r,me);db.addContact(me,target.getId());
        sessions.emitTo(me,Event.newBuilder().setType(EventType.EVENT_CONTACT).setActorId(me).setTargetUserId(target.getId()).setText("CONTACT_ADDED").build());
        o.onNext(online(target));o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void removeContact(ContactRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());User target=contactTarget(r,me);db.removeContact(me,target.getId());
        sessions.emitTo(me,Event.newBuilder().setType(EventType.EVENT_CONTACT).setActorId(me).setTargetUserId(target.getId()).setText("CONTACT_REMOVED").build());
        o.onNext(Ack.newBuilder().setOk(true).setMessage("Contact retiré").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void listContacts(AuthRequest r,StreamObserver<UsersResponse> o){try{
        long me=uid(r.getToken());UsersResponse.Builder b=UsersResponse.newBuilder();for(User u:db.listContacts(me))b.addUsers(visibleUser(me,u));o.onNext(b.build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void blockUser(ContactRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());User target=contactTarget(r,me);db.blockUser(me,target.getId());
        sessions.emitTo(me,Event.newBuilder().setType(EventType.EVENT_BLOCK).setActorId(me).setTargetUserId(target.getId()).setText("BLOCKED").build());
        o.onNext(Ack.newBuilder().setOk(true).setMessage(target.getDisplayName()+" bloqué").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void unblockUser(ContactRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());User target=contactTarget(r,me);db.unblockUser(me,target.getId());
        sessions.emitTo(me,Event.newBuilder().setType(EventType.EVENT_BLOCK).setActorId(me).setTargetUserId(target.getId()).setText("UNBLOCKED").build());
        o.onNext(Ack.newBuilder().setOk(true).setMessage(target.getDisplayName()+" débloqué").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void listBlocked(AuthRequest r,StreamObserver<UsersResponse> o){try{
        long me=uid(r.getToken());UsersResponse.Builder b=UsersResponse.newBuilder();for(User u:db.listBlocked(me))b.addUsers(visibleUser(me,u));o.onNext(b.build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    // ---------------- MESSAGES ----------------

    @Override public void sendMessage(SendMessageRequest r, StreamObserver<ChatMessage> o){try{
        long sender=uid(r.getToken()); validateMedia(r.getData().size());
        if(r.getGroupId()>0){if(!db.isMember(r.getGroupId(),sender))throw new SecurityException("Vous n'êtes pas membre de ce groupe.");}
        else {
            if(r.getRecipientUserId()<=0||db.getUser(r.getRecipientUserId())==null)throw new IllegalArgumentException("Destinataire invalide.");
            if(db.isBlockedEither(sender,r.getRecipientUserId()))throw new SecurityException("Cette conversation est bloquée.");
        }
        if(r.getType()==MessageType.MESSAGE_TEXT && r.getContent().trim().isEmpty())throw new IllegalArgumentException("Message vide.");
        ChatMessage m=db.insertMessage(sender,r.getRecipientUserId(),r.getGroupId(),r.getType(),r.getContent(),r.getFileName(),r.getMimeType(),r.getData().toByteArray(),r.getReplyToId());
        if(r.getGroupId()==0 && sessions.isOnline(r.getRecipientUserId())){db.markDelivered(m.getId(),r.getRecipientUserId());m=db.getMessage(m.getId());}
        emitMessage(m,EventType.EVENT_MESSAGE);o.onNext(m);o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void listMessages(MessagesRequest r, StreamObserver<MessagesResponse> o){try{
        long me=uid(r.getToken()); List<ChatMessage> list;
        if(r.getGroupId()>0){if(!db.isMember(r.getGroupId(),me))throw new SecurityException("Accès au groupe refusé.");list=db.listGroupMessages(r.getGroupId(),me,r.getLimit()==0?300:r.getLimit());}
        else { if(db.isBlockedEither(me,r.getOtherUserId())){/* historique reste consultable */} list=db.listDirectMessages(me,r.getOtherUserId(),r.getLimit()==0?300:r.getLimit()); }
        o.onNext(MessagesResponse.newBuilder().addAllMessages(list).build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void searchMessages(SearchMessagesRequest r,StreamObserver<MessagesResponse> o){try{
        long me=uid(r.getToken());if(r.getGroupId()>0&&!db.isMember(r.getGroupId(),me))throw new SecurityException("Accès au groupe refusé.");
        List<ChatMessage> list=db.searchMessages(me,r.getOtherUserId(),r.getGroupId(),r.getQuery(),r.getLimit()==0?100:r.getLimit());
        o.onNext(MessagesResponse.newBuilder().addAllMessages(list).build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void editMessage(EditMessageRequest r,StreamObserver<ChatMessage> o){try{
        long me=uid(r.getToken()); if(r.getContent().trim().isEmpty())throw new IllegalArgumentException("Message vide.");ChatMessage m=db.editMessage(r.getMessageId(),me,r.getContent().trim());if(m==null)throw new SecurityException("Modification non autorisée.");emitMessage(m,EventType.EVENT_MESSAGE);o.onNext(m);o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void deleteMessage(DeleteMessageRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());ChatMessage before=db.getMessage(r.getMessageId());if(before==null)throw new IllegalArgumentException("Message introuvable.");
        if(r.getForEveryone()){
            if(!db.deleteMessageForEveryone(r.getMessageId(),me))throw new SecurityException("Seul l'expéditeur peut supprimer ce message pour tout le monde.");
            ChatMessage after=db.getMessage(r.getMessageId());emitMessage(after,EventType.EVENT_MESSAGE);
            o.onNext(Ack.newBuilder().setOk(true).setMessage("Message supprimé pour tout le monde").build());
        }else{
            boolean participant=before.getSenderId()==me||before.getRecipientUserId()==me||(before.getGroupId()>0&&db.isMember(before.getGroupId(),me));
            if(!participant)throw new SecurityException("Suppression non autorisée.");
            db.hideMessage(r.getMessageId(),me);o.onNext(Ack.newBuilder().setOk(true).setMessage("Message supprimé pour moi").build());
        }
        o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void pinMessage(PinMessageRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());ChatMessage m=db.getMessageForViewer(r.getMessageId(),me);if(m==null)throw new IllegalArgumentException("Message introuvable.");
        boolean participant=m.getSenderId()==me||m.getRecipientUserId()==me||(m.getGroupId()>0&&db.isMember(m.getGroupId(),me));if(!participant)throw new SecurityException("Action non autorisée.");
        db.pinMessage(r.getMessageId(),me,r.getPinned());
        sessions.emitTo(me,Event.newBuilder().setType(EventType.EVENT_MESSAGE).setActorId(me).setText("PIN_CHANGED").setMessage(m).build());
        o.onNext(Ack.newBuilder().setOk(true).setMessage(r.getPinned()?"Message épinglé":"Message désépinglé").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void reactMessage(ReactMessageRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());ChatMessage m=db.getMessage(r.getMessageId());if(m==null)throw new IllegalArgumentException("Message introuvable.");db.react(r.getMessageId(),me,r.getEmoji());m=db.getMessage(r.getMessageId());emitMessage(m,EventType.EVENT_REACTION);o.onNext(Ack.newBuilder().setOk(true).setMessage("Réaction mise à jour").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void markRead(MarkReadRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());boolean changed=db.markRead(r.getMessageId(),me);ChatMessage m=db.getMessage(r.getMessageId());if(changed&&m!=null)emitMessage(m,EventType.EVENT_READ);o.onNext(Ack.newBuilder().setOk(true).build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void setTyping(TypingRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());User u=db.getUser(me);String activity=r.getActivity().isBlank()?"typing":r.getActivity();
        Event e=Event.newBuilder().setType(EventType.EVENT_TYPING).setActorId(me).setActorName(u.getDisplayName()).setTargetUserId(r.getRecipientUserId()).setGroupId(r.getGroupId()).setTyping(r.getTyping()).setText(activity).build();
        if(r.getGroupId()>0){
            if(!db.isMember(r.getGroupId(),me))throw new SecurityException("Accès au groupe refusé.");
            for(Long id:db.groupMemberIds(r.getGroupId()))if(id!=me)sessions.emitTo(id,e);
        }else{
            if(r.getRecipientUserId()<=0||db.getUser(r.getRecipientUserId())==null)throw new IllegalArgumentException("Destinataire invalide.");
            if(!db.isBlockedEither(me,r.getRecipientUserId()))sessions.emitTo(r.getRecipientUserId(),e);
        }
        o.onNext(Ack.newBuilder().setOk(true).build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    // ---------------- GROUPS ----------------

    @Override public void createGroup(CreateGroupRequest r,StreamObserver<Group> o){try{
        long me=uid(r.getToken());validateMedia(r.getAvatar().size());if(r.getName().trim().length()<2)throw new IllegalArgumentException("Nom de groupe trop court.");
        Group g=db.createGroup(me,r.getName().trim(),r.getDescription().trim(),r.getAvatar().toByteArray(),r.getMemberIdsList());Event e=Event.newBuilder().setType(EventType.EVENT_GROUP).setGroup(g).setActorId(me).setText("Groupe créé").build();sessions.emitToMany(db.groupMemberIds(g.getId()),e);o.onNext(g);o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void updateGroup(UpdateGroupRequest r,StreamObserver<Group> o){try{
        long me=uid(r.getToken());validateMedia(r.getAvatar().size());if(r.getName().trim().length()<2)throw new IllegalArgumentException("Nom de groupe trop court.");
        Group g=db.updateGroup(r.getGroupId(),me,r.getName().trim(),r.getDescription().trim(),r.getAvatar().toByteArray(),r.getReplaceAvatar());
        sessions.emitToMany(db.groupMemberIds(r.getGroupId()),Event.newBuilder().setType(EventType.EVENT_GROUP).setGroup(g).setActorId(me).setText("Groupe modifié").build());o.onNext(g);o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void listGroups(AuthRequest r,StreamObserver<GroupsResponse> o){try{long me=uid(r.getToken());o.onNext(GroupsResponse.newBuilder().addAllGroups(db.listGroups(me)).build());o.onCompleted();}catch(Throwable t){fail(o,t);}}

    @Override public void getGroupMembers(GroupRequest r,StreamObserver<UsersResponse> o){try{long me=uid(r.getToken());if(!db.isMember(r.getGroupId(),me))throw new SecurityException("Accès refusé.");UsersResponse.Builder b=UsersResponse.newBuilder();for(User u:db.groupMembers(r.getGroupId()))b.addUsers(visibleUser(me,u));o.onNext(b.build());o.onCompleted();}catch(Throwable t){fail(o,t);}}

    @Override public void getGroupMembersDetailed(GroupRequest r,StreamObserver<GroupMembersResponse> o){try{
        long me=uid(r.getToken());if(!db.isMember(r.getGroupId(),me))throw new SecurityException("Accès refusé.");GroupMembersResponse.Builder b=GroupMembersResponse.newBuilder();for(GroupMember gm:db.groupMembersDetailed(r.getGroupId()))b.addMembers(gm.toBuilder().setUser(visibleUser(me,gm.getUser())).build());o.onNext(b.build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void addGroupMember(GroupMemberRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());if(!db.canManage(r.getGroupId(),me))throw new SecurityException("Droits insuffisants.");if(db.getUser(r.getUserId())==null)throw new IllegalArgumentException("Utilisateur introuvable.");db.addGroupMember(r.getGroupId(),r.getUserId());Group g=db.getGroup(r.getGroupId(),me);sessions.emitToMany(db.groupMemberIds(r.getGroupId()),Event.newBuilder().setType(EventType.EVENT_GROUP).setGroup(g).setActorId(me).setText("Membre ajouté").build());o.onNext(Ack.newBuilder().setOk(true).setMessage("Membre ajouté").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void removeGroupMember(GroupMemberRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());if(!db.canManage(r.getGroupId(),me))throw new SecurityException("Droits insuffisants.");if(db.isOwner(r.getGroupId(),r.getUserId()))throw new IllegalArgumentException("Le propriétaire ne peut pas être retiré.");
        if("ADMIN".equals(db.groupRole(r.getGroupId(),r.getUserId()))&&!db.isOwner(r.getGroupId(),me))throw new SecurityException("Seul le propriétaire peut retirer un administrateur.");
        List<Long> before=db.groupMemberIds(r.getGroupId());db.removeGroupMember(r.getGroupId(),r.getUserId());Group g=db.getGroup(r.getGroupId(),me);Set<Long> notify=new LinkedHashSet<>(before);notify.add(r.getUserId());sessions.emitToMany(notify,Event.newBuilder().setType(EventType.EVENT_GROUP).setGroup(g).setActorId(me).setText("Membre retiré").build());o.onNext(Ack.newBuilder().setOk(true).setMessage("Membre retiré").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void setGroupRole(GroupRoleRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());if(!db.isOwner(r.getGroupId(),me))throw new SecurityException("Seul le propriétaire peut gérer les administrateurs.");
        if(r.getUserId()==me)throw new IllegalArgumentException("Le propriétaire reste propriétaire.");if(!db.isMember(r.getGroupId(),r.getUserId()))throw new IllegalArgumentException("Ce membre n'appartient plus au groupe.");
        String role=r.getRole().toUpperCase();if(!role.equals("ADMIN")&&!role.equals("MEMBER"))throw new IllegalArgumentException("Rôle invalide.");db.setGroupRole(r.getGroupId(),r.getUserId(),role);
        Group g=db.getGroup(r.getGroupId(),me);sessions.emitToMany(db.groupMemberIds(r.getGroupId()),Event.newBuilder().setType(EventType.EVENT_GROUP).setGroup(g).setActorId(me).setText(role.equals("ADMIN")?"Nouvel administrateur":"Administrateur retiré").build());
        o.onNext(Ack.newBuilder().setOk(true).setMessage("Rôle mis à jour").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void leaveGroup(GroupRequest r,StreamObserver<Ack> o){try{long me=uid(r.getToken());if(db.isOwner(r.getGroupId(),me))throw new IllegalArgumentException("Le propriétaire doit supprimer le groupe.");db.removeGroupMember(r.getGroupId(),me);sessions.emitToMany(db.groupMemberIds(r.getGroupId()),Event.newBuilder().setType(EventType.EVENT_GROUP).setActorId(me).setText("Un membre a quitté le groupe").build());o.onNext(Ack.newBuilder().setOk(true).setMessage("Vous avez quitté le groupe").build());o.onCompleted();}catch(Throwable t){fail(o,t);}}

    @Override public void deleteGroup(GroupRequest r,StreamObserver<Ack> o){try{long me=uid(r.getToken());if(!db.isOwner(r.getGroupId(),me))throw new SecurityException("Seul le propriétaire peut supprimer le groupe.");List<Long> targets=db.groupMemberIds(r.getGroupId());db.deleteGroup(r.getGroupId());sessions.emitToMany(targets,Event.newBuilder().setType(EventType.EVENT_GROUP).setActorId(me).setGroupId(r.getGroupId()).setText("Groupe supprimé").build());o.onNext(Ack.newBuilder().setOk(true).setMessage("Groupe supprimé").build());o.onCompleted();}catch(Throwable t){fail(o,t);}}

    // ---------------- STATUSES ----------------

    @Override public void createStatus(CreateStatusRequest r,StreamObserver<StatusUpdate> o){try{long me=uid(r.getToken());validateMedia(r.getData().size());if(r.getType()==StatusType.STATUS_TEXT&&r.getContent().trim().isEmpty())throw new IllegalArgumentException("Statut vide.");StatusUpdate s=db.createStatus(me,r.getType(),r.getContent(),r.getBackground(),r.getFileName(),r.getMimeType(),r.getData().toByteArray());sessions.broadcast(Event.newBuilder().setType(EventType.EVENT_STATUS).setStatus(s).setActorId(me).setActorName(s.getUserName()).build());o.onNext(s);o.onCompleted();}catch(Throwable t){fail(o,t);}}

    @Override public void updateStatus(UpdateStatusRequest r,StreamObserver<StatusUpdate> o){try{
        long me=uid(r.getToken());StatusUpdate old=db.getStatus(r.getStatusId());if(old==null||old.getUserId()!=me)throw new SecurityException("Modification du statut non autorisée.");if(old.getType()==StatusType.STATUS_TEXT&&r.getContent().trim().isEmpty())throw new IllegalArgumentException("Statut vide.");
        StatusUpdate st=db.updateStatus(r.getStatusId(),me,r.getContent().trim(),r.getBackground().isBlank()?old.getBackground():r.getBackground());if(st==null)throw new IllegalArgumentException("Statut expiré ou introuvable.");sessions.broadcast(Event.newBuilder().setType(EventType.EVENT_STATUS).setStatus(st).setActorId(me).setActorName(st.getUserName()).setText("Statut modifié").build());o.onNext(st);o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void deleteStatus(StatusRequest r,StreamObserver<Ack> o){try{long me=uid(r.getToken());if(!db.deleteStatus(r.getStatusId(),me))throw new SecurityException("Suppression du statut non autorisée.");sessions.broadcast(Event.newBuilder().setType(EventType.EVENT_STATUS).setActorId(me).setText("STATUS_DELETED").build());o.onNext(Ack.newBuilder().setOk(true).setMessage("Statut supprimé").build());o.onCompleted();}catch(Throwable t){fail(o,t);}}

    @Override public void listStatuses(AuthRequest r,StreamObserver<StatusesResponse> o){try{
        long me=uid(r.getToken());StatusesResponse.Builder b=StatusesResponse.newBuilder();
        for(StatusUpdate s:db.listStatuses()){
            if(s.getUserId()==me){b.addStatuses(s.toBuilder().setViewedByMe(true).build());continue;}
            if(db.isBlockedEither(me,s.getUserId()))continue;
            if(db.isContact(me,s.getUserId()) || db.isContact(s.getUserId(),me)){
                b.addStatuses(s.toBuilder().setViewedByMe(db.hasViewedStatus(s.getId(),me)).build());
            }
        }
        o.onNext(b.build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void viewStatus(StatusRequest r,StreamObserver<Ack> o){try{
        long me=uid(r.getToken());StatusUpdate s=db.getStatus(r.getStatusId());
        if(s==null||s.getExpiresAt()<System.currentTimeMillis())throw new IllegalArgumentException("Statut expiré ou introuvable.");
        if(s.getUserId()!=me && (db.isBlockedEither(me,s.getUserId()) || (!db.isContact(me,s.getUserId()) && !db.isContact(s.getUserId(),me))))throw new SecurityException("Ce statut n'est pas visible pour ce compte.");
        db.viewStatus(r.getStatusId(),me);StatusUpdate updated=db.getStatus(r.getStatusId());sessions.emitTo(s.getUserId(),Event.newBuilder().setType(EventType.EVENT_STATUS).setStatus(updated).setActorId(me).setText("STATUS_VIEWED").build());o.onNext(Ack.newBuilder().setOk(true).build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void listStatusViewers(StatusRequest r,StreamObserver<UsersResponse> o){try{long me=uid(r.getToken());UsersResponse.Builder b=UsersResponse.newBuilder();for(User u:db.statusViewers(r.getStatusId(),me))b.addUsers(online(u));o.onNext(b.build());o.onCompleted();}catch(Throwable t){fail(o,t);}}

    @Override public void commentStatus(CommentStatusRequest r,StreamObserver<StatusComment> o){try{
        long me=uid(r.getToken());if(r.getComment().trim().isEmpty())throw new IllegalArgumentException("Commentaire vide.");StatusUpdate st=db.getStatus(r.getStatusId());if(st==null||st.getExpiresAt()<System.currentTimeMillis())throw new IllegalArgumentException("Statut expiré ou introuvable.");
        if(st.getUserId()!=me && (db.isBlockedEither(me,st.getUserId()) || (!db.isContact(me,st.getUserId()) && !db.isContact(st.getUserId(),me))))throw new SecurityException("Tu ne peux pas répondre à ce statut.");
        StatusComment c=db.addStatusComment(r.getStatusId(),me,r.getComment().trim());sessions.emitTo(st.getUserId(),Event.newBuilder().setType(EventType.EVENT_STATUS).setStatus(st).setActorId(me).setActorName(c.getUserName()).setText("Nouveau commentaire: "+c.getComment()).build());o.onNext(c);o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public void listStatusComments(StatusRequest r,StreamObserver<StatusCommentsResponse> o){try{uid(r.getToken());o.onNext(StatusCommentsResponse.newBuilder().addAllComments(db.statusComments(r.getStatusId())).build());o.onCompleted();}catch(Throwable t){fail(o,t);}}

    // ---------------- CALLS ----------------

    @Override public void signalCall(CallPacket packet, StreamObserver<Ack> o){try{
        long me=uid(packet.getToken());
        if(packet.getType()==CallPacketType.CALL_AUDIO || packet.getType()==CallPacketType.CALL_VIDEO || packet.getType()==CallPacketType.CALL_REGISTER) throw new IllegalArgumentException("Type de signal d'appel invalide.");

        if(packet.getGroupId()>0){
            long groupId=packet.getGroupId();if(!db.isMember(groupId,me))throw new SecurityException("Vous n'êtes pas membre de ce groupe.");
            CallPacket routed=packet.toBuilder().clearToken().setFromUserId(me).setTimestamp(System.currentTimeMillis()).build();
            if(packet.getType()==CallPacketType.CALL_OFFER){
                groupCallParticipants.put(packet.getCallId(), ConcurrentHashMap.newKeySet());
                groupCallParticipants.get(packet.getCallId()).add(me);
                ChatMessage history=db.insertGroupCallHistory(me,groupId,packet.getCallId(),packet.getVideoEnabled());
                emitMessage(history,EventType.EVENT_MESSAGE);
                for(Long memberId:db.groupMemberIds(groupId)){if(memberId==me)continue;sessions.emitTo(memberId,Event.newBuilder().setType(EventType.EVENT_CALL).setCall(routed).setActorId(me).setTargetUserId(memberId).setGroupId(groupId).build());}
            }else if(packet.getType()==CallPacketType.CALL_END){
                groupCallParticipants.remove(packet.getCallId());
                String status=db.callWasAccepted(packet.getCallId())?"COMPLETED":"NO_ANSWER";
                ChatMessage history=db.updateCallStatus(packet.getCallId(),status);if(history!=null)emitMessage(history,EventType.EVENT_MESSAGE);
                for(Long memberId:db.groupMemberIds(groupId)){if(memberId==me)continue;sessions.emitTo(memberId,Event.newBuilder().setType(EventType.EVENT_CALL).setCall(routed).setActorId(me).setTargetUserId(memberId).setGroupId(groupId).build());}
            }else if(packet.getType()==CallPacketType.CALL_ACCEPT){
                groupCallParticipants.computeIfAbsent(packet.getCallId(), k -> ConcurrentHashMap.newKeySet()).add(me);
                ChatMessage history=db.getCallMessage(packet.getCallId());
                if(history!=null && "RINGING".equals(history.getCallStatus())){history=db.updateCallStatus(packet.getCallId(),"ONGOING");if(history!=null)emitMessage(history,EventType.EVENT_MESSAGE);}
                if(packet.getToUserId()>0)sessions.emitTo(packet.getToUserId(),Event.newBuilder().setType(EventType.EVENT_CALL).setCall(routed).setActorId(me).setTargetUserId(packet.getToUserId()).setGroupId(groupId).build());
            }else if(packet.getType()==CallPacketType.CALL_LEAVE){
                Set<Long> participants=groupCallParticipants.get(packet.getCallId());
                if(participants!=null){
                    participants.remove(me);
                    for(Long participant:participants){
                        sessions.emitTo(participant,Event.newBuilder().setType(EventType.EVENT_CALL).setCall(routed).setActorId(me).setTargetUserId(participant).setGroupId(groupId).build());
                    }
                    // Le groupe peut continuer si des participants restent. Quand le dernier quitte,
                    // on clôture proprement l'historique au lieu de laisser un appel "ONGOING".
                    if(participants.isEmpty()){
                        groupCallParticipants.remove(packet.getCallId(),participants);
                        String status=db.callWasAccepted(packet.getCallId())?"COMPLETED":"NO_ANSWER";
                        ChatMessage history=db.updateCallStatus(packet.getCallId(),status);
                        if(history!=null)emitMessage(history,EventType.EVENT_MESSAGE);
                    }
                }
            }else if(packet.getType()==CallPacketType.CALL_REJECT){
                if(packet.getToUserId()>0)sessions.emitTo(packet.getToUserId(),Event.newBuilder().setType(EventType.EVENT_CALL).setCall(routed).setActorId(me).setTargetUserId(packet.getToUserId()).setGroupId(groupId).build());
            }
            o.onNext(Ack.newBuilder().setOk(true).setMessage("Signal d'appel de groupe transmis").build());o.onCompleted();return;
        }

        if(packet.getToUserId()<=0)throw new IllegalArgumentException("Destinataire d'appel invalide.");
        if(db.isBlockedEither(me,packet.getToUserId()))throw new SecurityException("Impossible d'appeler ce contact car la conversation est bloquée.");

        if(packet.getType()==CallPacketType.CALL_OFFER){ChatMessage history=db.insertCallHistory(me,packet.getToUserId(),packet.getCallId(),packet.getVideoEnabled());if(sessions.isOnline(packet.getToUserId()))db.markDelivered(history.getId(),packet.getToUserId());history=db.getMessage(history.getId());emitMessage(history,EventType.EVENT_MESSAGE);}

        CallPacket routed=packet.toBuilder().clearToken().setFromUserId(me).setTimestamp(System.currentTimeMillis()).build();
        if(packet.getType()==CallPacketType.CALL_OFFER && !sessions.isOnline(packet.getToUserId())){
            ChatMessage history=db.updateCallStatus(packet.getCallId(),"UNAVAILABLE");if(history!=null)emitMessage(history,EventType.EVENT_MESSAGE);
            CallPacket unavailable=CallPacket.newBuilder().setCallId(packet.getCallId()).setFromUserId(packet.getToUserId()).setToUserId(me).setType(CallPacketType.CALL_UNAVAILABLE).setTimestamp(System.currentTimeMillis()).setVideoEnabled(packet.getVideoEnabled()).setText("Utilisateur indisponible pour l'appel").build();
            sessions.emitTo(me,Event.newBuilder().setType(EventType.EVENT_CALL).setCall(unavailable).setActorId(packet.getToUserId()).setTargetUserId(me).build());
        }else{
            if(packet.getType()==CallPacketType.CALL_ACCEPT){ChatMessage history=db.updateCallStatus(packet.getCallId(),"ONGOING");if(history!=null)emitMessage(history,EventType.EVENT_MESSAGE);}
            else if(packet.getType()==CallPacketType.CALL_REJECT){String status=packet.getText()!=null&&packet.getText().toLowerCase().contains("occup")?"BUSY":"REJECTED";ChatMessage history=db.updateCallStatus(packet.getCallId(),status);if(history!=null)emitMessage(history,EventType.EVENT_MESSAGE);}
            else if(packet.getType()==CallPacketType.CALL_END){String status=db.callWasAccepted(packet.getCallId())?"COMPLETED":"NO_ANSWER";ChatMessage history=db.updateCallStatus(packet.getCallId(),status);if(history!=null)emitMessage(history,EventType.EVENT_MESSAGE);}
            sessions.emitTo(packet.getToUserId(),Event.newBuilder().setType(EventType.EVENT_CALL).setCall(routed).setActorId(me).setTargetUserId(packet.getToUserId()).build());
        }
        o.onNext(Ack.newBuilder().setOk(true).setMessage("Signal d'appel transmis").build());o.onCompleted();
    }catch(Throwable t){fail(o,t);}}

    @Override public StreamObserver<CallPacket> call(StreamObserver<CallPacket> out){
        AtomicLong registeredUser=new AtomicLong(0);

        // Le callback d’annulation DOIT être déclaré pendant l’initialisation de
        // l’appel gRPC, avant que cette méthode retourne le StreamObserver entrant.
        // L’ancienne version tentait de le faire plus tard, à la réception de
        // CALL_REGISTER, ce qui faisait tomber le canal média avec INTERNAL.
        if(out instanceof io.grpc.stub.ServerCallStreamObserver<CallPacket> serverOut){
            serverOut.setOnCancelHandler(() -> {
                long id=registeredUser.get();
                if(id>0) calls.unregister(id,out);
            });
        }

        return new StreamObserver<>(){
            @Override public void onNext(CallPacket packet){
                try{
                    long me=uid(packet.getToken());
                    if(registeredUser.get()==0)registeredUser.set(me);
                    if(registeredUser.get()!=me)throw new SecurityException("Canal d'appel invalide.");

                    // Le flux bidirectionnel Call est le canal média dédié. Il ne doit pas
                    // partager audio/vidéo avec Subscribe (messages, présence, statuts...).
                    if(packet.getType()==CallPacketType.CALL_REGISTER){
                        calls.register(me,out);
                        // Accusé d'enregistrement du canal média. Le client peut ainsi
                        // distinguer un vrai canal prêt d'un flux simplement créé localement.
                        CallPacket ready=CallPacket.newBuilder()
                                .setType(CallPacketType.CALL_REGISTER)
                                .setFromUserId(me)
                                .setToUserId(me)
                                .setTimestamp(System.currentTimeMillis())
                                .setText("MEDIA_READY")
                                .build();
                        synchronized(out){out.onNext(ready);}
                        System.out.println("[Tuyage CALL] Canal média enregistré pour utilisateur #"+me);
                        return;
                    }

                    if(packet.getType()!=CallPacketType.CALL_AUDIO && packet.getType()!=CallPacketType.CALL_VIDEO)return;
                    CallPacket routed=packet.toBuilder().clearToken().setFromUserId(me).setTimestamp(System.currentTimeMillis()).build();

                    if(packet.getGroupId()>0){
                        if(!db.isMember(packet.getGroupId(),me))throw new SecurityException("Accès à l'appel de groupe refusé.");
                        Set<Long> participants=groupCallParticipants.get(packet.getCallId());
                        if(participants==null || !participants.contains(me))return;
                        for(Long memberId:participants){
                            if(memberId==me)continue;
                            // Le canal média dédié est la source de vérité pour un appel.
                            // Ne pas dépendre de Subscribe/isOnline : une reconnexion du flux
                            // de présence ne doit jamais couper l'audio/vidéo d'un appel actif.
                            calls.send(memberId,routed.toBuilder().setToUserId(memberId).build());
                        }
                    }else{
                        if(packet.getToUserId()<=0)throw new IllegalArgumentException("Destinataire d'appel invalide.");
                        if(db.isBlockedEither(me,packet.getToUserId()))return;

                        // IMPORTANT v3.0.11 :
                        // l'audio/vidéo d'appel passe exclusivement par CallRegistry.
                        // L'ancien test sessions.isOnline(...) pouvait jeter des paquets
                        // pendant une micro-reconnexion du flux Subscribe, alors que le
                        // canal média gRPC était toujours READY.
                        boolean delivered = calls.send(packet.getToUserId(),routed);
                        if(!delivered && packet.getType()==CallPacketType.CALL_AUDIO
                                && packet.getSequence()%250==0){
                            System.err.println("[Tuyage CALL] Audio non routé vers #"
                                    + packet.getToUserId()
                                    + " • aucun canal média enregistré.");
                        }
                    }
                }catch(Throwable t){
                    long id=registeredUser.get();
                    if(id>0)calls.unregister(id,out);
                    try{out.onError(Status.INTERNAL.withDescription(t.getMessage()==null?t.getClass().getSimpleName():t.getMessage()).asRuntimeException());}catch(Exception ignored){}
                }
            }
            @Override public void onError(Throwable t){long id=registeredUser.get();if(id>0)calls.unregister(id,out);}
            @Override public void onCompleted(){long id=registeredUser.get();if(id>0)calls.unregister(id,out);try{out.onCompleted();}catch(Exception ignored){}}
        };
    }

    @Override public void subscribe(AuthRequest r,StreamObserver<Event> o){try{
        long me=uid(r.getToken());sessions.subscribe(me,o);
        // Dès que le client est réellement abonné, les messages hors ligne deviennent "livrés".
        for(ChatMessage m:db.markPendingDelivered(me)){if(m.getSenderId()!=me)sessions.emitTo(m.getSenderId(),Event.newBuilder().setType(EventType.EVENT_READ).setMessage(m).setActorId(me).setTargetUserId(m.getSenderId()).setText("DELIVERED").build());}
    }catch(Throwable t){fail(o,t);}}
}
