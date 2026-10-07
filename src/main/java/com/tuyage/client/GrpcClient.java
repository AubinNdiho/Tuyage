package com.tuyage.client;

import com.google.protobuf.ByteString;
import com.tuyage.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class GrpcClient implements AutoCloseable {
    private final ManagedChannel channel;
    private final TuyageServiceGrpc.TuyageServiceBlockingStub blocking;
    private final TuyageServiceGrpc.TuyageServiceStub async;
    private String token = "";
    private volatile StreamObserver<CallPacket> callOutbound;
    private volatile Consumer<CallPacket> callPacketHandler;
    private volatile Consumer<Throwable> callErrorHandler;
    private final AtomicLong callGeneration = new AtomicLong();
    private volatile boolean callRegistered;

    GrpcClient(String target) {
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().maxInboundMessageSize(128 * 1024 * 1024).build();
        blocking = TuyageServiceGrpc.newBlockingStub(channel);
        async = TuyageServiceGrpc.newStub(channel);
    }

    AuthResponse register(String username, String displayName, String password) {
        AuthResponse a = blocking.register(RegisterRequest.newBuilder().setUsername(username).setDisplayName(displayName).setPassword(password).build()); token=a.getToken(); return a;
    }
    AuthResponse login(String username,String password){AuthResponse a=blocking.login(LoginRequest.newBuilder().setUsername(username).setPassword(password).build());token=a.getToken();return a;}
    String requestPasswordReset(String phone){return blocking.requestPasswordReset(PasswordResetRequest.newBuilder().setPhone(phone==null?"":phone).build()).getMessage();}
    void resetPassword(String phone,String code,String newPassword){blocking.resetPassword(ResetPasswordRequest.newBuilder().setPhone(phone==null?"":phone).setCode(code==null?"":code).setNewPassword(newPassword==null?"":newPassword).build());}
    void logout(){if(!token.isBlank())try{blocking.logout(auth());}catch(Exception ignored){} token="";}
    AuthRequest auth(){return AuthRequest.newBuilder().setToken(token).build();}
    String token(){return token;}
    User me(){return blocking.getMe(auth());}
    List<User> users(){return blocking.listUsers(auth()).getUsersList();}
    List<UnreadCount> unreadCounts(){return blocking.getUnreadCounts(auth()).getCountsList();}
    User updateProfile(String display,String bio,byte[] avatar,boolean replace){return blocking.updateProfile(UpdateProfileRequest.newBuilder().setToken(token).setDisplayName(display).setBio(bio).setAvatar(ByteString.copyFrom(avatar==null?new byte[0]:avatar)).setReplaceAvatar(replace).build());}
    void changePassword(String currentPassword,String newPassword){blocking.changePassword(ChangePasswordRequest.newBuilder().setToken(token).setCurrentPassword(currentPassword).setNewPassword(newPassword).build());}
    void deleteAccount(String password){blocking.deleteAccount(DeleteAccountRequest.newBuilder().setToken(token).setPassword(password).build());token="";}

    User addContact(String phone){return blocking.addContact(ContactRequest.newBuilder().setToken(token).setPhone(phone==null?"":phone).build());}
    User addContact(long userId){return blocking.addContact(ContactRequest.newBuilder().setToken(token).setUserId(userId).build());}
    void removeContact(long userId){blocking.removeContact(ContactRequest.newBuilder().setToken(token).setUserId(userId).build());}
    List<User> contacts(){return blocking.listContacts(auth()).getUsersList();}
    void block(long userId){blocking.blockUser(ContactRequest.newBuilder().setToken(token).setUserId(userId).build());}
    void unblock(long userId){blocking.unblockUser(ContactRequest.newBuilder().setToken(token).setUserId(userId).build());}
    List<User> blocked(){return blocking.listBlocked(auth()).getUsersList();}

    ChatMessage send(long recipient,long group,MessageType type,String content,String fileName,String mime,byte[] data,long replyTo){return blocking.sendMessage(SendMessageRequest.newBuilder().setToken(token).setRecipientUserId(recipient).setGroupId(group).setType(type).setContent(content==null?"":content).setFileName(fileName==null?"":fileName).setMimeType(mime==null?"":mime).setData(ByteString.copyFrom(data==null?new byte[0]:data)).setReplyToId(replyTo).build());}
    List<ChatMessage> directMessages(long other){return blocking.listMessages(MessagesRequest.newBuilder().setToken(token).setOtherUserId(other).setLimit(500).build()).getMessagesList();}
    List<ChatMessage> groupMessages(long group){return blocking.listMessages(MessagesRequest.newBuilder().setToken(token).setGroupId(group).setLimit(500).build()).getMessagesList();}
    List<ChatMessage> searchDirect(long other,String query){return blocking.searchMessages(SearchMessagesRequest.newBuilder().setToken(token).setOtherUserId(other).setQuery(query==null?"":query).setLimit(150).build()).getMessagesList();}
    List<ChatMessage> searchGroup(long group,String query){return blocking.searchMessages(SearchMessagesRequest.newBuilder().setToken(token).setGroupId(group).setQuery(query==null?"":query).setLimit(150).build()).getMessagesList();}
    ChatMessage edit(long id,String text){return blocking.editMessage(EditMessageRequest.newBuilder().setToken(token).setMessageId(id).setContent(text).build());}
    void deleteForMe(long id){blocking.deleteMessage(DeleteMessageRequest.newBuilder().setToken(token).setMessageId(id).setForEveryone(false).build());}
    void deleteForEveryone(long id){blocking.deleteMessage(DeleteMessageRequest.newBuilder().setToken(token).setMessageId(id).setForEveryone(true).build());}
    void delete(long id){deleteForEveryone(id);}
    void pin(long id,boolean pinned){blocking.pinMessage(PinMessageRequest.newBuilder().setToken(token).setMessageId(id).setPinned(pinned).build());}
    void react(long id,String emoji){blocking.reactMessage(ReactMessageRequest.newBuilder().setToken(token).setMessageId(id).setEmoji(emoji).build());}
    void markRead(long id){blocking.markRead(MarkReadRequest.newBuilder().setToken(token).setMessageId(id).build());}
    void activity(long recipient,long group,boolean active,String activity){blocking.setTyping(TypingRequest.newBuilder().setToken(token).setRecipientUserId(recipient).setGroupId(group).setTyping(active).setActivity(activity==null?"typing":activity).build());}
    void typing(long recipient,long group,boolean typing){activity(recipient,group,typing,"typing");}

    Group createGroup(String name,String description,byte[] avatar,List<Long> members){return blocking.createGroup(CreateGroupRequest.newBuilder().setToken(token).setName(name).setDescription(description).setAvatar(ByteString.copyFrom(avatar==null?new byte[0]:avatar)).addAllMemberIds(members).build());}
    Group updateGroup(long group,String name,String description,byte[] avatar,boolean replace){return blocking.updateGroup(UpdateGroupRequest.newBuilder().setToken(token).setGroupId(group).setName(name).setDescription(description).setAvatar(ByteString.copyFrom(avatar==null?new byte[0]:avatar)).setReplaceAvatar(replace).build());}
    List<Group> groups(){return blocking.listGroups(auth()).getGroupsList();}
    List<User> groupMembers(long groupId){return blocking.getGroupMembers(GroupRequest.newBuilder().setToken(token).setGroupId(groupId).build()).getUsersList();}
    List<GroupMember> groupMembersDetailed(long groupId){return blocking.getGroupMembersDetailed(GroupRequest.newBuilder().setToken(token).setGroupId(groupId).build()).getMembersList();}
    void addMember(long group,long user){blocking.addGroupMember(GroupMemberRequest.newBuilder().setToken(token).setGroupId(group).setUserId(user).build());}
    void removeMember(long group,long user){blocking.removeGroupMember(GroupMemberRequest.newBuilder().setToken(token).setGroupId(group).setUserId(user).build());}
    void setGroupRole(long group,long user,String role){blocking.setGroupRole(GroupRoleRequest.newBuilder().setToken(token).setGroupId(group).setUserId(user).setRole(role).build());}
    void leaveGroup(long group){blocking.leaveGroup(GroupRequest.newBuilder().setToken(token).setGroupId(group).build());}
    void deleteGroup(long group){blocking.deleteGroup(GroupRequest.newBuilder().setToken(token).setGroupId(group).build());}

    StatusUpdate createTextStatus(String text,String bg){return blocking.createStatus(CreateStatusRequest.newBuilder().setToken(token).setType(StatusType.STATUS_TEXT).setContent(text).setBackground(bg).build());}
    StatusUpdate createMediaStatus(StatusType type,String fileName,String mime,byte[] data){return blocking.createStatus(CreateStatusRequest.newBuilder().setToken(token).setType(type).setFileName(fileName).setMimeType(mime).setData(ByteString.copyFrom(data)).setBackground("aurora").build());}
    StatusUpdate updateStatus(long statusId,String content,String background){return blocking.updateStatus(UpdateStatusRequest.newBuilder().setToken(token).setStatusId(statusId).setContent(content==null?"":content).setBackground(background==null?"":background).build());}
    void deleteStatus(long statusId){blocking.deleteStatus(StatusRequest.newBuilder().setToken(token).setStatusId(statusId).build());}
    List<StatusUpdate> statuses(){return blocking.listStatuses(auth()).getStatusesList();}
    void viewStatus(long statusId){blocking.viewStatus(StatusRequest.newBuilder().setToken(token).setStatusId(statusId).build());}
    List<User> statusViewers(long statusId){return blocking.listStatusViewers(StatusRequest.newBuilder().setToken(token).setStatusId(statusId).build()).getUsersList();}
    StatusComment comment(long statusId,String text){return blocking.commentStatus(CommentStatusRequest.newBuilder().setToken(token).setStatusId(statusId).setComment(text).build());}
    List<StatusComment> statusComments(long statusId){return blocking.listStatusComments(StatusRequest.newBuilder().setToken(token).setStatusId(statusId).build()).getCommentsList();}

    void startCallChannel(Consumer<CallPacket> onPacket,Consumer<Throwable> onError){
        callPacketHandler=onPacket;
        callErrorHandler=onError;
        openCallChannel(onPacket,onError);
    }

    private synchronized void openCallChannel(Consumer<CallPacket> onPacket,Consumer<Throwable> onError){
        if(token.isBlank() || channel.isShutdown() || callOutbound!=null) return;
        callPacketHandler=onPacket;
        callErrorHandler=onError;
        final long generation=callGeneration.incrementAndGet();
        callRegistered=false;

        callOutbound=async.call(new StreamObserver<>(){
            public void onNext(CallPacket value){
                if(generation!=callGeneration.get()) return;
                if(value.getType()==CallPacketType.CALL_REGISTER && "MEDIA_READY".equals(value.getText())){
                    callRegistered=true;
                    System.out.println("[Tuyage CALL] Canal média gRPC enregistré côté serveur.");
                    return;
                }
                onPacket.accept(value);
            }
            public void onError(Throwable t){
                if(generation==callGeneration.get()){
                    callOutbound=null;
                    callRegistered=false;
                    onError.accept(t);
                    scheduleCallReconnect(onPacket,onError);
                }
            }
            public void onCompleted(){
                if(generation==callGeneration.get()){
                    callOutbound=null;
                    callRegistered=false;
                    scheduleCallReconnect(onPacket,onError);
                }
            }
        });

        // Premier paquet du flux: enregistrement du canal média pour cet utilisateur.
        sendCallInternal(0,0,CallPacketType.CALL_REGISTER,"",new byte[0],false,"",0L,0L,0,0,0,"");
    }

    private void scheduleCallReconnect(Consumer<CallPacket> onPacket,Consumer<Throwable> onError){
        if(token.isBlank() || channel.isShutdown()) return;
        Thread retry=new Thread(()->{
            try{Thread.sleep(900);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            if(!token.isBlank() && !channel.isShutdown() && callOutbound==null) openCallChannel(onPacket,onError);
        },"tuyage-call-reconnect");
        retry.setDaemon(true);
        retry.start();
    }

    private synchronized boolean ensureCallChannel(){
        if(callOutbound!=null) return true;
        Consumer<CallPacket> packets=callPacketHandler;
        Consumer<Throwable> errors=callErrorHandler;
        if(packets==null || errors==null || token.isBlank() || channel.isShutdown()) return false;
        openCallChannel(packets,errors);
        return callOutbound!=null;
    }

    synchronized void sendCall(long target,CallPacketType type,String callId,byte[] payload,boolean video,String text){
        sendCallInternal(target,0,type,callId,payload,video,text,0L,0L,0,0,0,"");
    }

    synchronized boolean sendMediaCall(long target,CallPacketType type,String callId,byte[] payload,boolean video,long sequence){
        return sendMediaCall(target,type,callId,payload,video,sequence,0,0,0);
    }

    synchronized boolean sendMediaCall(long target,CallPacketType type,String callId,byte[] payload,boolean video,long sequence,
                                       int sampleRate,int sampleBits,int channels){
        return sendCallInternal(target,0,type,callId,payload,video,"",sequence,System.currentTimeMillis(),sampleRate,sampleBits,channels,"");
    }

    synchronized boolean sendMediaCall(long target,CallPacketType type,String callId,byte[] payload,boolean video,long sequence,
                                       int sampleRate,int sampleBits,int channels,String audioCodec){
        return sendCallInternal(target,0,type,callId,payload,video,"",sequence,System.currentTimeMillis(),sampleRate,sampleBits,channels,audioCodec);
    }

    synchronized void sendGroupCall(long groupId,long target,CallPacketType type,String callId,boolean video,String text){
        sendCallInternal(target,groupId,type,callId,new byte[0],video,text,0L,0L,0,0,0,"");
    }

    synchronized boolean sendGroupMediaCall(long groupId,CallPacketType type,String callId,byte[] payload,boolean video,long sequence){
        return sendGroupMediaCall(groupId,type,callId,payload,video,sequence,0,0,0,"");
    }

    synchronized boolean sendGroupMediaCall(long groupId,CallPacketType type,String callId,byte[] payload,boolean video,long sequence,
                                            int sampleRate,int sampleBits,int channels){
        return sendGroupMediaCall(groupId,type,callId,payload,video,sequence,sampleRate,sampleBits,channels,"");
    }

    synchronized boolean sendGroupMediaCall(long groupId,CallPacketType type,String callId,byte[] payload,boolean video,long sequence,
                                            int sampleRate,int sampleBits,int channels,String audioCodec){
        return sendCallInternal(0,groupId,type,callId,payload,video,"",sequence,System.currentTimeMillis(),sampleRate,sampleBits,channels,audioCodec);
    }

    private synchronized boolean sendCallInternal(long target,long groupId,CallPacketType type,String callId,byte[] payload,
                                                  boolean video,String text,long sequence,long mediaTimestamp,
                                                  int sampleRate,int sampleBits,int channels,String audioCodec){
        CallPacket packet=CallPacket.newBuilder()
                .setToken(token)
                .setCallId(callId==null?"":callId)
                .setToUserId(target)
                .setGroupId(groupId)
                .setType(type)
                .setPayload(ByteString.copyFrom(payload==null?new byte[0]:payload))
                .setTimestamp(System.currentTimeMillis())
                .setVideoEnabled(video)
                .setText(text==null?"":text)
                .setSequence(sequence)
                .setMediaTimestamp(mediaTimestamp)
                .setAudioSampleRate(sampleRate)
                .setAudioSampleBits(sampleBits)
                .setAudioChannels(channels)
                .setAudioCodec(audioCodec==null?"":audioCodec)
                .build();

        if(type!=CallPacketType.CALL_REGISTER && type!=CallPacketType.CALL_AUDIO && type!=CallPacketType.CALL_VIDEO){
            blocking.signalCall(packet);
            return true;
        }

        if(callOutbound==null && type!=CallPacketType.CALL_REGISTER) ensureCallChannel();
        StreamObserver<CallPacket> outbound=callOutbound;
        if(outbound==null){
            System.err.println("[Tuyage CALL] Paquet média non envoyé: canal gRPC indisponible.");
            return false;
        }

        try{
            synchronized(outbound){outbound.onNext(packet);}
            return true;
        }catch(RuntimeException ex){
            if(outbound==callOutbound){callOutbound=null;callRegistered=false;}
            System.err.println("[Tuyage CALL] Echec d'envoi média: "+ex.getMessage());
            return false;
        }
    }

    boolean isCallChannelRegistered(){ return callRegistered; }

    void subscribe(Consumer<Event> onEvent,Consumer<Throwable> onError){
        subscribeInternal(onEvent,onError);
    }

    private void subscribeInternal(Consumer<Event> onEvent,Consumer<Throwable> onError){
        if(token.isBlank() || channel.isShutdown()) return;
        async.subscribe(auth(),new StreamObserver<>(){
            public void onNext(Event value){onEvent.accept(value);}
            public void onError(Throwable t){
                onError.accept(t);
                scheduleResubscribe(onEvent,onError);
            }
            public void onCompleted(){scheduleResubscribe(onEvent,onError);}
        });
    }

    private void scheduleResubscribe(Consumer<Event> onEvent,Consumer<Throwable> onError){
        if(token.isBlank() || channel.isShutdown()) return;
        Thread retry=new Thread(()->{
            try{Thread.sleep(1800);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            if(!token.isBlank() && !channel.isShutdown()) subscribeInternal(onEvent,onError);
        },"tuyage-grpc-reconnect");
        retry.setDaemon(true);
        retry.start();
    }

    @Override public void close(){try{if(callOutbound!=null){try{callOutbound.onCompleted();}catch(Exception ignored){}callOutbound=null;}logout();channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);}catch(Exception ignored){}}
}
