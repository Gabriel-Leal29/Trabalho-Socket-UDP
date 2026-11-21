package miniservidor.cliente;

import miniservidor.comum.FalhaProtocolo;
import miniservidor.comum.InfoArquivo;
import miniservidor.comum.Protocolo;
import miniservidor.comum.Util;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

public class JanelaCliente extends JFrame implements ConexaoCliente.Ouvinte {

    private enum Tipo { INFO, SUCESSO, AVISO, ERRO }

    private static final Color VERDE = new Color(0, 130, 0);
    private static final Color VERMELHO = new Color(190, 0, 0);
    private static final Color LARANJA = new Color(200, 110, 0);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final Preferences preferencias = Preferences.userNodeForPackage(JanelaCliente.class);
    private final Path pastaDownloads = Path.of(System.getProperty("user.home"), "Downloads", "MiniServidorArquivos");

    private final JTextField campoServidor = new JTextField(12);
    private final JTextField campoPorta = new JTextField(5);
    private final JTextField campoNome = new JTextField(14);
    private final JButton botaoConectar = new JButton("Conectar");
    private final JLabel rotuloEstado = new JLabel("Desconectado");

    private final DefaultTableModel modeloArquivos =
            new DefaultTableModel(new Object[]{"Nome", "Tamanho", "Modificado em"}, 0) {
                @Override
                public boolean isCellEditable(int linha, int coluna) {
                    return false;
                }
            };
    private final JTable tabelaArquivos = new JTable(modeloArquivos);
    private final JLabel rotuloLista = new JLabel(" ");
    private final JButton botaoBaixar = new JButton("Baixar selecionado");
    private final JButton botaoAbrirDownloads = new JButton("Abrir pasta de downloads");

    private final JButton botaoEscolher = new JButton("Escolher arquivo...");
    private final JLabel rotuloArquivo = new JLabel("Nenhum arquivo escolhido");
    private final JButton botaoEnviar = new JButton("Enviar (upload)");
    private final JProgressBar barraUpload = new JProgressBar(0, 1000);
    private final JProgressBar barraDownload = new JProgressBar(0, 1000);
    private final JSpinner spinnerPerda = new JSpinner(new SpinnerNumberModel(0, 0, 50, 5));

    private final JTextPane areaMensagens = new JTextPane();

    // só alterados na thread do Swing
    private ConexaoCliente conexao;
    private boolean conectando;
    private Path arquivoEscolhido;
    private boolean enviando;
    private boolean baixando;

