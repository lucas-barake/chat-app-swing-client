package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.logging.Logger;

class ChatApp extends JFrame {
    private User loggedUser;
    private final DefaultListModel<User> userListModel = new DefaultListModel<>();
    private final JList<User> userList = new JList<>(userListModel);
    private final DefaultListModel<Message> messageListModel = new DefaultListModel<>();
    private MyWebSocketClient webSocket;
    private final JTextField messageInputField = new JTextField();
    private final JScrollPane messageScrollPane;

    /**
     * El constructor de la clase ChatApp.
     * Inicializa los componentes GUI y configura los escuchadores de eventos.
     */
    public ChatApp() {
        setTitle("Chat Application");
        setSize(800, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        initializeLoginPanel();

        // Parte izquierda
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                recipientSelected(userList.getSelectedValue());
            }
        });
        add(new JScrollPane(userList), BorderLayout.WEST);

        // Parte central
        JList<Message> messageList = new JList<>(messageListModel);
        messageList.setCellRenderer(new MessageCellRenderer());
        messageScrollPane = new JScrollPane(messageList);
        add(messageScrollPane, BorderLayout.CENTER);

        // Parte inferior
        JPanel messageInputPanel = new JPanel(new BorderLayout());
        JButton sendMessageButton = new JButton("Send");
        sendMessageButton.addActionListener(e -> sendMessage());
        messageInputField.addActionListener(e -> sendMessage());
        messageInputPanel.add(messageInputField, BorderLayout.CENTER);
        messageInputPanel.add(sendMessageButton, BorderLayout.EAST);
        add(messageInputPanel, BorderLayout.SOUTH);
    }

    /**
     * Inicializa el panel de inicio de sesión.
     * Crea los componentes GUI necesarios y configura los escuchadores de eventos para el login del usuario.
     */
    private void initializeLoginPanel() {
        JPanel loginPanel = new JPanel();
        JTextField usernameField = new JTextField(20);
        JButton joinButton = new JButton("Join App");

        joinButton.addActionListener(e -> {
            String username = usernameField.getText();
            loginUser(username);
        });

        loginPanel.add(new JLabel("Username:"));
        loginPanel.add(usernameField);
        loginPanel.add(joinButton);
        add(loginPanel, BorderLayout.NORTH);
    }

    /**
     * Inicia la sesión del usuario con el nombre de usuario dado.
     * Envía una petición POST al servidor para crear un nuevo usuario.
     * La respuesta del servidor es deserializada en un objeto User usando Gson.
     *
     * @param username el nombre de usuario del usuario
     */
    private void loginUser(String username) {
        System.setProperty("java.net.http.HttpClient.log", "all");
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/users/" + username))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(body -> {
                    Gson gson = new Gson();
                    try {
                        User user = gson.fromJson(body, User.class);
                        SwingUtilities.invokeLater(() -> {
                            loggedUser = user;
                            connectWebSocket();
                            fetchAllUsers();
                        });
                    } catch (JsonSyntaxException e) {
                        Logger.getGlobal().warning("Invalid JSON response from server");
                    }
                });
    }

    /**
     * Obtiene todos los usuarios del servidor.
     * Envía una petición GET al servidor para recuperar todos los usuarios.
     * La respuesta del servidor se deserializa en una lista de objetos de usuario utilizando Gson.
     */
    private void fetchAllUsers() {
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/users"))
                .GET()
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(body -> {
                    Gson gson = new Gson();
                    java.lang.reflect.Type userListType = new TypeToken<List<User>>() {
                    }.getType();
                    List<User> users = gson.fromJson(body, userListType);

                    SwingUtilities.invokeLater(() -> {
                        userListModel.clear();
                        users.stream()
                                .filter(u -> u.getId() != loggedUser.getId()) // Filtrar el usuario conectado
                                .forEach(userListModel::addElement);
                    });
                })
                .exceptionally(e -> {
                    Logger.getGlobal().warning("Error fetching users: " + e.getMessage());
                    return null;
                });
    }

    /**
     * Se conecta al servidor WebSocket.
     * Crea una nueva instancia de MyWebSocketClient y se conecta al servidor.
     */
    private void connectWebSocket() {
        webSocket = new MyWebSocketClient(URI.create("ws://localhost:8080/chat"));
        webSocket.connect();
    }

    /**
     * Maneja el evento cuando un destinatario es seleccionado de la lista de usuarios.
     * Envía una petición GET al servidor para recuperar todos los mensajes entre el usuario conectado y el destinatario seleccionado.
     * La respuesta del servidor es deserializada en una lista de objetos Message usando Gson.
     *
     * @param recipient el destinatario seleccionado
     */
    private void recipientSelected(User recipient) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create("http://localhost:8080/messages?user1Id=" + loggedUser.getId() + "&user2Id="
                        + recipient.getId()))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(body -> {
                    Gson gson = new Gson();
                    java.lang.reflect.Type responseType = new TypeToken<List<Message>>() {
                    }.getType();
                    List<Message> messages = gson.fromJson(body, responseType);
                    updateMessageList(messages);
                })
                .exceptionally(e -> {
                    Logger.getGlobal().warning("Error fetching messages: " + e.getMessage());
                    return null;
                });
    }

    /**
     * Envía un mensaje al destinatario seleccionado.
     * Crea un nuevo JsonObject para el mensaje y lo envía al servidor a través de la conexión WebSocket.
     */
    private void sendMessage() {
        String messageContent = messageInputField.getText().trim();
        if (messageContent.isEmpty())
            return;

        User recipient = userList.getSelectedValue();
        if (recipient != null && webSocket != null && webSocket.isOpen()) {
            JsonObject jsonMessage = new JsonObject();
            jsonMessage.addProperty("sender", loggedUser.getUsername());
            jsonMessage.addProperty("receiver", recipient.getUsername());
            jsonMessage.addProperty("content", messageContent);

            Gson gson = new Gson();
            String messageJson = gson.toJson(jsonMessage);

            webSocket.send(messageJson);
            Logger.getGlobal().info("Sent message: " + messageJson);
            messageInputField.setText("");
        }
    }

    class MyWebSocketClient extends WebSocketClient {

        /**
         * El constructor de la clase MyWebSocketClient.
         * Llama al super constructor con el URI del servidor dado.
         *
         * @param serverUri el URI del servidor WebSocket
         */
        public MyWebSocketClient(URI serverUri) {
            super(serverUri);
        }

        /**
         * Maneja el evento cuando se abre la conexión WebSocket.
         * Registra el evento.
         *
         * @param handshakedata los datos de handshake del servidor.
         */
        @Override
        public void onOpen(ServerHandshake handshakedata) {
            Logger.getGlobal().info("WebSocket connection opened");
        }

        /**
         * Maneja el evento cuando se recibe un mensaje del servidor.
         * Registra el mensaje y lo deserializa en un objeto Message usando Gson.
         *
         * @param message el mensaje del servidor
         */
        @Override
        public void onMessage(String message) {
            Logger.getGlobal().info("Received message: " + message);
            Gson gson = new Gson();
            try {
                Message messageObj = gson.fromJson(message, Message.class);
                onNewMessage(messageObj);
            } catch (JsonSyntaxException e) {
                Logger.getGlobal().warning("Invalid JSON message received from server");
            }
        }

        /**
         * Maneja el evento cuando se cierra la conexión WebSocket.
         * Registra el evento junto con el código de salida y la razón.** @param code el código de salida.
         *
         * @param reason la razón del cierre
         * @param remote si el cierre fue iniciado por el peer remoto
         */
        @Override
        public void onClose(int code, String reason, boolean remote) {
            Logger.getGlobal()
                    .info("WebSocket connection closed with exit code " + code + " additional info: " + reason);
        }

        /**
         * Maneja el evento cuando ocurre un error en la conexión WebSocket.
         * Registra el mensaje de error.
         *
         * @param ex la excepción que causó el error
         */
        @Override
        public void onError(Exception ex) {
            Logger.getGlobal().warning("WebSocket error: " + ex.getMessage());
        }
    }

    /**
     * Actualiza la lista de mensajes con los mensajes dados.
     * Borra la lista de mensajes y añade los mensajes dados.
     * También se desplaza al final de la lista de mensajes.
     *
     * @param messages los mensajes a añadir a la lista de mensajes
     */
    private void updateMessageList(List<Message> messages) {
        SwingUtilities.invokeLater(() -> {
            messageListModel.clear();
            for (Message message : messages) {
                messageListModel.addElement(message);
            }
            scrollToBottom();
        });
    }

    /**
     * Maneja el evento cuando se recibe un nuevo mensaje del servidor.
     * Agrega el nuevo mensaje a la lista de mensajes y se desplaza hacia la parte inferior de la lista de mensajes.
     *
     * @param message el nuevo mensaje
     */
    private void onNewMessage(Message message) {
        SwingUtilities.invokeLater(() -> {
            messageListModel.addElement(message);
            scrollToBottom();
        });
    }

    /**
     * Desplaza la vista hacia la parte inferior de la lista de mensajes.
     */
    private void scrollToBottom() {
        int lastIndex = messageListModel.getSize() - 1;
        if (lastIndex >= 0) {
            JList<Message> messageList = (JList<Message>) messageScrollPane.getViewport().getView();
            messageList.ensureIndexIsVisible(lastIndex);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatApp().setVisible(true));
    }
}

class MessageCellRenderer extends JLabel implements ListCellRenderer<Message> {
    /**
     * Devuelve un componente que ha sido configurado para mostrar el valor especificado.
     * Los colores de primer plano y fondo del componente se establecen en los colores utilizados para este tipo de componente por el aspecto actual.
     *
     * @param list         la JList que estamos pintando
     * @param value        el valor devuelto por list.getModel().getElementAt(index)
     * @param index        el índice de la celda
     * @param isSelected   true si la celda especificada está seleccionada
     * @param cellHasFocus true si la celda especificada tiene el foco
     * @return un componente cuyo método paint() representará el valor especificado
     */
    @Override
    public Component getListCellRendererComponent(JList<? extends Message> list, Message value, int index, boolean isSelected, boolean cellHasFocus) {
        setText(value.getSender().getUsername() + ": " + value.getContent());
        return this;
    }
}
