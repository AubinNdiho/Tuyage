package com.tuyage.client;

import com.tuyage.proto.AuthResponse;
import com.tuyage.proto.User;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Popup;
import javafx.util.Duration;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import javax.imageio.ImageIO;

public class ClientApp extends Application {
    private Stage stage;
    private final ClientPrefs prefs = new ClientPrefs();
    private boolean dark;
    private GrpcClient api;
    private User me;
    private TrayIcon trayIcon;

    @Override public void start(Stage primaryStage) {
        this.stage=primaryStage; this.dark=prefs.dark();
        stage.setTitle("Tuyage");
        try { stage.getIcons().add(new Image(getClass().getResourceAsStream("/images/taskbar-icon.png"))); } catch (Exception ignored) {}
        stage.setMinWidth(1050); stage.setMinHeight(680);
        initSystemTray();
        stage.setOnCloseRequest(e->{removeSystemTray();if(api!=null)api.close();Platform.exit();});
        showAuth(); stage.show();
    }

    private void showAuth() {
        VBox brand=new VBox(16);brand.setAlignment(Pos.CENTER);brand.getStyleClass().add("auth-brand");brand.setPadding(new Insets(50));
        ImageView logo=new ImageView(new Image(getClass().getResourceAsStream("/images/logo.png")));logo.setFitWidth(210);logo.setFitHeight(210);logo.setPreserveRatio(true);
        Label sub=new Label("Messagerie gRPC • claire, moderne et instantanée");sub.getStyleClass().add("muted");sub.setWrapText(true);brand.getChildren().addAll(logo,sub);

        TabPane tabs=new TabPane();tabs.getStyleClass().add("auth-tabs");
        TextField server=new TextField(prefs.server());server.setPromptText("Serveur: localhost:9090 ou IP:9090");server.getStyleClass().add("server-field");
        Tab loginTab=new Tab("Connexion",loginPane(server));loginTab.setClosable(false);Tab registerTab=new Tab("Créer un compte",registerPane(server));registerTab.setClosable(false);tabs.getTabs().addAll(loginTab,registerTab);

        VBox card=new VBox(14,new Label("Adresse du serveur gRPC"),server,tabs);card.getStyleClass().add("auth-card");card.setPadding(new Insets(28));VBox.setVgrow(tabs,Priority.ALWAYS);
        StackPane right=new StackPane(card);right.setPadding(new Insets(60));
        BorderPane root=new BorderPane();root.setLeft(brand);root.setCenter(right);BorderPane.setMargin(brand,new Insets(0));
        Scene scene=new Scene(root,1180,760);applyTheme(scene);stage.setScene(scene);
    }

    private VBox loginPane(TextField server){
        TextField phone=new TextField(prefs.username());phone.setPromptText("Numéro de téléphone, ex. +257 79 00 00 00");PasswordField password=new PasswordField();password.setPromptText("Mot de passe");Button go=new Button("Se connecter");go.getStyleClass().add("primary-button");go.setMaxWidth(Double.MAX_VALUE);
        Label note=new Label("Ton numéro de téléphone identifie ton compte. Tes conversations sont conservées sur le serveur.");note.getStyleClass().add("muted");note.setWrapText(true);
        go.setOnAction(e->runAuth(server.getText(),()->api.login(phone.getText(),password.getText()),phone.getText()));password.setOnAction(e->go.fire());
        Button forgot=new Button("Mot de passe oublié ?");
        forgot.getStyleClass().add("link-button");
        forgot.setOnAction(e->showPasswordReset(server.getText(),phone.getText()));
        VBox box=new VBox(14,new Label("Bienvenue sur Tuyage"),phone,password,go,forgot,note);box.setPadding(new Insets(24,4,4,4));return box;
    }

