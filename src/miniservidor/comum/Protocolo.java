package miniservidor.comum;

public final class Protocolo {

    private Protocolo() {
    }

    public static final int PORTA_PADRAO = 5000;

    // Código de cada comando (primeiro byte do datagrama)
    public static final byte REGISTER = 1;
    public static final byte ACK = 2;
    public static final byte LIST = 3;
    public static final byte UPLOAD = 4;
    public static final byte DATA = 5;
    public static final byte DOWNLOAD = 6;
    public static final byte UPDATE_LIST = 7;
    public static final byte ERROR = 8;
    public static final byte METADATA = 9; // resposta do DOWNLOAD: nome, tamanho, nº de fragmentos e hash
    public static final byte PING = 10;    // heartbeat do cliente
    public static final byte BYE = 11;

    public static String nomeDoTipo(byte tipo) {
        return switch (tipo) {
            case REGISTER -> "REGISTER";
            case ACK -> "ACK";
            case LIST -> "LIST";
            case UPLOAD -> "UPLOAD";
            case DATA -> "DATA";
            case DOWNLOAD -> "DOWNLOAD";
            case UPDATE_LIST -> "UPDATE_LIST";
            case ERROR -> "ERROR";
            case METADATA -> "METADATA";
            case PING -> "PING";
            case BYE -> "BYE";
            default -> "DESCONHECIDO(" + tipo + ")";
        };
    }

    // 1400 + 9 bytes de cabeçalho do DATA = 1409 bytes, abaixo dos 1472 que cabem num
    // pacote Ethernet (MTU 1500 - 20 do IP - 8 do UDP). Assim o IP não fragmenta o datagrama.
    public static final int TAMANHO_FRAGMENTO = 1400;

    // O macOS não envia datagramas UDP maiores que 9216 bytes
    public static final int TAMANHO_MAX_DATAGRAMA = 8192;

    public static final int JANELA = 64;
    public static final int TIMEOUT_FRAGMENTO_MS = 250;    // dobra a cada timeout seguido
    public static final int BACKOFF_MAXIMO = 4;            // até no máximo 4x (1 s)
    public static final int MAX_TENTATIVAS_FRAGMENTO = 10; // ~9 s sem resposta
    public static final int ACKS_PARA_RETRANSMISSAO_RAPIDA = 3;

    public static final int TIMEOUT_REQUISICAO_MS = 1000;
    public static final int MAX_TENTATIVAS_REQUISICAO = 5;
    public static final int TIMEOUT_UPDATE_LIST_MS = 500;
    public static final int TRANSFERENCIA_INATIVA_MS = 15_000;

    // Por quanto tempo o receptor lembra de uma transferência já concluída. Se o último ACK
    // se perder, o emissor reenvia o fragmento e recebe o ACK de novo (como o TIME_WAIT do TCP).
    public static final int MEMORIA_CONCLUIDAS_MS = 60_000;

    public static final int INTERVALO_PING_MS = 1_000;
    public static final int SERVIDOR_SILENCIOSO_MS = 6_000; // cliente mostra "conexão perdida"
    public static final int CLIENTE_INATIVO_MS = 10_000;    // servidor remove o cliente

    // Códigos de erro (baseados nos do HTTP)
    public static final short ERRO_REQUISICAO_INVALIDA = 400;
    public static final short ERRO_NAO_REGISTRADO = 401;
    public static final short ERRO_ARQUIVO_INEXISTENTE = 404;
    public static final short ERRO_TIMEOUT = 408;
    public static final short ERRO_TRANSFERENCIA_DESCONHECIDA = 410;
    public static final short ERRO_HASH_INVALIDO = 422;
    public static final short ERRO_INTERNO = 500;
    public static final short ERRO_SERVIDOR_INDISPONIVEL = 503; // gerado no próprio cliente
}
