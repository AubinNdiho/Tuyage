package com.tuyage.client;

import com.tuyage.proto.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javafx.animation.PauseTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.util.Duration;

final class MainView extends BorderPane {
    private enum Mode { CHATS, GROUPS, STATUS }
    private record StatusOwner(long id, String name, List<StatusUpdate> statuses) {
        @Override public String toString() { return name + " • " + statuses.size(); }
    }

    private final ClientApp app;
    private final GrpcClient api;
    private User me;
    private Mode mode = Mode.CHATS;

    private final VBox leftContent = new VBox(10);
    private HBox meIdentityCard;
    private final BorderPane center = new BorderPane();
    private final Label sectionTitle = new Label("Discussions");
    private final TextField search = new TextField();

    private List<User> allUsers = new ArrayList<>();
    private List<User> allContacts = new ArrayList<>();
    private Set<Long> blockedIds = new HashSet<>();
    private List<Group> allGroups = new ArrayList<>();
    private List<StatusUpdate> allStatuses = new ArrayList<>();
    private User selectedUser;
    private Group selectedGroup;
    private List<ChatMessage> currentMessages = new ArrayList<>();
    private final Map<Long,Integer> unreadUsers = new HashMap<>();
    private final Map<Long,Integer> unreadGroups = new HashMap<>();
    // Activité récente utilisée par la liste Discussions unifiée (contacts + groupes).
    private final Map<Long,Long> recentUserActivity = new HashMap<>();
    private final Map<Long,Long> recentGroupActivity = new HashMap<>();
    private final Map<Long,String> recentUserPreview = new HashMap<>();
    private final Map<Long,String> recentGroupPreview = new HashMap<>();
    private int openedUnreadCount = 0;

    private VBox messagesBox;
    private Label conversationTitle;
    private Label conversationSub;
    private Label typingLabel;
    private TextField composer;
    private TextField conversationSearch;
    private Label replyBanner;
    private long replyToId;

    private final AudioRecorder recorder = new AudioRecorder();
    private Button micButton;
    private HBox audioPreview;
    private Label audioPreviewLabel;
    private HBox composerBar;
    private Timeline recordingTimer;
    private long recordingStartedAt;
    private byte[] pendingAudio = new byte[0];
    private final PauseTransition typingPause = new PauseTransition(Duration.seconds(1.2));
    private Timeline statusTimeline;
    private MediaPlayer statusMediaPlayer;
    private Path statusMediaTemp;
    private boolean statusViewerOpen;
    private StatusOwner activeStatusOwner;
    private int activeStatusIndex;
    private Button statusPauseButton;
    private StackPane statusOverlay;

    private CallController calls;