    public JanelaCliente() {
        super("Mini Servidor de Arquivos (UDP) — Cliente");
        campoServidor.setText(preferencias.get("servidor", "127.0.0.1"));
        campoPorta.setText(preferencias.get("porta", String.valueOf(Protocolo.PORTA_PADRAO)));
        campoNome.setText(preferencias.get("nome", nomePadrao()));
        montarTela();
        atualizarBotoes();
        mostrarEstadoVisual(ConexaoCliente.Estado.DESCONECTADO);
        mensagem(Tipo.INFO, "Digite o IP do servidor e clique em Conectar. Downloads vão para " + pastaDownloads);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (conexao != null) {
                    conexao.desconectar();
                }
                dispose();
                System.exit(0);
            }
        });
        setSize(900, 720);
        setMinimumSize(new Dimension(760, 600));
        setLocationRelativeTo(null);
    }

    private void montarTela() {
        // Conexão
        JPanel painelConexao = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        painelConexao.setBorder(BorderFactory.createTitledBorder("Conexão"));
        painelConexao.add(new JLabel("Servidor (IP):"));
        painelConexao.add(campoServidor);
        painelConexao.add(new JLabel("Porta:"));
        painelConexao.add(campoPorta);
        painelConexao.add(new JLabel("Seu nome:"));
        painelConexao.add(campoNome);
        painelConexao.add(botaoConectar);
        painelConexao.add(rotuloEstado);

        // Lista de arquivos
        tabelaArquivos.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabelaArquivos.setRowHeight(22);
        tabelaArquivos.setFillsViewportHeight(true);
        tabelaArquivos.getColumnModel().getColumn(0).setPreferredWidth(430);
        tabelaArquivos.getColumnModel().getColumn(1).setPreferredWidth(100);
        tabelaArquivos.getColumnModel().getColumn(2).setPreferredWidth(140);
        DefaultTableCellRenderer direita = new DefaultTableCellRenderer();
        direita.setHorizontalAlignment(SwingConstants.RIGHT);
        tabelaArquivos.getColumnModel().getColumn(1).setCellRenderer(direita);

        JPanel botoesLista = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        botoesLista.add(botaoBaixar);
        botoesLista.add(botaoAbrirDownloads);
        botoesLista.add(rotuloLista);

        JPanel painelLista = new JPanel(new BorderLayout(4, 4));
        painelLista.setBorder(BorderFactory.createTitledBorder("Arquivos no servidor (lista atualizada automaticamente)"));
        painelLista.add(new JScrollPane(tabelaArquivos), BorderLayout.CENTER);
        painelLista.add(botoesLista, BorderLayout.SOUTH);

        // Transferências
        barraUpload.setStringPainted(true);
        barraUpload.setString("Nenhum upload");
        barraDownload.setStringPainted(true);
        barraDownload.setString("Nenhum download");

        JPanel painelTransferencias = new JPanel(new GridBagLayout());
        painelTransferencias.setBorder(BorderFactory.createTitledBorder("Transferências"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy = 0;
        c.gridx = 0;
        painelTransferencias.add(botaoEscolher, c);
        c.gridx = 1;
        c.weightx = 1;
        painelTransferencias.add(rotuloArquivo, c);
        c.gridx = 2;
        c.weightx = 0;
        painelTransferencias.add(botaoEnviar, c);

        c.gridy = 1;
        c.gridx = 0;
        painelTransferencias.add(new JLabel("Upload:"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        painelTransferencias.add(barraUpload, c);

        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 1;
        painelTransferencias.add(new JLabel("Download:"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        painelTransferencias.add(barraDownload, c);

        JPanel linhaPerda = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        linhaPerda.add(spinnerPerda);
        linhaPerda.add(new JLabel("% dos datagramas enviados por este cliente são descartados (para testar a confiabilidade)"));
        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 1;
        painelTransferencias.add(new JLabel("Perda simulada:"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        painelTransferencias.add(linhaPerda, c);

        // Mensagens
        areaMensagens.setEditable(false);
        JScrollPane rolagemMensagens = new JScrollPane(areaMensagens);
        rolagemMensagens.setPreferredSize(new Dimension(800, 170));
        rolagemMensagens.setBorder(BorderFactory.createTitledBorder("Mensagens e status"));

        JPanel centro = new JPanel(new BorderLayout(6, 6));
        centro.add(painelLista, BorderLayout.CENTER);
        centro.add(painelTransferencias, BorderLayout.SOUTH);

        JPanel raiz = new JPanel(new BorderLayout(6, 6));
        raiz.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        raiz.add(painelConexao, BorderLayout.NORTH);
        raiz.add(centro, BorderLayout.CENTER);
        raiz.add(rolagemMensagens, BorderLayout.SOUTH);
        setContentPane(raiz);

        // Ações
        botaoConectar.addActionListener(e -> alternarConexao());
        botaoEscolher.addActionListener(e -> escolherArquivo());
        botaoEnviar.addActionListener(e -> enviarArquivo());
        botaoBaixar.addActionListener(e -> baixarSelecionado());
        botaoAbrirDownloads.addActionListener(e -> abrirPastaDownloads());
        tabelaArquivos.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    baixarSelecionado();
                }
            }
        });
        spinnerPerda.addChangeListener(e -> {
            if (conexao != null) {
                conexao.getCanal().setPerdaPercentual((Integer) spinnerPerda.getValue());
            }
        });
    }

    private void alternarConexao() {
        if (conexao != null) {
            ConexaoCliente antiga = conexao;
            conexao = null;
            atualizarBotoes();
            modeloArquivos.setRowCount(0);
            rotuloLista.setText(" ");
            new Thread(antiga::desconectar, "desconectar").start();
            return;
        }

        String host = campoServidor.getText().strip();
        String nome = campoNome.getText().strip().isEmpty() ? nomePadrao() : campoNome.getText().strip();
        int porta;
        try {
            porta = Integer.parseInt(campoPorta.getText().strip());
            if (porta < 1 || porta > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            mensagem(Tipo.ERRO, "Porta inválida.");
            return;
        }
        if (host.isEmpty()) {
            mensagem(Tipo.ERRO, "Digite o IP do servidor.");
            return;
        }
        preferencias.put("servidor", host);
        preferencias.put("porta", String.valueOf(porta));
        preferencias.put("nome", nome);

        conectando = true;
        atualizarBotoes();
        mensagem(Tipo.INFO, "Conectando a " + host + ":" + porta + " (REGISTER)...");
        int perda = (Integer) spinnerPerda.getValue();

        Thread t = new Thread(() -> {
            try {
                ConexaoCliente nova = new ConexaoCliente(host, porta, nome, this);
                nova.getCanal().setPerdaPercentual(perda);
                nova.conectar();
                naTela(() -> conexao = nova);
            } catch (UnknownHostException e) {
                naTela(() -> mensagem(Tipo.ERRO, "Endereço de servidor inválido: " + host));
            } catch (FalhaProtocolo e) {
                naTela(() -> mensagem(Tipo.ERRO, e.getMessage()
                        + ". Confira o IP, a porta, se o servidor está aberto e o firewall."));
            } catch (Exception e) {
                naTela(() -> mensagem(Tipo.ERRO, "Falha ao conectar: " + descrever(e)));
            } finally {
                naTela(() -> {
                    conectando = false;
                    atualizarBotoes();
                });
            }
        }, "conectar");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void listaAtualizada(List<InfoArquivo> arquivos) {
        naTela(() -> mostrarLista(arquivos));
    }

    @Override
    public void estadoAlterado(ConexaoCliente.Estado estado, String detalhe) {
        naTela(() -> {
            mostrarEstadoVisual(estado);
            Tipo tipo = switch (estado) {
                case CONECTADO -> Tipo.SUCESSO;
                case CONEXAO_PERDIDA -> Tipo.ERRO;
                default -> Tipo.INFO;
            };
            mensagem(tipo, detalhe);
        });
    }

    private void mostrarEstadoVisual(ConexaoCliente.Estado estado) {
        switch (estado) {
            case CONECTADO -> {
                rotuloEstado.setIcon(bolinha(VERDE));
                rotuloEstado.setText("Conectado");
            }
            case CONEXAO_PERDIDA -> {
                rotuloEstado.setIcon(bolinha(VERMELHO));
                rotuloEstado.setText("Conexão perdida");
            }
            default -> {
                rotuloEstado.setIcon(bolinha(Color.GRAY));
                rotuloEstado.setText("Desconectado");
            }
        }
    }

    private void mostrarLista(List<InfoArquivo> arquivos) {
        if (conexao == null && !conectando) {
            return; // resposta atrasada de uma conexão que já foi encerrada
        }
        String selecionado = nomeSelecionado();
        modeloArquivos.setRowCount(0);
        for (InfoArquivo a : arquivos) {
            String data = DATA_HORA.format(Instant.ofEpochMilli(a.modificado()).atZone(ZoneId.systemDefault()));
            modeloArquivos.addRow(new Object[]{a.nome(), Util.formatarTamanho(a.tamanho()), data});
        }
        for (int i = 0; i < modeloArquivos.getRowCount(); i++) {
            if (modeloArquivos.getValueAt(i, 0).equals(selecionado)) {
                tabelaArquivos.setRowSelectionInterval(i, i);
            }
        }
        rotuloLista.setText(arquivos.size() + " arquivo(s) · lista atualizada às " + HORA.format(LocalTime.now()));
    }

    private void escolherArquivo() {
        JFileChooser seletor = new JFileChooser(preferencias.get("ultimaPasta", System.getProperty("user.home")));
        seletor.setDialogTitle("Escolha o arquivo para enviar (qualquer tipo)");
        if (seletor.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        arquivoEscolhido = seletor.getSelectedFile().toPath();
        if (arquivoEscolhido.getParent() != null) {
            preferencias.put("ultimaPasta", arquivoEscolhido.getParent().toString());
        }
        String tamanho;
        try {
            tamanho = Util.formatarTamanho(Files.size(arquivoEscolhido));
        } catch (IOException e) {
            tamanho = "?";
        }
        rotuloArquivo.setText(arquivoEscolhido.getFileName() + "  (" + tamanho + ")");
        barraUpload.setValue(0);
        barraUpload.setString("Pronto para enviar");
        atualizarBotoes();
    }

    private void enviarArquivo() {
        if (conexao == null || arquivoEscolhido == null || enviando) {
            return;
        }
        ConexaoCliente c = conexao;
        Path arquivo = arquivoEscolhido;
        if (!Files.isRegularFile(arquivo)) {
            mensagem(Tipo.ERRO, "Arquivo não encontrado: " + arquivo);
            return;
        }
        String nome = arquivo.getFileName().toString();
        enviando = true;
        atualizarBotoes();
        barraUpload.setValue(0);
        barraUpload.setString("Calculando hash SHA-256...");
        mensagem(Tipo.INFO, "Enviando \"" + nome + "\"...");

        Thread t = new Thread(() -> {
            try {
                TarefaUpload.Resultado r = TarefaUpload.executar(c, arquivo, progressoNaBarra(barraUpload));
                String renomeado = r.nomeFinal().equals(nome) ? ""
                        : ". Já existia um arquivo com esse nome: salvo como \"" + r.nomeFinal() + "\"";
                naTela(() -> {
                    barraUpload.setValue(1000);
                    barraUpload.setString("Upload concluído — " + nome);
                    mensagem(Tipo.SUCESSO, "Upload concluído: \"" + nome + "\" (" + Util.formatarTamanho(r.tamanho())
                            + ", " + r.fragmentos() + " fragmentos, " + r.retransmissoes() + " retransmissões, "
                            + segundos(r.duracaoMs()) + ")" + renomeado);
                });
            } catch (FalhaProtocolo e) {
                naTela(() -> falhou(barraUpload, "Upload", e.getMessage() + " [código " + e.getCodigo() + "]"));
            } catch (Exception e) {
                naTela(() -> falhou(barraUpload, "Upload", descrever(e)));
            } finally {
                naTela(() -> {
                    enviando = false;
                    atualizarBotoes();
                });
            }
        }, "upload");
        t.setDaemon(true);
        t.start();
    }

    private void baixarSelecionado() {
        if (conexao == null || baixando) {
            return;
        }
        String nome = nomeSelecionado();
        if (nome == null) {
            mensagem(Tipo.AVISO, "Selecione um arquivo da lista para baixar.");
            return;
        }
        ConexaoCliente c = conexao;
        baixando = true;
        atualizarBotoes();
        barraDownload.setValue(0);
        barraDownload.setString("Pedindo \"" + nome + "\"...");
        mensagem(Tipo.INFO, "Baixando \"" + nome + "\"...");

        Thread t = new Thread(() -> {
            try {
                TarefaDownload.Resultado r = TarefaDownload.executar(c, nome, pastaDownloads, progressoNaBarra(barraDownload));
                naTela(() -> {
                    barraDownload.setValue(1000);
                    barraDownload.setString("Download concluído — " + nome);
                    mensagem(Tipo.SUCESSO, "Download concluído: \"" + nome + "\" salvo em " + r.destino()
                            + " (" + Util.formatarTamanho(r.tamanho()) + ", SHA-256 conferido, fora de ordem: "
                            + r.foraDeOrdem() + ", duplicados: " + r.duplicados() + ", " + segundos(r.duracaoMs()) + ")");
                });
            } catch (FalhaProtocolo e) {
                String motivo = e.getCodigo() == Protocolo.ERRO_ARQUIVO_INEXISTENTE
                        ? "o arquivo não existe mais no servidor [código 404]"
                        : e.getMessage() + " [código " + e.getCodigo() + "]";
                naTela(() -> falhou(barraDownload, "Download", motivo));
            } catch (Exception e) {
                naTela(() -> falhou(barraDownload, "Download", descrever(e)));
            } finally {
                naTela(() -> {
                    baixando = false;
                    atualizarBotoes();
                });
            }
        }, "download");
        t.setDaemon(true);
        t.start();
    }

    private void abrirPastaDownloads() {
        new Thread(() -> {
            try {
                Files.createDirectories(pastaDownloads);
                Desktop.getDesktop().open(pastaDownloads.toFile());
            } catch (IOException | UnsupportedOperationException e) {
                naTela(() -> mensagem(Tipo.ERRO, "Não foi possível abrir " + pastaDownloads + ": " + e.getMessage()));
            }
        }, "abrir-pasta").start();
    }

    // atualiza a barra no máximo 1000 vezes por transferência, para não sobrecarregar a EDT
    private OuvinteProgresso progressoNaBarra(JProgressBar barra) {
        int[] ultimo = {-1};
        return (feito, total) -> {
            int permil = total <= 0 ? 1000 : (int) (feito * 1000 / total);
            if (permil == ultimo[0]) {
                return;
            }
            ultimo[0] = permil;
            String texto = permil / 10 + "%  (" + Util.formatarTamanho(feito) + " de " + Util.formatarTamanho(total) + ")";
            naTela(() -> {
                barra.setValue(permil);
                barra.setString(texto);
            });
        };
    }

    private void falhou(JProgressBar barra, String operacao, String motivo) {
        barra.setString(operacao + " falhou");
        mensagem(Tipo.ERRO, operacao + " falhou: " + motivo);
    }

    private void atualizarBotoes() {
        boolean conectado = conexao != null;
        botaoConectar.setText(conectado ? "Desconectar" : "Conectar");
        botaoConectar.setEnabled(!conectando);
        boolean editavel = !conectado && !conectando;
        campoServidor.setEnabled(editavel);
        campoPorta.setEnabled(editavel);
        campoNome.setEnabled(editavel);
        botaoEscolher.setEnabled(!enviando);
        botaoEnviar.setEnabled(conectado && arquivoEscolhido != null && !enviando);
        botaoBaixar.setEnabled(conectado && !baixando);
    }

    private String nomeSelecionado() {
        int linha = tabelaArquivos.getSelectedRow();
        if (linha < 0) {
            return null;
        }
        return (String) modeloArquivos.getValueAt(tabelaArquivos.convertRowIndexToModel(linha), 0);
    }

    private void mensagem(Tipo tipo, String texto) {
        StyledDocument doc = areaMensagens.getStyledDocument();
        SimpleAttributeSet estilo = new SimpleAttributeSet();
        Color cor = switch (tipo) {
            case SUCESSO -> VERDE;
            case ERRO -> VERMELHO;
            case AVISO -> LARANJA;
            default -> areaMensagens.getForeground();
        };
        StyleConstants.setForeground(estilo, cor);
        StyleConstants.setBold(estilo, tipo != Tipo.INFO);
        String rotulo = switch (tipo) {
            case SUCESSO -> "OK";
            case ERRO -> "ERRO";
            case AVISO -> "AVISO";
            default -> "INFO";
        };
        try {
            doc.insertString(doc.getLength(), "[" + HORA.format(LocalTime.now()) + "] " + rotulo + ": " + texto + "\n", estilo);
        } catch (BadLocationException ignorada) {
        }
        areaMensagens.setCaretPosition(doc.getLength());
    }

    private static void naTela(Runnable acao) {
        SwingUtilities.invokeLater(acao);
    }

    private static String descrever(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String segundos(long ms) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%.1f s", ms / 1000.0);
    }

    private static String nomePadrao() {
        String so = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String maquina = so.contains("win") ? "windows" : so.contains("mac") ? "mac" : "pc";
        return System.getProperty("user.name", "cliente") + "-" + maquina;
    }

    private static Icon bolinha(Color cor) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(cor);
                g2.fillOval(x, y + 1, 11, 11);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 12;
            }

            @Override
            public int getIconHeight() {
                return 13;
            }
        };
    }
}
