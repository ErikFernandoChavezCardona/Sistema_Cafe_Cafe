package application;

import application.controller.CajaBridge;
import application.controller.EgresoBridge;
import application.controller.FacturaBridge;
import application.controller.ReporteBridge;
import application.controller.WebBridge;
import application.model.connection.DatabaseConnection;
import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;
import application.controller.ProductoBridge;
import application.controller.InsumoBridge;
import application.controller.VentaBridge;

public class Main extends Application {

    // Referencias fuertes: si se pierden, el recolector de basura rompe el puente
    // con el HTML.
    private WebBridge bridge;
    private CajaBridge cajaBridge;
    private VentaBridge ventaBridge;
    private ProductoBridge productoBridge;
    private InsumoBridge insumoBridge;
    private EgresoBridge egresoBridge;
    private FacturaBridge facturaBridge;
    private ReporteBridge reporteBridge;

    @Override
    public void start(Stage stage) {
        DatabaseConnection.initialize();

        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        WebEngine engine = view.getEngine();
        bridge = new WebBridge(engine);
        cajaBridge = new CajaBridge(engine, bridge);
        ventaBridge = new VentaBridge(engine, bridge);
        productoBridge = new ProductoBridge(engine, bridge);
        insumoBridge = new InsumoBridge(engine, bridge);
        egresoBridge = new EgresoBridge(engine, bridge);
        facturaBridge = new FacturaBridge(engine, bridge);
        reporteBridge = new ReporteBridge(engine, bridge);

        // Cada vez que carga una página, se le entregan los puentes: window.javaApp y
        // window.javaCaja
        engine.getLoadWorker().stateProperty().addListener((obs, anterior, estado) -> {
            if (estado == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("javaApp", bridge);
                window.setMember("javaCaja", cajaBridge);
                window.setMember("javaVenta", ventaBridge);
                window.setMember("javaProductos", productoBridge);
                window.setMember("javaInsumos", insumoBridge);
                window.setMember("javaEgresos", egresoBridge);
                window.setMember("javaFacturas", facturaBridge);
                window.setMember("javaReportes", reporteBridge);
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
