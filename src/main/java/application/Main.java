package application;

import application.controller.WebBridge;
import application.model.connection.DatabaseConnection;
import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

public class Main extends Application {

    // Referencia fuerte: si se pierde, el recolector de basura rompe el puente con el HTML.
    private WebBridge bridge;

    @Override
    public void start(Stage stage) {
        DatabaseConnection.initialize();

        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        WebEngine engine = view.getEngine();
        bridge = new WebBridge(engine);

        // Cada vez que carga una página, se le entrega el puente como window.javaApp
        engine.getLoadWorker().stateProperty().addListener((obs, anterior, estado) -> {
            if (estado == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("javaApp", bridge);
                engine.executeScript("window.onJavaReady && window.onJavaReady()");
            }
        });

        engine.load(WebBridge.pagina("login.html"));

        stage.setTitle("Café Café");
        stage.setScene(new Scene(view, 1280, 800));
        stage.setMaximized(true);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
