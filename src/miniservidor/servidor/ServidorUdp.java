package miniservidor.servidor;

import miniservidor.comum.CanalUdp;
import miniservidor.comum.InfoArquivo;
import miniservidor.comum.Mensagem;
import miniservidor.comum.Util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static miniservidor.comum.Protocolo.*;

// Threads do servidor:
// - despachante: a única que lê o socket. Responde REGISTER, LIST, PING e BYE e entrega
//   DATA, ACK e ERROR para a sessão da transferência certa;
// - uma thread por upload/download (SessaoUpload e SessaoDownload);
// - um pool que manda o UPDATE_LIST para cada cliente e espera o ACK;
// - monitor: remove clientes que pararam de mandar PING e confere se a pasta central mudou.
public class ServidorUdp {

    private final int porta;
    private final RepositorioArquivos repositorio;
    private final LogServidor log;
    private CanalUdp canal;
    private volatile boolean rodando;

    // chaves: "IP:porta" para clientes e "IP:porta#id" para transferências
    private final Map<String, ClienteConectado> clientes = new ConcurrentHashMap<>();
    private final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();
    // uploads que terminaram há pouco (erro == null é sucesso), para reconfirmar o último fragmento
    private final Map<String, Concluida> concluidas = new ConcurrentHashMap<>();
    private record Concluida(long quando, Mensagem erro) {
    }
    private final Map<String, CountDownLatch> listasSemAck = new ConcurrentHashMap<>();
    private final AtomicInteger proximoIdServidor = new AtomicInteger(1);
    private final ExecutorService envioDeListas = Executors.newCachedThreadPool(tarefa -> {
        Thread t = new Thread(tarefa, "envio-update-list");
        t.setDaemon(true);
        return t;
    });

    public ServidorUdp(int porta, RepositorioArquivos repositorio, LogServidor log) {
        this.porta = porta;
        this.repositorio = repositorio;
        this.log = log;
    }

