package miniservidor.comum;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.BitSet;

import static miniservidor.comum.Protocolo.TAMANHO_FRAGMENTO;

// Recebe os fragmentos e monta o arquivo (upload no servidor, download no cliente).
// Cada fragmento é gravado na posição nº x 1400, então a ordem de chegada não importa.
// O BitSet marca o que já chegou: o duplicado não é gravado de novo, mas quem chama reenvia o ACK,
// porque o ACK anterior pode ter se perdido. No fim o SHA-256 é comparado com o anunciado.
public class ReceptorArquivo implements Closeable {

    public enum Resultado { NOVO, FORA_DE_ORDEM, DUPLICADO, INVALIDO }

    private final Path arquivo;
    private final long tamanho;
    private final int total;
    private final String hashEsperado;
    private final RandomAccessFile saida;
    private final BitSet recebidos;

    private int quantidadeRecebida;
    private long bytesRecebidos;
    private int proximoEsperado;   // menor fragmento que ainda não chegou
    private int duplicados;
    private int foraDeOrdem;
    private boolean fechado;

    public ReceptorArquivo(Path arquivo, long tamanho, int total, String hashEsperado) throws IOException {
        if (tamanho < 0 || total != Util.totalFragmentos(tamanho)) {
            throw new IllegalArgumentException("nº de fragmentos não bate com o tamanho do arquivo");
        }
        this.arquivo = arquivo;
        this.tamanho = tamanho;
        this.total = total;
        this.hashEsperado = hashEsperado;
        this.recebidos = new BitSet(total);
        this.saida = new RandomAccessFile(arquivo.toFile(), "rw");
        saida.setLength(tamanho);
    }

    public synchronized Resultado receber(int fragmento, byte[] dados) throws IOException {
        if (fragmento < 0 || fragmento >= total || dados.length != tamanhoDoFragmento(fragmento)) {
            return Resultado.INVALIDO;
        }
        if (recebidos.get(fragmento)) {
            duplicados++;
            return Resultado.DUPLICADO;
        }
        saida.seek((long) fragmento * TAMANHO_FRAGMENTO);
        saida.write(dados);
        recebidos.set(fragmento);
        quantidadeRecebida++;
        bytesRecebidos += dados.length;

        Resultado resultado = fragmento == proximoEsperado ? Resultado.NOVO : Resultado.FORA_DE_ORDEM;
        if (resultado == Resultado.FORA_DE_ORDEM) {
            foraDeOrdem++;
        }
        proximoEsperado = recebidos.nextClearBit(proximoEsperado);
        return resultado;
    }

    private long tamanhoDoFragmento(int fragmento) {
        if (fragmento < total - 1) {
            return TAMANHO_FRAGMENTO;
        }
        return tamanho - (long) (total - 1) * TAMANHO_FRAGMENTO;
    }

    public synchronized boolean completo() {
        return quantidadeRecebida == total;
    }

    public boolean conferirHash() throws IOException {
        close();
        return Util.sha256(arquivo).equalsIgnoreCase(hashEsperado);
    }

    public synchronized int getRecebidos() {
        return quantidadeRecebida;
    }

    public synchronized long getBytesRecebidos() {
        return bytesRecebidos;
    }

    public synchronized int getDuplicados() {
        return duplicados;
    }

    public synchronized int getForaDeOrdem() {
        return foraDeOrdem;
    }

    public int getTotal() {
        return total;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!fechado) {
            fechado = true;
            saida.close();
        }
    }
}
