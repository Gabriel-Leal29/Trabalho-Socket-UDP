package miniservidor.servidor;

import miniservidor.comum.Util;

import java.net.InetSocketAddress;

public class ClienteConectado {

    private final String id;
    private final InetSocketAddress endereco;
    private final String enderecoTexto;
    private volatile long ultimoContato = System.currentTimeMillis();

    public ClienteConectado(String id, InetSocketAddress endereco) {
        this.id = id;
        this.endereco = endereco;
        this.enderecoTexto = Util.endereco(endereco);
    }

    public void tocar() {
        ultimoContato = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public InetSocketAddress getEndereco() {
        return endereco;
    }

    public String getEnderecoTexto() {
        return enderecoTexto;
    }

    public long getUltimoContato() {
        return ultimoContato;
    }

    @Override
    public String toString() {
        return id + " (" + enderecoTexto + ")";
    }
}
