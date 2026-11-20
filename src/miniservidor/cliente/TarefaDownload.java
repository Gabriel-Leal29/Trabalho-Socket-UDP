package miniservidor.cliente;

import miniservidor.comum.FalhaProtocolo;
import miniservidor.comum.Mensagem;
import miniservidor.comum.ReceptorArquivo;
import miniservidor.comum.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static miniservidor.comum.Protocolo.*;

// O arquivo é baixado para um .part e só ganha o nome final se o SHA-256 conferir.
public final class TarefaDownload {

    public record Resultado(Path destino, long tamanho, int fragmentos, int duplicados, int foraDeOrdem,
                            long duracaoMs) {
    }

    private TarefaDownload() {
    }

    public static Resultado executar(ConexaoCliente conexao, String nome, Path pastaDestino, OuvinteProgresso ouvinte)
            throws IOException, InterruptedException, FalhaProtocolo {
        int id = conexao.novoId(); // o id do DOWNLOAD vira o id da transferência
        BlockingQueue<Mensagem> fila = conexao.abrirFila(id);
        Path temporario = null;
        try {
            Mensagem meta = conexao.requisitar(Mensagem.download(id, nome), fila);
            if (meta.tipo == ERROR) {
                throw new FalhaProtocolo(meta.codigo, meta.texto);
            }
            if (meta.tamanho < 0 || !fragmentosConferem(meta)) {
                throw new FalhaProtocolo(ERRO_REQUISICAO_INVALIDA, "metadados inválidos recebidos do servidor");
            }
            conexao.enviar(Mensagem.ack(METADATA, id, -1, ""));

            Files.createDirectories(pastaDestino);
            temporario = Files.createTempFile(pastaDestino, ".baixando-", ".part");
            long inicio = System.currentTimeMillis();
            int duplicados;
            int foraDeOrdem;

            try (ReceptorArquivo receptor = new ReceptorArquivo(temporario, meta.tamanho, meta.totalFragmentos, meta.hash)) {
                while (!receptor.completo()) {
                    Mensagem m = fila.poll(TRANSFERENCIA_INATIVA_MS, TimeUnit.MILLISECONDS);
                    if (m == null) {
                        throw new FalhaProtocolo(ERRO_TIMEOUT, "conexão perdida: o servidor parou de enviar fragmentos ("
                                + TRANSFERENCIA_INATIVA_MS / 1000 + " s sem dados)");
                    }
                    if (m.tipo == METADATA) {
                        conexao.enviar(Mensagem.ack(METADATA, id, -1, "")); // o ACK do METADATA se perdeu
                    } else if (m.tipo == ERROR) {
                        throw new FalhaProtocolo(m.codigo, m.texto);
                    } else if (m.tipo == DATA) {
                        ReceptorArquivo.Resultado r = receptor.receber(m.fragmento, m.dados);
                        if (r == ReceptorArquivo.Resultado.INVALIDO) {
                            continue; // sem ACK: o servidor retransmite
                        }
                        if (receptor.completo() && !receptor.conferirHash()) {
                            conexao.enviar(Mensagem.error(id, ERRO_HASH_INVALIDO, "hash SHA-256 não confere no cliente"));
                            throw new FalhaProtocolo(ERRO_HASH_INVALIDO, "arquivo corrompido: hash SHA-256 não confere");
                        }
                        conexao.enviar(Mensagem.ack(DATA, id, m.fragmento, ""));
                        ouvinte.progresso(receptor.getBytesRecebidos(), meta.tamanho);
                    }
                }
                duplicados = receptor.getDuplicados();
                foraDeOrdem = receptor.getForaDeOrdem();
            }

            Path destino = Util.caminhoLivre(pastaDestino, meta.texto); // não sobrescreve download anterior
            Files.move(temporario, destino);
            temporario = null;
            conexao.concluirDownload(id); // continua confirmando fragmentos repetidos por um tempo
            return new Resultado(destino, meta.tamanho, meta.totalFragmentos, duplicados, foraDeOrdem,
                    System.currentTimeMillis() - inicio);
        } finally {
            if (temporario != null) {
                Files.deleteIfExists(temporario);
            }
            conexao.fecharFila(id);
        }
    }

    private static boolean fragmentosConferem(Mensagem meta) {
        try {
            return meta.totalFragmentos == Util.totalFragmentos(meta.tamanho);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
