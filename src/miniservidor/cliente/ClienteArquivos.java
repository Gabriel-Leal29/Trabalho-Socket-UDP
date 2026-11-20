package miniservidor.cliente;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class ClienteArquivos {

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignorada) {
            }
            new JanelaCliente().setVisible(true);
        });
    }
}
