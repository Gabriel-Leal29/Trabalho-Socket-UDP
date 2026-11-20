package miniservidor;

import miniservidor.cliente.ClienteArquivos;
import miniservidor.servidor.ServidorArquivos;

import javax.swing.JOptionPane;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

public class Iniciar {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.net.preferIPv4Stack", "true");
        String modo = args.length > 0 ? args[0].toLowerCase() : "";
        String[] resto = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;

        if (modo.equals("servidor")) {
            ServidorArquivos.main(resto);
        } else if (modo.equals("cliente")) {
            ClienteArquivos.main(resto);
        } else if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Uso: java -jar MiniServidorArquivos.jar servidor|cliente [opções]");
        } else {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignorada) {
            }
            Object[] opcoes = {"Servidor", "Cliente"};
            int escolha = JOptionPane.showOptionDialog(null, "O que você quer abrir nesta máquina?",
                    "Mini Servidor de Arquivos (UDP)", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, opcoes, opcoes[1]);
            if (escolha == 0) {
                ServidorArquivos.main(resto);
            } else if (escolha == 1) {
                ClienteArquivos.main(resto);
            } else {
                System.exit(0);
            }
        }
    }
}
