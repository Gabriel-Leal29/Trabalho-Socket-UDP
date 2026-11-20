package miniservidor.comum;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static miniservidor.comum.Protocolo.*;

// Envio confiável de um arquivo (upload no cliente, download no servidor).
// Janela deslizante com repetição seletiva: até JANELA fragmentos sem ACK, cada um com o seu
// timeout. Só o fragmento perdido é reenviado, por timeout ou pela retransmissão rápida.
// Os ACKs chegam pela fila, que é preenchida pela thread que lê o socket.
public class EmissorJanela {

    public interface Ouvinte {
        default void progresso(int confirmados, int total) {
        }

        default void retransmissao(int fragmento, int tentativa, boolean rapida) {
        }
    }

    public record Resultado(int fragmentos, int retransmissoes, long duracaoMs) {
    }

    private final CanalUdp canal;
    private final InetSocketAddress destino;
    private final int idTransferencia;
    private final Path arquivo;
    private final long tamanho;
    private final int total;
    private final BlockingQueue<Mensagem> fila;
    private final Ouvinte ouvinte;

    // Estado de cada fragmento (índice = nº do fragmento)
    private boolean[] confirmado;
    private long[] prazo;
    private int[] timeouts;
    private int[] envios;
    private long[] ordemEnvio;
    private int[] acksPosteriores; // ACKs de fragmentos enviados depois deste
    private long contadorEnvios;
    private int retransmissoes;

    public EmissorJanela(CanalUdp canal, InetSocketAddress destino, int idTransferencia,
                         Path arquivo, long tamanho, BlockingQueue<Mensagem> fila, Ouvinte ouvinte) {
        this.canal = canal;
        this.destino = destino;
        this.idTransferencia = idTransferencia;
        this.arquivo = arquivo;
        this.tamanho = tamanho;
        this.total = Util.totalFragmentos(tamanho);
        this.fila = fila;
        this.ouvinte = ouvinte;
    }

    public Resultado enviar() throws IOException, InterruptedException, FalhaProtocolo {
        long inicio = System.currentTimeMillis();
        confirmado = new boolean[total];
        prazo = new long[total];
        timeouts = new int[total];
        envios = new int[total];
        ordemEnvio = new long[total];
        acksPosteriores = new int[total];
        int base = 0;    // fragmento mais antigo ainda sem ACK
        int proximo = 0; // próximo fragmento que nunca foi enviado
        int confirmados = 0;

        try (RandomAccessFile entrada = new RandomAccessFile(arquivo.toFile(), "r")) {
            while (confirmados < total) {

                // envia os fragmentos novos que cabem na janela
                while (proximo < total && proximo < base + JANELA) {
                    enviarFragmento(entrada, proximo);
                    proximo++;
                }

                // espera ACKs até vencer o prazo mais próximo
                long menorPrazo = Long.MAX_VALUE;
                for (int i = base; i < proximo; i++) {
                    if (!confirmado[i]) {
                        menorPrazo = Math.min(menorPrazo, prazo[i]);
                    }
                }
                long espera = Math.min(TIMEOUT_FRAGMENTO_MS, Math.max(1, menorPrazo - System.currentTimeMillis()));
                Mensagem resposta = fila.poll(espera, TimeUnit.MILLISECONDS);
                while (resposta != null) {
                    if (resposta.tipo == ERROR) {
                        throw new FalhaProtocolo(resposta.codigo, resposta.texto);
                    }
                    if (resposta.tipo == ACK && resposta.tipoConfirmado == DATA) {
                        int f = resposta.fragmento;
                        if (f >= 0 && f < proximo && !confirmado[f]) {
                            confirmado[f] = true;
                            confirmados++;
                            ouvinte.progresso(confirmados, total);
                            retransmissaoRapida(entrada, base, proximo, f);
                        }
                    }
                    resposta = fila.poll();
                }
                while (base < total && confirmado[base]) {
                    base++;
                }

                // reenvia os fragmentos com prazo vencido
                long agora = System.currentTimeMillis();
                for (int i = base; i < proximo; i++) {
                    if (confirmado[i] || agora < prazo[i]) {
                        continue;
                    }
                    timeouts[i]++;
                    if (timeouts[i] >= MAX_TENTATIVAS_FRAGMENTO) {
                        throw new FalhaProtocolo(ERRO_TIMEOUT, "o outro lado não confirmou o fragmento " + i
                                + " em " + MAX_TENTATIVAS_FRAGMENTO + " tentativas (conexão perdida)");
                    }
                    reenviar(entrada, i, false);
                }
            }
        }
        return new Resultado(total, retransmissoes, System.currentTimeMillis() - inicio);
    }

    // Chegou o ACK de f: os fragmentos enviados antes de f e ainda sem ACK provavelmente se perderam.
    // Com 3 ACKs posteriores o fragmento é reenviado sem esperar o timeout (como os 3 ACKs duplicados do TCP).
    private void retransmissaoRapida(RandomAccessFile entrada, int base, int proximo, int f) throws IOException {
        for (int i = base; i < proximo; i++) {
            if (!confirmado[i] && ordemEnvio[i] < ordemEnvio[f]
                    && ++acksPosteriores[i] == ACKS_PARA_RETRANSMISSAO_RAPIDA) {
                reenviar(entrada, i, true);
            }
        }
    }

    private void reenviar(RandomAccessFile entrada, int numero, boolean rapida) throws IOException {
        retransmissoes++;
        enviarFragmento(entrada, numero);
        ouvinte.retransmissao(numero, envios[numero], rapida);
    }

    // 250 ms, 500 ms, 1 s, 1 s, ...
    private static long timeout(int timeoutsSeguidos) {
        long multiplicador = Math.min(1L << Math.min(timeoutsSeguidos, 10), BACKOFF_MAXIMO);
        return TIMEOUT_FRAGMENTO_MS * multiplicador;
    }

    private void enviarFragmento(RandomAccessFile entrada, int numero) throws IOException {
        long inicio = (long) numero * TAMANHO_FRAGMENTO;
        int quantidade = (int) Math.min(TAMANHO_FRAGMENTO, tamanho - inicio);
        byte[] payload = new byte[Math.max(0, quantidade)];
        entrada.seek(inicio);
        entrada.readFully(payload);
        canal.enviar(Mensagem.data(idTransferencia, numero, payload), destino);

        envios[numero]++;
        ordemEnvio[numero] = ++contadorEnvios;
        acksPosteriores[numero] = 0;
        prazo[numero] = System.currentTimeMillis() + timeout(timeouts[numero]);
    }
}
