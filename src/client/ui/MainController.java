package client.ui;

import client.network.Client;
import javafx.fxml.FXML;
import javafx.scene.control.*;

public class MainController {

    @FXML private TextArea chatArea;
    @FXML private TextField inputField;
    @FXML private Button sendButton;

    private Client client;

    @FXML
    public void initialize() {

        client = new Client();
        client.connect("localhost", 6000, msg -> {
            chatArea.appendText(msg + "\n");
        });

        sendButton.setOnAction(e -> sendMessage());
        inputField.setOnAction(e -> sendMessage());
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        client.send("나: " + text);
        inputField.clear();
    }
}