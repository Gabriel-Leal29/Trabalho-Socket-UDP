package miniservidor.cliente;

import miniservidor.comum.EmissorJanela;
import miniservidor.comum.FalhaProtocolo;
import miniservidor.comum.Mensagem;
import miniservidor.comum.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;

import static miniservidor.comum.Protocolo.ERROR;
import static miniservidor.comum.Protocolo.TAMANHO_FRAGMENTO;

public final class TarefaUpload {

    public record Resultado(String nomeFinal, long tamanho, int fragmentos, int retransmissoes, long duracaoMs) {
    }

    private TarefaUpload() {
    }

    public static Resultado executar(ConexaoCliente conexao, Path arquivo, OuvinteProgresso ouvinte)
            throws IOException, InterruptedException, FalhaProtocolo {
        String nome = arquivo.getFileName().toString();
        long tamanho = Files.size(arquivo);
        int total = Util.totalFragmentos(tamanho);
        String hash = Util.sha256(arquivo);

        int id = conexao.novoId(); // o id do UPLOAD vira o id da transferência
        BlockingQueue<Mensagem> fila = conexao.abrirFila(id);
        try {
            Mensagem resposta = conexao.requisitar(Mensagem.upload(id, nome, tamanho, total, hash), fila);
            if (resposta.tipo == ERROR) {
                throw new FalhaProtocolo(resposta.codigo, resposta.texto);
            }
            String nomeFinal = resposta.texto; // o servidor pode ter renomeado

            EmissorJanela emissor = new EmissorJanela(conexao.getCanal(), conexao.getServidor(), id,
                    arquivo, tamanho, fila, new EmissorJanela.Ouvinte() {
                        @Override
                        public void progresso(int confirmados, int totalFragmentos) {
                            ouvinte.progresso(Math.min(tamanho, (long) confirmados * TAMANHO_FRAGMENTO), tamanho);
                        }
                    });
            EmissorJanela.Resultado r = emissor.enviar();
            return new Resultado(nomeFinal, tamanho, r.fragmentos(), r.retransmissoes(), r.duracaoMs());
        } finally {
            conexao.fecharFila(id);
        }
    }
}
