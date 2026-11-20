package miniservidor.servidor;

import miniservidor.comum.EmissorJanela;
import miniservidor.comum.FalhaProtocolo;
import miniservidor.comum.Mensagem;
import miniservidor.comum.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static miniservidor.comum.Protocolo.*;

// Envia um arquivo: DOWNLOAD -> METADATA (ou ERROR 404) -> ACK -> DATA pela janela deslizante.
class SessaoDownload extends Sessao {

    SessaoDownload(ServidorUdp servidor, Mensagem pedido, String chave) {
        super("download-" + chave, servidor, pedido, chave);
    }

    @Override
    public void run() {
        try {
            Path arquivo = servidor.getRepositorio().localizar(pedido.texto);
            if (arquivo == null) {
                log("DOWNLOAD", "ERRO", "arquivo inexistente: \"" + pedido.texto + "\" — respondido com ERROR 404");
                enviar(Mensagem.error(id, ERRO_ARQUIVO_INEXISTENTE, "Arquivo inexistente no servidor: " + pedido.texto));
                return;
            }
            String nome = arquivo.getFileName().toString();
            long tamanho = Files.size(arquivo);
            int total = Util.totalFragmentos(tamanho);
            String hash = servidor.getRepositorio().hash(arquivo);
            log("DOWNLOAD", "INICIO", "\"" + nome + "\" (" + Util.formatarTamanho(tamanho) + ", "
                    + total + " fragmentos, transferência #" + id + ")");

            if (!enviarMetadados(Mensagem.metadata(id, nome, tamanho, total, hash))) {
                log("DOWNLOAD", "TIMEOUT", "\"" + nome + "\": cliente não confirmou os metadados");
                return;
            }

            EmissorJanela emissor = new EmissorJanela(servidor.getCanal(), cliente, id, arquivo, tamanho, fila,
                    new EmissorJanela.Ouvinte() {
                        @Override
                        public void retransmissao(int fragmento, int tentativa, boolean rapida) {
                            String motivo = rapida ? "retransmissão rápida (3 fragmentos seguintes já confirmados)"
                                    : "timeout sem ACK";
                            log("DATA", "RETRANSMISSAO", "download #" + id + " \"" + nome + "\": fragmento "
                                    + fragmento + ", tentativa " + tentativa + " — " + motivo);
                        }
                    });
            EmissorJanela.Resultado r = emissor.enviar();
            log("DOWNLOAD", "OK", "\"" + nome + "\" enviado (" + Util.formatarTamanho(tamanho) + ", "
                    + r.fragmentos() + " fragmentos, " + r.retransmissoes() + " retransmissões, " + r.duracaoMs() + " ms)");

        } catch (FalhaProtocolo e) {
            String status = e.getCodigo() == ERRO_TIMEOUT ? "TIMEOUT" : "ERRO";
            log("DOWNLOAD", status, "\"" + pedido.texto + "\": " + e.getMessage());
        } catch (IOException e) {
            log("DOWNLOAD", "ERRO", "\"" + pedido.texto + "\": falha de leitura/envio: " + e.getMessage());
            tentarEnviar(Mensagem.error(id, ERRO_INTERNO, "Erro no servidor: " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            servidor.removerSessao(chave, this);
        }
    }

    private boolean enviarMetadados(Mensagem metadados) throws IOException, InterruptedException, FalhaProtocolo {
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_FRAGMENTO; tentativa++) {
            if (tentativa > 1) {
                log("METADATA", "RETRANSMISSAO", "download #" + id + ": sem ACK, tentativa " + tentativa);
            }
            enviar(metadados);
            long limite = System.currentTimeMillis() + TIMEOUT_REQUISICAO_MS;
            long resta;
            while ((resta = limite - System.currentTimeMillis()) > 0) {
                Mensagem m = fila.poll(resta, TimeUnit.MILLISECONDS);
                if (m == null) {
                    break;
                }
                if (m.tipo == ACK && m.tipoConfirmado == METADATA) {
                    return true;
                }
                if (m.tipo == DOWNLOAD) {
                    enviar(metadados); // o cliente repetiu o pedido: o METADATA anterior se perdeu
                }
                if (m.tipo == ERROR) {
                    throw new FalhaProtocolo(m.codigo, "cliente cancelou: " + m.texto);
                }
            }
        }
        return false;
    }
}