    private VBox registerPane(TextField server){
        TextField phone=new TextField();phone.setPromptText("Numéro de téléphone, ex. +257 79 00 00 00");TextField display=new TextField();display.setPromptText("Ton nom affiché");PasswordField password=new PasswordField();password.setPromptText("Mot de passe (6 caractères minimum)");Button go=new Button("Créer mon compte");go.getStyleClass().add("primary-button");go.setMaxWidth(Double.MAX_VALUE);
        Label hint=new Label("Le numéro de téléphone sera ton identifiant de connexion.");hint.getStyleClass().add("muted");hint.setWrapText(true);
        go.setOnAction(e->runAuth(server.getText(),()->api.register(phone.getText(),display.getText(),password.getText()),phone.getText()));password.setOnAction(e->go.fire());
        VBox box=new VBox(14,new Label("Nouveau compte"),phone,display,password,go,hint);box.setPadding(new Insets(24,4,4,4));return box;
    }

    private void showPasswordReset(String server, String suggestedPhone) {
        String target=server==null?"":server.trim();
        if(target.isEmpty()){error("Indique d'abord l'adresse du serveur.");return;}
        TextInputDialog phoneDialog=new TextInputDialog(suggestedPhone==null?"":suggestedPhone);
        phoneDialog.initOwner(stage);
        phoneDialog.setTitle("Récupération du compte");
        phoneDialog.setHeaderText("Réinitialiser le mot de passe Tuyage");
        phoneDialog.setContentText("Numéro de téléphone :");
        phoneDialog.showAndWait().ifPresent(phone->{
            String value=phone.trim();if(value.isEmpty())return;
            try(GrpcClient resetApi=new GrpcClient(target)){
                String message=resetApi.requestPasswordReset(value);
                info(message);
                Dialog<ButtonType> dialog=new Dialog<>();dialog.initOwner(stage);dialog.setTitle("Code de récupération");
                TextField code=new TextField();code.setPromptText("Code à 6 chiffres");
                PasswordField pass=new PasswordField();pass.setPromptText("Nouveau mot de passe");
                PasswordField confirm=new PasswordField();confirm.setPromptText("Confirmer le nouveau mot de passe");
                Label hint=new Label("Le code est valable 5 minutes. En mode TP il est aussi affiché dans la console du serveur.");hint.setWrapText(true);hint.getStyleClass().add("muted");
                dialog.getDialogPane().setContent(new VBox(10,new Label("Code reçu"),code,pass,confirm,hint));
                ButtonType save=new ButtonType("Réinitialiser",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
                dialog.showAndWait().filter(x->x==save).ifPresent(x->{
                    if(!pass.getText().equals(confirm.getText())){error("Les deux mots de passe ne correspondent pas.");return;}
                    try(GrpcClient confirmApi=new GrpcClient(target)){confirmApi.resetPassword(value,code.getText(),pass.getText());info("Mot de passe réinitialisé. Tu peux maintenant te connecter.");}
                    catch(Exception ex){error(friendly(ex));}
                });
            }catch(Exception ex){error(friendly(ex));}
        });
    }

    @FunctionalInterface private interface AuthCall { AuthResponse call(); }
    private void runAuth(String server,AuthCall call,String username){
        String target=server.trim();if(target.isEmpty()){error("Indique l'adresse du serveur.");return;}
        try{if(api!=null)api.close();api=new GrpcClient(target);AuthResponse auth=call.call();me=auth.getUser();prefs.save(target,dark,username);showMain();}
        catch(Exception ex){if(api!=null){api.close();api=null;}error(friendly(ex));}
    }

    private void showMain(){MainView view=new MainView(this,api,me);Scene scene=new Scene(view,1320,820);applyTheme(scene);stage.setScene(scene);stage.setMaximized(false);}

    void setCurrentUser(User user){this.me=user;}
    User currentUser(){return me;}
    Stage stage(){return stage;}
    boolean isDark(){return dark;}
    boolean isFocused(){return stage!=null && stage.isFocused();}
    String chatBackground(){return prefs.chatBackground();}
    void saveChatBackground(String path){prefs.saveChatBackground(path);}
    boolean isMutedUser(long id){return prefs.mutedUser(id);}
    boolean isMutedGroup(long id){return prefs.mutedGroup(id);}
    boolean isPinnedUser(long id){return prefs.pinnedUser(id);}
    boolean isPinnedGroup(long id){return prefs.pinnedGroup(id);}
    boolean isArchivedUser(long id){return prefs.archivedUser(id);}
    boolean isArchivedGroup(long id){return prefs.archivedGroup(id);}
    void toggleMutedUser(long id){prefs.toggleMutedUser(id);}
    void toggleMutedGroup(long id){prefs.toggleMutedGroup(id);}
    void togglePinnedUser(long id){prefs.togglePinnedUser(id);}
    void togglePinnedGroup(long id){prefs.togglePinnedGroup(id);}
    void toggleArchivedUser(long id){prefs.toggleArchivedUser(id);}
    void toggleArchivedGroup(long id){prefs.toggleArchivedGroup(id);}
    void toggleTheme(){dark=!dark;prefs.save(prefs.server(),dark,me==null?prefs.username():me.getUsername());applyTheme(stage.getScene());}
    void logout(){if(api!=null){api.close();api=null;}me=null;showAuth();}

    void applyTheme(Scene scene){
        if(scene==null)return;scene.getStylesheets().clear();scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());if(dark)scene.getStylesheets().add(getClass().getResource("/css/dark.css").toExternalForm());
    }

