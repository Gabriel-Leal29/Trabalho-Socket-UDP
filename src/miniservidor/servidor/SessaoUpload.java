package miniservidor.servidor;

import miniservidor.comum.Mensagem;
import miniservidor.comum.ReceptorArquivo;
import miniservidor.comum.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static miniservidor.comum.Protocolo.*;

class SessaoUpload extends Sessao {

    private enum Estado { RECEBENDO, CONCLUIDO, FALHOU }

    private Estado estado = Estado.RECEBENDO;
    private Mensagem respostaUpload; // reenviada se o UPLOAD chegar repetido
    private Mensagem erroFinal;
    private ReceptorArquivo receptor;
    private Path temporario;
    private String nomeFinal;
    private long inicio;

    SessaoUpload(ServidorUdp servidor, Mensagem pedido, String chave) {
        super("upload-" + chave, servidor, pedido, chave);
    }

    @Override
    public void run() {
        RepositorioArquivos repositorio = servidor.getRepositorio();
        try {
            if (!prepararRecepcao(repositorio)) {
                return;
            }
            enviar(respostaUpload);
            processarMensagens();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            estado = Estado.FALHOU;
            erroFinal = Mensagem.error(id, ERRO_INTERNO, "Erro no servidor: " + e.getMessage());
            log("UPLOAD", "ERRO", "falha de leitura/escrita no servidor: " + e.getMessage());
            tentarEnviar(erroFinal);
        } finally {
            encerrar(repositorio);
        }
    }

    private boolean prepararRecepcao(RepositorioArquivos repositorio) throws IOException {
        String nome = Util.sanitizarNome(pedido.texto);
        String problema = null;
        if (nome == null) {
            problema = "nome de arquivo inválido";
        } else if (pedido.tamanho < 0) {
            problema = "tamanho inválido";
        } else if (!fragmentosConferem()) {
            problema = "nº de fragmentos não corresponde ao tamanho";
        } else if (!pedido.hash.matches("[0-9a-fA-F]{64}")) {
            problema = "hash SHA-256 inválido";
        }
        if (problema != null) {
            log("UPLOAD", "ERRO", "pedido recusado (\"" + pedido.texto + "\"): " + problema);
            enviar(Mensagem.error(id, ERRO_REQUISICAO_INVALIDA, "Upload recusado: " + problema));
            return false;
        }

        nomeFinal = repositorio.reservarNome(nome);
        temporario = repositorio.criarTemporario();
        receptor = new ReceptorArquivo(temporario, pedido.tamanho, pedido.totalFragmentos, pedido.hash);
        respostaUpload = Mensagem.ack(UPLOAD, id, -1, nomeFinal);
        inicio = System.currentTimeMillis();

        String duplicado = nomeFinal.equals(nome) ? "" : " — nome já existe, será salvo como \"" + nomeFinal + "\"";
        log("UPLOAD", "INICIO", "\"" + nome + "\" (" + Util.formatarTamanho(pedido.tamanho) + ", "
                + pedido.totalFragmentos + " fragmentos, transferência #" + id + ")" + duplicado);
        return true;
    }

    private boolean fragmentosConferem() {
        try {
            return pedido.totalFragmentos == Util.totalFragmentos(pedido.tamanho);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void processarMensagens() throws IOException, InterruptedException {
        while (estado == Estado.RECEBENDO) {
            Mensagem m = fila.poll(TRANSFERENCIA_INATIVA_MS, TimeUnit.MILLISECONDS);
            if (m == null) {
                estado = Estado.FALHOU;
                erroFinal = Mensagem.error(id, ERRO_TIMEOUT, "Upload abortado: nenhum fragmento chegou em "
                        + TRANSFERENCIA_INATIVA_MS / 1000 + " s");
                log("UPLOAD", "TIMEOUT", "\"" + nomeFinal + "\": nenhum fragmento há " + TRANSFERENCIA_INATIVA_MS / 1000
                        + " s (recebidos " + receptor.getRecebidos() + " de " + receptor.getTotal()
                        + ") — upload descartado");
                return;
            }
            switch (m.tipo) {
                case UPLOAD -> enviar(respostaUpload); // o ACK do UPLOAD se perdeu
                case DATA -> tratarFragmento(m);
                case ERROR -> {
                    estado = Estado.FALHOU;
                    log("UPLOAD", "ERRO", "\"" + nomeFinal + "\": cliente cancelou (" + m.texto + ")");
                }
            }
        }
    }

    private void tratarFragmento(Mensagem m) throws IOException {
        ReceptorArquivo.Resultado resultado = receptor.receber(m.fragmento, m.dados);
        if (resultado == ReceptorArquivo.Resultado.INVALIDO) {
            log("DATA", "ERRO", "upload #" + id + ": fragmento " + m.fragmento + " inválido ("
                    + m.dados.length + " bytes) — descartado sem ACK");
            return;
        }
        if (resultado == ReceptorArquivo.Resultado.DUPLICADO) {
            log("DATA", "DUPLICADO", "upload #" + id + ": fragmento " + m.fragmento
                    + " recebido de novo — ACK reenviado");
        }
        if (receptor.completo()) {
            finalizar();
            if (estado == Estado.FALHOU) {
                enviar(erroFinal); // no lugar do último ACK: arquivo corrompido
                return;
            }
        }
        enviar(Mensagem.ack(DATA, id, m.fragmento, ""));
    }

    private void finalizar() throws IOException {
        if (receptor.conferirHash()) {
            servidor.getRepositorio().efetivar(temporario, nomeFinal, pedido.hash);
            estado = Estado.CONCLUIDO;
            long duracao = System.currentTimeMillis() - inicio;
            log("UPLOAD", "OK", "\"" + nomeFinal + "\" salvo (" + Util.formatarTamanho(pedido.tamanho) + ", "
                    + receptor.getTotal() + " fragmentos, SHA-256 conferido, fora de ordem: " + receptor.getForaDeOrdem()
                    + ", duplicados: " + receptor.getDuplicados() + ", " + duracao + " ms)");
            servidor.arquivosAlterados();
        } else {
            estado = Estado.FALHOU;
            erroFinal = Mensagem.error(id, ERRO_HASH_INVALIDO,
                    "Hash SHA-256 não confere: o arquivo chegou corrompido, envie de novo");
            log("UPLOAD", "ERRO", "\"" + nomeFinal + "\": hash SHA-256 não confere — arquivo descartado");
        }
    }

    private void encerrar(RepositorioArquivos repositorio) {
        if (receptor != null) {
            try {
                receptor.close();
            } catch (IOException ignorada) {
            }
        }
        if (estado != Estado.CONCLUIDO) {
            if (temporario != null) {
                try {
                    Files.deleteIfExists(temporario);
                } catch (IOException ignorada) {
                }
            }
            if (nomeFinal != null) {
                repositorio.liberarNome(nomeFinal);
            }
        }
        if (receptor != null) {
            // guarda o resultado por um tempo, caso o último ACK tenha se perdido (ver ServidorUdp.encaminhar)
            if (estado == Estado.CONCLUIDO) {
                servidor.registrarConcluida(chave, null);
            } else {
                servidor.registrarConcluida(chave, erroFinal != null ? erroFinal
                        : Mensagem.error(id, ERRO_TRANSFERENCIA_DESCONHECIDA, "Upload encerrado"));
            }
        }
        servidor.removerSessao(chave, this);
    }
}
