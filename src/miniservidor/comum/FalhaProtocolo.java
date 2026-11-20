package miniservidor.comum;

public class FalhaProtocolo extends Exception {

    private final short codigo;

    public FalhaProtocolo(short codigo, String mensagem) {
        super(mensagem);
        this.codigo = codigo;
    }

    public short getCodigo() {
        return codigo;
    }
}
