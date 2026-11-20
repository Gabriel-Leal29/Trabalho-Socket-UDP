package miniservidor.comum;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

// Perda simulada: com X%, cada datagrama enviado tem X% de chance de ser descartado.
// Numa rede local quase nada se perde, então é assim que dá para ver as retransmissões.
public class CanalUdp implements Closeable {

    public static class DatagramaInvalidoException extends IOException {
        private final InetSocketAddress remetente;

        public DatagramaInvalidoException(InetSocketAddress remetente, String motivo) {
            super(motivo);
            this.remetente = remetente;
        }

        public InetSocketAddress getRemetente() {
            return remetente;
        }
    }

    private final DatagramSocket socket;
    private final byte[] bufferRecepcao = new byte[65535];
    private volatile int perdaPercentual;
    private final AtomicLong descartados = new AtomicLong();

    public CanalUdp(int porta) throws SocketException {
        socket = new DatagramSocket(porta);
        try {
            // buffer maior para caber uma janela inteira de fragmentos
            socket.setReceiveBufferSize(1 << 20);
            socket.setSendBufferSize(1 << 20);
        } catch (SocketException ignorada) {
        }
    }

    public void enviar(Mensagem mensagem, SocketAddress destino) throws IOException {
        byte[] bytes = mensagem.paraBytes();
        if (perdaPercentual > 0 && ThreadLocalRandom.current().nextInt(100) < perdaPercentual) {
            descartados.incrementAndGet();
            return;
        }
        socket.send(new DatagramPacket(bytes, bytes.length, destino));
    }

    public Mensagem receber() throws IOException {
        DatagramPacket pacote = new DatagramPacket(bufferRecepcao, bufferRecepcao.length);
        socket.receive(pacote);
        InetSocketAddress remetente = (InetSocketAddress) pacote.getSocketAddress();
        try {
            Mensagem mensagem = Mensagem.deBytes(pacote.getData(), pacote.getLength());
            mensagem.remetente = remetente;
            return mensagem;
        } catch (IOException e) {
            throw new DatagramaInvalidoException(remetente, e.getMessage());
        }
    }

    public void setPerdaPercentual(int perdaPercentual) {
        this.perdaPercentual = Math.max(0, Math.min(100, perdaPercentual));
    }

    public int getPerdaPercentual() {
        return perdaPercentual;
    }

    public long getDescartados() {
        return descartados.get();
    }

    public int getPortaLocal() {
        return socket.getLocalPort();
    }

    public boolean isFechado() {
        return socket.isClosed();
    }

    @Override
    public void close() {
        socket.close();
    }
}
