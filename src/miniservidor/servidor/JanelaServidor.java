package miniservidor.servidor;

import miniservidor.comum.InfoArquivo;
import miniservidor.comum.Util;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.util.List;

public class JanelaServidor extends JFrame {

    private static final int LIMITE_LOG = 200_000;

    private final Path pasta;
    private final Path arquivoLog;

    private final JTextField campoPorta;
    private final JButton botaoIniciar = new JButton("Iniciar servidor");
    private final JLabel rotuloEstado = new JLabel("Servidor parado");
    private final JSpinner spinnerPerda = new JSpinner(new SpinnerNumberModel(0, 0, 50, 5));
    private final JTextArea areaLog = new JTextArea();
    private final DefaultListModel<String> modeloClientes = new DefaultListModel<>();
    private final DefaultListModel<String> modeloArquivos = new DefaultListModel<>();

    private ServidorUdp servidor;
    private LogServidor log;
    private RepositorioArquivos repositorio;

    public JanelaServidor(Path pasta, Path arquivoLog, int porta, int perda) {
        super("Mini Servidor de Arquivos (UDP) — Servidor");
        this.pasta = pasta;
        this.arquivoLog = arquivoLog;
        this.campoPorta = new JTextField(String.valueOf(porta), 5);
        spinnerPerda.setValue(perda);
        montarTela();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 650);
        setLocationRelativeTo(null);
        new Timer(1000, e -> atualizarListas()).start();
    }

    private void montarTela() {
        JPanel linha1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        linha1.add(new JLabel("Porta UDP:"));
        linha1.add(campoPorta);
        linha1.add(botaoIniciar);
        linha1.add(Box.createHorizontalStrut(16));
        linha1.add(new JLabel("Perda simulada (%):"));
        linha1.add(spinnerPerda);
        linha1.add(Box.createHorizontalStrut(16));
        JButton botaoPasta = new JButton("Abrir pasta central");
        JButton botaoLog = new JButton("Abrir log_servidor.txt");
        linha1.add(botaoPasta);
        linha1.add(botaoLog);

        JPanel linha2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        rotuloEstado.setFont(rotuloEstado.getFont().deriveFont(Font.BOLD, 14f));
        linha2.add(rotuloEstado);

        JPanel topo = new JPanel();
        topo.setLayout(new BoxLayout(topo, BoxLayout.Y_AXIS));
        topo.add(linha1);
        topo.add(linha2);

        areaLog.setEditable(false);
        areaLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane rolagemLog = new JScrollPane(areaLog);
        rolagemLog.setBorder(BorderFactory.createTitledBorder("Log do servidor (" + arquivoLog.getFileName() + ")"));

        JList<String> listaClientes = new JList<>(modeloClientes);
        JScrollPane rolagemClientes = new JScrollPane(listaClientes);
        rolagemClientes.setBorder(BorderFactory.createTitledBorder("Clientes ativos"));
        JList<String> listaArquivos = new JList<>(modeloArquivos);
        JScrollPane rolagemArquivos = new JScrollPane(listaArquivos);
        rolagemArquivos.setBorder(BorderFactory.createTitledBorder("Arquivos na pasta central"));
        JPanel direita = new JPanel(new GridLayout(2, 1, 0, 6));
        direita.add(rolagemClientes);
        direita.add(rolagemArquivos);

        JSplitPane divisao = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rolagemLog, direita);
        divisao.setResizeWeight(0.7);
        divisao.setDividerLocation(740);

        JPanel raiz = new JPanel(new BorderLayout(6, 6));
        raiz.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        raiz.add(topo, BorderLayout.NORTH);
        raiz.add(divisao, BorderLayout.CENTER);
        setContentPane(raiz);

        botaoIniciar.addActionListener(e -> iniciar());
        spinnerPerda.addChangeListener(e -> {
            if (servidor != null) {
                servidor.getCanal().setPerdaPercentual((Integer) spinnerPerda.getValue());
            }
        });
        botaoPasta.addActionListener(e -> abrir(pasta));
        botaoLog.addActionListener(e -> abrir(arquivoLog));
    }

    public void iniciar() {
        int porta;
        try {
            porta = Integer.parseInt(campoPorta.getText().strip());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Porta inválida.", "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (log == null) {
                log = new LogServidor(arquivoLog);
                log.adicionarOuvinte(linha -> SwingUtilities.invokeLater(() -> adicionarLinhaLog(linha)));
                repositorio = new RepositorioArquivos(pasta);
            }
            ServidorUdp novo = new ServidorUdp(porta, repositorio, log);
            novo.iniciar();
            novo.getCanal().setPerdaPercentual((Integer) spinnerPerda.getValue());
            servidor = novo;
            campoPorta.setEnabled(false);
            botaoIniciar.setEnabled(false);
            List<String> ips = Util.ipsLocais();
            String ipsTexto = ips.isEmpty() ? "127.0.0.1" : String.join("  ou  ", ips);
            rotuloEstado.setForeground(new Color(0, 128, 0));
            rotuloEstado.setText("Servidor ATIVO — nos clientes use IP " + ipsTexto + " e porta " + porta);
            atualizarListas();
        } catch (BindException e) {
            JOptionPane.showMessageDialog(this, "A porta " + porta + " já está em uso (outro servidor aberto?).\n"
                    + "Feche o outro ou escolha outra porta.", "Porta em uso", JOptionPane.ERROR_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Não foi possível iniciar: " + e.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void adicionarLinhaLog(String linha) {
        areaLog.append(linha + "\n");
        int excesso = areaLog.getDocument().getLength() - LIMITE_LOG;
        if (excesso > 0) {
            areaLog.replaceRange("", 0, excesso);
        }
        areaLog.setCaretPosition(areaLog.getDocument().getLength());
    }

    private void atualizarListas() {
        if (servidor == null) {
            return;
        }
        modeloClientes.clear();
        for (ClienteConectado c : servidor.getClientes()) {
            modeloClientes.addElement(c.toString());
        }
        modeloArquivos.clear();
        for (InfoArquivo a : repositorio.getLista().arquivos()) {
            modeloArquivos.addElement(a.nome() + "  (" + Util.formatarTamanho(a.tamanho()) + ")");
        }
    }

    private void abrir(Path caminho) {
        new Thread(() -> {
            try {
                Desktop.getDesktop().open(caminho.toFile());
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Não foi possível abrir " + caminho + "\n" + e.getMessage()));
            }
        }, "abrir-pasta").start();
    }
}