    public synchronized void iniciar() throws IOException {
        canal = new CanalUdp(porta);
        rodando = true;
        repositorio.atualizarLista();
        log.registrar(enderecoLocal(), "SERVIDOR", "INICIO", "ouvindo UDP na porta " + porta
                + " | IPs: " + String.join(", ", Util.ipsLocais()) + " | pasta central: " + repositorio.getPasta());

        Thread despachante = new Thread(this::lacoRecepcao, "despachante-udp");
        despachante.setDaemon(true);
        despachante.start();

        Thread monitor = new Thread(this::lacoMonitor, "monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    public synchronized void parar() {
        if (!rodando) {
            return;
        }
        rodando = false;
        canal.close();
        envioDeListas.shutdownNow();
        sessoes.values().forEach(Thread::interrupt);
        log.registrar(enderecoLocal(), "SERVIDOR", "FIM", "servidor encerrado");
    }

    private void lacoRecepcao() {
        while (rodando) {
            try {
                tratar(canal.receber());
            } catch (CanalUdp.DatagramaInvalidoException e) {
                log.registrar(Util.endereco(e.getRemetente()), "DATAGRAMA", "ERRO",
                        "datagrama fora do protocolo descartado: " + e.getMessage());
            } catch (IOException e) {
                if (rodando) {
                    log.registrar(enderecoLocal(), "SERVIDOR", "ERRO", "falha ao receber datagrama: " + e.getMessage());
                }
            } catch (RuntimeException e) {
                log.registrar(enderecoLocal(), "SERVIDOR", "ERRO", "erro inesperado: " + e);
            }
        }
    }

    private void tratar(Mensagem m) throws IOException {
        String endereco = Util.endereco(m.remetente);
        ClienteConectado cliente = clientes.get(endereco);
        if (cliente != null) {
            cliente.tocar(); // qualquer datagrama conta como sinal de vida
        }

        switch (m.tipo) {
            case REGISTER -> registrar(m, endereco, cliente);
            case PING -> {
                if (cliente != null) {
                    enviar(Mensagem.ack(PING, m.id, -1, ""), m.remetente);
                } else {
                    enviar(Mensagem.error(m.id, ERRO_NAO_REGISTRADO, "Cliente não registrado"), m.remetente);
                }
            }
            case BYE -> {
                enviar(Mensagem.ack(BYE, m.id, -1, ""), m.remetente);
                ClienteConectado saiu = clientes.remove(endereco);
                if (saiu != null) {
                    log.registrar(endereco, "DESCONEXAO", "OK", "cliente \"" + saiu.getId() + "\" saiu ("
                            + clientes.size() + " ativo(s))");
                }
            }
            case LIST -> {
                if (exigirRegistro(m, cliente, endereco)) {
                    Mensagem lista = montarLista(m.id);
                    enviar(lista, m.remetente);
                    log.registrar(endereco, "LIST", "OK", "lista enviada (" + lista.arquivos.size()
                            + " arquivo(s), versão " + lista.versao + ")");
                }
            }
            case UPLOAD, DOWNLOAD -> iniciarTransferencia(m, endereco, cliente);
            case ACK -> {
                if (m.tipoConfirmado == UPDATE_LIST) {
                    CountDownLatch aviso = listasSemAck.get(chave(endereco, m.id));
                    if (aviso != null) {
                        aviso.countDown();
                    }
                } else {
                    encaminhar(m, endereco);
                }
            }
            case DATA, ERROR -> encaminhar(m, endereco);
            default -> log.registrar(endereco, nomeDoTipo(m.tipo), "ERRO", "comando inesperado vindo de um cliente");
        }
    }

    private void registrar(Mensagem m, String endereco, ClienteConectado atual) throws IOException {
        String idCliente = m.texto.isBlank() ? "anônimo" : m.texto.strip();
        enviar(Mensagem.ack(REGISTER, m.id, -1, ""), m.remetente);
        if (atual != null && atual.getId().equals(idCliente)) {
            return; // REGISTER repetido porque o nosso ACK se perdeu
        }
        clientes.put(endereco, new ClienteConectado(idCliente, m.remetente));
        log.registrar(endereco, "CONEXAO", "OK", "cliente \"" + idCliente + "\" registrado ("
                + clientes.size() + " ativo(s))");
    }

    private boolean exigirRegistro(Mensagem m, ClienteConectado cliente, String endereco) throws IOException {
        if (cliente != null) {
            return true;
        }
        enviar(Mensagem.error(m.id, ERRO_NAO_REGISTRADO, "Cliente não registrado: envie REGISTER"), m.remetente);
        log.registrar(endereco, nomeDoTipo(m.tipo), "ERRO", "pedido de cliente não registrado — respondido com ERROR 401");
        return false;
    }

    private void iniciarTransferencia(Mensagem m, String endereco, ClienteConectado cliente) throws IOException {
        String chave = chave(endereco, m.id);
        Sessao existente = sessoes.get(chave);
        if (existente != null) {
            existente.entregar(m); // pedido repetido: a sessão reenvia a resposta
            return;
        }
        if (concluidas.containsKey(chave)) {
            return; // pedido atrasado de uma transferência que já terminou
        }
        if (!exigirRegistro(m, cliente, endereco)) {
            return;
        }
        Sessao nova = m.tipo == UPLOAD ? new SessaoUpload(this, m, chave) : new SessaoDownload(this, m, chave);
        sessoes.put(chave, nova);
        nova.start();
    }

    private void encaminhar(Mensagem m, String endereco) throws IOException {
        String chave = chave(endereco, m.id);
        Sessao sessao = sessoes.get(chave);
        if (sessao != null) {
            sessao.entregar(m);
            return;
        }
        if (m.tipo != DATA) {
            return; // ACK/ERROR atrasados de transferências já encerradas são ignorados
        }
        Concluida concluida = concluidas.get(chave);
        if (concluida != null && concluida.erro() == null) {
            enviar(Mensagem.ack(DATA, m.id, m.fragmento, ""), m.remetente);
            log.registrar(endereco, "DATA", "DUPLICADO", "upload #" + m.id + " já concluído: fragmento " + m.fragmento
                    + " reenviado pelo cliente (último ACK se perdeu) — ACK reenviado");
        } else if (concluida != null) {
            enviar(concluida.erro(), m.remetente);
        } else {
            log.registrar(endereco, "DATA", "ERRO", "fragmento " + m.fragmento + " de transferência desconhecida #"
                    + m.id + " — respondido com ERROR 410");
            enviar(Mensagem.error(m.id, ERRO_TRANSFERENCIA_DESCONHECIDA,
                    "Transferência desconhecida (expirou ou o servidor reiniciou)"), m.remetente);
        }
    }

    void registrarConcluida(String chave, Mensagem erro) {
        concluidas.put(chave, new Concluida(System.currentTimeMillis(), erro));
    }

    void arquivosAlterados() {
        if (repositorio.atualizarLista()) {
            notificarClientes();
        }
    }

    private void notificarClientes() {
        for (ClienteConectado cliente : clientes.values()) {
            envioDeListas.submit(() -> enviarListaConfiavel(cliente));
        }
    }

    private void enviarListaConfiavel(ClienteConectado cliente) {
        int id = proximoIdServidor.getAndIncrement();
        String chave = chave(cliente.getEnderecoTexto(), id);
        CountDownLatch confirmado = new CountDownLatch(1);
        listasSemAck.put(chave, confirmado);
        try {
            Mensagem lista = montarLista(id);
            for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_FRAGMENTO; tentativa++) {
                if (tentativa > 1) {
                    log.registrar(cliente.getEnderecoTexto(), "UPDATE_LIST", "RETRANSMISSAO",
                            "versão " + lista.versao + " sem ACK, tentativa " + tentativa);
                }
                canal.enviar(lista, cliente.getEndereco());
                if (confirmado.await(TIMEOUT_UPDATE_LIST_MS, TimeUnit.MILLISECONDS)) {
                    log.registrar(cliente.getEnderecoTexto(), "UPDATE_LIST", "OK", "versão " + lista.versao
                            + " entregue a \"" + cliente.getId() + "\" (" + lista.arquivos.size() + " arquivo(s))");
                    return;
                }
            }
            log.registrar(cliente.getEnderecoTexto(), "UPDATE_LIST", "TIMEOUT", "\"" + cliente.getId()
                    + "\" não confirmou a versão " + lista.versao + " após " + MAX_TENTATIVAS_FRAGMENTO + " tentativas");
        } catch (IOException e) {
            log.registrar(cliente.getEnderecoTexto(), "UPDATE_LIST", "ERRO", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            listasSemAck.remove(chave);
        }
    }

    private Mensagem montarLista(int id) {
        RepositorioArquivos.Lista lista = repositorio.getLista();
        List<InfoArquivo> arquivos = new ArrayList<>(lista.arquivos());
        Mensagem m = Mensagem.updateList(id, lista.versao(), arquivos);
        while (m.paraBytes().length > TAMANHO_MAX_DATAGRAMA && !arquivos.isEmpty()) {
            arquivos.remove(arquivos.size() - 1);
            m = Mensagem.updateList(id, lista.versao(), arquivos);
        }
        return m;
    }

    private void lacoMonitor() {
        int ciclo = 0;
        while (rodando) {
            try {
                Thread.sleep(1000);
                long agora = System.currentTimeMillis();
                concluidas.values().removeIf(c -> agora - c.quando() > MEMORIA_CONCLUIDAS_MS);
                for (ClienteConectado c : clientes.values()) {
                    long silencio = agora - c.getUltimoContato();
                    if (silencio > CLIENTE_INATIVO_MS && clientes.remove(c.getEnderecoTexto(), c)) {
                        log.registrar(c.getEnderecoTexto(), "DESCONEXAO", "TIMEOUT", "cliente \"" + c.getId()
                                + "\" sem sinal de vida há " + silencio / 1000 + " s — removido ("
                                + clientes.size() + " ativo(s))");
                    }
                }
                // a cada 2 s confere se alguém apagou ou colocou arquivo na pasta à mão
                if (++ciclo % 2 == 0 && repositorio.atualizarLista()) {
                    log.registrar(enderecoLocal(), "PASTA", "ALTERACAO", "conteúdo da pasta central mudou — versão "
                            + repositorio.getLista().versao() + ", enviando UPDATE_LIST");
                    notificarClientes();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.registrar(enderecoLocal(), "SERVIDOR", "ERRO", "monitor: " + e);
            }
        }
    }

    void enviar(Mensagem mensagem, InetSocketAddress destino) throws IOException {
        canal.enviar(mensagem, destino);
    }

    void removerSessao(String chave, Sessao sessao) {
        sessoes.remove(chave, sessao);
    }

    private static String chave(String endereco, int id) {
        return endereco + "#" + id;
    }

    private String enderecoLocal() {
        return "0.0.0.0:" + porta;
    }

    public CanalUdp getCanal() {
        return canal;
    }

    public LogServidor getLog() {
        return log;
    }

    public RepositorioArquivos getRepositorio() {
        return repositorio;
    }

    public int getPorta() {
        return porta;
    }

    public int getTransferenciasAtivas() {
        return sessoes.size();
    }

    public List<ClienteConectado> getClientes() {
        List<ClienteConectado> lista = new ArrayList<>(clientes.values());
        lista.sort(Comparator.comparing(ClienteConectado::getId, String.CASE_INSENSITIVE_ORDER));
        return lista;
    }
}
