package miniservidor.cliente;

import miniservidor.comum.CanalUdp;
import miniservidor.comum.FalhaProtocolo;
import miniservidor.comum.InfoArquivo;
import miniservidor.comum.Mensagem;
import miniservidor.comum.Util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static miniservidor.comum.Protocolo.*;

public class ConexaoCliente {

    public enum Estado { DESCONECTADO, CONECTADO, CONEXAO_PERDIDA }

    // chamados fora da thread do Swing
    public interface Ouvinte {
        void listaAtualizada(List<InfoArquivo> arquivos);

        void estadoAlterado(Estado estado, String detalhe);
    }

    private final InetSocketAddress servidor;
    private final String idCliente;
    private final Ouvinte ouvinte;
    private final CanalUdp canal;

    private final Map<Integer, BlockingQueue<Mensagem>> filas = new ConcurrentHashMap<>();
    // downloads terminados há pouco, para reconfirmar fragmentos repetidos
    private final Map<Integer, Long> downloadsConcluidos = new ConcurrentHashMap<>();
    // começa num número aleatório para não repetir ids de uma execução anterior
    private final AtomicInteger proximoId = new AtomicInteger(ThreadLocalRandom.current().nextInt(1, 1_000_000_000));

    private volatile boolean ativo;
    private volatile Estado estado = Estado.DESCONECTADO;
    private volatile long ultimoContato;
    private volatile boolean precisaRegistrar;
    private int versaoLista = Integer.MIN_VALUE;
    private Thread heartbeat;

    public ConexaoCliente(String host, int porta, String idCliente, Ouvinte ouvinte) throws IOException {
        this.servidor = new InetSocketAddress(InetAddress.getByName(host), porta);
        this.idCliente = idCliente;
        this.ouvinte = ouvinte;
        this.canal = new CanalUdp(0);
    }

    public void conectar() throws IOException, InterruptedException, FalhaProtocolo {
        ativo = true;
        ultimoContato = System.currentTimeMillis();
        Thread receptora = new Thread(this::lacoRecepcao, "cliente-receptor");
        receptora.setDaemon(true);
        receptora.start();
        try {
            registrar();
            listar();
        } catch (IOException | InterruptedException | FalhaProtocolo | RuntimeException e) {
            fechar();
            throw e;
        }
        mudarEstado(Estado.CONECTADO, "Conectado a " + Util.endereco(servidor) + " como \"" + idCliente + "\"");
        heartbeat = new Thread(this::lacoHeartbeat, "cliente-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    public void desconectar() {
        if (!ativo) {
            return;
        }
        try {
            canal.enviar(Mensagem.bye(novoId()), servidor); // se o BYE se perder, o servidor remove por timeout
        } catch (IOException ignorada) {
        }
        fechar();
        mudarEstado(Estado.DESCONECTADO, "Desconectado do servidor");
    }

    private void fechar() {
        ativo = false;
        canal.close();
        if (heartbeat != null) {
            heartbeat.interrupt();
        }
    }

    private void registrar() throws IOException, InterruptedException, FalhaProtocolo {
        Mensagem resposta = requisitar(Mensagem.register(novoId(), idCliente));
        if (resposta.tipo == ERROR) {
            throw new FalhaProtocolo(resposta.codigo, resposta.texto);
        }
        synchronized (this) {
            versaoLista = Integer.MIN_VALUE; // o servidor pode ter reiniciado: aceita qualquer versão
        }
        precisaRegistrar = false;
    }

    // a lista que chega é aplicada pela thread receptora
    public void listar() throws IOException, InterruptedException, FalhaProtocolo {
        Mensagem resposta = requisitar(Mensagem.list(novoId()));
        if (resposta.tipo == ERROR) {
            throw new FalhaProtocolo(resposta.codigo, resposta.texto);
        }
    }

    public Mensagem requisitar(Mensagem pedido) throws IOException, InterruptedException, FalhaProtocolo {
        BlockingQueue<Mensagem> fila = abrirFila(pedido.id);
        try {
            return requisitar(pedido, fila);
        } finally {
            fecharFila(pedido.id);
        }
    }

    // Reenvia o pedido a cada 1 s, até 5 vezes. Se o servidor responder 401, registra de novo e repete.
    public Mensagem requisitar(Mensagem pedido, BlockingQueue<Mensagem> fila)
            throws IOException, InterruptedException, FalhaProtocolo {
        Mensagem resposta = enviarEEsperar(pedido, fila);
        if (resposta.tipo == ERROR && resposta.codigo == ERRO_NAO_REGISTRADO && pedido.tipo != REGISTER) {
            registrar();
            resposta = enviarEEsperar(pedido, fila);
        }
        return resposta;
    }

    private Mensagem enviarEEsperar(Mensagem pedido, BlockingQueue<Mensagem> fila)
            throws IOException, InterruptedException, FalhaProtocolo {
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_REQUISICAO; tentativa++) {
            enviar(pedido);
            long limite = System.currentTimeMillis() + TIMEOUT_REQUISICAO_MS;
            long resta;
            while ((resta = limite - System.currentTimeMillis()) > 0) {
                Mensagem m = fila.poll(resta, TimeUnit.MILLISECONDS);
                if (m == null) {
                    break;
                }
                if (ehResposta(pedido, m)) {
                    return m;
                }
            }
        }
        throw new FalhaProtocolo(ERRO_SERVIDOR_INDISPONIVEL, "Servidor indisponível: " + Util.endereco(servidor)
                + " não respondeu ao " + nomeDoTipo(pedido.tipo) + " após " + MAX_TENTATIVAS_REQUISICAO + " tentativas");
    }