    void notifyMessage(String title, String text){
        String safeTitle = title == null ? "Tuyage" : title;
        String safeText = text == null ? "" : text;
        // Notification native Windows quand Tuyage est réduit ou derrière une autre fenêtre.
        if (trayIcon != null && (stage == null || !stage.isFocused())) {
            try { trayIcon.displayMessage(safeTitle, safeText, TrayIcon.MessageType.NONE); } catch (Throwable ignored) { }
        }
        Platform.runLater(() -> {
            try { java.awt.Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {}
            Popup popup=new Popup();
            Label titleLabel=new Label(safeTitle);
            titleLabel.setStyle("-fx-font-size:14px;-fx-font-weight:700;-fx-text-fill:white;");
            Label body=new Label(safeText);
            body.setWrapText(true); body.setMaxWidth(280);
            body.setStyle("-fx-font-size:12px;-fx-text-fill:#eaf1ff;");
            VBox card=new VBox(5,titleLabel,body);
            card.setPadding(new Insets(12,16,12,16));
            card.setStyle("-fx-background-color:#20264e;-fx-background-radius:14;-fx-effect:dropshadow(gaussian,rgba(0,0,0,.28),18,.15,0,5);");
            popup.getContent().add(card); popup.setAutoFix(true); popup.setAutoHide(true);
            double x=Math.max(stage.getX()+20,stage.getX()+stage.getWidth()-330);
            double y=stage.getY()+55;
            popup.show(stage,x,y);
            PauseTransition close=new PauseTransition(Duration.seconds(4));
            close.setOnFinished(e->popup.hide()); close.play();
        });
    }

    private void initSystemTray(){
        if (!SystemTray.isSupported()) return;
        try {
            var input = getClass().getResourceAsStream("/images/logo.png");
            if (input == null) return;
            java.awt.Image awtImage = ImageIO.read(input);
            trayIcon = new TrayIcon(awtImage, "Tuyage");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                stage.setIconified(false);
                stage.show();
                stage.toFront();
                stage.requestFocus();
            }));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Throwable ignored) { trayIcon = null; }
    }

    private void removeSystemTray(){
        if (trayIcon == null || !SystemTray.isSupported()) return;
        try { SystemTray.getSystemTray().remove(trayIcon); } catch (Throwable ignored) { }
        trayIcon = null;
    }

    void error(String text){Alert a=new Alert(Alert.AlertType.ERROR);a.initOwner(stage);a.setTitle("Tuyage");a.setHeaderText("Une action n'a pas abouti");a.setContentText(text);a.showAndWait();}
    void info(String text){Alert a=new Alert(Alert.AlertType.INFORMATION);a.initOwner(stage);a.setTitle("Tuyage");a.setHeaderText(null);a.setContentText(text);a.showAndWait();}
    static String friendly(Throwable ex){String m=ex.getMessage();if(m==null)return ex.getClass().getSimpleName();int i=m.indexOf("description=");if(i>=0)return m.substring(i+12).replace("}","").trim();return m;}
}
