package client.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 원래: Main.fxml 였다면 ↓ 이렇게 변경
        Parent root = FXMLLoader.load(getClass().getResource("Lobby.fxml"));

        primaryStage.setTitle("Mafia Game - Lobby");
        primaryStage.setScene(new Scene(root, 800, 600)); // 로비니까 조금 넓게
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}