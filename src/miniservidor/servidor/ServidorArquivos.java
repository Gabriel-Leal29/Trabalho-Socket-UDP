package miniservidor.servidor;

import miniservidor.comum.Protocolo;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public class ServidorArquivos {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.net.preferIPv4Stack", "true");

        Path base = pastaBase();
        int porta = Protocolo.PORTA_PADRAO;
        Path pasta = base.resolve("arquivos_servidor");
        Path log = base.resolve("log_servidor.txt");
        int perda = 0;
        boolean console = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--porta" -> porta = Integer.parseInt(args[++i]);
                case "--pasta" -> pasta = Path.of(args[++i]);
                case "--log" -> log = Path.of(args[++i]);
                case "--perda" -> perda = Integer.parseInt(args[++i]);
                case "--console" -> console = true;
                default -> System.out.println("Opção ignorada: " + args[i]);
            }
        }

        if (console || GraphicsEnvironment.isHeadless()) {
            ServidorUdp servidor = new ServidorUdp(porta, new RepositorioArquivos(pasta), new LogServidor(log));
            servidor.iniciar();
            servidor.getCanal().setPerdaPercentual(perda);
            System.out.println("Servidor rodando. Ctrl+C para encerrar.");
            new CountDownLatch(1).await(); // bloqueia até o processo ser encerrado
            return;
        }

        Path pastaFinal = pasta;
        Path logFinal = log;
        int portaFinal = porta;
        int perdaFinal = perda;
        SwingUtilities.invokeLater(() -> {
            usarVisualDoSistema();
            JanelaServidor janela = new JanelaServidor(pastaFinal, logFinal, portaFinal, perdaFinal);
            janela.setVisible(true);
            janela.iniciar();
        });
    }

    // Onde ficam arquivos_servidor/ e log_servidor.txt. No IntelliJ é a pasta do projeto; com duplo
    // clique no .jar o macOS roda em "/", então nesse caso usa ~/MiniServidorArquivos.
    static Path pastaBase() {
        Path atual = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (atual.getParent() != null && Files.isWritable(atual)) {
            return atual;
        }
        return Path.of(System.getProperty("user.home"), "MiniServidorArquivos");
    }

    static void usarVisualDoSistema() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignorada) {
        }
    }
}