    MainView(ClientApp app, GrpcClient api, User me) {
        this.app = app;
        this.api = api;
        this.me = me;
        getStyleClass().add("main-root");
        setFocusTraversable(true);
        build();
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (statusViewerOpen && event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                stopStatusTimeline();
                closeStatusOverlay();
                showStatusHome();
                renderList();
            } else if (statusViewerOpen && event.getCode() == KeyCode.RIGHT && activeStatusOwner != null) {
                event.consume();
                showNextStatus(activeStatusOwner, activeStatusIndex);
            } else if (statusViewerOpen && event.getCode() == KeyCode.LEFT && activeStatusOwner != null) {
                event.consume();
                showPreviousStatus(activeStatusOwner, activeStatusIndex);
            } else if (statusViewerOpen && event.getCode() == KeyCode.SPACE) {
                Node focusOwner = app.stage().getScene() == null ? null : app.stage().getScene().getFocusOwner();
                if (!(focusOwner instanceof TextInputControl)) {
                    event.consume();
                    toggleStatusPlayback(statusPauseButton);
                }
            } else if ((recorder.isRecording() || pendingAudio.length > 0) && event.getCode() == KeyCode.ENTER) {
                event.consume();
                stopAndSendRecordedAudio();
            } else if (recorder.isRecording() && event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                cancelRecording();
            }
        });
        refreshAll();
        calls = new CallController(app, api, me, this::findUser, this::findGroup);
        subscribe();
        app.stage().focusedProperty().addListener((obs, oldValue, focused) -> {
            if (focused && (selectedUser != null || selectedGroup != null)) Platform.runLater(() -> {
                if(selectedUser!=null) unreadUsers.remove(selectedUser.getId());
                if(selectedGroup!=null) unreadGroups.remove(selectedGroup.getId());
                markVisibleRead();
                renderList();
            });
        });
        showChats();
    }

    private void build() {
        setLeft(buildRail());

        VBox listShell = new VBox(12);
        listShell.getStyleClass().add("list-shell");
        listShell.setPadding(new Insets(22, 16, 16, 16));
        sectionTitle.getStyleClass().add("section-title");
        search.setPromptText("Rechercher...");
        search.textProperty().addListener((o, a, b) -> renderList());
        leftContent.setFillWidth(true);
        VBox.setVgrow(leftContent, Priority.ALWAYS);
        listShell.getChildren().addAll(myIdentityCard(), new Separator(), sectionTitle, search, leftContent);
        listShell.setPrefWidth(330);
        listShell.setMinWidth(300);

        HBox content = new HBox(listShell, center);
        HBox.setHgrow(center, Priority.ALWAYS);
        center.getStyleClass().add("content-shell");
        setCenter(content);
        applyChatBackground();
        showWelcome();
    }

    private Node myIdentityCard() {
        meIdentityCard = new HBox(10);
        meIdentityCard.setAlignment(Pos.CENTER_LEFT);
        meIdentityCard.getStyleClass().add("me-card");
        meIdentityCard.setPadding(new Insets(10));
        meIdentityCard.setOnMouseClicked(e -> showProfile());
        refreshMyIdentityCard();
        return meIdentityCard;
    }

    private void refreshMyIdentityCard() {
        if (meIdentityCard == null) return;
        Node avatar = avatarNode(me);
        VBox text = new VBox(2);
        Label mine = new Label("Moi");
        mine.getStyleClass().add("me-card-kicker");
        Label name = new Label(me.getDisplayName());
        name.getStyleClass().add("item-title");
        Label phone = new Label(me.getUsername());
        phone.getStyleClass().add("muted-small");
        text.getChildren().addAll(mine, name, phone);
        meIdentityCard.getChildren().setAll(avatar, text);
    }

    private VBox buildRail() {
        VBox rail = new VBox(10);
        rail.getStyleClass().add("nav-rail");
        rail.setPadding(new Insets(18, 10, 18, 10));
        rail.setAlignment(Pos.TOP_CENTER);
        rail.setPrefWidth(92);

        ImageView logo = new ImageView(new Image(getClass().getResourceAsStream("/images/logo-icon.png")));
        logo.setFitWidth(72);
        logo.setFitHeight(72);
        logo.setPreserveRatio(true);

        Button chats = navButton("💬", "Chats", this::showChats);
        Button groups = navButton("👥", "Groupes", this::showGroups);
        Button status = navButton("◉", "Statuts", this::showStatuses);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Button settings = navButton("⚙", "Réglages", this::showSettingsDialog);
        Button logout = navButton("⇥", "Se déconnecter", app::logout);
        // Réglages reste une action principale, juste sous Statuts. La déconnexion
        // reste isolée en bas pour éviter les clics accidentels.
        rail.getChildren().addAll(logo, new Separator(), chats, groups, status, settings, spacer, logout);
        return rail;
    }

    private Button navButton(String icon, String text, Runnable action) {
        Button b = new Button(icon + "\n" + text);
        b.getStyleClass().add("nav-button");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void showWelcome() {
        resetRecording();
        VBox v = new VBox(14);
        v.setAlignment(Pos.CENTER);
        v.getStyleClass().add("welcome-card");
        v.setMaxWidth(520);
        v.setPadding(new Insets(26, 34, 26, 34));
        ImageView mark = new ImageView(new Image(getClass().getResourceAsStream("/images/logo.png")));
        mark.setFitWidth(220);
        mark.setFitHeight(220);
        mark.setPreserveRatio(true);
        Label sub = new Label("Choisis une conversation, un groupe ou un statut.\nTes échanges transitent en temps réel par gRPC.");
        sub.getStyleClass().add("muted");
        sub.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        v.getChildren().addAll(mark, sub);
        StackPane holder = new StackPane(v);
        holder.setPadding(new Insets(30));
        center.setCenter(holder);
        center.setTop(null);
        center.setBottom(null);
        applyChatBackground();
    }

    private void showChats() {
        stopStatusTimeline();
        statusViewerOpen = false;
        mode = Mode.CHATS;
        selectedGroup = null;
        sectionTitle.setText("Discussions");
        search.setPromptText("Rechercher une personne...");
        renderList();
        if (selectedUser != null) loadDirect(selectedUser); else showWelcome();
    }

    private void showGroups() {
        stopStatusTimeline();
        statusViewerOpen = false;
        mode = Mode.GROUPS;
        selectedUser = null;
        sectionTitle.setText("Groupes");
        search.setPromptText("Rechercher un groupe...");
        renderList();
        if (selectedGroup != null) loadGroup(selectedGroup); else showWelcome();
    }

    private void showStatuses() {
        stopStatusTimeline();
        statusViewerOpen = false;
        mode = Mode.STATUS;
        selectedUser = null;
        selectedGroup = null;
        resetRecording();
        sectionTitle.setText("Mises à jour");
        search.setPromptText("Rechercher un statut...");
        renderList();
        showStatusHome();
    }

    private void refreshAll() {
        try { allUsers = new ArrayList<>(api.users()); } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        try { allContacts = new ArrayList<>(api.contacts()); } catch (Exception ignored) { allContacts = new ArrayList<>(); }
        try { blockedIds = api.blocked().stream().map(User::getId).collect(Collectors.toSet()); } catch (Exception ignored) { blockedIds = new HashSet<>(); }
        try { allGroups = new ArrayList<>(api.groups()); } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        try { allStatuses = new ArrayList<>(api.statuses()); } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        unreadUsers.clear();unreadGroups.clear();
        try { for(UnreadCount c:api.unreadCounts()){if(c.getUserId()>0)unreadUsers.put(c.getUserId(),c.getCount());else if(c.getGroupId()>0)unreadGroups.put(c.getGroupId(),c.getCount());} } catch(Exception ignored) {}
        refreshConversationActivity();
    }

    private void refreshConversationActivity() {
        recentUserActivity.clear(); recentGroupActivity.clear();
        recentUserPreview.clear(); recentGroupPreview.clear();
        for (User u : allUsers) {
            try {
                List<ChatMessage> ms = api.directMessages(u.getId());
                if (!ms.isEmpty()) {
                    ChatMessage last = ms.stream().max(Comparator.comparingLong(ChatMessage::getCreatedAt)).orElse(null);
                    if (last != null) { recentUserActivity.put(u.getId(), last.getCreatedAt()); recentUserPreview.put(u.getId(), notificationPreview(last)); }
                }
            } catch (Exception ignored) { }
        }
        for (Group g : allGroups) {
            try {
                List<ChatMessage> ms = api.groupMessages(g.getId());
                if (!ms.isEmpty()) {
                    ChatMessage last = ms.stream().max(Comparator.comparingLong(ChatMessage::getCreatedAt)).orElse(null);
                    if (last != null) { recentGroupActivity.put(g.getId(), last.getCreatedAt()); recentGroupPreview.put(g.getId(), notificationPreview(last)); }
                }
            } catch (Exception ignored) { }
        }
    }

    private long conversationActivity(Object item) {
        if (item instanceof User u) return recentUserActivity.getOrDefault(u.getId(), 0L);
        if (item instanceof Group g) return recentGroupActivity.getOrDefault(g.getId(), g.getCreatedAt());
        return 0L;
    }

    private int conversationPinned(Object item) {
        if (item instanceof User u) return app.isPinnedUser(u.getId()) ? 0 : 1;
        if (item instanceof Group g) return app.isPinnedGroup(g.getId()) ? 0 : 1;
        return 1;
    }

    private void renderList() {
        leftContent.getChildren().clear();
        String q = search.getText() == null ? "" : search.getText().toLowerCase().trim();

        if (mode == Mode.CHATS) {
            HBox actions = new HBox(7);
            Button addContact = new Button("＋ Contact");
            Button contacts = new Button("☏ Contacts");
            addContact.getStyleClass().add("primary-button");
            contacts.getStyleClass().add("soft-button");
            addContact.setOnAction(e -> addContactByPhone());
            contacts.setOnAction(e -> showContactsDialog());
            actions.getChildren().addAll(addContact, contacts);

            Set<Long> contactsSet = allContacts.stream().map(User::getId).collect(Collectors.toSet());
            List<Object> conversations = new ArrayList<>();
            allUsers.stream()
                    .filter(u -> !app.isArchivedUser(u.getId()))
                    .filter(u -> u.getDisplayName().toLowerCase().contains(q) || u.getUsername().toLowerCase().contains(q))
                    .forEach(conversations::add);
            // Les groupes restent dans leur rubrique de gestion, mais apparaissent aussi
            // dans Discussions comme une conversation normale dès qu'ils sont actifs.
            allGroups.stream()
                    .filter(g -> !app.isArchivedGroup(g.getId()))
                    .filter(g -> g.getName().toLowerCase().contains(q))
                    .filter(g -> recentGroupActivity.containsKey(g.getId()) || unreadGroups.getOrDefault(g.getId(), 0) > 0)
                    .forEach(conversations::add);
            conversations.sort(Comparator.<Object>comparingInt(this::conversationPinned)
                    .thenComparing(Comparator.<Object>comparingLong(this::conversationActivity).reversed())
                    .thenComparing(o -> o instanceof User u ? u.getDisplayName() : ((Group)o).getName(), String.CASE_INSENSITIVE_ORDER));

            ListView<Object> list = new ListView<>();
            list.getStyleClass().add("clean-list");
            list.setItems(FXCollections.observableArrayList(conversations));
            list.setCellFactory(v -> new ListCell<>() {
                @Override protected void updateItem(Object item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) setGraphic(null);
                    else if (item instanceof User u) setGraphic(userCell(u));
                    else setGraphic(groupCell((Group)item));
                }
            });
            list.setOnMouseClicked(e -> {
                Object item = list.getSelectionModel().getSelectedItem();
                if (item instanceof User u) {
                    selectedUser = u; selectedGroup = null; loadDirect(u);
                } else if (item instanceof Group g) {
                    selectedGroup = g; selectedUser = null; loadGroup(g);
                }
            });
            VBox.setVgrow(list, Priority.ALWAYS);
            leftContent.getChildren().addAll(actions, list);
            return;
        }

        if (mode == Mode.GROUPS) {
            Button create = new Button("＋ Nouveau groupe");
            create.getStyleClass().add("primary-button");
            create.setMaxWidth(Double.MAX_VALUE);
            create.setOnAction(e -> createGroup());

            ListView<Group> list = new ListView<>();
            list.getStyleClass().add("clean-list");
            list.setItems(FXCollections.observableArrayList(allGroups.stream()
                    .filter(g -> !app.isArchivedGroup(g.getId()))
                    .filter(g -> g.getName().toLowerCase().contains(q))
                    .sorted(Comparator.<Group>comparingInt(g -> app.isPinnedGroup(g.getId()) ? 0 : 1)
                            .thenComparing(Group::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList()));
            list.setCellFactory(v -> new ListCell<>() {
                @Override protected void updateItem(Group g, boolean empty) {
                    super.updateItem(g, empty);
                    setGraphic(empty || g == null ? null : groupCell(g));
                }
            });
            list.setOnMouseClicked(e -> {
                Group g = list.getSelectionModel().getSelectedItem();
                if (g != null) {
                    selectedGroup = g;
                    selectedUser = null;
                    loadGroup(g);
                }
            });
            VBox.setVgrow(list, Priority.ALWAYS);
            leftContent.getChildren().addAll(create, list);
            return;
        }

        HBox actions = new HBox(8);
        Button text = new Button("✎ Texte");
        Button gallery = new Button("▧ Galerie");
        text.getStyleClass().add("primary-button");
        gallery.getStyleClass().add("soft-button");
        text.setOnAction(e -> createTextStatus());
        gallery.setOnAction(e -> createGalleryStatus());
        actions.getChildren().addAll(text, gallery);

        List<StatusOwner> owners = statusOwners().stream()
                .filter(o -> o.name().toLowerCase().contains(q))
                .toList();
        StatusOwner mine = owners.stream().filter(o -> o.id() == me.getId()).findFirst().orElse(null);
        List<StatusOwner> recent = owners.stream()
                .filter(o -> o.id() != me.getId())
                .filter(this::hasUnseenStatus)
                .toList();
        List<StatusOwner> viewed = owners.stream()
                .filter(o -> o.id() != me.getId())
                .filter(o -> !hasUnseenStatus(o))
                .toList();

        VBox statusSections = new VBox(9);
        statusSections.setFillWidth(true);
        if (mine != null) {
            statusSections.getChildren().add(statusSectionTitle("MON STATUT"));
            statusSections.getChildren().add(statusOwnerList(List.of(mine), false));
        } else {
            Label emptyMine = new Label("Ton statut • Aucun statut publié");
            emptyMine.getStyleClass().add("status-empty-hint");
            statusSections.getChildren().addAll(statusSectionTitle("MON STATUT"), emptyMine);
        }

        statusSections.getChildren().add(statusSectionTitle("MISES À JOUR RÉCENTES"));
        if (recent.isEmpty()) {
            Label none = new Label("Aucune nouvelle mise à jour.\nLes statuts de tes contacts apparaîtront ici.");
            none.getStyleClass().add("status-empty-hint");
            none.setWrapText(true);
            statusSections.getChildren().add(none);
        } else statusSections.getChildren().add(statusOwnerList(recent, true));

        if (!viewed.isEmpty()) {
            statusSections.getChildren().add(statusSectionTitle("MISES À JOUR VUES"));
            statusSections.getChildren().add(statusOwnerList(viewed, true));
        }

        ScrollPane statusScroll = new ScrollPane(statusSections);
        statusScroll.setFitToWidth(true);
        statusScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        statusScroll.getStyleClass().add("status-list-scroll");
        VBox.setVgrow(statusScroll, Priority.ALWAYS);
        leftContent.getChildren().addAll(actions, statusScroll);
    }

    private Label statusSectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status-section-title");
        return label;
    }

    private boolean hasUnseenStatus(StatusOwner owner) {
        return owner.statuses().stream().anyMatch(s -> !s.getViewedByMe());
    }

    private int firstUnseenStatusIndex(StatusOwner owner) {
        for (int i = 0; i < owner.statuses().size(); i++) {
            if (!owner.statuses().get(i).getViewedByMe()) return i;
        }
        return 0;
    }

    private ListView<StatusOwner> statusOwnerList(List<StatusOwner> owners, boolean contacts) {
        ListView<StatusOwner> list = new ListView<>(FXCollections.observableArrayList(owners));
        list.getStyleClass().add("clean-list");
        list.setFixedCellSize(68);
        list.setPrefHeight(Math.max(70, Math.min(330, owners.size() * 68 + 4)));
        list.setMinHeight(Math.min(70, list.getPrefHeight()));
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(StatusOwner o, boolean empty) {
                super.updateItem(o, empty);
                setGraphic(empty || o == null ? null : statusOwnerCell(o));
            }
        });
        list.setOnMouseClicked(e -> {
            StatusOwner o = list.getSelectionModel().getSelectedItem();
            if (o != null) showStatusOwner(o, contacts ? firstUnseenStatusIndex(o) : 0);
        });
        return list;
    }

    private Node userCell(User u) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox texts = new VBox(2);
        Label name = new Label(u.getDisplayName());
        name.getStyleClass().add("item-title");
        boolean isContact = allContacts.stream().anyMatch(c -> c.getId() == u.getId());
        boolean isBlocked = blockedIds.contains(u.getId());
        String latest = recentUserPreview.get(u.getId());
        long activity = recentUserActivity.getOrDefault(u.getId(), 0L);
        String stateText = latest != null ? latest + (activity > 0 ? " • " + fmtTime(activity) : "")
                : (isBlocked ? "⛔ Bloqué" : (u.getOnline() ? "● En ligne" : "Vu " + fmtLastSeen(u.getLastSeen())));
        Label state = new Label(stateText);
        state.getStyleClass().add(isBlocked ? "blocked-state" : (u.getOnline() ? "online" : "muted-small"));
        Label contactTag = new Label(isContact ? "contact" : "");
        contactTag.getStyleClass().add("contact-tag");
        HBox sub = new HBox(6, state, contactTag);
        sub.setAlignment(Pos.CENTER_LEFT);
        texts.getChildren().addAll(name, sub);
        HBox.setHgrow(texts, Priority.ALWAYS);
        Region spacer=new Region(); HBox.setHgrow(spacer,Priority.ALWAYS);
        row.getChildren().addAll(avatarNode(u), texts, spacer);
        if (app.isPinnedUser(u.getId())) { Label pin = new Label("📌"); pin.getStyleClass().add("conversation-indicator"); row.getChildren().add(pin); }
        if (app.isMutedUser(u.getId())) { Label mute = new Label("🔕"); mute.getStyleClass().add("conversation-indicator"); row.getChildren().add(mute); }
        int unread=unreadUsers.getOrDefault(u.getId(),0);
        if(unread>0){Label badge=new Label(unread>99?"99+":String.valueOf(unread));badge.getStyleClass().add("unread-badge");row.getChildren().add(badge);}
        ContextMenu menu = conversationContextMenu(u, null);
        row.setOnContextMenuRequested(e -> menu.show(row, e.getScreenX(), e.getScreenY()));
        return row;
    }

    private Node groupCell(Group g) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox tx = new VBox(2);
        Label name = new Label(g.getName());
        name.getStyleClass().add("item-title");
        String latest = recentGroupPreview.get(g.getId());
        long activity = recentGroupActivity.getOrDefault(g.getId(), 0L);
        Label meta = new Label(latest != null ? latest + (activity > 0 ? " • " + fmtTime(activity) : "")
                : g.getMemberCount() + " membres • " + prettyRole(g.getCurrentUserRole()));
        meta.getStyleClass().add("muted-small");
        tx.getChildren().addAll(name, meta);
        HBox.setHgrow(tx,Priority.ALWAYS);
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        row.getChildren().addAll(groupAvatarNode(g), tx, spacer);
        if (app.isPinnedGroup(g.getId())) { Label pin = new Label("📌"); pin.getStyleClass().add("conversation-indicator"); row.getChildren().add(pin); }
        if (app.isMutedGroup(g.getId())) { Label mute = new Label("🔕"); mute.getStyleClass().add("conversation-indicator"); row.getChildren().add(mute); }
        int unread=unreadGroups.getOrDefault(g.getId(),0);
        if(unread>0){Label badge=new Label(unread>99?"99+":String.valueOf(unread));badge.getStyleClass().add("unread-badge");row.getChildren().add(badge);}
        ContextMenu menu = conversationContextMenu(null, g);
        row.setOnContextMenuRequested(e -> menu.show(row, e.getScreenX(), e.getScreenY()));
        return row;
    }

    private Node statusOwnerCell(StatusOwner o) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        User ownerUser = o.id() == me.getId() ? me : findUser(o.id());
        Node avatar = avatarNode(ownerUser == null ? User.newBuilder().setDisplayName(o.name()).build() : ownerUser);
        avatar.getStyleClass().add(hasUnseenStatus(o) && o.id() != me.getId() ? "status-ring" : "status-ring-viewed");
        Label name = new Label((o.id() == me.getId() ? "Mon statut" : o.name()));
        name.getStyleClass().add("item-title");
        long newest = o.statuses().stream().mapToLong(StatusUpdate::getCreatedAt).max().orElse(0);
        String suffix = hasUnseenStatus(o) && o.id() != me.getId() ? " • Nouveau" : "";
        Label count = new Label(o.statuses().size() + " mise(s) à jour • " + fmtTime(newest) + suffix);
        count.getStyleClass().add("muted-small");
        row.getChildren().addAll(avatar, new VBox(2, name, count));
        return row;
    }

    private void loadDirect(User user) {
        resetRecording();
        try {
            selectedUser = allUsers.stream().filter(u -> u.getId() == user.getId()).findFirst().orElse(user);
            openedUnreadCount = unreadUsers.getOrDefault(selectedUser.getId(), 0);
            unreadUsers.remove(selectedUser.getId());
            if(mode==Mode.CHATS) renderList();
            currentMessages = new ArrayList<>(api.directMessages(user.getId()));
            conversationTitle = new Label(selectedUser.getDisplayName());
            conversationSub = new Label(selectedUser.getOnline() ? "● En ligne" : "Vu " + fmtLastSeen(selectedUser.getLastSeen()));
            buildChat();
            if(app.isFocused()) markVisibleRead();
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void loadGroup(Group group) {
        resetRecording();
        try {
            selectedGroup = allGroups.stream().filter(g -> g.getId() == group.getId()).findFirst().orElse(group);
            openedUnreadCount = unreadGroups.getOrDefault(selectedGroup.getId(), 0);
            unreadGroups.remove(selectedGroup.getId());
            if(mode==Mode.GROUPS) renderList();
            currentMessages = new ArrayList<>(api.groupMessages(group.getId()));
            List<User> members = api.groupMembers(group.getId());
            List<String> online = members.stream().filter(User::getOnline).filter(u -> u.getId() != me.getId()).map(User::getDisplayName).toList();
            conversationTitle = new Label(selectedGroup.getName());
            String who = online.isEmpty() ? "Personne d'autre en ligne"
                    : String.join(", ", online.stream().limit(3).toList()) + (online.size() > 3 ? " +" + (online.size() - 3) : "") + " en ligne";
            conversationSub = new Label(selectedGroup.getMemberCount() + " membres • " + who);
            buildChat();
            if(app.isFocused()) markVisibleRead();
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void buildChat() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("chat-header");
        conversationTitle.getStyleClass().add("chat-title");
        conversationSub.getStyleClass().add(selectedUser != null && selectedUser.getOnline() ? "online" : "muted-small");
        VBox names = new VBox(2, conversationTitle, conversationSub);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Node headAvatar = selectedUser != null ? avatarNode(selectedUser) : groupAvatarNode(selectedGroup);
        header.getChildren().addAll(headAvatar, names, spacer);
        conversationSearch = new TextField();
        conversationSearch.setPromptText("🔎 Rechercher dans le chat");
        conversationSearch.setPrefWidth(205);
        conversationSearch.getStyleClass().add("chat-search");
        conversationSearch.textProperty().addListener((obs, old, value) -> renderMessageList());
        header.getChildren().add(conversationSearch);

        if (selectedUser != null) {
            Button audioCall = new Button("📞");
            audioCall.getStyleClass().add("icon-button");
            audioCall.setTooltip(new Tooltip("Appel audio"));
            audioCall.setDisable(!selectedUser.getOnline() || blockedIds.contains(selectedUser.getId()));
            audioCall.setOnAction(e -> calls.startOutgoing(selectedUser, false));

            Button videoCall = new Button("🎥");
            videoCall.getStyleClass().add("icon-button");
            videoCall.setTooltip(new Tooltip("Appel vidéo"));
            videoCall.setDisable(!selectedUser.getOnline() || blockedIds.contains(selectedUser.getId()));
            videoCall.setOnAction(e -> calls.startOutgoing(selectedUser, true));
            header.getChildren().addAll(audioCall, videoCall);
        }
        else if (selectedGroup != null) {
            Button audioCall = new Button("📞");
            audioCall.getStyleClass().add("icon-button");
            audioCall.setTooltip(new Tooltip("Lancer un appel audio de groupe"));
            audioCall.setOnAction(e -> calls.startGroupOutgoing(selectedGroup, false));

            Button videoCall = new Button("🎥");
            videoCall.getStyleClass().add("icon-button");
            videoCall.setTooltip(new Tooltip("Lancer un appel vidéo de groupe"));
            videoCall.setOnAction(e -> calls.startGroupOutgoing(selectedGroup, true));
            header.getChildren().addAll(audioCall, videoCall);
        }

        MenuButton tools = new MenuButton("☰");
        tools.getStyleClass().add("icon-button");
        tools.setTooltip(new Tooltip("Outils de la conversation"));
        MenuItem mediaGallery = new MenuItem("▧ Médias, liens et fichiers");
        mediaGallery.setOnAction(e -> showSharedMedia());
        MenuItem exportChat = new MenuItem("⇩ Exporter la conversation");
        exportChat.setOnAction(e -> exportConversation());
        MenuItem archiveChat = new MenuItem("▣ Archiver cette conversation");
        archiveChat.setOnAction(e -> {
            if (selectedUser != null) app.toggleArchivedUser(selectedUser.getId());
            else if (selectedGroup != null) app.toggleArchivedGroup(selectedGroup.getId());
            selectedUser = null;
            selectedGroup = null;
            renderList();
            showWelcome();
            toast("Conversation archivée • retrouve-la dans Archives");
        });
        MenuItem clearMine = new MenuItem("⌫ Effacer la conversation pour moi");
        clearMine.setOnAction(e -> clearConversationForMe());
        tools.getItems().addAll(mediaGallery, exportChat, archiveChat, new SeparatorMenuItem(), clearMine);
        header.getChildren().add(tools);

        Button menu = new Button("⋯");
        menu.getStyleClass().add("icon-button");
        menu.setOnAction(e -> {
            if (selectedGroup != null) showGroupMenu(menu); else showProfileOf(selectedUser);
        });
        header.getChildren().add(menu);
        center.setTop(header);

        messagesBox = new VBox(8);
        messagesBox.setPadding(new Insets(20));
        messagesBox.getStyleClass().add("messages-layer");
        ScrollPane scroll = new ScrollPane(messagesBox);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("messages-scroll");
        renderMessageList();
        center.setCenter(scroll);
        Platform.runLater(() -> scroll.setVvalue(1.0));

        replyBanner = new Label();
        replyBanner.getStyleClass().add("reply-banner");
        replyBanner.setVisible(false);
        replyBanner.setManaged(false);

        typingLabel = new Label();
        typingLabel.getStyleClass().add("typing-label");
        typingLabel.setVisible(false);
        typingLabel.setManaged(false);

        composer = new TextField();
        boolean blockedConversation = selectedUser != null && blockedIds.contains(selectedUser.getId());
        composer.setPromptText(blockedConversation ? "Débloque ce contact pour envoyer un message" : "Écrire un message...");
        composer.setDisable(blockedConversation);
        HBox.setHgrow(composer, Priority.ALWAYS);
        composer.textProperty().addListener((obs, old, value) -> typingPulse());
        composer.setOnAction(e -> {
            if (recorder.isRecording() || pendingAudio.length > 0) stopAndSendRecordedAudio();
            else sendText();
        });

        Button emoji = new Button("☺");
        emoji.getStyleClass().add("icon-button");
        emoji.setOnAction(e -> emojiMenu(emoji));
        Button attach = new Button("＋");
        attach.getStyleClass().add("icon-button");
        attach.setTooltip(new Tooltip("Image, vidéo, audio ou fichier"));
        attach.setOnAction(e -> attachFile());
        if (blockedConversation) attach.setDisable(true);
        micButton = new Button("🎙");
        micButton.getStyleClass().add("icon-button");
        micButton.setTooltip(new Tooltip("Enregistrer un audio"));
        micButton.setOnAction(e -> toggleRecord());
        if (blockedConversation) micButton.setDisable(true);
        Button send = new Button("➤");
        send.getStyleClass().add("send-button");
        send.setOnAction(e -> sendText());
        if (blockedConversation) send.setDisable(true);

        HBox bar = new HBox(8, attach, emoji, composer, micButton, send);
        composerBar = bar;
        bar.setAlignment(Pos.CENTER);
        bar.getStyleClass().add("composer");

        audioPreview = new HBox(8);
        audioPreview.setAlignment(Pos.CENTER_LEFT);
        audioPreview.getStyleClass().add("audio-preview");
        audioPreview.setVisible(false);
        audioPreview.setManaged(false);
        audioPreviewLabel = new Label();
        audioPreviewLabel.getStyleClass().add("item-title");

        VBox bottom = new VBox(4, replyBanner, typingLabel, audioPreview, bar);
        center.setBottom(bottom);
    }

    private void renderMessageList() {
        if (messagesBox == null) return;
        messagesBox.getChildren().clear();
        String q = conversationSearch == null || conversationSearch.getText() == null ? "" : conversationSearch.getText().trim().toLowerCase();
        List<ChatMessage> visible = currentMessages;
        if (!q.isBlank()) {
            visible = currentMessages.stream().filter(m -> (m.getContent()+" "+m.getFileName()+" "+m.getSenderName()).toLowerCase().contains(q)).toList();
        }
        List<ChatMessage> pinned = visible.stream().filter(ChatMessage::getPinned).toList();
        if (!pinned.isEmpty()) {
            Label title = new Label("📌 Messages épinglés");
            title.getStyleClass().add("pinned-section-title");
            messagesBox.getChildren().add(title);
        }
        int unreadStart = -1;
        if (q.isBlank() && openedUnreadCount > 0) {
            int remaining = openedUnreadCount;
            for (int i = visible.size() - 1; i >= 0 && remaining > 0; i--) {
                ChatMessage candidate = visible.get(i);
                if (candidate.getSenderId() != me.getId() && !candidate.getDeleted()) {
                    unreadStart = i;
                    remaining--;
                }
            }
        }
        for (int i = 0; i < visible.size(); i++) {
            if (i == unreadStart) {
                HBox unreadDivider = new HBox(10);
                unreadDivider.setAlignment(Pos.CENTER);
                Region leftLine = new Region();
                Region rightLine = new Region();
                leftLine.getStyleClass().add("unread-divider-line");
                rightLine.getStyleClass().add("unread-divider-line");
                HBox.setHgrow(leftLine, Priority.ALWAYS);
                HBox.setHgrow(rightLine, Priority.ALWAYS);
                Label unreadLabel = new Label(openedUnreadCount == 1 ? "1 message non lu" : openedUnreadCount + " messages non lus");
                unreadLabel.getStyleClass().add("unread-divider-label");
                unreadDivider.getChildren().addAll(leftLine, unreadLabel, rightLine);
                messagesBox.getChildren().add(unreadDivider);
            }
            messagesBox.getChildren().add(messageBubble(visible.get(i)));
        }
        if (visible.isEmpty() && !q.isBlank()) {
            Label empty = new Label("Aucun message ne correspond à « " + q + " »");
            empty.getStyleClass().add("muted");
            messagesBox.getChildren().add(empty);
        }
    }

    private Node messageBubble(ChatMessage m) {
        boolean mine = m.getSenderId() == me.getId();
        boolean callLog = isCallLog(m);
        VBox bubble = new VBox(4);
        bubble.getStyleClass().addAll("message-bubble", mine ? "message-mine" : "message-other");
        bubble.setMaxWidth(560);

        if (selectedGroup != null && !mine && !m.getDeleted()) {
            Label who = new Label(m.getSenderName());
            who.getStyleClass().add("sender-name");
            bubble.getChildren().add(who);
        }
        if (m.getReplyToId() > 0) {
            ChatMessage replied = currentMessages.stream().filter(x -> x.getId() == m.getReplyToId()).findFirst().orElse(null);
            Label quote = new Label("↪ " + (replied == null ? "Message" : replied.getDeleted() ? "Message supprimé" : shortText(replied)));
            quote.getStyleClass().add("quoted-message");
            quote.setWrapText(true);
            bubble.getChildren().add(quote);
        }

        if (m.getDeleted()) {
            Label deleted = new Label("⊘ Message supprimé");
            deleted.getStyleClass().add("muted");
            bubble.getChildren().add(deleted);
        } else {
            switch (m.getType()) {
                case MESSAGE_IMAGE -> bubble.getChildren().add(imageNode(m));
                case MESSAGE_AUDIO -> bubble.getChildren().add(mediaButton("▶ Écouter l'audio", m, false));
                case MESSAGE_VIDEO -> bubble.getChildren().add(mediaButton("▶ Lire la vidéo", m, true));
                case MESSAGE_FILE -> bubble.getChildren().add(fileButton(m));
                case MESSAGE_EMOJI -> {
                    Label l = new Label(m.getContent());
                    l.getStyleClass().add("emoji-message");
                    bubble.getChildren().add(l);
                }
                case MESSAGE_CALL_AUDIO, MESSAGE_CALL_VIDEO -> bubble.getChildren().add(callHistoryNode(m, mine));
                default -> {
                    String content = m.getContent();
                    if (content.startsWith("⟦STATUS:") && content.contains("⟧")) {
                        int end = content.indexOf('⟧');
                        long statusId = 0;
                        try { statusId = Long.parseLong(content.substring(8, end)); } catch (Exception ignored) {}
                        final long linkedStatusId = statusId;
                        String visibleText = content.substring(end + 1).trim();
                        VBox statusReply = new VBox(4);
                        statusReply.getStyleClass().add("status-message-link");
                        Label tag = new Label("◉  Réponse à un statut");
                        tag.getStyleClass().add("status-message-tag");
                        Label l = new Label(visibleText);
                        l.setWrapText(true);
                        l.getStyleClass().add("message-text");
                        StatusUpdate linked = allStatuses.stream().filter(st -> st.getId() == linkedStatusId).findFirst().orElse(null);
                        if (linked != null && linked.getType() == StatusType.STATUS_IMAGE && !linked.getData().isEmpty()) {
                            try {
                                ImageView preview = new ImageView(new Image(new ByteArrayInputStream(linked.getData().toByteArray())));
                                preview.setFitWidth(150); preview.setFitHeight(86); preview.setPreserveRatio(true);
                                preview.getStyleClass().add("status-reply-thumbnail");
                                statusReply.getChildren().add(preview);
                            } catch (Exception ignored) {}
                        } else if (linked != null && linked.getType() == StatusType.STATUS_TEXT && !linked.getContent().isBlank()) {
                            Label previewText = new Label(linked.getContent().length() > 80 ? linked.getContent().substring(0, 77) + "…" : linked.getContent());
                            previewText.setWrapText(true); previewText.getStyleClass().add("status-reply-preview-text");
                            statusReply.getChildren().add(previewText);
                        }
                        statusReply.getChildren().addAll(tag, l);
                        if (linkedStatusId > 0) {
                            statusReply.setOnMouseClicked(e -> openStatusFromMessage(linkedStatusId));
                            Tooltip.install(statusReply, new Tooltip("Ouvrir le statut s’il est encore disponible"));
                        }
                        bubble.getChildren().add(statusReply);
                    } else {
                        Label l = new Label(content);
                        l.setWrapText(true);
                        l.getStyleClass().add("message-text");
                        bubble.getChildren().add(l);
                    }
                }
            }
        }

        if (!m.getReactionsSummary().isBlank()) {
            Label reactions = new Label(m.getReactionsSummary());
            reactions.getStyleClass().add("reaction-line");
            bubble.getChildren().add(reactions);
        }

        String meta = (m.getPinned() ? "📌 " : "") + fmtTime(m.getCreatedAt()) + (m.getEdited() ? " • modifié" : "");
        Label time = new Label(meta);
        time.getStyleClass().add("message-meta");
        HBox metaRow=new HBox(5,time);
        metaRow.setAlignment(Pos.CENTER_RIGHT);
        if(mine && m.getGroupId()==0 && !callLog){
            String ticks = m.getRead() ? "✓✓" : (m.getDelivered() ? "✓✓" : "✓");
            Label receipt=new Label(ticks);
            receipt.getStyleClass().addAll("read-receipt",m.getRead()?"read-receipt-read":"read-receipt-pending");
            String tip = m.getRead() ? "Lu par le destinataire" : (m.getDelivered() ? "Livré sur l'appareil du destinataire" : "Envoyé au serveur");
            receipt.setTooltip(new Tooltip(tip));
            metaRow.getChildren().add(receipt);
        }
        bubble.getChildren().add(metaRow);

        if (!callLog) {
            ContextMenu menu = new ContextMenu();
            MenuItem reply = new MenuItem("↩ Répondre");
            reply.setOnAction(e -> setReply(m));
            MenuItem forward = new MenuItem("↗ Transférer");
            forward.setOnAction(e -> forwardMessage(m));
            MenuItem pin = new MenuItem(m.getPinned() ? "📌 Désépingler" : "📌 Épingler");
            pin.setOnAction(e -> pinMessage(m));
            menu.getItems().addAll(reply, forward, pin);
            if ((m.getType()==MessageType.MESSAGE_TEXT || m.getType()==MessageType.MESSAGE_EMOJI) && !m.getDeleted()) {
                MenuItem copy = new MenuItem("⧉ Copier");
                copy.setOnAction(e -> copyMessage(m));
                menu.getItems().add(copy);
            }
            Menu reactionMenu = new Menu("Réagir");
            for (String emoji : List.of("❤️", "👍", "😂", "😮", "🙏", "🔥")) {
                MenuItem item = new MenuItem(emoji);
                item.setOnAction(e -> { try { api.react(m.getId(), emoji); } catch (Exception ex) { app.error(ClientApp.friendly(ex)); } });
                reactionMenu.getItems().add(item);
            }
            menu.getItems().add(reactionMenu);
            if (mine && !m.getDeleted() && m.getType() == MessageType.MESSAGE_TEXT) {
                MenuItem edit = new MenuItem("✎ Modifier");
                edit.setOnAction(e -> editMessage(m));
                menu.getItems().add(edit);
            }
            MenuItem delete = new MenuItem("🗑 Supprimer…");
            delete.setOnAction(e -> deleteMessage(m));
            menu.getItems().add(delete);
            bubble.setOnContextMenuRequested(e -> menu.show(bubble, e.getScreenX(), e.getScreenY()));
        }

        HBox row = new HBox(bubble);
        row.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return row;
    }

    private boolean isCallLog(ChatMessage m) {
        return m != null && (m.getType() == MessageType.MESSAGE_CALL_AUDIO || m.getType() == MessageType.MESSAGE_CALL_VIDEO);
    }

    private Node callHistoryNode(ChatMessage m, boolean mine) {
        boolean video = m.getType() == MessageType.MESSAGE_CALL_VIDEO;
        String icon = video ? "🎥" : "📞";
        boolean groupCall = m.getGroupId() > 0;
        String kind = video ? (groupCall ? "Appel vidéo de groupe" : "Appel vidéo") : (groupCall ? "Appel audio de groupe" : "Appel audio");
        String status = m.getCallStatus() == null ? "" : m.getCallStatus();
        String text;
        boolean missed = false;
        switch (status) {
            case "RINGING" -> text = groupCall ? kind + " • invitation en cours" : (mine ? kind + " sortant • sonnerie…" : kind + " entrant");
            case "ONGOING" -> text = kind + " • en cours";
            case "COMPLETED" -> text = groupCall ? kind + (m.getCallDurationSeconds() > 0 ? " • " + formatCallDuration(m.getCallDurationSeconds()) : "")
                    : (mine ? kind + " sortant" : kind + " entrant") + (m.getCallDurationSeconds() > 0 ? " • " + formatCallDuration(m.getCallDurationSeconds()) : "");
            case "REJECTED" -> text = kind + " refusé";
            case "BUSY" -> text = kind + " • occupé";
            case "NO_ANSWER" -> { text = groupCall ? kind + " • personne n'a rejoint" : (mine ? kind + " • sans réponse" : kind + " manqué"); missed = !groupCall && !mine; }
            case "UNAVAILABLE" -> { text = mine ? kind + " • indisponible" : kind + " manqué"; missed = !mine; }
            default -> text = kind;
        }
        Label iconLabel = new Label(icon);
        iconLabel.getStyleClass().add("call-history-icon");
        Label title = new Label(text);
        title.getStyleClass().add("call-history-title");
        if (missed) title.getStyleClass().add("call-history-missed");
        Label detail = new Label(groupCall ? m.getSenderName() + " a lancé l'appel dans le groupe" : (mine ? "Vous avez appelé" : m.getSenderName() + " vous a appelé"));
        detail.getStyleClass().add("muted-small");
        VBox texts = new VBox(1, title, detail);
        HBox row = new HBox(10, iconLabel, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("call-history");
        return row;
    }

    private static String formatCallDuration(long seconds) {
        long s = Math.max(0, seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    private Node imageNode(ChatMessage m) {
        try {
            ImageView iv = new ImageView(new Image(new ByteArrayInputStream(m.getData().toByteArray())));
            iv.setFitWidth(280);
            iv.setFitHeight(240);
            iv.setPreserveRatio(true);
            iv.getStyleClass().add("chat-image");
            iv.setOnMouseClicked(e -> {
                try { MediaViewer.openImage(m.getFileName(), m.getData().toByteArray()); }
                catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
            });
            return iv;
        } catch (Exception e) {
            return new Label("Image indisponible");
        }
    }

    private Node mediaButton(String text, ChatMessage m, boolean video) {
        Button b = new Button(text + " • " + m.getFileName());
        b.getStyleClass().add("media-button");
        b.setOnAction(e -> {
            try { MediaViewer.open(m.getFileName(), m.getMimeType(), m.getData().toByteArray(), video); }
            catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        });
        return b;
    }

    private Node fileButton(ChatMessage m) {
        Button b = new Button("⇩ Fichier : " + m.getFileName());
        b.getStyleClass().add("media-button");
        b.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName(m.getFileName());
            File file = chooser.showSaveDialog(app.stage());
            if (file != null) {
                try {
                    Files.write(file.toPath(), m.getData().toByteArray());
                    app.info("Fichier enregistré.");
                } catch (Exception ex) {
                    app.error(ex.getMessage());
                }
            }
        });
        return b;
    }


    private void showSharedMedia() {
        List<ChatMessage> media = currentMessages.stream()
                .filter(m -> !m.getDeleted())
                .filter(m -> m.getType()==MessageType.MESSAGE_IMAGE || m.getType()==MessageType.MESSAGE_VIDEO
                        || m.getType()==MessageType.MESSAGE_AUDIO || m.getType()==MessageType.MESSAGE_FILE)
                .toList();
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(app.stage());
        dialog.setTitle("Médias partagés");
        VBox box = new VBox(8);
        box.setPadding(new Insets(8));
        if (media.isEmpty()) box.getChildren().add(new Label("Aucun média ou fichier dans cette conversation."));
        for (ChatMessage m : media) {
            String icon = switch (m.getType()) {
                case MESSAGE_IMAGE -> "🖼";
                case MESSAGE_VIDEO -> "🎬";
                case MESSAGE_AUDIO -> "🎙";
                default -> "📎";
            };
            Label title = new Label(icon + "  " + (m.getFileName().isBlank()?m.getType().name():m.getFileName()));
            title.getStyleClass().add("item-title");
            Label meta = new Label(m.getSenderName()+" • "+fmtTime(m.getCreatedAt()));
            meta.getStyleClass().add("muted-small");
            VBox text = new VBox(2,title,meta);
            HBox.setHgrow(text,Priority.ALWAYS);
            Button open = new Button(m.getType()==MessageType.MESSAGE_FILE?"Enregistrer":"Ouvrir");
            open.getStyleClass().add("soft-button");
            open.setOnAction(e -> {
                try {
                    if (m.getType()==MessageType.MESSAGE_IMAGE) MediaViewer.openImage(m.getFileName(),m.getData().toByteArray());
                    else if (m.getType()==MessageType.MESSAGE_FILE) {
                        FileChooser chooser=new FileChooser();chooser.setInitialFileName(m.getFileName());File out=chooser.showSaveDialog(app.stage());if(out!=null)Files.write(out.toPath(),m.getData().toByteArray());
                    } else MediaViewer.open(m.getFileName(),m.getMimeType(),m.getData().toByteArray(),m.getType()==MessageType.MESSAGE_VIDEO);
                } catch(Exception ex) { app.error(ClientApp.friendly(ex)); }
            });
            HBox row = new HBox(10,text,open);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("media-gallery-row");
            box.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(box);scroll.setFitToWidth(true);scroll.setPrefSize(650,460);
        dialog.getDialogPane().setContent(scroll);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);dialog.showAndWait();
    }

    private void exportConversation() {
        if (currentMessages.isEmpty()) { app.info("Cette conversation ne contient encore aucun message."); return; }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Exporter la conversation Tuyage");
        String who = selectedUser!=null?selectedUser.getDisplayName():(selectedGroup!=null?selectedGroup.getName():"conversation");
        String safe = who.replaceAll("[^\\p{L}\\p{N}._-]+","_");
        chooser.setInitialFileName("Tuyage-"+safe+".txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Texte","*.txt"));
        File file=chooser.showSaveDialog(app.stage());if(file==null)return;
        StringBuilder out=new StringBuilder();
        out.append("Tuyage 3.0 FINAL TP\nConversation : ").append(who).append("\nExport : ").append(java.time.LocalDateTime.now()).append("\n\n");
        DateTimeFormatter full=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for(ChatMessage m:currentMessages){
            String when=Instant.ofEpochMilli(m.getCreatedAt()).atZone(ZoneId.systemDefault()).format(full);
            String body;
            if(m.getDeleted()) body="[message supprimé]";
            else if(isCallLog(m)) body="["+(m.getType()==MessageType.MESSAGE_CALL_VIDEO?"appel vidéo":"appel audio")+" • "+m.getCallStatus()+" • "+formatCallDuration(m.getCallDurationSeconds())+"]";
            else if(!m.getContent().isBlank()) body=m.getContent();
            else body="["+m.getType().name()+"] "+m.getFileName();
            out.append('[').append(when).append("] ").append(m.getSenderName()).append(" : ").append(body).append('\n');
        }
        try { Files.writeString(file.toPath(),out.toString(),java.nio.charset.StandardCharsets.UTF_8);toast("Conversation exportée"); }
        catch(Exception ex){app.error("Export impossible : "+ex.getMessage());}
    }

    private void clearConversationForMe() {
        if (currentMessages.isEmpty()) return;
        Alert alert=new Alert(Alert.AlertType.CONFIRMATION,"Tous les messages de cette conversation seront masqués uniquement pour toi. Continuer ?",ButtonType.YES,ButtonType.NO);
        alert.initOwner(app.stage());alert.setTitle("Effacer la conversation");
        if(alert.showAndWait().orElse(ButtonType.NO)!=ButtonType.YES)return;
        int hidden=0;
        for(ChatMessage m:new ArrayList<>(currentMessages)){
            try { api.deleteForMe(m.getId()); hidden++; } catch(Exception ignored) { }
        }
        reloadCurrentConversation();
        toast(hidden+" élément(s) effacé(s) pour toi");
    }

    private void sendText() {
        if (composer == null) return;
        String text = composer.getText().trim();
        if (text.isEmpty()) return;
        try {
            MessageType type = text.codePointCount(0, text.length()) <= 3 && text.matches("[^\\p{L}\\p{N}]+")
                    ? MessageType.MESSAGE_EMOJI : MessageType.MESSAGE_TEXT;
            api.send(selectedUser == null ? 0 : selectedUser.getId(), selectedGroup == null ? 0 : selectedGroup.getId(),
                    type, text, "", "", new byte[0], replyToId);
            composer.clear();
            clearReply();
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void attachFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Envoyer une image, vidéo, audio ou fichier");
        File file = chooser.showOpenDialog(app.stage());
        if (file == null) return;
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            if (data.length > 20 * 1024 * 1024) {
                app.error("Maximum 20 Mo pour ce TP.");
                return;
            }
            String mime = Files.probeContentType(file.toPath());
            if (mime == null) mime = guessMime(file.getName());
            MessageType type = mime.startsWith("image/") ? MessageType.MESSAGE_IMAGE
                    : mime.startsWith("audio/") ? MessageType.MESSAGE_AUDIO
                    : mime.startsWith("video/") ? MessageType.MESSAGE_VIDEO : MessageType.MESSAGE_FILE;
            api.send(selectedUser == null ? 0 : selectedUser.getId(), selectedGroup == null ? 0 : selectedGroup.getId(),
                    type, "", file.getName(), mime, data, replyToId);
            clearReply();
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void toggleRecord() {
        if (!recorder.isRecording()) startRecording();
        else stopRecordingToPreview();
    }

    private void startRecording() {
        try {
            clearPendingAudio();
            recorder.start();
            try { api.activity(selectedUser == null ? 0 : selectedUser.getId(), selectedGroup == null ? 0 : selectedGroup.getId(), true, "recording"); } catch (Exception ignored) {}
            recordingStartedAt = System.currentTimeMillis();
            micButton.setText("■");
            micButton.setTooltip(new Tooltip("Arrêter l'enregistrement"));
            micButton.getStyleClass().add("recording");
            showRecordingUi();
            recordingTimer = new Timeline(new KeyFrame(Duration.millis(250), e -> updateRecordingLabel()));
            recordingTimer.setCycleCount(Timeline.INDEFINITE);
            recordingTimer.play();
            Platform.runLater(this::requestFocus);
        } catch (Exception ex) {
            recorder.cancel();
            stopRecordingTimer();
            restoreComposerAfterRecording();
            app.error("Microphone : " + ClientApp.friendly(ex));
        }
    }

    private void showRecordingUi() {
        if (audioPreview == null) return;
        audioPreview.getChildren().clear();
        audioPreview.getStyleClass().remove("audio-ready");
        if (!audioPreview.getStyleClass().contains("audio-recording")) audioPreview.getStyleClass().add("audio-recording");
        Label red = new Label("●");
        red.getStyleClass().add("recording-dot");
        audioPreviewLabel.setText("Enregistrement • 00:00   •   Entrée = envoyer");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button stop = new Button("■ Arrêter");
        Button cancel = new Button("Annuler");
        stop.setOnAction(e -> stopRecordingToPreview());
        cancel.setOnAction(e -> cancelRecording());
        audioPreview.getChildren().addAll(red, audioPreviewLabel, spacer, stop, cancel);
        audioPreview.setVisible(true);
        audioPreview.setManaged(true);
        if (composerBar != null) {
            composerBar.setVisible(false);
            composerBar.setManaged(false);
        }
    }

    private void updateRecordingLabel() {
        if (!recorder.isRecording() || audioPreviewLabel == null) return;
        long seconds = Math.max(0, (System.currentTimeMillis() - recordingStartedAt) / 1000);
        audioPreviewLabel.setText(String.format("Enregistrement • %02d:%02d   •   Entrée = envoyer", seconds / 60, seconds % 60));
    }

    private void stopRecordingToPreview() {
        if (!recorder.isRecording()) return;
        try {
            byte[] data = recorder.stop();
            try { api.activity(selectedUser == null ? 0 : selectedUser.getId(), selectedGroup == null ? 0 : selectedGroup.getId(), false, "recording"); } catch (Exception ignored) {}
            stopRecordingTimer();
            restoreMicButton();
            if (data.length == 0) {
                restoreComposerAfterRecording();
                app.error("Aucun son n'a été enregistré.");
                return;
            }
            pendingAudio = data;
            showAudioReadyUi();
        } catch (Exception ex) {
            recorder.cancel();
            stopRecordingTimer();
            restoreMicButton();
            restoreComposerAfterRecording();
            app.error("Microphone : " + ClientApp.friendly(ex));
        }
    }

    private void stopAndSendRecordedAudio() {
        if (!recorder.isRecording()) {
            if (pendingAudio.length > 0) sendRecordedAudio();
            return;
        }
        try {
            byte[] data = recorder.stop();
            try { api.activity(selectedUser == null ? 0 : selectedUser.getId(), selectedGroup == null ? 0 : selectedGroup.getId(), false, "recording"); } catch (Exception ignored) {}
            stopRecordingTimer();
            restoreMicButton();
            restoreComposerAfterRecording();
            if (data.length == 0) {
                app.error("Aucun son n'a été enregistré.");
                return;
            }
            pendingAudio = data;
            sendRecordedAudio();
        } catch (Exception ex) {
            recorder.cancel();
            stopRecordingTimer();
            restoreMicButton();
            restoreComposerAfterRecording();
            app.error("Microphone : " + ClientApp.friendly(ex));
        }
    }

    private void showAudioReadyUi() {
        if (audioPreview == null) return;
        audioPreview.getChildren().clear();
        audioPreview.getStyleClass().remove("audio-recording");
        if (!audioPreview.getStyleClass().contains("audio-ready")) audioPreview.getStyleClass().add("audio-ready");
        audioPreviewLabel.setText("Audio prêt • " + audioDuration(pendingAudio) + "   •   Entrée = envoyer");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button listen = new Button("▶ Écouter");
        Button audioSend = new Button("Envoyer");
        Button cancel = new Button("Annuler");
        audioSend.getStyleClass().add("primary-button");
        listen.setOnAction(e -> previewRecordedAudio());
        audioSend.setOnAction(e -> sendRecordedAudio());
        cancel.setOnAction(e -> clearPendingAudio());
        audioPreview.getChildren().addAll(new Label("🎙"), audioPreviewLabel, spacer, listen, audioSend, cancel);
        audioPreview.setVisible(true);
        audioPreview.setManaged(true);
        restoreComposerAfterRecording();
    }

    private void previewRecordedAudio() {
        if (pendingAudio.length == 0) return;
        try { MediaViewer.open("aperçu-audio.wav", "audio/wav", pendingAudio, false); }
        catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
    }

    private void sendRecordedAudio() {
        if (pendingAudio.length == 0) return;
        try {
            api.send(selectedUser == null ? 0 : selectedUser.getId(), selectedGroup == null ? 0 : selectedGroup.getId(),
                    MessageType.MESSAGE_AUDIO, "", "audio-" + System.currentTimeMillis() + ".wav", "audio/wav", pendingAudio, replyToId);
            clearPendingAudio();
            clearReply();
            toast("Audio envoyé");
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void cancelRecording() {
        try { api.activity(selectedUser == null ? 0 : selectedUser.getId(), selectedGroup == null ? 0 : selectedGroup.getId(), false, "recording"); } catch (Exception ignored) {}
        recorder.cancel();
        stopRecordingTimer();
        pendingAudio = new byte[0];
        restoreMicButton();
        clearPendingAudio();
        toast("Enregistrement annulé");
    }

    private void stopRecordingTimer() {
        if (recordingTimer != null) {
            recordingTimer.stop();
            recordingTimer = null;
        }
    }

    private void restoreMicButton() {
        if (micButton != null) {
            micButton.setText("🎙");
            micButton.setTooltip(new Tooltip("Enregistrer un audio"));
            micButton.getStyleClass().remove("recording");
        }
    }

    private void restoreComposerAfterRecording() {
        if (composerBar != null) {
            composerBar.setVisible(true);
            composerBar.setManaged(true);
        }
    }

    private void clearPendingAudio() {
        pendingAudio = new byte[0];
        if (audioPreview != null) {
            audioPreview.setVisible(false);
            audioPreview.setManaged(false);
            audioPreview.getChildren().clear();
            audioPreview.getStyleClass().removeAll("audio-recording", "audio-ready");
        }
        restoreComposerAfterRecording();
    }

    private void resetRecording() {
        if (recorder.isRecording()) recorder.cancel();
        stopRecordingTimer();
        clearPendingAudio();
        restoreMicButton();
    }

    private void chooseChatBackground() {
        ButtonType choose = new ButtonType("Choisir une image", ButtonBar.ButtonData.OK_DONE);
        ButtonType reset = new ButtonType("Aucun fond", ButtonBar.ButtonData.OTHER);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.initOwner(app.stage());
        dialog.setTitle("Tuyage • Fond de discussion");
        dialog.setHeaderText("Personnaliser le fond des conversations");
        dialog.setContentText("Par défaut, Tuyage garde un fond propre sans photo. Tu peux choisir une image personnelle quand tu veux.");
        dialog.getButtonTypes().setAll(choose, reset, ButtonType.CANCEL);
        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result == reset) {
            app.saveChatBackground("");
            applyChatBackground();
            toast("Fond sans image restauré");
            return;
        }
        if (result != choose) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choisir le fond de discussion");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        File file = chooser.showOpenDialog(app.stage());
        if (file == null) return;
        try {
            new Image(file.toURI().toString(), false);
            app.saveChatBackground(file.getAbsolutePath());
            applyChatBackground();
            toast("Nouveau fond appliqué");
        } catch (Exception ex) {
            app.error("Cette image ne peut pas être utilisée comme fond.");
        }
    }

    private void applyChatBackground() {
        String custom = app.chatBackground();
        File f = custom == null || custom.isBlank() ? null : new File(custom);
        if (f == null || !f.isFile()) {
            // Fond neutre par défaut : aucune image derrière l'écran d'accueil ou les messages.
            center.setStyle("");
            return;
        }
        String safe = f.toURI().toString().replace("\\", "/").replace("'", "%27");
        center.setStyle("-fx-background-image: url('" + safe + "');"
                + "-fx-background-size: cover;"
                + "-fx-background-position: center center;"
                + "-fx-background-repeat: no-repeat;");
    }

    private void emojiMenu(Button owner) {
        ContextMenu menu = new ContextMenu();
        for (String emoji : List.of("😀", "😂", "😍", "🥳", "😎", "😭", "🙏", "❤️", "🔥", "👍", "🎉", "✨")) {
            MenuItem item = new MenuItem(emoji);
            item.setOnAction(e -> {
                composer.appendText(emoji);
                composer.requestFocus();
            });
            menu.getItems().add(item);
        }
        menu.show(owner, javafx.geometry.Side.TOP, 0, 0);
    }

    private void setReply(ChatMessage m) {
        replyToId = m.getId();
        replyBanner.setText("↪ Réponse à " + m.getSenderName() + " : " + shortText(m) + "   ✕");
        replyBanner.setVisible(true);
        replyBanner.setManaged(true);
        replyBanner.setOnMouseClicked(e -> clearReply());
        composer.requestFocus();
    }

    private void clearReply() {
        replyToId = 0;
        if (replyBanner != null) {
            replyBanner.setVisible(false);
            replyBanner.setManaged(false);
        }
    }

    private void editMessage(ChatMessage m) {
        TextInputDialog dialog = new TextInputDialog(m.getContent());
        dialog.initOwner(app.stage());
        dialog.setTitle("Modifier le message");
        dialog.setHeaderText("Modifier le contenu");
        dialog.showAndWait().ifPresent(value -> {
            if (!value.trim().isEmpty()) {
                try { api.edit(m.getId(), value.trim()); }
                catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
            }
        });
    }

    private void deleteMessage(ChatMessage m) {
        ButtonType mine = new ButtonType("Supprimer pour moi", ButtonBar.ButtonData.OTHER);
        ButtonType everyone = new ButtonType("Supprimer pour tout le monde", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(app.stage());
        alert.setTitle("Supprimer le message");
        alert.setHeaderText("Que veux-tu faire ?");
        if (m.getSenderId() == me.getId() && !m.getDeleted()) alert.getButtonTypes().setAll(mine, everyone, ButtonType.CANCEL);
        else alert.getButtonTypes().setAll(mine, ButtonType.CANCEL);
        ButtonType result = alert.showAndWait().orElse(ButtonType.CANCEL);
        try {
            if (result == mine) {
                api.deleteForMe(m.getId());
                reloadCurrentConversation();
                toast("Message supprimé pour toi");
            } else if (result == everyone) {
                api.deleteForEveryone(m.getId());
            }
        } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
    }

    private void copyMessage(ChatMessage m) {
        String text = m.getContent();
        if (text == null || text.isBlank()) text = m.getFileName();
        if (text == null || text.isBlank()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        toast("Copié");
    }

    private void pinMessage(ChatMessage m) {
        try {
            api.pin(m.getId(), !m.getPinned());
            reloadCurrentConversation();
            toast(m.getPinned() ? "Message désépinglé" : "Message épinglé");
        } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
    }

    private void forwardMessage(ChatMessage m) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(app.stage());
        dialog.setTitle("Transférer");
        dialog.setHeaderText("Choisis un destinataire ou un groupe");
        ListView<String> list = new ListView<>();
        Map<String,Object> targets = new LinkedHashMap<>();
        for (User u : allUsers) targets.put("👤 " + u.getDisplayName() + " • " + u.getUsername(), u);
        for (Group g : allGroups) targets.put("👥 " + g.getName(), g);
        list.setItems(FXCollections.observableArrayList(targets.keySet()));
        list.setPrefSize(470,330);
        dialog.getDialogPane().setContent(list);
        ButtonType send = new ButtonType("Transférer", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(send, ButtonType.CANCEL);
        dialog.showAndWait().filter(x -> x == send).ifPresent(x -> {
            String key = list.getSelectionModel().getSelectedItem();
            Object target = key == null ? null : targets.get(key);
            if (target == null) { app.error("Choisis un destinataire."); return; }
            try {
                long userId = target instanceof User u ? u.getId() : 0;
                long groupId = target instanceof Group g ? g.getId() : 0;
                String content = m.getDeleted() ? "" : m.getContent();
                api.send(userId, groupId, m.getType(), content, m.getFileName(), m.getMimeType(), m.getData().toByteArray(), 0);
                toast("Message transféré");
            } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        });
    }

    private void reloadCurrentConversation() {
        if (selectedUser != null) loadDirect(selectedUser);
        else if (selectedGroup != null) loadGroup(selectedGroup);
    }

    private void typingPulse() {
        if (selectedUser == null && selectedGroup == null) return;
        long userId = selectedUser == null ? 0 : selectedUser.getId();
        long groupId = selectedGroup == null ? 0 : selectedGroup.getId();
        try { api.activity(userId, groupId, true, "typing"); }
        catch (Exception ignored) {}
        typingPause.stop();
        typingPause.setOnFinished(e -> {
            try { api.activity(userId, groupId, false, "typing"); }
            catch (Exception ignored) {}
        });
        typingPause.playFromStart();
    }

    private ContextMenu conversationContextMenu(User user, Group group) {
        ContextMenu menu = new ContextMenu();
        boolean isUser = user != null;
        long id = isUser ? user.getId() : group.getId();
        boolean pinned = isUser ? app.isPinnedUser(id) : app.isPinnedGroup(id);
        boolean muted = isUser ? app.isMutedUser(id) : app.isMutedGroup(id);
        boolean archived = isUser ? app.isArchivedUser(id) : app.isArchivedGroup(id);
        MenuItem pin = new MenuItem(pinned ? "Désépingler la conversation" : "📌 Épingler la conversation");
        MenuItem mute = new MenuItem(muted ? "🔔 Réactiver les notifications" : "🔕 Mettre en sourdine");
        MenuItem archive = new MenuItem(archived ? "Restaurer des archives" : "▣ Archiver");
        pin.setOnAction(e -> { if (isUser) app.togglePinnedUser(id); else app.togglePinnedGroup(id); renderList(); });
        mute.setOnAction(e -> { if (isUser) app.toggleMutedUser(id); else app.toggleMutedGroup(id); renderList(); toast(muted ? "Notifications réactivées" : "Conversation en sourdine"); });
        archive.setOnAction(e -> {
            if (isUser) app.toggleArchivedUser(id); else app.toggleArchivedGroup(id);
            if (!archived) {
                if (isUser && selectedUser != null && selectedUser.getId() == id) selectedUser = null;
                if (!isUser && selectedGroup != null && selectedGroup.getId() == id) selectedGroup = null;
                showWelcome();
            }
            renderList();
        });
        menu.getItems().addAll(pin, mute, archive);
        return menu;
    }

    private void showArchivedDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(app.stage());
        dialog.setTitle("Conversations archivées");
        VBox box = new VBox(8);
        List<User> users = allUsers.stream().filter(u -> app.isArchivedUser(u.getId())).toList();
        List<Group> groups = allGroups.stream().filter(g -> app.isArchivedGroup(g.getId())).toList();
        if (users.isEmpty() && groups.isEmpty()) box.getChildren().add(new Label("Aucune conversation archivée."));
        for (User u : users) {
            HBox row = new HBox(8, avatarNode(u), new Label(u.getDisplayName() + " • " + u.getUsername())); row.setAlignment(Pos.CENTER_LEFT);
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); Button restore = new Button("Restaurer");
            restore.setOnAction(e -> { app.toggleArchivedUser(u.getId()); dialog.close(); renderList(); });
            row.getChildren().addAll(spacer, restore); box.getChildren().add(row);
        }
        for (Group g : groups) {
            HBox row = new HBox(8, groupAvatarNode(g), new Label("Groupe • " + g.getName())); row.setAlignment(Pos.CENTER_LEFT);
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); Button restore = new Button("Restaurer");
            restore.setOnAction(e -> { app.toggleArchivedGroup(g.getId()); dialog.close(); renderList(); });
            row.getChildren().addAll(spacer, restore); box.getChildren().add(row);
        }
        ScrollPane sc = new ScrollPane(box); sc.setFitToWidth(true); sc.setPrefSize(520, 380);
        dialog.getDialogPane().setContent(sc); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.showAndWait();
    }

    private void addContactByPhone() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(app.stage());
        dialog.setTitle("Ajouter un contact Tuyage");
        dialog.setHeaderText("Entre le numéro de téléphone du contact");
        dialog.setContentText("Numéro :");
        dialog.showAndWait().ifPresent(phone -> {
            if (phone.trim().isBlank()) return;
            try {
                User added = api.addContact(phone.trim());
                refreshContactsAndBlocks();
                renderList();
                toast(added.getDisplayName() + " ajouté aux contacts");
            } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        });
    }

    private void showContactsDialog() {
        refreshContactsAndBlocks();
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(app.stage());
        dialog.setTitle("Mes contacts Tuyage");
        VBox box = new VBox(8);
        if (allContacts.isEmpty()) box.getChildren().add(new Label("Aucun contact enregistré. Utilise + Contact."));
        for (User u : allContacts) {
            HBox row = new HBox(8, avatarNode(u)); row.setAlignment(Pos.CENTER_LEFT);
            VBox txt = new VBox(2, new Label(u.getDisplayName()), new Label(u.getUsername()));
            txt.getChildren().get(1).getStyleClass().add("muted-small");
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
            Button remove = new Button("Retirer");
            remove.setOnAction(e -> { try { api.removeContact(u.getId()); refreshContactsAndBlocks(); dialog.close(); renderList(); } catch(Exception ex){app.error(ClientApp.friendly(ex));} });
            row.getChildren().addAll(txt, spacer, remove); box.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(box); scroll.setFitToWidth(true); scroll.setPrefSize(500,380);
        dialog.getDialogPane().setContent(scroll); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.showAndWait();
    }

    private void showBlockedDialog() {
        refreshContactsAndBlocks();
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(app.stage()); dialog.setTitle("Contacts bloqués");
        VBox box = new VBox(8);
        List<User> blocked = allUsers.stream().filter(u -> blockedIds.contains(u.getId())).toList();
        if (blocked.isEmpty()) box.getChildren().add(new Label("Aucun contact bloqué."));
        for (User u : blocked) {
            HBox row = new HBox(8, avatarNode(u), new Label(u.getDisplayName() + " • " + u.getUsername())); row.setAlignment(Pos.CENTER_LEFT);
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); Button unblock = new Button("Débloquer");
            unblock.setOnAction(e -> { try { api.unblock(u.getId()); refreshContactsAndBlocks(); dialog.close(); renderList(); } catch(Exception ex){app.error(ClientApp.friendly(ex));} });
            row.getChildren().addAll(spacer, unblock); box.getChildren().add(row);
        }
        dialog.getDialogPane().setContent(box); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.showAndWait();
    }

    private void refreshContactsAndBlocks() {
        try { allContacts = new ArrayList<>(api.contacts()); } catch(Exception ignored) {}
        try { blockedIds = api.blocked().stream().map(User::getId).collect(Collectors.toSet()); } catch(Exception ignored) {}
    }

    private void markVisibleRead() {
        if (selectedUser == null && selectedGroup == null) return;
        for (ChatMessage m : currentMessages) {
            boolean shouldRead = selectedGroup != null
                    ? m.getGroupId()==selectedGroup.getId() && m.getSenderId()!=me.getId()
                    : m.getRecipientUserId()==me.getId() && !m.getRead();
            if (shouldRead) { try { api.markRead(m.getId()); } catch (Exception ignored) {} }
        }
        if(selectedUser!=null) unreadUsers.remove(selectedUser.getId());
        if(selectedGroup!=null) unreadGroups.remove(selectedGroup.getId());
    }

    private void createGroup() {
        try {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(app.stage());
            dialog.setTitle("Nouveau groupe");
            dialog.setHeaderText("Créer un espace de discussion");

            TextField name = new TextField();
            name.setPromptText("Nom du groupe");
            TextArea description = new TextArea();
            description.setPromptText("Description");
            description.setPrefRowCount(2);

            final byte[][] avatar = { new byte[0] };
            Label photoName = new Label("Aucune photo de groupe");
            photoName.getStyleClass().add("muted-small");
            Button choosePhoto = new Button("Choisir une photo");
            choosePhoto.setOnAction(e -> {
                FileChooser chooser = new FileChooser();
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
                File file = chooser.showOpenDialog(app.stage());
                if (file != null) {
                    try {
                        byte[] bytes = Files.readAllBytes(file.toPath());
                        if (bytes.length > 5 * 1024 * 1024) { app.error("Photo de groupe : maximum 5 Mo."); return; }
                        avatar[0] = bytes;
                        photoName.setText(file.getName());
                    } catch (Exception ex) { app.error(ex.getMessage()); }
                }
            });

            VBox checks = new VBox(6);
            List<CheckBox> boxes = new ArrayList<>();
            for (User u : allUsers) {
                CheckBox cb = new CheckBox(u.getDisplayName() + "  • " + u.getUsername() + (u.getOnline() ? "  • en ligne" : ""));
                cb.setUserData(u.getId());
                boxes.add(cb);
                checks.getChildren().add(cb);
            }
            ScrollPane memberScroll = new ScrollPane(checks);
            memberScroll.setFitToWidth(true);
            memberScroll.setPrefHeight(220);

            VBox content = new VBox(10,
                    new Label("Nom"), name,
                    new Label("Description"), description,
                    new HBox(8, choosePhoto, photoName),
                    new Label("Membres"), memberScroll);
            dialog.getDialogPane().setContent(content);
            ButtonType create = new ButtonType("Créer", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);

            dialog.showAndWait().filter(x -> x == create).ifPresent(x -> {
                try {
                    String groupName = name.getText().trim();
                    if (groupName.length() < 2) { app.error("Le nom du groupe doit contenir au moins 2 caractères."); return; }
                    List<Long> ids = boxes.stream().filter(CheckBox::isSelected).map(cb -> (Long) cb.getUserData()).toList();
                    if (ids.isEmpty()) { app.error("Sélectionne au moins un membre pour créer le groupe."); return; }
                    Group created = api.createGroup(groupName, description.getText().trim(), avatar[0], ids);
                    refreshGroups();
                    selectedGroup = allGroups.stream().filter(g -> g.getId() == created.getId()).findFirst().orElse(created);
                    selectedUser = null;
                    mode = Mode.GROUPS;
                    renderList();
                    loadGroup(selectedGroup);
                    toast("Groupe créé avec succès");
                } catch (Exception ex) {
                    app.error(ClientApp.friendly(ex));
                }
            });
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void showGroupMenu(Button owner) {
        ContextMenu menu = new ContextMenu();
        MenuItem members = new MenuItem("Voir les membres et rôles");
        members.setOnAction(e -> showMembers());
        MenuItem edit = new MenuItem("✎ Modifier le groupe");
        edit.setOnAction(e -> editGroup());
        MenuItem add = new MenuItem("＋ Ajouter un membre");
        add.setOnAction(e -> addMember());
        MenuItem leave = new MenuItem("Quitter le groupe");
        leave.setOnAction(e -> leaveGroup());
        MenuItem delete = new MenuItem("Supprimer le groupe");
        delete.setOnAction(e -> deleteGroup());
        menu.getItems().add(members);
        boolean manager = selectedGroup != null && (selectedGroup.getCurrentUserRole().equals("OWNER") || selectedGroup.getCurrentUserRole().equals("ADMIN"));
        if (manager) menu.getItems().addAll(edit, add);
        if (selectedGroup != null && !selectedGroup.getCurrentUserRole().equals("OWNER")) menu.getItems().add(leave);
        if (selectedGroup != null && selectedGroup.getCurrentUserRole().equals("OWNER")) menu.getItems().add(delete);
        menu.show(owner, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void showMembers() {
        try {
            List<GroupMember> members = api.groupMembersDetailed(selectedGroup.getId());
            Dialog<Void> dialog = new Dialog<>();
            dialog.initOwner(app.stage());
            dialog.setTitle("Membres - " + selectedGroup.getName());
            VBox box = new VBox(8);
            boolean owner = "OWNER".equals(selectedGroup.getCurrentUserRole());
            boolean manager = owner || "ADMIN".equals(selectedGroup.getCurrentUserRole());
            for (GroupMember gm : members) {
                User u = gm.getUser(); String role = gm.getRole();
                HBox row = new HBox(8); row.setAlignment(Pos.CENTER_LEFT);
                String roleText = "OWNER".equals(role) ? "propriétaire" : ("ADMIN".equals(role) ? "admin" : "membre");
                Label name = new Label((u.getOnline() ? "● " : "○ ") + u.getDisplayName() + "  • " + u.getUsername() + "  • " + roleText);
                if (u.getOnline()) name.getStyleClass().add("online");
                Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
                row.getChildren().addAll(avatarNode(u), name, spacer);
                if (owner && u.getId() != me.getId() && !"OWNER".equals(role)) {
                    Button roleButton = new Button("ADMIN".equals(role) ? "Retirer admin" : "Nommer admin");
                    roleButton.getStyleClass().add("soft-button");
                    roleButton.setOnAction(e -> {
                        try { api.setGroupRole(selectedGroup.getId(),u.getId(),"ADMIN".equals(role)?"MEMBER":"ADMIN"); dialog.close(); refreshGroups(); showMembers(); }
                        catch(Exception ex){app.error(ClientApp.friendly(ex));}
                    });
                    row.getChildren().add(roleButton);
                }
                if (manager && u.getId() != selectedGroup.getOwnerId() && u.getId()!=me.getId()
                        && (owner || !"ADMIN".equals(role))) {
                    Button remove = new Button("Retirer");
                    remove.setOnAction(e -> {
                        try { api.removeMember(selectedGroup.getId(), u.getId()); dialog.close(); refreshGroups(); reloadCurrentConversation(); }
                        catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
                    });
                    row.getChildren().add(remove);
                }
                box.getChildren().add(row);
            }
            ScrollPane scroll = new ScrollPane(box); scroll.setFitToWidth(true); scroll.setPrefSize(650, 450);
            dialog.getDialogPane().setContent(scroll); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.showAndWait();
        } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
    }

    private void editGroup() {
        if (selectedGroup == null) return;
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(app.stage()); dialog.setTitle("Modifier le groupe");
        TextField name = new TextField(selectedGroup.getName());
        TextArea description = new TextArea(selectedGroup.getDescription()); description.setPrefRowCount(3);
        final byte[][] avatar = {new byte[0]}; final boolean[] replace = {false};
        Label info = new Label(selectedGroup.getAvatar().isEmpty()?"Aucune photo":"Photo actuelle conservée"); info.getStyleClass().add("muted-small");
        Button choose = new Button("Changer la photo");
        choose.setOnAction(e -> { FileChooser fc=new FileChooser();fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images","*.png","*.jpg","*.jpeg"));File f=fc.showOpenDialog(app.stage());if(f!=null)try{avatar[0]=Files.readAllBytes(f.toPath());replace[0]=true;info.setText(f.getName());}catch(Exception ex){app.error(ex.getMessage());}});
        Button remove = new Button("Retirer la photo"); remove.setOnAction(e->{avatar[0]=new byte[0];replace[0]=true;info.setText("Photo retirée");});
        dialog.getDialogPane().setContent(new VBox(10,new Label("Nom"),name,new Label("Description"),description,new HBox(8,choose,remove),info));
        ButtonType save=new ButtonType("Enregistrer",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
        dialog.showAndWait().filter(x->x==save).ifPresent(x->{try{Group g=api.updateGroup(selectedGroup.getId(),name.getText().trim(),description.getText().trim(),avatar[0],replace[0]);refreshGroups();selectedGroup=g;loadGroup(g);toast("Groupe modifié");}catch(Exception ex){app.error(ClientApp.friendly(ex));}});
    }

    private void addMember() {
        try {
            Set<Long> current = api.groupMembers(selectedGroup.getId()).stream().map(User::getId).collect(Collectors.toSet());
            List<User> candidates = allUsers.stream().filter(u -> !current.contains(u.getId())).toList();
            if (candidates.isEmpty()) { app.info("Tous les utilisateurs sont déjà dans le groupe."); return; }
            Map<String, User> byLabel = new LinkedHashMap<>();
            for (User u : candidates) byLabel.put(u.getDisplayName() + " • " + u.getUsername(), u);
            List<String> labels = new ArrayList<>(byLabel.keySet());
            ChoiceDialog<String> dialog = new ChoiceDialog<>(labels.get(0), labels);
            dialog.initOwner(app.stage());
            dialog.setTitle("Ajouter un membre");
            dialog.setHeaderText("Choisir un utilisateur");
            dialog.showAndWait().ifPresent(label -> {
                User u = byLabel.get(label);
                if (u != null) {
                    try {
                        api.addMember(selectedGroup.getId(), u.getId());
                        refreshGroups();
                        loadGroup(selectedGroup);
                    } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
                }
            });
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void leaveGroup() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Quitter ce groupe ?", ButtonType.YES, ButtonType.NO);
        alert.initOwner(app.stage());
        if (alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
            try {
                api.leaveGroup(selectedGroup.getId());
                selectedGroup = null;
                refreshGroups();
                showGroups();
            } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        }
    }

    private void deleteGroup() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Supprimer définitivement ce groupe ?", ButtonType.YES, ButtonType.NO);
        alert.initOwner(app.stage());
        if (alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
            try {
                api.deleteGroup(selectedGroup.getId());
                selectedGroup = null;
                refreshGroups();
                showGroups();
            } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        }
    }

    private void showStatusHome() {
        closeStatusOverlay();
        stopStatusTimeline();
        statusViewerOpen = false;
        activeStatusOwner = null;
        activeStatusIndex = 0;
        VBox v = new VBox(14);
        v.setAlignment(Pos.CENTER);
        Label icon = new Label("◉");
        icon.getStyleClass().add("welcome-mark");
        Label title = new Label("Statuts 24 heures");
        title.getStyleClass().add("welcome-title");
        Label sub = new Label("Publie un texte ou choisis une image/vidéo dans ta galerie.\nTes propres statuts peuvent être modifiés ou supprimés.");
        sub.getStyleClass().add("muted");
        sub.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        v.getChildren().addAll(icon, title, sub);
        center.setTop(null);
        center.setBottom(null);
        center.setCenter(v);
    }

    private List<StatusOwner> statusOwners() {
        Map<Long, List<StatusUpdate>> map = new LinkedHashMap<>();
        for (StatusUpdate s : allStatuses) map.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(s);
        map.values().forEach(list -> list.sort(Comparator.comparingLong(StatusUpdate::getCreatedAt)));
        List<StatusOwner> out = new ArrayList<>();
        if (map.containsKey(me.getId())) out.add(new StatusOwner(me.getId(), me.getDisplayName(), map.remove(me.getId())));
        List<StatusOwner> contacts = new ArrayList<>();
        for (Map.Entry<Long, List<StatusUpdate>> entry : map.entrySet()) {
            contacts.add(new StatusOwner(entry.getKey(), entry.getValue().get(0).getUserName(), entry.getValue()));
        }
        contacts.sort(Comparator.comparingLong((StatusOwner o) -> o.statuses().stream().mapToLong(StatusUpdate::getCreatedAt).max().orElse(0)).reversed());
        out.addAll(contacts);
        return out;
    }

    private void createTextStatus() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(app.stage());
        dialog.setTitle("Nouveau statut texte");
        dialog.setHeaderText("Écris ton statut (verset, pensée, annonce...)");
        dialog.setContentText("Texte :");
        dialog.showAndWait().ifPresent(text -> {
            if (text.trim().isEmpty()) return;
            ChoiceDialog<String> bg = new ChoiceDialog<>("aurora", List.of("aurora", "ocean", "rose", "night"));
            bg.initOwner(app.stage());
            bg.setTitle("Fond du statut");
            bg.setHeaderText("Choisir un fond");
            bg.showAndWait().ifPresent(value -> {
                try {
                    api.createTextStatus(text.trim(), value);
                    refreshStatuses();
                    mode = Mode.STATUS;
                    renderList();
                    showStatusHome();
                } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
            });
        });
    }

    private void createGalleryStatus() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Galerie - image ou vidéo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images & vidéos", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.mp4", "*.m4v", "*.mov"));
        File file = chooser.showOpenDialog(app.stage());
        if (file == null) return;
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            if (data.length > 20 * 1024 * 1024) { app.error("Maximum 20 Mo pour ce TP."); return; }
            String mime = Files.probeContentType(file.toPath());
            if (mime == null) mime = guessMime(file.getName());
            StatusType type = mime.startsWith("video/") ? StatusType.STATUS_VIDEO : StatusType.STATUS_IMAGE;
            api.createMediaStatus(type, file.getName(), mime, data);
            refreshStatuses();
            mode = Mode.STATUS;
            renderList();
            showStatusHome();
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void showStatusOwner(StatusOwner owner, int index) {
        stopStatusTimeline();
        if (owner == null || owner.statuses().isEmpty()) return;
        int idx = Math.max(0, Math.min(index, owner.statuses().size() - 1));
        statusViewerOpen = true;
        activeStatusOwner = owner;
        activeStatusIndex = idx;
        StatusUpdate status = owner.statuses().get(idx);
        if (owner.id() != me.getId()) {
            try {
                api.viewStatus(status.getId());
                refreshStatuses();
            } catch (Exception ignored) {}
        }

        HBox segments = new HBox(5);
        segments.setMaxWidth(Double.MAX_VALUE);
        ProgressBar currentProgress = null;
        for (int i = 0; i < owner.statuses().size(); i++) {
            ProgressBar bar = new ProgressBar(i < idx ? 1.0 : 0.0);
            bar.getStyleClass().add("status-progress");
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setMinHeight(6);
            bar.setPrefHeight(6);
            if (status.getType() == StatusType.STATUS_IMAGE) bar.getStyleClass().add("status-progress-image");
            HBox.setHgrow(bar, Priority.ALWAYS);
            segments.getChildren().add(bar);
            if (i == idx) currentProgress = bar;
        }

        VBox card = new VBox(12);
        card.getChildren().add(segments);
        card.getStyleClass().addAll("status-card", "status-" + status.getBackground());
        card.setPadding(new Insets(22));

        HBox identity = new HBox(9);
        identity.setAlignment(Pos.CENTER_LEFT);
        User ownerUser = owner.id() == me.getId() ? me : findUser(owner.id());
        Node avatar = avatarNode(ownerUser == null ? User.newBuilder().setDisplayName(owner.name()).build() : ownerUser);
        Label top = new Label(status.getUserName() + "\n" + fmtTime(status.getCreatedAt()));
        top.getStyleClass().add("status-author");
        Label position = new Label((idx + 1) + "/" + owner.statuses().size());
        position.getStyleClass().add("status-position");
        Region identitySpacer = new Region();
        HBox.setHgrow(identitySpacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("status-close-button");
        close.setOnAction(e -> {
            closeStatusOverlay();
            showStatusHome();
            renderList();
        });
        identity.getChildren().addAll(avatar, top, identitySpacer, position, close);
        card.getChildren().add(identity);

        if (status.getType() == StatusType.STATUS_TEXT) {
            Label text = new Label(status.getContent());
            text.getStyleClass().add("status-text");
            text.setWrapText(true);
            text.setMaxWidth(700);
            VBox.setVgrow(text, Priority.ALWAYS);
            card.getChildren().add(text);
            startTimedStatus(owner, idx, currentProgress, 7.0);
        } else if (status.getType() == StatusType.STATUS_IMAGE) {
            ImageView image = new ImageView(new Image(new ByteArrayInputStream(status.getData().toByteArray())));
            image.setFitWidth(720);
            image.setFitHeight(470);
            image.setPreserveRatio(true);
            VBox.setVgrow(image, Priority.ALWAYS);
            card.getChildren().add(image);
            startTimedStatus(owner, idx, currentProgress, 7.0);
        } else {
            startInlineStatusVideo(status, owner, idx, currentProgress, card);
        }

        card.setOnMouseClicked(e -> {
            if (isInsideInteractiveControl(e.getTarget())) return;
            if (e.getX() < card.getWidth() / 2.0) showPreviousStatus(owner, idx);
            else showNextStatus(owner, idx);
        });

        HBox navigation = new HBox(8);
        navigation.setAlignment(Pos.CENTER_LEFT);
        Button previous = new Button("‹");
        statusPauseButton = new Button("Ⅱ");
        Button next = new Button("›");
        previous.setOnAction(e -> showPreviousStatus(owner, idx));
        next.setOnAction(e -> showNextStatus(owner, idx));
        statusPauseButton.setOnAction(e -> toggleStatusPlayback(statusPauseButton));
        navigation.getChildren().addAll(previous, statusPauseButton, next);

        if (owner.id() == me.getId()) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            navigation.getChildren().add(spacer);
            if (status.getType() == StatusType.STATUS_TEXT) {
                Button edit = new Button("✎  Modifier");
                edit.setTooltip(new Tooltip("Modifier ce statut"));
                edit.getStyleClass().add("status-action-button");
                edit.setOnAction(e -> editStatus(status));
                navigation.getChildren().add(edit);
            }
            Button views = new Button("👁 " + status.getViewCount());
            views.setTooltip(new Tooltip("Vues"));
            views.getStyleClass().add("status-action-button");
            views.setOnAction(e -> showStatusViewers(status));
            Button delete = new Button("⌫  Supprimer");
            delete.setTooltip(new Tooltip("Supprimer ce statut"));
            delete.getStyleClass().addAll("status-action-button", "status-action-danger");
            delete.setOnAction(e -> deleteStatus(status));
            navigation.getChildren().addAll(views, delete);
        }

        TextField commentField = new TextField();
        commentField.setPromptText(owner.id() == me.getId() ? "Ajouter une note..." : "Répondre à ce statut...");
        Button commentButton = new Button("Envoyer");
        commentButton.setOnAction(e -> {
            if (!commentField.getText().trim().isEmpty()) {
                try {
                    String reply = commentField.getText().trim();
                    api.comment(status.getId(), reply);
                    if (owner.id() != me.getId()) {
                        api.send(owner.id(), 0, MessageType.MESSAGE_TEXT,
                                "⟦STATUS:" + status.getId() + "⟧ Réponse à votre statut\n" + reply, "", "", new byte[0], 0);
                        toast("Réponse envoyée dans la conversation");
                    }
                    commentField.clear();
                    refreshStatuses();
                } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
            }
        });
        commentField.setOnAction(e -> commentButton.fire());
        commentField.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) pauseStatusPlayback(); else resumeStatusPlayback();
        });
        HBox commentBar = new HBox(8, commentField, commentButton);
        HBox.setHgrow(commentField, Priority.ALWAYS);

        navigation.getStyleClass().add("status-controls");
        previous.getStyleClass().add("status-nav-arrow");
        next.getStyleClass().add("status-nav-arrow");
        statusPauseButton.getStyleClass().add("status-pause-button");
        commentBar.getStyleClass().add("status-reply-bar");

        VBox viewer = new VBox(12, card, navigation, commentBar);
        viewer.getStyleClass().add("status-fullscreen-viewer");
        viewer.setMaxWidth(900);
        viewer.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(card, Priority.ALWAYS);

        closeStatusOverlay();
        statusOverlay = new StackPane(viewer);
        statusOverlay.getStyleClass().add("status-overlay");
        statusOverlay.setManaged(false);
        statusOverlay.resizeRelocate(0, 0, getWidth(), getHeight());
        statusOverlay.prefWidthProperty().bind(widthProperty());
        statusOverlay.prefHeightProperty().bind(heightProperty());
        getChildren().add(statusOverlay);
        statusOverlay.toFront();
        Platform.runLater(card::requestFocus);
    }


    private boolean isInsideInteractiveControl(Object target) {
        if (!(target instanceof Node node)) return false;
        Node current = node;
        while (current != null) {
            if (current instanceof ButtonBase || current instanceof TextInputControl) return true;
            current = current.getParent();
        }
        return false;
    }

    private void startTimedStatus(StatusOwner owner, int idx, ProgressBar progress, double seconds) {
        final double[] elapsed = {0.0};
        statusTimeline = new Timeline(new KeyFrame(Duration.millis(50), e -> {
            elapsed[0] += 0.05;
            progress.setProgress(Math.min(1.0, elapsed[0] / seconds));
            if (elapsed[0] >= seconds) showNextStatus(owner, idx);
        }));
        statusTimeline.setCycleCount(Timeline.INDEFINITE);
        statusTimeline.play();
    }

    private void startInlineStatusVideo(StatusUpdate status, StatusOwner owner, int idx, ProgressBar progress, VBox card) {
        try {
            String suffix = status.getFileName() != null && status.getFileName().contains(".")
                    ? status.getFileName().substring(status.getFileName().lastIndexOf('.')) : ".mp4";
            statusMediaTemp = Files.createTempFile("tuyage-status-", suffix);
            Files.write(statusMediaTemp, status.getData().toByteArray());
            statusMediaTemp.toFile().deleteOnExit();
            statusMediaPlayer = new MediaPlayer(new Media(statusMediaTemp.toUri().toString()));
            MediaView mediaView = new MediaView(statusMediaPlayer);
            mediaView.setPreserveRatio(true);
            mediaView.setFitWidth(720);
            mediaView.setFitHeight(470);
            card.getChildren().add(mediaView);
            statusMediaPlayer.currentTimeProperty().addListener((obs, oldTime, now) -> {
                Duration total = statusMediaPlayer.getTotalDuration();
                if (total != null && !total.isUnknown() && total.toMillis() > 0) {
                    progress.setProgress(Math.min(1.0, now.toMillis() / total.toMillis()));
                }
            });
            statusMediaPlayer.setOnEndOfMedia(() -> Platform.runLater(() -> showNextStatus(owner, idx)));
            statusMediaPlayer.setOnError(() -> Platform.runLater(() -> {
                Label error = new Label("Vidéo non compatible avec le lecteur intégré.");
                error.getStyleClass().add("muted");
                Button external = new Button("▶ Ouvrir la vidéo");
                external.getStyleClass().add("primary-button");
                external.setOnAction(e -> MediaViewer.open(status.getFileName(), status.getMimeType(), status.getData().toByteArray(), true));
                card.getChildren().addAll(error, external);
                startTimedStatus(owner, idx, progress, 8.0);
            }));
            statusMediaPlayer.play();
        } catch (Exception ex) {
            Label error = new Label("Lecture intégrée impossible : " + ex.getMessage());
            error.getStyleClass().add("muted");
            Button external = new Button("▶ Ouvrir la vidéo");
            external.getStyleClass().add("primary-button");
            external.setOnAction(e -> {
                try { MediaViewer.open(status.getFileName(), status.getMimeType(), status.getData().toByteArray(), true); }
                catch (Exception mediaEx) { app.error(ClientApp.friendly(mediaEx)); }
            });
            card.getChildren().addAll(error, external);
            startTimedStatus(owner, idx, progress, 8.0);
        }
    }

    private void showNextStatus(StatusOwner owner, int idx) {
        stopStatusTimeline();
        StatusOwner fresh = statusOwners().stream().filter(o -> o.id() == owner.id()).findFirst().orElse(owner);
        if (idx + 1 < fresh.statuses().size()) {
            showStatusOwner(fresh, idx + 1);
            return;
        }
        if (owner.id() == me.getId()) {
            showStatusHome();
            renderList();
            return;
        }
        List<StatusOwner> contacts = statusOwners().stream().filter(o -> o.id() != me.getId()).toList();
        for (int i = 0; i < contacts.size(); i++) {
            if (contacts.get(i).id() == owner.id()) {
                if (i + 1 < contacts.size()) showStatusOwner(contacts.get(i + 1), firstUnseenStatusIndex(contacts.get(i + 1)));
                else { showStatusHome(); renderList(); }
                return;
            }
        }
        showStatusHome();
        renderList();
    }

    private void showPreviousStatus(StatusOwner owner, int idx) {
        stopStatusTimeline();
        StatusOwner fresh = statusOwners().stream().filter(o -> o.id() == owner.id()).findFirst().orElse(owner);
        if (idx > 0) {
            showStatusOwner(fresh, idx - 1);
            return;
        }
        if (owner.id() == me.getId()) return;
        List<StatusOwner> contacts = statusOwners().stream().filter(o -> o.id() != me.getId()).toList();
        for (int i = 0; i < contacts.size(); i++) {
            if (contacts.get(i).id() == owner.id() && i > 0) {
                StatusOwner previous = contacts.get(i - 1);
                showStatusOwner(previous, Math.max(0, previous.statuses().size() - 1));
                return;
            }
        }
    }

    private void toggleStatusPlayback(Button button) {
        boolean playing = statusMediaPlayer != null
                ? statusMediaPlayer.getStatus() == MediaPlayer.Status.PLAYING
                : statusTimeline != null && statusTimeline.getStatus() == Timeline.Status.RUNNING;
        if (playing) {
            pauseStatusPlayback();
            if (button != null) button.setText("▶");
        } else {
            resumeStatusPlayback();
            if (button != null) button.setText("Ⅱ");
        }
    }

    private void pauseStatusPlayback() {
        if (statusTimeline != null) statusTimeline.pause();
        if (statusMediaPlayer != null) statusMediaPlayer.pause();
    }

    private void resumeStatusPlayback() {
        if (statusTimeline != null) statusTimeline.play();
        if (statusMediaPlayer != null) statusMediaPlayer.play();
    }

    private void showStatusViewers(StatusUpdate status) {
        try {
            List<User> viewers = api.statusViewers(status.getId());
            Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(app.stage()); dialog.setTitle("Vues du statut");
            VBox box = new VBox(8);
            if (viewers.isEmpty()) box.getChildren().add(new Label("Personne n'a encore vu ce statut."));
            for (User u : viewers) {
                HBox row = new HBox(8, avatarNode(u), new VBox(2, new Label(u.getDisplayName()), new Label(u.getUsername())));
                row.setAlignment(Pos.CENTER_LEFT); box.getChildren().add(row);
            }
            ScrollPane sc = new ScrollPane(box); sc.setFitToWidth(true); sc.setPrefSize(460,340);
            dialog.getDialogPane().setContent(sc); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.showAndWait();
        } catch(Exception ex) { app.error(ClientApp.friendly(ex)); }
    }

    private void closeStatusOverlay() {
        if (statusOverlay != null) {
            getChildren().remove(statusOverlay);
            statusOverlay = null;
        }
    }

    private void openStatusFromMessage(long statusId) {
        try {
            refreshStatuses();
            for (StatusOwner owner : statusOwners()) {
                for (int i = 0; i < owner.statuses().size(); i++) {
                    if (owner.statuses().get(i).getId() == statusId) {
                        showStatusOwner(owner, i);
                        return;
                    }
                }
            }
            toast("Ce statut a expiré ou n’est plus disponible");
        } catch (Exception ex) {
            toast("Ce statut n’est plus disponible");
        }
    }

    private void stopStatusTimeline() {
        statusPauseButton = null;
        if (statusTimeline != null) {
            statusTimeline.stop();
            statusTimeline = null;
        }
        if (statusMediaPlayer != null) {
            try { statusMediaPlayer.stop(); statusMediaPlayer.dispose(); } catch (Exception ignored) {}
            statusMediaPlayer = null;
        }
        if (statusMediaTemp != null) {
            try { Files.deleteIfExists(statusMediaTemp); } catch (Exception ignored) {}
            statusMediaTemp = null;
        }
    }

    private void editStatus(StatusUpdate status) {
        TextInputDialog dialog = new TextInputDialog(status.getContent());
        dialog.initOwner(app.stage());
        dialog.setTitle("Modifier le statut");
        dialog.setHeaderText("Modifier le texte de ton statut");
        dialog.showAndWait().ifPresent(text -> {
            if (text.trim().isEmpty()) return;
            ChoiceDialog<String> background = new ChoiceDialog<>(status.getBackground(), List.of("aurora", "ocean", "rose", "night"));
            background.initOwner(app.stage());
            background.setTitle("Modifier le fond");
            background.setHeaderText("Choisir la couleur du fond");
            background.showAndWait().ifPresent(bg -> {
                try {
                    api.updateStatus(status.getId(), text.trim(), bg);
                    refreshStatuses();
                    StatusOwner owner = statusOwners().stream().filter(o -> o.id() == me.getId()).findFirst().orElse(null);
                    if (owner != null) {
                        int idx = 0;
                        for (int i = 0; i < owner.statuses().size(); i++) if (owner.statuses().get(i).getId() == status.getId()) idx = i;
                        showStatusOwner(owner, idx);
                    } else showStatusHome();
                } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
            });
        });
    }

    private void deleteStatus(StatusUpdate status) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Supprimer définitivement cette mise à jour ?", ButtonType.YES, ButtonType.NO);
        alert.initOwner(app.stage());
        alert.setTitle("Supprimer le statut");
        if (alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
            try {
                api.deleteStatus(status.getId());
                refreshStatuses();
                renderList();
                StatusOwner owner = statusOwners().stream().filter(o -> o.id() == me.getId()).findFirst().orElse(null);
                if (owner == null || owner.statuses().isEmpty()) showStatusHome();
                else showStatusOwner(owner, 0);
            } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        }
    }

    private void showSettingsDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(app.stage());
        dialog.setTitle("Réglages Tuyage");
        dialog.setHeaderText("Compte, conversations et apparence");

        Label hint = new Label("Les options secondaires sont regroupées ici pour garder l’écran principal simple.");
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");

        Button archives = new Button("▣  Archives");
        Button blocked = new Button("⛔  Utilisateurs bloqués");
        Button contacts = new Button("☏  Gérer mes contacts");
        Button wallpaper = new Button("▧  Fond des discussions");
        Button theme = new Button(app.isDark() ? "☀  Passer au thème clair" : "☾  Passer au thème sombre");
        Button account = new Button("☺  Profil et compte");
        Button password = new Button("🔒  Changer le mot de passe");

        for (Button b : List.of(archives, blocked, contacts, wallpaper, theme, account, password)) {
            b.getStyleClass().add("settings-action");
            b.setMaxWidth(Double.MAX_VALUE);
        }

        archives.setOnAction(e -> { dialog.close(); Platform.runLater(this::showArchivedDialog); });
        blocked.setOnAction(e -> { dialog.close(); Platform.runLater(this::showBlockedDialog); });
        contacts.setOnAction(e -> { dialog.close(); Platform.runLater(this::showContactsDialog); });
        wallpaper.setOnAction(e -> { dialog.close(); Platform.runLater(this::chooseChatBackground); });
        account.setOnAction(e -> { dialog.close(); Platform.runLater(this::showProfile); });
        password.setOnAction(e -> { dialog.close(); Platform.runLater(this::changePassword); });
        theme.setOnAction(e -> {
            app.toggleTheme();
            dialog.close();
            toast(app.isDark() ? "Thème sombre activé" : "Thème clair activé");
        });

        VBox conversations = new VBox(8, new Label("Conversations"), archives, blocked, contacts);
        VBox appearance = new VBox(8, new Label("Apparence"), wallpaper, theme);
        VBox security = new VBox(8, new Label("Compte et sécurité"), account, password);
        conversations.getStyleClass().add("settings-section");
        appearance.getStyleClass().add("settings-section");
        security.getStyleClass().add("settings-section");

        VBox content = new VBox(12, hint, conversations, appearance, security);
        content.setPrefWidth(430);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void showProfile() {
        try {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(app.stage());
            dialog.setTitle("Mon profil");
            dialog.setHeaderText("Modifier réellement mon profil");

            Label username = new Label(me.getUsername());
            username.getStyleClass().add("muted");
            TextField display = new TextField(me.getDisplayName());
            TextArea bio = new TextArea(me.getBio());
            bio.setPrefRowCount(3);

            final byte[][] avatar = { new byte[0] };
            final boolean[] replaceAvatar = { false };
            Label avatarInfo = new Label(me.getAvatar().isEmpty() ? "Aucune photo" : "Photo actuelle enregistrée");
            avatarInfo.getStyleClass().add("muted-small");

            Button choose = new Button("Choisir une nouvelle photo");
            choose.setOnAction(e -> {
                FileChooser chooser = new FileChooser();
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
                File file = chooser.showOpenDialog(app.stage());
                if (file != null) {
                    try {
                        byte[] bytes = Files.readAllBytes(file.toPath());
                        if (bytes.length > 5 * 1024 * 1024) { app.error("Photo de profil : maximum 5 Mo."); return; }
                        avatar[0] = bytes;
                        replaceAvatar[0] = true;
                        avatarInfo.setText("Nouvelle photo : " + file.getName());
                    } catch (Exception ex) { app.error(ex.getMessage()); }
                }
            });
            Button remove = new Button("Retirer ma photo");
            remove.setOnAction(e -> {
                avatar[0] = new byte[0];
                replaceAvatar[0] = true;
                avatarInfo.setText("La photo sera retirée");
            });

            Button password = new Button("Changer mon mot de passe");
            password.getStyleClass().add("soft-button");
            password.setOnAction(e -> changePassword());
            Button deleteAccount = new Button("Supprimer mon compte");
            deleteAccount.getStyleClass().add("danger-button");
            deleteAccount.setOnAction(e -> {
                dialog.close();
                deleteAccount();
            });

            HBox photoActions = new HBox(8, choose, remove);
            HBox accountActions = new HBox(8, password, deleteAccount);
            VBox content = new VBox(10,
                    new Label("Numéro de téléphone"), username,
                    new Label("Nom affiché"), display,
                    new Label("Bio / info"), bio,
                    new Label("Photo de profil"), photoActions, avatarInfo,
                    new Separator(),
                    new Label("Sécurité du compte"), accountActions,
                    new Label("Logo application : remplace src/main/resources/images/logo.png"));
            dialog.getDialogPane().setContent(content);
            ButtonType save = new ButtonType("Enregistrer les modifications", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

            dialog.showAndWait().filter(x -> x == save).ifPresent(x -> {
                try {
                    String name = display.getText().trim();
                    if (name.length() < 2) { app.error("Le nom affiché est trop court."); return; }
                    me = api.updateProfile(name, bio.getText().trim(), avatar[0], replaceAvatar[0]);
                    app.setCurrentUser(me);
                    refreshMyIdentityCard();
                    refreshUsers();
                    renderList();
                    toast("Profil modifié avec succès");
                } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
            });
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void changePassword() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(app.stage());
        dialog.setTitle("Changer le mot de passe");
        PasswordField current = new PasswordField();
        current.setPromptText("Mot de passe actuel");
        PasswordField next = new PasswordField();
        next.setPromptText("Nouveau mot de passe");
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Confirmer le nouveau mot de passe");
        dialog.getDialogPane().setContent(new VBox(10, current, next, confirm));
        ButtonType save = new ButtonType("Modifier", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.showAndWait().filter(x -> x == save).ifPresent(x -> {
            if (!next.getText().equals(confirm.getText())) { app.error("Les deux nouveaux mots de passe sont différents."); return; }
            try {
                api.changePassword(current.getText(), next.getText());
                app.info("Mot de passe modifié avec succès.");
            } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        });
    }

    private void deleteAccount() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(app.stage());
        dialog.setTitle("Supprimer mon compte");
        dialog.setHeaderText("Cette action est définitive");
        Label warning = new Label("Tes messages privés, statuts et groupes dont tu es propriétaire seront supprimés.\nTape SUPPRIMER et confirme ton mot de passe.");
        warning.setWrapText(true);
        TextField confirmText = new TextField();
        confirmText.setPromptText("SUPPRIMER");
        PasswordField password = new PasswordField();
        password.setPromptText("Mot de passe actuel");
        dialog.getDialogPane().setContent(new VBox(10, warning, confirmText, password));
        ButtonType delete = new ButtonType("Supprimer définitivement", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(delete, ButtonType.CANCEL);
        dialog.showAndWait().filter(x -> x == delete).ifPresent(x -> {
            if (!"SUPPRIMER".equals(confirmText.getText().trim())) { app.error("Tape exactement SUPPRIMER pour confirmer."); return; }
            try {
                api.deleteAccount(password.getText());
                app.info("Ton compte a été supprimé définitivement.");
                app.logout();
            } catch (Exception ex) { app.error(ClientApp.friendly(ex)); }
        });
    }

    private void showProfileOf(User u) {
        if (u == null) return;
        refreshContactsAndBlocks();
        boolean contact = allContacts.stream().anyMatch(c -> c.getId() == u.getId());
        boolean blocked = blockedIds.contains(u.getId());
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(app.stage());
        dialog.setTitle("Profil • " + u.getDisplayName());
        VBox box = new VBox(12);
        box.setPadding(new Insets(8));
        Label name = new Label(u.getDisplayName()); name.getStyleClass().add("item-title");
        Label phone = new Label(u.getUsername()); phone.getStyleClass().add("muted-small");
        Label presence = new Label(u.getOnline() ? "● En ligne" : "Vu " + fmtLastSeen(u.getLastSeen()));
        presence.getStyleClass().add(u.getOnline()?"online":"muted-small");
        HBox identity = new HBox(12, avatarNode(u), new VBox(3, name, phone, presence));
        identity.setAlignment(Pos.CENTER_LEFT);
        Label bio = new Label(u.getBio().isBlank() ? "Aucune bio" : u.getBio()); bio.setWrapText(true); bio.getStyleClass().add("muted");
        Button contactButton = new Button(contact ? "Retirer des contacts" : "＋ Ajouter aux contacts");
        contactButton.setOnAction(e -> {
            try {
                if (contact) api.removeContact(u.getId()); else api.addContact(u.getId());
                refreshContactsAndBlocks(); dialog.close(); renderList();
                toast(contact ? "Contact retiré" : "Contact ajouté");
            } catch(Exception ex){app.error(ClientApp.friendly(ex));}
        });
        Button blockButton = new Button(blocked ? "Débloquer" : "⛔ Bloquer");
        if (!blocked) blockButton.getStyleClass().add("danger-button");
        blockButton.setOnAction(e -> {
            try {
                if (blocked) api.unblock(u.getId()); else api.block(u.getId());
                refreshContactsAndBlocks(); dialog.close(); renderList();
                if (selectedUser != null && selectedUser.getId()==u.getId()) loadDirect(u);
                toast(blocked ? "Contact débloqué" : "Contact bloqué");
            } catch(Exception ex){app.error(ClientApp.friendly(ex));}
        });
        Button message = new Button("💬 Message"); message.getStyleClass().add("primary-button");
        message.setOnAction(e -> {dialog.close();selectedUser=u;selectedGroup=null;mode=Mode.CHATS;loadDirect(u);});
        HBox actions = new HBox(8, message, contactButton, blockButton);
        box.getChildren().addAll(identity, new Separator(), bio, actions);
        dialog.getDialogPane().setContent(box); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.showAndWait();
    }

    private void subscribe() {
        api.subscribe(event -> {
            // Audio/video temps reel : traitement hors du thread JavaFX pour
            // eviter latence, gels de l'interface et pertes de paquets.
            if (event.getType() == EventType.EVENT_CALL && event.hasCall()
                    && (event.getCall().getType() == CallPacketType.CALL_AUDIO
                    || event.getCall().getType() == CallPacketType.CALL_VIDEO)) {
                if (calls != null) calls.handleExternalSignal(event.getCall());
                return;
            }
            Platform.runLater(() -> handleEvent(event));
        }, err -> Platform.runLater(() -> {}));
    }

    private void handleEvent(Event event) {
        try {
            switch (event.getType()) {
                case EVENT_PRESENCE -> {
                    refreshUsers();
                    if ("ACCOUNT_DELETED".equals(event.getText()) && selectedUser != null && selectedUser.getId() == event.getActorId()) {
                        selectedUser = null;
                        showChats();
                        toast("Ce compte a été supprimé");
                    } else if (selectedUser != null) {
                        User refreshed = findUser(selectedUser.getId());
                        if (refreshed != null) loadDirect(refreshed);
                    }
                    if (selectedGroup != null) loadGroup(selectedGroup);
                }
                case EVENT_MESSAGE -> handleMessageEvent(event);
                case EVENT_READ, EVENT_REACTION -> {
                    ChatMessage m = event.getMessage();
                    if (selectedUser != null && m.getGroupId() == 0
                            && (m.getSenderId() == selectedUser.getId() || m.getRecipientUserId() == selectedUser.getId())) loadDirect(selectedUser);
                    else if (selectedGroup != null && m.getGroupId() == selectedGroup.getId()) loadGroup(selectedGroup);
                }
                case EVENT_CALL -> {
                    if(calls!=null && event.hasCall()) calls.handleExternalSignal(event.getCall());
                }
                case EVENT_GROUP -> {
                    refreshGroups();
                    if (selectedGroup != null) {
                        Group g = allGroups.stream().filter(x -> x.getId() == selectedGroup.getId()).findFirst().orElse(null);
                        if (g == null) { selectedGroup = null; showGroups(); }
                        else loadGroup(g);
                    }
                }
                case EVENT_STATUS -> {
                    refreshStatuses();
                    if (mode == Mode.STATUS) renderList();
                    if (!event.getText().isBlank() && !"STATUS_DELETED".equals(event.getText()) && event.getActorId() != me.getId())
                        toast(event.getActorName() + " • " + event.getText());
                }
                case EVENT_TYPING -> {
                    boolean relevant = (selectedUser != null && event.getGroupId() == 0 && event.getActorId() == selectedUser.getId())
                            || (selectedGroup != null && event.getGroupId() == selectedGroup.getId());
                    if (relevant && typingLabel != null) {
                        String activity = event.getText();
                        typingLabel.setText(event.getActorName() + ("recording".equals(activity) ? " enregistre un audio..." : " écrit..."));
                        typingLabel.setVisible(event.getTyping());
                        typingLabel.setManaged(event.getTyping());
                    }
                }
                case EVENT_CONTACT, EVENT_BLOCK -> {
                    refreshContactsAndBlocks();
                    if (mode == Mode.CHATS) renderList();
                    if (selectedUser != null && event.getTargetUserId() == selectedUser.getId()) loadDirect(selectedUser);
                }
                default -> { }
            }
        } catch (Exception ignored) {}
    }

    private void handleMessageEvent(Event event) {
        ChatMessage m=event.getMessage();
        boolean mine=m.getSenderId()==me.getId();
        if (m.getGroupId() > 0) {
            recentGroupActivity.put(m.getGroupId(), m.getCreatedAt());
            recentGroupPreview.put(m.getGroupId(), notificationPreview(m));
        } else {
            long activityPeer = mine ? m.getRecipientUserId() : m.getSenderId();
            if (activityPeer > 0) {
                recentUserActivity.put(activityPeer, m.getCreatedAt());
                recentUserPreview.put(activityPeer, notificationPreview(m));
            }
        }
        if (isCallLog(m)) {
            if (m.getGroupId() > 0) {
                boolean open = selectedGroup != null && selectedGroup.getId() == m.getGroupId();
                if (open) loadGroup(selectedGroup); else if (mode == Mode.GROUPS || mode == Mode.CHATS) renderList();
                return;
            }
            long peerId = mine ? m.getRecipientUserId() : m.getSenderId();
            boolean open = selectedUser != null && selectedUser.getId() == peerId;
            boolean visible = open && app.isFocused();
            // Un appel sans reponse laisse une vraie trace et, pour le
            // destinataire, une notification "appel manque". Les changements
            // RINGING -> ONGOING -> COMPLETED ne multiplient pas les badges.
            if (!mine && "NO_ANSWER".equals(m.getCallStatus()) && !visible) {
                unreadUsers.merge(peerId, 1, Integer::sum);
                User caller = findUser(m.getSenderId());
                String name = caller == null ? m.getSenderName() : caller.getDisplayName();
                if (!app.isMutedUser(peerId)) app.notifyMessage(m.getType()==MessageType.MESSAGE_CALL_VIDEO ? "🎥 Appel vidéo manqué" : "📞 Appel manqué", name + " a essayé de vous appeler");
            }
            if (open) loadDirect(selectedUser); else if (mode == Mode.CHATS) renderList();
            return;
        }
        if(m.getGroupId()==0){
            long peerId=mine?m.getRecipientUserId():m.getSenderId();
            boolean open=selectedUser!=null && selectedUser.getId()==peerId;
            boolean visible=open && app.isFocused();
            if(!mine){
                if(!visible) unreadUsers.merge(peerId,1,Integer::sum);
                if(!visible){
                    User sender=findUser(m.getSenderId());
                    String name=sender==null?m.getSenderName():sender.getDisplayName();
                    if (!app.isMutedUser(peerId)) app.notifyMessage("💬 " + name, notificationPreview(m));
                }
            }
            if(open) loadDirect(selectedUser); else if(mode==Mode.CHATS) renderList();
        }else{
            boolean open=selectedGroup!=null && selectedGroup.getId()==m.getGroupId();
            boolean visible=open && app.isFocused();
            if(!mine){
                if(!visible) unreadGroups.merge(m.getGroupId(),1,Integer::sum);
                if(!visible){
                    Group g=allGroups.stream().filter(x->x.getId()==m.getGroupId()).findFirst().orElse(null);
                    String title=(g==null?"Groupe":g.getName()) + " • " + m.getSenderName();
                    if (!app.isMutedGroup(m.getGroupId())) app.notifyMessage("💬 " + title, notificationPreview(m));
                }
            }
            if(open) loadGroup(selectedGroup); else if(mode==Mode.GROUPS || mode==Mode.CHATS) renderList();
        }
    }

    private String notificationPreview(ChatMessage m){
        if(m.getDeleted()) return "Message supprimé";
        return switch(m.getType()){
            case MESSAGE_AUDIO -> "🎙 Message audio";
            case MESSAGE_VIDEO -> "🎥 Vidéo";
            case MESSAGE_IMAGE -> "🖼 Image";
            case MESSAGE_FILE -> "📎 " + (m.getFileName().isBlank()?"Fichier":m.getFileName());
            case MESSAGE_EMOJI -> m.getContent();
            case MESSAGE_CALL_AUDIO -> "📞 " + callPreview(m);
            case MESSAGE_CALL_VIDEO -> "🎥 " + callPreview(m);
            default -> m.getContent().length()>80?m.getContent().substring(0,80)+"…":m.getContent();
        };
    }

    private String callPreview(ChatMessage m) {
        boolean mine = m.getSenderId() == me.getId();
        return switch (m.getCallStatus()) {
            case "COMPLETED" -> (mine ? "Appel sortant" : "Appel entrant") + (m.getCallDurationSeconds()>0 ? " • " + formatCallDuration(m.getCallDurationSeconds()) : "");
            case "NO_ANSWER" -> mine ? "Sans réponse" : "Appel manqué";
            case "REJECTED" -> "Appel refusé";
            case "BUSY" -> "Utilisateur occupé";
            case "UNAVAILABLE" -> mine ? "Utilisateur indisponible" : "Appel manqué";
            case "ONGOING" -> "Appel en cours";
            default -> mine ? "Appel sortant" : "Appel entrant";
        };
    }

    private void refreshUsers() {
        try {
            allUsers = new ArrayList<>(api.users());
            refreshContactsAndBlocks();
            if (mode == Mode.CHATS) renderList();
        } catch (Exception ignored) {}
    }

    private void refreshGroups() {
        try {
            allGroups = new ArrayList<>(api.groups());
            if (mode == Mode.GROUPS || mode == Mode.CHATS) renderList();
        } catch (Exception ex) {
            app.error(ClientApp.friendly(ex));
        }
    }

    private void refreshStatuses() {
        try {
            allStatuses = new ArrayList<>(api.statuses());
            if (mode == Mode.STATUS) renderList();
        } catch (Exception ignored) {}
    }

    private User findUser(long id) {
        if (id == me.getId()) return me;
        return allUsers.stream().filter(u -> u.getId() == id).findFirst().orElse(null);
    }

    private Group findGroup(long id) {
        return allGroups.stream().filter(g -> g.getId() == id).findFirst().orElse(null);
    }

    private void toast(String text) {
        Node previous = center.getBottom();
        Label label = new Label(text);
        label.getStyleClass().add("toast");
        StackPane pane = new StackPane(label);
        pane.setPadding(new Insets(12));
        center.setBottom(pane);
        PauseTransition transition = new PauseTransition(Duration.seconds(3));
        transition.setOnFinished(x -> center.setBottom(previous));
        transition.play();
    }

    private Node avatarNode(User u) {
        if (u != null && !u.getAvatar().isEmpty()) {
            try {
                ImageView iv = new ImageView(new Image(new ByteArrayInputStream(u.getAvatar().toByteArray())));
                iv.setFitWidth(44);
                iv.setFitHeight(44);
                iv.setPreserveRatio(false);
                iv.setClip(new Circle(22, 22, 22));
                return iv;
            } catch (Exception ignored) {}
        }
        Label label = new Label(initials(u == null ? "?" : u.getDisplayName()));
        label.getStyleClass().add("avatar-circle");
        return label;
    }

    private Node groupAvatarNode(Group g) {
        if (g != null && !g.getAvatar().isEmpty()) {
            try {
                ImageView iv = new ImageView(new Image(new ByteArrayInputStream(g.getAvatar().toByteArray())));
                iv.setFitWidth(44);
                iv.setFitHeight(44);
                iv.setPreserveRatio(false);
                iv.setClip(new Circle(22, 22, 22));
                return iv;
            } catch (Exception ignored) {}
        }
        Label label = new Label("#");
        label.getStyleClass().add("avatar-circle");
        return label;
    }

    private String shortText(ChatMessage m) {
        if (m.getDeleted()) return "Message supprimé";
        String value = switch (m.getType()) {
            case MESSAGE_AUDIO -> "🎙 Audio";
            case MESSAGE_VIDEO -> "🎬 Vidéo";
            case MESSAGE_IMAGE -> "▧ Image";
            case MESSAGE_FILE -> "📎 " + m.getFileName();
            case MESSAGE_CALL_AUDIO -> "📞 " + callPreview(m);
            case MESSAGE_CALL_VIDEO -> "🎥 " + callPreview(m);
            default -> m.getContent();
        };
        return value.length() > 55 ? value.substring(0, 55) + "…" : value;
    }

    private static String audioDuration(byte[] wav) {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            double seconds = stream.getFrameLength() / stream.getFormat().getFrameRate();
            return String.format(Locale.ROOT, "%.1f s", seconds);
        } catch (Exception e) {
            return "prêt";
        }
    }

    private static String guessMime(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (n.matches(".*\\.(png)$")) return "image/png";
        if (n.matches(".*\\.(jpg|jpeg)$")) return "image/jpeg";
        if (n.matches(".*\\.(gif)$")) return "image/gif";
        if (n.matches(".*\\.(wav)$")) return "audio/wav";
        if (n.matches(".*\\.(mp3)$")) return "audio/mpeg";
        if (n.matches(".*\\.(mp4|m4v|mov)$")) return "video/mp4";
        return "application/octet-stream";
    }

    private static String prettyRole(String role) {
        return switch (role) {
            case "OWNER" -> "propriétaire";
            case "ADMIN" -> "admin";
            default -> "membre";
        };
    }

    private static String initials(String s) {
        if (s == null || s.isBlank()) return "?";
        String[] parts = s.trim().split("\\s+");
        return (parts.length == 1
                ? parts[0].substring(0, Math.min(2, parts[0].length()))
                : parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter LAST = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());
    private static String fmtTime(long ms) { return TIME.format(Instant.ofEpochMilli(ms)); }
    private static String fmtLastSeen(long ms) { return ms <= 0 ? "récemment" : LAST.format(Instant.ofEpochMilli(ms)); }
}