    private static boolean ehResposta(Mensagem pedido, Mensagem m) {
        if (m.tipo == ERROR) {
            return true;
        }
        return switch (pedido.tipo) {
            case LIST -> m.tipo == UPDATE_LIST;
            case DOWNLOAD -> m.tipo == METADATA;
            default -> m.tipo == ACK && m.tipoConfirmado == pedido.tipo;
        };
    }

    private void lacoRecepcao() {
        while (ativo) {
            Mensagem m;
            try {
                m = canal.receber();
            } catch (CanalUdp.DatagramaInvalidoException e) {
                continue;
            } catch (IOException e) {
                if (!ativo) {
                    return; // socket fechado ao desconectar
                }
                continue;
            }
            ultimoContato = System.currentTimeMillis();
            try {
                if (m.tipo == UPDATE_LIST) {
                    canal.enviar(Mensagem.ack(UPDATE_LIST, m.id, -1, ""), servidor);
                    aplicarLista(m);
                }
                BlockingQueue<Mensagem> fila = filas.get(m.id);
                if (fila != null) {
                    fila.offer(m);
                } else if (m.tipo == DATA && downloadsConcluidos.containsKey(m.id)) {
                    // o servidor não recebeu o último ACK do download: confirma de novo
                    canal.enviar(Mensagem.ack(DATA, m.id, m.fragmento, ""), servidor);
                } else if (m.tipo == ERROR && m.codigo == ERRO_NAO_REGISTRADO) {
                    precisaRegistrar = true; // resposta a um PING: o servidor reiniciou
                }
            } catch (IOException ignorada) {
            }
        }
    }

    private void aplicarLista(Mensagem m) {
        List<InfoArquivo> arquivos;
        synchronized (this) {
            if (m.versao < versaoLista) {
                return; // retransmissão atrasada de uma lista mais velha
            }
            versaoLista = m.versao;
            arquivos = List.copyOf(m.arquivos);
        }
        ouvinte.listaAtualizada(arquivos);
    }

    private void lacoHeartbeat() {
        while (ativo) {
            try {
                Thread.sleep(INTERVALO_PING_MS);
                canal.enviar(Mensagem.ping(novoId()), servidor); // a resposta só atualiza ultimoContato
                long agora = System.currentTimeMillis();
                downloadsConcluidos.values().removeIf(quando -> agora - quando > MEMORIA_CONCLUIDAS_MS);
                long silencio = agora - ultimoContato;
                if (silencio > SERVIDOR_SILENCIOSO_MS) {
                    if (estado == Estado.CONECTADO) {
                        mudarEstado(Estado.CONEXAO_PERDIDA, "Conexão perdida: o servidor não responde há "
                                + silencio / 1000 + " s. Tentando reconectar...");
                    }
                } else if (estado == Estado.CONEXAO_PERDIDA || precisaRegistrar) {
                    boolean estavaPerdida = estado == Estado.CONEXAO_PERDIDA;
                    registrar();
                    listar();
                    mudarEstado(Estado.CONECTADO, estavaPerdida ? "Conexão restabelecida com o servidor"
                            : "O servidor reiniciou: cliente registrado de novo");
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // tenta de novo no próximo ciclo
            }
        }
    }

    private void mudarEstado(Estado novo, String detalhe) {
        estado = novo;
        ouvinte.estadoAlterado(novo, detalhe);
    }

    public int novoId() {
        return proximoId.getAndIncrement();
    }

    public BlockingQueue<Mensagem> abrirFila(int id) {
        BlockingQueue<Mensagem> fila = new LinkedBlockingQueue<>();
        filas.put(id, fila);
        return fila;
    }

    public void fecharFila(int id) {
        filas.remove(id);
    }

    public void enviar(Mensagem mensagem) throws IOException {
        canal.enviar(mensagem, servidor);
    }

    public void concluirDownload(int id) {
        downloadsConcluidos.put(id, System.currentTimeMillis());
        fecharFila(id);
    }

    public CanalUdp getCanal() {
        return canal;
    }

    public InetSocketAddress getServidor() {
        return servidor;
    }

    public Estado getEstado() {
        return estado;
    }
}
