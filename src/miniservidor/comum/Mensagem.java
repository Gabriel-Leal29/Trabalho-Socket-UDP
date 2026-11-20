package miniservidor.comum;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static miniservidor.comum.Protocolo.*;

/*
 * Formato dos datagramas: tipo (1 byte) + id (4 bytes) + campos do comando (ver paraBytes).
 * Números em big-endian e textos com writeUTF (2 bytes de tamanho + UTF-8).
 * Em UPLOAD e DOWNLOAD o id do pedido vira o id da transferência, usado depois nos DATA e ACK.
 * O ACK leva o tipo que está confirmando, para um ACK de DATA não ser confundido com outro de mesmo id.
 */
public final class Mensagem {

    public static final int TAMANHO_CABECALHO = 5;

    public final byte tipo;
    public final int id;

    // Cada comando usa só alguns destes campos
    public byte tipoConfirmado;
    public int fragmento = -1;
    public String texto = ""; // id do cliente, nome do arquivo, info do ACK ou mensagem de erro
    public long tamanho;
    public int totalFragmentos;
    public String hash = "";
    public short codigo;
    public int versao;
    public List<InfoArquivo> arquivos = List.of();
    public byte[] dados = new byte[0];

    public InetSocketAddress remetente; // preenchido na recepção, não vai no datagrama

    private Mensagem(byte tipo, int id) {
        this.tipo = tipo;
        this.id = id;
    }

    public static Mensagem register(int id, String idCliente) {
        Mensagem m = new Mensagem(REGISTER, id);
        m.texto = idCliente;
        return m;
    }

    public static Mensagem ack(byte tipoConfirmado, int id, int fragmento, String info) {
        Mensagem m = new Mensagem(ACK, id);
        m.tipoConfirmado = tipoConfirmado;
        m.fragmento = fragmento;
        m.texto = info;
        return m;
    }

    public static Mensagem list(int id) {
        return new Mensagem(LIST, id);
    }

    public static Mensagem upload(int id, String nome, long tamanho, int totalFragmentos, String hash) {
        Mensagem m = new Mensagem(UPLOAD, id);
        m.texto = nome;
        m.tamanho = tamanho;
        m.totalFragmentos = totalFragmentos;
        m.hash = hash;
        return m;
    }

    public static Mensagem data(int idTransferencia, int fragmento, byte[] dados) {
        Mensagem m = new Mensagem(DATA, idTransferencia);
        m.fragmento = fragmento;
        m.dados = dados;
        return m;
    }

    public static Mensagem download(int id, String nome) {
        Mensagem m = new Mensagem(DOWNLOAD, id);
        m.texto = nome;
        return m;
    }

    public static Mensagem updateList(int id, int versao, List<InfoArquivo> arquivos) {
        Mensagem m = new Mensagem(UPDATE_LIST, id);
        m.versao = versao;
        m.arquivos = arquivos;
        return m;
    }

    public static Mensagem error(int id, short codigo, String mensagem) {
        Mensagem m = new Mensagem(ERROR, id);
        m.codigo = codigo;
        m.texto = mensagem;
        return m;
    }

    public static Mensagem metadata(int id, String nome, long tamanho, int totalFragmentos, String hash) {
        Mensagem m = new Mensagem(METADATA, id);
        m.texto = nome;
        m.tamanho = tamanho;
        m.totalFragmentos = totalFragmentos;
        m.hash = hash;
        return m;
    }

    public static Mensagem ping(int id) {
        return new Mensagem(PING, id);
    }

    public static Mensagem bye(int id) {
        return new Mensagem(BYE, id);
    }

    public byte[] paraBytes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + dados.length);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(tipo);
            out.writeInt(id);
            switch (tipo) {
                case REGISTER, DOWNLOAD -> out.writeUTF(texto);
                case ACK -> {
                    out.writeByte(tipoConfirmado);
                    out.writeInt(fragmento);
                    out.writeUTF(texto);
                }
                case UPLOAD, METADATA -> {
                    out.writeUTF(texto);
                    out.writeLong(tamanho);
                    out.writeInt(totalFragmentos);
                    out.writeUTF(hash);
                }
                case DATA -> {
                    out.writeInt(fragmento);
                    out.write(dados);
                }
                case UPDATE_LIST -> {
                    out.writeInt(versao);
                    out.writeInt(arquivos.size());
                    for (InfoArquivo a : arquivos) {
                        out.writeUTF(a.nome());
                        out.writeLong(a.tamanho());
                        out.writeLong(a.modificado());
                    }
                }
                case ERROR -> {
                    out.writeShort(codigo);
                    out.writeUTF(texto);
                }
                default -> {
                    // LIST, PING e BYE só têm o cabeçalho
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static Mensagem deBytes(byte[] buffer, int comprimento) throws IOException {
        if (comprimento < TAMANHO_CABECALHO) {
            throw new IOException("datagrama curto demais (" + comprimento + " bytes)");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(buffer, 0, comprimento));
        byte tipo = in.readByte();
        int id = in.readInt();
        Mensagem m = new Mensagem(tipo, id);
        switch (tipo) {
            case REGISTER, DOWNLOAD -> m.texto = in.readUTF();
            case ACK -> {
                m.tipoConfirmado = in.readByte();
                m.fragmento = in.readInt();
                m.texto = in.readUTF();
            }
            case UPLOAD, METADATA -> {
                m.texto = in.readUTF();
                m.tamanho = in.readLong();
                m.totalFragmentos = in.readInt();
                m.hash = in.readUTF();
            }
            case DATA -> {
                m.fragmento = in.readInt();
                m.dados = in.readAllBytes(); // o resto do datagrama é o payload
            }
            case UPDATE_LIST -> {
                m.versao = in.readInt();
                int quantidade = in.readInt();
                if (quantidade < 0) {
                    throw new IOException("quantidade de arquivos negativa");
                }
                List<InfoArquivo> lista = new ArrayList<>(Math.min(quantidade, 1024));
                for (int i = 0; i < quantidade; i++) {
                    lista.add(new InfoArquivo(in.readUTF(), in.readLong(), in.readLong()));
                }
                m.arquivos = lista;
            }
            case ERROR -> {
                m.codigo = in.readShort();
                m.texto = in.readUTF();
            }
            case LIST, PING, BYE -> {
            }
            default -> throw new IOException("comando desconhecido: " + tipo);
        }
        return m;
    }

    @Override
    public String toString() {
        return nomeDoTipo(tipo) + "#" + id + (fragmento >= 0 ? " fragmento " + fragmento : "");
    }
}
