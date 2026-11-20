package miniservidor.servidor;

import miniservidor.comum.Mensagem;
import miniservidor.comum.Util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

// Um upload ou download rodando na sua própria thread. O servidor tem um só socket, então a
// thread despachante do ServidorUdp coloca na fila de cada sessão os datagramas que são dela.
abstract class Sessao extends Thread {

    protected final ServidorUdp servidor;
    protected final Mensagem pedido;
    protected final String chave;
    protected final InetSocketAddress cliente;
    protected final String endereco;
    protected final int id;
    protected final BlockingQueue<Mensagem> fila = new LinkedBlockingQueue<>();

    Sessao(String nomeThread, ServidorUdp servidor, Mensagem pedido, String chave) {
        super(nomeThread);
        setDaemon(true);
        this.servidor = servidor;
        this.pedido = pedido;
        this.chave = chave;
        this.cliente = pedido.remetente;
        this.endereco = Util.endereco(pedido.remetente);
        this.id = pedido.id;
    }

    void entregar(Mensagem mensagem) {
        fila.offer(mensagem);
    }

    protected void enviar(Mensagem mensagem) throws IOException {
        servidor.enviar(mensagem, cliente);
    }

    protected void tentarEnviar(Mensagem mensagem) {
        try {
            enviar(mensagem);
        } catch (IOException ignorada) {
        }
    }

    protected void log(String operacao, String status, String detalhes) {
        servidor.getLog().registrar(endereco, operacao, status, detalhes);
    }
}
